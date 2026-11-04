package com.feederp.rnd

import androidx.room.withTransaction
import com.feederp.core.DocNumbers
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.core.Stamper
import com.feederp.core.newId
import com.feederp.data.AppDatabase
import com.feederp.data.rnd.FeedTrialDao
import com.feederp.data.rnd.FeedTrialRecordEntity
import com.feederp.data.rnd.FieldFeedbackDao
import com.feederp.data.rnd.FieldFeedbackEntity
import com.feederp.data.rnd.FormulaTrialDao
import com.feederp.data.rnd.FormulaTrialEntity
import com.feederp.data.rnd.TrialStatus
import com.feederp.formulation.FormulaRepository
import com.feederp.inventory.AuditRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R&D: ফর্মুলা ট্রায়াল (প্রস্তাবিত ফর্মুলা = বেস থেকে নতুন DRAFT ভার্সন), গ্রুপভিত্তিক ফিড ট্রায়াল ফল (FCR ইত্যাদি),
 * অনুমোদন প্রবাহ (R&D জমা → QC যাচাই → ম্যানেজার অনুমোদন; প্রতিটি ধাপের অনুমতি [TrialRules]-এ),
 * এবং মাঠ-পর্যায়ের ফিডব্যাক। ট্রায়াল অনুমোদনের সময় প্রস্তাবিত ফর্মুলাও অনুমোদিত (আগের ভার্সন ARCHIVED) হয়।
 * প্রস্তাবিত ফর্মুলার লাইন বদল ফর্মুলেশন স্ক্রিনে (FORMULA_EDIT) করতে হয়; ট্রায়াল চলাকালীন সেখানেই পুষ্টি/খরচ দেখা যায়।
 * সীমা: ফিডব্যাকে ব্যাচ নম্বর হাতে লেখা (প্রোডাকশন ব্যাচের সাথে স্বয়ংক্রিয় মিল নেই)।
 */
