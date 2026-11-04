package com.feederp.maintenance

import androidx.room.withTransaction
import com.feederp.core.DocNumbers
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.core.Stamper
import com.feederp.core.newId
import com.feederp.data.AppDatabase
import com.feederp.data.maintenance.ApprovalStatus
import com.feederp.data.maintenance.MachineDao
import com.feederp.data.maintenance.MachineEntity
import com.feederp.data.maintenance.MachineRunLogDao
import com.feederp.data.maintenance.MachineStatus
import com.feederp.data.maintenance.MachineType
import com.feederp.data.maintenance.MaintenanceScheduleDao
import com.feederp.data.maintenance.MaintenanceScheduleEntity
import com.feederp.data.maintenance.ScheduleBasis
import com.feederp.data.maintenance.WorkOrderChecklistDao
import com.feederp.data.maintenance.WorkOrderChecklistItemEntity
import com.feederp.data.maintenance.WorkOrderDao
import com.feederp.data.maintenance.WorkOrderEntity
import com.feederp.data.maintenance.WorkOrderPriority
import com.feederp.data.maintenance.WorkOrderStatus
import com.feederp.data.maintenance.WorkOrderType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * রক্ষণাবেক্ষণ: মেশিন, ওয়ার্ক অর্ডার (ব্রেকডাউন/প্রিভেন্টিভ), অনুমোদন, প্রিভেন্টিভ শিডিউল।
 * এখানে নেই (পরে): স্পেয়ার পার্টস ইস্যু (স্টক), মিটার রিডিং, ক্যালিব্রেশন, গাড়ির সার্ভিস, মাসিক ডেপ্রিসিয়েশন পোস্টিং।
 * ওয়ার্ক অর্ডারের খরচ এখানে শুধু রেকর্ড হয়, লেজারে যায় না — আলাদা "খরচ" এন্ট্রি দিলে দ্বিগুণ গোনা এড়াতে খেয়াল রাখুন।
 */
