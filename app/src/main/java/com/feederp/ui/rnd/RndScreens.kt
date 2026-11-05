package com.feederp.ui.rnd

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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.feederp.ui.common.Text
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
import com.feederp.data.rnd.FieldFeedbackEntity
import com.feederp.data.rnd.FormulaTrialEntity
import com.feederp.data.rnd.TrialStatus
import com.feederp.rnd.FcrResult
import com.feederp.rnd.TrialRules
import com.feederp.ui.common.KeyValueRow
import com.feederp.ui.common.LabeledField
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.SearchableList
import com.feederp.ui.common.formatDate
import com.feederp.ui.master.DropdownField
import com.feederp.ui.master.MessageDialog
import com.feederp.ui.master.SectionTitle

@Composable
fun TrialListScreen(onBack: () -> Unit, onNew: () -> Unit, onOpen: (String) -> Unit, onFeedback: () -> Unit, vm: TrialListViewModel = hiltViewModel()) {
    val trials by vm.trials.collectAsStateWithLifecycle()
    ScreenScaffold(
        title = "R&D ট্রায়াল", onBack = onBack, onAdd = if (vm.canStart) onNew else null,
        actions = { TextButton(onClick = onFeedback) { Text("মাঠ ফিডব্যাক") } },
    ) { padding ->
        SearchableList(
            items = trials, modifier = Modifier.padding(padding), searchText = { "${it.trialNo} ${it.objective} ${it.changes}" },
            emptyText = "এখনও কোনো ট্রায়াল নেই", onClick = { onOpen(it.id) },
        ) { t: FormulaTrialEntity ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(t.trialNo, style = MaterialTheme.typography.titleSmall)
                Text(TrialRules.labelBn(t.status), style = MaterialTheme.typography.bodySmall)
            }
            Text(t.objective, style = MaterialTheme.typography.bodySmall)
            Text(formatDate(t.startDate), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun NewTrialScreen(onBack: () -> Unit, onCreated: (String) -> Unit, vm: NewTrialViewModel = hiltViewModel()) {
    val approved by vm.approved.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageDialog(message, onDismiss = vm::clearMessage)
    var base by remember { mutableStateOf<String?>(null) }
    var changes by remember { mutableStateOf("") }
    var objective by remember { mutableStateOf("") }
    ScreenScaffold(title = "নতুন ট্রায়াল", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            DropdownField("বেস ফর্মুলা (অনুমোদিত)", approved.map { it.id }, base, { id -> approved.firstOrNull { it.id == id }?.let { "${it.code} ${it.name} v${it.version}" } ?: id }, { base = it })
            Spacer(Modifier.height(6.dp)); LabeledField("কী বদলাতে চান", changes, { changes = it }, singleLine = false)
            Spacer(Modifier.height(6.dp)); LabeledField("উদ্দেশ্য", objective, { objective = it }, singleLine = false)
            Text("তৈরি হলে বেসের লাইনসহ একটি নতুন খসড়া ফর্মুলা হবে। লাইন বদলাতে ফর্মুলেশন মেনুতে ওই খসড়া খুলুন।", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Button(onClick = { vm.start(base, changes, objective, onCreated) }, modifier = Modifier.fillMaxWidth()) { Text("ট্রায়াল শুরু") }
        }
    }
}

@Composable
private fun ResultBlock(r: FcrResult) {
    Text("জীবিত ${r.survivors}/${r.animalCount} · মৃত্যু ${fmt(r.mortalityPct, 1)}%", style = MaterialTheme.typography.bodySmall)
    Text("FCR ${fmt(r.fcr)} · দৈনিক বৃদ্ধি ${fmt(r.avgDailyGainGrams, 1)} গ্রাম/প্রাণী", style = MaterialTheme.typography.bodySmall)
    r.costPerKgGainPaisa?.let { Text("প্রতি কেজি বৃদ্ধির খাদ্য-খরচ ৳ ${fmt(it / 100.0)}", style = MaterialTheme.typography.bodySmall) }
    r.eggProductionPct?.let { Text("ডিম উৎপাদন ${fmt(it, 1)}%", style = MaterialTheme.typography.bodySmall) }
    r.milkPerAnimalPerDayLiters?.let { Text("দুধ ${fmt(it)} লিটার/প্রাণী/দিন", style = MaterialTheme.typography.bodySmall) }
}

@Composable
fun TrialDetailScreen(onBack: () -> Unit, vm: TrialDetailViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageDialog(message, onDismiss = vm::clearMessage)
    var addOpen by remember { mutableStateOf(false) }
    var decision by remember { mutableStateOf<String?>(null) }   // QC | APPROVE | REJECT
    val t = st.trial
    ScreenScaffold(title = t?.trialNo ?: "ট্রায়াল", onBack = onBack) { padding ->
        if (t == null) return@ScreenScaffold
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            KeyValueRow("অবস্থা", TrialRules.labelBn(t.status))
            KeyValueRow("বেস", st.baseName)
            KeyValueRow("প্রস্তাবিত", st.proposedName)
            KeyValueRow("পরিবর্তন", t.changes)
            KeyValueRow("উদ্দেশ্য", t.objective)
            t.qcNote?.let { KeyValueRow("QC নোট", it) }
            t.decisionNote?.let { KeyValueRow("সিদ্ধান্তের নোট", it) }

            SectionTitle("সমষ্টিগত ফল")
            st.total?.let { ResultBlock(it) } ?: Text("এখনও কোনো ফল নেই", style = MaterialTheme.typography.bodySmall)

            SectionTitle("গ্রুপ")
            st.groups.forEach { g ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("${g.record.groupName} (${g.record.species}) · ${g.record.days} দিন", style = MaterialTheme.typography.titleSmall)
                        ResultBlock(g.result)
                        if (vm.canRnd && TrialRules.canEnterData(t.status)) TextButton(onClick = { vm.removeRecord(g.record.id) }) { Text("মুছুন") }
                    }
                }
            }
            if (vm.canRnd && TrialRules.canEnterData(t.status)) {
                OutlinedButton(onClick = { addOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("+ গ্রুপের ফল যোগ") }
                Spacer(Modifier.height(6.dp))
                Button(onClick = vm::submit, modifier = Modifier.fillMaxWidth()) { Text("QC-তে জমা দিন") }
            }
            if (t.status == TrialStatus.SUBMITTED && vm.canQc) Button(onClick = { decision = "QC" }, modifier = Modifier.fillMaxWidth()) { Text("QC যাচাই সম্পন্ন") }
            if (t.status == TrialStatus.QC_VERIFIED && vm.canApprove) Button(onClick = { decision = "APPROVE" }, modifier = Modifier.fillMaxWidth()) { Text("অনুমোদন (ফর্মুলাও অনুমোদিত হবে)") }
            val canReject = when (t.status) {
                TrialStatus.RUNNING -> vm.canRnd; TrialStatus.SUBMITTED -> vm.canQc; TrialStatus.QC_VERIFIED -> vm.canApprove; else -> false
            }
            if (canReject) OutlinedButton(onClick = { decision = "REJECT" }, modifier = Modifier.fillMaxWidth()) { Text("বাতিল") }
        }
    }
    if (addOpen) AddRecordDialog(vm) { addOpen = false }
    decision?.let { d ->
        var note by remember(d) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { decision = null },
            title = { Text(when (d) { "QC" -> "QC যাচাই"; "APPROVE" -> "অনুমোদন"; else -> "বাতিল" }) },
            text = { LabeledField(if (d == "REJECT") "বাতিলের কারণ (আবশ্যক)" else "নোট (ঐচ্ছিক)", note, { note = it }, singleLine = false) },
            confirmButton = {
                TextButton(onClick = {
                    when (d) { "QC" -> vm.qcVerify(note); "APPROVE" -> vm.approve(note); else -> vm.reject(note) }
                    decision = null
                }) { Text("নিশ্চিত") }
            },
            dismissButton = { TextButton(onClick = { decision = null }) { Text("বাতিল") } },
        )
    }
}

