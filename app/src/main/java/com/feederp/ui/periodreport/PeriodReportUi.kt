package com.feederp.ui.periodreport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.feederp.data.ItemEntity
import com.feederp.data.WarehouseEntity
import com.feederp.inventory.StockRepository
import com.feederp.master.MasterRepository
import com.feederp.reports.PeriodReport
import com.feederp.reports.PeriodReportRepository
import com.feederp.ui.accounts.taka
import com.feederp.ui.common.LabeledField
import com.feederp.ui.common.ReportActions
import com.feederp.ui.common.ReportRow
import com.feederp.ui.common.ReportSection
import com.feederp.ui.common.ReportTable
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.Text
import com.feederp.ui.master.BaseViewModel
import com.feederp.ui.master.DateField
import com.feederp.ui.master.DropdownField
import com.feederp.ui.master.MessageDialog
import com.feederp.ui.master.SectionTitle
import com.feederp.ui.master.parseKgToGrams
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import javax.inject.Inject

enum class ReportPeriod(val labelBn: String) {
    THIS_MONTH("এই মাস"), LAST_MONTH("গত মাস"), THIS_YEAR("এই বছর"), LAST_YEAR("গত বছর"),
}

/** [from, to) মিলিসেকেন্ড। */
fun reportRange(p: ReportPeriod, now: Long = System.currentTimeMillis()): Pair<Long, Long> {
    fun startOfDay(): Calendar = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val month = startOfDay().apply { set(Calendar.DAY_OF_MONTH, 1) }
    val year = startOfDay().apply { set(Calendar.DAY_OF_YEAR, 1) }
    fun plus(c: Calendar, field: Int, n: Int): Long = (c.clone() as Calendar).apply { add(field, n) }.timeInMillis
    return when (p) {
        ReportPeriod.THIS_MONTH -> month.timeInMillis to plus(month, Calendar.MONTH, 1)
        ReportPeriod.LAST_MONTH -> plus(month, Calendar.MONTH, -1) to month.timeInMillis
        ReportPeriod.THIS_YEAR -> year.timeInMillis to plus(year, Calendar.YEAR, 1)
        ReportPeriod.LAST_YEAR -> plus(year, Calendar.YEAR, -1) to year.timeInMillis
    }
}

private fun kg(grams: Double): String = String.format(java.util.Locale.US, "%,.1f", grams / 1000.0)

/** স্ক্রিন ও PDF — দুটোর জন্য একই টেবিল। */
fun buildReportTable(period: ReportPeriod, r: PeriodReport): ReportTable {
    val stockRows = r.stock.map { l ->
        ReportRow(listOf(l.name, kg(l.opening), kg(l.purchased), kg(l.produced), kg(l.used), kg(l.sold), kg(l.other), kg(l.closing)))
    } + ReportRow(
        listOf(
            "মোট", kg(r.stock.sumOf { it.opening }), kg(r.stock.sumOf { it.purchased }), kg(r.stock.sumOf { it.produced }),
            kg(r.stock.sumOf { it.used }), kg(r.stock.sumOf { it.sold }), kg(r.stock.sumOf { it.other }), kg(r.stock.sumOf { it.closing }),
        ),
        true,
    )
    val b = r.batches
    val batchRows = listOf(
        ReportRow(listOf("মোট ব্যাচ (সম্পন্ন)", "${b.count}")),
        ReportRow(listOf("মোট কাঁচামাল ইনপুট (কেজি)", kg(b.inputGrams))),
        ReportRow(listOf("মোট ভালো উৎপাদন (কেজি)", kg(b.outputGrams))),
        ReportRow(listOf("মোট অপচয় (কেজি)", kg(b.wastageGrams))),
        ReportRow(listOf("মোট উৎপাদন খরচ ৳", taka(b.totalCostPaisa))),
    )
    val productRows = r.products.map { ReportRow(listOf(it.name, "${it.batches}", kg(it.outputGrams))) }
    val batchLineRows = r.batchLines.map { ReportRow(listOf(it.batchNo, it.product, kg(it.inputGrams), kg(it.outputGrams), kg(it.wastageGrams))) }
    val p = r.pnl
    val pnlRows = listOf(
        ReportRow(listOf("মোট আয় (বিক্রি)", taka(p.totalRevenuePaisa))),
        ReportRow(listOf("সরাসরি খরচ (COGS)", taka(p.totalDirectCostPaisa))),
        ReportRow(listOf("মোট লাভ (Gross)", taka(p.grossProfitPaisa)), true),
        ReportRow(listOf("পরিচালন খরচ", taka(p.totalExpensePaisa))),
        ReportRow(listOf("নিট লাভ/(ক্ষতি)", taka(p.netProfitPaisa)), true),
    )
    return ReportTable(
        "মাসিক/বার্ষিক রিপোর্ট", period.labelBn,
        buildList {
            add(ReportSection("স্টক হিসাব (কেজি)", listOf("আইটেম", "শুরু", "কেনা", "উৎপাদন", "ব্যবহার (ব্যাচ/ফর্মুলা)", "বিক্রি", "অন্যান্য", "শেষ"), stockRows, setOf(1, 2, 3, 4, 5, 6, 7)))
            add(ReportSection("উৎপাদন ও ব্যাচ", listOf("বিবরণ", "মান"), batchRows, setOf(1)))
            if (productRows.isNotEmpty()) add(ReportSection("পণ্য অনুযায়ী", listOf("পণ্য", "ব্যাচ", "উৎপাদন (কেজি)"), productRows, setOf(1, 2)))
            if (batchLineRows.isNotEmpty()) add(ReportSection("ব্যাচ অনুযায়ী (কেজি)", listOf("ব্যাচ", "পণ্য", "ইনপুট", "আউটপুট", "অপচয়"), batchLineRows, setOf(2, 3, 4)))
            add(ReportSection("লাভ-ক্ষতি (৳)", listOf("বিবরণ", "৳"), pnlRows, setOf(1)))
        },
    )
}

