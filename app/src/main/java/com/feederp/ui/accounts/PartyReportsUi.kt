package com.feederp.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import com.feederp.ui.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.feederp.accounts.AccountingCalc
import com.feederp.accounts.AgeInput
import com.feederp.accounts.AgeingCalc
import com.feederp.accounts.AgeingRow
import com.feederp.accounts.CashBookRow
import com.feederp.accounts.CashEntry
import com.feederp.core.AccountCodes
import com.feederp.data.PartyDao
import com.feederp.data.PartyEntity
import com.feederp.data.accounts.LedgerDao
import com.feederp.data.accounts.PartyEntryRow
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.formatDate
import com.feederp.ui.master.BaseViewModel
import com.feederp.ui.master.DropdownField
import com.feederp.ui.master.SectionTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

// ---------------- পার্টি লেজার ----------------

data class LedgerRow(val entry: PartyEntryRow, val balancePaisa: Long)

@HiltViewModel
class PartyLedgerViewModel @Inject constructor(partyDao: PartyDao, private val ledgerDao: LedgerDao) : BaseViewModel() {
    val parties: StateFlow<List<PartyEntity>> = partyDao.observeAll().map { l -> l.filter { it.deletedAt == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _party = MutableStateFlow<String?>(null)
    val party: StateFlow<String?> = _party.asStateFlow()
    fun select(id: String) { _party.value = id }

    /** + = আমরা পাব (ক্রেতার বাকি), − = আমরা দেব (সাপ্লায়ার/অগ্রিম)। */
    val rows: StateFlow<List<LedgerRow>> = _party.flatMapLatest { id ->
        if (id == null) flowOf(emptyList())
        else ledgerDao.observePartyEntries(id).map { list ->
            val bal = AccountingCalc.runningBalance(list.map { it.debitPaisa - it.creditPaisa })
            list.mapIndexed { i, e -> LedgerRow(e, bal[i]) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun PartyLedgerScreen(onBack: () -> Unit, vm: PartyLedgerViewModel = hiltViewModel()) {
    val parties by vm.parties.collectAsStateWithLifecycle()
    val sel by vm.party.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    ScreenScaffold(title = "পার্টি লেজার", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(12.dp)) {
            DropdownField("পার্টি", parties.map { it.id }, sel, { id -> parties.firstOrNull { it.id == id }?.name ?: id }, { vm.select(it) })
            Spacer(Modifier.height(4.dp))
            Text("ব্যালেন্স: + = আমরা পাব, − = আমরা দেব", style = MaterialTheme.typography.bodySmall)
            if (sel != null && rows.isEmpty()) Text("কোনো লেনদেন নেই", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                items(rows) { r ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${formatDate(r.entry.entryDate)} · ${r.entry.voucherType}", style = MaterialTheme.typography.bodySmall)
                                Text("৳ ${taka(r.balancePaisa)}", style = MaterialTheme.typography.titleSmall)
                            }
                            r.entry.memo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text(
                                if (r.entry.debitPaisa > 0) "ডেবিট ${taka(r.entry.debitPaisa)}" else "ক্রেডিট ${taka(r.entry.creditPaisa)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------- বাকির বয়স (শুধু ক্রেতার বাকি) ----------------

data class AgingLine(val row: AgeingRow, val name: String)

@HiltViewModel
class AgingViewModel @Inject constructor(private val ledgerDao: LedgerDao, private val partyDao: PartyDao) : BaseViewModel() {
    private val _lines = MutableStateFlow<List<AgingLine>>(emptyList())
    val lines: StateFlow<List<AgingLine>> = _lines.asStateFlow()

    init { load() }

    /** আদায়/ফেরত সবচেয়ে পুরোনো বিল থেকে বাদ দিয়ে (FIFO) বয়স। অগ্রিম/ক্রেডিট আলাদা কলামে। */
    fun load() = launchSafe {
        val names = partyDao.all().associate { it.id to it.name }
        val inputs = ledgerDao.allReceivableEntries().map { AgeInput(it.partyId, it.entryDate, it.debitPaisa - it.creditPaisa) }
        _lines.value = AgeingCalc.age(inputs, System.currentTimeMillis()).map { AgingLine(it, names[it.partyId] ?: "?") }
    }
}

@Composable
fun AgingScreen(onBack: () -> Unit, vm: AgingViewModel = hiltViewModel()) {
    val lines by vm.lines.collectAsStateWithLifecycle()
    ScreenScaffold(title = "বাকির বয়স (ক্রেতা)", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(12.dp)) {
            if (lines.isEmpty()) Text("কোনো বকেয়া নেই", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(lines) { l ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(l.name, style = MaterialTheme.typography.titleSmall)
                                Text("৳ ${taka(l.row.totalDuePaisa)}", style = MaterialTheme.typography.titleSmall)
                            }
                            Text("০–৩০ দিন ${taka(l.row.currentPaisa)} · ৩১–৬০ ${taka(l.row.d31to60Paisa)} · ৬১–৯০ ${taka(l.row.d61to90Paisa)} · ৯০+ ${taka(l.row.over90Paisa)}", style = MaterialTheme.typography.bodySmall)
                            if (l.row.advancePaisa > 0) Text("অগ্রিম/ক্রেডিট ${taka(l.row.advancePaisa)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

// ---------------- ক্যাশবুক ----------------

private val CASH_ACCOUNTS = listOf(AccountCodes.CASH to "নগদ", AccountCodes.BANK to "ব্যাংক", AccountCodes.MOBILE_BANKING to "মোবাইল ব্যাংকিং")

@HiltViewModel
class CashBookViewModel @Inject constructor(private val ledgerDao: LedgerDao) : BaseViewModel() {
    private val _account = MutableStateFlow(AccountCodes.CASH)
    val account: StateFlow<String> = _account.asStateFlow()
    private val _period = MutableStateFlow(Period.THIS_MONTH)
    val period: StateFlow<Period> = _period.asStateFlow()
    private val _rows = MutableStateFlow<List<CashBookRow>>(emptyList())
    val rows: StateFlow<List<CashBookRow>> = _rows.asStateFlow()
    private val _opening = MutableStateFlow(0L)
    val opening: StateFlow<Long> = _opening.asStateFlow()

    init { reload() }

    fun setAccount(code: String) { _account.value = code; reload() }
    fun setPeriod(p: Period) { _period.value = p; reload() }

    private fun reload() = launchSafe {
        val (from, to) = periodRange(_period.value)
        val codes = listOf(_account.value)
        val opening = if (from > 0) ledgerDao.netBefore(codes, from) else 0L
        val entries = ledgerDao.entriesForAccounts(codes, from, to).map { CashEntry(it.id, it.entryDate, it.accountCode, it.memo, it.debitPaisa, it.creditPaisa) }
        _opening.value = opening
        _rows.value = AccountingCalc.cashBook(entries, opening)
    }
}

@Composable
fun CashBookScreen(onBack: () -> Unit, vm: CashBookViewModel = hiltViewModel()) {
    val account by vm.account.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val opening by vm.opening.collectAsStateWithLifecycle()
    ScreenScaffold(title = "ক্যাশবুক", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(12.dp)) {
            DropdownField("খাত", CASH_ACCOUNTS.map { it.first }, account, { c -> CASH_ACCOUNTS.first { it.first == c }.second }, { vm.setAccount(it) })
            Spacer(Modifier.height(6.dp))
            DropdownField("সময়", Period.values().toList(), period, { it.labelBn }, { vm.setPeriod(it) })
            SectionTitle("শুরুর জের ৳ ${taka(opening)}")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(rows) { r ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("${formatDate(r.date)} ${r.memo.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                            Text(if (r.inPaisa > 0) "জমা ${taka(r.inPaisa)}" else "খরচ ${taka(r.outPaisa)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("৳ ${taka(r.balancePaisa)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
