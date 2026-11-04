package com.feederp.ui.hr

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.data.hr.AttendanceStatus
import com.feederp.data.hr.EmployeeStatus
import com.feederp.data.hr.EmploymentType
import com.feederp.data.hr.HrAttendanceEntity
import com.feederp.data.hr.HrBonusEntity
import com.feederp.data.hr.HrDeductionEntity
import com.feederp.data.hr.HrEmployeeEntity
import com.feederp.data.hr.HrLoanEntity
import com.feederp.data.hr.HrPayrollRunEntity
import com.feederp.data.hr.HrPayslipEntity
import com.feederp.data.hr.LoanKind
import com.feederp.data.hr.LoanStatus
import com.feederp.data.hr.PayBasis
import com.feederp.data.hr.PayrollStatus
import com.feederp.hr.HrRepository
import com.feederp.hr.PayrollGenResult
import com.feederp.ui.master.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

// ---------- সাধারণ সহায়ক ----------

fun employmentTypeBn(t: EmploymentType) = when (t) {
    EmploymentType.PERMANENT -> "স্থায়ী"; EmploymentType.TEMPORARY -> "অস্থায়ী"
    EmploymentType.DAILY -> "দৈনিক"; EmploymentType.CONTRACT -> "চুক্তিভিত্তিক"
}

fun employeeStatusBn(s: EmployeeStatus) = when (s) {
    EmployeeStatus.ACTIVE -> "সক্রিয়"; EmployeeStatus.RESIGNED -> "ইস্তফা"; EmployeeStatus.TERMINATED -> "বরখাস্ত"
}

fun attendanceBn(s: AttendanceStatus) = when (s) {
    AttendanceStatus.PRESENT -> "উপস্থিত"; AttendanceStatus.ABSENT -> "অনুপস্থিত"
    AttendanceStatus.LEAVE -> "ছুটি"; AttendanceStatus.HOLIDAY -> "ছুটির দিন"
}

fun payBasisBn(b: PayBasis) = when (b) {
    PayBasis.MONTHLY -> "মাসিক"; PayBasis.DAILY -> "দৈনিক মজুরি"; PayBasis.PIECE -> "পিস-রেট"
}

fun payrollStatusBn(s: PayrollStatus) = when (s) {
    PayrollStatus.DRAFT -> "খসড়া"; PayrollStatus.APPROVED -> "অনুমোদিত"; PayrollStatus.PAID -> "পরিশোধিত"
}

fun loanKindBn(k: LoanKind) = if (k == LoanKind.LOAN) "ঋণ" else "অগ্রিম"

fun payMethodBn(m: String) = when (m) { "BANK" -> "ব্যাংক"; "MOBILE_BANKING" -> "মোবাইল ব্যাংকিং"; else -> "নগদ" }

fun takaText(paisa: Long): String = String.format(java.util.Locale.US, "%.2f", paisa / 100.0)

fun monthLabel(month: Int): String = "${month / 100}-${(month % 100).toString().padStart(2, '0')}"

fun dayKeyOf(epochMs: Long): Int =
    java.time.Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().let { it.year * 10000 + it.monthValue * 100 + it.dayOfMonth }

fun currentMonth(): Int = LocalDate.now().let { it.year * 100 + it.monthValue }

/** আগের ১২ মাস + চলতি + পরের মাস (নতুন→পুরোনো)। */
fun monthOptions(): List<Int> {
    val now = LocalDate.now().withDayOfMonth(1)
    return (1 downTo -12).map { now.plusMonths(it.toLong()).let { d -> d.year * 100 + d.monthValue } }
}

// ---------- কর্মী ----------

@HiltViewModel
class EmployeeListViewModel @Inject constructor(repo: HrRepository, private val session: SessionManager) : BaseViewModel() {
    val employees: StateFlow<List<HrEmployeeEntity>> = repo.observeEmployees().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val canEdit: Boolean get() = session.can(Permission.HR)
}

data class EmployeeEditState(val employee: HrEmployeeEntity? = null, val salaryNote: String = "")

