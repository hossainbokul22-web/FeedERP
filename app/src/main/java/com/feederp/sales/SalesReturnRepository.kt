package com.feederp.sales

import androidx.room.withTransaction
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
import com.feederp.data.ItemType
import com.feederp.data.MovementType
import com.feederp.data.distribution.DeliveryDao
import com.feederp.data.sales.SalesInvoiceDao
import com.feederp.data.sales.SalesReturnDao
import com.feederp.data.sales.SalesReturnEntity
import com.feederp.data.sales.SalesReturnLineEntity
import com.feederp.data.sales.SalesStatus
import com.feederp.inventory.AuditRepository
import com.feederp.inventory.StockCalc
import com.feederp.inventory.StockRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

data class ReturnLineInput(val invoiceLineId: String, val qtyGrams: Double)

/** একটি ইনভয়েস-লাইনে ফেরতযোগ্য পরিমাণের হিসাব (UI-র জন্য)। */
data class ReturnableLine(
    val invoiceLineId: String, val itemId: String, val deliveredGrams: Double, val returnedGrams: Double,
    val unitPricePaisaPerKg: Long,
) { val returnableGrams: Double get() = (deliveredGrams - returnedGrams).coerceAtLeast(0.0) }

/**
 * বিক্রয় ফেরত (ক্রেডিট নোট) — শুধু APPROVE অনুমতি (ম্যানেজার/মালিক)।
 *  - শুধু ডেলিভারি হওয়া পরিমাণের বেশি-না ফেরত নেওয়া যায় (আগের ফেরত বাদে)।
 *  - ক্রেডিট = লাইনের ডিসকাউন্ট-সমন্বিত দামের আনুপাতিক; শেষ ফেরতে বাকিটা নেয় (রাউন্ডিং জমে না)।
 *  - লেজার: Dr বিক্রয় ফেরত (4010) / Cr ক্রেতার বাকি। ইনভয়েসের বকেয়া ফেরতের অঙ্ক কমে ([SalesCalc.outstandingPaisa])।
 *  - [restock] সত্য: মাল ডেলিভারির লটগুলোতেই RETURN_IN হয় ও ইনভেন্টরি/COGS উল্টে যায় (বর্তমান ওজন-গড় খরচে)।
 *    মিথ্যা (ক্ষতিগ্রস্ত/নষ্ট): স্টকে ফেরে না; COGS যেমন ছিল তেমনই থাকে (ক্ষতি হিসেবে)। ভালো কি না তা মানুষ ঠিক করবে — লট QC স্ট্যাটাস বদলায় না।
 * সীমা: নগদ টাকা ফেরত স্বয়ংক্রিয় নয় — আগে আদায় হয়ে থাকলে ক্রেতার হিসাবে ক্রেডিট (ঋণাত্মক বাকি) থাকে; ফেরত দিতে হাতে সমন্বয় করুন।
 */
