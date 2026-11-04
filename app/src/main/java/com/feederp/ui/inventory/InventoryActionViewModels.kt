package com.feederp.ui.inventory

import androidx.lifecycle.viewModelScope
import com.feederp.core.Weight
import com.feederp.data.ItemEntity
import com.feederp.data.WarehouseEntity
import com.feederp.inventory.StockCalc
import com.feederp.inventory.StockRepository
import com.feederp.master.MasterRepository
import com.feederp.ui.master.BaseViewModel
import com.feederp.ui.master.parseKgToGrams
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------------- স্টক অ্যাডজাস্টমেন্ট ----------------

data class AdjustForm(
    val itemId: String? = null,
    val warehouseId: String? = null,
    val lotId: String? = null,
    val increase: Boolean = true,
    val qtyKg: String = "",
    val reason: String = "",
)

@HiltViewModel
class AdjustmentViewModel @Inject constructor(
    master: MasterRepository,
    private val stock: StockRepository,
) : BaseViewModel() {
    val items: StateFlow<List<ItemEntity>> =
        master.observeItems().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val warehouses: StateFlow<List<WarehouseEntity>> =
        master.observeWarehouses().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _form = MutableStateFlow(AdjustForm())
    val form: StateFlow<AdjustForm> = _form.asStateFlow()
    private val _current = MutableStateFlow<Double?>(null)
    /** নির্বাচিত আইটেম/গুদাম/লটের বর্তমান স্টক (গ্রাম)। */
    val currentGrams: StateFlow<Double?> = _current.asStateFlow()

    val lotChoices: StateFlow<List<LotChoice>> = combine(stock.observeLots(), _form) { lots, f ->
        val now = System.currentTimeMillis()
        lots.filter { it.itemId == f.itemId }
            .sortedBy { it.expiryAt ?: Long.MAX_VALUE }
            .map { LotChoice(it.id, "${it.batchNo} · ${InventoryLabels.expiryText(it.expiryAt, now)}", 0.0) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun update(f: (AdjustForm) -> AdjustForm) {
        _form.update(f)
        refreshCurrent()
    }

    private fun refreshCurrent() {
        val f = _form.value
        val item = f.itemId
        val wh = f.warehouseId
        if (item == null || wh == null) { _current.value = null; return }
        viewModelScope.launch {
            try { _current.value = stock.stockGrams(item, wh, f.lotId) } catch (e: Exception) { _current.value = null }
        }
    }

    fun save() {
        val f = _form.value
        launchSafe(
            onDone = {
                _form.update { it.copy(qtyKg = "", reason = "") }
                refreshCurrent()
                showMessage("অ্যাডজাস্টমেন্ট সংরক্ষিত হয়েছে")
            },
        ) {
            val itemId = f.itemId ?: throw IllegalArgumentException("আইটেম বাছাই করুন")
            val whId = f.warehouseId ?: throw IllegalArgumentException("গুদাম বাছাই করুন")
            val grams = parseKgToGrams(f.qtyKg) ?: throw IllegalArgumentException("পরিমাণ সঠিক সংখ্যা নয়")
            require(grams > StockCalc.EPS) { "পরিমাণ শূন্যের বেশি হতে হবে" }
            val item = items.value.firstOrNull { it.id == itemId }
            if (item != null && item.trackLot && f.lotId == null) throw IllegalArgumentException("লট বাছাই করুন")
            stock.adjust(itemId, f.lotId, whId, if (f.increase) grams else -grams, f.reason)
        }
    }
}

// ---------------- গুদাম ট্রান্সফার ----------------

data class TransferForm(
    val itemId: String? = null,
    val fromId: String? = null,
    val toId: String? = null,
    val lotId: String? = null,
    val qtyKg: String = "",
    val note: String = "",
)

@HiltViewModel
class TransferViewModel @Inject constructor(
    master: MasterRepository,
    private val stock: StockRepository,
) : BaseViewModel() {
    val items: StateFlow<List<ItemEntity>> =
        master.observeItems().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val warehouses: StateFlow<List<WarehouseEntity>> =
        master.observeWarehouses().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _form = MutableStateFlow(TransferForm())
    val form: StateFlow<TransferForm> = _form.asStateFlow()
    private val _available = MutableStateFlow<Double?>(null)
    /** উৎস গুদামে (ও নির্বাচিত লটে) বর্তমান স্টক। */
    val availableGrams: StateFlow<Double?> = _available.asStateFlow()

    /** উৎস গুদামে যেসব লটে স্টক আছে। */
    val lotChoices: StateFlow<List<LotChoice>> = combine(
        stock.observeLots(), stock.observeLotWarehouseBalances(), _form,
    ) { lots, balances, f ->
        val now = System.currentTimeMillis()
        val lotById = lots.associateBy { it.id }
        balances.filter { it.itemId == f.itemId && it.warehouseId == f.fromId && it.qtyGrams > StockCalc.EPS }
            .mapNotNull { b ->
                val lot = lotById[b.lotId] ?: return@mapNotNull null
                LotChoice(
                    b.lotId,
                    "${lot.batchNo} · ${InventoryLabels.expiryText(lot.expiryAt, now)} · ${Weight.formatKg(b.qtyGrams)}",
                    b.qtyGrams,
                )
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun update(f: (TransferForm) -> TransferForm) {
        _form.update(f)
        refreshAvailable()
    }

    private fun refreshAvailable() {
        val f = _form.value
        val item = f.itemId
        val from = f.fromId
        if (item == null || from == null) { _available.value = null; return }
        viewModelScope.launch {
            try { _available.value = stock.stockGrams(item, from, f.lotId) } catch (e: Exception) { _available.value = null }
        }
    }

    fun save() {
        val f = _form.value
        launchSafe(
            onDone = {
                _form.update { it.copy(qtyKg = "", note = "", lotId = null) }
                refreshAvailable()
                showMessage("ট্রান্সফার সম্পন্ন হয়েছে")
            },
        ) {
            val itemId = f.itemId ?: throw IllegalArgumentException("আইটেম বাছাই করুন")
            val from = f.fromId ?: throw IllegalArgumentException("উৎস গুদাম বাছাই করুন")
            val to = f.toId ?: throw IllegalArgumentException("গন্তব্য গুদাম বাছাই করুন")
            val grams = parseKgToGrams(f.qtyKg) ?: throw IllegalArgumentException("পরিমাণ সঠিক সংখ্যা নয়")
            require(grams > StockCalc.EPS) { "পরিমাণ শূন্যের বেশি হতে হবে" }
            if (lotChoices.value.isNotEmpty() && f.lotId == null) throw IllegalArgumentException("লট বাছাই করুন")
            stock.transfer(itemId, f.lotId, from, to, grams, f.note.ifBlank { null })
        }
    }
}
