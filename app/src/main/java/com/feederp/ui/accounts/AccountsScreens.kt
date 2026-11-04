package com.feederp.ui.accounts

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
import com.feederp.accounts.ChartOfAccounts
import com.feederp.accounts.PnlLine
import com.feederp.data.accounts.ExpenseEntity
import com.feederp.data.sales.PayMethods
import com.feederp.ui.common.KeyValueRow
import com.feederp.ui.common.LabeledField
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.SearchableList
import com.feederp.ui.common.formatDate
import com.feederp.ui.master.DropdownField
import com.feederp.ui.master.MessageDialog
import com.feederp.ui.master.SectionTitle

@Composable
fun ReportsScreen(onBack: () -> Unit, vm: ReportsViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageDialog(message, onDismiss = vm::clearMessage)
    ScreenScaffold(title = "হিসাব রিপোর্ট", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            DropdownField(
                label = "সময়কাল", options = Period.values().toList(), selected = st.period,
                optionLabel = { it.labelBn }, onSelect = vm::load,
            )

            st.pnl?.let { p ->
                SectionTitle("লাভ-ক্ষতি")
                PnlGroup("আয়", p.revenue)
                KeyValueRow("মোট আয়", "৳ ${taka(p.totalRevenuePaisa)}")
                PnlGroup("সরাসরি খরচ (COGS ইত্যাদি)", p.directCosts)
                KeyValueRow("মোট লাভ (Gross)", "৳ ${taka(p.grossProfitPaisa)}")
                PnlGroup("পরিচালন খরচ", p.expenses)
                Spacer(Modifier.height(4.dp))
                KeyValueRow("নিট লাভ/(ক্ষতি)", "৳ ${taka(p.netProfitPaisa)}")
                Text(
                    "COGS ডেলিভারি নিশ্চিত হলে এবং উৎপাদন খরচ ব্যাচ সম্পূর্ণ হলে লেজারে আসে; তার আগের মাল এখানে ধরা নেই।",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            st.trial?.let { t ->
                SectionTitle("ট্রায়াল ব্যালেন্স")
                t.rows.forEach { r ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${r.code} ${r.name}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(2f))
                        Text(if (r.debitPaisa > 0) taka(r.debitPaisa) else "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(if (r.creditPaisa > 0) taka(r.creditPaisa) else "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
                KeyValueRow("মোট ডেবিট", taka(t.totalDebitPaisa))
                KeyValueRow("মোট ক্রেডিট", taka(t.totalCreditPaisa))
                Text(
                    if (t.balanced) "মিলেছে ✔" else "মিলছে না ✖ — ডেবিট ও ক্রেডিটে গরমিল, হিসাবরক্ষককে জানান",
                    color = if (t.balanced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                if (st.period != Period.ALL) {
                    Text("নির্বাচিত সময়কালের নড়াচড়া দেখানো হচ্ছে; জের (opening balance) ধরা নেই।", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PnlGroup(title: String, lines: List<PnlLine>) {
    if (lines.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
    lines.forEach { KeyValueRow("${it.code} ${it.name}", taka(it.amountPaisa)) }
}

private fun expenseStatusBn(s: String) = when (s) {
    "APPROVED" -> "অনুমোদিত"; "PENDING" -> "অনুমোদনের অপেক্ষায়"; "REJECTED" -> "বাতিল"; "VOID" -> "ভয়েড"; else -> s
}

@Composable
fun ExpenseScreen(onBack: () -> Unit, vm: ExpenseViewModel = hiltViewModel()) {
    val list by vm.expenses.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var addOpen by remember { mutableStateOf(false) }
    var reasonFor by remember { mutableStateOf<Pair<ExpenseEntity, Boolean>?>(null) }  // (খরচ, true=রিজেক্ট / false=ভয়েড)
    var selected by remember { mutableStateOf<ExpenseEntity?>(null) }
    MessageDialog(message, onDismiss = vm::clearMessage)
    val names = remember { ChartOfAccounts.defaults.associate { it.code to it.nameBn } }

    ScreenScaffold(title = "খরচ", onBack = onBack, onAdd = { addOpen = true }) { padding ->
        SearchableList(
            items = list, modifier = Modifier.padding(padding),
            searchText = { "${it.expenseNo} ${it.description} ${it.payeeName.orEmpty()}" },
            emptyText = "এখনও কোনো খরচ নেই", onClick = { selected = it },
        ) { e: ExpenseEntity ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${e.expenseNo} · ${names[e.accountCode] ?: e.accountCode}", style = MaterialTheme.typography.titleSmall)
                Text("৳ ${taka(e.amountPaisa)}", style = MaterialTheme.typography.titleSmall)
            }
            Text("${formatDate(e.expenseDate)} · ${e.description} · ${expenseStatusBn(e.status)}", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (addOpen) AddExpenseDialog(
        onDismiss = { addOpen = false },
        onSave = { code, amount, method, desc, payee -> vm.create(code, amount, method, desc, payee) { addOpen = false } },
    )

    selected?.let { e ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(e.expenseNo) },
            text = {
                Column {
                    Text("${names[e.accountCode] ?: e.accountCode} — ৳ ${taka(e.amountPaisa)}")
                    Text(e.description)
                    e.payeeName?.let { Text("প্রাপক: $it") }
                    Text("অবস্থা: ${expenseStatusBn(e.status)}")
                    e.rejectReason?.let { Text("বাতিলের কারণ: $it") }
                }
            },
            confirmButton = {
                if (vm.canApprove && e.status == "PENDING") TextButton(onClick = { vm.approve(e.id); selected = null }) { Text("অনুমোদন") }
            },
            dismissButton = {
                Row {
                    if (vm.canApprove && e.status == "PENDING") TextButton(onClick = { reasonFor = e to true; selected = null }) { Text("বাতিল করুন") }
                    if (vm.canApprove && e.status == "APPROVED") TextButton(onClick = { reasonFor = e to false; selected = null }) { Text("ভয়েড (রিভার্স)") }
                    TextButton(onClick = { selected = null }) { Text("বন্ধ") }
                }
            },
        )
    }

    reasonFor?.let { (e, isReject) ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { reasonFor = null },
            title = { Text(if (isReject) "খরচ বাতিল" else "খরচ ভয়েড") },
            text = { LabeledField("কারণ (বাধ্যতামূলক)", reason, { reason = it }) },
            confirmButton = {
                TextButton(onClick = { if (isReject) vm.reject(e.id, reason) else vm.void(e.id, reason); reasonFor = null }) { Text("নিশ্চিত") }
            },
            dismissButton = { TextButton(onClick = { reasonFor = null }) { Text("থাক") } },
        )
    }
}

@Composable
private fun AddExpenseDialog(onDismiss: () -> Unit, onSave: (String?, String, String, String, String) -> Unit) {
    var code by remember { mutableStateOf<String?>(null) }
    var amount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("CASH") }
    var desc by remember { mutableStateOf("") }
    var payee by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("নতুন খরচ") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DropdownField(
                    label = "খাত", options = ChartOfAccounts.expenseChoices.map { it.code }, selected = code,
                    optionLabel = { c -> ChartOfAccounts.expenseChoices.firstOrNull { it.code == c }?.nameBn ?: c }, onSelect = { code = it },
                )
                Spacer(Modifier.height(6.dp))
                LabeledField("অঙ্ক (৳)", amount, { amount = it }, numeric = true)
                Spacer(Modifier.height(6.dp))
                DropdownField(
                    label = "মাধ্যম", options = PayMethods.all, selected = method,
                    optionLabel = { PayMethods.labelBn(it) }, onSelect = { method = it },
                )
                Spacer(Modifier.height(6.dp))
                LabeledField("বিবরণ", desc, { desc = it })
                Spacer(Modifier.height(6.dp))
                LabeledField("প্রাপক (ঐচ্ছিক)", payee, { payee = it })
                Text("৫,০০০ টাকার বেশি হলে (ম্যানেজার ছাড়া) অনুমোদনের অপেক্ষায় থাকবে।", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(code, amount, method, desc, payee) }) { Text("সংরক্ষণ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } },
    )
}
