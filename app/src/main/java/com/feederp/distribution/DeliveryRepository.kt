package com.feederp.distribution

import androidx.room.withTransaction
import com.feederp.accounts.LedgerLine
import com.feederp.accounts.LedgerRepository
import com.feederp.core.AccountCodes
import com.feederp.core.DocNumbers
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.core.Stamper
import com.feederp.core.newId
import com.feederp.data.ItemDao
import com.feederp.data.AppDatabase
import com.feederp.data.ItemType
import com.feederp.data.MovementType
import com.feederp.data.PartyDao
import com.feederp.data.distribution.DeliveryAllocationEntity
import com.feederp.data.distribution.DeliveryDao
import com.feederp.data.distribution.DeliveryLineEntity
import com.feederp.data.distribution.DeliveryNoteEntity
import com.feederp.data.distribution.DistStatus
import com.feederp.data.sales.SalesInvoiceDao
import com.feederp.data.sales.SalesStatus
import com.feederp.inventory.AuditRepository
import com.feederp.inventory.LotStock
import com.feederp.inventory.StockCalc
import com.feederp.inventory.StockRepository
import com.feederp.sales.SalesCalc
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

data class PlanLineInput(val invoiceLineId: String, val qtyGrams: Double)

/**
 * ডেলিভারি নোট (চালান): প্ল্যান → লোড → ডেলিভারি নিশ্চিত।
 *  - প্ল্যান: ইনভয়েসের বাকি পরিমাণের বেশি প্ল্যান করা যায় না (আংশিক ডেলিভারি সম্ভব)।
 *  - লোড: FEFO ও QC-পাস, মেয়াদ-উত্তীর্ণ নয় এমন লট থেকে SALE_OUT। একবারই হয় (দ্বিতীয়বার ডাকলে ত্রুটি, স্টক দ্বিগুণ কমে না)।
 *  - নিশ্চিত: ডেলিভারি হওয়া পরিমাণের COGS পোস্ট; না-পৌঁছানো অংশ RETURN_IN হয়ে একই লটে ফেরে।
 * সীমাবদ্ধতা: COGS ও ফেরত নিশ্চিত করার সময়ের ওয়েটেড-অ্যাভারেজ খরচে হয় (লোডের সময়ের সাথে সামান্য ফারাক হতে পারে)।
 * ট্রিপ/গাড়ি/POD/ক্যাশ-মিলানো ডিস্ট্রিবিউশন মডিউলে (ধাপ ৩) যোগ হবে।
 */
