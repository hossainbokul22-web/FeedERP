package com.feederp.data.maintenance

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class MachineType {
    GRINDER, MIXER, PELLET_MILL, COOLER, DRYER, BOILER, PACKING, GENERATOR, WEIGHING_SCALE, DOSING_SYSTEM, OTHER
}

enum class MachineStatus { ACTIVE, BREAKDOWN, UNDER_MAINTENANCE, RETIRED }

enum class DepreciationMethod { STRAIGHT_LINE, REDUCING_BALANCE }

enum class ScheduleBasis { HOURS, DATE }

enum class WorkOrderType { PREVENTIVE, BREAKDOWN, CALIBRATION, IMPROVEMENT }

enum class WorkOrderPriority { LOW, NORMAL, HIGH, CRITICAL }

enum class WorkOrderStatus { OPEN, IN_PROGRESS, WAITING_PARTS, DONE, CLOSED }

enum class ApprovalStatus { NOT_REQUIRED, PENDING, APPROVED, REJECTED }

enum class RequisitionStatus { REQUESTED, ISSUED, PURCHASE_REQUESTED, CANCELLED }

enum class PurchaseRequestStatus { OPEN, ORDERED, CLOSED, CANCELLED }

enum class MeterType { ELECTRICITY, WATER, FUEL, GREASE, LUBRICANT_OIL }

@Entity(tableName = "machines", indices = [Index("code", unique = true), Index("status")])
data class MachineEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val type: MachineType,
    val capacityKgPerHour: Double = 0.0,
    val purchaseDate: Long,
    val purchaseCostPaisa: Long = 0L,
    val salvageValuePaisa: Long = 0L,
    val usefulLifeMonths: Int = 120,
    val depreciationMethod: DepreciationMethod = DepreciationMethod.STRAIGHT_LINE,
    val location: String? = null,
    val status: MachineStatus = MachineStatus.ACTIVE,
    val openingRunHours: Double = 0.0,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** অ্যাপেন্ড-অনলি: মেশিন চালুর ইতিহাস। */
@Entity(
    tableName = "machine_run_logs",
    indices = [Index("machineId"), Index("batchId"), Index("startAt")],
)
data class MachineRunLogEntity(
    @PrimaryKey val id: String,
    val machineId: String,
    val batchId: String? = null,
    val startAt: Long,
    val endAt: Long,
    val runMinutes: Int,
    val downtimeMinutes: Int = 0,
    val downtimeReason: String? = null,
    val outputGrams: Double = 0.0,
    val createdAt: Long,
    val deviceId: String,
    val createdBy: String,
)

