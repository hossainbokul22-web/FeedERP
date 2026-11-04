package com.feederp.ui.sales

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.feederp.core.Money
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.data.ItemDao
import com.feederp.data.ItemEntity
import com.feederp.data.ItemType
import com.feederp.data.PartyDao
import com.feederp.data.PartyEntity
import com.feederp.data.PartyType
import com.feederp.data.WarehouseDao
import com.feederp.data.WarehouseEntity
import com.feederp.data.sales.CollectionEntity
import com.feederp.data.sales.ItemPriceEntity
import com.feederp.data.sales.PayMethods
import com.feederp.data.sales.SalesInvoiceEntity
import com.feederp.data.sales.SalesInvoiceLineEntity
import com.feederp.sales.SaleLineInput
import com.feederp.sales.SalesRepository
import com.feederp.ui.master.BaseViewModel
import com.feederp.ui.master.parseNum
import com.feederp.ui.master.parseTakaToPaisa
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

fun taka(paisa: Long): String = String.format(java.util.Locale.US, "%.2f", paisa / 100.0)
fun kg(grams: Double): String = String.format(java.util.Locale.US, "%.3f", grams / 1000.0).trimEnd('0').trimEnd('.')

data class InvoiceRow(val invoice: SalesInvoiceEntity, val partyName: String)

