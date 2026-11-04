package com.feederp.ui.sales

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
import androidx.compose.material3.OutlinedButton
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
import com.feederp.data.PartyType
import com.feederp.data.sales.PayMethods
import com.feederp.data.sales.SalesStatus
import com.feederp.ui.common.KeyValueRow
import com.feederp.ui.common.LabeledField
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.SearchableList
import com.feederp.ui.common.formatDate
import com.feederp.ui.master.DropdownField
import com.feederp.ui.master.MessageDialog
import com.feederp.ui.master.SectionTitle

@Composable
fun InvoiceListScreen(
    onBack: () -> Unit, onNew: () -> Unit, onOpen: (String) -> Unit, onPrices: () -> Unit,
    vm: InvoiceListViewModel = hiltViewModel(),
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    ScreenScaffold(
        title = "বিক্রয় ইনভয়েস", onBack = onBack, onAdd = onNew,
        actions = { TextButton(onClick = onPrices) { Text("দামের তালিকা") } },
    ) { padding ->
        SearchableList(
            items = rows, modifier = Modifier.padding(padding),
            searchText = { "${it.invoice.invoiceNo} ${it.partyName}" },
            emptyText = "এখনও কোনো ইনভয়েস নেই",
            onClick = { onOpen(it.invoice.id) },
        ) { r: InvoiceRow ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${r.invoice.invoiceNo} · ${r.partyName}", style = MaterialTheme.typography.titleSmall)
                Text("৳ ${taka(r.invoice.totalPaisa)}", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "${formatDate(r.invoice.invoiceDate)} · ${SalesStatus.labelBn(r.invoice.status)} · " +
                    "আদায় ৳ ${taka(r.invoice.paidPaisa)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun NewInvoiceScreen(onBack: () -> Unit, onSaved: (String) -> Unit, vm: NewInvoiceViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val parties by vm.parties.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val warehouses by vm.warehouses.collectAsStateWithLifecycle()
    val balance by vm.balance.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageDialog(message, onDismiss = vm::clearMessage)

    ScreenScaffold(title = "নতুন ইনভয়েস", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            DropdownField(
                label = "ক্রেতা", options = parties, selected = parties.firstOrNull { it.id == s.partyId },
                optionLabel = { "${it.name} (${if (it.type == PartyType.DEALER) "ডিলার" else "কাস্টমার"})" },
                onSelect = vm::selectParty,
            )
            balance?.let { Text("বর্তমান বাকি: ৳ ${taka(it)}", style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(6.dp))
            DropdownField(
                label = "গুদাম (যেখান থেকে ডেলিভারি)", options = warehouses, selected = warehouses.firstOrNull { it.id == s.warehouseId },
                optionLabel = { it.name }, onSelect = { vm.selectWarehouse(it.id) },
            )

            SectionTitle("আইটেম")
            if (!vm.canEditPrice) {
                Text("দাম তালিকা থেকে আসে; বদলাতে ম্যানেজারের অনুমতি লাগে।", style = MaterialTheme.typography.bodySmall)
            }
            s.lines.forEachIndexed { i, l ->
                DropdownField(
                    label = "আইটেম ${i + 1}", options = items, selected = items.firstOrNull { it.id == l.itemId },
                    optionLabel = { it.name }, onSelect = { vm.setItem(i, it.id) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField("পরিমাণ (কেজি)", l.qtyKg, { vm.setQty(i, it) }, Modifier.weight(1f), numeric = true)
                    LabeledField("দাম (৳/কেজি)", l.priceTaka, { vm.setPrice(i, it) }, Modifier.weight(1f), numeric = true)
                }
                if (s.lines.size > 1) TextButton(onClick = { vm.removeLine(i) }) { Text("এই লাইন বাদ দিন") }
                Spacer(Modifier.height(6.dp))
            }
            OutlinedButton(onClick = vm::addLine) { Text("+ আরেকটি আইটেম") }

            SectionTitle("হিসাব")
            val subtotal = vm.subtotalPaisa()
            KeyValueRow("সাবটোটাল", "৳ ${taka(subtotal)}")
            LabeledField("ডিসকাউন্ট (৳)", s.discountTaka, vm::setDiscount, numeric = true)
            Spacer(Modifier.height(6.dp))
            LabeledField("এখনই আদায় (৳, ঐচ্ছিক)", s.paidTaka, vm::setPaid, numeric = true)
            Spacer(Modifier.height(6.dp))
            DropdownField(
                label = "পেমেন্ট মাধ্যম", options = payMethodOptions, selected = s.method,
                optionLabel = { PayMethods.labelBn(it) }, onSelect = vm::setMethod,
            )
            Spacer(Modifier.height(6.dp))
            LabeledField("নোট (ঐচ্ছিক)", s.note, vm::setNote)
            Spacer(Modifier.height(6.dp))
            LabeledField("অনুমোদনের কারণ (ক্রেডিট লিমিট/ডিসকাউন্ট ছাড়ালে, শুধু ম্যানেজার)", s.overrideReason, vm::setOverride)
            Text("স্টক ডেলিভারির সময় কমবে; QC-পাস লট থেকেই মাল যাবে।", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Button(onClick = { vm.save(onSaved) }, modifier = Modifier.fillMaxWidth()) { Text("ইনভয়েস তৈরি করুন") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun InvoiceDetailScreen(onBack: () -> Unit, onReturn: (String) -> Unit = {}, vm: InvoiceDetailViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var collectOpen by remember { mutableStateOf(false) }
    var cancelOpen by remember { mutableStateOf(false) }
    var reverseId by remember { mutableStateOf<String?>(null) }
    MessageDialog(message, onDismiss = vm::clearMessage)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pdfLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        if (uri != null) ctx.contentResolver.openOutputStream(uri)?.let { vm.exportPdf(it) {} }
    }

    val inv = st.invoice
    ScreenScaffold(title = inv?.invoiceNo ?: "ইনভয়েস", onBack = onBack) { padding ->
        if (inv == null) return@ScreenScaffold
        val due = (inv.totalPaisa - inv.paidPaisa - st.returnedPaisa).coerceAtLeast(0)
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            KeyValueRow("ক্রেতা", st.partyName)
            KeyValueRow("তারিখ", formatDate(inv.invoiceDate))
            KeyValueRow("অবস্থা", SalesStatus.labelBn(inv.status))
            KeyValueRow("ক্রেতার মোট বাকি (লেজার)", "৳ ${taka(st.balancePaisa)}")
            inv.note?.let { KeyValueRow("নোট", it) }

            SectionTitle("আইটেম")
            st.lines.forEach { l ->
                Text(
                    "${st.itemNames[l.itemId] ?: "?"}: ${kg(l.qtyGrams)} কেজি × ৳ ${taka(l.unitPricePaisaPerKg)} = ৳ ${taka(l.lineTotalPaisa)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(6.dp))
            KeyValueRow("সাবটোটাল", "৳ ${taka(inv.subtotalPaisa)}")
            KeyValueRow("ডিসকাউন্ট", "৳ ${taka(inv.discountPaisa)}")
            KeyValueRow("মোট", "৳ ${taka(inv.totalPaisa)}")
            KeyValueRow("আদায়", "৳ ${taka(inv.paidPaisa)}")
            if (st.returnedPaisa > 0) KeyValueRow("ফেরত (ক্রেডিট)", "৳ ${taka(st.returnedPaisa)}")
            KeyValueRow("বকেয়া", "৳ ${taka(due)}")

            SectionTitle("আদায়ের ইতিহাস")
            if (st.collections.isEmpty()) Text("কোনো আদায় নেই", style = MaterialTheme.typography.bodySmall)
            st.collections.forEach { c ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${c.collectionNo} · ${formatDate(c.collectionDate)} · ${PayMethods.labelBn(c.method)}",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    Text("৳ ${taka(c.amountPaisa)}", style = MaterialTheme.typography.bodyMedium)
                }
                if (vm.canApprove && c.amountPaisa > 0 && c.reversesId == null &&
                    st.collections.none { it.reversesId == c.id }
                ) TextButton(onClick = { reverseId = c.id }) { Text("এই আদায় রিভার্স করুন") }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { pdfLauncher.launch("${inv.invoiceNo}.pdf") }, modifier = Modifier.fillMaxWidth()) { Text("PDF সংরক্ষণ / শেয়ার ফাইল") }
            if (inv.status != SalesStatus.CANCELLED) {
                Spacer(Modifier.height(10.dp))
                if (due > 0) Button(onClick = { collectOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("আদায় নিন") }
                if (vm.canApprove && (inv.status == SalesStatus.PARTIAL || inv.status == SalesStatus.DELIVERED)) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { onReturn(inv.id) }, modifier = Modifier.fillMaxWidth()) { Text("ফেরত নিন") }
                }
                if (vm.canApprove && inv.status == SalesStatus.CONFIRMED) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { cancelOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("ইনভয়েস বাতিল") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (collectOpen) CollectDialog(
        onDismiss = { collectOpen = false },
        onConfirm = { amount, method, note -> vm.collect(amount, method, note) { collectOpen = false } },
    )
    if (cancelOpen) ReasonDialog(
        title = "ইনভয়েস বাতিল", onDismiss = { cancelOpen = false },
        onConfirm = { reason -> vm.cancel(reason); cancelOpen = false },
    )
    reverseId?.let { id ->
        ReasonDialog(
            title = "আদায় রিভার্স", onDismiss = { reverseId = null },
            onConfirm = { reason -> vm.reverseCollection(id, reason); reverseId = null },
        )
    }
}

@Composable
private fun CollectDialog(onDismiss: () -> Unit, onConfirm: (String, String, String) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("CASH") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("আদায়") },
        text = {
            Column {
                LabeledField("অঙ্ক (৳)", amount, { amount = it }, numeric = true)
                Spacer(Modifier.height(6.dp))
                DropdownField(
                    label = "মাধ্যম", options = payMethodOptions, selected = method,
                    optionLabel = { PayMethods.labelBn(it) }, onSelect = { method = it },
                )
                Spacer(Modifier.height(6.dp))
                LabeledField("নোট (ঐচ্ছিক)", note, { note = it })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(amount, method, note) }) { Text("সংরক্ষণ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } },
    )
}

@Composable
private fun ReasonDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { LabeledField("কারণ (বাধ্যতামূলক)", reason, { reason = it }) },
        confirmButton = { TextButton(onClick = { onConfirm(reason) }) { Text("নিশ্চিত") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("থাক") } },
    )
}

@Composable
fun PriceListScreen(onBack: () -> Unit, vm: PriceListViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageDialog(message, onDismiss = vm::clearMessage)
    var editing by remember { mutableStateOf<PriceRow?>(null) }

    ScreenScaffold(title = "দামের তালিকা (৳/কেজি)", onBack = onBack) { padding ->
        SearchableList(
            items = rows, modifier = Modifier.padding(padding),
            searchText = { it.item.name }, emptyText = "কোনো ফিনিশড গুডস আইটেম নেই",
            onClick = if (vm.canEdit) ({ r: PriceRow -> editing = r }) else null,
        ) { r: PriceRow ->
            Text(r.item.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "সবার: ${r.all?.let(::taka) ?: "—"} · ডিলার: ${r.dealer?.let(::taka) ?: "—"} · কাস্টমার: ${r.customer?.let(::taka) ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    editing?.let { row ->
        var kind by remember(row.item.id) { mutableStateOf<PartyType?>(null) }
        var price by remember(row.item.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(row.item.name) },
            text = {
                Column {
                    DropdownField(
                        label = "কার জন্য", options = listOf<PartyType?>(null, PartyType.DEALER, PartyType.CUSTOMER), selected = kind,
                        optionLabel = { when (it) { null -> "সবার জন্য"; PartyType.DEALER -> "ডিলার"; else -> "কাস্টমার" } },
                        onSelect = { kind = it },
                    )
                    Spacer(Modifier.height(6.dp))
                    LabeledField("দাম (৳/কেজি)", price, { price = it }, numeric = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.save(row.item.id, kind, price) { editing = null } }) { Text("সংরক্ষণ") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("বাতিল") } },
        )
    }
}
