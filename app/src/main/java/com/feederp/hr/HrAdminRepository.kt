package com.feederp.hr

import androidx.room.withTransaction
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.core.Stamper
import com.feederp.core.newId
import com.feederp.data.AppDatabase
import com.feederp.data.hr.AttendanceStatus
import com.feederp.data.hr.EmployeeStatus
import com.feederp.data.hr.HrEmployeeDao
import com.feederp.data.hr.HrEmployeeDocDao
import com.feederp.data.hr.HrEmployeeDocEntity
import com.feederp.data.hr.HrHolidayDao
import com.feederp.data.hr.HrHolidayEntity
import com.feederp.data.hr.HrLeaveBalanceDao
import com.feederp.data.hr.HrLeaveBalanceEntity
import com.feederp.data.hr.HrLeaveRequestDao
import com.feederp.data.hr.HrLeaveRequestEntity
import com.feederp.data.hr.HrLeaveTypeDao
import com.feederp.data.hr.HrLeaveTypeEntity
import com.feederp.data.hr.HrTrainingAttendeeEntity
import com.feederp.data.hr.HrTrainingDao
import com.feederp.data.hr.HrTrainingEntity
import com.feederp.data.hr.LeaveStatus
import com.feederp.inventory.AuditRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

data class LeaveBalanceView(val type: HrLeaveTypeEntity, val entitledDays: Double, val committedDays: Double) {
    val remainingDays: Double get() = LeaveCalc.remaining(entitledDays, committedDays)
}

/**
 * HR প্রশাসন: ছুটির ধরন/প্রাপ্য/আবেদন/অনুমোদন, ছুটির ক্যালেন্ডার, প্রশিক্ষণ ও সেফটি সনদ, কর্মীর কাগজপত্র।
 *  - আবেদন HR (Permission.HR) করে; অনুমোদন/বাতিল (Permission.APPROVE) ম্যানেজার/মালিক করে।
 *  - অনুমোদনে প্রতি কার্যদিবসের হাজিরা বসে: সবেতন ছুটি → LEAVE; বিনা বেতনে → ABSENT (বেতন কাটে)। অনুমোদিত বেতন-মাস হলে ব্যর্থ হয়।
 *  - ছুটির সীমা: সবেতন ধরনে প্রাপ্য না থাকলে বা ছাড়ালে আবেদন হয় না; বছর পেরোনো আবেদন নেই (দুটি আলাদা আবেদন করুন)।
 * ডিফল্ট ছুটির ধরনগুলো ([seedDefaults]) বাংলাদেশ শ্রম আইনের সাধারণ ধারণা (নৈমিত্তিক ১০, অসুস্থতা ১৪); নীতি অনুযায়ী যাচাই করে বদলে নিন।
 * সীমা: অনুমোদিত ছুটি পরে বাতিল/উল্টানো যায় না (হাজিরা হাতে ঠিক করুন); ফাইল সংযুক্তি নেই (কাগজের নাম ও মেয়াদ রাখা হয়)।
 */