@HiltViewModel
class InvoiceListViewModel @Inject constructor(
    repo: SalesRepository,
    partyDao: PartyDao,
) : BaseViewModel() {
    val rows: StateFlow<List<InvoiceRow>> = combine(repo.observeInvoices(), partyDao.observeAll()) { invs, parties ->
        val names = parties.associate { it.id to it.name }
        invs.map { InvoiceRow(it, names[it.partyId] ?: "অজানা ক্রেতা") }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

data class LineForm(val itemId: String? = null, val qtyKg: String = "", val priceTaka: String = "")

data class NewInvoiceState(
    val partyId: String? = null,
    val warehouseId: String? = null,
    val lines: List<LineForm> = listOf(LineForm()),
    val discountTaka: String = "",
    val paidTaka: String = "",
    val method: String = "CASH",
    val note: String = "",
    val overrideReason: String = "",
)

@HiltViewModel
class NewInvoiceViewModel @Inject constructor(
    private val repo: SalesRepository,
    partyDao: PartyDao,
    itemDao: ItemDao,
    warehouseDao: WarehouseDao,
    private val session: SessionManager,
) : BaseViewModel() {
    val parties: StateFlow<List<PartyEntity>> = partyDao.observeAll()
        .map { l -> l.filter { it.type != PartyType.SUPPLIER && it.deletedAt == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val items: StateFlow<List<ItemEntity>> = itemDao.observeAll()
        .map { l -> l.filter { it.type == ItemType.FINISHED_GOOD && it.deletedAt == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val warehouses: StateFlow<List<WarehouseEntity>> = warehouseDao.observeAll()
        .map { l -> l.filter { it.deletedAt == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(NewInvoiceState())
    val state: StateFlow<NewInvoiceState> = _state.asStateFlow()

    /** ক্রেতার বর্তমান বাকি (লেজার থেকে), ক্রেতা বাছাইয়ের পর দেখানোর জন্য। */
    private val _balance = MutableStateFlow<Long?>(null)
    val balance: StateFlow<Long?> = _balance.asStateFlow()

    val canEditPrice: Boolean get() = session.can(Permission.PRICE_EDIT)

    fun selectParty(p: PartyEntity) {
        _state.update { it.copy(partyId = p.id) }
        launchSafe { _balance.value = repo.partyBalancePaisa(p.id) }
        // ক্রেতার ধরন বদলালে তালিকার দাম বদলায়, তাই আগে বাছা আইটেমের দাম নতুন করে বসাই
        _state.value.lines.forEachIndexed { i, l -> l.itemId?.let { setItem(i, it) } }
    }

    fun selectWarehouse(id: String) = _state.update { it.copy(warehouseId = id) }

    fun setItem(index: Int, itemId: String) {
        updateLine(index) { it.copy(itemId = itemId) }
        val partyId = _state.value.partyId ?: return
        launchSafe {
            val type = parties.value.firstOrNull { it.id == partyId }?.type ?: return@launchSafe
            repo.listPricePaisaPerKg(itemId, type)?.let { p ->
                updateLine(index) { if (it.itemId == itemId) it.copy(priceTaka = Money.toTaka(p).toString()) else it }
            }
        }
    }

    fun setQty(index: Int, v: String) = updateLine(index) { it.copy(qtyKg = v) }
    fun setPrice(index: Int, v: String) = updateLine(index) { it.copy(priceTaka = v) }
    fun addLine() = _state.update { it.copy(lines = it.lines + LineForm()) }
    fun removeLine(index: Int) = _state.update {
        if (it.lines.size <= 1) it else it.copy(lines = it.lines.filterIndexed { i, _ -> i != index })
    }
    fun setDiscount(v: String) = _state.update { it.copy(discountTaka = v) }
    fun setPaid(v: String) = _state.update { it.copy(paidTaka = v) }
    fun setMethod(v: String) = _state.update { it.copy(method = v) }
    fun setNote(v: String) = _state.update { it.copy(note = v) }
    fun setOverride(v: String) = _state.update { it.copy(overrideReason = v) }

    private fun updateLine(index: Int, f: (LineForm) -> LineForm) = _state.update {
        it.copy(lines = it.lines.mapIndexed { i, l -> if (i == index) f(l) else l })
    }

    /** মোট (পয়সা) — স্ক্রিনে সারাংশ দেখানোর জন্য; ভুল ইনপুটে null-সমতুল্য ০। */
    fun subtotalPaisa(): Long = _state.value.lines.sumOf { l ->
        val q = parseNum(l.qtyKg) ?: return@sumOf 0L
        val p = parseTakaToPaisa(l.priceTaka) ?: return@sumOf 0L
        if (q > 0 && p > 0) com.feederp.sales.SalesCalc.lineTotalPaisa(q * 1000.0, p) else 0L
    }

    fun save(onSaved: (String) -> Unit) {
        val s = _state.value
        launchSafe {
            val partyId = s.partyId ?: throw IllegalArgumentException("ক্রেতা বাছাই করুন")
            val warehouseId = s.warehouseId ?: throw IllegalArgumentException("গুদাম বাছাই করুন")
            val lines = s.lines.mapIndexed { i, l ->
                val n = i + 1
                val itemId = l.itemId ?: throw IllegalArgumentException("লাইন $n: আইটেম বাছাই করুন")
                val qty = parseNum(l.qtyKg)?.takeIf { it > 0 } ?: throw IllegalArgumentException("লাইন $n: পরিমাণ (কেজি) সংখ্যায় দিন")
                val price = parseTakaToPaisa(l.priceTaka)?.takeIf { it > 0 } ?: throw IllegalArgumentException("লাইন $n: দাম (টাকা/কেজি) দিন")
                SaleLineInput(itemId, qty * 1000.0, price)
            }
            fun money(label: String, text: String): Long =
                if (text.isBlank()) 0L else parseTakaToPaisa(text) ?: throw IllegalArgumentException("$label সংখ্যায় দিন")
            val id = repo.createInvoice(
                partyId = partyId, lines = lines, discountPaisa = money("ডিসকাউন্ট", s.discountTaka),
                paidNowPaisa = money("আদায়", s.paidTaka), method = s.method, invoiceDate = System.currentTimeMillis(),
                warehouseId = warehouseId, note = s.note, overrideReason = s.overrideReason,
            )
            onSaved(id)
        }
    }
}

data class InvoiceDetailState(
    val invoice: SalesInvoiceEntity? = null,
    val partyName: String = "",
    val lines: List<SalesInvoiceLineEntity> = emptyList(),
    val itemNames: Map<String, String> = emptyMap(),
    val collections: List<CollectionEntity> = emptyList(),
    val balancePaisa: Long = 0,
    val returns: List<com.feederp.data.sales.SalesReturnEntity> = emptyList(),
) {
    val returnedPaisa: Long get() = returns.sumOf { it.totalPaisa }
}

@HiltViewModel
class InvoiceDetailViewModel @Inject constructor(
    private val repo: SalesRepository,
    private val returnRepo: com.feederp.sales.SalesReturnRepository,
    partyDao: PartyDao,
    itemDao: ItemDao,
    private val session: SessionManager,
    savedState: SavedStateHandle,
) : BaseViewModel() {
    private val invoiceId: String = requireNotNull(savedState.get<String>("id")) { "ইনভয়েস id নেই" }
    private val refresh = MutableStateFlow(0)

    val canApprove: Boolean get() = session.can(Permission.APPROVE)

    val state: StateFlow<InvoiceDetailState> = combine(
        repo.observeInvoice(invoiceId), partyDao.observeAll(), itemDao.observeAll(), refresh,
    ) { inv, parties, items, _ ->
        if (inv == null) InvoiceDetailState()
        else InvoiceDetailState(
            invoice = inv,
            partyName = parties.firstOrNull { it.id == inv.partyId }?.name.orEmpty(),
            lines = repo.lines(inv.id),
            itemNames = items.associate { it.id to it.name },
            collections = repo.collections(inv.id),
            balancePaisa = repo.partyBalancePaisa(inv.partyId),
            returns = returnRepo.forInvoice(inv.id),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InvoiceDetailState())

    private fun reload() { refresh.value += 1 }

    fun collect(amountTaka: String, method: String, note: String, onDone: () -> Unit) = launchSafe(onDone = onDone) {
        val inv = state.value.invoice ?: throw IllegalStateException("ইনভয়েস পাওয়া যায়নি")
        val amount = parseTakaToPaisa(amountTaka)?.takeIf { it > 0 } ?: throw IllegalArgumentException("আদায়ের অঙ্ক সংখ্যায় দিন")
        repo.collect(inv.partyId, inv.id, amount, method, System.currentTimeMillis(), note)
        reload()
    }

    fun reverseCollection(collectionId: String, reason: String) = launchSafe {
        repo.reverseCollection(collectionId, reason)
        reload()
    }

    /** ইনভয়েস PDF [out]-এ লেখে। কোম্পানির নাম সেটিংসে না থাকায় আপাতত অ্যাপের নাম। */
    fun exportPdf(out: java.io.OutputStream, onDone: () -> Unit) = launchSafe(onDone) {
        val st = state.value
        val inv = st.invoice ?: throw IllegalStateException("ইনভয়েস পাওয়া যায়নি")
        val model = com.feederp.sales.InvoicePrintModel(
            companyName = "ফিড ERP", invoiceNo = inv.invoiceNo, dateMillis = inv.invoiceDate, partyName = st.partyName,
            lines = st.lines.map { l ->
                com.feederp.sales.PrintLine(st.itemNames[l.itemId] ?: "?", kg(l.qtyGrams), taka(l.unitPricePaisaPerKg), taka(l.lineTotalPaisa))
            },
            subtotal = taka(inv.subtotalPaisa), discount = taka(inv.discountPaisa), total = taka(inv.totalPaisa), paid = taka(inv.paidPaisa),
            returned = st.returnedPaisa.takeIf { it > 0 }?.let { taka(it) },
            due = taka((inv.totalPaisa - inv.paidPaisa - st.returnedPaisa).coerceAtLeast(0)), note = inv.note,
        )
        try { com.feederp.sales.InvoicePrinter.write(out, model) } finally { try { out.close() } catch (_: Exception) {} }
    }

    fun cancel(reason: String) = launchSafe {
        repo.cancelInvoice(invoiceId, reason)
        reload()
    }
}

data class PriceRow(val item: ItemEntity, val all: Long?, val dealer: Long?, val customer: Long?)

@HiltViewModel
class PriceListViewModel @Inject constructor(
    private val repo: SalesRepository,
    itemDao: ItemDao,
    private val session: SessionManager,
) : BaseViewModel() {
    val canEdit: Boolean get() = session.can(Permission.PRICE_EDIT)

    val rows: StateFlow<List<PriceRow>> = combine(itemDao.observeAll(), repo.observePrices()) { items, prices ->
        val byId = prices.associateBy { it.id }
        items.filter { it.type == ItemType.FINISHED_GOOD && it.deletedAt == null }.map { it ->
            fun p(t: PartyType?) = byId[ItemPriceEntity.idFor(it.id, t?.name)]?.pricePaisaPerKg
            PriceRow(it, p(null), p(PartyType.DEALER), p(PartyType.CUSTOMER))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(itemId: String, partyType: PartyType?, takaText: String, onDone: () -> Unit) = launchSafe(onDone = onDone) {
        val paisa = parseTakaToPaisa(takaText)?.takeIf { it > 0 } ?: throw IllegalArgumentException("দাম (টাকা/কেজি) সংখ্যায় দিন")
        repo.setPrice(itemId, partyType, paisa)
    }
}

val payMethodOptions: List<String> = PayMethods.all