@Singleton
class MaintenanceRepository @Inject constructor(
    private val db: AppDatabase,
    private val machineDao: MachineDao,
    private val runLogDao: MachineRunLogDao,
    private val woDao: WorkOrderDao,
    private val checklistDao: WorkOrderChecklistDao,
    private val scheduleDao: MaintenanceScheduleDao,
    private val docNumbers: DocNumbers,
    private val stamper: Stamper,
    private val session: SessionManager,
    private val audit: com.feederp.inventory.AuditRepository,
) {
    fun observeMachines(): Flow<List<MachineEntity>> = machineDao.observeAll()
    fun observeWorkOrders(): Flow<List<WorkOrderEntity>> = woDao.observeAll()
    fun observeWorkOrder(id: String): Flow<WorkOrderEntity?> = woDao.observeById(id)
    fun observeChecklist(woId: String): Flow<List<WorkOrderChecklistItemEntity>> = checklistDao.observeByWorkOrder(woId)
    fun observeSchedules(): Flow<List<MaintenanceScheduleEntity>> = scheduleDao.observeAll()

    // ---------------- মেশিন ----------------

    suspend fun saveMachine(
        id: String?, code: String, name: String, type: MachineType, capacityKgPerHour: Double,
        purchaseDate: Long, purchaseCostPaisa: Long, usefulLifeMonths: Int, location: String?,
    ): String {
        session.require(Permission.MAINTENANCE)
        require(code.isNotBlank()) { "মেশিনের কোড দিন" }
        require(name.isNotBlank()) { "মেশিনের নাম দিন" }
        require(capacityKgPerHour >= 0 && capacityKgPerHour.isFinite()) { "ক্ষমতা সঠিক নয়" }
        require(purchaseCostPaisa >= 0) { "ক্রয়মূল্য ঋণাত্মক হতে পারে না" }
        require(usefulLifeMonths in 1..1200) { "আয়ুষ্কাল ১–১২০০ মাসের মধ্যে হতে হবে" }
        val old = id?.let { machineDao.getById(it) }
        val mid = old?.id ?: newId()
        val dup = machineDao.all().firstOrNull { it.code.equals(code.trim(), true) && it.id != mid && it.deletedAt == null }
        require(dup == null) { "এই কোডে আরেকটি মেশিন আছে" }
        machineDao.upsert(
            (old ?: MachineEntity(
                id = mid, code = code.trim(), name = name.trim(), type = type, purchaseDate = purchaseDate,
                updatedAt = stamper.now(), deviceId = stamper.deviceId,
            )).copy(
                code = code.trim(), name = name.trim(), type = type, capacityKgPerHour = capacityKgPerHour,
                purchaseDate = purchaseDate, purchaseCostPaisa = purchaseCostPaisa, usefulLifeMonths = usefulLifeMonths,
                location = location?.trim()?.ifBlank { null }, updatedAt = stamper.now(), deviceId = stamper.deviceId,
            )
        )
        audit.log("SAVE_MACHINE", "machines", mid, "${code.trim()} ${name.trim()}")
        return mid
    }

    suspend fun retireMachine(machineId: String) {
        session.require(Permission.APPROVE)
        val m = requireNotNull(machineDao.getById(machineId)) { "মেশিন পাওয়া যায়নি" }
        machineDao.upsert(m.copy(status = MachineStatus.RETIRED, updatedAt = stamper.now(), deviceId = stamper.deviceId))
        audit.log("RETIRE_MACHINE", "machines", machineId, m.name)
    }

    // ---------------- ওয়ার্ক অর্ডার ----------------

    /** যে কেউ (MAINTENANCE_REPORT) ব্রেকডাউন জানাতে পারে; মেশিন "ব্রেকডাউন" অবস্থায় যায়। */
    suspend fun reportBreakdown(machineId: String, title: String, description: String?, priority: WorkOrderPriority): String {
        session.require(Permission.MAINTENANCE_REPORT)
        require(title.isNotBlank()) { "সমস্যার সংক্ষিপ্ত বিবরণ লিখুন" }
        return createWo(machineId, WorkOrderType.BREAKDOWN, priority, title, description, null, null, null)
    }

    suspend fun createWorkOrder(
        machineId: String, type: WorkOrderType, priority: WorkOrderPriority, title: String, description: String?,
        assignedTo: String?, dueAt: Long?,
    ): String {
        session.require(Permission.MAINTENANCE)
        require(title.isNotBlank()) { "কাজের শিরোনাম লিখুন" }
        return createWo(machineId, type, priority, title, description, assignedTo, dueAt, null)
    }

    private suspend fun createWo(
        machineId: String, type: WorkOrderType, priority: WorkOrderPriority, title: String, description: String?,
        assignedTo: String?, dueAt: Long?, scheduleId: String?, checklistText: String = "",
    ): String {
        val userId = session.userId
        val machine = machineDao.getById(machineId)
        require(machine != null && machine.deletedAt == null && machine.status != MachineStatus.RETIRED) { "মেশিন পাওয়া যায়নি বা অবসরপ্রাপ্ত" }
        val id = newId()
        val no = docNumbers.next("WO")
        db.withTransaction {
            val now = stamper.now()
            woDao.upsert(
                WorkOrderEntity(
                    id = id, woNo = no, machineId = machineId, type = type, priority = priority, title = title.trim(),
                    description = description?.trim()?.ifBlank { null }, reportedBy = userId, assignedTo = assignedTo,
                    reportedAt = now, dueAt = dueAt, scheduleId = scheduleId, updatedAt = now, deviceId = stamper.deviceId,
                )
            )
            val steps = MaintenanceCalc.parseChecklist(checklistText)
            if (steps.isNotEmpty()) {
                checklistDao.upsertAll(steps.mapIndexed { i, t ->
                    WorkOrderChecklistItemEntity(newId(), id, t, i, updatedAt = now, deviceId = stamper.deviceId)
                })
            }
            if (type == WorkOrderType.BREAKDOWN && machine.status == MachineStatus.ACTIVE) {
                machineDao.upsert(machine.copy(status = MachineStatus.BREAKDOWN, updatedAt = now, deviceId = stamper.deviceId))
            }
        }
        audit.log("CREATE_WORK_ORDER", "work_orders", id, "$no ${type.name} $title")
        return id
    }

    suspend fun changeStatus(woId: String, to: WorkOrderStatus) {
        session.require(Permission.MAINTENANCE)
        require(to != WorkOrderStatus.DONE && to != WorkOrderStatus.CLOSED) { "সম্পন্ন/বন্ধ করতে নির্ধারিত বোতাম ব্যবহার করুন" }
        db.withTransaction {
            val wo = requireNotNull(woDao.getById(woId)) { "ওয়ার্ক অর্ডার পাওয়া যায়নি" }
            require(MaintenanceCalc.canTransition(wo.status, to)) { "এই অবস্থা থেকে সরাসরি এখানে যাওয়া যায় না" }
            val now = stamper.now()
            woDao.upsert(
                wo.copy(
                    status = to, startedAt = wo.startedAt ?: if (to == WorkOrderStatus.IN_PROGRESS) now else null,
                    assignedTo = wo.assignedTo ?: if (to == WorkOrderStatus.IN_PROGRESS) session.userId else null,
                    updatedAt = now, deviceId = stamper.deviceId,
                )
            )
            if (to == WorkOrderStatus.IN_PROGRESS) {
                machineDao.getById(wo.machineId)?.takeIf { it.status == MachineStatus.BREAKDOWN }?.let {
                    machineDao.upsert(it.copy(status = MachineStatus.UNDER_MAINTENANCE, updatedAt = now, deviceId = stamper.deviceId))
                }
            }
        }
    }

    suspend fun toggleChecklist(itemId: String) {
        session.require(Permission.MAINTENANCE)
        val it = requireNotNull(checklistDao.getById(itemId)) { "ধাপ পাওয়া যায়নি" }
        val now = stamper.now()
        checklistDao.upsert(
            it.copy(
                done = !it.done, doneBy = if (!it.done) session.userId else null, doneAt = if (!it.done) now else null,
                updatedAt = now, deviceId = stamper.deviceId,
            )
        )
    }

    /** কাজ সম্পন্ন: কারণ ও নেওয়া ব্যবস্থা বাধ্যতামূলক; খরচ সীমা (৳৫,০০০) ছাড়ালে ম্যানেজারের অনুমোদন লাগবে। */
    suspend fun complete(woId: String, rootCause: String, actionTaken: String, partsPaisa: Long, laborPaisa: Long, otherPaisa: Long) {
        session.require(Permission.MAINTENANCE)
        require(actionTaken.isNotBlank()) { "কী কাজ করা হয়েছে লিখুন" }
        require(partsPaisa >= 0 && laborPaisa >= 0 && otherPaisa >= 0) { "খরচ ঋণাত্মক হতে পারে না" }
        db.withTransaction {
            val wo = requireNotNull(woDao.getById(woId)) { "ওয়ার্ক অর্ডার পাওয়া যায়নি" }
            require(wo.status == WorkOrderStatus.IN_PROGRESS) { "শুধু 'চলমান' কাজ সম্পন্ন করা যায়" }
            if (wo.type == WorkOrderType.BREAKDOWN) require(rootCause.isNotBlank()) { "ব্রেকডাউনে মূল কারণ লিখুন" }
            val open = checklistDao.byWorkOrder(woId).count { !it.done && it.deletedAt == null }
            require(open == 0) { "চেকলিস্টের $open টি ধাপ বাকি; আগে সব টিক দিন" }
            val now = stamper.now()
            val total = MaintenanceCalc.totalCost(partsPaisa, laborPaisa, otherPaisa)
            val downtime = if (wo.type == WorkOrderType.BREAKDOWN) MaintenanceCalc.breakdownDowntimeMinutes(wo.reportedAt, now, now) else wo.downtimeMinutes
            woDao.upsert(
                wo.copy(
                    status = WorkOrderStatus.DONE, completedAt = now, rootCause = rootCause.trim().ifBlank { null },
                    actionTaken = actionTaken.trim(), partsCostPaisa = partsPaisa, laborCostPaisa = laborPaisa,
                    otherCostPaisa = otherPaisa, costPaisa = total, downtimeMinutes = downtime,
                    approvalStatus = MaintenanceCalc.nextApprovalStatus(wo.approvalStatus, total, MaintenanceCalc.DEFAULT_APPROVAL_LIMIT_PAISA),
                    updatedAt = now, deviceId = stamper.deviceId,
                )
            )
            restoreMachineIfClear(wo, now)
        }
        audit.log("COMPLETE_WORK_ORDER", "work_orders", woId, null)
    }

    private suspend fun restoreMachineIfClear(wo: WorkOrderEntity, now: Long) {
        val m = machineDao.getById(wo.machineId) ?: return
        if ((m.status == MachineStatus.BREAKDOWN || m.status == MachineStatus.UNDER_MAINTENANCE) &&
            woDao.openBreakdownCount(wo.machineId, wo.id) == 0
        ) machineDao.upsert(m.copy(status = MachineStatus.ACTIVE, updatedAt = now, deviceId = stamper.deviceId))
    }

    suspend fun approve(woId: String) = decide(woId, true, null)
    suspend fun reject(woId: String, reason: String) = decide(woId, false, reason)

    private suspend fun decide(woId: String, ok: Boolean, reason: String?) {
        session.require(Permission.APPROVE)
        if (!ok) require(!reason.isNullOrBlank()) { "প্রত্যাখ্যানের কারণ লিখুন" }
        val wo = requireNotNull(woDao.getById(woId)) { "ওয়ার্ক অর্ডার পাওয়া যায়নি" }
        require(wo.approvalStatus == ApprovalStatus.PENDING) { "অনুমোদনের অপেক্ষায় নেই" }
        woDao.upsert(
            wo.copy(
                approvalStatus = if (ok) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED,
                approvedBy = session.userId, safetyNote = if (ok) wo.safetyNote else "প্রত্যাখ্যান: ${reason!!.trim()}",
                updatedAt = stamper.now(), deviceId = stamper.deviceId,
            )
        )
        audit.log(if (ok) "APPROVE_WORK_ORDER" else "REJECT_WORK_ORDER", "work_orders", woId, reason)
    }

    /** বন্ধ: সম্পন্ন + (প্রয়োজনে) অনুমোদিত হতে হবে। প্রিভেন্টিভ হলে শিডিউলের "শেষ করা" হালনাগাদ হয়। */
    suspend fun close(woId: String) {
        session.require(Permission.MAINTENANCE)
        db.withTransaction {
            val wo = requireNotNull(woDao.getById(woId)) { "ওয়ার্ক অর্ডার পাওয়া যায়নি" }
            require(wo.status == WorkOrderStatus.DONE) { "আগে কাজ সম্পন্ন করুন" }
            require(wo.approvalStatus != ApprovalStatus.PENDING) { "খরচ সীমার বেশি; ম্যানেজারের অনুমোদন বাকি" }
            require(wo.approvalStatus != ApprovalStatus.REJECTED) { "খরচ প্রত্যাখ্যাত; খরচ ঠিক করে আবার সম্পন্ন করুন" }
            val now = stamper.now()
            woDao.upsert(wo.copy(status = WorkOrderStatus.CLOSED, closedAt = now, updatedAt = now, deviceId = stamper.deviceId))
            wo.scheduleId?.let { sid ->
                scheduleDao.getById(sid)?.let { s ->
                    scheduleDao.upsert(
                        s.copy(
                            lastDoneAt = now, lastDoneRunHours = currentRunHours(wo.machineId), openWorkOrderId = null,
                            updatedAt = now, deviceId = stamper.deviceId,
                        )
                    )
                }
            }
        }
        audit.log("CLOSE_WORK_ORDER", "work_orders", woId, null)
    }

    // ---------------- প্রিভেন্টিভ শিডিউল ----------------

    suspend fun saveSchedule(
        machineId: String, title: String, basis: ScheduleBasis, intervalHours: Double, intervalDays: Int, checklist: String,
    ): String {
        session.require(Permission.MAINTENANCE)
        require(title.isNotBlank()) { "শিডিউলের নাম দিন" }
        if (basis == ScheduleBasis.HOURS) require(intervalHours > 0 && intervalHours.isFinite()) { "ঘণ্টার ব্যবধান ০ এর বেশি দিন" }
        else require(intervalDays > 0) { "দিনের ব্যবধান ০ এর বেশি দিন" }
        require(machineDao.getById(machineId) != null) { "মেশিন পাওয়া যায়নি" }
        val id = newId()
        val now = stamper.now()
        scheduleDao.upsert(
            MaintenanceScheduleEntity(
                id = id, machineId = machineId, title = title.trim(), basis = basis, intervalHours = intervalHours,
                intervalDays = intervalDays, checklist = checklist, baselineAt = now,
                lastDoneRunHours = currentRunHours(machineId), updatedAt = now, deviceId = stamper.deviceId,
            )
        )
        return id
    }

    /** সময় বা ঘণ্টা হয়ে আসা শিডিউলের জন্য প্রিভেন্টিভ ওয়ার্ক অর্ডার তৈরি (আগের টি খোলা থাকলে নতুন হয় না)। ফেরত: কয়টি তৈরি। */
    suspend fun generateDuePreventive(): Int {
        session.require(Permission.MAINTENANCE)
        var created = 0
        val now = System.currentTimeMillis()
        for (s in scheduleDao.activeAll()) {
            if (s.openWorkOrderId != null || s.deletedAt != null) continue
            val machine = machineDao.getById(s.machineId) ?: continue
            if (machine.status == MachineStatus.RETIRED) continue
            val next = MaintenanceCalc.nextService(
                s.basis, s.intervalHours, s.intervalDays, s.baselineAt, s.lastDoneAt, s.lastDoneRunHours, currentRunHours(s.machineId), now,
            )
            if (!next.isDueForGeneration) continue
            val woId = createWo(
                s.machineId, WorkOrderType.PREVENTIVE, WorkOrderPriority.NORMAL, s.title, "স্বয়ংক্রিয় প্রিভেন্টিভ রক্ষণাবেক্ষণ",
                null, next.dueAt, s.id, s.checklist,
            )
            scheduleDao.upsert(s.copy(openWorkOrderId = woId, updatedAt = stamper.now(), deviceId = stamper.deviceId))
            created++
        }
        return created
    }

    private suspend fun currentRunHours(machineId: String): Double {
        val m = machineDao.getById(machineId) ?: return 0.0
        return MaintenanceCalc.totalRunHours(m.openingRunHours, runLogDao.totalRunMinutes(machineId), 0.0)
    }
}
