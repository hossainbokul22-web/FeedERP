package com.feederp.ui.delivery

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feederp.data.distribution.DistStatus
import com.feederp.ui.common.KeyValueRow
import com.feederp.ui.common.LabeledField
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.SearchableList
import com.feederp.ui.common.formatDate
import com.feederp.ui.master.DropdownField
import com.feederp.ui.master.MessageDialog
import com.feederp.ui.master.SectionTitle

private fun noteStatusBn(n: com.feederp.data.distribution.DeliveryNoteEntity): String = when {
    n.deliveredAt != null && n.status == DistStatus.NOTE_PARTIAL -> "আংশিক পৌঁছেছে"
    n.deliveredAt != null -> "ডেলিভারি সম্পন্ন"
    n.loadedAt != null -> "লোড হয়েছে, পথে"
    else -> "প্ল্যান করা (লোড বাকি)"
}

@Composable
fun DeliveryListScreen(onBack: () -> Unit, onNew: () -> Unit, onOpen: (String) -> Unit, vm: DeliveryListViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    ScreenScaffold(title = "ডেলিভারি নোট (চালান)", onBack = onBack, onAdd = onNew) { padding ->
        SearchableList(
            items = rows, modifier = Modifier.padding(padding),
            searchText = { "${it.note.challanNo} ${it.note.invoiceNo} ${it.partyName}" },
            emptyText = "এখনও কোনো ডেলিভারি নোট নেই", onClick = { onOpen(it.note.id) },
        ) { r: NoteRow ->
            Text("${r.note.challanNo} · ${r.partyName}", style = MaterialTheme.typography.titleSmall)
            Text("ইনভয়েস ${r.note.invoiceNo} · ${noteStatusBn(r.note)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun NewDeliveryScreen(onBack: () -> Unit, onSaved: (String) -> Unit, vm: NewDeliveryViewModel = hiltViewModel()) {
    val invoices by vm.invoices.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageDialog(message, onDismiss = vm::clearMessage)

    ScreenScaffold(title = "নতুন ডেলিভারি নোট", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            DropdownField(
                label = "ইনভয়েস (ডেলিভারি বাকি)", options = invoices, selected = invoices.firstOrNull { it.invoice.id == selected },
                optionLabel = { "${it.invoice.invoiceNo} · ${it.partyName}" }, onSelect = { vm.select(it.invoice.id) },
            )
            if (selected != null) {
                SectionTitle("কত কেজি পাঠানো হবে")
                Text("০ রাখলে ওই লাইন এই নোটে বাদ যাবে।", style = MaterialTheme.typography.bodySmall)
                rows.forEachIndexed { i, r ->
                    Spacer(Modifier.height(6.dp))
                    LabeledField(
                        "${r.itemName} (বাকি ${kgText(r.remainingGrams)} কেজি)", r.qtyKg, { vm.setQty(i, it) }, numeric = true,
                    )
                }
                Spacer(Modifier.height(6.dp))
                LabeledField("নোট (ঐচ্ছিক)", note, vm::setNote)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { vm.save(onSaved) }, modifier = Modifier.fillMaxWidth()) { Text("ডেলিভারি নোট তৈরি করুন") }
            }
        }
    }
}

@Composable
fun DeliveryDetailScreen(onBack: () -> Unit, vm: DeliveryDetailViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var confirmOpen by remember { mutableStateOf(false) }
    MessageDialog(message, onDismiss = vm::clearMessage)

    val n = st.note
    ScreenScaffold(title = n?.challanNo ?: "ডেলিভারি নোট", onBack = onBack) { padding ->
        if (n == null) return@ScreenScaffold
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            KeyValueRow("ক্রেতা", st.partyName)
            KeyValueRow("ইনভয়েস", n.invoiceNo)
            KeyValueRow("অবস্থা", noteStatusBn(n))
            n.address?.let { KeyValueRow("ঠিকানা", it) }
            n.deliveredAt?.let { KeyValueRow("ডেলিভারির তারিখ", formatDate(it)) }
            n.receivedBy?.let { KeyValueRow("গ্রহীতা", it) }
            n.note?.let { KeyValueRow("নোট", it) }

            SectionTitle("আইটেম (কেজি)")
            st.lines.forEach { l ->
                Text(
                    "${st.itemNames[l.itemId] ?: "?"}: প্ল্যান ${kgText(l.plannedGrams)} · লোড ${kgText(l.loadedGrams)} · " +
                        "পৌঁছেছে ${kgText(l.deliveredGrams)}" + if (l.returnedGrams > 0) " · ফেরত ${kgText(l.returnedGrams)}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (vm.canDispatch) {
                Spacer(Modifier.height(12.dp))
                if (n.loadedAt == null) {
                    Text("লোড করলে FEFO অনুযায়ী QC-পাস লট থেকে স্টক কমবে। একবারই করা যায়।", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = vm::load, modifier = Modifier.fillMaxWidth()) { Text("মাল লোড করুন") }
                } else if (n.deliveredAt == null) {
                    Button(onClick = { confirmOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("ডেলিভারি নিশ্চিত করুন") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmOpen) {
        var receiver by remember { mutableStateOf("") }
        val delivered = remember { mutableStateMapOf<String, String>() }
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text("ডেলিভারি নিশ্চিত") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LabeledField("গ্রহীতার নাম", receiver, { receiver = it })
                    Text("পুরো মাল পৌঁছালে নিচের ঘর ফাঁকা রাখুন; কম পৌঁছালে কেজি লিখুন (বাকিটা স্টকে ফিরবে)।", style = MaterialTheme.typography.bodySmall)
                    st.lines.forEach { l ->
                        Spacer(Modifier.height(6.dp))
                        LabeledField(
                            "${st.itemNames[l.itemId] ?: "?"} (লোড ${kgText(l.loadedGrams)})", delivered[l.id].orEmpty(),
                            { delivered[l.id] = it }, numeric = true,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirm(receiver, delivered.toMap()) { confirmOpen = false } }) { Text("নিশ্চিত") }
            },
            dismissButton = { TextButton(onClick = { confirmOpen = false }) { Text("বাতিল") } },
        )
    }
}
