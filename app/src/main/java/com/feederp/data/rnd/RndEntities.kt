package com.feederp.data.rnd

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** অনুমোদন প্রবাহ: R&D (RUNNING) -> SUBMITTED -> QC_VERIFIED -> Manager APPROVED | REJECTED */
enum class TrialStatus { RUNNING, SUBMITTED, QC_VERIFIED, APPROVED, REJECTED }

@Entity(tableName = "formula_trials", indices = [Index(value = ["trialNo"], unique = true), Index("baseFormulaId"), Index("status")])
data class FormulaTrialEntity(
    @PrimaryKey val id: String,
    val trialNo: String,
    val baseFormulaId: String,
    val proposedFormulaId: String,     // তৈরি হওয়া DRAFT ভার্সন
    val changes: String,
    val objective: String,
    val startDate: Long,
    val endDate: Long? = null,
    val status: TrialStatus = TrialStatus.RUNNING,
    val qcBy: String? = null,
    val qcAt: Long? = null,
    val qcNote: String? = null,
    val decidedBy: String? = null,
    val decidedAt: Long? = null,
    val decisionNote: String? = null,
    val createdBy: String,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** ফিড ট্রায়ালের একটি গ্রুপের ফলাফল। ওজন প্রাণী-প্রতি গ্রামে, খাদ্য গ্রহণ মোট গ্রামে। */
@Entity(tableName = "feed_trial_records", indices = [Index("trialId")])
data class FeedTrialRecordEntity(
    @PrimaryKey val id: String,
    val trialId: String,
    val groupName: String,
    val species: String,
    val animalCount: Int,
    val days: Int,
    val startWeightGrams: Double,
    val endWeightGrams: Double,
    val feedIntakeGrams: Double,
    val mortalityCount: Int = 0,
    val eggCount: Long? = null,
    val milkLiters: Double? = null,
    val feedCostPaisaPerKg: Long? = null,
    val note: String? = null,
    val recordedAt: Long,
    val createdBy: String,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(tableName = "field_feedbacks", indices = [Index("batchNo"), Index("resolved")])
data class FieldFeedbackEntity(
    @PrimaryKey val id: String,
    val batchNo: String,               // প্রোডাকশন ব্যাচ নম্বর
    val batchId: String? = null,
    val farmName: String,
    val partyId: String? = null,
    val visitedAt: Long,
    val issueType: String,             // যেমন: বৃদ্ধি কম, ডায়রিয়া, ফেদার পিকিং
    val severity: Int = 1,             // ১ = কম, ২ = মাঝারি, ৩ = গুরুতর
    val animalCount: Int? = null,
    val description: String,
    val actionTaken: String? = null,
    val resolved: Boolean = false,
    val createdBy: String,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Dao
interface FormulaTrialDao {
    @Query("SELECT * FROM formula_trials WHERE deletedAt IS NULL ORDER BY startDate DESC")
    fun observeAll(): Flow<List<FormulaTrialEntity>>

    @Query("SELECT * FROM formula_trials WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FormulaTrialEntity?

    @Query("SELECT * FROM formula_trials WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<FormulaTrialEntity?>

    /** একটি প্রস্তাবিত (DRAFT) ফর্মুলা যে ট্রায়ালের অংশ, সেটি (না থাকলে null)। */
    @Query("SELECT * FROM formula_trials WHERE proposedFormulaId = :formulaId AND deletedAt IS NULL ORDER BY startDate DESC LIMIT 1")
    suspend fun byProposedFormula(formulaId: String): FormulaTrialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(trial: FormulaTrialEntity)
}

@Dao
interface FeedTrialDao {
    @Query("SELECT * FROM feed_trial_records WHERE trialId = :trialId AND deletedAt IS NULL ORDER BY recordedAt")
    fun observeByTrial(trialId: String): Flow<List<FeedTrialRecordEntity>>

    @Query("SELECT * FROM feed_trial_records WHERE trialId = :trialId AND deletedAt IS NULL ORDER BY recordedAt")
    suspend fun byTrial(trialId: String): List<FeedTrialRecordEntity>

    @Query("SELECT * FROM feed_trial_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FeedTrialRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: FeedTrialRecordEntity)
}

@Dao
interface FieldFeedbackDao {
    @Query("SELECT * FROM field_feedbacks WHERE deletedAt IS NULL ORDER BY visitedAt DESC")
    fun observeAll(): Flow<List<FieldFeedbackEntity>>

    @Query("SELECT * FROM field_feedbacks WHERE batchNo = :batchNo AND deletedAt IS NULL ORDER BY visitedAt DESC")
    suspend fun byBatchNo(batchNo: String): List<FieldFeedbackEntity>

    @Query("SELECT * FROM field_feedbacks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FieldFeedbackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: FieldFeedbackEntity)
}
