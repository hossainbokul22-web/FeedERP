package com.feederp.ui.production

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.feederp.ui.common.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feederp.core.Money
import com.feederp.core.Weight
import com.feederp.data.production.BatchStatus
import com.feederp.data.production.ProductionBatchEntity
import com.feederp.inventory.QcStatus
import com.feederp.ui.common.ConfirmDialog
import com.feederp.ui.common.KeyValueRow
import com.feederp.ui.common.LabeledField
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.SearchableList
import com.feederp.ui.common.formatDate
import com.feederp.ui.master.DropdownField
import com.feederp.ui.master.ErrorText
import com.feederp.ui.master.MessageDialog
import com.feederp.ui.master.SectionTitle
import java.util.Locale

private fun statusBn(s: BatchStatus) = when (s) {
    BatchStatus.PLANNED -> "পরিকল্পিত"
    BatchStatus.IN_PROGRESS -> "চলমান"
    BatchStatus.COMPLETED -> "সম্পন্ন"
    BatchStatus.CANCELLED -> "বাতিল"
}

private fun f1(d: Double) = String.format(Locale.US, "%.1f", d)
private fun kg(grams: Double) = Weight.formatKg(grams)

@Composable
fun BatchListScreen(
    onBack: () -> Unit,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    vm: BatchListViewModel = hiltViewModel(),
) {
    val batches by vm.batches.collectAsStateWithLifecycle()
    ScreenScaffold(title = "প্রোডাকশন ব্যাচ", onBack = onBack, onAdd = onNew) { padding ->
        Column(Modifier.padding(padding)) {
            SearchableList(
                items = batches,
                searchText = { it.batchNo },
                emptyText = "কোনো ব্যাচ নেই। + চেপে নতুন ব্যাচ বানান।",
                onClick = { onOpen(it.id) },
            ) { b: ProductionBatchEntity ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(b.batchNo, style = MaterialTheme.typography.titleMedium)
                    Text(statusBn(b.status), style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "পরিকল্পিত ${kg(b.plannedQtyGrams)} · ${formatDate(b.orderedAt)}" +
                        if (b.status == BatchStatus.COMPLETED) " · QC: ${QcStatus.labelBn(b.qcStatus)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (b.isMedicated && b.status == BatchStatus.COMPLETED && !b.cleaningDone) {
                    Text("⚠ লাইন-ক্লিনিং বাকি", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun NewBatchScreen(onBack: () -> Unit, onCreated: (String) -> Unit, vm: NewBatchViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageDialog(message, onDismiss = vm::clearMessage)
    ScreenScaffold(title = "নতুন ব্যাচ", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            DropdownField(
                label = "অনুমোদিত ফর্মুলা", options = s.formulas, selected = s.formulas.firstOrNull { it.id == s.formulaId },
                optionLabel = { "${it.code} · ${it.name} (v${it.version})" }, onSelect = vm::onFormula,
                error = if (s.formulas.isEmpty()) "কোনো অনুমোদিত ফর্মুলা নেই; আগে ফর্মুলা অনুমোদন করুন" else null,
            )
            Spacer(Modifier.height(8.dp))
            LabeledField("পরিকল্পিত পরিমাণ (কেজি)", s.plannedKg, vm::onPlannedKg, numeric = true)
            Spacer(Modifier.height(8.dp))
            DropdownField(
                label = "কাঁচামাল যে গুদাম থেকে যাবে", options = s.warehouses,
                selected = s.warehouses.firstOrNull { it.id == s.sourceWarehouseId },
                optionLabel = { it.name }, onSelect = vm::onSource,
            )
            Spacer(Modifier.height(8.dp))
            DropdownField(
                label = "তৈরি ফিড যে গুদামে জমা হবে", options = s.warehouses,
                selected = s.warehouses.firstOrNull { it.id == s.fgWarehouseId },
                optionLabel = { it.name }, onSelect = vm::onFg,
            )
            Spacer(Modifier.height(8.dp))
            LabeledField("বস্তার সাইজ (কেজি; লুজ হলে ০)", s.bagKg, vm::onBagKg, numeric = true)
            Spacer(Modifier.height(8.dp))
            LabeledField("শেলফ-লাইফ (দিন)", s.shelfDays, vm::onShelfDays, numeric = true)
            Spacer(Modifier.height(8.dp))
            LabeledField("নোট (ঐচ্ছিক)", s.note, vm::onNote, singleLine = false)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { vm.create(onCreated) }, modifier = Modifier.fillMaxWidth()) { Text("ব্যাচ তৈরি করুন") }
        }
    }
}

@Composable
fun BatchDetailScreen(onBack: () -> Unit, vm: BatchDetailViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val names by vm.itemNames.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var confirmCancel by remember { mutableStateOf(false) }
    var showRun by remember { mutableStateOf(false) }
    var showComplete by remember { mutableStateOf(false) }

    MessageDialog(message, onDismiss = vm::clearMessage)
    if (confirmCancel) {
        ConfirmDialog(
            "ব্যাচ বাতিল", "এই পরিকল্পিত ব্যাচ বাতিল হবে। নিশ্চিত?",
            onConfirm = { confirmCancel = false; vm.cancel(onBack) }, onDismiss = { confirmCancel = false },
        )
    }
    if (showRun) AddRunDialog(onDismiss = { showRun = false }, onSave = { a, b, c, d, e -> showRun = false; vm.addRun(a, b, c, d, e) })
    if (showComplete) CompleteDialog(
        onDismiss = { showComplete = false },
        onSave = { a, b, c, d -> showComplete = false; vm.complete(a, b, c, d, onDone = {}) },
    )

    val b = st.batch
    ScreenScaffold(title = b?.batchNo ?: "ব্যাচ", onBack = onBack) { padding ->
        if (b == null) return@ScreenScaffold
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            KeyValueRow("স্ট্যাটাস", statusBn(b.status))
            KeyValueRow("ফর্মুলা সংস্করণ", "v${b.formulaVersion}" + if (b.isMedicated) " (ওষুধযুক্ত)" else "")
            KeyValueRow("পরিকল্পিত", kg(b.plannedQtyGrams))
            if (b.status == BatchStatus.COMPLETED) KeyValueRow("QC", QcStatus.labelBn(b.qcStatus))

            if (b.status == BatchStatus.PLANNED) {
                SectionTitle("কোন লট থেকে কত যাবে (FEFO)")
                val p = preview
                if (p == null) Text("হিসাব হচ্ছে…") else {
                    p.lines.forEach { line ->
                        Text("${line.name}: প্রয়োজন ${kg(line.requiredGrams)}", style = MaterialTheme.typography.titleSmall)
                        line.allocations.forEach { a ->
                            Text("   লট ${p.lotBatchNo[a.lotId] ?: a.lotId}: ${kg(a.qtyGrams)}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (!line.isCovered) ErrorText("   ঘাটতি ${kg(line.shortfallGrams)}")
                    }
                    ErrorText(p.shortageMessage)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = vm::start, enabled = p.shortageMessage == null, modifier = Modifier.fillMaxWidth()) {
                        Text("ব্যাচ শুরু (কাঁচামাল স্টক থেকে কাটা হবে)")
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { confirmCancel = true }, modifier = Modifier.fillMaxWidth()) { Text("ব্যাচ বাতিল") }
            }

            if (b.status == BatchStatus.IN_PROGRESS || b.status == BatchStatus.COMPLETED) {
                SectionTitle("ব্যবহৃত উপাদান (ট্রেসেবিলিটি)")
                st.consumption.forEach { c ->
                    Text(
                        "${names[c.itemId] ?: "অজানা আইটেম"}: ${kg(c.qtyGrams)}" + (c.lotBatchNo?.let { " · লট $it" } ?: "") +
                            if (c.isTraceable) " · ওষুধ/প্রিমিক্স" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SectionTitle("মেশিন রান")
                if (st.runs.isEmpty()) Text("কোনো রান নেই", style = MaterialTheme.typography.bodySmall)
                st.runs.forEach { r ->
                    Text(
                        "${r.machineName ?: r.machineId}: ${f1(r.runHours)} ঘণ্টা" +
                            if (r.downtimeMinutes > 0) " (ডাউনটাইম ${r.downtimeMinutes} মি, ${r.downtimeReason.orEmpty()})" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (b.status == BatchStatus.IN_PROGRESS) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showRun = true }, modifier = Modifier.fillMaxWidth()) { Text("+ মেশিন রান যোগ") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { showComplete = true }, modifier = Modifier.fillMaxWidth()) { Text("ব্যাচ সম্পূর্ণ করুন") }
            }

            if (b.status == BatchStatus.COMPLETED) {
                SectionTitle("ফলাফল ও খরচ")
                KeyValueRow("আউটপুট", kg(b.outputGoodGrams) + if (b.outputBags > 0) " (${b.outputBags} বস্তা)" else "")
                KeyValueRow("ইয়িল্ড", "${f1(b.yieldPct)}%")
                KeyValueRow("অপচয়", kg(b.wastageGrams))
                KeyValueRow("কাঁচামাল ও ওষুধ", Money.format(b.materialCostPaisa))
                KeyValueRow("প্যাকেজিং", Money.format(b.packagingCostPaisa))
                KeyValueRow("লেবার", Money.format(b.labourCostPaisa))
                KeyValueRow("ওভারহেড", Money.format(b.overheadCostPaisa))
                KeyValueRow("মেশিন", Money.format(b.machineCostPaisa))
                KeyValueRow("মোট খরচ", Money.format(b.totalCostPaisa))
                KeyValueRow("খরচ প্রতি কেজি", Money.format(b.costPerKgPaisa))
                b.expiryAt?.let { KeyValueRow("মেয়াদ", formatDate(it)) }
                if (b.qcStatus == QcStatus.PENDING) {
                    Text("QC পাস না হওয়া পর্যন্ত এই ব্যাচের ফিড বিক্রি করা যাবে না।", color = MaterialTheme.colorScheme.secondary)
                }

                if (st.cleaning.isNotEmpty()) {
                    SectionTitle(if (b.cleaningDone) "লাইন-ক্লিনিং সম্পন্ন ✔" else "লাইন-ক্লিনিং চেকলিস্ট (নতুন ব্যাচের আগে বাধ্যতামূলক)")
                    st.cleaning.forEach { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = item.done, onCheckedChange = { vm.setCleaning(item.id, it) })
                            Text(item.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AddRunDialog(onDismiss: () -> Unit, onSave: (String, String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("") }
    var down by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("মেশিন রান") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                LabeledField("মেশিনের নাম (যেমন মিক্সার-১)", name, { name = it })
                LabeledField("চালু থাকার ঘণ্টা", hours, { hours = it }, numeric = true)
                LabeledField("ডাউনটাইম (মিনিট, ঐচ্ছিক)", down, { down = it }, numeric = true)
                LabeledField("ডাউনটাইমের কারণ", reason, { reason = it })
                LabeledField("ঘণ্টা-খরচ (৳, ঐচ্ছিক)", cost, { cost = it }, numeric = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, hours, down, reason, cost) }) { Text("সংরক্ষণ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } },
    )
}

@Composable
private fun CompleteDialog(onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var out by remember { mutableStateOf("") }
    var labour by remember { mutableStateOf("") }
    var overhead by remember { mutableStateOf("") }
    var packaging by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ব্যাচ সম্পূর্ণ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                LabeledField("ভালো আউটপুট (কেজি)", out, { out = it }, numeric = true)
                LabeledField("লেবার খরচ (৳)", labour, { labour = it }, numeric = true)
                LabeledField("বিদ্যুৎ/ওভারহেড (৳)", overhead, { overhead = it }, numeric = true)
                LabeledField("প্যাকেজিং খরচ (৳)", packaging, { packaging = it }, numeric = true)
                Text("সম্পূর্ণ করলে তৈরি ফিড স্টকে ঢুকবে (QC পাস না হওয়া পর্যন্ত বিক্রি বন্ধ) এবং হিসাবে এন্ট্রি হবে।", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(out, labour, overhead, packaging) }) { Text("সম্পূর্ণ করুন") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } },
    )
}
