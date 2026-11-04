package com.feederp.ui.formulation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.feederp.data.formulation.FormulaEntity
import com.feederp.data.formulation.FormulaStatus
import com.feederp.formulation.FormulaEvaluation
import com.feederp.ui.common.ConfirmDialog
import com.feederp.ui.common.KeyValueRow
import com.feederp.ui.common.LabeledField
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.SearchableList
import com.feederp.ui.master.DropdownField
import com.feederp.ui.master.ErrorText
import com.feederp.ui.master.MessageDialog
import com.feederp.ui.master.SectionTitle
import com.feederp.ui.master.SwitchRow
import java.util.Locale

private fun statusBn(s: FormulaStatus) = when (s) {
    FormulaStatus.DRAFT -> "খসড়া"
    FormulaStatus.APPROVED -> "অনুমোদিত"
    FormulaStatus.ARCHIVED -> "আর্কাইভ"
}

private fun fmt(d: Double, digits: Int = 2) = String.format(Locale.US, "%.${digits}f", d)

@Composable
fun FormulaListScreen(
    onBack: () -> Unit,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    vm: FormulaListViewModel = hiltViewModel(),
) {
    val formulas by vm.formulas.collectAsStateWithLifecycle()
    ScreenScaffold(title = "ফর্মুলা", onBack = onBack, onAdd = onNew) { padding ->
        Column(Modifier.padding(padding)) {
            SearchableList(
                items = formulas,
                searchText = { "${it.code} ${it.name}" },
                emptyText = "কোনো ফর্মুলা নেই। + চেপে নতুন ফর্মুলা বানান।",
                onClick = { onOpen(it.id) },
            ) { f: FormulaEntity ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${f.code} · ${f.name}", style = MaterialTheme.typography.titleMedium)
                    Text("v${f.version}", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    statusBn(f.status) + if (f.isMedicated) " · ওষুধযুক্ত" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (f.status == FormulaStatus.APPROVED) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun FormulaEditorScreen(
    onBack: () -> Unit,
    onOpenOther: (String) -> Unit,
    vm: FormulaEditorViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var confirmApprove by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var optOpen by remember { mutableStateOf(false) }
    val opt by vm.opt.collectAsStateWithLifecycle()

    MessageDialog(message, onDismiss = vm::clearMessage)
    if (optOpen) OptimizerDialog(s.ingredientOptions, opt, vm) { optOpen = false; vm.clearOptimizer() }
    if (confirmApprove) {
        ConfirmDialog(
            "অনুমোদন", "অনুমোদনের পর এই ভার্সন আর বদলানো যাবে না। চালিয়ে যাবেন?",
            onConfirm = { confirmApprove = false; vm.approve(onDone = onBack) },
            onDismiss = { confirmApprove = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            "খসড়া মুছুন", "এই খসড়া ফর্মুলা মুছে ফেলা হবে। নিশ্চিত?",
            onConfirm = { confirmDelete = false; vm.deleteDraft(onDone = onBack) },
            onDismiss = { confirmDelete = false },
        )
    }

    ScreenScaffold(
        title = if (s.formulaId == null) "নতুন ফর্মুলা" else "${s.code} · v${s.version} (${statusBn(s.status)})",
        onBack = onBack,
    ) { padding ->
        if (!s.loaded) return@ScreenScaffold
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            if (!s.editable) {
                Text(
                    "এই ভার্সন ${statusBn(s.status)}, তাই শুধু দেখা যাবে। বদলাতে \"নতুন ভার্সন\" বানান।",
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            SectionTitle("মূল তথ্য")
            LabeledField("কোড", s.code, vm::onCode)
            Spacer(Modifier.height(8.dp))
            LabeledField("ফর্মুলার নাম", s.name, vm::onName)
            Spacer(Modifier.height(8.dp))
            DropdownField(
                label = "তৈরি পণ্য", options = s.productOptions,
                selected = s.productOptions.firstOrNull { it.id == s.productItemId },
                optionLabel = { it.name }, onSelect = vm::onProduct, enabled = s.editable,
                error = if (s.productOptions.isEmpty()) "আগে মাস্টার ডাটায় ফিনিশড গুডস আইটেম যোগ করুন" else null,
            )
            SwitchRow("ওষুধযুক্ত ফিড (Medicated)", s.isMedicated, vm::onMedicated)
            LabeledField("প্রাণীর ধরন (যেমন ব্রয়লার)", s.species, vm::onSpecies)
            Spacer(Modifier.height(8.dp))
            LabeledField("বয়স (দিন)", s.ageDays, vm::onAge, numeric = true)

            SectionTitle("নিউট্রিয়েন্ট লক্ষ্যমাত্রা (ঐচ্ছিক)")
            LabeledField("ন্যূনতম প্রোটিন %", s.minProtein, { vm.onTarget("minProtein", it) }, numeric = true)
            LabeledField("ন্যূনতম শক্তি (kcal/kg)", s.minEnergy, { vm.onTarget("minEnergy", it) }, numeric = true)
            LabeledField("ন্যূনতম ফ্যাট %", s.minFat, { vm.onTarget("minFat", it) }, numeric = true)
            LabeledField("সর্বোচ্চ ফ্যাট %", s.maxFat, { vm.onTarget("maxFat", it) }, numeric = true)
            LabeledField("সর্বোচ্চ ফাইবার %", s.maxFibre, { vm.onTarget("maxFibre", it) }, numeric = true)
            LabeledField("সর্বোচ্চ আর্দ্রতা %", s.maxMoisture, { vm.onTarget("maxMoisture", it) }, numeric = true)

            SectionTitle("উপাদান (গ্রাম প্রতি টন খাদ্যে)")
            s.lines.forEach { line ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        DropdownField(
                            label = "আইটেম", options = s.ingredientOptions,
                            selected = s.ingredientOptions.firstOrNull { it.id == line.itemId },
                            optionLabel = { it.name }, onSelect = { vm.onLineItem(line.key, it) },
                            enabled = s.editable,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LabeledField(
                                "গ্রাম/টন", line.gramsText, { vm.onLineGrams(line.key, it) },
                                modifier = Modifier.weight(1f), numeric = true,
                            )
                            if (s.editable) {
                                Spacer(Modifier.width(4.dp))
                                IconButton(onClick = { vm.removeLine(line.key) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "লাইন মুছুন")
                                }
                            }
                        }
                    }
                }
            }
            if (s.editable) OutlinedButton(onClick = vm::addLine) { Text("+ উপাদান যোগ করুন") }
            if (s.editable) OutlinedButton(onClick = { optOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("সর্বনিম্ন খরচের মিশ্রণ বের করুন") }
            val total = s.totalGramsPerTon()
            KeyValueRow("মোট", "${fmt(total, 0)} গ্রাম/টন (${fmt(total / 10_000.0)}%)")

            EvaluationPanel(s.evaluation)

            Spacer(Modifier.height(16.dp))
            if (s.editable) {
                Button(onClick = { vm.save() }, modifier = Modifier.fillMaxWidth()) { Text("খসড়া সংরক্ষণ") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { confirmApprove = true }, modifier = Modifier.fillMaxWidth()) { Text("সংরক্ষণ ও অনুমোদন") }
                if (s.formulaId != null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("খসড়া মুছুন") }
                }
            } else {
                Button(onClick = { vm.newVersion(onOpenOther) }, modifier = Modifier.fillMaxWidth()) { Text("নতুন ভার্সন বানান") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EvaluationPanel(eval: FormulaEvaluation?) {
    SectionTitle("হিসাব (লাইভ)")
    if (eval == null) {
        Text("উপাদান যোগ করলে নিউট্রিয়েন্ট ও খরচ এখানে দেখা যাবে।", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val n = eval.nutrients
    KeyValueRow("প্রোটিন", "${fmt(n.proteinPct)}%")
    KeyValueRow("শক্তি", "${fmt(n.energyKcalPerKg, 0)} kcal/kg")
    KeyValueRow("ফ্যাট", "${fmt(n.fatPct)}%")
    KeyValueRow("ফাইবার", "${fmt(n.fibrePct)}%")
    KeyValueRow("আর্দ্রতা", "${fmt(n.moisturePct)}%")
    KeyValueRow("খরচ প্রতি কেজি", Money.format(Math.round(n.costPerKgPaisa)))
    KeyValueRow("খরচ প্রতি টন", Money.format(n.costPerTonPaisa))
    if (eval.itemsWithoutCost.isNotEmpty()) {
        Text(
            "দাম নেই (স্টকে ক্রয়মূল্য নেই): " + eval.itemsWithoutCost.joinToString(", ") + "। খরচ কম দেখাতে পারে।",
            color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall,
        )
    }
    if (n.itemsMissingNutrientData.isNotEmpty()) {
        Text(
            "নিউট্রিয়েন্ট ডাটা নেই (০ ধরা হয়েছে): " + n.itemsMissingNutrientData.joinToString(", "),
            color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall,
        )
    }
    eval.report.blockers.forEach { ErrorText("✖ $it") }
    eval.report.warnings.forEach {
        Text("⚠ $it", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
    }
    if (eval.report.ok) {
        Text("অনুমোদনে কোনো বাধা নেই।", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OptimizerDialog(
    candidates: List<com.feederp.data.ItemEntity>, run: com.feederp.formulation.OptRun?, vm: FormulaEditorViewModel, onClose: () -> Unit,
) {
    val picked = remember { androidx.compose.runtime.mutableStateMapOf<String, Pair<String, String>>() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("সর্বনিম্ন খরচের মিশ্রণ") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (run == null) {
                    Text("উপরের নিউট্রিয়েন্ট লক্ষ্যমাত্রা মেনে সবচেয়ে সস্তা মিশ্রণ। যেসব উপাদান ব্যবহার করবেন টিক দিন; অবশ্য-লাগবে (প্রিমিক্স/মিনারেল) উপাদানে সর্বনিম্ন % দিন। স্টকে দাম না থাকা বা নিষিদ্ধ আইটেম বাদ যাবে।", style = MaterialTheme.typography.bodySmall)
                    candidates.forEach { it ->
                        val on = it.id in picked
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = on, onCheckedChange = { c -> if (c) picked[it.id] = "" to "" else picked.remove(it.id) })
                            Text(it.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (on) Row {
                            LabeledField("ন্যূনতম %", picked[it.id]?.first.orEmpty(), { v -> picked[it.id] = v to (picked[it.id]?.second ?: "") }, modifier = Modifier.weight(1f), numeric = true)
                            Spacer(Modifier.width(6.dp))
                            LabeledField("সর্বোচ্চ %", picked[it.id]?.second.orEmpty(), { v -> picked[it.id] = (picked[it.id]?.first ?: "") to v }, modifier = Modifier.weight(1f), numeric = true)
                        }
                    }
                } else {
                    val r = run.result
                    Text(r.message, style = MaterialTheme.typography.bodyMedium)
                    if (run.skipped.isNotEmpty()) Text("বাদ: ${run.skipped.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    r.relaxHints.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    if (r.ok) {
                        r.lines.forEach { Text("${it.name}: ${fmt(it.pct)}% (${fmt(it.gramsPerTon, 0)} গ্রাম/টন)", style = MaterialTheme.typography.bodyMedium) }
                        Text("খরচ ≈ ৳ ${fmt(r.costPerTonPaisa / 100.0)} / টন", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        },
        confirmButton = {
            if (run == null) androidx.compose.material3.TextButton(onClick = { vm.runOptimizer(picked.toMap()) }) { Text("হিসাব করুন") }
            else if (run.result.ok) androidx.compose.material3.TextButton(onClick = { vm.applyOptimizer(); onClose() }) { Text("লাইনে বসান") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { if (run != null) vm.clearOptimizer() else onClose() }) { Text(if (run != null) "আবার বাছাই" else "বাতিল") }
        },
    )
}
