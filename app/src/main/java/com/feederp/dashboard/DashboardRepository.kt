package com.feederp.dashboard

import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.data.production.BatchStatus
import com.feederp.data.sales.SalesStatus
import com.feederp.inventory.StockRepository
import com.feederp.master.MasterRepository
import com.feederp.production.ProductionRepository
import com.feederp.sales.SalesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/** null = এই ইউজারের এই তথ্য দেখার অনুমতি নেই (কার্ড লুকানো হবে)। */
data class DashboardData(
    val todaySalesPaisa: Long?,
    val last7DaysSalesPaisa: List<Long>?,
    val duePaisa: Long?,
    val lowStockCount: Int?,
    val pendingQcLots: Int?,
    val activeBatches: Int?,
)

object DashboardCalc {
    /** আজ সহ শেষ [days] দিনের বিক্রি (পুরনো → আজ)। invoices = (তারিখ millis, মোট পয়সা)। */
    fun salesByDay(invoices: List<Pair<Long, Long>>, nowMillis: Long, days: Int = 7): List<Long> {
        val starts = dayStarts(nowMillis, days)
        val out = LongArray(days)
        for ((at, amount) in invoices) {
            val idx = starts.indexOfLast { at >= it }
            if (idx >= 0 && at < starts[days - 1] + 86_400_000L) out[idx] += amount
        }
        return out.toList()
    }

    fun dayStarts(nowMillis: Long, days: Int): List<Long> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val list = ArrayList<Long>()
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        repeat(days) { list.add(cal.timeInMillis); cal.add(Calendar.DAY_OF_YEAR, 1) }
        return list
    }
}

@Singleton
class DashboardRepository @Inject constructor(
    private val sales: SalesRepository,
    private val stock: StockRepository,
    private val master: MasterRepository,
    private val production: ProductionRepository,
    private val session: SessionManager,
) {
    fun observe(): Flow<DashboardData> {
        val canSales = session.can(Permission.SALES)
        val canStock = session.can(Permission.INVENTORY) || session.can(Permission.PURCHASE)
        val canQc = session.can(Permission.QC)
        val canProd = session.can(Permission.PRODUCTION)
        val invoicesF = if (canSales) sales.observeInvoices() else flowOf(emptyList())
        val itemsF = if (canStock) master.observeItems() else flowOf(emptyList())
        val totalsF = if (canStock) stock.observeItemWarehouseTotals() else flowOf(emptyList())
        val lotsF = if (canQc) stock.observeLots() else flowOf(emptyList())
        val batchesF = if (canProd) production.observeBatches() else flowOf(emptyList())
        return combine(invoicesF, itemsF, totalsF, lotsF, batchesF) { invoices, items, totals, lots, batches ->
            val now = System.currentTimeMillis()
            val live = invoices.filter { it.status != SalesStatus.CANCELLED && it.deletedAt == null }
            val byDay = DashboardCalc.salesByDay(live.map { it.invoiceDate to it.totalPaisa }, now)
            val perItem = totals.groupBy { it.itemId }.mapValues { e -> e.value.sumOf { it.qtyGrams } }
            DashboardData(
                todaySalesPaisa = if (canSales) byDay.last() else null,
                last7DaysSalesPaisa = if (canSales) byDay else null,
                duePaisa = if (canSales) live.sumOf { (it.totalPaisa - it.paidPaisa).coerceAtLeast(0L) } else null,
                lowStockCount = if (canStock) items.count { it.minStockGrams > 0.0 && (perItem[it.id] ?: 0.0) < it.minStockGrams } else null,
                pendingQcLots = if (canQc) lots.count { it.qcStatus == "PENDING" } else null,
                activeBatches = if (canProd) batches.count { it.status == BatchStatus.IN_PROGRESS } else null,
            )
        }
    }
}
