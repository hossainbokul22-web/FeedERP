package com.feederp.ui.inventory

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.data.ItemEntity
import com.feederp.data.MovementType
import com.feederp.data.StockMovementEntity
import com.feederp.data.WarehouseEntity
import com.feederp.inventory.ExpiryState
import com.feederp.inventory.StockCalc
import com.feederp.inventory.StockLevel
import com.feederp.inventory.StockRepository
import com.feederp.master.MasterRepository
import com.feederp.ui.master.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

// ---------------- স্টক তালিকা ----------------

data class StockRow(val item: ItemEntity, val qtyGrams: Double, val isLow: Boolean, val isNegative: Boolean)

@HiltViewModel
class StockListViewModel @Inject constructor(
    master: MasterRepository,
    stock: StockRepository,
) : BaseViewModel() {
    private val whFilter = MutableStateFlow<String?>(null)
    val selectedWarehouse: StateFlow<String?> = whFilter.asStateFlow()
    val warehouses: StateFlow<List<WarehouseEntity>> =
        master.observeWarehouses().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rows: StateFlow<List<StockRow>> = combine(
        master.observeItems(), stock.observeItemWarehouseTotals(), whFilter,
    ) { items, totals, wh ->
        val byItem = totals.filter { wh == null || it.warehouseId == wh }
            .groupBy { it.itemId }.mapValues { e -> e.value.sumOf { it.qtyGrams } }
        // মিনিমাম স্টক তুলনা শুধু সব-গুদামের মোট স্টকে অর্থপূর্ণ
        val lowIds = if (wh == null) {
            StockCalc.lowStock(items.map { StockLevel(it.id, it.minStockGrams, byItem[it.id] ?: 0.0) }).map { it.itemId }.toSet()
        } else emptySet()
        items.map { i ->
            val q = byItem[i.id] ?: 0.0
            StockRow(i, q, isLow = i.id in lowIds, isNegative = StockCalc.isNegative(q))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectWarehouse(id: String?) { whFilter.value = id }
}

// ---------------- লট / মেয়াদ ----------------

data class LotRow(
    val lotId: String,
    val itemName: String,
    val itemCode: String,
    val batchNo: String,
    val warehouseName: String,
    val qtyGrams: Double,
    val expiryAt: Long?,
    val qcStatus: String,
    val expiry: ExpiryState,
    val moisturePct: Double?,
)

@HiltViewModel
class LotListViewModel @Inject constructor(
    master: MasterRepository,
    private val stock: StockRepository,
    session: SessionManager,
) : BaseViewModel() {
    val canQc: Boolean = session.can(Permission.QC)

    val rows: StateFlow<List<LotRow>> = combine(
        master.observeItems(), master.observeWarehouses(), stock.observeLots(), stock.observeLotWarehouseBalances(),
    ) { items, warehouses, lots, balances ->
        val itemById = items.associateBy { it.id }
        val whById = warehouses.associateBy { it.id }
        val lotById = lots.associateBy { it.id }
        val now = System.currentTimeMillis()
        balances.mapNotNull { b ->
            val lot = lotById[b.lotId] ?: return@mapNotNull null
            if (kotlin.math.abs(b.qtyGrams) <= StockCalc.EPS) return@mapNotNull null
            LotRow(
                lotId = lot.id, itemName = itemById[b.itemId]?.name ?: "?", itemCode = itemById[b.itemId]?.code ?: "",
                batchNo = lot.batchNo, warehouseName = whById[b.warehouseId]?.name ?: "?", qtyGrams = b.qtyGrams,
                expiryAt = lot.expiryAt, qcStatus = lot.qcStatus, expiry = StockCalc.expiryState(lot.expiryAt, now),
                moisturePct = lot.moisturePct,
            )
        }.sortedWith(compareBy<LotRow> { it.expiryAt == null }.thenBy { it.expiryAt ?: Long.MAX_VALUE }.thenBy { it.itemName })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setQc(lotId: String, status: String) = launchSafe { stock.setLotQcStatus(lotId, status) }
}

// ---------------- মুভমেন্ট ----------------

data class MovementRow(
    val id: String,
    val createdAt: Long,
    val type: MovementType,
    val itemName: String,
    val warehouseName: String,
    val batchNo: String?,
    val qtyGrams: Double,
    val unitCostPaisaPerKg: Long?,
    val note: String?,
)

@HiltViewModel
class MovementListViewModel @Inject constructor(
    master: MasterRepository,
    stock: StockRepository,
    savedStateHandle: SavedStateHandle,
) : BaseViewModel() {
    private val itemId: String? = savedStateHandle.get<String>("itemId")
    val itemFilterName: StateFlow<String?> = master.observeItems()
        .map { items -> items.firstOrNull { it.id == itemId }?.name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val movements = if (itemId == null) stock.observeRecentMovements(300) else stock.observeMovementsByItem(itemId, 300)

    val rows: StateFlow<List<MovementRow>> = combine(
        master.observeItems(), master.observeWarehouses(), stock.observeLots(), movements,
    ) { items, warehouses, lots, moves: List<StockMovementEntity> ->
        val itemById = items.associateBy { it.id }
        val whById = warehouses.associateBy { it.id }
        val lotById = lots.associateBy { it.id }
        moves.map { m ->
            MovementRow(
                id = m.id, createdAt = m.createdAt, type = m.type, itemName = itemById[m.itemId]?.name ?: "?",
                warehouseName = whById[m.warehouseId]?.name ?: "?", batchNo = m.lotId?.let { lotById[it]?.batchNo },
                qtyGrams = m.qtyGrams, unitCostPaisaPerKg = m.unitCostPaisaPerKg, note = m.note,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

// ---------------- অ্যালার্ট ----------------

data class LowRow(val item: ItemEntity, val currentGrams: Double)
data class NegativeRow(val itemName: String, val warehouseName: String, val qtyGrams: Double)
data class ExpiryAlertRow(
    val itemName: String, val batchNo: String, val warehouseName: String,
    val qtyGrams: Double, val expiryAt: Long, val state: ExpiryState,
)
data class AlertsUi(
    val low: List<LowRow> = emptyList(),
    val negative: List<NegativeRow> = emptyList(),
    val expiry: List<ExpiryAlertRow> = emptyList(),
)

@HiltViewModel
class AlertsViewModel @Inject constructor(
    master: MasterRepository,
    stock: StockRepository,
) : BaseViewModel() {
    val ui: StateFlow<AlertsUi> = combine(
        master.observeItems(), master.observeWarehouses(), stock.observeItemWarehouseTotals(),
        stock.observeLots(), stock.observeLotWarehouseBalances(),
    ) { items, warehouses, itemWh, lots, lotWh ->
        val itemById = items.associateBy { it.id }
        val whById = warehouses.associateBy { it.id }
        val totals = itemWh.groupBy { it.itemId }.mapValues { e -> e.value.sumOf { it.qtyGrams } }
        val low = StockCalc.lowStock(items.map { StockLevel(it.id, it.minStockGrams, totals[it.id] ?: 0.0) })
            .mapNotNull { l -> itemById[l.itemId]?.let { LowRow(it, l.currentGrams) } }
        val negative = itemWh.filter { StockCalc.isNegative(it.qtyGrams) }.map {
            NegativeRow(itemById[it.itemId]?.name ?: "?", whById[it.warehouseId]?.name ?: "?", it.qtyGrams)
        }
        val lotById = lots.associateBy { it.id }
        val now = System.currentTimeMillis()
        val expiry = lotWh.mapNotNull { b ->
            val lot = lotById[b.lotId] ?: return@mapNotNull null
            val exp = lot.expiryAt ?: return@mapNotNull null
            if (b.qtyGrams <= StockCalc.EPS) return@mapNotNull null
            val st = StockCalc.expiryState(exp, now)
            if (st != ExpiryState.NEAR && st != ExpiryState.EXPIRED) return@mapNotNull null
            ExpiryAlertRow(itemById[b.itemId]?.name ?: "?", lot.batchNo, whById[b.warehouseId]?.name ?: "?", b.qtyGrams, exp, st)
        }.sortedBy { it.expiryAt }
        AlertsUi(low, negative, expiry)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlertsUi())
}