@Composable
private fun AddRecordDialog(vm: TrialDetailViewModel, onDismiss: () -> Unit) {
    var group by remember { mutableStateOf("") }
    var species by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("") }
    var startG by remember { mutableStateOf("") }
    var endG by remember { mutableStateOf("") }
    var feed by remember { mutableStateOf("") }
    var dead by remember { mutableStateOf("") }
    var eggs by remember { mutableStateOf("") }
    var milk by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("গ্রুপের ফল") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LabeledField("গ্রুপের নাম (যেমন নতুন ফর্মুলা / কন্ট্রোল)", group, { group = it })
                Spacer(Modifier.height(4.dp)); LabeledField("প্রজাতি (ব্রয়লার/লেয়ার/গরু...)", species, { species = it })
                Spacer(Modifier.height(4.dp)); LabeledField("প্রাণীর সংখ্যা", count, { count = it }, numeric = true)
                Spacer(Modifier.height(4.dp)); LabeledField("দিন", days, { days = it }, numeric = true)
                Spacer(Modifier.height(4.dp)); LabeledField("শুরুর ওজন (গ্রাম/প্রাণী)", startG, { startG = it }, numeric = true)
                Spacer(Modifier.height(4.dp)); LabeledField("শেষ ওজন (গ্রাম/প্রাণী)", endG, { endG = it }, numeric = true)
                Spacer(Modifier.height(4.dp)); LabeledField("মোট খাদ্য গ্রহণ (কেজি)", feed, { feed = it }, numeric = true)
                Spacer(Modifier.height(4.dp)); LabeledField("মৃত্যু (ঐচ্ছিক)", dead, { dead = it }, numeric = true)
                Spacer(Modifier.height(4.dp)); LabeledField("মোট ডিম (ঐচ্ছিক)", eggs, { eggs = it }, numeric = true)
                Spacer(Modifier.height(4.dp)); LabeledField("মোট দুধ লিটার (ঐচ্ছিক)", milk, { milk = it }, numeric = true)
                Spacer(Modifier.height(4.dp)); LabeledField("খাদ্যের দাম টাকা/কেজি (ঐচ্ছিক)", cost, { cost = it }, numeric = true)
                Spacer(Modifier.height(4.dp)); LabeledField("নোট", note, { note = it })
            }
        },
        confirmButton = { TextButton(onClick = { vm.addRecord(group, species, count, days, startG, endG, feed, dead, eggs, milk, cost, note, onDismiss) }) { Text("সংরক্ষণ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } },
    )
}

@Composable
fun FeedbackScreen(onBack: () -> Unit, vm: FeedbackViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageDialog(message, onDismiss = vm::clearMessage)
    var addOpen by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf<FieldFeedbackEntity?>(null) }
    ScreenScaffold(title = "মাঠ ফিডব্যাক", onBack = onBack, onAdd = if (vm.canEdit) ({ addOpen = true }) else null) { padding ->
        SearchableList(
            items = items, modifier = Modifier.padding(padding), searchText = { "${it.batchNo} ${it.farmName} ${it.issueType}" },
            emptyText = "কোনো ফিডব্যাক নেই", onClick = { if (vm.canEdit && !it.resolved) resolving = it },
        ) { f ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${f.farmName} · ব্যাচ ${f.batchNo}", style = MaterialTheme.typography.titleSmall)
                Text(if (f.resolved) "সমাধান" else "খোলা", style = MaterialTheme.typography.bodySmall)
            }
            Text("${f.issueType} · গুরুত্ব ${f.severity}/৩ · ${formatDate(f.visitedAt)}", style = MaterialTheme.typography.bodySmall)
            Text(f.description, style = MaterialTheme.typography.bodySmall)
            f.actionTaken?.let { Text("ব্যবস্থা: $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (addOpen) {
        var batch by remember { mutableStateOf("") }
        var farm by remember { mutableStateOf("") }
        var issue by remember { mutableStateOf("") }
        var sev by remember { mutableStateOf(1) }
        var animals by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addOpen = false },
            title = { Text("নতুন ফিডব্যাক") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LabeledField("ব্যাচ নম্বর", batch, { batch = it })
                    Spacer(Modifier.height(4.dp)); LabeledField("খামারের নাম", farm, { farm = it })
                    Spacer(Modifier.height(4.dp)); LabeledField("সমস্যা (বৃদ্ধি কম / ডায়রিয়া...)", issue, { issue = it })
                    Spacer(Modifier.height(4.dp)); DropdownField("গুরুত্ব", listOf(1, 2, 3), sev, { when (it) { 1 -> "১ কম"; 2 -> "২ মাঝারি"; else -> "৩ গুরুতর" } }, { sev = it })
                    Spacer(Modifier.height(4.dp)); LabeledField("আক্রান্ত প্রাণী (ঐচ্ছিক)", animals, { animals = it }, numeric = true)
                    Spacer(Modifier.height(4.dp)); LabeledField("বিবরণ", desc, { desc = it }, singleLine = false)
                }
            },
            confirmButton = { TextButton(onClick = { vm.add(batch, farm, issue, sev, animals, desc) { addOpen = false } }) { Text("সংরক্ষণ") } },
            dismissButton = { TextButton(onClick = { addOpen = false }) { Text("বাতিল") } },
        )
    }
    resolving?.let { f ->
        var action by remember(f) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { resolving = null },
            title = { Text("সমাধান: ${f.farmName}") },
            text = { LabeledField("কী ব্যবস্থা নেওয়া হলো", action, { action = it }, singleLine = false) },
            confirmButton = { TextButton(onClick = { vm.resolve(f.id, action) { resolving = null } }) { Text("সমাধান হয়েছে") } },
            dismissButton = { TextButton(onClick = { resolving = null }) { Text("বাতিল") } },
        )
    }
}
