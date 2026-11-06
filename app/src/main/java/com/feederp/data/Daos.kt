package com.feederp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE deletedAt IS NULL ORDER BY displayName")
    fun observeAll(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE username = :username AND deletedAt IS NULL LIMIT 1")
    suspend fun getByUsername(username: String): UserEntity?

    /** মুছে ফেলা ইউজার সহ (username ইউনিক ইনডেক্সের জন্য)। */
    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun getByUsernameAny(username: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): UserEntity?

    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM users WHERE role = 'OWNER' AND active = 1 AND deletedAt IS NULL")
    suspend fun activeOwnerCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: UserEntity)
}

@Dao
interface UnitDao {
    @Query("SELECT * FROM units WHERE deletedAt IS NULL ORDER BY name")
    fun observeAll(): Flow<List<UnitEntity>>

    @Query("SELECT * FROM units WHERE deletedAt IS NULL ORDER BY name")
    suspend fun all(): List<UnitEntity>

    @Query("SELECT * FROM units WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): UnitEntity?

    @Query("SELECT COUNT(*) FROM units")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(unit: UnitEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(unit: UnitEntity)
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE deletedAt IS NULL ORDER BY name")
    fun observeAll(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE deletedAt IS NULL ORDER BY name")
    suspend fun all(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ItemEntity?

    @Query("SELECT * FROM items WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<ItemEntity>

    /** মুছে ফেলা আইটেম সহ (কোড ইউনিক ইনডেক্সের জন্য)। */
    @Query("SELECT * FROM items WHERE code = :code LIMIT 1")
    suspend fun getByCode(code: String): ItemEntity?

    /** এই ইউনিট ব্যবহার করছে এমন (মুছে-না-ফেলা) আইটেমের সংখ্যা। */
    @Query("SELECT COUNT(*) FROM items WHERE unitId = :unitId AND deletedAt IS NULL")
    suspend fun countByUnit(unitId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ItemEntity)
}

@Dao
interface PartyDao {
    @Query("SELECT * FROM parties WHERE deletedAt IS NULL ORDER BY name")
    fun observeAll(): Flow<List<PartyEntity>>

    @Query("SELECT * FROM parties WHERE deletedAt IS NULL ORDER BY name")
    suspend fun all(): List<PartyEntity>

    @Query("SELECT * FROM parties WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PartyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(party: PartyEntity)
}

@Dao
interface WarehouseDao {
    @Query("SELECT * FROM warehouses WHERE deletedAt IS NULL ORDER BY name")
    fun observeAll(): Flow<List<WarehouseEntity>>

    @Query("SELECT * FROM warehouses WHERE deletedAt IS NULL ORDER BY name")
    suspend fun all(): List<WarehouseEntity>

    @Query("SELECT * FROM warehouses WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WarehouseEntity?

    @Query("SELECT COUNT(*) FROM warehouses")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(warehouse: WarehouseEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(warehouse: WarehouseEntity)
}

/** লট অনুযায়ী ব্যালেন্স (একটি গুদামে)। */
data class LotBalanceRow(val lotId: String, val qtyGrams: Double)

/** আইটেম অনুযায়ী মোট স্টক (সব গুদাম)। */
data class ItemStockRow(val itemId: String, val qtyGrams: Double)

/** সময়কালে আইটেম × মুভমেন্টের ধরন অনুযায়ী যোগফল (type = MovementType.name)। */
data class ItemTypeSumRow(val itemId: String, val type: String, val qtyGrams: Double)

/** লট × গুদাম অনুযায়ী ব্যালেন্স। */
data class LotWarehouseBalanceRow(val lotId: String, val itemId: String, val warehouseId: String, val qtyGrams: Double)

/** আইটেম × গুদাম অনুযায়ী ব্যালেন্স (নেগেটিভ স্টক ধরার জন্য)। */
data class ItemWarehouseRow(val itemId: String, val warehouseId: String, val qtyGrams: Double)

/** খরচসহ ইনকামিং মুভমেন্ট (ওয়েটেড-অ্যাভারেজের জন্য)। */
data class CostRow(val qtyGrams: Double, val unitCostPaisaPerKg: Long)

@Dao
interface StockMovementDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(movement: StockMovementEntity)

    @Query(
        "SELECT itemId AS itemId, type AS type, SUM(qtyGrams) AS qtyGrams FROM stock_movements " +
            "WHERE createdAt >= :from AND createdAt < :to GROUP BY itemId, type"
    )
    suspend fun periodByType(from: Long, to: Long): List<ItemTypeSumRow>

    @Query("SELECT itemId AS itemId, SUM(qtyGrams) AS qtyGrams FROM stock_movements WHERE createdAt < :before GROUP BY itemId")
    suspend fun balanceBefore(before: Long): List<ItemStockRow>

    @Query(
        "SELECT COALESCE(SUM(qtyGrams), 0.0) FROM stock_movements WHERE itemId = :itemId " +
            "AND (:warehouseId IS NULL OR warehouseId = :warehouseId) AND (:lotId IS NULL OR lotId = :lotId)"
    )
    suspend fun sumGrams(itemId: String, warehouseId: String?, lotId: String?): Double

    @Query(
        "SELECT COALESCE(SUM(qtyGrams), 0.0) FROM stock_movements WHERE itemId = :itemId " +
            "AND (:warehouseId IS NULL OR warehouseId = :warehouseId)"
    )
    fun observeSumGrams(itemId: String, warehouseId: String?): Flow<Double>

    @Query(
        "SELECT lotId AS lotId, SUM(qtyGrams) AS qtyGrams FROM stock_movements " +
            "WHERE itemId = :itemId AND warehouseId = :warehouseId AND lotId IS NOT NULL " +
            "GROUP BY lotId HAVING SUM(qtyGrams) > 0.0005"
    )
    suspend fun lotBalances(itemId: String, warehouseId: String): List<LotBalanceRow>

    @Query(
        "SELECT lotId AS lotId, itemId AS itemId, warehouseId AS warehouseId, SUM(qtyGrams) AS qtyGrams " +
            "FROM stock_movements WHERE lotId IS NOT NULL GROUP BY lotId, itemId, warehouseId " +
            "HAVING ABS(SUM(qtyGrams)) > 0.0005"
    )
    fun observeLotWarehouseBalances(): Flow<List<LotWarehouseBalanceRow>>

    @Query("SELECT itemId AS itemId, SUM(qtyGrams) AS qtyGrams FROM stock_movements GROUP BY itemId")
    fun observeItemTotals(): Flow<List<ItemStockRow>>

    @Query(
        "SELECT itemId AS itemId, warehouseId AS warehouseId, SUM(qtyGrams) AS qtyGrams " +
            "FROM stock_movements GROUP BY itemId, warehouseId"
    )
    fun observeItemWarehouseTotals(): Flow<List<ItemWarehouseRow>>

    @Query(
        "SELECT qtyGrams AS qtyGrams, unitCostPaisaPerKg AS unitCostPaisaPerKg FROM stock_movements " +
            "WHERE itemId = :itemId AND qtyGrams > 0 AND unitCostPaisaPerKg IS NOT NULL"
    )
    suspend fun costRows(itemId: String): List<CostRow>

    @Query("SELECT * FROM stock_movements ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<StockMovementEntity>>

    @Query("SELECT * FROM stock_movements WHERE itemId = :itemId ORDER BY createdAt DESC LIMIT :limit")
    fun observeByItem(itemId: String, limit: Int): Flow<List<StockMovementEntity>>

    /** একটি গুদামে যেসব আইটেমের ব্যালেন্স শূন্য নয় তাদের সংখ্যা (গুদাম মোছার আগে যাচাই)। */
    @Query(
        "SELECT COUNT(*) FROM (SELECT itemId FROM stock_movements WHERE warehouseId = :warehouseId " +
            "GROUP BY itemId HAVING ABS(SUM(qtyGrams)) > 0.0005)"
    )
    suspend fun nonZeroItemCountForWarehouse(warehouseId: String): Int

    /** একটি গুদামের সব আইটেমের মোট ব্যালেন্স (গুদাম মোছার আগে যাচাইয়ে)। */
    @Query("SELECT COALESCE(SUM(ABS(qtyGrams)), 0.0) FROM stock_movements WHERE warehouseId = :warehouseId")
    suspend fun movementVolumeForWarehouse(warehouseId: String): Double

    @Query(
        "SELECT COALESCE(SUM(qtyGrams), 0.0) FROM stock_movements WHERE warehouseId = :warehouseId"
    )
    suspend fun sumGramsForWarehouse(warehouseId: String): Double
}

@Dao
interface AuditLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(log: AuditLogEntity)

    @Query("SELECT * FROM audit_log ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditLogEntity>>
}
