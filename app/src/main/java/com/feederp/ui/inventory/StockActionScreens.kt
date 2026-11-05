package com.feederp.ui.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import com.feederp.ui.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feederp.core.Weight
import com.feederp.inventory.ExpiryState
import com.feederp.ui.common.LabeledField
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.formatDate
import com.feederp.ui.master.DropdownField
import com.feederp.ui.master.MessageDialog
import com.feederp.ui.master.SectionTitle

@Composable
fun AdjustmentScreen(onBack: () -> Unit, vm: AdjustmentViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val warehouses by vm.warehouses.collectAsStateWithLifecycle()
    val lots by vm.lotChoices.collectAsStateWithLifecycle()
    val current by vm.currentGrams.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    ScreenScaffold(title = "স্টক অ্যাডজাস্টমেন্ট", onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "গণনায় পাওয়া স্টকের সাথে খাতার স্টক না মিললে এখানে সংশোধন করুন। কারণ লেখা বাধ্যতামূলক এবং অডিট লগে থাকবে।",
                style = MaterialTheme.typography.bodySmall,
            )
            DropdownField(
                label = "আইটেম *", options = items, selected = items.firstOrNull { it.id == form.itemId },
                optionLabel = { "${it.name} (${it.code})" },
                onSelect = { i -> vm.update { it.copy(itemId = i.id, lotId = null) } },
            )
            DropdownField(
                label = "গুদাম *", options = warehouses, selected = warehouses.firstOrNull { it.id == form.warehouseId },
                optionLabel = { it.name }, onSelect = { w -> vm.update { it.copy(warehouseId = w.id) } },
            )
            DropdownField(
                label = "লট", options = lots, selected = lots.firstOrNull { it.lotId == form.lotId },
                optionLabel = { it.label }, onSelect = { l -> vm.update { it.copy(lotId = l.lotId) } },
            )
            current?.let { Text("বর্তমান স্টক: ${Weight.formatKg(it)}", style = MaterialTheme.typography.titleSmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = form.increase, onClick = { vm.update { it.copy(increase = true) } }, label = { Text("বাড়ান") })
                FilterChip(selected = !form.increase, onClick = { vm.update { it.copy(increase = false) } }, label = { Text("কমান") })
            }
            LabeledField("পরিমাণ (কেজি) *", form.qtyKg, { v -> vm.update { it.copy(qtyKg = v) } }, numeric = true)
            LabeledField("কারণ *", form.reason, { v -> vm.update { it.copy(reason = v) } }, singleLine = false)
            Button(onClick = vm::save, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("সংরক্ষণ করুন") }
        }
    }
    MessageDialog(message, onDismiss = vm::clearMessage)
}

@Composable
fun TransferScreen(onBack: () -> Unit, vm: TransferViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val warehouses by vm.warehouses.collectAsStateWithLifecycle()
    val lots by vm.lotChoices.collectAsStateWithLifecycle()
    val available by vm.availableGrams.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    ScreenScaffold(title = "গুদাম ট্রান্সফার", onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DropdownField(
                label = "আইটেম *", options = items, selected = items.firstOrNull { it.id == form.itemId },
                optionLabel = { "${it.name} (${it.code})" },
                onSelect = { i -> vm.update { it.copy(itemId = i.id, lotId = null) } },
            )
            DropdownField(
                label = "উৎস গুদাম *", options = warehouses, selected = warehouses.firstOrNull { it.id == form.fromId },
                optionLabel = { it.name }, onSelect = { w -> vm.update { it.copy(fromId = w.id, lotId = null) } },
            )
            DropdownField(
                label = "গন্তব্য গুদাম *",
                options = warehouses.filter { it.id != form.fromId },
                selected = warehouses.firstOrNull { it.id == form.toId },
                optionLabel = { it.name }, onSelect = { w -> vm.update { it.copy(toId = w.id) } },
            )
            if (lots.isNotEmpty()) {
                DropdownField(
                    label = "লট *", options = lots, selected = lots.firstOrNull { it.lotId == form.lotId },
                    optionLabel = { it.label }, onSelect = { l -> vm.update { it.copy(lotId = l.lotId) } },
                )
            }
            available?.let { Text("উৎস গুদামে স্টক: ${Weight.formatKg(it)}", style = MaterialTheme.typography.titleSmall) }
            LabeledField("পরিমাণ (কেজি) *", form.qtyKg, { v -> vm.update { it.copy(qtyKg = v) } }, numeric = true)
            LabeledField("নোট", form.note, { v -> vm.update { it.copy(note = v) } }, singleLine = false)
            Button(onClick = vm::save, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("ট্রান্সফার করুন") }
        }
    }
    MessageDialog(message, onDismiss = vm::clearMessage)
}

@Composable
fun AlertsScreen(onBack: () -> Unit, vm: AlertsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val empty = ui.low.isEmpty() && ui.negative.isEmpty() && ui.expiry.isEmpty()

    ScreenScaffold(title = "স্টক অ্যালার্ট", onBack = onBack) { pad ->
        LazyColumn(
            Modifier.padding(pad).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (empty) item { Text("কোনো অ্যালার্ট নেই", Modifier.padding(16.dp)) }
            if (ui.negative.isNotEmpty()) {
                item { SectionTitle("নেগেটিভ স্টক (সিঙ্ক/অফলাইন বিক্রির পর)") }
                items(ui.negative) { n ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(n.itemName, style = MaterialTheme.typography.titleMedium)
                            Text("গুদাম: ${n.warehouseName}", style = MaterialTheme.typography.bodySmall)
                            Text(Weight.formatKg(n.qtyGrams), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            if (ui.low.isNotEmpty()) {
                item { SectionTitle("মিনিমাম স্টকের নিচে") }
                items(ui.low) { l ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${l.item.name} (${l.item.code})", style = MaterialTheme.typography.titleMedium)
                            Text("আছে: ${Weight.formatKg(l.currentGrams)}", color = MaterialTheme.colorScheme.error)
                            Text("সর্বনিম্ন: ${Weight.formatKg(l.item.minStockGrams)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (ui.expiry.isNotEmpty()) {
                item { SectionTitle("মেয়াদ শেষ / শেষের পথে") }
                items(ui.expiry) { e ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${e.itemName} · ${e.batchNo}", style = MaterialTheme.typography.titleMedium)
                            Text("গুদাম: ${e.warehouseName} · ${Weight.formatKg(e.qtyGrams)}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                (if (e.state == ExpiryState.EXPIRED) "মেয়াদোত্তীর্ণ: " else "মেয়াদ: ") + formatDate(e.expiryAt),
                                color = if (e.state == ExpiryState.EXPIRED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        }
    }
}
