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
import com.feederp.core.Money
import com.feederp.data.PartyEntity
import com.feederp.data.PartyType
import com.feederp.master.MasterLabels
import com.feederp.ui.common.ConfirmDialog
import com.feederp.ui.common.LabeledField
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.SearchableList

private const val ALL_PARTY = "সব"

@Composable
fun PartyListScreen(onBack: () -> Unit, vm: PartyViewModel = hiltViewModel()) {
    val parties by vm.parties.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<PartyType?>(null) }
    var toDelete by remember { mutableStateOf<PartyEntity?>(null) }
    val filtered = remember(parties, filter) {
        val f = filter
        if (f == null) parties else parties.filter { it.type == f }
    }
    val labels = remember { listOf(ALL_PARTY) + PartyType.values().map { MasterLabels.partyType(it) } }

    ScreenScaffold(title = "পার্টি (সাপ্লায়ার/কাস্টমার/ডিলার)", onBack = onBack, onAdd = if (vm.canEdit) vm::startNew else null) { pad ->
        Column(Modifier.padding(pad)) {
            DropdownField(
                label = "ধরন",
                options = labels,
                selected = filter?.let { MasterLabels.partyType(it) } ?: ALL_PARTY,
                optionLabel = { it },
                onSelect = { l -> filter = PartyType.values().firstOrNull { MasterLabels.partyType(it) == l } },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            SearchableList(
                items = filtered,
                modifier = Modifier.weight(1f),
                searchText = { it.name + " " + (it.phone ?: "") + " " + (it.address ?: "") },
                emptyText = "কোনো পার্টি নেই",
            ) { p ->
                Text(p.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    MasterLabels.partyType(p.type) + (p.phone?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                p.address?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (p.type != PartyType.SUPPLIER) {
                    Text("ক্রেডিট লিমিট: ${Money.format(p.creditLimitPaisa)}", style = MaterialTheme.typography.bodySmall)
                }
                if (vm.canEdit) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.startEdit(p) }) { Text("সম্পাদনা") }
                        TextButton(onClick = { toDelete = p }) { Text("মুছুন") }
                    }
                }
            }
        }
    }

    editing?.let { form ->
        AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text(if (form.isNew) "নতুন পার্টি" else "পার্টি সম্পাদনা") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DropdownField(
                        label = "ধরন *",
                        options = PartyType.values().toList(),
                        selected = form.type,
                        optionLabel = { MasterLabels.partyType(it) },
                        onSelect = { t -> vm.update { it.copy(type = t) } },
                    )
                    LabeledField("নাম *", form.name, { v -> vm.update { it.copy(name = v) } })
                    LabeledField("ফোন", form.phone, { v -> vm.update { it.copy(phone = v) } })
                    LabeledField("ঠিকানা", form.address, { v -> vm.update { it.copy(address = v) } }, singleLine = false)
                    if (form.type != PartyType.SUPPLIER) {
                        LabeledField("ক্রেডিট লিমিট (টাকা)", form.creditLimitTaka, { v -> vm.update { it.copy(creditLimitTaka = v) } }, numeric = true)
                    }
                    ErrorText(message)
                }
            },
            confirmButton = { TextButton(onClick = vm::save) { Text("সংরক্ষণ") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("বাতিল") } },
        )
    }
    toDelete?.let { p ->
        ConfirmDialog(
            title = "পার্টি মুছবেন?",
            message = "${p.name} মোছা হবে।",
            onConfirm = { vm.delete(p.id); toDelete = null },
            onDismiss = { toDelete = null },
        )
    }
    // ফর্ম ডায়ালগ খোলা থাকলে ত্রুটি ডায়ালগের ভেতরেই দেখানো হয়
    if (editing == null) MessageDialog(message, onDismiss = vm::clearMessage)
}
