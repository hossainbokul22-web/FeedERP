package com.feederp.data.inventory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** কোয়ালিটি স্ট্যাটাসের অনুমোদিত মান: PENDING | PASSED | REJECTED | HOLD */
@Entity(
    tableName = "lots",
    indices = [Index("itemId"), Index("expiryAt"), Index("qcStatus"), Index(value = ["itemId", "batchNo"])],
)
data class LotEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val batchNo: String,
    val mfgAt: Long? = null,
    val expiryAt: Long? = null,
    val moisturePct: Double? = null,
    val supplierId: String? = null,
    val qcStatus: String = "PENDING",
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Dao
interface LotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(lot: LotEntity)

    @Query("SELECT * FROM lots WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): LotEntity?

    @Query("SELECT * FROM lots WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun getByIds(ids: List<String>): List<LotEntity>

    @Query("SELECT * FROM lots WHERE deletedAt IS NULL")
    fun observeAll(): Flow<List<LotEntity>>

    @Query("SELECT * FROM lots WHERE itemId = :itemId AND deletedAt IS NULL ORDER BY expiryAt")
    suspend fun byItem(itemId: String): List<LotEntity>
}
