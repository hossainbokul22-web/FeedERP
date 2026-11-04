package com.feederp.ui.master

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feederp.core.Weight
import com.feederp.data.ItemEntity
import com.feederp.data.ItemType
import com.feederp.master.MasterLabels
import com.feederp.ui.common.ConfirmDialog
import com.feederp.ui.common.LabeledField
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.SearchableList

private const val ALL_LABEL = "সব"

@Composable
fun ItemListScreen(
    onBack: () -> Unit,
    onNew: () -> Unit,
    onEdit: (String) -> Unit,
    vm: ItemListViewModel = hiltViewModel(),
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val units by vm.units.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var typeFilter by remember { mutableStateOf<ItemType?>(null) }
    var toDelete by remember { mutableStateOf<ItemEntity?>(null) }
    var scanOpen by remember { mutableStateOf(false) }
    val unitSymbol = remember(units) { units.associate { it.id to it.symbol } }
    val filtered = remember(items, typeFilter) {
        val t = typeFilter
        if (t == null) items else items.filter { it.type == t }
    }
    val labels = remember { listOf(ALL_LABEL) + ItemType.values().map { MasterLabels.itemType(it) } }

    if (scanOpen) com.feederp.ui.common.BarcodeScannerDialog(
        onResult = { code -> scanOpen = false; vm.findByScan(code, onEdit) },
        onDismiss = { scanOpen = false },
    )
    ScreenScaffold(
        title = "আইটেম", onBack = onBack, onAdd = if (vm.canEdit) onNew else null,
        actions = { androidx.compose.material3.TextButton(onClick = { scanOpen = true }) { Text("স্ক্যান") } },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            DropdownField(
                label = "ধরন",
                options = labels,
                selected = typeFilter?.let { MasterLabels.itemType(it) } ?: ALL_LABEL,
                optionLabel = { it },
                onSelect = { label -> typeFilter = ItemType.values().firstOrNull { MasterLabels.itemType(it) == label } },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            SearchableList(
                items = filtered,
                modifier = Modifier.weight(1f),
                searchText = { it.name + " " + it.code + " " + (it.nameBn ?: "") + " " + (it.activeIngredient ?: "") },
                emptyText = "কোনো আইটেম নেই",
            ) { item ->
                Text(
                    item.name + (item.nameBn?.let { " ($it)" } ?: ""),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${item.code} · ${MasterLabels.itemType(item.type)} · ইউনিট: ${unitSymbol[item.unitId] ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (item.minStockGrams > 0.0) {
                    Text("মিনিমাম স্টক: ${Weight.formatKg(item.minStockGrams)}", style = MaterialTheme.typography.bodySmall)
                }
                val tags = buildList {
                    if (item.requiresQc) add("QC লাগে")
                    if (item.isControlled) add("নিয়ন্ত্রিত")
                    if (!item.trackExpiry) add("মেয়াদ ট্র্যাক নেই")
                }
                if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                if (MasterLabels.showsMedicineFields(item.type)) {
                    val parts = buildList {
                        item.activeIngredient?.let { add("উপাদান: $it") }
                        if (item.minDoseGPerTon != null || item.maxDoseGPerTon != null) {
                            add("ডোজ: ${item.minDoseGPerTon ?: "-"}–${item.maxDoseGPerTon ?: "-"} গ্রাম/টন")
                        }
                        item.withdrawalDays?.let { add("উইথড্রয়াল: $it দিন") }
                    }
                    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                if (vm.canEdit) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onEdit(item.id) }) { Text("সম্পাদনা") }
                        TextButton(onClick = { toDelete = item }) { Text("মুছুন") }
                    }
                }
            }
        }
    }
    toDelete?.let { item ->
        ConfirmDialog(
            title = "আইটেম মুছবেন?",
            message = "${item.name} (${item.code}) মোছা হবে।",
            onConfirm = { vm.delete(item.id); toDelete = null },
            onDismiss = { toDelete = null },
        )
    }
    MessageDialog(message, onDismiss = vm::clearMessage)
}

