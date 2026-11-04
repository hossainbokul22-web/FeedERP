package com.feederp.data.formulation

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class FormulaStatus { DRAFT, APPROVED, ARCHIVED }

/**
 * ফর্মুলা (ভার্সনসহ)। একই ফর্মুলার সব ভার্সনের [rootId] এক (ভার্সন ১ এর id)।
 * শুধু APPROVED ফর্মুলা প্রোডাকশনে ব্যবহার করা যায়। লাইনের পরিমাণ গ্রাম প্রতি টন (১০,০০,০০০ গ্রাম) খাদ্যে।
 */
@Entity(
    tableName = "formulas",
    indices = [Index("rootId"), Index("status"), Index("productItemId"), Index(value = ["rootId", "version"])],
)
data class FormulaEntity(
    @PrimaryKey val id: String,
    val rootId: String,
    val code: String,
    val name: String,
    val version: Int,
    val baseFormulaId: String? = null,
    val productItemId: String,
    val status: FormulaStatus = FormulaStatus.DRAFT,
    val isMedicated: Boolean = false,
    val targetSpecies: String? = null,
    val targetAgeDays: Int? = null,
    // নিউট্রিয়েন্ট লক্ষ্যমাত্রা (ঐচ্ছিক)
    val minProteinPct: Double? = null,
    val minEnergyKcalPerKg: Double? = null,
    val minFatPct: Double? = null,
    val maxFatPct: Double? = null,
    val maxFibrePct: Double? = null,
    val maxMoisturePct: Double? = null,
    val note: String? = null,
    val approvedBy: String? = null,
    val approvedAt: Long? = null,
    val createdBy: String,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(tableName = "formula_lines", indices = [Index("formulaId"), Index("itemId")])
data class FormulaLineEntity(
    @PrimaryKey val id: String,
    val formulaId: String,
    val itemId: String,
    val gramsPerTon: Double,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/**
 * ব্যবহারকারী-কনফিগার করা নিষিদ্ধ/নিয়ন্ত্রিত উপাদানের তালিকা (আইন অনুযায়ী)।
 * ডিভাইস-লোকাল সেটিং, সিঙ্ক হয় না। level = "BANNED" | "CONTROLLED"
 */
@Entity(tableName = "local_restricted_items")
data class RestrictedItemEntity(
    @PrimaryKey val itemId: String,
    val level: String,
    val note: String? = null,
    val updatedAt: Long,
)

@Dao
interface FormulaDao {
    @Query("SELECT * FROM formulas WHERE deletedAt IS NULL ORDER BY name, version DESC")
    fun observeAll(): Flow<List<FormulaEntity>>

    @Query("SELECT * FROM formulas WHERE status = 'APPROVED' AND deletedAt IS NULL ORDER BY name")
    suspend fun approved(): List<FormulaEntity>

    @Query("SELECT * FROM formulas WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FormulaEntity?

    @Query("SELECT * FROM formulas WHERE rootId = :rootId AND deletedAt IS NULL ORDER BY version DESC")
    suspend fun versionsOf(rootId: String): List<FormulaEntity>

    @Query("SELECT MAX(version) FROM formulas WHERE rootId = :rootId")
    suspend fun maxVersion(rootId: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(formula: FormulaEntity)

    @Query("SELECT * FROM formula_lines WHERE formulaId = :formulaId AND deletedAt IS NULL")
    suspend fun lines(formulaId: String): List<FormulaLineEntity>

    @Query("SELECT * FROM formula_lines WHERE formulaId = :formulaId AND deletedAt IS NULL")
    fun observeLines(formulaId: String): Flow<List<FormulaLineEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLines(lines: List<FormulaLineEntity>)

    @Query(
        "UPDATE formula_lines SET deletedAt = :now, updatedAt = :now, deviceId = :deviceId " +
            "WHERE formulaId = :formulaId AND deletedAt IS NULL"
    )
    suspend fun softDeleteLines(formulaId: String, now: Long, deviceId: String)
}

@Dao
interface RestrictedItemDao {
    @Query("SELECT * FROM local_restricted_items ORDER BY level, itemId")
    fun observeAll(): Flow<List<RestrictedItemEntity>>

    @Query("SELECT * FROM local_restricted_items")
    suspend fun all(): List<RestrictedItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: RestrictedItemEntity)

    @Query("DELETE FROM local_restricted_items WHERE itemId = :itemId")
    suspend fun delete(itemId: String)
}