@HiltViewModel
class PeriodReportViewModel @Inject constructor(private val repo: PeriodReportRepository) : BaseViewModel() {
    private val _period = MutableStateFlow(ReportPeriod.THIS_MONTH)
    val period: StateFlow<ReportPeriod> = _period.asStateFlow()
    private val _report = MutableStateFlow<PeriodReport?>(null)
    val report: StateFlow<PeriodReport?> = _report.asStateFlow()

    init { load(ReportPeriod.THIS_MONTH) }

    fun load(p: ReportPeriod) {
        _period.value = p
        launchSafe {
            val (from, to) = reportRange(p)
            _report.value = repo.build(from, to)
        }
    }
}

@Composable
fun PeriodReportScreen(onBack: () -> Unit, vm: PeriodReportViewModel = hiltViewModel()) {
    val period by vm.period.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val table = remember(period, report) { report?.let { buildReportTable(period, it) } }
    ScreenScaffold(title = "মাসিক/বার্ষিক রিপোর্ট", onBack = onBack, actions = { ReportActions(table) }) { pad ->
        Column(
            Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReportPeriod.values().forEach { p ->
                    FilterChip(selected = p == period, onClick = { vm.load(p) }, label = { Text(p.labelBn) })
                }
            }
            if (table == null) Text("লোড হচ্ছে…") else table.sections.forEach { sec ->
                SectionTitle(sec.heading ?: "")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(sec.headers.joinToString("  |  "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        sec.rows.forEach { row ->
                            Text(
                                row.cells.joinToString("  |  "),
                                style = if (row.bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            Text("PDF/প্রিন্ট/শেয়ার: ওপরের ডানের আইকন থেকে।", style = MaterialTheme.typography.bodySmall)
        }
    }
    MessageDialog(message, onDismiss = vm::clearMessage)
}

// ---------------- ওপেনিং স্টক ----------------

data class OpeningForm(
    val itemId: String? = null,
    val warehouseId: String? = null,
    val qtyKg: String = "",
    val atMillis: Long = System.currentTimeMillis(),
    val expiryAt: Long? = null,
)

@HiltViewModel
class OpeningStockViewModel @Inject constructor(
    master: MasterRepository,
    private val stock: StockRepository,
) : BaseViewModel() {
    val items: StateFlow<List<ItemEntity>> =
        master.observeItems().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val warehouses: StateFlow<List<WarehouseEntity>> =
        master.observeWarehouses().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _form = MutableStateFlow(OpeningForm())
    val form: StateFlow<OpeningForm> = _form.asStateFlow()
    private val _saved = MutableStateFlow(0)
    val savedCount: StateFlow<Int> = _saved.asStateFlow()

    fun update(f: (OpeningForm) -> OpeningForm) { _form.value = f(_form.value) }

    fun save() {
        val f = _form.value
        launchSafe(
            onDone = {
                _saved.value = _saved.value + 1
                _form.value = f.copy(itemId = null, qtyKg = "", expiryAt = null)
                showMessage("ওপেনিং স্টক সংরক্ষিত হয়েছে")
            },
        ) {
            val itemId = f.itemId ?: throw IllegalArgumentException("আইটেম বাছাই করুন")
            val whId = f.warehouseId ?: throw IllegalArgumentException("গুদাম বাছাই করুন")
            val grams = parseKgToGrams(f.qtyKg) ?: throw IllegalArgumentException("পরিমাণ সঠিক সংখ্যা নয়")
            stock.addOpening(itemId, whId, grams, f.atMillis, f.expiryAt)
        }
    }
}

@Composable
fun OpeningStockScreen(onBack: () -> Unit, vm: OpeningStockViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val warehouses by vm.warehouses.collectAsStateWithLifecycle()
    val saved by vm.savedCount.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    ScreenScaffold(title = "ওপেনিং স্টক", onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "হিসাব শুরুর দিনে গুদামে যা আছে তা আইটেম ধরে ধরে তুলুন। দাম লাগবে না। একটা সংরক্ষণ করলে পরের আইটেম দিন।",
                style = MaterialTheme.typography.bodySmall,
            )
            DropdownField(
                label = "আইটেম *", options = items, selected = items.firstOrNull { it.id == form.itemId },
                optionLabel = { "${it.name} (${it.code})" }, onSelect = { i -> vm.update { it.copy(itemId = i.id) } },
            )
            DropdownField(
                label = "গুদাম *", options = warehouses, selected = warehouses.firstOrNull { it.id == form.warehouseId },
                optionLabel = { it.name }, onSelect = { w -> vm.update { it.copy(warehouseId = w.id) } },
            )
            LabeledField("পরিমাণ (কেজি) *", form.qtyKg, { v -> vm.update { it.copy(qtyKg = v) } }, numeric = true)
            DateField("ওপেনিং তারিখ *", form.atMillis, { d -> if (d != null) vm.update { it.copy(atMillis = d) } })
            DateField("মেয়াদ শেষের তারিখ (ঐচ্ছিক)", form.expiryAt, { d -> vm.update { it.copy(expiryAt = d) } }, clearable = true)
            Button(onClick = vm::save, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("সংরক্ষণ করুন") }
            if (saved > 0) Text("এই সেশনে $saved টি ওপেনিং এন্ট্রি হয়েছে", style = MaterialTheme.typography.bodySmall)
        }
    }
    MessageDialog(message, onDismiss = vm::clearMessage)
}
