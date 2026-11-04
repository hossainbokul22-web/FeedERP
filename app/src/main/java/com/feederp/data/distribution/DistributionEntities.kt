package com.feederp.data.distribution

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** ডিস্ট্রিবিউশন মডিউলের স্ট্যাটাস ও ধরনের স্থির মান। */
object DistStatus {
    // ডেলিভারি নোট
    const val NOTE_PENDING = "PENDING"
    const val NOTE_PARTIAL = "PARTIAL"
    const val NOTE_DELIVERED = "DELIVERED"
    // ট্রিপ
    const val TRIP_PLANNED = "PLANNED"
    const val TRIP_LOADED = "LOADED"
    const val TRIP_ONROAD = "ONROAD"
    const val TRIP_COMPLETED = "COMPLETED"
    const val TRIP_CANCELLED = "CANCELLED"
    // স্টপ
    const val STOP_PENDING = "PENDING"
    const val STOP_DELIVERED = "DELIVERED"
    const val STOP_FAILED = "FAILED"
    // গাড়ির মালিকানা
    const val OWN = "OWN"
    const val HIRED = "HIRED"
    // ফেরতের ধরন
    const val RET_RETURN = "RETURN"
    const val RET_DAMAGED = "DAMAGED"
}

/** পরিবহন খরচের ধরন। */
object CostKinds {
    const val FUEL = "FUEL"
    const val ALLOWANCE = "ALLOWANCE"
    const val TOLL = "TOLL"
    const val HIRED_VEHICLE = "HIRED_VEHICLE"
    const val OTHER = "OTHER"
    val all = listOf(FUEL, ALLOWANCE, TOLL, HIRED_VEHICLE, OTHER)
    fun labelBn(kind: String): String = when (kind) {
        FUEL -> "জ্বালানি"
        ALLOWANCE -> "ড্রাইভার ভাতা"
        TOLL -> "টোল"
        HIRED_VEHICLE -> "ভাড়া গাড়ির বিল"
        else -> "অন্যান্য"
    }
}

@Entity(tableName = "territories", indices = [Index("name")])
data class TerritoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val code: String? = null,
    val note: String? = null,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** ডিলার/কাস্টমারের এলাকা, ডেলিভারি ঠিকানা ও লোকেশন। `id` = partyId (প্রতি পার্টিতে একটি)। */