@Entity(tableName = "maintenance_schedules", indices = [Index("machineId"), Index("active")])
data class MaintenanceScheduleEntity(
    @PrimaryKey val id: String,
    val machineId: String,
    val title: String,
    val basis: ScheduleBasis,
    val intervalHours: Double = 0.0,
    val intervalDays: Int = 0,
    /** চেকলিস্ট: প্রতি লাইনে একটি ধাপ */
    val checklist: String = "",
    val baselineAt: Long,
    val lastDoneAt: Long? = null,
    val lastDoneRunHours: Double = 0.0,
    val openWorkOrderId: String? = null,
    val active: Boolean = true,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "work_orders",
    indices = [Index("machineId"), Index("status"), Index("type"), Index("reportedAt"), Index("scheduleId"), Index("woNo", unique = true)],
)
data class WorkOrderEntity(
    @PrimaryKey val id: String,
    val woNo: String,
    val machineId: String,
    val type: WorkOrderType,
    val priority: WorkOrderPriority = WorkOrderPriority.NORMAL,
    val title: String,
    val description: String? = null,
    val reportedBy: String,
    val assignedTo: String? = null,
    val status: WorkOrderStatus = WorkOrderStatus.OPEN,
    val reportedAt: Long,
    val dueAt: Long? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val closedAt: Long? = null,
    val rootCause: String? = null,
    val actionTaken: String? = null,
    val photoPath: String? = null,
    val scheduleId: String? = null,
    val downtimeMinutes: Int = 0,
    val partsCostPaisa: Long = 0L,
    val laborCostPaisa: Long = 0L,
    val otherCostPaisa: Long = 0L,
    val costPaisa: Long = 0L,
    val approvalStatus: ApprovalStatus = ApprovalStatus.NOT_REQUIRED,
    val approvedBy: String? = null,
    val safetyNote: String? = null,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(tableName = "work_order_checklist_items", indices = [Index("workOrderId")])
data class WorkOrderChecklistItemEntity(
    @PrimaryKey val id: String,
    val workOrderId: String,
    val text: String,
    val sortOrder: Int,
    val done: Boolean = false,
    val doneBy: String? = null,
    val doneAt: Long? = null,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "spare_part_requisitions",
    indices = [Index("workOrderId"), Index("status"), Index("itemId"), Index("reqNo", unique = true)],
)
data class SparePartRequisitionEntity(
    @PrimaryKey val id: String,
    val reqNo: String,
    val workOrderId: String,
    val machineId: String,
    val itemId: String,
    val warehouseId: String,
    val qtyGrams: Double,
    val status: RequisitionStatus = RequisitionStatus.REQUESTED,
    val unitCostPaisaPerKg: Long = 0L,
    val totalCostPaisa: Long = 0L,
    val requestedBy: String,
    val issuedBy: String? = null,
    val issuedAt: Long? = null,
    val note: String? = null,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** স্টকে না থাকলে Purchase বিভাগের জন্য অনুরোধ। */
@Entity(tableName = "maint_purchase_requests", indices = [Index("status"), Index("itemId")])
data class MaintPurchaseRequestEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val qtyGrams: Double,
    val workOrderId: String? = null,
    val requisitionId: String? = null,
    val status: PurchaseRequestStatus = PurchaseRequestStatus.OPEN,
    val note: String? = null,
    val requestedBy: String,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** অ্যাপেন্ড-অনলি */
@Entity(tableName = "calibration_logs", indices = [Index("machineId"), Index("createdAt")])
data class CalibrationLogEntity(
    @PrimaryKey val id: String,
    val machineId: String,
    val instrument: String,
    val referenceGrams: Double,
    val measuredGrams: Double,
    val tolerancePct: Double,
    val deviationPct: Double,
    val passed: Boolean,
    val certificateNo: String? = null,
    val nextDueAt: Long? = null,
    val workOrderId: String? = null,
    val note: String? = null,
    val createdAt: Long,
    val deviceId: String,
    val createdBy: String,
)

/** অ্যাপেন্ড-অনলি: বিদ্যুৎ/পানি/জ্বালানি মিটার (ক্রমযোজিত রিডিং) বা গ্রিজ/তেল (ব্যবহৃত পরিমাণ)। */
@Entity(tableName = "meter_readings", indices = [Index("meterType"), Index("machineId"), Index("readingAt")])
data class MeterReadingEntity(
    @PrimaryKey val id: String,
    val meterType: MeterType,
    val machineId: String? = null,
    val reading: Double,
    val unit: String,
    val readingAt: Long,
    val note: String? = null,
    val createdAt: Long,
    val deviceId: String,
    val createdBy: String,
)

/** গাড়ির এন্টিটি Agent F এর; এখানে vehicleId শুধু String। */
@Entity(tableName = "vehicle_service_reminders", indices = [Index("vehicleId"), Index("active")])
data class VehicleServiceReminderEntity(
    @PrimaryKey val id: String,
    val vehicleId: String,
    val title: String,
    val intervalKm: Int = 0,
    val intervalDays: Int = 0,
    val lastServiceKm: Int = 0,
    val lastServiceAt: Long? = null,
    val baselineAt: Long,
    val active: Boolean = true,
    val note: String? = null,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** অ্যাপেন্ড-অনলি: মাসিক ডেপ্রিসিয়েশন পোস্টিং; (machineId, period) অনন্য। */
@Entity(
    tableName = "machine_depreciation_entries",
    indices = [Index(value = ["machineId", "period"], unique = true)],
)
data class MachineDepreciationEntryEntity(
    @PrimaryKey val id: String,
    val machineId: String,
    val period: String,          // "yyyy-MM"
    val amountPaisa: Long,
    val voucherId: String? = null,
    val createdAt: Long,
    val deviceId: String,
    val createdBy: String,
)

// ---------------------------------------------------------------- DAOs

@Dao
interface MachineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(m: MachineEntity)

    @Query("SELECT * FROM machines WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MachineEntity?

    @Query("SELECT * FROM machines WHERE deletedAt IS NULL ORDER BY code")
    fun observeAll(): Flow<List<MachineEntity>>

    @Query("SELECT * FROM machines WHERE deletedAt IS NULL ORDER BY code")
    suspend fun all(): List<MachineEntity>
}

@Dao
interface MachineRunLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(log: MachineRunLogEntity)

    @Query("SELECT * FROM machine_run_logs WHERE machineId = :machineId ORDER BY startAt DESC LIMIT 200")
    fun observeByMachine(machineId: String): Flow<List<MachineRunLogEntity>>

    @Query("SELECT COALESCE(SUM(runMinutes), 0) FROM machine_run_logs WHERE machineId = :machineId")
    suspend fun totalRunMinutes(machineId: String): Long

    @Query("SELECT * FROM machine_run_logs WHERE machineId = :machineId AND startAt >= :from AND startAt < :to")
    suspend fun inRange(machineId: String, from: Long, to: Long): List<MachineRunLogEntity>
}

@Dao
interface MaintenanceScheduleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: MaintenanceScheduleEntity)

    @Query("SELECT * FROM maintenance_schedules WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MaintenanceScheduleEntity?

    @Query("SELECT * FROM maintenance_schedules WHERE deletedAt IS NULL ORDER BY title")
    fun observeAll(): Flow<List<MaintenanceScheduleEntity>>

    @Query("SELECT * FROM maintenance_schedules WHERE deletedAt IS NULL AND active = 1")
    suspend fun activeAll(): List<MaintenanceScheduleEntity>
}

@Dao
interface WorkOrderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(w: WorkOrderEntity)

    @Query("SELECT * FROM work_orders WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WorkOrderEntity?

    @Query("SELECT * FROM work_orders WHERE id = :id AND deletedAt IS NULL")
    fun observeById(id: String): Flow<WorkOrderEntity?>

    @Query("SELECT * FROM work_orders WHERE deletedAt IS NULL ORDER BY reportedAt DESC")
    fun observeAll(): Flow<List<WorkOrderEntity>>

    @Query("SELECT * FROM work_orders WHERE deletedAt IS NULL AND machineId = :machineId AND reportedAt >= :from AND reportedAt < :to")
    suspend fun forMachineInRange(machineId: String, from: Long, to: Long): List<WorkOrderEntity>

    @Query(
        "SELECT COUNT(*) FROM work_orders WHERE deletedAt IS NULL AND machineId = :machineId AND type = 'BREAKDOWN' " +
            "AND status IN ('OPEN','IN_PROGRESS','WAITING_PARTS') AND id <> :exceptId"
    )
    suspend fun openBreakdownCount(machineId: String, exceptId: String): Int
}

@Dao
interface WorkOrderChecklistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<WorkOrderChecklistItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WorkOrderChecklistItemEntity)

    @Query("SELECT * FROM work_order_checklist_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WorkOrderChecklistItemEntity?

    @Query("SELECT * FROM work_order_checklist_items WHERE workOrderId = :woId AND deletedAt IS NULL ORDER BY sortOrder")
    fun observeByWorkOrder(woId: String): Flow<List<WorkOrderChecklistItemEntity>>

    @Query("SELECT * FROM work_order_checklist_items WHERE workOrderId = :woId AND deletedAt IS NULL ORDER BY sortOrder")
    suspend fun byWorkOrder(woId: String): List<WorkOrderChecklistItemEntity>
}

