package com.feederp.purchase

import androidx.room.withTransaction
import com.feederp.accounts.ChartOfAccounts
import com.feederp.accounts.LedgerLine
import com.feederp.accounts.LedgerRepository
import com.feederp.core.AccountCodes
import com.feederp.core.DocNumbers
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.core.Stamper
import com.feederp.core.newId
import com.feederp.data.AppDatabase
import com.feederp.data.ItemDao
import com.feederp.data.MovementType
import com.feederp.data.PartyDao
import com.feederp.data.PartyType
import com.feederp.data.WarehouseDao
import com.feederp.data.purchase.GoodsReceiptDao
import com.feederp.data.purchase.GoodsReceiptEntity
import com.feederp.data.purchase.GoodsReceiptLineEntity
import com.feederp.data.purchase.PurchaseOrderDao
import com.feederp.data.purchase.PurchaseOrderEntity
import com.feederp.data.purchase.PurchaseOrderLineEntity
import com.feederp.data.purchase.SupplierPaymentDao
import com.feederp.data.purchase.SupplierPaymentEntity
import com.feederp.inventory.AuditRepository
import com.feederp.inventory.QcStatus
import com.feederp.inventory.StockCalc
import com.feederp.inventory.StockRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

data class PoLineInput(val itemId: String, val qtyGrams: Double, val ratePaisaPerKg: Long)

data class GrnLineInput(
    val itemId: String,
    val batchNo: String,
    val qtyGrams: Double,
    val ratePaisaPerKg: Long,
    val mfgAt: Long? = null,
    val expiryAt: Long? = null,
    val moisturePct: Double? = null,
)

/** PO এর একটি আইটেমের অর্ডার/রিসিভ/বাকি (UI এর জন্য)। */
data class PoProgressRow(val itemId: String, val orderedGrams: Double, val receivedGrams: Double) {
    val remainingGrams: Double get() = PurchaseCalc.remainingGrams(orderedGrams, receivedGrams)
}

