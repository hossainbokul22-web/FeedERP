package com.feederp.ui.purchase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.feederp.core.Money
import com.feederp.core.Weight
import com.feederp.purchase.PayMethod
import com.feederp.purchase.PoStatus
import com.feederp.purchase.PurchaseCalc
import com.feederp.ui.common.KeyValueRow
import com.feederp.ui.common.LabeledField
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.SearchableList
import com.feederp.ui.common.formatDate
import com.feederp.ui.master.DateField
import com.feederp.ui.master.DropdownField
import com.feederp.ui.master.MessageDialog
import com.feederp.ui.master.SectionTitle

// ---------------- PO তালিকা ----------------

@Composable
fun PoListScreen(
    onBack: () -> Unit,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    vm: PoListViewModel = hiltViewModel(),
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    ScreenScaffold(title = "ক্রয় অর্ডার (PO)", onBack = onBack, onAdd = if (vm.canCreate) onNew else null) { pad ->
        SearchableList(
            items = rows,
            modifier = Modifier.padding(pad),
            searchText = { it.po.poNo + " " + it.supplierName },
            emptyText = "কোনো PO নেই",
            onClick = { onOpen(it.po.id) },
        ) { r ->
            Text("${r.po.poNo} · ${r.supplierName}", style = MaterialTheme.typography.titleMedium)
            Text("তারিখ: ${formatDate(r.po.orderDate)} · ${PoStatus.labelBn(r.po.status)}", style = MaterialTheme.typography.bodySmall)
            Text("মোট: ${Money.format(r.po.totalPaisa)}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------------- PO ফর্ম ----------------

@Composable
fun PoFormScreen(onBack: () -> Unit, vm: PoFormViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    ScreenScaffold(title = if (vm.isEdit) "PO সম্পাদনা" else "নতুন PO", onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DropdownField(
                label = "সাপ্লায়ার *", options = suppliers, selected = suppliers.firstOrNull { it.id == form.supplierId },
                optionLabel = { it.name }, onSelect = { s -> vm.update { it.copy(supplierId = s.id) } },
            )
            DateField("অর্ডারের তারিখ *", form.orderDate, { d -> if (d != null) vm.update { it.copy(orderDate = d) } })
            DateField(
                "প্রত্যাশিত ডেলিভারি", form.expectedDate, { d -> vm.update { it.copy(expectedDate = d) } },
                clearable = true,
            )
            LabeledField("নোট", form.note, { v -> vm.update { it.copy(note = v) } }, singleLine = false)
            SectionTitle("আইটেম")
            form.lines.forEachIndexed { idx, line ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DropdownField(
                            label = "আইটেম *", options = items, selected = items.firstOrNull { it.id == line.itemId },
                            optionLabel = { "${it.name} (${it.code})" },
                            onSelect = { i -> vm.updateLine(idx) { it.copy(itemId = i.id) } },
                        )
                        LabeledField("পরিমাণ (কেজি) *", line.qtyKg, { v -> vm.updateLine(idx) { it.copy(qtyKg = v) } }, numeric = true)
                        LabeledField("দর (টাকা/কেজি) *", line.rateTaka, { v -> vm.updateLine(idx) { it.copy(rateTaka = v) } }, numeric = true)
                        if (form.lines.size > 1) {
                            TextButton(onClick = { vm.removeLine(idx) }) { Text("লাইন মুছুন") }
                        }
                    }
                }
            }
            OutlinedButton(onClick = vm::addLine, modifier = Modifier.fillMaxWidth()) { Text("+ আইটেম যোগ করুন") }
            Text("আনুমানিক মোট: ${Money.format(form.totalPaisa())}", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = { vm.save { onBack() } },
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            ) { Text("খসড়া সংরক্ষণ করুন") }
        }
    }
    MessageDialog(message, onDismiss = vm::clearMessage)
}

// ---------------- PO বিস্তারিত ----------------

@Composable
fun PoDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onReceive: (String) -> Unit,
    vm: PoDetailViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var showCancel by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    val po = ui.po

    ScreenScaffold(title = po?.poNo ?: "PO", onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (po == null) {
                Text("PO পাওয়া যায়নি", Modifier.padding(16.dp))
            } else {
                KeyValueRow("সাপ্লায়ার", ui.supplierName)
                KeyValueRow("অবস্থা", PoStatus.labelBn(po.status))
                KeyValueRow("অর্ডারের তারিখ", formatDate(po.orderDate))
                po.expectedDate?.let { KeyValueRow("প্রত্যাশিত ডেলিভারি", formatDate(it)) }
                KeyValueRow("মোট", Money.format(po.totalPaisa))
                po.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                SectionTitle("আইটেম")
                ui.lines.forEach { l ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Text(l.itemName, style = MaterialTheme.typography.titleSmall)
                            KeyValueRow("অর্ডার", Weight.formatKg(l.orderedGrams))
                            KeyValueRow("রিসিভ", Weight.formatKg(l.receivedGrams))
                            KeyValueRow("বাকি", Weight.formatKg(l.remainingGrams))
                            KeyValueRow("দর (কেজি)", Money.format(l.ratePaisaPerKg))
                        }
                    }
                }

                if (ui.grns.isNotEmpty()) {
                    SectionTitle("এই PO র রিসিভ")
                    ui.grns.forEach { g ->
                        Text("${g.grnNo} · ${formatDate(g.receivedAt)} · ${Money.format(g.totalPaisa)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (po.status == PoStatus.DRAFT && vm.canPurchase) {
                        OutlinedButton(onClick = { onEdit(po.id) }) { Text("সম্পাদনা") }
                    }
                    if (po.status == PoStatus.DRAFT && vm.canApprove) {
                        Button(onClick = vm::approve) { Text("অনুমোদন") }
                    }
                    if (PoStatus.canReceive(po.status) && vm.canPurchase) {
                        Button(onClick = { onReceive(po.id) }) { Text("মাল রিসিভ") }
                    }
                }
                val canCancel = (po.status == PoStatus.DRAFT && vm.canPurchase) || (po.status == PoStatus.ORDERED && vm.canApprove)
                if (canCancel) {
                    TextButton(onClick = { reason = ""; showCancel = true }) { Text("PO বাতিল করুন") }
                }
            }
        }
    }
    if (showCancel) {
        AlertDialog(
            onDismissRequest = { showCancel = false },
            title = { Text("PO বাতিল") },
            text = { LabeledField("বাতিলের কারণ *", reason, { reason = it }) },
            confirmButton = { TextButton(onClick = { vm.cancel(reason); showCancel = false }) { Text("বাতিল করুন") } },
            dismissButton = { TextButton(onClick = { showCancel = false }) { Text("না") } },
        )
    }
    MessageDialog(message, onDismiss = vm::clearMessage)
}

