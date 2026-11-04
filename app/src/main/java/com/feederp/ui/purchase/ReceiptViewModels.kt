package com.feederp.ui.purchase

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.data.ItemEntity
import com.feederp.data.ItemType
import com.feederp.data.PartyEntity
import com.feederp.data.PartyType
import com.feederp.data.WarehouseEntity
import com.feederp.data.purchase.GoodsReceiptEntity
import com.feederp.data.purchase.GoodsReceiptLineEntity
import com.feederp.data.purchase.SupplierPaymentEntity
import com.feederp.inventory.StockCalc
import com.feederp.master.MasterRepository
import com.feederp.purchase.GrnLineInput
import com.feederp.purchase.PurchaseRepository
import com.feederp.ui.master.BaseViewModel
import com.feederp.ui.master.gramsToKgText
import com.feederp.ui.master.parseKgToGrams
import com.feederp.ui.master.parseNum
import com.feederp.ui.master.parseTakaToPaisa
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

// ---------------- গুডস রিসিভ ফর্ম ----------------

data class GrnLineForm(
    val itemId: String? = null,
    val batchNo: String = "",
    val qtyKg: String = "",
    val rateTaka: String = "",
    val mfgAt: Long? = null,
    val expiryAt: Long? = null,
    val moisture: String = "",
)

data class GrnForm(
    val supplierId: String? = null,
    val warehouseId: String? = null,
    val receivedAt: Long = System.currentTimeMillis(),
    val note: String = "",
    val lines: List<GrnLineForm> = listOf(GrnLineForm()),
)

private fun GrnForm.toInputs(): List<GrnLineInput> = lines.map { l ->
    val itemId = l.itemId ?: throw IllegalArgumentException("প্রতিটি লাইনে আইটেম বাছাই করুন")
    val qty = parseKgToGrams(l.qtyKg) ?: throw IllegalArgumentException("পরিমাণ সঠিক সংখ্যা নয়")
    val rate = parseTakaToPaisa(l.rateTaka) ?: throw IllegalArgumentException("দর (টাকা/কেজি) সঠিক সংখ্যা নয়")
    val moisture = if (l.moisture.isBlank()) null
    else parseNum(l.moisture) ?: throw IllegalArgumentException("আর্দ্রতা সঠিক সংখ্যা নয়")
    GrnLineInput(itemId, l.batchNo, qty, rate, l.mfgAt, l.expiryAt, moisture)
}