@Singleton
class DeliveryRepository @Inject constructor(
    private val db: AppDatabase,
    private val deliveryDao: DeliveryDao,
    private val invoiceDao: SalesInvoiceDao,
    private val partyDao: PartyDao,
    private val itemDao: ItemDao,
    private val stock: StockRepository,
    private val ledger: LedgerRepository,
    private val docNumbers: DocNumbers,
    private val stamper: Stamper,
    private val session: SessionManager,
    private val audit: AuditRepository,
) {
    private companion object { const val EPS = 0.0005 }

    fun observeNotes(): Flow<List<DeliveryNoteEntity>> = deliveryDao.observeNotes()
    fun observeLines(noteId: String): Flow<List<DeliveryLineEntity>> = deliveryDao.observeLinesOf(noteId)
    suspend fun note(id: String): DeliveryNoteEntity? = deliveryDao.getNote(id)
    suspend fun lines(noteId: String): List<DeliveryLineEntity> = deliveryDao.linesOf(noteId)

    fun observePendingInvoices() = invoiceDao.observePendingDelivery()

    /** ইনভয়েসের প্রতিটি লাইন ও তার এখনও প্ল্যান-না-করা বাকি পরিমাণ (গ্রাম)। */
    suspend fun remainingByLine(invoiceId: String): List<Pair<com.feederp.data.sales.SalesInvoiceLineEntity, Double>> =
        invoiceDao.linesFor(invoiceId).map { it to (it.qtyGrams - committedGrams(it.id)).coerceAtLeast(0.0) }

    /** ইনভয়েস লাইনের বিপরীতে এখন পর্যন্ত বরাদ্দ: ডেলিভারি হয়ে গেলে প্রকৃত ডেলিভারি, নইলে প্ল্যান করা পরিমাণ। */
    suspend fun committedGrams(invoiceLineId: String): Double {
        val lines = deliveryDao.allLines().filter { it.invoiceLineId == invoiceLineId }
        if (lines.isEmpty()) return 0.0
        val notes = deliveryDao.getNotes(lines.map { it.deliveryNoteId }.distinct()).associateBy { it.id }
        return lines.sumOf { l ->
            val n = notes[l.deliveryNoteId] ?: return@sumOf 0.0
            if (n.deliveredAt != null) l.deliveredGrams else l.plannedGrams
        }
    }

    suspend fun planNote(invoiceId: String, plan: List<PlanLineInput>, dueDate: Long?, note: String?): String {
        session.require(Permission.DELIVERY_DISPATCH)
        val userId = session.userId
        require(plan.isNotEmpty()) { "কমপক্ষে একটি লাইনের পরিমাণ দিন" }
        val invoice = invoiceDao.getById(invoiceId)
        require(invoice != null && invoice.deletedAt == null) { "ইনভয়েস পাওয়া যায়নি" }
        require(invoice.status == SalesStatus.CONFIRMED || invoice.status == SalesStatus.PARTIAL) {
            "এই ইনভয়েসে ডেলিভারি প্ল্যান করা যায় না (${SalesStatus.labelBn(invoice.status)})"
        }
        val party = partyDao.getById(invoice.partyId)
        val noteId = newId()
        val challanNo = docNumbers.next("CH")
        db.withTransaction {
            val now = stamper.now()
            val rows = plan.map { p ->
                require(p.qtyGrams.isFinite() && p.qtyGrams > EPS) { "ডেলিভারির পরিমাণ ০ এর বেশি হতে হবে" }
                val line = invoiceDao.getLine(p.invoiceLineId)
                require(line != null && line.invoiceId == invoiceId && line.deletedAt == null) { "ইনভয়েস লাইন পাওয়া যায়নি" }
                val item = itemDao.getById(line.itemId)
                val remaining = line.qtyGrams - committedGrams(line.id)
                require(p.qtyGrams <= remaining + EPS) {
                    "${item?.name ?: "আইটেম"}: বাকি আছে ${fmtKg(remaining.coerceAtLeast(0.0))} কেজি, এর বেশি প্ল্যান করা যায় না"
                }
                DeliveryLineEntity(
                    id = newId(), deliveryNoteId = noteId, invoiceId = invoiceId, invoiceLineId = line.id, itemId = line.itemId,
                    plannedGrams = p.qtyGrams, updatedAt = now, deviceId = stamper.deviceId,
                )
            }
            require(rows.map { it.invoiceLineId }.distinct().size == rows.size) { "একই লাইন দুইবার দেওয়া যাবে না" }
            deliveryDao.upsertNote(
                DeliveryNoteEntity(
                    id = noteId, challanNo = challanNo, invoiceId = invoiceId, invoiceNo = invoice.invoiceNo,
                    partyId = invoice.partyId, warehouseId = invoice.warehouseId, address = party?.address,
                    dueDate = dueDate, note = note?.trim()?.ifBlank { null }, createdBy = userId,
                    updatedAt = now, deviceId = stamper.deviceId,
                )
            )
            deliveryDao.upsertLines(rows)
        }
        audit.log("PLAN_DELIVERY", "delivery_notes", noteId, "$challanNo (${invoice.invoiceNo})")
        return noteId
    }

    /** লোড: FEFO বরাদ্দে স্টক কমে। যেকোনো লাইনে পর্যাপ্ত QC-পাস স্টক না থাকলে কিছুই কমে না। */
    suspend fun loadNote(noteId: String) {
        session.require(Permission.DELIVERY_DISPATCH)
        val userId = session.userId
        db.withTransaction {
            val n = deliveryDao.getNote(noteId)
            require(n != null && n.deletedAt == null) { "ডেলিভারি নোট পাওয়া যায়নি" }
            require(n.loadedAt == null) { "এই নোটের মাল ইতিমধ্যে লোড হয়েছে" }
            val lines = deliveryDao.linesOf(noteId)
            require(lines.isNotEmpty()) { "নোটে কোনো লাইন নেই" }

            // ধাপ ১: বরাদ্দ ঠিক করা (স্টক এখনও অপরিবর্তিত); একই আইটেমের একাধিক লাইনে একই লট দুইবার গোনা হয় না
            val used = HashMap<String, Double>()
            val plans = ArrayList<Pair<DeliveryLineEntity, List<com.feederp.inventory.LotAllocation>>>()
            for (l in lines) {
                val lots: List<LotStock> = stock.lotsFefo(l.itemId, n.warehouseId)
                    .map { it.copy(qtyGrams = it.qtyGrams - (used[it.lotId] ?: 0.0)) }
                val alloc = StockCalc.allocateFefo(lots, l.plannedGrams)
                if (!alloc.isFullyCovered) {
                    val name = itemDao.getById(l.itemId)?.name ?: "আইটেম"
                    throw IllegalStateException("$name: ${fmtKg(alloc.shortfallGrams)} কেজি ঘাটতি (শুধু QC-পাস ও মেয়াদোত্তীর্ণ নয় এমন স্টক গোনা হয়েছে)")
                }
                alloc.allocations.forEach { used[it.lotId] = (used[it.lotId] ?: 0.0) + it.qtyGrams }
                plans += l to alloc.allocations
            }

            // ধাপ ২: মুভমেন্ট ও বরাদ্দ লেখা
            val now = stamper.now()
            for ((l, allocs) in plans) {
                val cost = stock.weightedAvgCostPaisaPerKg(l.itemId)
                for (a in allocs) {
                    val movementId = stock.record(
                        itemId = l.itemId, lotId = a.lotId, warehouseId = n.warehouseId, type = MovementType.SALE_OUT,
                        qtyGrams = -a.qtyGrams, unitCostPaisaPerKg = cost, refType = "DELIVERY_NOTE", refId = noteId,
                        note = "চালান ${n.challanNo}",
                    )
                    deliveryDao.insertAllocation(
                        DeliveryAllocationEntity(
                            id = newId(), deliveryLineId = l.id, deliveryNoteId = noteId, itemId = l.itemId, lotId = a.lotId,
                            qtyGrams = a.qtyGrams, movementId = movementId, createdBy = userId, createdAt = now, deviceId = stamper.deviceId,
                        )
                    )
                }
            }
            deliveryDao.upsertLines(plans.map { (l, _) -> l.copy(loadedGrams = l.plannedGrams, updatedAt = now, deviceId = stamper.deviceId) })
            deliveryDao.upsertNote(n.copy(loadedAt = now, updatedAt = now, deviceId = stamper.deviceId))
        }
        audit.log("LOAD_DELIVERY", "delivery_notes", noteId, null)
    }

    /**
     * ডেলিভারি নিশ্চিত। [deliveredGrams]: লাইন আইডি → প্রকৃত ডেলিভারি; না দিলে লোড করা পুরোটাই ধরা হয়।
     * পৌঁছায়নি এমন অংশ একই লটে স্টকে ফেরে এবং ইনভয়েসে আবার প্ল্যানযোগ্য থাকে।
     */
    suspend fun confirmDelivery(noteId: String, receivedBy: String, deliveredGrams: Map<String, Double> = emptyMap()) {
        session.require(Permission.DELIVERY_DISPATCH)
        val userId = session.userId
        require(receivedBy.isNotBlank()) { "কে মাল বুঝে নিয়েছে তার নাম লিখুন" }
        db.withTransaction {
            val n = deliveryDao.getNote(noteId)
            require(n != null && n.deletedAt == null) { "ডেলিভারি নোট পাওয়া যায়নি" }
            require(n.loadedAt != null) { "আগে মাল লোড করুন" }
            require(n.deliveredAt == null) { "এই নোটের ডেলিভারি ইতিমধ্যে নিশ্চিত করা হয়েছে" }
            val lines = deliveryDao.linesOf(noteId)
            val now = stamper.now()
            val cogsRaw = HashMap<String, Long>()   // ইনভেন্টরি খাত → মূল্য
            val updated = lines.map { l ->
                val d = deliveredGrams[l.id] ?: l.loadedGrams
                require(d.isFinite() && d >= 0 && d <= l.loadedGrams + EPS) { "ডেলিভারি পরিমাণ লোডের বেশি বা ঋণাত্মক হতে পারে না" }
                val delivered = minOf(d, l.loadedGrams)
                val back = l.loadedGrams - delivered
                val item = itemDao.getById(l.itemId)
                val cost = stock.weightedAvgCostPaisaPerKg(l.itemId)
                if (back > EPS) {
                    val allocs = deliveryDao.allocationsOfLine(l.id).map { it.lotId to it.qtyGrams }
                    for ((lotId, qty) in SalesCalc.spreadAcrossLots(allocs, back)) {
                        stock.record(
                            itemId = l.itemId, lotId = lotId, warehouseId = n.warehouseId, type = MovementType.RETURN_IN,
                            qtyGrams = qty, unitCostPaisaPerKg = cost, refType = "DELIVERY_NOTE", refId = noteId,
                            note = "অসম্পূর্ণ ডেলিভারি, চালান ${n.challanNo}",
                        )
                    }
                }
                val account = if (item?.type == ItemType.FINISHED_GOOD) AccountCodes.INVENTORY_FG else AccountCodes.INVENTORY_RAW
                cogsRaw[account] = (cogsRaw[account] ?: 0L) + StockCalc.valuePaisa(delivered, cost)
                l.copy(deliveredGrams = delivered, returnedGrams = if (back > EPS) back else 0.0, updatedAt = now, deviceId = stamper.deviceId)
            }
            val totalCogs = cogsRaw.values.sum()
            if (totalCogs > 0) {
                val ledgerLines = ArrayList<LedgerLine>()
                ledgerLines += LedgerLine(AccountCodes.COGS, debitPaisa = totalCogs)
                cogsRaw.filterValues { it > 0 }.forEach { (acc, v) -> ledgerLines += LedgerLine(acc, creditPaisa = v) }
                ledger.post(
                    voucherType = "COGS", refType = "DELIVERY_NOTE", refId = noteId,
                    memo = "বিক্রীত পণ্যের খরচ, চালান ${n.challanNo}", lines = ledgerLines, entryDate = now,
                )
            }
            deliveryDao.upsertLines(updated)
            val noteStatus = SalesCalc.deliveryStatus(updated.map { it.plannedGrams to it.deliveredGrams })
            deliveryDao.upsertNote(
                n.copy(
                    status = if (noteStatus == SalesCalc.DELIVERY_DELIVERED) DistStatus.NOTE_DELIVERED else DistStatus.NOTE_PARTIAL,
                    deliveredAt = now, receivedBy = receivedBy.trim(), updatedAt = now, deviceId = stamper.deviceId,
                )
            )
            refreshInvoiceStatus(n.invoiceId, now)
        }
        audit.log("CONFIRM_DELIVERY", "delivery_notes", noteId, "গ্রহীতা: ${receivedBy.trim()}")
    }

    /** ইনভয়েসের সব লাইনের মোট ডেলিভারি দেখে ইনভয়েস স্ট্যাটাস (নিশ্চিত/আংশিক/সম্পন্ন) ঠিক করে। */
    private suspend fun refreshInvoiceStatus(invoiceId: String, now: Long) {
        val inv = invoiceDao.getById(invoiceId) ?: return
        if (inv.status == SalesStatus.CANCELLED) return
        val invLines = invoiceDao.linesFor(invoiceId)
        val dl = deliveryDao.allLines().filter { it.invoiceId == invoiceId }
        val notes = deliveryDao.getNotes(dl.map { it.deliveryNoteId }.distinct()).associateBy { it.id }
        val pairs = invLines.map { il ->
            il.qtyGrams to dl.filter { it.invoiceLineId == il.id && notes[it.deliveryNoteId]?.deliveredAt != null }.sumOf { it.deliveredGrams }
        }
        val status = when (SalesCalc.deliveryStatus(pairs)) {
            SalesCalc.DELIVERY_DELIVERED -> SalesStatus.DELIVERED
            SalesCalc.DELIVERY_PARTIAL -> SalesStatus.PARTIAL
            else -> SalesStatus.CONFIRMED
        }
        if (status != inv.status) invoiceDao.updateStatus(invoiceId, status, now, stamper.deviceId)
    }

    private fun fmtKg(grams: Double): String = String.format(java.util.Locale.US, "%.3f", grams / 1000.0).trimEnd('0').trimEnd('.')
}