@Entity(tableName = "dealer_areas", indices = [Index("partyId"), Index("territoryId")])
data class DealerAreaEntity(
    @PrimaryKey val id: String,
    val partyId: String,
    val territoryId: String? = null,
    val deliveryAddress: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(tableName = "vehicles", indices = [Index("regNo")])
data class VehicleEntity(
    @PrimaryKey val id: String,
    val regNo: String,
    val type: String,                      // ট্রাক, পিকআপ, ভ্যান ...
    val capacityGrams: Double,
    val ownership: String = DistStatus.OWN, // OWN | HIRED
    val ownerName: String? = null,          // ভাড়া গাড়ির মালিক
    val fitnessExpiryAt: Long? = null,
    val insuranceExpiryAt: Long? = null,
    val taxTokenExpiryAt: Long? = null,
    val active: Boolean = true,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(tableName = "drivers", indices = [Index("name")])
data class DriverEntity(
    @PrimaryKey val id: String,
    val name: String,
    val licenseNo: String? = null,
    val licenseExpiryAt: Long? = null,
    val phone: String? = null,
    val active: Boolean = true,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** ডেলিভারি নোট (চালান); একটি ইনভয়েসের জন্য। স্ট্যাটাস: PENDING | PARTIAL | DELIVERED। */
@Entity(
    tableName = "delivery_notes",
    indices = [Index(value = ["challanNo"], unique = true), Index("invoiceId"), Index("partyId"),
        Index("tripId"), Index("status"), Index("deliveredAt")],
)
data class DeliveryNoteEntity(
    @PrimaryKey val id: String,
    val challanNo: String,
    val invoiceId: String,
    val invoiceNo: String,
    val partyId: String,
    val warehouseId: String,
    val territoryId: String? = null,
    val address: String? = null,
    val dueDate: Long? = null,         // প্রতিশ্রুত ডেলিভারি তারিখ (দিনের শুরু)
    val status: String = DistStatus.NOTE_PENDING,
    val tripId: String? = null,
    val vehicleNo: String? = null,
    val driverName: String? = null,
    val loadedAt: Long? = null,
    val deliveredAt: Long? = null,
    val receivedBy: String? = null,
    val note: String? = null,
    val createdBy: String,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(tableName = "delivery_lines", indices = [Index("deliveryNoteId"), Index("invoiceLineId"), Index("itemId")])
data class DeliveryLineEntity(
    @PrimaryKey val id: String,
    val deliveryNoteId: String,
    val invoiceId: String,
    val invoiceLineId: String,
    val itemId: String,
    val bagSizeKg: Double = 0.0,       // 0 = লুজ
    val plannedGrams: Double,
    val loadedGrams: Double = 0.0,
    val deliveredGrams: Double = 0.0,
    val returnedGrams: Double = 0.0,   // ফেরত (ভালো অবস্থায়)
    val damagedGrams: Double = 0.0,    // ছেঁড়া/ক্ষতিগ্রস্ত
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** লোডিংয়ে কোন লট থেকে কত নেওয়া হলো (অ্যাপেন্ড-অনলি)। */
@Entity(tableName = "delivery_allocations", indices = [Index("deliveryLineId"), Index("deliveryNoteId"), Index("lotId")])
data class DeliveryAllocationEntity(
    @PrimaryKey val id: String,
    val deliveryLineId: String,
    val deliveryNoteId: String,
    val itemId: String,
    val lotId: String,
    val qtyGrams: Double,
    val movementId: String,
    val createdBy: String,
    val createdAt: Long,
    val deviceId: String,
)

/** ডেলিভারি প্রমাণ (অ্যাপেন্ড-অনলি); একই নোটে একাধিক হলে সর্বশেষটি ধরা হয়। */
@Entity(tableName = "delivery_pods", indices = [Index("deliveryNoteId"), Index("tripId")])
data class DeliveryPodEntity(
    @PrimaryKey val id: String,
    val deliveryNoteId: String,
    val tripId: String?,
    val receiverName: String,
    val receiverPhone: String? = null,
    val signaturePath: String? = null,
    val photoPath: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val deliveredAt: Long,
    val createdBy: String,
    val createdAt: Long,
    val deviceId: String,
)

/** ফেরত/ক্ষতির রেকর্ড (অ্যাপেন্ড-অনলি)। kind = RETURN | DAMAGED। */
@Entity(tableName = "delivery_returns", indices = [Index("deliveryNoteId"), Index("deliveryLineId"), Index("itemId")])
data class DeliveryReturnEntity(
    @PrimaryKey val id: String,
    val deliveryNoteId: String,
    val deliveryLineId: String,
    val itemId: String,
    val lotId: String?,
    val kind: String,
    val qtyGrams: Double,
    val bags: Int? = null,
    val reason: String? = null,
    val movementId: String? = null,
    val createdBy: String,
    val createdAt: Long,
    val deviceId: String,
)

/** ডেলিভারির সময় নগদ কালেকশন (অ্যাপেন্ড-অনলি)। */
@Entity(tableName = "delivery_collections", indices = [Index("deliveryNoteId"), Index("tripId"), Index("createdAt"), Index("createdBy")])
data class DeliveryCollectionEntity(
    @PrimaryKey val id: String,
    val deliveryNoteId: String,
    val tripId: String?,
    val partyId: String,
    val amountPaisa: Long,
    val voucherId: String?,
    val createdBy: String,
    val createdAt: Long,
    val deviceId: String,
)

/** দিন শেষে ক্যাশ জমা মিলানো (অ্যাপেন্ড-অনলি)। */
@Entity(
    tableName = "cash_reconciliations",
    indices = [Index(value = ["businessDate", "collectorId"], unique = true)],
)
data class CashReconciliationEntity(
    @PrimaryKey val id: String,
    val businessDate: Long,           // দিনের শুরু (epoch millis)
    val collectorId: String,          // কালেকশন নেওয়া ইউজার
    val collectedPaisa: Long,
    val cashExpensesPaisa: Long,
    val expectedPaisa: Long,
    val depositedPaisa: Long,
    val differencePaisa: Long,
    val note: String? = null,
    val createdBy: String,
    val createdAt: Long,
    val deviceId: String,
)

@Entity(tableName = "trips", indices = [Index(value = ["tripNo"], unique = true), Index("vehicleId"), Index("driverId"), Index("status"), Index("plannedDate")])
data class TripEntity(
    @PrimaryKey val id: String,
    val tripNo: String,
    val vehicleId: String,
    val driverId: String,
    val warehouseId: String,
    val route: String,
    val territoryId: String? = null,
    val plannedDate: Long,
    val status: String = DistStatus.TRIP_PLANNED,
    val startKm: Double? = null,
    val endKm: Double? = null,
    val loadedAt: Long? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val note: String? = null,
    val createdBy: String,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(tableName = "trip_stops", indices = [Index("tripId"), Index("deliveryNoteId")])
data class TripStopEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val deliveryNoteId: String,
    val sequence: Int,
    val status: String = DistStatus.STOP_PENDING,
    val arrivedAt: Long? = null,
    val failReason: String? = null,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(tableName = "transport_costs", indices = [Index("tripId"), Index("incurredAt")])
data class TransportCostEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val kind: String,
    val amountPaisa: Long,
    val litres: Double? = null,
    val note: String? = null,
    val incurredAt: Long,
    val createdBy: String,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** ডিভাইস-লোকাল GPS ব্রেডক্রাম্ব (সিঙ্ক হয় না)। */
@Entity(tableName = "local_gps_points", indices = [Index("tripId"), Index("recordedAt")])
data class LocalGpsPointEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float? = null,
    val recordedAt: Long,
)

// ---------- রিপোর্ট রো (Room POJO) ----------

data class AreaPartyRow(val partyId: String, val territoryId: String?, val deliveredGrams: Double, val noteCount: Int)
data class TripLoadRow(val tripId: String, val loadedGrams: Double, val deliveredGrams: Double)
data class TripCostRow(val tripId: String, val totalPaisa: Long)
data class CollectorTotalRow(val createdBy: String, val totalPaisa: Long, val count: Int)

// ---------- DAO ----------

@Dao
interface TerritoryDao {
    @Query("SELECT * FROM territories WHERE deletedAt IS NULL ORDER BY name")
    fun observeAll(): Flow<List<TerritoryEntity>>

    @Query("SELECT * FROM territories WHERE deletedAt IS NULL ORDER BY name")
    suspend fun all(): List<TerritoryEntity>

    @Query("SELECT * FROM territories WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TerritoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(territory: TerritoryEntity)
}

@Dao
interface DealerAreaDao {
    @Query("SELECT * FROM dealer_areas WHERE deletedAt IS NULL")
    fun observeAll(): Flow<List<DealerAreaEntity>>

    @Query("SELECT * FROM dealer_areas WHERE deletedAt IS NULL")
    suspend fun all(): List<DealerAreaEntity>

    @Query("SELECT * FROM dealer_areas WHERE partyId = :partyId LIMIT 1")
    suspend fun getByParty(partyId: String): DealerAreaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(area: DealerAreaEntity)
}

@Dao
interface VehicleDao {
    @Query("SELECT * FROM vehicles WHERE deletedAt IS NULL ORDER BY regNo")
    fun observeAll(): Flow<List<VehicleEntity>>

    @Query("SELECT * FROM vehicles WHERE deletedAt IS NULL ORDER BY regNo")
    suspend fun all(): List<VehicleEntity>

    @Query("SELECT * FROM vehicles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): VehicleEntity?

    @Query("SELECT * FROM vehicles WHERE regNo = :regNo AND deletedAt IS NULL LIMIT 1")
    suspend fun getByRegNo(regNo: String): VehicleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vehicle: VehicleEntity)
}

@Dao
interface DriverDao {
    @Query("SELECT * FROM drivers WHERE deletedAt IS NULL ORDER BY name")
    fun observeAll(): Flow<List<DriverEntity>>

    @Query("SELECT * FROM drivers WHERE deletedAt IS NULL ORDER BY name")
    suspend fun all(): List<DriverEntity>

    @Query("SELECT * FROM drivers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DriverEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(driver: DriverEntity)
}

@Dao
interface DeliveryDao {
    @Query("SELECT * FROM delivery_notes WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeNotes(): Flow<List<DeliveryNoteEntity>>

    @Query("SELECT * FROM delivery_notes WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    suspend fun allNotes(): List<DeliveryNoteEntity>

    @Query("SELECT * FROM delivery_notes WHERE id = :id LIMIT 1")
    suspend fun getNote(id: String): DeliveryNoteEntity?

    @Query("SELECT * FROM delivery_notes WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun getNotes(ids: List<String>): List<DeliveryNoteEntity>

    /** ট্রিপে এখনও যোগ হয়নি এমন নোট (লোড প্ল্যানিংয়ের প্রার্থী)। */
    @Query("SELECT * FROM delivery_notes WHERE deletedAt IS NULL AND tripId IS NULL AND loadedAt IS NULL ORDER BY dueDate")
    fun observeUnassignedNotes(): Flow<List<DeliveryNoteEntity>>

    @Query("SELECT * FROM delivery_notes WHERE deletedAt IS NULL AND deliveredAt IS NOT NULL AND deliveredAt BETWEEN :from AND :to")
    suspend fun deliveredBetween(from: Long, to: Long): List<DeliveryNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: DeliveryNoteEntity)

    @Query("SELECT * FROM delivery_lines WHERE deliveryNoteId = :noteId AND deletedAt IS NULL ORDER BY itemId")
    suspend fun linesOf(noteId: String): List<DeliveryLineEntity>

    @Query("SELECT * FROM delivery_lines WHERE deliveryNoteId = :noteId AND deletedAt IS NULL ORDER BY itemId")
    fun observeLinesOf(noteId: String): Flow<List<DeliveryLineEntity>>

    @Query("SELECT * FROM delivery_lines WHERE deletedAt IS NULL")
    suspend fun allLines(): List<DeliveryLineEntity>

    @Query("SELECT * FROM delivery_lines WHERE deliveryNoteId IN (:noteIds) AND deletedAt IS NULL")
    suspend fun linesOfNotes(noteIds: List<String>): List<DeliveryLineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLines(lines: List<DeliveryLineEntity>)

    /** একই ইনভয়েস লাইনের জন্য প্ল্যান করা কিন্তু এখনও লোড হয়নি এমন পরিমাণ (দ্বিগুণ প্ল্যান ঠেকাতে)। */
    @Query(
        "SELECT COALESCE(SUM(l.plannedGrams), 0.0) FROM delivery_lines l " +
            "JOIN delivery_notes n ON n.id = l.deliveryNoteId " +
            "WHERE l.invoiceLineId = :invoiceLineId AND l.deletedAt IS NULL AND n.deletedAt IS NULL AND n.loadedAt IS NULL"
    )
    suspend fun plannedUnloadedGrams(invoiceLineId: String): Double

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAllocation(allocation: DeliveryAllocationEntity)

    @Query("SELECT * FROM delivery_allocations WHERE deliveryLineId = :lineId ORDER BY createdAt")
    suspend fun allocationsOfLine(lineId: String): List<DeliveryAllocationEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPod(pod: DeliveryPodEntity)

    @Query("SELECT * FROM delivery_pods WHERE deliveryNoteId = :noteId ORDER BY createdAt DESC LIMIT 1")
    suspend fun podOf(noteId: String): DeliveryPodEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReturn(row: DeliveryReturnEntity)

    @Query("SELECT * FROM delivery_returns WHERE deliveryNoteId = :noteId ORDER BY createdAt")
    suspend fun returnsOfNote(noteId: String): List<DeliveryReturnEntity>

    @Query("SELECT * FROM delivery_returns WHERE createdAt BETWEEN :from AND :to ORDER BY createdAt DESC")
    suspend fun returnsBetween(from: Long, to: Long): List<DeliveryReturnEntity>

    @Query(
        "SELECT n.partyId AS partyId, d.territoryId AS territoryId, " +
            "COALESCE(SUM(l.deliveredGrams), 0.0) AS deliveredGrams, COUNT(DISTINCT n.id) AS noteCount " +
            "FROM delivery_notes n " +
            "JOIN delivery_lines l ON l.deliveryNoteId = n.id AND l.deletedAt IS NULL " +
            "LEFT JOIN dealer_areas d ON d.partyId = n.partyId AND d.deletedAt IS NULL " +
            "WHERE n.deletedAt IS NULL AND n.deliveredAt IS NOT NULL AND n.deliveredAt BETWEEN :from AND :to " +
            "GROUP BY n.partyId, d.territoryId"
    )
    suspend fun areaPartyTotals(from: Long, to: Long): List<AreaPartyRow>
}

@Dao
interface TripDao {
    @Query("SELECT * FROM trips WHERE deletedAt IS NULL ORDER BY plannedDate DESC, tripNo DESC")
    fun observeTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :id LIMIT 1")
    suspend fun getTrip(id: String): TripEntity?

    @Query("SELECT * FROM trips WHERE deletedAt IS NULL AND plannedDate BETWEEN :from AND :to ORDER BY plannedDate")
    suspend fun tripsBetween(from: Long, to: Long): List<TripEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrip(trip: TripEntity)

    @Query("SELECT * FROM trip_stops WHERE tripId = :tripId AND deletedAt IS NULL ORDER BY sequence")
    suspend fun stopsOf(tripId: String): List<TripStopEntity>

    @Query("SELECT * FROM trip_stops WHERE tripId = :tripId AND deletedAt IS NULL ORDER BY sequence")
    fun observeStopsOf(tripId: String): Flow<List<TripStopEntity>>

    @Query("SELECT * FROM trip_stops WHERE id = :id LIMIT 1")
    suspend fun getStop(id: String): TripStopEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStops(stops: List<TripStopEntity>)

    @Query(
        "SELECT s.tripId AS tripId, COALESCE(SUM(l.loadedGrams), 0.0) AS loadedGrams, " +
            "COALESCE(SUM(l.deliveredGrams), 0.0) AS deliveredGrams " +
            "FROM trip_stops s JOIN delivery_lines l ON l.deliveryNoteId = s.deliveryNoteId AND l.deletedAt IS NULL " +
            "WHERE s.deletedAt IS NULL GROUP BY s.tripId"
    )
    suspend fun loadTotals(): List<TripLoadRow>

    @Query("SELECT * FROM transport_costs WHERE tripId = :tripId AND deletedAt IS NULL ORDER BY incurredAt")
    fun observeCosts(tripId: String): Flow<List<TransportCostEntity>>

    @Query("SELECT * FROM transport_costs WHERE tripId = :tripId AND deletedAt IS NULL ORDER BY incurredAt")
    suspend fun costsOf(tripId: String): List<TransportCostEntity>

    @Query("SELECT tripId AS tripId, COALESCE(SUM(amountPaisa), 0) AS totalPaisa FROM transport_costs WHERE deletedAt IS NULL GROUP BY tripId")
    suspend fun costTotals(): List<TripCostRow>

    @Query("SELECT * FROM transport_costs WHERE id = :id LIMIT 1")
    suspend fun getCost(id: String): TransportCostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCost(cost: TransportCostEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGps(point: LocalGpsPointEntity)

    @Query("SELECT * FROM local_gps_points WHERE tripId = :tripId ORDER BY recordedAt")
    fun observeGps(tripId: String): Flow<List<LocalGpsPointEntity>>

    @Query("DELETE FROM local_gps_points WHERE recordedAt < :before")
    suspend fun purgeGpsBefore(before: Long)
}

@Dao
interface DistFinanceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCollection(row: DeliveryCollectionEntity)

    @Query("SELECT * FROM delivery_collections WHERE createdAt BETWEEN :from AND :to ORDER BY createdAt DESC")
    suspend fun collectionsBetween(from: Long, to: Long): List<DeliveryCollectionEntity>

    @Query(
        "SELECT createdBy AS createdBy, COALESCE(SUM(amountPaisa), 0) AS totalPaisa, COUNT(*) AS count " +
            "FROM delivery_collections WHERE createdAt BETWEEN :from AND :to GROUP BY createdBy"
    )
    suspend fun collectorTotals(from: Long, to: Long): List<CollectorTotalRow>

    @Query(
        "SELECT COALESCE(SUM(amountPaisa), 0) FROM transport_costs WHERE deletedAt IS NULL AND createdBy = :userId " +
            "AND kind IN ('FUEL','ALLOWANCE','TOLL','OTHER') AND incurredAt BETWEEN :from AND :to"
    )
    suspend fun cashExpensesOf(userId: String, from: Long, to: Long): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecon(row: CashReconciliationEntity)

    @Query("SELECT * FROM cash_reconciliations WHERE businessDate = :day AND collectorId = :collectorId LIMIT 1")
    suspend fun reconOf(day: Long, collectorId: String): CashReconciliationEntity?

    @Query("SELECT * FROM cash_reconciliations ORDER BY businessDate DESC, createdAt DESC")
    fun observeRecons(): Flow<List<CashReconciliationEntity>>
}
