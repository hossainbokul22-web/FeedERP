package com.feederp.ui.purchase

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.data.ItemEntity
import com.feederp.data.ItemType
import com.feederp.data.PartyEntity
import com.feederp.data.PartyType
import com.feederp.data.purchase.GoodsReceiptEntity
import com.feederp.data.purchase.PurchaseOrderEntity
import com.feederp.master.MasterRepository
import com.feederp.purchase.PoLineInput
import com.feederp.purchase.PurchaseCalc
import com.feederp.purchase.PurchaseRepository
import com.feederp.ui.master.BaseViewModel
import com.feederp.ui.master.gramsToKgText
import com.feederp.ui.master.parseKgToGrams
import com.feederp.ui.master.parseTakaToPaisa
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

// ---------------- PO তালিকা ----------------

data class PoRow(val po: PurchaseOrderEntity, val supplierName: String)

@HiltViewModel
class PoListViewModel @Inject constructor(
    master: MasterRepository,
    purchase: PurchaseRepository,
    session: SessionManager,
) : BaseViewModel() {
    val canCreate: Boolean = session.can(Permission.PURCHASE)
    val rows: StateFlow<List<PoRow>> = combine(purchase.observePos(), master.observeParties()) { pos, parties ->
        val names = parties.associate { it.id to it.name }
        pos.map { PoRow(it, names[it.supplierId] ?: "?") }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

// ---------------- PO ফর্ম ----------------

data class PoLineForm(val itemId: String? = null, val qtyKg: String = "", val rateTaka: String = "")

data class PoForm(
    val supplierId: String? = null,
    val orderDate: Long = System.currentTimeMillis(),
    val expectedDate: Long? = null,
    val note: String = "",
    val lines: List<PoLineForm> = listOf(PoLineForm()),
) {
    /** ফর্মে যা লেখা আছে তার আনুমানিক মোট (ভুল লাইন বাদ দিয়ে), পয়সায়। */
    fun totalPaisa(): Long = lines.sumOf { l ->
        val q = parseKgToGrams(l.qtyKg) ?: return@sumOf 0L
        val r = parseTakaToPaisa(l.rateTaka) ?: return@sumOf 0L
        PurchaseCalc.lineTotalPaisa(q, r)
    }
}

private fun PoForm.toInputs(): List<PoLineInput> = lines.map { l ->
    val itemId = l.itemId ?: throw IllegalArgumentException("প্রতিটি লাইনে আইটেম বাছাই করুন")
    val qty = parseKgToGrams(l.qtyKg) ?: throw IllegalArgumentException("পরিমাণ সঠিক সংখ্যা নয়")
    val rate = parseTakaToPaisa(l.rateTaka) ?: throw IllegalArgumentException("দর (টাকা/কেজি) সঠিক সংখ্যা নয়")
    PoLineInput(itemId, qty, rate)
}

@HiltViewModel
class PoFormViewModel @Inject constructor(
    master: MasterRepository,
    private val purchase: PurchaseRepository,
    savedStateHandle: SavedStateHandle,
) : BaseViewModel() {
    private val editId: String? = savedStateHandle.get<String>("id")
    val isEdit: Boolean = editId != null

    val suppliers: StateFlow<List<PartyEntity>> = master.observeParties()
        .map { list -> list.filter { it.type == PartyType.SUPPLIER } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val items: StateFlow<List<ItemEntity>> = master.observeItems()
        .map { list -> list.filter { it.type != ItemType.FINISHED_GOOD } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _form = MutableStateFlow(PoForm())
    val form: StateFlow<PoForm> = _form.asStateFlow()

    init {
        if (editId != null) launchSafe {
            val po = purchase.observePo(editId).first() ?: throw IllegalStateException("PO পাওয়া যায়নি")
            val lines = purchase.observePoLines(editId).first()
            _form.value = PoForm(
                supplierId = po.supplierId, orderDate = po.orderDate, expectedDate = po.expectedDate, note = po.note ?: "",
                lines = lines.map {
                    PoLineForm(
                        it.itemId, gramsToKgText(it.qtyGrams),
                        (it.ratePaisaPerKg / 100.0).toString().removeSuffix(".0"),
                    )
                }.ifEmpty { listOf(PoLineForm()) },
            )
        }
    }

    fun update(f: (PoForm) -> PoForm) { _form.update(f) }
    fun addLine() = update { it.copy(lines = it.lines + PoLineForm()) }
    fun removeLine(index: Int) = update {
        if (it.lines.size <= 1) it else it.copy(lines = it.lines.filterIndexed { i, _ -> i != index })
    }
    fun updateLine(index: Int, f: (PoLineForm) -> PoLineForm) = update {
        it.copy(lines = it.lines.mapIndexed { i, l -> if (i == index) f(l) else l })
    }

    /** খসড়া সংরক্ষণ; সফল হলে PO এর id দিয়ে [onSaved] ডাকে। */
    fun save(onSaved: (String) -> Unit) {
        val f = _form.value
        var savedId: String? = null
        launchSafe(onDone = { savedId?.let(onSaved) }) {
            val supplier = f.supplierId ?: throw IllegalArgumentException("সাপ্লায়ার বাছাই করুন")
            savedId = purchase.savePo(editId, supplier, f.orderDate, f.expectedDate, f.note, f.toInputs())
        }
    }
}

// ---------------- PO বিস্তারিত ----------------

data class PoLineView(
    val itemName: String,
    val orderedGrams: Double,
    val receivedGrams: Double,
    val ratePaisaPerKg: Long,
) {
    val remainingGrams: Double get() = PurchaseCalc.remainingGrams(orderedGrams, receivedGrams)
}

data class PoDetailUi(
    val po: PurchaseOrderEntity? = null,
    val supplierName: String = "",
    val lines: List<PoLineView> = emptyList(),
    val grns: List<GoodsReceiptEntity> = emptyList(),
)

@HiltViewModel
class PoDetailViewModel @Inject constructor(
    master: MasterRepository,
    private val purchase: PurchaseRepository,
    session: SessionManager,
    savedStateHandle: SavedStateHandle,
) : BaseViewModel() {
    private val poId: String = savedStateHandle.get<String>("id") ?: ""
    val canApprove: Boolean = session.can(Permission.APPROVE)
    val canPurchase: Boolean = session.can(Permission.PURCHASE)

    private val core = combine(
        purchase.observePo(poId), purchase.observePoLines(poId), purchase.observeReceivedLinesForPo(poId),
    ) { po, lines, received -> Triple(po, lines, received) }
    private val aux = combine(
        purchase.observeGrnsForPo(poId), master.observeItems(), master.observeParties(),
    ) { grns, items, parties -> Triple(grns, items, parties) }

    val ui: StateFlow<PoDetailUi> = combine(core, aux) { c, a ->
        val (po, lines, received) = c
        val (grns, items, parties) = a
        val itemNames = items.associate { it.id to it.name }
        val receivedByItem = received.groupBy { it.itemId }.mapValues { e -> e.value.sumOf { it.qtyGrams } }
        val views = lines.groupBy { it.itemId }.map { (itemId, ls) ->
            PoLineView(itemNames[itemId] ?: "?", ls.sumOf { it.qtyGrams }, receivedByItem[itemId] ?: 0.0, ls.first().ratePaisaPerKg)
        }
        PoDetailUi(po, parties.firstOrNull { it.id == po?.supplierId }?.name ?: "?", views, grns)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PoDetailUi())

    fun approve() = launchSafe { purchase.approvePo(poId) }
    fun cancel(reason: String) = launchSafe { purchase.cancelPo(poId, reason) }
}