@Singleton
class PurchaseRepository @Inject constructor(
    private val db: AppDatabase,
    private val poDao: PurchaseOrderDao,
    private val grnDao: GoodsReceiptDao,
    private val paymentDao: SupplierPaymentDao,
    private val partyDao: PartyDao,
    private val itemDao: ItemDao,
    private val warehouseDao: WarehouseDao,
    private val stock: StockRepository,
    private val ledger: LedgerRepository,
    private val docNumbers: DocNumbers,
    private val stamper: Stamper,
    private val session: SessionManager,
    private val audit: AuditRepository,
) {
    // ---------------- পড়া ----------------

    fun observePos(): Flow<List<PurchaseOrderEntity>> = poDao.observeAll()
    fun observePo(id: String): Flow<PurchaseOrderEntity?> = poDao.observeById(id)
    fun observePoLines(poId: String): Flow<List<PurchaseOrderLineEntity>> = poDao.observeLines(poId)
    fun observeGrns(): Flow<List<GoodsReceiptEntity>> = grnDao.observeAll()
    fun observeGrnsForPo(poId: String): Flow<List<GoodsReceiptEntity>> = grnDao.observeForPo(poId)
    fun observeGrnLines(grnId: String): Flow<List<GoodsReceiptLineEntity>> = grnDao.observeLines(grnId)
    fun observeReceivedLinesForPo(poId: String): Flow<List<GoodsReceiptLineEntity>> = grnDao.observeLinesForPo(poId)
    fun observeSupplierPayments(supplierId: String): Flow<List<SupplierPaymentEntity>> =
        paymentDao.observeForSupplier(supplierId)

    /** + = সাপ্লায়ার আমাদের দেবে (অগ্রিম), - = আমরা সাপ্লায়ারকে দেব। */
    fun observeSupplierBalancePaisa(supplierId: String): Flow<Long> = ledger.observePartyBalancePaisa(supplierId)
    suspend fun supplierBalancePaisa(supplierId: String): Long = ledger.partyBalancePaisa(supplierId)

    /** PO এর প্রতিটি আইটেমের অর্ডার/রিসিভ/বাকি। */
    suspend fun poProgress(poId: String): List<PoProgressRow> {
        val ordered = poDao.lines(poId)
        val received = grnDao.linesForPo(poId).groupBy { it.itemId }.mapValues { e -> e.value.sumOf { it.qtyGrams } }
        return ordered.groupBy { it.itemId }.map { (itemId, ls) ->
            PoProgressRow(itemId, ls.sumOf { it.qtyGrams }, received[itemId] ?: 0.0)
        }
    }

    // ---------------- PO ----------------

    /** PO খসড়া তৈরি/সম্পাদনা (শুধু DRAFT সম্পাদনা করা যায়)। ফেরত: poId। */
    suspend fun savePo(
        poId: String?,
        supplierId: String,
        orderDate: Long,
        expectedDate: Long?,
        note: String?,
        lines: List<PoLineInput>,
    ): String {
        session.require(Permission.PURCHASE)
        val userId = session.userId
        val supplier = partyDao.getById(supplierId)
        require(supplier != null && supplier.deletedAt == null && supplier.type == PartyType.SUPPLIER) { "সাপ্লায়ার বাছাই করুন" }
        require(lines.isNotEmpty()) { "অন্তত একটি আইটেম যোগ করুন" }
        require(lines.map { it.itemId }.toSet().size == lines.size) { "একই আইটেম দুইবার দেওয়া যাবে না" }
        lines.forEach {
            require(it.qtyGrams > StockCalc.EPS) { "পরিমাণ শূন্যের বেশি হতে হবে" }
            require(it.ratePaisaPerKg >= 0) { "দর ঋণাত্মক হতে পারে না" }
            val item = itemDao.getById(it.itemId)
            require(item != null && item.deletedAt == null) { "আইটেম পাওয়া যায়নি" }
        }
        if (expectedDate != null) require(expectedDate >= orderDate) { "প্রত্যাশিত তারিখ অর্ডারের আগে হতে পারে না" }
        val existing = poId?.let { poDao.getById(it) }
        if (poId != null) {
            require(existing != null) { "PO পাওয়া যায়নি" }
            require(existing.status == PoStatus.DRAFT) { "শুধু খসড়া PO সম্পাদনা করা যায়" }
        }
        val id = poId ?: newId()
        val poNo = existing?.poNo ?: docNumbers.next("PO")
        val total = lines.sumOf { PurchaseCalc.lineTotalPaisa(it.qtyGrams, it.ratePaisaPerKg) }
        val now = stamper.now()
        db.withTransaction {
            poDao.upsert(
                PurchaseOrderEntity(
                    id = id, poNo = poNo, supplierId = supplierId, orderDate = orderDate, expectedDate = expectedDate,
                    status = PoStatus.DRAFT, totalPaisa = total, note = note?.trim()?.ifBlank { null },
                    createdBy = existing?.createdBy ?: userId, updatedAt = now, deviceId = stamper.deviceId,
                )
            )
            if (existing != null) {
                val old = poDao.lines(id).map { it.copy(deletedAt = now, updatedAt = now, deviceId = stamper.deviceId) }
                if (old.isNotEmpty()) poDao.upsertLines(old)
            }
            poDao.upsertLines(
                lines.map {
                    PurchaseOrderLineEntity(
                        id = newId(), poId = id, itemId = it.itemId, qtyGrams = it.qtyGrams,
                        ratePaisaPerKg = it.ratePaisaPerKg, updatedAt = now, deviceId = stamper.deviceId,
                    )
                }
            )
        }
        return id
    }

    /** PO অনুমোদন: DRAFT -> ORDERED (APPROVE পারমিশন, অর্থাৎ ম্যানেজার/মালিক)। */
    suspend fun approvePo(poId: String) {
        session.require(Permission.APPROVE)
        val po = poDao.getById(poId)
        require(po != null && po.deletedAt == null) { "PO পাওয়া যায়নি" }
        require(po.status == PoStatus.DRAFT) { "শুধু খসড়া PO অনুমোদন করা যায়" }
        poDao.upsert(po.copy(status = PoStatus.ORDERED, updatedAt = stamper.now(), deviceId = stamper.deviceId))
        audit.log("PO_APPROVE", "purchase_orders", poId, "${po.poNo} মোট ${po.totalPaisa} পয়সা")
    }

    /** PO বাতিল: খসড়া হলে PURCHASE, অনুমোদিত (এখনও কিছু রিসিভ হয়নি) হলে APPROVE লাগে। */
    suspend fun cancelPo(poId: String, reason: String) {
        val po = poDao.getById(poId)
        require(po != null && po.deletedAt == null) { "PO পাওয়া যায়নি" }
        require(reason.isNotBlank()) { "বাতিলের কারণ লিখতে হবে" }
        when (po.status) {
            PoStatus.DRAFT -> session.require(Permission.PURCHASE)
            PoStatus.ORDERED -> session.require(Permission.APPROVE)
            else -> throw IllegalArgumentException("এই অবস্থার PO বাতিল করা যায় না")
        }
        poDao.upsert(po.copy(status = PoStatus.CANCELLED, updatedAt = stamper.now(), deviceId = stamper.deviceId))
        audit.log("PO_CANCEL", "purchase_orders", poId, "${po.poNo}: ${reason.trim()}")
    }

    // ---------------- গুডস রিসিভ ----------------

    /**
     * গুডস রিসিভ: প্রতিটি লাইনে লট তৈরি (QC লাগলে PENDING), PURCHASE_IN মুভমেন্ট (খরচসহ), এবং
     * সাপ্লায়ার লেজারে (ডেবিট কাঁচামাল মজুদ, ক্রেডিট সাপ্লায়ার বাকি) পোস্ট। সব একই ট্রানজ্যাকশনে।
     * ফেরত: grnId।
     */
    suspend fun receiveGoods(
        poId: String?,
        supplierId: String,
        warehouseId: String,
        receivedAt: Long,
        note: String?,
        lines: List<GrnLineInput>,
    ): String {
        session.require(Permission.PURCHASE)
        val userId = session.userId
        val supplier = partyDao.getById(supplierId)
        require(supplier != null && supplier.deletedAt == null && supplier.type == PartyType.SUPPLIER) { "সাপ্লায়ার বাছাই করুন" }
        val wh = warehouseDao.getById(warehouseId)
        require(wh != null && wh.deletedAt == null) { "গুদাম বাছাই করুন" }
        require(lines.isNotEmpty()) { "অন্তত একটি আইটেম যোগ করুন" }

        // PO যাচাই ও বাকি পরিমাণ
        val po: PurchaseOrderEntity? = if (poId != null) poDao.getById(poId) else null
        if (poId != null) {
            require(po != null && po.deletedAt == null) { "PO পাওয়া যায়নি" }
            require(PoStatus.canReceive(po.status)) { "এই PO এখন রিসিভ করা যাবে না (${PoStatus.labelBn(po.status)})" }
            require(po.supplierId == supplierId) { "PO এর সাপ্লায়ার আলাদা" }
        }
        val orderedByItem: Map<String, Double> =
            if (poId != null) poDao.lines(poId).groupBy { it.itemId }.mapValues { e -> e.value.sumOf { it.qtyGrams } }
            else emptyMap()
        val remainingByItem: Map<String, Double>? =
            if (poId != null) {
                val receivedBefore = grnDao.linesForPo(poId).groupBy { it.itemId }.mapValues { e -> e.value.sumOf { it.qtyGrams } }
                orderedByItem.mapValues { (k, v) -> PurchaseCalc.remainingGrams(v, receivedBefore[k] ?: 0.0) }
            } else null

        // লাইন যাচাই
        val items = lines.map { l ->
            require(l.batchNo.isNotBlank()) { "ব্যাচ নম্বর দিন" }
            require(l.qtyGrams > StockCalc.EPS) { "পরিমাণ শূন্যের বেশি হতে হবে" }
            require(l.ratePaisaPerKg >= 0) { "দর ঋণাত্মক হতে পারে না" }
            val item = itemDao.getById(l.itemId) ?: throw IllegalArgumentException("আইটেম পাওয়া যায়নি")
            require(item.deletedAt == null) { "আইটেম পাওয়া যায়নি" }
            if (poId != null) require(l.itemId in orderedByItem) { "${item.name} এই PO তে নেই" }
            if (item.trackExpiry) require(l.expiryAt != null) { "${item.name}: মেয়াদ দিন" }
            if (l.expiryAt != null) require(l.expiryAt > receivedAt) { "${item.name}: মেয়াদোত্তীর্ণ পণ্য রিসিভ করা যাবে না" }
            if (l.mfgAt != null && l.expiryAt != null) require(l.expiryAt >= l.mfgAt) { "${item.name}: মেয়াদ উৎপাদনের আগে হতে পারে না" }
            if (l.moisturePct != null) require(l.moisturePct in 0.0..100.0) { "${item.name}: আর্দ্রতা ০-১০০% হতে হবে" }
            if (StockRepository.isRestricted(item)) require(wh.isMedicineStore) { "${item.name}: ওষুধ/নিয়ন্ত্রিত আইটেম ওষুধ গুদামে রিসিভ করুন" }
            item
        }.associateBy { it.id }

        val grnNo = docNumbers.next("GRN")
        val grnId = newId()
        val now = stamper.now()
        val lineTotals = lines.map { PurchaseCalc.lineTotalPaisa(it.qtyGrams, it.ratePaisaPerKg) }
        val total = lineTotals.sum()
        val seenExpected = HashSet<String>()

        db.withTransaction {
            val grnLines = ArrayList<GoodsReceiptLineEntity>()
            for (l in lines) {
                val item = items.getValue(l.itemId)
                val status = if (item.requiresQc) QcStatus.PENDING else QcStatus.PASSED
                val lotId = stock.createLot(l.itemId, l.batchNo, l.mfgAt, l.expiryAt, l.moisturePct, supplierId, status)
                stock.record(
                    itemId = l.itemId, lotId = lotId, warehouseId = warehouseId, type = MovementType.PURCHASE_IN,
                    qtyGrams = l.qtyGrams, unitCostPaisaPerKg = l.ratePaisaPerKg,
                    refType = "GRN", refId = grnId, note = "গুডস রিসিভ $grnNo",
                )
                // PO এর বাকি পরিমাণ প্রতি আইটেমের প্রথম লাইনে রাখা হয় (ভ্যারিয়েন্স দেখার জন্য)
                val expected = if (remainingByItem != null && seenExpected.add(l.itemId)) remainingByItem[l.itemId] else null
                grnLines += GoodsReceiptLineEntity(
                    id = newId(), grnId = grnId, itemId = l.itemId, lotId = lotId, batchNo = l.batchNo.trim(),
                    qtyGrams = l.qtyGrams, ratePaisaPerKg = l.ratePaisaPerKg, mfgAt = l.mfgAt, expiryAt = l.expiryAt,
                    moisturePct = l.moisturePct, expectedGrams = expected, updatedAt = now, deviceId = stamper.deviceId,
                )
            }
            grnDao.upsert(
                GoodsReceiptEntity(
                    id = grnId, grnNo = grnNo, poId = poId, supplierId = supplierId, warehouseId = warehouseId,
                    receivedAt = receivedAt, totalPaisa = total, note = note?.trim()?.ifBlank { null },
                    createdBy = userId, updatedAt = now, deviceId = stamper.deviceId,
                )
            )
            grnDao.upsertLines(grnLines)
            if (total > 0) {
                ledger.post(
                    voucherType = "PURCHASE", refType = "GRN", refId = grnId, memo = "গুডস রিসিভ $grnNo",
                    lines = listOf(
                        LedgerLine(AccountCodes.INVENTORY_RAW, debitPaisa = total),
                        LedgerLine(AccountCodes.PAYABLE, creditPaisa = total, partyId = supplierId),
                    ),
                    entryDate = receivedAt,
                )
            }
            if (po != null && poId != null) {
                val receivedNow = grnDao.linesForPo(poId).groupBy { it.itemId }.mapValues { e -> e.value.sumOf { it.qtyGrams } }
                val newStatus = PurchaseCalc.statusAfterReceipt(orderedByItem, receivedNow)
                if (newStatus != po.status) {
                    poDao.upsert(po.copy(status = newStatus, updatedAt = stamper.now(), deviceId = stamper.deviceId))
                }
            }
        }
        audit.log("GRN_CREATE", "goods_receipts", grnId, "$grnNo মোট $total পয়সা, লাইন ${lines.size}")
        return grnId
    }

    // ---------------- সাপ্লায়ার পেমেন্ট ----------------

    /**
     * সাপ্লায়ারকে পেমেন্ট: লেজারে ডেবিট সাপ্লায়ার বাকি, ক্রেডিট ক্যাশ/ব্যাংক/মোবাইল।
     * টাকা বের হয় বলে ACCOUNTS পারমিশন (ম্যানেজার/মালিক) লাগে। ফেরত: paymentId।
     */
    suspend fun paySupplier(
        supplierId: String,
        amountPaisa: Long,
        method: String,
        grnId: String?,
        note: String?,
        paidAt: Long,
    ): String {
        session.require(Permission.ACCOUNTS)
        val userId = session.userId
        require(amountPaisa > 0) { "পেমেন্টের অঙ্ক ০ এর বেশি হতে হবে" }
        require(method in PayMethod.all) { "পেমেন্ট মাধ্যম সঠিক নয়" }
        val supplier = partyDao.getById(supplierId)
        require(supplier != null && supplier.deletedAt == null && supplier.type == PartyType.SUPPLIER) { "সাপ্লায়ার বাছাই করুন" }
        if (grnId != null) {
            val grn = grnDao.getById(grnId)
            require(grn != null && grn.supplierId == supplierId) { "রিসিভটি এই সাপ্লায়ারের নয়" }
        }
        val id = newId()
        db.withTransaction {
            paymentDao.insert(
                SupplierPaymentEntity(
                    id = id, supplierId = supplierId, amountPaisa = amountPaisa, method = method, grnId = grnId,
                    note = note?.trim()?.ifBlank { null }, paidAt = paidAt, createdBy = userId,
                    createdAt = stamper.now(), deviceId = stamper.deviceId,
                )
            )
            ledger.post(
                voucherType = "SUPPLIER_PAYMENT", refType = "SUPPLIER_PAYMENT", refId = id,
                memo = "সাপ্লায়ার পেমেন্ট: ${supplier.name}",
                lines = listOf(
                    LedgerLine(AccountCodes.PAYABLE, debitPaisa = amountPaisa, partyId = supplierId),
                    LedgerLine(ChartOfAccounts.cashAccountForMethod(method), creditPaisa = amountPaisa),
                ),
                entryDate = paidAt,
            )
        }
        audit.log("SUPPLIER_PAYMENT", "supplier_payments", id, "${supplier.name} $amountPaisa পয়সা ($method)")
        return id
    }
}