// ---------------- গুডস রিসিভ ----------------

@Composable
fun GoodsReceiptScreen(onBack: () -> Unit, vm: GoodsReceiptViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()
    val warehouses by vm.warehouses.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val remaining by vm.remaining.collectAsStateWithLifecycle()
    val poNo by vm.poNo.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    ScreenScaffold(title = if (vm.isPoReceipt) "মাল রিসিভ (PO ${poNo ?: ""})" else "মাল রিসিভ", onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DropdownField(
                label = "সাপ্লায়ার *", options = suppliers, selected = suppliers.firstOrNull { it.id == form.supplierId },
                optionLabel = { it.name }, onSelect = { s -> vm.update { it.copy(supplierId = s.id) } },
                enabled = !vm.isPoReceipt,
            )
            DropdownField(
                label = "গুদাম *", options = warehouses, selected = warehouses.firstOrNull { it.id == form.warehouseId },
                optionLabel = { it.name + if (it.isMedicineStore) " (ওষুধ)" else "" },
                onSelect = { w -> vm.update { it.copy(warehouseId = w.id) } },
            )
            DateField("রিসিভের তারিখ *", form.receivedAt, { d -> if (d != null) vm.update { it.copy(receivedAt = d) } })
            LabeledField("নোট", form.note, { v -> vm.update { it.copy(note = v) } }, singleLine = false)
            SectionTitle("আইটেম")
            form.lines.forEachIndexed { idx, line ->
                val item = items.firstOrNull { it.id == line.itemId }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DropdownField(
                            label = "আইটেম *", options = items, selected = item,
                            optionLabel = { "${it.name} (${it.code})" },
                            onSelect = { i -> vm.updateLine(idx) { it.copy(itemId = i.id) } },
                        )
                        line.itemId?.let { id ->
                            remaining[id]?.let { Text("PO তে বাকি: ${Weight.formatKg(it)}", style = MaterialTheme.typography.bodySmall) }
                        }
                        if (item != null && item.requiresQc) {
                            Text("QC লাগবে: লট প্রথমে অপেক্ষমাণ থাকবে", style = MaterialTheme.typography.bodySmall)
                        }
                        LabeledField("ব্যাচ নম্বর *", line.batchNo, { v -> vm.updateLine(idx) { it.copy(batchNo = v) } })
                        LabeledField("পরিমাণ (কেজি) *", line.qtyKg, { v -> vm.updateLine(idx) { it.copy(qtyKg = v) } }, numeric = true)
                        LabeledField("দর (টাকা/কেজি) *", line.rateTaka, { v -> vm.updateLine(idx) { it.copy(rateTaka = v) } }, numeric = true)
                        DateField(
                            "উৎপাদনের তারিখ", line.mfgAt, { d -> vm.updateLine(idx) { it.copy(mfgAt = d) } }, clearable = true,
                        )
                        DateField(
                            if (item?.trackExpiry == true) "মেয়াদ *" else "মেয়াদ", line.expiryAt,
                            { d -> vm.updateLine(idx) { it.copy(expiryAt = d) } }, clearable = true,
                        )
                        LabeledField("আর্দ্রতা (%)", line.moisture, { v -> vm.updateLine(idx) { it.copy(moisture = v) } }, numeric = true)
                        if (form.lines.size > 1) {
                            TextButton(onClick = { vm.removeLine(idx) }) { Text("লাইন মুছুন") }
                        }
                    }
                }
            }
            OutlinedButton(onClick = vm::addLine, modifier = Modifier.fillMaxWidth()) { Text("+ আইটেম যোগ করুন") }
            Button(
                onClick = { vm.save { onBack() } },
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            ) { Text("রিসিভ সংরক্ষণ করুন") }
        }
    }
    MessageDialog(message, onDismiss = vm::clearMessage)
}