@Composable
fun ItemFormScreen(onBack: () -> Unit, vm: ItemFormViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val units by vm.units.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    LaunchedEffect(units, form.isNew) {
        if (form.isNew && form.unitId.isBlank() && units.isNotEmpty()) vm.update { it.copy(unitId = units.first().id) }
    }

    ScreenScaffold(title = if (form.isNew) "নতুন আইটেম" else "আইটেম সম্পাদনা", onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LabeledField("কোড *", form.code, { v -> vm.update { it.copy(code = v) } })
            LabeledField("নাম (ইংরেজি) *", form.name, { v -> vm.update { it.copy(name = v) } })
            LabeledField("নাম (বাংলা)", form.nameBn, { v -> vm.update { it.copy(nameBn = v) } })
            DropdownField(
                label = "ধরন *",
                options = ItemType.values().toList(),
                selected = form.type,
                optionLabel = { MasterLabels.itemType(it) },
                onSelect = { t ->
                    vm.update {
                        // ওষুধ/প্রিমিক্স/অ্যাডিটিভে সাধারণত QC ও মেয়াদ লাগে
                        if (MasterLabels.showsMedicineFields(t)) it.copy(type = t, requiresQc = true, trackLot = true, trackExpiry = true)
                        else it.copy(type = t)
                    }
                },
            )
            DropdownField(
                label = "ইউনিট *",
                options = units,
                selected = units.firstOrNull { it.id == form.unitId },
                optionLabel = { "${it.name} (${it.symbol})" },
                onSelect = { u -> vm.update { it.copy(unitId = u.id) } },
            )
            LabeledField("মিনিমাম স্টক (কেজি)", form.minStockKg, { v -> vm.update { it.copy(minStockKg = v) } }, numeric = true)
            SwitchRow("লট ট্র্যাক করুন", form.trackLot) { v ->
                vm.update { it.copy(trackLot = v, trackExpiry = if (v) it.trackExpiry else false) }
            }
            SwitchRow("মেয়াদ ট্র্যাক করুন", form.trackExpiry) { v ->
                vm.update { it.copy(trackExpiry = v, trackLot = if (v) true else it.trackLot) }
            }
            SwitchRow("গুডস রিসিভে QC লাগবে", form.requiresQc) { v -> vm.update { it.copy(requiresQc = v) } }
            SwitchRow("নিয়ন্ত্রিত আইটেম (ইস্যুতে ম্যানেজার অনুমতি)", form.isControlled) { v -> vm.update { it.copy(isControlled = v) } }
            LabeledField("সংরক্ষণ নির্দেশনা", form.storageNote, { v -> vm.update { it.copy(storageNote = v) } }, singleLine = false)

            if (MasterLabels.showsMedicineFields(form.type)) {
                SectionTitle("ওষুধ / প্রিমিক্স / অ্যাডিটিভ তথ্য")
                LabeledField(
                    if (form.type == ItemType.MEDICINE) "সক্রিয় উপাদান (Active Ingredient) *" else "সক্রিয় উপাদান",
                    form.activeIngredient, { v -> vm.update { it.copy(activeIngredient = v) } },
                )
                LabeledField("ঘনত্ব (%)", form.concentrationPct, { v -> vm.update { it.copy(concentrationPct = v) } }, numeric = true)
                LabeledField("সর্বনিম্ন ডোজ (গ্রাম/টন ফিড)", form.minDose, { v -> vm.update { it.copy(minDose = v) } }, numeric = true)
                LabeledField("সর্বোচ্চ ডোজ (গ্রাম/টন ফিড)", form.maxDose, { v -> vm.update { it.copy(maxDose = v) } }, numeric = true)
                LabeledField("উইথড্রয়াল পিরিয়ড (দিন)", form.withdrawalDays, { v -> vm.update { it.copy(withdrawalDays = v) } }, numeric = true)
            }

            SectionTitle("নিউট্রিয়েন্ট (প্রতি ১০০ গ্রামে, ঐচ্ছিক)")
            LabeledField("প্রোটিন (%)", form.protein, { v -> vm.update { it.copy(protein = v) } }, numeric = true)
            LabeledField("শক্তি (kcal/kg)", form.energy, { v -> vm.update { it.copy(energy = v) } }, numeric = true)
            LabeledField("ফ্যাট (%)", form.fat, { v -> vm.update { it.copy(fat = v) } }, numeric = true)
            LabeledField("ফাইবার (%)", form.fibre, { v -> vm.update { it.copy(fibre = v) } }, numeric = true)
            LabeledField("আর্দ্রতা (%)", form.moisture, { v -> vm.update { it.copy(moisture = v) } }, numeric = true)

            Button(
                onClick = { vm.save(onBack) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            ) { Text("সংরক্ষণ করুন") }
        }
    }
    MessageDialog(message, onDismiss = vm::clearMessage)
}
