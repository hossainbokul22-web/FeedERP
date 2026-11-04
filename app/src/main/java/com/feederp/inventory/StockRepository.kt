package com.feederp.inventory

import androidx.room.withTransaction
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.core.Stamper
import com.feederp.core.newId
import com.feederp.data.AppDatabase
import com.feederp.data.ItemDao
import com.feederp.data.ItemEntity
import com.feederp.data.ItemStockRow
import com.feederp.data.ItemType
import com.feederp.data.ItemWarehouseRow
import com.feederp.data.LotWarehouseBalanceRow
import com.feederp.data.MovementType
import com.feederp.data.StockMovementDao
import com.feederp.data.StockMovementEntity
import com.feederp.data.WarehouseDao
import com.feederp.data.inventory.LotDao
import com.feederp.data.inventory.LotEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * স্টক = মুভমেন্টের যোগফল। এখানে কখনও স্টক সংখ্যা সরাসরি বদলানো হয় না, শুধু মুভমেন্ট যোগ হয় (সিঙ্ক-নিরাপদ)।
 * নেগেটিভ স্টক ব্লক করা হয় না (অফলাইন বিক্রির কারণে সিঙ্কের পর হতে পারে), কিন্তু অডিট লগে অ্যালার্ট থাকে।
 */
@Singleton
class StockRepository @Inject constructor(
    private val db: AppDatabase,
    private val movementDao: StockMovementDao,
    private val lotDao: LotDao,
    private val itemDao: ItemDao,
    private val warehouseDao: WarehouseDao,
    private val stamper: Stamper,
    private val session: SessionManager,
    private val audit: AuditRepository,
) {
    // ---------------- লট ----------------

    /** নতুন লট তৈরি; ফেরত: lotId। */
    suspend fun createLot(
        itemId: String,
        batchNo: String,
        mfgAt: Long?,
        expiryAt: Long?,
        moisturePct: Double? = null,
        supplierId: String? = null,
        qcStatus: String = "PENDING",
    ): String {
        session.userId // লগইন লাগবে
        require(batchNo.isNotBlank()) { "ব্যাচ নম্বর খালি হতে পারে না" }
        require(qcStatus in QcStatus.all) { "QC স্ট্যাটাস সঠিক নয়: $qcStatus" }
        if (mfgAt != null && expiryAt != null) require(expiryAt >= mfgAt) { "মেয়াদ উৎপাদনের তারিখের আগে হতে পারে না" }
        if (moisturePct != null) require(moisturePct in 0.0..100.0) { "আর্দ্রতা ০-১০০% এর মধ্যে হতে হবে" }
        val item = itemDao.getById(itemId)
        require(item != null && item.deletedAt == null) { "আইটেম পাওয়া যায়নি" }
        val id = newId()
        lotDao.upsert(
            LotEntity(
                id = id, itemId = itemId, batchNo = batchNo.trim(), mfgAt = mfgAt, expiryAt = expiryAt,
                moisturePct = moisturePct, supplierId = supplierId, qcStatus = qcStatus,
                updatedAt = stamper.now(), deviceId = stamper.deviceId,
            )
        )
        return id
    }

    /** QC স্ট্যাটাস বদল (QC পারমিশন লাগে); অডিট লগ হয়। */
    suspend fun setLotQcStatus(lotId: String, status: String) {
        session.require(Permission.QC)
        require(status in QcStatus.all) { "QC স্ট্যাটাস সঠিক নয়: $status" }
        val lot = lotDao.getById(lotId)
        require(lot != null && lot.deletedAt == null) { "লট পাওয়া যায়নি" }
        if (lot.qcStatus == status) return
        lotDao.upsert(lot.copy(qcStatus = status, updatedAt = stamper.now(), deviceId = stamper.deviceId))
        audit.log("LOT_QC_STATUS", "lots", lotId, "${lot.qcStatus} -> $status (ব্যাচ ${lot.batchNo})")
    }

    fun observeLots(): Flow<List<LotEntity>> = lotDao.observeAll()

    /** একটি গুদামে বিক্রি/ব্যবহারযোগ্য লট (QC পাস/অনির্ধারিত, মেয়াদোত্তীর্ণ নয়), FEFO ক্রমে। */
    suspend fun lotsFefo(itemId: String, warehouseId: String): List<LotStock> {
        val item = itemDao.getById(itemId) ?: return emptyList()
        val balances = movementDao.lotBalances(itemId, warehouseId)
        if (balances.isEmpty()) return emptyList()
        val lots = balances.map { it.lotId }.chunked(500).flatMap { lotDao.getByIds(it) }.associateBy { it.id }
        val now = System.currentTimeMillis()
        val result = ArrayList<LotStock>()
        for (b in balances) {
            val lot = lots[b.lotId] ?: continue
            if (!StockCalc.isIssuable(lot.qcStatus, item.requiresQc, lot.expiryAt, now)) continue
            result += LotStock(lot.id, lot.batchNo, lot.expiryAt, b.qtyGrams)
        }
        return StockCalc.fefoSort(result)
    }

    // ---------------- স্টক পড়া ----------------

    fun observeStockGrams(itemId: String, warehouseId: String? = null): Flow<Double> =
        movementDao.observeSumGrams(itemId, warehouseId)

    suspend fun stockGrams(itemId: String, warehouseId: String? = null, lotId: String? = null): Double =
        movementDao.sumGrams(itemId, warehouseId, lotId)

    /** কেজি প্রতি ওয়েটেড-অ্যাভারেজ খরচ (পয়সা), সব ইনকামিং মুভমেন্টের খরচ থেকে। */
    suspend fun weightedAvgCostPaisaPerKg(itemId: String): Long =
        StockCalc.weightedAvgCostPaisaPerKg(
            movementDao.costRows(itemId).map { CostLayer(it.qtyGrams, it.unitCostPaisaPerKg) }
        )

    fun observeItemTotals(): Flow<List<ItemStockRow>> = movementDao.observeItemTotals()
    fun observeItemWarehouseTotals(): Flow<List<ItemWarehouseRow>> = movementDao.observeItemWarehouseTotals()
    fun observeLotWarehouseBalances(): Flow<List<LotWarehouseBalanceRow>> = movementDao.observeLotWarehouseBalances()
    fun observeRecentMovements(limit: Int = 200): Flow<List<StockMovementEntity>> = movementDao.observeRecent(limit)
    fun observeMovementsByItem(itemId: String, limit: Int = 200): Flow<List<StockMovementEntity>> =
        movementDao.observeByItem(itemId, limit)

    // ---------------- লেখা (শুধু মুভমেন্ট যোগ) ----------------

    /**
     * একটি মুভমেন্ট যোগ করে; ফেরত: movementId। [qtyGrams]: ইন = ধনাত্মক, আউট = ঋণাত্মক।
     * ওষুধ/নিয়ন্ত্রিত আইটেমের ইস্যুতে [Permission.INVENTORY_ISSUE_MEDICINE] লাগে।
     * বিক্রি/উৎপাদনে ব্যবহারের জন্য লট QC-উপযুক্ত ও মেয়াদোত্তীর্ণ নয় হতে হবে।
     */
    suspend fun record(
        itemId: String,
        lotId: String?,
        warehouseId: String,
        type: MovementType,
        qtyGrams: Double,
        unitCostPaisaPerKg: Long? = null,
        refType: String? = null,
        refId: String? = null,
        note: String? = null,
    ): String {
        val userId = session.userId
        StockCalc.signError(type, qtyGrams)?.let { throw IllegalArgumentException(it) }
        if (unitCostPaisaPerKg != null) require(unitCostPaisaPerKg >= 0) { "ইউনিট খরচ ঋণাত্মক হতে পারে না" }
        val item = itemDao.getById(itemId)
        require(item != null && item.deletedAt == null) { "আইটেম পাওয়া যায়নি" }
        val wh = warehouseDao.getById(warehouseId)
        require(wh != null && wh.deletedAt == null) { "গুদাম পাওয়া যায়নি" }
        if (isRestricted(item) && qtyGrams < 0 && type in ISSUE_TYPES) {
            session.require(Permission.INVENTORY_ISSUE_MEDICINE)
        }
        if (lotId != null) {
            val lot = lotDao.getById(lotId)
            require(lot != null && lot.deletedAt == null) { "লট পাওয়া যায়নি" }
            require(lot.itemId == itemId) { "লটটি এই আইটেমের নয়" }
            if (qtyGrams < 0 && type in QC_GATED_TYPES) {
                require(StockCalc.isIssuable(lot.qcStatus, item.requiresQc, lot.expiryAt, System.currentTimeMillis())) {
                    "লট ${lot.batchNo} ব্যবহারযোগ্য নয় (QC: ${QcStatus.labelBn(lot.qcStatus)} বা মেয়াদোত্তীর্ণ)"
                }
            }
        }
        val id = newId()
        movementDao.insert(
            StockMovementEntity(
                id = id, itemId = itemId, lotId = lotId, warehouseId = warehouseId, type = type,
                qtyGrams = qtyGrams, refType = refType, refId = refId, createdBy = userId,
                createdAt = stamper.now(), deviceId = stamper.deviceId,
                unitCostPaisaPerKg = unitCostPaisaPerKg, note = note,
            )
        )
        if (qtyGrams < 0) alertIfNegative(item, itemId, warehouseId, lotId, id)
        return id
    }

    /** স্টক অ্যাডজাস্টমেন্ট (গণনার পর মিল করা); [deltaGrams] ধনাত্মক = বাড়ানো, ঋণাত্মক = কমানো। কারণ বাধ্যতামূলক। */
    suspend fun adjust(itemId: String, lotId: String?, warehouseId: String, deltaGrams: Double, reason: String): String {
        session.require(Permission.INVENTORY)
        require(reason.isNotBlank()) { "অ্যাডজাস্টমেন্টের কারণ লিখতে হবে" }
        if (deltaGrams < 0) {
            val available = stockGrams(itemId, warehouseId, lotId)
            require(StockCalc.hasEnough(available, -deltaGrams)) {
                "স্টকের চেয়ে বেশি কমানো যাবে না (আছে ${com.feederp.core.Weight.formatKg(available)})"
            }
        }
        val id = record(itemId, lotId, warehouseId, MovementType.ADJUSTMENT, deltaGrams, null, "ADJUSTMENT", null, reason.trim())
        audit.log("STOCK_ADJUST", "stock_movements", id, "item=$itemId qty=$deltaGrams কারণ=${reason.trim()}")
        return id
    }

    /** এক গুদাম থেকে অন্য গুদামে ট্রান্সফার (দুটি মুভমেন্ট, একই refId); ফেরত: ট্রান্সফার refId। */
    suspend fun transfer(
        itemId: String,
        lotId: String?,
        fromWarehouseId: String,
        toWarehouseId: String,
        qtyGrams: Double,
        note: String? = null,
    ): String {
        session.require(Permission.INVENTORY)
        require(qtyGrams > StockCalc.EPS) { "পরিমাণ শূন্যের বেশি হতে হবে" }
        require(fromWarehouseId != toWarehouseId) { "উৎস ও গন্তব্য গুদাম আলাদা হতে হবে" }
        val item = itemDao.getById(itemId)
        require(item != null && item.deletedAt == null) { "আইটেম পাওয়া যায়নি" }
        val to = warehouseDao.getById(toWarehouseId)
        require(to != null && to.deletedAt == null) { "গন্তব্য গুদাম পাওয়া যায়নি" }
        if (isRestricted(item)) require(to.isMedicineStore) { "ওষুধ/নিয়ন্ত্রিত আইটেম শুধু ওষুধ গুদামে ট্রান্সফার করা যাবে" }
        val available = stockGrams(itemId, fromWarehouseId, lotId)
        require(StockCalc.hasEnough(available, qtyGrams)) {
            "উৎস গুদামে পর্যাপ্ত স্টক নেই (আছে ${com.feederp.core.Weight.formatKg(available)})"
        }
        val ref = newId()
        db.withTransaction {
            record(itemId, lotId, fromWarehouseId, MovementType.TRANSFER, -qtyGrams, null, "TRANSFER", ref, note)
            record(itemId, lotId, toWarehouseId, MovementType.TRANSFER, qtyGrams, null, "TRANSFER", ref, note)
        }
        audit.log("STOCK_TRANSFER", "stock_movements", ref, "item=$itemId qty=$qtyGrams from=$fromWarehouseId to=$toWarehouseId")
        return ref
    }

    // ---------------- ভেতরের সহায়ক ----------------

    private suspend fun alertIfNegative(item: ItemEntity, itemId: String, warehouseId: String, lotId: String?, movementId: String) {
        val whBal = movementDao.sumGrams(itemId, warehouseId, null)
        val lotBal = if (lotId != null) movementDao.sumGrams(itemId, warehouseId, lotId) else 0.0
        if (StockCalc.isNegative(whBal) || StockCalc.isNegative(lotBal)) {
            audit.log(
                "NEGATIVE_STOCK", "stock_movements", movementId,
                "${item.name}: গুদাম ব্যালেন্স=$whBal, লট ব্যালেন্স=$lotBal (গ্রাম)",
            )
        }
    }

    companion object {
        /** ইস্যু ধরা হয় যেসব আউটগোয়িং টাইপকে (ট্রান্সফার নয়)। */
        private val ISSUE_TYPES = setOf(MovementType.PRODUCTION_OUT, MovementType.SALE_OUT, MovementType.ADJUSTMENT)
        private val QC_GATED_TYPES = setOf(MovementType.PRODUCTION_OUT, MovementType.SALE_OUT)

        /** ওষুধ বা নিয়ন্ত্রিত আইটেম। */
        fun isRestricted(item: ItemEntity): Boolean = item.type == ItemType.MEDICINE || item.isControlled
    }
}