// ---------------- রিসিভ তালিকা ----------------

@Composable
fun GrnListScreen(onBack: () -> Unit, vm: GrnListViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()

    ScreenScaffold(title = "গুডস রিসিভ তালিকা", onBack = onBack) { pad ->
        SearchableList(
            items = rows,
            modifier = Modifier.padding(pad),
            searchText = { it.grn.grnNo + " " + it.supplierName },
            emptyText = "কোনো রিসিভ নেই",
            onClick = { vm.select(it.grn.id) },
        ) { r ->
            Text("${r.grn.grnNo} · ${r.supplierName}", style = MaterialTheme.typography.titleMedium)
            Text("গুদাম: ${r.warehouseName} · ${formatDate(r.grn.receivedAt)}", style = MaterialTheme.typography.bodySmall)
            Text("মোট: ${Money.format(r.grn.totalPaisa)}", style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (selectedId != null) {
        AlertDialog(
            onDismissRequest = { vm.select(null) },
            title = { Text("রিসিভের আইটেম") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (detail.isEmpty()) Text("কোনো লাইন নেই")
                    detail.forEach { d ->
                        Column {
                            Text("${d.itemName} · ব্যাচ ${d.line.batchNo}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${Weight.formatKg(d.line.qtyGrams)} × ${Money.format(d.line.ratePaisaPerKg)}/কেজি = " +
                                    Money.format(PurchaseCalc.lineTotalPaisa(d.line.qtyGrams, d.line.ratePaisaPerKg)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            d.line.expiryAt?.let { Text("মেয়াদ: ${formatDate(it)}", style = MaterialTheme.typography.bodySmall) }
                            d.line.expectedGrams?.let {
                                Text("PO অনুযায়ী প্রত্যাশিত: ${Weight.formatKg(it)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.select(null) }) { Text("বন্ধ করুন") } },
        )
    }
}

// ---------------- সাপ্লায়ার পেমেন্ট ----------------

@Composable
fun SupplierPaymentScreen(onBack: () -> Unit, vm: SupplierPaymentViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()
    val balance by vm.balancePaisa.collectAsStateWithLifecycle()
    val payments by vm.payments.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    ScreenScaffold(title = "সাপ্লায়ার পেমেন্ট", onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!vm.canPay) {
                Text("পেমেন্ট দেওয়ার অনুমতি নেই (ম্যানেজার/মালিক)", color = MaterialTheme.colorScheme.error)
            }
            DropdownField(
                label = "সাপ্লায়ার *", options = suppliers, selected = suppliers.firstOrNull { it.id == form.supplierId },
                optionLabel = { it.name }, onSelect = { s -> vm.update { it.copy(supplierId = s.id) } },
            )
            balance?.let { b ->
                val due = PurchaseCalc.payableDuePaisa(b)
                val adv = PurchaseCalc.advanceWithSupplierPaisa(b)
                Text(
                    when {
                        due > 0 -> "বাকি (আমরা দেব): ${Money.format(due)}"
                        adv > 0 -> "অগ্রিম (সাপ্লায়ার থেকে পাব): ${Money.format(adv)}"
                        else -> "কোনো বাকি নেই"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            LabeledField("অঙ্ক (টাকা) *", form.amountTaka, { v -> vm.update { it.copy(amountTaka = v) } }, numeric = true)
            DropdownField(
                label = "মাধ্যম *", options = PayMethod.all, selected = form.method,
                optionLabel = { PayMethod.labelBn(it) }, onSelect = { m -> vm.update { it.copy(method = m) } },
            )
            DateField("তারিখ *", form.paidAt, { d -> if (d != null) vm.update { it.copy(paidAt = d) } })
            LabeledField("নোট", form.note, { v -> vm.update { it.copy(note = v) } }, singleLine = false)
            Button(
                onClick = vm::save, enabled = vm.canPay,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            ) { Text("পেমেন্ট করুন") }

            if (payments.isNotEmpty()) {
                SectionTitle("আগের পেমেন্ট")
                payments.forEach { p ->
                    Text(
                        "${formatDate(p.paidAt)} · ${Money.format(p.amountPaisa)} · ${PayMethod.labelBn(p.method)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
    MessageDialog(message, onDismiss = vm::clearMessage)
}