@Singleton
class SalesReturnRepository @Inject constructor(
    private val db: AppDatabase,
    private val returnDao: SalesReturnDao,
    private val invoiceDao: SalesInvoiceDao,
    private val deliveryDao: DeliveryDao,
    private val itemDao: ItemDao,
    private val stock: StockRepository,
    private val ledger: LedgerRepository,
    private val docNumbers: DocNumbers,
    private val stamper: Stamper,
    private val session: SessionManager,
    private val audit: AuditRepository,
) {
    private companion object { const val EPS = 0.0005 }

    fun observeAll(): Flow<List<SalesReturnEntity>> = returnDao.observeAll()
    suspend fun forInvoice(invoiceId: String): List<SalesReturnEntity> = returnDao.forInvoice(invoiceId)
    suspend fun linesOf(returnId: String): List<SalesReturnLineEntity> = returnDao.linesFor(returnId)

    suspend fun returnable(invoiceId: String): List<ReturnableLine> {
        val lines = invoiceDao.linesFor(invoiceId)
        val dl = deliveryDao.allLines().filter { it.invoiceId == invoiceId }
        val notes = deliveryDao.getNotes(dl.map { it.deliveryNoteId }.distinct()).associateBy { it.id }
        return lines.map { il ->
            val delivered = dl.filter { it.invoiceLineId == il.id && notes[it.deliveryNoteId]?.deliveredAt != null }.sumOf { it.deliveredGrams }
            ReturnableLine(il.id, il.itemId, delivered, returnDao.returnedGramsForLine(il.id), il.unitPricePaisaPerKg)
        }
    }

    /** ফেরত: ক্রেডিট নোটের আইডি। */
    suspend fun createReturn(invoiceId: String, inputs: List<ReturnLineInput>, reason: String, restock: Boolean, returnDate: Long): String {
        session.require(Permission.APPROVE)
        val userId = session.userId
        require(reason.isNotBlank()) { "ফেরতের কারণ লিখুন" }
        val wanted = inputs.filter { it.qtyGrams > 0 }
        require(wanted.isNotEmpty()) { "কমপক্ষে একটি লাইনে ফেরত পরিমাণ দিন" }
        require(wanted.all { it.qtyGrams.isFinite() }) { "পরিমাণ ঠিক নয়" }
        require(wanted.map { it.invoiceLineId }.toSet().size == wanted.size) { "একই লাইন দুইবার দেওয়া যাবে না" }

        val returnId = newId()
        val returnNo = docNumbers.next("SRT")
        db.withTransaction {
            val inv = invoiceDao.getById(invoiceId)
            require(inv != null && inv.deletedAt == null) { "ইনভয়েস পাওয়া যায়নি" }
            require(inv.status == SalesStatus.PARTIAL || inv.status == SalesStatus.DELIVERED) { "ডেলিভারি হয়নি এমন ইনভয়েসে ফেরত হয় না" }
            val invLines = invoiceDao.linesFor(invoiceId)
            val shares = SalesCalc.allocateDiscount(invLines.map { it.lineTotalPaisa }, inv.discountPaisa)
            val avail = returnable(invoiceId).associateBy { it.invoiceLineId }
            val priorLines = returnDao.forInvoice(invoiceId).flatMap { returnDao.linesFor(it.id) }
            val now = stamper.now()

            val outLines = ArrayList<SalesReturnLineEntity>()
            val invRaw = HashMap<String, Long>()
            for (w in wanted) {
                val idx = invLines.indexOfFirst { it.id == w.invoiceLineId }
                require(idx >= 0) { "ইনভয়েস লাইন পাওয়া যায়নি" }
                val il = invLines[idx]; val a = avail.getValue(il.id)
                require(w.qtyGrams <= a.returnableGrams + EPS) { "ফেরত পরিমাণ ফেরতযোগ্য (${a.returnableGrams / 1000} কেজি) এর বেশি" }
                val net = il.lineTotalPaisa - shares[idx]
                val creditedBefore = priorLines.filter { it.invoiceLineId == il.id }.sumOf { it.lineTotalPaisa }
                val fully = w.qtyGrams + a.returnedGrams >= il.qtyGrams - EPS
                val credit = if (fully) (net - creditedBefore).coerceAtLeast(0) else (net * (w.qtyGrams / il.qtyGrams)).roundToLong()
                outLines += SalesReturnLineEntity(
                    id = newId(), returnId = returnId, invoiceLineId = il.id, itemId = il.itemId, qtyGrams = w.qtyGrams,
                    unitPricePaisaPerKg = il.unitPricePaisaPerKg, lineTotalPaisa = credit, updatedAt = now, deviceId = stamper.deviceId,
                )
                if (restock) {
                    val allocs = deliveryDao.allLines().filter { it.invoiceLineId == il.id }
                        .flatMap { deliveryDao.allocationsOfLine(it.id) }.map { it.lotId to it.qtyGrams }
                    val spread = SalesCalc.spreadAcrossLots(allocs, w.qtyGrams)
                    check(spread.isNotEmpty()) { "মূল লট পাওয়া যায়নি; 'স্টকে ফেরত' ছাড়া ফেরত নিন" }
                    val cost = stock.weightedAvgCostPaisaPerKg(il.itemId)
                    val item = itemDao.getById(il.itemId)
                    for ((lotId, q) in spread) {
                        stock.record(
                            itemId = il.itemId, lotId = lotId, warehouseId = inv.warehouseId, type = MovementType.RETURN_IN, qtyGrams = q,
                            unitCostPaisaPerKg = cost, refType = "SALES_RETURN", refId = returnId, note = "ফেরত $returnNo",
                        )
                    }
                    val acc = if (item?.type == ItemType.FINISHED_GOOD) AccountCodes.INVENTORY_FG else AccountCodes.INVENTORY_RAW
                    invRaw[acc] = (invRaw[acc] ?: 0L) + StockCalc.valuePaisa(w.qtyGrams, cost)
                }
            }
            val total = outLines.sumOf { it.lineTotalPaisa }
            require(total > 0) { "ফেরতের ক্রেডিট ০ হচ্ছে" }
            val outstandingBefore = SalesCalc.outstandingPaisa(inv.totalPaisa, inv.paidPaisa, returnDao.returnedTotalForInvoice(invoiceId))
            // আদায়ের বেশি ফেরত হলে বাড়তি অংশ ক্রেতার ক্রেডিট হিসেবে থাকে — অনুমোদনকারী জানবেন
            val voucher = ledger.post(
                voucherType = "SALES_RETURN", refType = "SALES_RETURN", refId = returnId,
                memo = "বিক্রয় ফেরত $returnNo (${inv.invoiceNo})",
                lines = listOf(
                    LedgerLine(AccountCodes.SALES_RETURN, debitPaisa = total),
                    LedgerLine(AccountCodes.RECEIVABLE, creditPaisa = total, partyId = inv.partyId),
                ),
                entryDate = returnDate,
            )
            val cogsBack = invRaw.values.sum()
            if (cogsBack > 0) {
                val ls = ArrayList<LedgerLine>()
                invRaw.filterValues { it > 0 }.forEach { (acc, v) -> ls += LedgerLine(acc, debitPaisa = v) }
                ls += LedgerLine(AccountCodes.COGS, creditPaisa = cogsBack)
                ledger.post(
                    voucherType = "COGS_REVERSAL", refType = "SALES_RETURN", refId = returnId,
                    memo = "ফেরত মাল স্টকে, $returnNo", lines = ls, entryDate = returnDate,
                )
            }
            returnDao.insert(
                SalesReturnEntity(
                    id = returnId, returnNo = returnNo, invoiceId = invoiceId, partyId = inv.partyId, returnDate = returnDate,
                    totalPaisa = total, reason = reason.trim(), voucherId = voucher, createdBy = userId, createdAt = now, deviceId = stamper.deviceId,
                )
            )
            returnDao.insertLines(outLines)
            audit.log(
                "SALES_RETURN", "sales_returns", returnId,
                "$returnNo ${inv.invoiceNo} $total পয়সা ${if (restock) "স্টকে ফেরত" else "নষ্ট/স্টকে নয়"}" +
                    if (total > outstandingBefore) " [আদায়ের বেশি: ক্রেডিট ${(total - outstandingBefore)} পয়সা]" else "",
            )
        }
        return returnId
    }
}
