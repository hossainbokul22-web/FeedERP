package com.feederp.data.qc

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * একটি QC টেস্ট ফল (অ্যাপেন্ড-অনলি)। ভুল ধরা পড়লে মুছে নয়, নতুন টেস্ট যোগ হয়;
 * প্রতিটি প্যারামিটারের সর্বশেষ ফলই গণ্য হয়। সীমা (specMin/specMax) টেস্টের সময়কার মান ধরে রাখে।
 */
@Entity(
    tableName = "qc_tests",
    indices = [Index("lotId"), Index(value = ["itemId", "parameter"])],
)
data class QcTestEntity(
    @PrimaryKey val id: String,
    val lotId: String,
    val itemId: String,
    val parameter: String,
    val unit: String? = null,
    val value: Double,
    val specMin: Double? = null,
    val specMax: Double? = null,
    val passed: Boolean,
    val note: String? = null,
    val createdBy: String,
    val createdAt: Long,
    val deviceId: String,
)

@Dao
interface QcDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(test: QcTestEntity)

    @Query("SELECT * FROM qc_tests WHERE lotId = :lotId ORDER BY createdAt DESC")
    fun observeByLot(lotId: String): Flow<List<QcTestEntity>>

    @Query("SELECT * FROM qc_tests WHERE lotId = :lotId ORDER BY createdAt DESC")
    suspend fun byLot(lotId: String): List<QcTestEntity>

    /** একই আইটেম ও প্যারামিটারের সর্বশেষ টেস্ট (নতুন টেস্টে সীমা আগে থেকে বসানোর জন্য)। */
    @Query("SELECT * FROM qc_tests WHERE itemId = :itemId AND parameter = :parameter ORDER BY createdAt DESC LIMIT 1")
    suspend fun lastFor(itemId: String, parameter: String): QcTestEntity?
}