@Singleton
class RndRepository @Inject constructor(
    private val db: AppDatabase,
    private val trialDao: FormulaTrialDao,
    private val recordDao: FeedTrialDao,
    private val feedbackDao: FieldFeedbackDao,
    private val formulas: FormulaRepository,
    private val docNumbers: DocNumbers,
    private val stamper: Stamper,
    private val session: SessionManager,
    private val audit: AuditRepository,
) {
    fun observeTrials(): Flow<List<FormulaTrialEntity>> = trialDao.observeAll()
    fun observeTrial(id: String): Flow<FormulaTrialEntity?> = trialDao.observeById(id)
    fun observeRecords(trialId: String): Flow<List<FeedTrialRecordEntity>> = recordDao.observeByTrial(trialId)
    fun observeFeedback(): Flow<List<FieldFeedbackEntity>> = feedbackDao.observeAll()

    fun toInput(r: FeedTrialRecordEntity) = FcrInput(
        r.animalCount, r.days, r.startWeightGrams, r.endWeightGrams, r.feedIntakeGrams,
        r.mortalityCount, r.eggCount, r.milkLiters, r.feedCostPaisaPerKg,
    )

    // ---------------- ট্রায়াল ----------------

    /** বেস ফর্মুলা থেকে নতুন খসড়া ভার্সন + ট্রায়াল তৈরি। ফেরত: ট্রায়াল id। */
    suspend fun startTrial(baseFormulaId: String, changes: String, objective: String, startDate: Long): String {
        session.require(Permission.RND)
        require(changes.isNotBlank()) { "কী বদলাতে চান লিখুন" }
        require(objective.isNotBlank()) { "ট্রায়ালের উদ্দেশ্য লিখুন" }
        val base = requireNotNull(formulas.getApproved(baseFormulaId)) { "বেস হিসেবে শুধু অনুমোদিত ফর্মুলা নেওয়া যায়" }
        return db.withTransaction {
            val proposed = formulas.createDraftVersion(base.id, note = "R&D ট্রায়াল: ${changes.trim()}")
            val id = newId()
            val now = stamper.now()
            trialDao.upsert(
                FormulaTrialEntity(
                    id = id, trialNo = docNumbers.next("TRL"), baseFormulaId = base.id, proposedFormulaId = proposed,
                    changes = changes.trim(), objective = objective.trim(), startDate = startDate,
                    createdBy = session.userId, updatedAt = now, deviceId = stamper.deviceId,
                )
            )
            audit.log("TRIAL_START", "formula_trials", id, "${base.code} v${base.version}")
            id
        }
    }

    suspend fun addRecord(
        trialId: String, groupName: String, species: String, input: FcrInput, note: String?,
    ) {
        session.require(Permission.RND)
        val t = requireNotNull(trialDao.getById(trialId)) { "ট্রায়াল পাওয়া যায়নি" }
        require(TrialRules.canEnterData(t.status)) { "শুধু চলমান ট্রায়ালে ফল যোগ করা যায়" }
        require(groupName.isNotBlank()) { "গ্রুপের নাম লিখুন" }
        require(species.isNotBlank()) { "প্রজাতি লিখুন (যেমন ব্রয়লার)" }
        val errors = FcrCalculator.validate(input)
        require(errors.isEmpty()) { errors.joinToString("\n") }
        val now = stamper.now()
        recordDao.upsert(
            FeedTrialRecordEntity(
                id = newId(), trialId = trialId, groupName = groupName.trim(), species = species.trim(),
                animalCount = input.animalCount, days = input.days, startWeightGrams = input.startWeightGrams,
                endWeightGrams = input.endWeightGrams, feedIntakeGrams = input.feedIntakeGrams, mortalityCount = input.mortalityCount,
                eggCount = input.eggCount, milkLiters = input.milkLiters, feedCostPaisaPerKg = input.feedCostPaisaPerKg,
                note = note?.trim()?.ifBlank { null }, recordedAt = now, createdBy = session.userId,
                updatedAt = now, deviceId = stamper.deviceId,
            )
        )
    }

    suspend fun removeRecord(recordId: String) {
        session.require(Permission.RND)
        val r = requireNotNull(recordDao.getById(recordId)) { "রেকর্ড পাওয়া যায়নি" }
        val t = requireNotNull(trialDao.getById(r.trialId)) { "ট্রায়াল পাওয়া যায়নি" }
        require(TrialRules.canEnterData(t.status)) { "জমা দেওয়ার পর ফল বদলানো যায় না" }
        recordDao.upsert(r.copy(deletedAt = stamper.now(), updatedAt = stamper.now(), deviceId = stamper.deviceId))
    }

    /** প্রতি গ্রুপের এবং সমষ্টিগত ফল। */
    suspend fun results(trialId: String): Pair<List<Pair<FeedTrialRecordEntity, FcrResult>>, FcrResult?> {
        val per = recordDao.byTrial(trialId).map { it to FcrCalculator.calculate(toInput(it)) }
        return per to FcrCalculator.aggregate(per.map { it.second })
    }

    suspend fun submit(trialId: String) {
        val t = requireNotNull(trialDao.getById(trialId)) { "ট্রায়াল পাওয়া যায়নি" }
        transition(t, TrialStatus.SUBMITTED, null)
    }

    suspend fun qcVerify(trialId: String, note: String?) {
        val t = requireNotNull(trialDao.getById(trialId)) { "ট্রায়াল পাওয়া যায়নি" }
        transition(t, TrialStatus.QC_VERIFIED, note)
    }

    suspend fun approve(trialId: String, note: String?) {
        val t = requireNotNull(trialDao.getById(trialId)) { "ট্রায়াল পাওয়া যায়নি" }
        transition(t, TrialStatus.APPROVED, note)
    }

    suspend fun reject(trialId: String, note: String) {
        val t = requireNotNull(trialDao.getById(trialId)) { "ট্রায়াল পাওয়া যায়নি" }
        transition(t, TrialStatus.REJECTED, note)
    }

    private suspend fun transition(t: FormulaTrialEntity, to: TrialStatus, note: String?) {
        require(TrialRules.canTransition(t.status, to)) { "${TrialRules.labelBn(t.status)} অবস্থা থেকে এই ধাপে যাওয়া যায় না" }
        TrialRules.requiredPermission(t.status, to)?.let { session.require(it) }
        if (to == TrialStatus.REJECTED) TrialRules.rejectionBlockers(note).let { require(it.isEmpty()) { it.joinToString() } }
        if (to == TrialStatus.SUBMITTED) {
            val blockers = TrialRules.submitBlockers(
                t.status, recordDao.byTrial(t.id).size, formulas.evaluate(t.proposedFormulaId).report.blockers,
            )
            require(blockers.isEmpty()) { blockers.joinToString("\n") }
        }
        db.withTransaction {
            val now = stamper.now()
            // ফর্মুলা অনুমোদন ব্যর্থ হলে (যেমন নতুন ব্লকার) পুরো ধাপ বাতিল হয়
            if (to == TrialStatus.APPROVED) formulas.approve(t.proposedFormulaId, trialApproved = true)
            val updated = when (to) {
                TrialStatus.SUBMITTED -> t.copy(endDate = now)
                TrialStatus.QC_VERIFIED -> t.copy(qcBy = session.userId, qcAt = now, qcNote = note?.trim()?.ifBlank { null })
                TrialStatus.APPROVED, TrialStatus.REJECTED ->
                    t.copy(decidedBy = session.userId, decidedAt = now, decisionNote = note?.trim()?.ifBlank { null })
                TrialStatus.RUNNING -> t
            }
            trialDao.upsert(updated.copy(status = to, updatedAt = now, deviceId = stamper.deviceId))
            audit.log("TRIAL_${to.name}", "formula_trials", t.id, note)
        }
    }

    // ---------------- মাঠ-পর্যায়ের ফিডব্যাক ----------------

    suspend fun addFeedback(
        batchNo: String, farmName: String, visitedAt: Long, issueType: String, severity: Int,
        animalCount: Int?, description: String,
    ) {
        session.require(Permission.RND)
        require(batchNo.isNotBlank()) { "ব্যাচ নম্বর লিখুন" }
        require(farmName.isNotBlank()) { "খামারের নাম লিখুন" }
        require(issueType.isNotBlank()) { "সমস্যার ধরন লিখুন" }
        require(severity in 1..3) { "গুরুত্ব ১–৩ হতে হবে" }
        require(description.isNotBlank()) { "বিবরণ লিখুন" }
        require(animalCount == null || animalCount > 0) { "প্রাণীর সংখ্যা ০ এর বেশি হতে হবে" }
        val id = newId()
        feedbackDao.upsert(
            FieldFeedbackEntity(
                id = id, batchNo = batchNo.trim(), farmName = farmName.trim(), visitedAt = visitedAt, issueType = issueType.trim(),
                severity = severity, animalCount = animalCount, description = description.trim(),
                createdBy = session.userId, updatedAt = stamper.now(), deviceId = stamper.deviceId,
            )
        )
        audit.log("FEEDBACK_ADD", "field_feedbacks", id, "$batchNo $issueType")
    }

    suspend fun resolveFeedback(id: String, actionTaken: String) {
        session.require(Permission.RND)
        require(actionTaken.isNotBlank()) { "কী ব্যবস্থা নেওয়া হয়েছে লিখুন" }
        val f = requireNotNull(feedbackDao.getById(id)) { "ফিডব্যাক পাওয়া যায়নি" }
        feedbackDao.upsert(f.copy(resolved = true, actionTaken = actionTaken.trim(), updatedAt = stamper.now(), deviceId = stamper.deviceId))
        audit.log("FEEDBACK_RESOLVE", "field_feedbacks", id, actionTaken)
    }
}
