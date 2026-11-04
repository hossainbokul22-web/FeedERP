package com.feederp.hr

import androidx.room.withTransaction
import com.feederp.accounts.LedgerRepository
import com.feederp.core.DocNumbers
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.core.Stamper
import com.feederp.core.newId
import com.feederp.data.AppDatabase
import com.feederp.data.hr.AttendanceStatus
import com.feederp.data.hr.EmployeeStatus
import com.feederp.data.hr.EmploymentType
import com.feederp.data.hr.HrAttendanceDao
import com.feederp.data.hr.HrAttendanceEntity
import com.feederp.data.hr.HrBonusDao
import com.feederp.data.hr.HrBonusEntity
import com.feederp.data.hr.HrDeductionDao
import com.feederp.data.hr.HrDeductionEntity
import com.feederp.data.hr.HrEmployeeDao
import com.feederp.data.hr.HrEmployeeEntity
import com.feederp.data.hr.HrHolidayDao
import com.feederp.data.hr.HrLoanDao
import com.feederp.data.hr.HrLoanEntity
import com.feederp.data.hr.HrLoanRepaymentEntity
import com.feederp.data.hr.HrPayrollRunDao
import com.feederp.data.hr.HrPayrollRunEntity
import com.feederp.data.hr.HrPayslipDao
import com.feederp.data.hr.HrPayslipEntity
import com.feederp.data.hr.HrPieceWorkDao
import com.feederp.data.hr.HrPieceWorkEntity
import com.feederp.data.hr.HrSalaryStructureDao
import com.feederp.data.hr.HrSalaryStructureEntity
import com.feederp.data.hr.LoanKind
import com.feederp.data.hr.LoanStatus
import com.feederp.data.hr.PayBasis
import com.feederp.data.hr.PayrollStatus
import com.feederp.data.sales.PayMethods
import com.feederp.inventory.AuditRepository
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class PayrollGenResult(val runId: String, val slips: Int, val skippedNoSalary: List<String>, val employeesWithUnmarkedDays: Int)

/**
 * HR: কর্মী, হাজিরা, বেতন কাঠামো, অগ্রিম/ঋণ, বোনাস/জরিমানা, পিস-রেট কাজ, মাসিক বেতন রান (খসড়া → অনুমোদন → পরিশোধ)।
 * বেতনের হিসাব [PayrollCalc]-এ। লেজার: অনুমোদনে Dr বেতন খরচ / Cr অগ্রিম+বেতন-বাকি; পরিশোধে Dr বেতন-বাকি / Cr নগদ/ব্যাংক।
 * ধরে নেওয়া নিয়ম: সাপ্তাহিক ছুটি শুক্রবার ([WEEKLY_OFF]); মাসের মাঝে যোগ/ছাড়লে মাসিক বেতনে ওই দিনগুলো অনুপস্থিত ধরে আনুপাতিক কাটা হয়।
 * এখনও নেই: ছুটির আবেদন/ব্যালেন্স, প্রশিক্ষণ, কাগজপত্রের স্ক্রিন (ডেটা-টেবিল আছে, লজিক/স্ক্রিন নেই); অনুমোদিত রান পরে বদলানো/রিভার্স হয় না।
 */
