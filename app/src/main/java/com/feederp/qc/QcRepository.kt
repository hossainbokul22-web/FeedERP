package com.feederp.qc

import androidx.room.withTransaction
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.core.Stamper
import com.feederp.core.newId
import com.feederp.data.AppDatabase
import com.feederp.data.inventory.LotDao
import com.feederp.data.production.ProductionDao
import com.feederp.data.qc.QcDao
import com.feederp.data.qc.QcTestEntity
import com.feederp.inventory.AuditRepository
import com.feederp.inventory.StockRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * লটের QC: টেস্ট ফল রেকর্ড (অ্যাপেন্ড-অনলি) ও সিদ্ধান্ত (পাস/হোল্ড/বাতিল)। সিদ্ধান্ত লটের স্ট্যাটাস বদলায়
 * ([StockRepository.setLotQcStatus]); লটটি কোনো ব্যাচের ফিনিশড গুডস লট হলে ব্যাচের qcStatus-ও একই সাথে বদলায়।
 * পাস হলে তবেই লট বিক্রি/প্রোডাকশনে ব্যবহারযোগ্য হয় (স্টক রিপোজিটরি যাচাই করে)।
 */
@Singleton
class QcRepository @Inject constructor(
    private val db: AppDatabase,
    private val dao: QcDao,
    private val lotDao: LotDao,
    private val productionDao: ProductionDao,
    private val stock: StockRepository,
    private val audit: AuditRepository,
    private val stamper: Stamper,
    private val session: SessionManager,
) {
    fun observeTests(lotId: String): Flow<List<QcTestEntity>> = dao.observeByLot(lotId)

    /** একই আইটেম ও প্যারামিটারের সর্বশেষ টেস্ট; নতুন টেস্টের সীমা আগে থেকে বসাতে। */
    suspend fun lastSpec(itemId: String, parameter: String): QcTestEntity? = dao.lastFor(itemId, parameter)

    suspend fun recordTest(
        lotId: String,
        parameter: String,
        unit: String,
        value: Double?,
        specMin: Double?,
        specMax: Double?,
        note: String? = null,
    ): String {
        session.require(Permission.QC)
        QcCalc.inputError(value, specMin, specMax)?.let { throw IllegalArgumentException(it) }
        val param = parameter.trim()
        require(param.isNotEmpty() && param != QcParameters.OTHER) { "প্যারামিটারের নাম দিন" }
        val lot = lotDao.getById(lotId)
        require(lot != null && lot.deletedAt == null) { "লট পাওয়া যায়নি" }
        val v = value!!
        val id = newId()
        dao.insert(
            QcTestEntity(
                id = id, lotId = lotId, itemId = lot.itemId, parameter = param, unit = unit.trim(), value = v,
                specMin = specMin, specMax = specMax, passed = QcCalc.passed(v, specMin, specMax),
                note = note?.trim()?.ifBlank { null }, createdBy = session.userId,
                createdAt = stamper.now(), deviceId = stamper.deviceId,
            )
        )
        return id
    }

    /**
     * সিদ্ধান্ত: [status] = "PASSED" | "HOLD" | "REJECTED"। নিয়ম [QcCalc.decisionError]।
     * ওভাররাইড (ফেল/টেস্টহীন লট পাস) শুধু [Permission.APPROVE] ধারীর, কারণসহ, এবং অডিট লগে যায়।
     */
    suspend fun decide(lotId: String, status: String, note: String?) {
        session.require(Permission.QC)
        db.withTransaction {
            val lot = lotDao.getById(lotId)
            require(lot != null && lot.deletedAt == null) { "লট পাওয়া যায়নি" }
            val points = dao.byLot(lotId).map { TestPoint(it.parameter, it.passed, it.createdAt) }
            val rec = QcCalc.recommendation(points)
            QcCalc.decisionError(status, rec, session.can(Permission.APPROVE), note)?.let { throw IllegalStateException(it) }

            stock.setLotQcStatus(lotId, status)
            productionDao.batchByFgLot(lotId)?.let { b ->
                productionDao.upsertBatch(b.copy(qcStatus = status, updatedAt = stamper.now(), deviceId = stamper.deviceId))
            }
            val overridden = status == "PASSED" && rec != QcRecommendation.ALL_PASS
            audit.log(
                if (overridden) "QC_OVERRIDE_PASS" else "QC_DECISION", "lot", lotId,
                "$status" + (note?.trim()?.takeIf { it.isNotEmpty() }?.let { " — $it" } ?: ""),
            )
        }
    }
}
