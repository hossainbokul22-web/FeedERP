package com.feederp.ui.delivery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.data.ItemDao
import com.feederp.data.PartyDao
import com.feederp.data.distribution.DeliveryLineEntity
import com.feederp.data.distribution.DeliveryNoteEntity
import com.feederp.data.sales.SalesInvoiceEntity
import com.feederp.data.sales.SalesInvoiceLineEntity
import com.feederp.distribution.DeliveryRepository
import com.feederp.distribution.PlanLineInput
import com.feederp.ui.master.BaseViewModel
import com.feederp.ui.master.parseNum
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

fun kgText(grams: Double): String = String.format(java.util.Locale.US, "%.3f", grams / 1000.0).trimEnd('0').trimEnd('.')

data class NoteRow(val note: DeliveryNoteEntity, val partyName: String)

@HiltViewModel
class DeliveryListViewModel @Inject constructor(repo: DeliveryRepository, partyDao: PartyDao) : BaseViewModel() {
    val rows: StateFlow<List<NoteRow>> = combine(repo.observeNotes(), partyDao.observeAll()) { notes, parties ->
        val names = parties.associate { it.id to it.name }
        notes.map { NoteRow(it, names[it.partyId] ?: "অজানা ক্রেতা") }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

data class PlanRow(val line: SalesInvoiceLineEntity, val itemName: String, val remainingGrams: Double, val qtyKg: String)

@HiltViewModel
class NewDeliveryViewModel @Inject constructor(
    private val repo: DeliveryRepository,
    partyDao: PartyDao,
    private val itemDao: ItemDao,
) : BaseViewModel() {
    data class InvoiceOption(val invoice: SalesInvoiceEntity, val partyName: String)

    val invoices: StateFlow<List<InvoiceOption>> = combine(repo.observePendingInvoices(), partyDao.observeAll()) { invs, parties ->
        val names = parties.associate { it.id to it.name }
        invs.map { InvoiceOption(it, names[it.partyId] ?: "?") }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()
    private val _rows = MutableStateFlow<List<PlanRow>>(emptyList())
    val rows: StateFlow<List<PlanRow>> = _rows.asStateFlow()
    private val _note = MutableStateFlow("")
    val note: StateFlow<String> = _note.asStateFlow()

    fun setNote(v: String) { _note.value = v }

    fun select(invoiceId: String) {
        _selected.value = invoiceId
        launchSafe {
            val names = itemDao.all().associate { it.id to it.name }
            _rows.value = repo.remainingByLine(invoiceId).map { (l, rem) ->
                PlanRow(l, names[l.itemId] ?: "?", rem, if (rem > 0) kgText(rem) else "0")
            }
        }
    }

    fun setQty(index: Int, v: String) = _rows.update { r -> r.mapIndexed { i, x -> if (i == index) x.copy(qtyKg = v) else x } }

    fun save(onSaved: (String) -> Unit) = launchSafe {
        val invoiceId = _selected.value ?: throw IllegalArgumentException("ইনভয়েস বাছাই করুন")
        val plan = _rows.value.mapNotNull { r ->
            val kg = parseNum(r.qtyKg) ?: throw IllegalArgumentException("${r.itemName}: পরিমাণ সংখ্যায় দিন")
            if (kg <= 0) null else PlanLineInput(r.line.id, kg * 1000.0)
        }
        onSaved(repo.planNote(invoiceId, plan, null, _note.value))
    }
}

data class DeliveryDetailState(
    val note: DeliveryNoteEntity? = null,
    val partyName: String = "",
    val lines: List<DeliveryLineEntity> = emptyList(),
    val itemNames: Map<String, String> = emptyMap(),
)

@HiltViewModel
class DeliveryDetailViewModel @Inject constructor(
    private val repo: DeliveryRepository,
    partyDao: PartyDao,
    itemDao: ItemDao,
    private val session: SessionManager,
    savedState: SavedStateHandle,
) : BaseViewModel() {
    private val noteId: String = requireNotNull(savedState.get<String>("id")) { "নোট id নেই" }
    private val refresh = MutableStateFlow(0)
    val canDispatch: Boolean get() = session.can(Permission.DELIVERY_DISPATCH)

    val state: StateFlow<DeliveryDetailState> = combine(
        repo.observeNotes().map { list -> list.firstOrNull { it.id == noteId } },
        repo.observeLines(noteId), partyDao.observeAll(), itemDao.observeAll(), refresh,
    ) { note, lines, parties, items, _ ->
        DeliveryDetailState(
            note = note, partyName = parties.firstOrNull { it.id == note?.partyId }?.name.orEmpty(),
            lines = lines, itemNames = items.associate { it.id to it.name },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeliveryDetailState())

    fun load() = launchSafe { repo.loadNote(noteId); refresh.value += 1 }

    /** [deliveredKg]: লাইন আইডি → পৌঁছানো কেজি (ফাঁকা = লোড করা পুরোটাই)। */
    fun confirm(receivedBy: String, deliveredKg: Map<String, String>, onDone: () -> Unit) = launchSafe(onDone = onDone) {
        val grams = HashMap<String, Double>()
        for ((id, text) in deliveredKg) {
            if (text.isBlank()) continue
            val kg = parseNum(text) ?: throw IllegalArgumentException("ডেলিভারি পরিমাণ সংখ্যায় দিন")
            grams[id] = kg * 1000.0
        }
        repo.confirmDelivery(noteId, receivedBy, grams)
        refresh.value += 1
    }
}