@Singleton
class HrRepository @Inject constructor(
    private val db: AppDatabase,
    private val employeeDao: HrEmployeeDao,
    private val attendanceDao: HrAttendanceDao,
    private val holidayDao: HrHolidayDao,
    private val structureDao: HrSalaryStructureDao,
    private val bonusDao: HrBonusDao,
    private val deductionDao: HrDeductionDao,
    private val pieceDao: HrPieceWorkDao,
    private val runDao: HrPayrollRunDao,
    private val slipDao: HrPayslipDao,
    private val loanDao: HrLoanDao,
    private val ledger: LedgerRepository,
    private val docNumbers: DocNumbers,
    private val stamper: Stamper,
    private val session: SessionManager,
    private val audit: AuditRepository,
) {
    companion object { val WEEKLY_OFF: Set<DayOfWeek> = setOf(DayOfWeek.FRIDAY) }

    fun observeEmployees(): Flow<List<HrEmployeeEntity>> = employeeDao.observeAll()
    fun observeRuns(): Flow<List<HrPayrollRunEntity>> = runDao.observeAll()
    fun observeSlips(runId: String): Flow<List<HrPayslipEntity>> = slipDao.observeForRun(runId)
    fun observeLoans(): Flow<List<HrLoanEntity>> = loanDao.observeAll()
    fun observeRepayments(): Flow<List<HrLoanRepaymentEntity>> = loanDao.observeRepayments()
    fun observeDay(dayKey: Int): Flow<List<HrAttendanceEntity>> = attendanceDao.observeDay(dayKey)
    fun observeBonuses(month: Int): Flow<List<HrBonusEntity>> = bonusDao.observeForMonth(month)
    fun observeDeductions(month: Int): Flow<List<HrDeductionEntity>> = deductionDao.observeForMonth(month)
    suspend fun structures(employeeId: String) = structureDao.forEmployee(employeeId)

    // ---------------- কর্মী ----------------

    suspend fun saveEmployee(
        id: String?, name: String, department: String, designation: String, joinDate: Long, type: EmploymentType,
        phone: String?, nid: String?, payMethod: String, payAccount: String?,
    ): String {
        session.require(Permission.HR)
        require(name.isNotBlank()) { "কর্মীর নাম লিখুন" }
        require(department.isNotBlank()) { "বিভাগ লিখুন" }
        require(designation.isNotBlank()) { "পদবি লিখুন" }
        require(payMethod in PayMethods.all) { "বেতন পরিশোধের মাধ্যম ঠিক নয়" }
        if (payMethod != "CASH") require(!payAccount.isNullOrBlank()) { "ব্যাংক/মোবাইল ব্যাংকিং অ্যাকাউন্ট নম্বর দিন" }
        val old = id?.let { employeeDao.getById(it) }
        val eid = id ?: newId()
        val now = stamper.now()
        employeeDao.upsert(
            (old ?: HrEmployeeEntity(
                id = eid, empNo = docNumbers.next("EMP"), name = name, department = department, designation = designation,
                joinDate = joinDate, employmentType = type, updatedAt = now, deviceId = stamper.deviceId,
            )).copy(
                name = name.trim(), department = department.trim(), designation = designation.trim(), joinDate = joinDate,
                employmentType = type, phone = phone?.trim()?.ifBlank { null }, nid = nid?.trim()?.ifBlank { null },
                payMethod = payMethod, payAccount = payAccount?.trim()?.ifBlank { null }, updatedAt = now, deviceId = stamper.deviceId,
            )
        )
        audit.log("SAVE_EMPLOYEE", "hr_employees", eid, name)
        return eid
    }

    suspend fun resign(employeeId: String, reason: String, exitAt: Long) {
        session.require(Permission.HR)
        require(reason.isNotBlank()) { "ছাড়ার কারণ লিখুন" }
        val e = requireNotNull(employeeDao.getById(employeeId)) { "কর্মী পাওয়া যায়নি" }
        require(e.status == EmployeeStatus.ACTIVE) { "কর্মী আগেই সক্রিয় নন" }
        val owed = loanDao.allActive().filter { it.employeeId == employeeId }.sumOf { it.principalPaisa - loanDao.repaidPaisa(it.id) }
        require(owed <= 0) { "এই কর্মীর ${owed / 100} টাকা অগ্রিম/ঋণ বাকি; আগে সমন্বয় করুন (ছাড়ার তারিখ পর্যন্ত বেতন রানে কাটা যাবে)" }
        employeeDao.upsert(e.copy(status = EmployeeStatus.RESIGNED, exitAt = exitAt, exitReason = reason.trim(), updatedAt = stamper.now(), deviceId = stamper.deviceId))
        audit.log("EMPLOYEE_EXIT", "hr_employees", employeeId, reason)
    }

    suspend fun saveSalary(
        employeeId: String, effectiveFrom: Long, basis: PayBasis, basic: Long, house: Long, medical: Long, transport: Long, other: Long,
        otRatePerHour: Long, dailyWage: Long,
    ) {
        session.require(Permission.HR_SALARY)
        requireNotNull(employeeDao.getById(employeeId)) { "কর্মী পাওয়া যায়নি" }
        require(listOf(basic, house, medical, transport, other, otRatePerHour, dailyWage).all { it >= 0 }) { "টাকা ঋণাত্মক হতে পারে না" }
        when (basis) {
            PayBasis.MONTHLY -> require(basic > 0) { "মাসিক বেতনে মূল বেতন ০ এর বেশি হতে হবে" }
            PayBasis.DAILY -> require(dailyWage > 0) { "দৈনিক মজুরি ০ এর বেশি হতে হবে" }
            PayBasis.PIECE -> Unit
        }
        val id = newId()
        structureDao.upsert(
            HrSalaryStructureEntity(
                id = id, employeeId = employeeId, effectiveFrom = effectiveFrom, payBasis = basis, basicPaisa = basic,
                houseAllowancePaisa = house, medicalAllowancePaisa = medical, transportAllowancePaisa = transport,
                otherAllowancePaisa = other, overtimeRatePaisaPerHour = otRatePerHour, dailyWagePaisa = dailyWage,
                updatedAt = stamper.now(), deviceId = stamper.deviceId,
            )
        )
        audit.log("SET_SALARY", "hr_salary_structures", id, "কর্মী $employeeId")
    }

    // ---------------- হাজিরা, পিস-কাজ, বোনাস, কর্তন ----------------

    suspend fun markAttendance(employeeId: String, dayKey: Int, status: AttendanceStatus, lateMinutes: Int, overtimeMinutes: Int, note: String?) {
        session.require(Permission.HR)
        require(lateMinutes >= 0 && overtimeMinutes >= 0) { "মিনিট ঋণাত্মক হতে পারে না" }
        require(dayKey in 20000101..20991231) { "তারিখ ঠিক নয়" }
        requireNotNull(employeeDao.getById(employeeId)) { "কর্মী পাওয়া যায়নি" }
        assertMonthOpen(dayKey / 100)
        val old = attendanceDao.find(employeeId, dayKey)
        attendanceDao.upsert(
            (old ?: HrAttendanceEntity(id = newId(), employeeId = employeeId, dayKey = dayKey, status = status, updatedAt = 0, deviceId = "")).copy(
                status = status, lateMinutes = lateMinutes, overtimeMinutes = overtimeMinutes,
                note = note?.trim()?.ifBlank { null }, deletedAt = null, updatedAt = stamper.now(), deviceId = stamper.deviceId,
            )
        )
    }

    suspend fun addPieceWork(employeeId: String, dayKey: Int, department: String, quantity: Double, ratePaisa: Long, unit: String) {
        session.require(Permission.HR)
        require(quantity.isFinite() && quantity > 0) { "পরিমাণ ০ এর বেশি হতে হবে" }
        require(ratePaisa > 0) { "রেট ০ এর বেশি হতে হবে" }
        requireNotNull(employeeDao.getById(employeeId)) { "কর্মী পাওয়া যায়নি" }
        assertMonthOpen(dayKey / 100)
        val amount = Math.round(quantity * ratePaisa)
        pieceDao.upsert(
            HrPieceWorkEntity(
                id = newId(), employeeId = employeeId, dayKey = dayKey, department = department.trim().ifBlank { "প্রোডাকশন" },
                quantity = quantity, ratePaisaPerUnit = ratePaisa, amountPaisa = amount, unitLabel = unit.ifBlank { "কেজি" },
                updatedAt = stamper.now(), deviceId = stamper.deviceId,
            )
        )
    }

    suspend fun addBonus(employeeId: String, month: Int, title: String, amountPaisa: Long) {
        session.require(Permission.HR_SALARY)
        require(title.isNotBlank()) { "বোনাসের নাম লিখুন" }
        require(amountPaisa > 0) { "অঙ্ক ০ এর বেশি হতে হবে" }
        requireNotNull(employeeDao.getById(employeeId)) { "কর্মী পাওয়া যায়নি" }
        assertMonthOpen(month)
        bonusDao.upsert(HrBonusEntity(newId(), employeeId, month, title.trim(), amountPaisa, stamper.now(), stamper.deviceId))
        audit.log("ADD_BONUS", "hr_bonuses", employeeId, "$month $title $amountPaisa")
    }

    suspend fun addFine(employeeId: String, month: Int, reason: String, amountPaisa: Long) {
        session.require(Permission.HR_SALARY)
        require(reason.isNotBlank()) { "জরিমানার কারণ লিখুন" }
        require(amountPaisa > 0) { "অঙ্ক ০ এর বেশি হতে হবে" }
        requireNotNull(employeeDao.getById(employeeId)) { "কর্মী পাওয়া যায়নি" }
        assertMonthOpen(month)
        deductionDao.upsert(HrDeductionEntity(newId(), employeeId, month, "FINE", reason.trim(), amountPaisa, stamper.now(), stamper.deviceId))
        audit.log("ADD_FINE", "hr_deductions", employeeId, "$month $reason $amountPaisa")
    }

    private suspend fun assertMonthOpen(month: Int) {
        val run = runDao.getByMonth(month)
        require(run == null || run.deletedAt != null || run.status == PayrollStatus.DRAFT) { "এই মাসের বেতন অনুমোদিত হয়ে গেছে; আর বদলানো যায় না" }
    }

    // ---------------- অগ্রিম/ঋণ ----------------

    /** অগ্রিম/ঋণ প্রদান: লেজারে Dr কর্মচারী অগ্রিম / Cr নগদ-ব্যাংক; কিস্তি প্রতি মাসের বেতন থেকে কাটে। */
    suspend fun issueLoan(employeeId: String, kind: LoanKind, principalPaisa: Long, installmentPaisa: Long, method: String, note: String?): String {
        session.require(Permission.HR_SALARY)
        require(principalPaisa > 0) { "অঙ্ক ০ এর বেশি হতে হবে" }
        require(installmentPaisa in 1..principalPaisa) { "মাসিক কিস্তি ০ এর বেশি এবং মূল অঙ্কের বেশি নয়" }
        require(method in PayMethods.all) { "মাধ্যম ঠিক নয়" }
        val e = requireNotNull(employeeDao.getById(employeeId)) { "কর্মী পাওয়া যায়নি" }
        require(e.status == EmployeeStatus.ACTIVE) { "সক্রিয় কর্মীকেই অগ্রিম দেওয়া যায়" }
        val id = newId()
        db.withTransaction {
            val v = ledger.postEmployeeAdvance(id, "${if (kind == LoanKind.LOAN) "ঋণ" else "অগ্রিম"}: ${e.name}", principalPaisa, method)
            loanDao.upsert(
                HrLoanEntity(
                    id = id, employeeId = employeeId, kind = kind, principalPaisa = principalPaisa, installmentPaisa = installmentPaisa,
                    issuedAt = stamper.now(), method = method, voucherId = v, note = note?.trim()?.ifBlank { null },
                    updatedAt = stamper.now(), deviceId = stamper.deviceId,
                )
            )
        }
        audit.log("ISSUE_LOAN", "hr_loans", id, "${e.name} ${kind.name} $principalPaisa")
        return id
    }

    // ---------------- বেতন রান ----------------

    private fun monthRange(month: Int): Pair<Int, Int> = month * 100 + 1 to month * 100 + 31

    private fun startOfDay(dayKey: Int): Long =
        LocalDate.of(dayKey / 10000, dayKey / 100 % 100, dayKey % 100).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun dayKeyOf(epochMs: Long): Int =
        java.time.Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().let { it.year * 10000 + it.monthValue * 100 + it.dayOfMonth }

    /**
     * মাসের খসড়া বেতন রান তৈরি/নতুন করে হিসাব। অনুমোদিত/পরিশোধিত মাসে চলে না। প্রতিবার আগের খসড়া স্লিপ বাদ দিয়ে নতুন করে হয়।
     * বেতন কাঠামো না থাকা কর্মী বাদ পড়ে ([PayrollGenResult.skippedNoSalary])।
     */
    suspend fun generatePayroll(month: Int): PayrollGenResult {
        session.require(Permission.HR_SALARY)
        require(month in 200001..209912 && month % 100 in 1..12) { "মাস ঠিক নয়" }
        val (from, to) = monthRange(month)
        val days = AttendanceCalc.daysInMonth(month)
        val monthStartDay = from; val monthEndDay = month * 100 + days
        val monthEndMs = startOfDay(monthEndDay) + 86_399_999L

        return db.withTransaction {
            val existing = runDao.getByMonth(month)
            require(existing == null || existing.status == PayrollStatus.DRAFT) { "এই মাসের বেতন অনুমোদিত হয়ে গেছে" }
            val now = stamper.now()
            val runId = existing?.id ?: newId()

            val holidays = holidayDao.inRange(from, to).map { it.dayKey }.toSet()
            val attendance = attendanceDao.inRange(from, to).groupBy { it.employeeId }
            val piece = pieceDao.inRange(from, to).groupBy { it.employeeId }
            val bonuses = bonusDao.forMonth(month).filter { it.deletedAt == null }.groupBy { it.employeeId }
            val fines = deductionDao.forMonth(month).filter { it.deletedAt == null }.groupBy { it.employeeId }
            val loansByEmp = loanDao.allActive().groupBy { it.employeeId }
            val oldSlips = slipDao.forRunIncludingDeleted(runId).associateBy { it.employeeId }

            val skipped = ArrayList<String>()
            var unmarkedEmployees = 0
            val slips = ArrayList<HrPayslipEntity>()
            for (emp in employeeDao.all()) {
                if (emp.deletedAt != null) continue
                val joinDay = dayKeyOf(emp.joinDate)
                val exitDay = emp.exitAt?.let { dayKeyOf(it) }
                if (joinDay > monthEndDay) continue                       // এখনও যোগ দেননি
                if (exitDay != null && exitDay < monthStartDay) continue   // আগের মাসেই ছেড়েছেন
                if (emp.status != EmployeeStatus.ACTIVE && exitDay == null) continue

                val st = structureDao.forEmployee(emp.id).filter { it.deletedAt == null && it.effectiveFrom <= monthEndMs }.maxByOrNull { it.effectiveFrom }
                if (st == null) { skipped += emp.name; continue }

                val summary = AttendanceCalc.summarize(
                    month, (attendance[emp.id] ?: emptyList()).map { DayRecord(it.dayKey, it.status, it.lateMinutes, it.overtimeMinutes) },
                    holidays, WEEKLY_OFF, unmarkedAsPresent = st.payBasis == PayBasis.MONTHLY,
                )
                if (summary.unmarkedDays > 0) unmarkedEmployees++
                // মাসের মাঝে যোগ/ছাড়া: মাসিক বেতনে ওই দিনগুলো অনুপস্থিত ধরে আনুপাতিক কাটা (উপস্থিত থেকে সরিয়ে)
                val outside = AttendanceCalc.dayKeys(month).count { it < joinDay || (exitDay != null && it > exitDay) }
                val outsideAdj = if (st.payBasis == PayBasis.MONTHLY) minOf(outside.toDouble(), summary.presentDays) else 0.0

                val loans = loansByEmp[emp.id].orEmpty()
                val dueLoan = loans.filter { it.kind == LoanKind.LOAN }.sumOf { PayrollCalc.installmentDue(it.installmentPaisa, it.principalPaisa - loanDao.repaidPaisa(it.id)) }
                val dueAdv = loans.filter { it.kind == LoanKind.ADVANCE }.sumOf { PayrollCalc.installmentDue(it.installmentPaisa, it.principalPaisa - loanDao.repaidPaisa(it.id)) }

                val f = PayrollCalc.compute(
                    PayInput(
                        basis = st.payBasis, basicPaisa = st.basicPaisa,
                        allowancesPaisa = st.houseAllowancePaisa + st.medicalAllowancePaisa + st.transportAllowancePaisa + st.otherAllowancePaisa,
                        overtimeRatePaisaPerHour = st.overtimeRatePaisaPerHour, dailyWagePaisa = st.dailyWagePaisa, daysInMonth = days,
                        presentDays = summary.presentDays - outsideAdj, paidLeaveDays = summary.paidLeaveDays,
                        absentDays = summary.absentDays + outsideAdj, overtimeMinutes = summary.overtimeMinutes,
                        bonusPaisa = bonuses[emp.id].orEmpty().sumOf { it.amountPaisa },
                        pieceWagePaisa = if (st.payBasis == PayBasis.PIECE) piece[emp.id].orEmpty().sumOf { it.amountPaisa } else 0,
                        fineDeductionPaisa = fines[emp.id].orEmpty().sumOf { it.amountPaisa },
                        loanInstallmentDuePaisa = dueLoan, advanceInstallmentDuePaisa = dueAdv,
                    )
                )
                val holidayCount = AttendanceCalc.dayKeys(month).count { it in holidays }
                slips += HrPayslipEntity(
                    id = oldSlips[emp.id]?.id ?: newId(), runId = runId, employeeId = emp.id, month = month, department = emp.department,
                    payBasis = st.payBasis, daysInMonth = days, workingDays = days - holidayCount, presentDays = summary.presentDays - outsideAdj,
                    paidLeaveDays = summary.paidLeaveDays, absentDays = summary.absentDays + outsideAdj, lateCount = summary.lateCount,
                    overtimeMinutes = summary.overtimeMinutes, basicPaisa = f.basicPaisa, allowancesPaisa = f.allowancesPaisa,
                    overtimePaisa = f.overtimePaisa, bonusPaisa = f.bonusPaisa, pieceWagePaisa = f.pieceWagePaisa, grossPaisa = f.grossPaisa,
                    absentDeductionPaisa = f.absentDeductionPaisa, loanDeductionPaisa = f.loanDeductionPaisa,
                    advanceDeductionPaisa = f.advanceDeductionPaisa, fineDeductionPaisa = f.fineDeductionPaisa,
                    totalDeductionPaisa = f.totalDeductionPaisa, netPaisa = f.netPaisa, payMethod = emp.payMethod,
                    updatedAt = now, deviceId = stamper.deviceId,
                )
            }
            // আগের খসড়ার যেসব স্লিপ এবার নেই সেগুলো সরিয়ে ফেলা (নরম মোছা)
            val keep = slips.map { it.id }.toSet()
            oldSlips.values.filter { it.id !in keep && it.deletedAt == null }.forEach { slipDao.upsert(it.copy(deletedAt = now, updatedAt = now, deviceId = stamper.deviceId)) }
            slips.forEach { slipDao.upsert(it) }
            runDao.upsert(
                (existing ?: HrPayrollRunEntity(id = runId, month = month, updatedAt = now, deviceId = stamper.deviceId)).copy(
                    totalGrossPaisa = slips.sumOf { it.grossPaisa }, totalDeductionPaisa = slips.sumOf { it.totalDeductionPaisa },
                    totalNetPaisa = slips.sumOf { it.netPaisa }, deletedAt = null, updatedAt = now, deviceId = stamper.deviceId,
                )
            )
            PayrollGenResult(runId, slips.size, skipped, unmarkedEmployees)
        }
    }

    /** অনুমোদন (ম্যানেজার): লেজারে বেতন-খরচ পোস্ট, ঋণ/অগ্রিম কিস্তি রেকর্ড। এরপর রান আর বদলানো যায় না। */
    suspend fun approveRun(runId: String) {
        session.require(Permission.HR_SALARY)
        session.require(Permission.APPROVE)
        val userId = session.userId
        db.withTransaction {
            val run = requireNotNull(runDao.getById(runId)) { "বেতন রান পাওয়া যায়নি" }
            require(run.status == PayrollStatus.DRAFT && run.deletedAt == null) { "শুধু খসড়া রান অনুমোদন করা যায়" }
            val slips = slipDao.forRun(runId)
            require(slips.isNotEmpty()) { "রানে কোনো স্লিপ নেই" }
            val expense = slips.sumOf { it.grossPaisa - it.absentDeductionPaisa - it.fineDeductionPaisa }
            val advanceRecovery = slips.sumOf { it.loanDeductionPaisa + it.advanceDeductionPaisa }
            require(expense > 0) { "মোট বেতন খরচ ০; অনুমোদন করার কিছু নেই" }
            val now = stamper.now()
            val activeLoans = loanDao.allActive().groupBy { it.employeeId }
            for (s in slips) {
                for ((kind, amount) in listOf(LoanKind.LOAN to s.loanDeductionPaisa, LoanKind.ADVANCE to s.advanceDeductionPaisa)) {
                    if (amount <= 0) continue
                    val loans = activeLoans[s.employeeId].orEmpty().filter { it.kind == kind }
                    val dues = loans.map { PayrollCalc.installmentDue(it.installmentPaisa, it.principalPaisa - loanDao.repaidPaisa(it.id)) }
                    val parts = PayrollCalc.allocateSequential(dues, amount)
                    check(parts.sum() == amount) { "কিস্তি বণ্টনে গরমিল (${s.employeeId}); বেতন রান আবার হিসাব করুন" }
                    loans.forEachIndexed { i, loan ->
                        if (parts[i] <= 0) return@forEachIndexed
                        loanDao.insertRepayment(HrLoanRepaymentEntity(newId(), loan.id, s.id, parts[i], now, stamper.deviceId, userId))
                        if (loan.principalPaisa - loanDao.repaidPaisa(loan.id) <= 0) {
                            loanDao.upsert(loan.copy(status = LoanStatus.CLEARED, updatedAt = now, deviceId = stamper.deviceId))
                        }
                    }
                }
            }
            val voucher = ledger.postPayrollAccrual(runId, "বেতন ${run.month}", expense, advanceRecovery)
            runDao.upsert(run.copy(status = PayrollStatus.APPROVED, approvedBy = userId, approvedAt = now, accrualVoucherId = voucher, updatedAt = now, deviceId = stamper.deviceId))
        }
        audit.log("APPROVE_PAYROLL", "hr_payroll_runs", runId, null)
    }

    /** পরিশোধ: কর্মীর বেতন-মাধ্যম অনুযায়ী ভাগ করে লেজারে নগদ/ব্যাংক/মোবাইল থেকে বেতন-বাকি শোধ। */
    suspend fun payRun(runId: String) {
        session.require(Permission.HR_SALARY)
        db.withTransaction {
            val run = requireNotNull(runDao.getById(runId)) { "বেতন রান পাওয়া যায়নি" }
            require(run.status == PayrollStatus.APPROVED) { "শুধু অনুমোদিত রান পরিশোধ করা যায়" }
            val slips = slipDao.forRun(runId)
            val now = stamper.now()
            val vouchers = ArrayList<String>()
            slips.groupBy { it.payMethod }.forEach { (method, list) ->
                val sum = list.sumOf { it.netPaisa }
                if (sum > 0) vouchers += ledger.postSalaryPayment(runId, "বেতন পরিশোধ ${run.month} ($method)", sum, method)
            }
            slips.forEach { slipDao.upsert(it.copy(paidAt = now, updatedAt = now, deviceId = stamper.deviceId)) }
            runDao.upsert(run.copy(status = PayrollStatus.PAID, paidAt = now, paymentVoucherIds = vouchers.joinToString(","), updatedAt = now, deviceId = stamper.deviceId))
        }
        audit.log("PAY_PAYROLL", "hr_payroll_runs", runId, null)
    }
}