@Dao
interface SparePartRequisitionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: SparePartRequisitionEntity)

    @Query("SELECT * FROM spare_part_requisitions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SparePartRequisitionEntity?

    @Query("SELECT * FROM spare_part_requisitions WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SparePartRequisitionEntity>>

    @Query("SELECT * FROM spare_part_requisitions WHERE deletedAt IS NULL AND workOrderId = :woId ORDER BY updatedAt DESC")
    fun observeByWorkOrder(woId: String): Flow<List<SparePartRequisitionEntity>>

    @Query("SELECT * FROM spare_part_requisitions WHERE deletedAt IS NULL AND workOrderId = :woId")
    suspend fun byWorkOrder(woId: String): List<SparePartRequisitionEntity>
}

@Dao
interface MaintPurchaseRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: MaintPurchaseRequestEntity)

    @Query("SELECT * FROM maint_purchase_requests WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MaintPurchaseRequestEntity>>
}

@Dao
interface CalibrationLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(c: CalibrationLogEntity)

    @Query("SELECT * FROM calibration_logs ORDER BY createdAt DESC LIMIT 300")
    fun observeRecent(): Flow<List<CalibrationLogEntity>>
}

@Dao
interface MeterReadingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(m: MeterReadingEntity)

    @Query("SELECT * FROM meter_readings ORDER BY readingAt DESC LIMIT 300")
    fun observeRecent(): Flow<List<MeterReadingEntity>>

    /** সর্বশেষ আগের রিডিং (একই ধরন ও একই মেশিন/সাধারণ মিটার)। */
    @Query(
        "SELECT * FROM meter_readings WHERE meterType = :type AND COALESCE(machineId, '') = :machineKey " +
            "AND readingAt <= :before ORDER BY readingAt DESC LIMIT 1"
    )
    suspend fun lastBefore(type: MeterType, machineKey: String, before: Long): MeterReadingEntity?
}

@Dao
interface VehicleServiceReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: VehicleServiceReminderEntity)

    @Query("SELECT * FROM vehicle_service_reminders WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): VehicleServiceReminderEntity?

    @Query("SELECT * FROM vehicle_service_reminders WHERE deletedAt IS NULL ORDER BY vehicleId, title")
    fun observeAll(): Flow<List<VehicleServiceReminderEntity>>
}

@Dao
interface MachineDepreciationEntryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(e: MachineDepreciationEntryEntity)

    @Query("SELECT COUNT(*) FROM machine_depreciation_entries WHERE machineId = :machineId AND period = :period")
    suspend fun countFor(machineId: String, period: String): Int

    @Query("SELECT COALESCE(SUM(amountPaisa), 0) FROM machine_depreciation_entries WHERE machineId = :machineId")
    suspend fun accumulatedPaisa(machineId: String): Long
}