@Singleton
class HrAdminRepository @Inject constructor(
    private val db: AppDatabase,
    private val employeeDao: HrEmployeeDao,
    private val typeDao: HrLeaveTypeDao,
    private val balanceDao: HrLeaveBalanceDao,
    private val requestDao: HrLeaveRequestDao,
    private val holidayDao: HrHolidayDao,
    private val trainingDao: HrTrainingDao,
    private val docDao: HrEmployeeDocDao,
    private val hr: HrRepository,
    private val stamper: Stamper,
    private val session: SessionManager,
    private val audit: AuditRepository,
) {
    fun observeTypes(): Flow<List<HrLeaveTypeEntity>> = typeDao.observeAll()
    fun observeRequests(): Flow<List<HrLeaveRequestEntity>> = requestDao.observeAll()
    fun observeHolidays(): Flow<List<HrHolidayEntity>> = holidayDao.observeAll()
    fun observeTrainings(): Flow<List<HrTrainingEntity>> = trainingDao.observeAll()
    fun observeDocs(employeeId: String): Flow<List<HrEmployeeDocEntity>> = docDao.observeForEmployee(employeeId)
    suspend fun trainingAttendees(trainingId: String) = trainingDao.attendees(trainingId)
    suspend fun expiringDocs(untilMillis: Long) = docDao.expiringUntil(untilMillis)
    suspend fun expiringCertificates(untilMillis: Long) = trainingDao.certificatesExpiringUntil(untilMillis)

    // ---------------- ছুটির ধরন ও প্রাপ্য ----------------

    suspend fun seedDefaults() {
        session.require(Permission.HR)
        if (typeDao.countAll() > 0) return
        val now = stamper.now()
        listOf(
            Triple("নৈমিত্তিক ছুটি", 10.0, true), Triple("অসুস্থতাজনিত ছুটি", 14.0, true),
            Triple("অর্জিত ছুটি", 0.0, true), Triple("বিনা বেতনে ছুটি", 0.0, false),
        ).forEach { (n, d, paid) -> typeDao.upsert(HrLeaveTypeEntity(newId(), n, d, paid, now, stamper.deviceId)) }
    }

    suspend fun saveLeaveType(id: String?, name: String, defaultDays: Double, paid: Boolean) {
        session.require(Permission.HR)
        require(name.isNotBlank()) { "ছুটির নাম লিখুন" }
        require(defaultDays.isFinite() && defaultDays in 0.0..366.0) { "বার্ষিক দিন ০–৩৬৬ হতে হবে" }
        if (typeDao.all().any { it.name == name.trim() && it.id != id }) throw IllegalArgumentException("এই নামে ছুটির ধরন আছে")
        typeDao.upsert(
            HrLeaveTypeEntity(id ?: newId(), name.trim(), defaultDays, paid, stamper.now(), stamper.deviceId)
        )
    }

    /** সক্রিয় কর্মীদের জন্য [year]-এর প্রাপ্য ছুটি তৈরি (যেটা আগে আছে সেটা বদলায় না)। ফেরত: নতুন কত সারি হলো। */
    suspend fun ensureYearBalances(year: Int): Int {
        session.require(Permission.HR)
        require(year in 2000..2099) { "বছর ঠিক নয়" }
        var n = 0
        db.withTransaction {
            val types = typeDao.all().filter { it.paid && it.defaultAnnualDays > 0 }
            for (e in employeeDao.allActive()) for (t in types) {
                if (balanceDao.find(e.id, t.id, year) != null) continue
                balanceDao.upsert(HrLeaveBalanceEntity(newId(), e.id, t.id, year, t.defaultAnnualDays, stamper.now(), stamper.deviceId))
                n++
            }
        }
        return n
    }

    suspend fun setEntitlement(employeeId: String, leaveTypeId: String, year: Int, days: Double) {
        session.require(Permission.HR)
        require(days.isFinite() && days in 0.0..366.0) { "দিন ০–৩৬৬ হতে হবে" }
        val old = balanceDao.find(employeeId, leaveTypeId, year)
        balanceDao.upsert(
            (old ?: HrLeaveBalanceEntity(newId(), employeeId, leaveTypeId, year, days, 0, ""))
                .copy(entitledDays = days, deletedAt = null, updatedAt = stamper.now(), deviceId = stamper.deviceId)
        )
        audit.log("LEAVE_ENTITLEMENT", "hr_leave_balances", employeeId, "$year $leaveTypeId $days")
    }

    suspend fun balances(employeeId: String, year: Int): List<LeaveBalanceView> {
        val types = typeDao.all().associateBy { it.id }
        val committed = requestDao.activeForEmployee(employeeId).filter { it.fromDay / 10000 == year }.groupBy { it.leaveTypeId }
            .mapValues { (_, l) -> l.sumOf { it.days } }
        return balanceDao.forEmployeeYear(employeeId, year).mapNotNull { b ->
            types[b.leaveTypeId]?.let { LeaveBalanceView(it, b.entitledDays, committed[it.id] ?: 0.0) }
        }
    }

    // ---------------- আবেদন ও অনুমোদন ----------------

    suspend fun requestLeave(employeeId: String, leaveTypeId: String, fromDay: Int, toDay: Int, reason: String?): String {
        session.require(Permission.HR)
        val emp = requireNotNull(employeeDao.getById(employeeId)) { "কর্মী পাওয়া যায়নি" }
        require(emp.status == EmployeeStatus.ACTIVE) { "সক্রিয় কর্মীর ছুটি নেওয়া যায়" }
        val type = requireNotNull(typeDao.getById(leaveTypeId)) { "ছুটির ধরন পাওয়া যায়নি" }
        require(fromDay / 10000 == toDay / 10000) { "এক আবেদন এক বছরের মধ্যে হতে হবে" }
        val holidays = holidayDao.inRange(fromDay, toDay).map { it.dayKey }.toSet()
        val days = LeaveCalc.workingDays(fromDay, toDay, holidays, HrRepository.WEEKLY_OFF)
        require(days.isNotEmpty()) { "এই সময়ে কোনো কার্যদিবস নেই (সব ছুটির দিন)" }
        val existing = requestDao.activeForEmployee(employeeId)
        require(existing.none { LeaveCalc.overlaps(fromDay, toDay, it.fromDay, it.toDay) }) { "এই সময়ে আরেকটি ছুটির আবেদন আছে" }
        if (type.paid) {
            val year = fromDay / 10000
            val bal = balanceDao.find(employeeId, leaveTypeId, year)
            require(bal != null && bal.deletedAt == null) { "${type.name}: এই বছরের প্রাপ্য ঠিক করা নেই" }
            val committed = existing.filter { it.leaveTypeId == leaveTypeId && it.fromDay / 10000 == year }.sumOf { it.days }
            require(LeaveCalc.remaining(bal.entitledDays, committed) >= days.size - 1e-9) {
                "${type.name}: বাকি ${LeaveCalc.remaining(bal.entitledDays, committed)} দিন, চাওয়া ${days.size} দিন"
            }
        }
        val id = newId()
        requestDao.upsert(
            HrLeaveRequestEntity(
                id = id, employeeId = employeeId, leaveTypeId = leaveTypeId, fromDay = fromDay, toDay = toDay,
                days = days.size.toDouble(), reason = reason?.trim()?.ifBlank { null }, requestedBy = session.userId,
                updatedAt = stamper.now(), deviceId = stamper.deviceId,
            )
        )
        audit.log("LEAVE_REQUEST", "hr_leave_requests", id, "${emp.name} ${type.name} ${days.size} দিন")
        return id
    }

    suspend fun decide(requestId: String, approve: Boolean, note: String?) {
        session.require(Permission.APPROVE)
        if (!approve) require(!note.isNullOrBlank()) { "প্রত্যাখ্যানের কারণ লিখুন" }
        db.withTransaction {
            val r = requireNotNull(requestDao.getById(requestId)) { "আবেদন পাওয়া যায়নি" }
            require(r.status == LeaveStatus.PENDING) { "শুধু অপেক্ষমান আবেদনে সিদ্ধান্ত হয়" }
            val now = stamper.now()
            if (approve) {
                val type = requireNotNull(typeDao.getById(r.leaveTypeId)) { "ছুটির ধরন পাওয়া যায়নি" }
                val holidays = holidayDao.inRange(r.fromDay, r.toDay).map { it.dayKey }.toSet()
                val days = LeaveCalc.workingDays(r.fromDay, r.toDay, holidays, HrRepository.WEEKLY_OFF)
                days.forEach {
                    hr.markAttendance(r.employeeId, it, if (type.paid) AttendanceStatus.LEAVE else AttendanceStatus.ABSENT, 0, 0, "ছুটি: ${type.name}")
                }
            }
            requestDao.upsert(
                r.copy(
                    status = if (approve) LeaveStatus.APPROVED else LeaveStatus.REJECTED, decidedBy = session.userId, decidedAt = now,
                    decisionNote = note?.trim()?.ifBlank { null }, updatedAt = now, deviceId = stamper.deviceId,
                )
            )
            audit.log(if (approve) "LEAVE_APPROVE" else "LEAVE_REJECT", "hr_leave_requests", requestId, note)
        }
    }

    suspend fun cancelPending(requestId: String) {
        session.require(Permission.HR)
        val r = requireNotNull(requestDao.getById(requestId)) { "আবেদন পাওয়া যায়নি" }
        require(r.status == LeaveStatus.PENDING) { "শুধু অপেক্ষমান আবেদন বাতিল করা যায়" }
        requestDao.upsert(r.copy(status = LeaveStatus.CANCELLED, updatedAt = stamper.now(), deviceId = stamper.deviceId))
    }

    // ---------------- ছুটির ক্যালেন্ডার ----------------

    suspend fun saveHoliday(dayKey: Int, name: String) {
        session.require(Permission.HR)
        require(name.isNotBlank()) { "ছুটির নাম লিখুন" }
        LeaveCalc.toDate(dayKey)   // অবৈধ তারিখে ব্যতিক্রম
        val old = holidayDao.findByDay(dayKey)
        holidayDao.upsert(
            (old ?: HrHolidayEntity(newId(), dayKey, name.trim(), 0, "")).copy(name = name.trim(), deletedAt = null, updatedAt = stamper.now(), deviceId = stamper.deviceId)
        )
        audit.log("HOLIDAY_SAVE", "hr_holidays", null, "$dayKey $name")
    }

    suspend fun deleteHoliday(dayKey: Int) {
        session.require(Permission.HR)
        val old = holidayDao.findByDay(dayKey) ?: return
        holidayDao.upsert(old.copy(deletedAt = stamper.now(), updatedAt = stamper.now(), deviceId = stamper.deviceId))
    }

    // ---------------- প্রশিক্ষণ ও সনদ ----------------

    suspend fun saveTraining(title: String, kind: String, conductedAt: Long, hours: Double, trainer: String?, note: String?): String {
        session.require(Permission.HR)
        require(title.isNotBlank()) { "শিরোনাম লিখুন" }
        require(kind == "TRAINING" || kind == "SAFETY") { "ধরন ঠিক নয়" }
        require(hours.isFinite() && hours >= 0) { "ঘণ্টা ঋণাত্মক হতে পারে না" }
        val id = newId()
        trainingDao.upsert(
            HrTrainingEntity(id, title.trim(), kind, conductedAt, hours, trainer?.trim()?.ifBlank { null }, note?.trim()?.ifBlank { null }, stamper.now(), stamper.deviceId)
        )
        return id
    }

    suspend fun addAttendee(trainingId: String, employeeId: String, certificateExpiryAt: Long?) {
        session.require(Permission.HR)
        requireNotNull(employeeDao.getById(employeeId)) { "কর্মী পাওয়া যায়নি" }
        val old = trainingDao.findAttendee(trainingId, employeeId)
        trainingDao.upsertAttendee(
            (old ?: HrTrainingAttendeeEntity(newId(), trainingId, employeeId, null, 0, "")).copy(
                certificateExpiryAt = certificateExpiryAt, deletedAt = null, updatedAt = stamper.now(), deviceId = stamper.deviceId,
            )
        )
    }

    // ---------------- কর্মীর কাগজপত্র ----------------

    suspend fun saveDoc(employeeId: String, title: String, note: String?, expiryAt: Long?) {
        session.require(Permission.HR)
        require(title.isNotBlank()) { "কাগজের নাম লিখুন" }
        requireNotNull(employeeDao.getById(employeeId)) { "কর্মী পাওয়া যায়নি" }
        docDao.upsert(HrEmployeeDocEntity(newId(), employeeId, title.trim(), note?.trim()?.ifBlank { null }, expiryAt, stamper.now(), stamper.deviceId))
    }
}