@HiltViewModel
class EmployeeEditViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: HrRepository,
    private val session: SessionManager,
) : BaseViewModel() {
    /** "new" = নতুন কর্মী। */
    val employeeId: String? = handle.get<String>("id")?.takeIf { it != "new" }
    val canSalary: Boolean get() = session.can(Permission.HR_SALARY)
    val canEdit: Boolean get() = session.can(Permission.HR)

    val employee: StateFlow<HrEmployeeEntity?> =
        repo.observeEmployees().map { l -> l.firstOrNull { it.id == employeeId } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(
        name: String, department: String, designation: String, joinDate: Long?, type: EmploymentType,
        phone: String, nid: String, payMethod: String, payAccount: String, onSaved: (String) -> Unit,
    ) = launchSafe {
        val id = repo.saveEmployee(
            employeeId, name, department, designation, joinDate ?: throw IllegalArgumentException("যোগদানের তারিখ দিন"),
            type, phone, nid, payMethod, payAccount,
        )
        onSaved(id)
    }

    fun resign(reason: String, exitAt: Long?, onDone: () -> Unit) = launchSafe(onDone) {
        repo.resign(employeeId ?: throw IllegalArgumentException("আগে কর্মী সংরক্ষণ করুন"), reason, exitAt ?: System.currentTimeMillis())
    }

    fun saveSalary(
        basis: PayBasis, effectiveFrom: Long?, basic: String, house: String, medical: String, transport: String,
        other: String, otRate: String, dailyWage: String, onDone: () -> Unit,
    ) = launchSafe(onDone) {
        fun p(t: String, label: String): Long = if (t.isBlank()) 0L
        else (com.feederp.ui.master.parseTakaToPaisa(t) ?: throw IllegalArgumentException("$label: সংখ্যা ঠিক নয়"))
        repo.saveSalary(
            employeeId ?: throw IllegalArgumentException("আগে কর্মী সংরক্ষণ করুন"),
            effectiveFrom ?: throw IllegalArgumentException("কার্যকরের তারিখ দিন"), basis,
            p(basic, "মূল বেতন"), p(house, "বাসা ভাড়া"), p(medical, "চিকিৎসা"), p(transport, "যাতায়াত"),
            p(other, "অন্যান্য ভাতা"), p(otRate, "ওভারটাইম রেট"), p(dailyWage, "দৈনিক মজুরি"),
        )
    }
}

// ---------- হাজিরা ----------

data class AttendanceRow(val employee: HrEmployeeEntity, val record: HrAttendanceEntity?)

@HiltViewModel
class AttendanceViewModel @Inject constructor(private val repo: HrRepository, private val session: SessionManager) : BaseViewModel() {
    private val _day = MutableStateFlow(dayKeyOf(System.currentTimeMillis()))
    val day: StateFlow<Int> = _day.asStateFlow()
    fun setDay(epochMs: Long) { _day.value = dayKeyOf(epochMs) }
    val canEdit: Boolean get() = session.can(Permission.HR)

    val rows: StateFlow<List<AttendanceRow>> = _day.flatMapLatest { d ->
        combine(repo.observeEmployees(), repo.observeDay(d)) { emps, recs ->
            val byEmp = recs.filter { it.deletedAt == null }.associateBy { it.employeeId }
            emps.filter { it.status == EmployeeStatus.ACTIVE }.map { AttendanceRow(it, byEmp[it.id]) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun mark(employeeId: String, status: AttendanceStatus, lateMin: String, otMin: String) = launchSafe {
        repo.markAttendance(employeeId, _day.value, status, lateMin.toIntOrNull() ?: 0, otMin.toIntOrNull() ?: 0, null)
    }
}

// ---------- বেতন রান ----------

@HiltViewModel
class PayrollListViewModel @Inject constructor(private val repo: HrRepository, private val session: SessionManager) : BaseViewModel() {
    val runs: StateFlow<List<HrPayrollRunEntity>> = repo.observeRuns().map { l -> l.filter { it.deletedAt == null }.sortedByDescending { it.month } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val canGenerate: Boolean get() = session.can(Permission.HR_SALARY)

    fun generate(month: Int, onDone: (String) -> Unit) = launchSafe {
        val r: PayrollGenResult = repo.generatePayroll(month)
        val notes = buildList {
            if (r.skippedNoSalary.isNotEmpty()) add("বেতন কাঠামো নেই (বাদ গেছে): ${r.skippedNoSalary.joinToString()}")
            if (r.employeesWithUnmarkedDays > 0) add("${r.employeesWithUnmarkedDays} জনের কিছু দিনের হাজিরা তোলা নেই (মাসিক বেতনভোগী হলে উপস্থিত ধরা হয়েছে)")
        }
        if (notes.isNotEmpty()) showMessage("${r.slips}টি স্লিপ তৈরি হয়েছে।\n" + notes.joinToString("\n"))
        onDone(r.runId)
    }
}

data class SlipRow(val slip: HrPayslipEntity, val name: String, val empNo: String)

data class RunState(val run: HrPayrollRunEntity? = null, val slips: List<SlipRow> = emptyList())

@HiltViewModel
class PayrollRunViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: HrRepository,
    private val session: SessionManager,
) : BaseViewModel() {
    private val runId: String = requireNotNull(handle.get<String>("id"))
    val canApprove: Boolean get() = session.can(Permission.HR_SALARY) && session.can(Permission.APPROVE)
    val canPay: Boolean get() = session.can(Permission.HR_SALARY)

    val state: StateFlow<RunState> = combine(repo.observeRuns(), repo.observeSlips(runId), repo.observeEmployees()) { runs, slips, emps ->
        val names = emps.associateBy { it.id }
        RunState(
            runs.firstOrNull { it.id == runId },
            slips.filter { it.deletedAt == null }.map { SlipRow(it, names[it.employeeId]?.name ?: "?", names[it.employeeId]?.empNo ?: "") }
                .sortedBy { it.empNo },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RunState())

    fun regenerate() = launchSafe { state.value.run?.let { repo.generatePayroll(it.month) } }
    fun approve() = launchSafe { repo.approveRun(runId) }
    fun pay() = launchSafe { repo.payRun(runId) }
}

// ---------- অগ্রিম/ঋণ ----------

data class LoanRow(val loan: HrLoanEntity, val name: String, val repaidPaisa: Long) {
    val balancePaisa: Long get() = loan.principalPaisa - repaidPaisa
}

@HiltViewModel
class LoansViewModel @Inject constructor(private val repo: HrRepository, private val session: SessionManager) : BaseViewModel() {
    val canIssue: Boolean get() = session.can(Permission.HR_SALARY)

    val rows: StateFlow<List<LoanRow>> = combine(repo.observeLoans(), repo.observeRepayments(), repo.observeEmployees()) { loans, reps, emps ->
        val names = emps.associate { it.id to it.name }
        val repaid = reps.groupBy { it.loanId }.mapValues { (_, l) -> l.sumOf { it.amountPaisa } }
        loans.filter { it.deletedAt == null }.sortedByDescending { it.issuedAt }
            .map { LoanRow(it, names[it.employeeId] ?: "?", repaid[it.id] ?: 0L) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeEmployees: StateFlow<List<HrEmployeeEntity>> = repo.observeEmployees().map { l -> l.filter { it.status == EmployeeStatus.ACTIVE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun issue(employeeId: String?, kind: LoanKind, amount: String, installment: String, method: String, note: String, onDone: () -> Unit) = launchSafe(onDone) {
        val a = com.feederp.ui.master.parseTakaToPaisa(amount) ?: throw IllegalArgumentException("অঙ্ক ঠিক নয়")
        val i = com.feederp.ui.master.parseTakaToPaisa(installment) ?: throw IllegalArgumentException("কিস্তির অঙ্ক ঠিক নয়")
        repo.issueLoan(employeeId ?: throw IllegalArgumentException("কর্মী বাছাই করুন"), kind, a, i, method, note)
    }
}

// ---------- বোনাস / জরিমানা / পিস-কাজ ----------

data class AdjustRow(val label: String, val name: String, val title: String, val amountPaisa: Long)

@HiltViewModel
class AdjustmentsViewModel @Inject constructor(private val repo: HrRepository, private val session: SessionManager) : BaseViewModel() {
    private val _month = MutableStateFlow(currentMonth())
    val month: StateFlow<Int> = _month.asStateFlow()
    fun setMonth(m: Int) { _month.value = m }
    val canSalary: Boolean get() = session.can(Permission.HR_SALARY)
    val canHr: Boolean get() = session.can(Permission.HR)

    val employees: StateFlow<List<HrEmployeeEntity>> = repo.observeEmployees().map { l -> l.filter { it.status == EmployeeStatus.ACTIVE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rows: StateFlow<List<AdjustRow>> = _month.flatMapLatest { m ->
        combine(repo.observeBonuses(m), repo.observeDeductions(m), repo.observeEmployees()) { b: List<HrBonusEntity>, d: List<HrDeductionEntity>, emps ->
            val names = emps.associate { it.id to it.name }
            b.filter { it.deletedAt == null }.map { AdjustRow("বোনাস", names[it.employeeId] ?: "?", it.title, it.amountPaisa) } +
                d.filter { it.deletedAt == null }.map { AdjustRow("জরিমানা", names[it.employeeId] ?: "?", it.reason, it.amountPaisa) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun taka(t: String): Long = com.feederp.ui.master.parseTakaToPaisa(t) ?: throw IllegalArgumentException("অঙ্ক ঠিক নয়")

    fun addBonus(employeeId: String?, title: String, amount: String, onDone: () -> Unit) = launchSafe(onDone) {
        repo.addBonus(employeeId ?: throw IllegalArgumentException("কর্মী বাছাই করুন"), _month.value, title, taka(amount))
    }

    fun addFine(employeeId: String?, reason: String, amount: String, onDone: () -> Unit) = launchSafe(onDone) {
        repo.addFine(employeeId ?: throw IllegalArgumentException("কর্মী বাছাই করুন"), _month.value, reason, taka(amount))
    }

    fun addPiece(employeeId: String?, dayMs: Long?, dept: String, qty: String, rate: String, unit: String, onDone: () -> Unit) = launchSafe(onDone) {
        val q = com.feederp.ui.master.parseNum(qty) ?: throw IllegalArgumentException("পরিমাণ ঠিক নয়")
        repo.addPieceWork(
            employeeId ?: throw IllegalArgumentException("কর্মী বাছাই করুন"), dayKeyOf(dayMs ?: System.currentTimeMillis()), dept, q, taka(rate), unit,
        )
    }
}
