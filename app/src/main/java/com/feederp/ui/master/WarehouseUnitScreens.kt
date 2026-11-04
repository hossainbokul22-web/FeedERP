package com.feederp.ui.master

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feederp.data.UnitEntity
import com.feederp.data.WarehouseEntity
import com.feederp.ui.common.ConfirmDialog
import com.feederp.ui.common.LabeledField
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.SearchableList

@Composable
fun WarehouseListScreen(onBack: () -> Unit, vm: WarehouseViewModel = hiltViewModel()) {
    val list by vm.warehouses.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<WarehouseEntity?>(null) }

    ScreenScaffold(title = "গুদাম", onBack = onBack, onAdd = if (vm.canEdit) vm::startNew else null) { pad ->
        SearchableList(
            items = list,
            modifier = Modifier.padding(pad),
            searchText = { it.name },
            emptyText = "কোনো গুদাম নেই",
        ) { w ->
            Text(w.name, style = MaterialTheme.typography.titleMedium)
            if (w.isMedicineStore) Text("ওষুধ গুদাম", style = MaterialTheme.typography.labelMedium)
            if (vm.canEdit) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { vm.startEdit(w) }) { Text("সম্পাদনা") }
                    TextButton(onClick = { toDelete = w }) { Text("মুছুন") }
                }
            }
        }
    }

    editing?.let { form ->
        AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text(if (form.isNew) "নতুন গুদাম" else "গুদাম সম্পাদনা") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LabeledField("গুদামের নাম *", form.name, { v -> vm.update { it.copy(name = v) } })
                    SwitchRow("ওষুধ গুদাম", form.isMedicineStore, { v -> vm.update { it.copy(isMedicineStore = v) } })
                    ErrorText(message)
                }
            },
            confirmButton = { TextButton(onClick = vm::save) { Text("সংরক্ষণ") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("বাতিল") } },
        )
    }
    toDelete?.let { w ->
        ConfirmDialog(
            title = "গুদাম মুছবেন?",
            message = "${w.name} মোছা হবে।",
            onConfirm = { vm.delete(w.id); toDelete = null },
            onDismiss = { toDelete = null },
        )
    }
    if (editing == null) MessageDialog(message, onDismiss = vm::clearMessage)
}

@Composable
fun UnitListScreen(onBack: () -> Unit, vm: UnitViewModel = hiltViewModel()) {
    val list by vm.units.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<UnitEntity?>(null) }

    ScreenScaffold(title = "ইউনিট", onBack = onBack, onAdd = if (vm.canEdit) vm::startNew else null) { pad ->
        SearchableList(
            items = list,
            modifier = Modifier.padding(pad),
            searchText = { it.name + " " + it.symbol },
            emptyText = "কোনো ইউনিট নেই",
        ) { u ->
            Text("${u.name} (${u.symbol})", style = MaterialTheme.typography.titleMedium)
            Text("১ ${u.symbol} = ${trimNum(u.toBaseFactor)} গ্রাম", style = MaterialTheme.typography.bodySmall)
            if (vm.canEdit) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { vm.startEdit(u) }) { Text("সম্পাদনা") }
                    TextButton(onClick = { toDelete = u }) { Text("মুছুন") }
                }
            }
        }
    }

    editing?.let { form ->
        AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text(if (form.isNew) "নতুন ইউনিট" else "ইউনিট সম্পাদনা") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LabeledField("নাম * (যেমন কেজি)", form.name, { v -> vm.update { it.copy(name = v) } })
                    LabeledField("চিহ্ন * (যেমন kg)", form.symbol, { v -> vm.update { it.copy(symbol = v) } })
                    LabeledField("১ ইউনিট = কত গ্রাম *", form.factorGrams, { v -> vm.update { it.copy(factorGrams = v) } }, numeric = true)
                    ErrorText(message)
                }
            },
            confirmButton = { TextButton(onClick = vm::save) { Text("সংরক্ষণ") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("বাতিল") } },
        )
    }
    toDelete?.let { u ->
        ConfirmDialog(
            title = "ইউনিট মুছবেন?",
            message = "${u.name} মোছা হবে।",
            onConfirm = { vm.delete(u.id); toDelete = null },
            onDismiss = { toDelete = null },
        )
    }
    if (editing == null) MessageDialog(message, onDismiss = vm::clearMessage)
}

private fun trimNum(d: Double): String =
    if (d == Math.floor(d) && d < 1e15) d.toLong().toString() else d.toString()
