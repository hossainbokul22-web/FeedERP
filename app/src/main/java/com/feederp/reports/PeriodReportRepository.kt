package com.feederp.reports

import com.feederp.accounts.AccountTotal
import com.feederp.accounts.AccountingCalc
import com.feederp.accounts.ProfitAndLoss
import com.feederp.data.MovementType
import com.feederp.data.StockMovementDao
import com.feederp.data.accounts.LedgerDao
import com.feederp.data.production.BatchStatus
import com.feederp.master.MasterRepository
import com.feederp.production.ProductionRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** একটি আইটেমের সময়কালের স্টক হিসাব (সব মান গ্রামে)। */
data class StockLine(
    val code: String, val name: String,
    val opening: Double, val purchased: Double, val produced: Double,
    val used: Double, val sold: Double, val other: Double, val closing: Double,
)

data class BatchSummary(val count: Int, val inputGrams: Double, val outputGrams: Double, val wastageGrams: Double, val totalCostPaisa: Long)

data class ProductLine(val name: String, val batches: Int, val outputGrams: Double)

data class BatchLine(val batchNo: String, val product: String, val inputGrams: Double, val outputGrams: Double, val wastageGrams: Double)

data class PeriodReport(
    val from: Long, val to: Long,
    val stock: List<StockLine>,
    val batches: BatchSummary,
    val products: List<ProductLine>,
    val batchLines: List<BatchLine>,
    val pnl: ProfitAndLoss,
)

/** মাস/বছরের রিপোর্ট: স্টক (শুরু→শেষ), কাঁচামাল ব্যবহার, ব্যাচ, লাভ-ক্ষতি। */
@Singleton
class PeriodReportRepository @Inject constructor(
    private val movementDao: StockMovementDao,
    private val master: MasterRepository,
    private val production: ProductionRepository,
    private val ledgerDao: LedgerDao,
) {
    suspend fun build(from: Long, to: Long): PeriodReport {
        val items = master.observeItems().first()
        val byId = items.associateBy { it.id }
        val opening = movementDao.balanceBefore(from).associate { it.itemId to it.qtyGrams }
        val rows = movementDao.periodByType(from, to)
        // ব্যবহার = ব্যাচ/ফর্মুলা অনুযায়ী আসল কাঁচামাল খরচ (বাতিল ব্যাচ বাদে), সময়কালে নথিভুক্ত
        val allBatches = production.observeBatches().first().filter { it.deletedAt == null && it.status != BatchStatus.CANCELLED }
        val usedByItem = HashMap<String, Double>()
        allBatches.forEach { b ->
            production.observeConsumption(b.id).first().forEach { c ->
                if (c.createdAt >= from && c.createdAt < to) usedByItem[c.itemId] = (usedByItem[c.itemId] ?: 0.0) + c.qtyGrams
            }
        }
        val perItem = rows.groupBy { it.itemId }
        val ids = (opening.keys + perItem.keys + usedByItem.keys).toSet()
        val stock = ids.mapNotNull { id ->
            val item = byId[id] ?: return@mapNotNull null
            val m = perItem[id].orEmpty()
            fun sum(vararg t: MovementType) = m.filter { r -> t.any { it.name == r.type } }.sumOf { it.qtyGrams }
            val purchased = sum(MovementType.PURCHASE_IN)
            val produced = sum(MovementType.PRODUCTION_IN)
            val used = usedByItem[id] ?: 0.0
            val sold = -sum(MovementType.SALE_OUT)
            val open = opening[id] ?: 0.0
            val close = open + m.sumOf { it.qtyGrams }   // আসল স্টক (সব মুভমেন্ট)
            val other = close - open - purchased - produced + used + sold   // সমন্বয়/রিটার্ন/ট্রান্সফার ইত্যাদি; টেবিল মেলাতে
            StockLine(item.code, item.name, open, purchased, produced, used, sold, other, close)
        }.filter { l ->
            listOf(l.opening, l.purchased, l.produced, l.used, l.sold, l.other, l.closing).any { abs(it) > 0.0005 }
        }.sortedBy { it.name }

        val done = allBatches.filter { b ->
            b.status == BatchStatus.COMPLETED &&
                (b.completedAt ?: b.orderedAt).let { it >= from && it < to }
        }
        val summary = BatchSummary(
            count = done.size,
            inputGrams = done.sumOf { it.actualInputGrams },
            outputGrams = done.sumOf { it.outputGoodGrams },
            wastageGrams = done.sumOf { it.wastageGrams },
            totalCostPaisa = done.sumOf { it.totalCostPaisa },
        )
        val products = done.groupBy { it.productItemId }.map { (pid, list) ->
            ProductLine(byId[pid]?.name ?: pid, list.size, list.sumOf { it.outputGoodGrams })
        }.sortedBy { it.name }

        val batchLines = done.sortedBy { it.completedAt ?: it.orderedAt }.map { b ->
            BatchLine(b.batchNo, byId[b.productItemId]?.name ?: b.productItemId, b.actualInputGrams, b.outputGoodGrams, b.wastageGrams)
        }
        val totals = ledgerDao.totalsBetween(from, to).map { AccountTotal(it.accountCode, it.debitPaisa, it.creditPaisa) }
        return PeriodReport(from, to, stock, summary, products, batchLines, AccountingCalc.profitAndLoss(totals))
    }
}