@HiltViewModel
class GoodsReceiptViewModel @Inject constructor(
    master: MasterRepository,
    private val purchase: PurchaseRepository,
    savedStateHandle: SavedStateHandle,
) : BaseViewModel() {
    private val poId: String? = savedStateHandle.get<String>("poId")
    val isPoReceipt: Boolean = poId != null

    private val _poNo = MutableStateFlow<String?>(null)
    val poNo: StateFlow<String?> = _poNo.asStateFlow()
    private val _poItemIds = MutableStateFlow<Set<String>>(emptySet())
    private val _remaining = MutableStateFlow<Map<String, Double>>(emptyMap())
    /** PO এর বিপরীতে রিসিভে আইটেমভিত্তিক বাকি পরিমাণ (গ্রাম)। */
    val remaining: StateFlow<Map<String, Double>> = _remaining.asStateFlow()

    val suppliers: StateFlow<List<PartyEntity>> = master.observeParties()
        .map { list -> list.filter { it.type == PartyType.SUPPLIER } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val warehouses: StateFlow<List<WarehouseEntity>> =
        master.observeWarehouses().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val items: StateFlow<List<ItemEntity>> = combine(master.observeItems(), _poItemIds) { all, poItems ->
        if (poId != null) all.filter { it.id in poItems } else all.filter { it.type != ItemType.FINISHED_GOOD }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _form = MutableStateFlow(GrnForm())
    val form: StateFlow<GrnForm> = _form.asStateFlow()

    init {
        if (poId != null) launchSafe {
            val po = purchase.observePo(poId).first() ?: throw IllegalStateException("PO পাওয়া যায়নি")
            val poLines = purchase.observePoLines(poId).first()
            val rates = poLines.associate { it.itemId to it.ratePaisaPerKg }
            val progress = purchase.poProgress(poId)
            _poNo.value = po.poNo
            _poItemIds.value = progress.map { it.itemId }.toSet()
            _remaining.value = progress.associate { it.itemId to it.remainingGrams }
            _form.value = GrnForm(
                supplierId = po.supplierId,
                lines = progress.filter { it.remainingGrams > StockCalc.EPS }.map {
                    GrnLineForm(
                        itemId = it.itemId, qtyKg = gramsToKgText(it.remainingGrams),
                        rateTaka = ((rates[it.itemId] ?: 0L) / 100.0).toString().removeSuffix(".0"),
                    )
                }.ifEmpty { listOf(GrnLineForm()) },
            )
        }
    }

    fun update(f: (GrnForm) -> GrnForm) { _form.update(f) }
    fun addLine() = update { it.copy(lines = it.lines + GrnLineForm()) }
    fun removeLine(index: Int) = update {
        if (it.lines.size <= 1) it else it.copy(lines = it.lines.filterIndexed { i, _ -> i != index })
    }
    fun updateLine(index: Int, f: (GrnLineForm) -> GrnLineForm) = update {
        it.copy(lines = it.lines.mapIndexed { i, l -> if (i == index) f(l) else l })
    }

    fun save(onSaved: () -> Unit) {
        val f = _form.value
        launchSafe(onDone = onSaved) {
            val supplier = f.supplierId ?: throw IllegalArgumentException("সাপ্লায়ার বাছাই করুন")
            val wh = f.warehouseId ?: throw IllegalArgumentException("গুদাম বাছাই করুন")
            purchase.receiveGoods(poId, supplier, wh, f.receivedAt, f.note, f.toInputs())
        }
    }
}

// ---------------- রিসিভ তালিকা ----------------

data class GrnRow(val grn: GoodsReceiptEntity, val supplierName: String, val warehouseName: String)
data class GrnLineView(val line: GoodsReceiptLineEntity, val itemName: String)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GrnListViewModel @Inject constructor(
    master: MasterRepository,
    purchase: PurchaseRepository,
) : BaseViewModel() {
    val rows: StateFlow<List<GrnRow>> = combine(
        purchase.observeGrns(), master.observeParties(), master.observeWarehouses(),
    ) { grns, parties, whs ->
        val pn = parties.associate { it.id to it.name }
        val wn = whs.associate { it.id to it.name }
        grns.map { GrnRow(it, pn[it.supplierId] ?: "?", wn[it.warehouseId] ?: "?") }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val selected = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = selected.asStateFlow()
    fun select(id: String?) { selected.value = id }

    val detail: StateFlow<List<GrnLineView>> = combine(
        selected.flatMapLatest { id ->
            if (id == null) flowOf(emptyList<GoodsReceiptLineEntity>()) else purchase.observeGrnLines(id)
        },
        master.observeItems(),
    ) { lines, items ->
        val names = items.associate { it.id to it.name }
        lines.map { GrnLineView(it, names[it.itemId] ?: "?") }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

// ---------------- সাপ্লায়ার পেমেন্ট ----------------

data class PaymentForm(
    val supplierId: String? = null,
    val amountTaka: String = "",
    val method: String = "CASH",
    val paidAt: Long = System.currentTimeMillis(),
    val note: String = "",
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SupplierPaymentViewModel @Inject constructor(
    master: MasterRepository,
    private val purchase: PurchaseRepository,
    session: SessionManager,
) : BaseViewModel() {
    val canPay: Boolean = session.can(Permission.ACCOUNTS)

    val suppliers: StateFlow<List<PartyEntity>> = master.observeParties()
        .map { list -> list.filter { it.type == PartyType.SUPPLIER } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _form = MutableStateFlow(PaymentForm())
    val form: StateFlow<PaymentForm> = _form.asStateFlow()

    private val supplierId = _form.map { it.supplierId }.distinctUntilChanged()

    /** লেজার ব্যালেন্স: ঋণাত্মক = আমরা সাপ্লায়ারকে দেব। */
    val balancePaisa: StateFlow<Long?> = supplierId.flatMapLatest { id ->
        if (id == null) flowOf<Long?>(null) else purchase.observeSupplierBalancePaisa(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val payments: StateFlow<List<SupplierPaymentEntity>> = supplierId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList<SupplierPaymentEntity>()) else purchase.observeSupplierPayments(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun update(f: (PaymentForm) -> PaymentForm) { _form.update(f) }

    fun save() {
        val f = _form.value
        launchSafe(
            onDone = {
                _form.update { it.copy(amountTaka = "", note = "") }
                showMessage("পেমেন্ট সংরক্ষিত হয়েছে")
            },
        ) {
            val supplier = f.supplierId ?: throw IllegalArgumentException("সাপ্লায়ার বাছাই করুন")
            val paisa = parseTakaToPaisa(f.amountTaka) ?: throw IllegalArgumentException("অঙ্ক সঠিক সংখ্যা নয়")
            purchase.paySupplier(supplier, paisa, f.method, null, f.note, f.paidAt)
        }
    }
}
