package com.feederp.data.production

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class BatchStatus { PLANNED, IN_PROGRESS, COMPLETED, CANCELLED }

/** QC স্ট্যাটাস (ফিনিশড গুডস লটের সাথে মেলানো): PENDING | HOLD | PASSED | REJECTED */
@Entity(
    tableName = "production_batches",
    indices = [Index(value = ["batchNo"], unique = true), Index("formulaId"), Index("status"), Index("productItemId")],
)
data class ProductionBatchEntity(
    @PrimaryKey val id: String,
    val batchNo: String,
    val formulaId: String,
    val formulaVersion: Int,
    val productItemId: String,
    val isMedicated: Boolean,
    val plannedQtyGrams: Double,
    val sourceWarehouseId: String,
    val fgWarehouseId: String,
    val bagSizeKg: Double = 0.0,          // 0 = লুজ
    val shelfLifeDays: Int = 90,
    val status: BatchStatus = BatchStatus.PLANNED,
    val qcStatus: String = "PENDING",
    val actualInputGrams: Double = 0.0,
    val outputGoodGrams: Double = 0.0,
    val outputBags: Int = 0,
    val wastageGrams: Double = 0.0,
    val yieldPct: Double = 0.0,
    val fgLotId: String? = null,
    val materialCostPaisa: Long = 0L,
    val packagingCostPaisa: Long = 0L,
    val labourCostPaisa: Long = 0L,
    val overheadCostPaisa: Long = 0L,
    val machineCostPaisa: Long = 0L,
    val totalCostPaisa: Long = 0L,
    val costPerKgPaisa: Long = 0L,
    val ledgerVoucherId: String? = null,
    val cleaningRequired: Boolean = false,
    val cleaningDone: Boolean = false,
    val orderedAt: Long,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val mfgAt: Long? = null,
    val expiryAt: Long? = null,
    val note: String? = null,
    val createdBy: String,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** ব্যাচে ব্যবহৃত কাঁচামাল/ওষুধ/প্রিমিক্স/প্যাকেজিং (অ্যাপেন্ড-অনলি; লট ট্রেসেবিলিটি)। */
@Entity(tableName = "batch_consumptions", indices = [Index("batchId"), Index("lotId"), Index("itemId")])
data class BatchConsumptionEntity(
    @PrimaryKey val id: String,
    val batchId: String,
    val itemId: String,
    val lotId: String?,
    val lotBatchNo: String?,
    val qtyGrams: Double,
    val unitCostPaisaPerKg: Long,
    val isTraceable: Boolean,         // ওষুধ/প্রিমিক্স/অ্যাডিটিভ হলে true
    val isPackaging: Boolean = false,
    val stockMovementId: String?,
    val createdBy: String,
    val createdAt: Long,
    val deviceId: String,
)

/** ওষুধযুক্ত ব্যাচের পর লাইন ক্লিনিং চেকলিস্টের আইটেম। */
@Entity(tableName = "cleaning_checklist_items", indices = [Index("batchId")])
data class CleaningChecklistItemEntity(
    @PrimaryKey val id: String,
    val batchId: String,
    val sortOrder: Int,
    val text: String,
    val done: Boolean = false,
    val doneBy: String? = null,
    val doneAt: Long? = null,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/**
 * ব্যাচের জন্য মেশিন রান লগ (অ্যাপেন্ড-অনলি)। machineId হলো Machine এন্টিটির id (প্লেইন String, FK নয়)।
 */
@Entity(tableName = "batch_machine_runs", indices = [Index("batchId"), Index("machineId")])
data class BatchMachineRunEntity(
    @PrimaryKey val id: String,
    val batchId: String,
    val machineId: String,
    val machineName: String? = null,
    val startAt: Long,
    val endAt: Long,
    val runHours: Double,
    val downtimeMinutes: Int = 0,
    val downtimeReason: String? = null,
    val hourlyCostPaisa: Long = 0L,
    val note: String? = null,
    val createdBy: String,
    val createdAt: Long,
    val deviceId: String,
)

@Dao
interface ProductionDao {
    @Query("SELECT * FROM production_batches WHERE deletedAt IS NULL ORDER BY orderedAt DESC")
    fun observeBatches(): Flow<List<ProductionBatchEntity>>

    @Query("SELECT * FROM production_batches WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProductionBatchEntity?

    @Query("SELECT * FROM production_batches WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<ProductionBatchEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBatch(batch: ProductionBatchEntity)

    /** ফিনিশড গুডস লটের ব্যাচ (QC সিদ্ধান্তে ব্যাচের qcStatus মেলানোর জন্য)। */
    @Query("SELECT * FROM production_batches WHERE fgLotId = :lotId AND deletedAt IS NULL LIMIT 1")
    suspend fun batchByFgLot(lotId: String): ProductionBatchEntity?

    /** ফিনিশড গুডস লট থেকে তার উৎপাদন ব্যাচ (QC সিদ্ধান্ত ব্যাচে প্রতিফলিত করতে)। */
    @Query("SELECT * FROM production_batches WHERE fgLotId = :lotId AND deletedAt IS NULL LIMIT 1")
    suspend fun getByFgLot(lotId: String): ProductionBatchEntity?

    @Query(
        "SELECT * FROM production_batches WHERE status = 'COMPLETED' AND deletedAt IS NULL " +
            "AND completedAt BETWEEN :from AND :to ORDER BY completedAt DESC"
    )
    suspend fun completedBetween(from: Long, to: Long): List<ProductionBatchEntity>

    @Query(
        "SELECT COUNT(*) FROM production_batches WHERE isMedicated = 1 AND status = 'COMPLETED' " +
            "AND cleaningDone = 0 AND deletedAt IS NULL"
    )
    suspend fun pendingCleaningCount(): Int

    @Query(
        "SELECT DISTINCT b.* FROM production_batches b JOIN batch_consumptions c ON c.batchId = b.id " +
            "WHERE c.lotId = :lotId AND b.deletedAt IS NULL ORDER BY b.orderedAt DESC"
    )
    suspend fun batchesUsingLot(lotId: String): List<ProductionBatchEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConsumption(row: BatchConsumptionEntity)

    @Query("SELECT * FROM batch_consumptions WHERE batchId = :batchId ORDER BY createdAt")
    suspend fun consumptionOf(batchId: String): List<BatchConsumptionEntity>

    @Query("SELECT * FROM batch_consumptions WHERE batchId = :batchId ORDER BY createdAt")
    fun observeConsumption(batchId: String): Flow<List<BatchConsumptionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCleaningItems(items: List<CleaningChecklistItemEntity>)

    @Query("SELECT * FROM cleaning_checklist_items WHERE batchId = :batchId AND deletedAt IS NULL ORDER BY sortOrder")
    suspend fun cleaningItems(batchId: String): List<CleaningChecklistItemEntity>

    @Query("SELECT * FROM cleaning_checklist_items WHERE batchId = :batchId AND deletedAt IS NULL ORDER BY sortOrder")
    fun observeCleaningItems(batchId: String): Flow<List<CleaningChecklistItemEntity>>

    @Query("SELECT * FROM cleaning_checklist_items WHERE id = :id LIMIT 1")
    suspend fun getCleaningItem(id: String): CleaningChecklistItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCleaningItem(item: CleaningChecklistItemEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(run: BatchMachineRunEntity)

    @Query("SELECT * FROM batch_machine_runs WHERE batchId = :batchId ORDER BY startAt")
    suspend fun runsOf(batchId: String): List<BatchMachineRunEntity>

    @Query("SELECT * FROM batch_machine_runs WHERE batchId = :batchId ORDER BY startAt")
    fun observeRuns(batchId: String): Flow<List<BatchMachineRunEntity>>
}
