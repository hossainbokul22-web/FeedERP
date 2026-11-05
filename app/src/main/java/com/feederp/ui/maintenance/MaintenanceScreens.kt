package com.feederp.ui.maintenance

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.feederp.ui.common.Text
import androidx.compose.material3.TextButton
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
import com.feederp.data.maintenance.ApprovalStatus
import com.feederp.data.maintenance.MachineEntity
import com.feederp.data.maintenance.MachineStatus
import com.feederp.data.maintenance.MachineType
import com.feederp.data.maintenance.WorkOrderPriority
import com.feederp.data.maintenance.WorkOrderStatus
import com.feederp.ui.common.KeyValueRow
import com.feederp.ui.common.LabeledField
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.SearchableList
import com.feederp.ui.common.formatDate
import com.feederp.ui.master.DropdownField
import com.feederp.ui.master.MessageDialog
import com.feederp.ui.master.SectionTitle
import com.feederp.ui.master.SwitchRow

@Composable
fun MachineListScreen(onBack: () -> Unit, vm: MachineListViewModel = hiltViewModel()) {
    val machines by vm.machines.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<MachineEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var scheduleFor by remember { mutableStateOf<MachineEntity?>(null) }
    MessageDialog(message, onDismiss = vm::clearMessage)

    ScreenScaffold(title = "মেশিন", onBack = onBack, onAdd = if (vm.canManage) ({ adding = true }) else null) { padding ->
        SearchableList(
            items = machines, modifier = Modifier.padding(padding),
            searchText = { "${it.code} ${it.name}" }, emptyText = "এখনও কোনো মেশিন নেই",
            onClick = if (vm.canManage) ({ m: MachineEntity -> editing = m }) else null,
        ) { m: MachineEntity ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${m.code} · ${m.name}", style = MaterialTheme.typography.titleSmall)
                Text(machineStatusBn(m.status), style = MaterialTheme.typography.bodySmall)
            }
            Text(machineTypeBn(m.type) + (m.location?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
        }
    }

    if (adding || editing != null) {
        val m = editing
        MachineDialog(
            initial = m, onDismiss = { adding = false; editing = null },
            onSave = { code, name, type, cap, cost, life, loc ->
                vm.save(m?.id, code, name, type, cap, cost, life, loc) { adding = false; editing = null }
            },
            onSchedule = m?.let { x -> { editing = null; scheduleFor = x } },
            onRetire = m?.takeIf { it.status != MachineStatus.RETIRED }?.let { x -> { vm.retire(x.id); editing = null } },
        )
    }
    scheduleFor?.let { m ->
        ScheduleDialog(m.name, onDismiss = { scheduleFor = null }) { title, byHours, interval, checklist ->
            vm.addSchedule(m.id, title, byHours, interval, checklist) { scheduleFor = null }
        }
    }
}

@Composable
private fun MachineDialog(
    initial: MachineEntity?, onDismiss: () -> Unit,
    onSave: (String, String, MachineType, String, String, String, String) -> Unit,
    onSchedule: (() -> Unit)?, onRetire: (() -> Unit)?,
) {
    var code by remember { mutableStateOf(initial?.code.orEmpty()) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var type by remember { mutableStateOf(initial?.type ?: MachineType.OTHER) }
    var cap by remember { mutableStateOf(initial?.capacityKgPerHour?.takeIf { it > 0 }?.toString().orEmpty()) }
    var cost by remember { mutableStateOf(initial?.purchaseCostPaisa?.takeIf { it > 0 }?.let { (it / 100.0).toString() }.orEmpty()) }
    var life by remember { mutableStateOf((initial?.usefulLifeMonths ?: 120).toString()) }
    var loc by remember { mutableStateOf(initial?.location.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "নতুন মেশিন" else "মেশিন সম্পাদনা") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LabeledField("কোড (ইউনিক)", code, { code = it })
                Spacer(Modifier.height(4.dp)); LabeledField("নাম", name, { name = it })
                Spacer(Modifier.height(4.dp))
                DropdownField("ধরন", MachineType.values().toList(), type, { machineTypeBn(it) }, { type = it })
                Spacer(Modifier.height(4.dp)); LabeledField("ক্ষমতা (কেজি/ঘণ্টা)", cap, { cap = it }, numeric = true)
                Spacer(Modifier.height(4.dp)); LabeledField("ক্রয়মূল্য (৳)", cost, { cost = it }, numeric = true)
                Spacer(Modifier.height(4.dp)); LabeledField("আয়ুষ্কাল (মাস)", life, { life = it }, numeric = true)
                Spacer(Modifier.height(4.dp)); LabeledField("অবস্থান", loc, { loc = it })
                onSchedule?.let { TextButton(onClick = it) { Text("+ রক্ষণাবেক্ষণ শিডিউল যোগ করুন") } }
                onRetire?.let { TextButton(onClick = it) { Text("মেশিন অবসর দিন") } }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(code, name, type, cap, cost, life, loc) }) { Text("সংরক্ষণ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } },
    )
}

@Composable
private fun ScheduleDialog(machineName: String, onDismiss: () -> Unit, onSave: (String, Boolean, String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var byHours by remember { mutableStateOf(true) }
    var interval by remember { mutableStateOf("") }
    var checklist by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("শিডিউল: $machineName") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LabeledField("কাজের নাম (যেমন গ্রিজিং)", title, { title = it })
                SwitchRow(if (byHours) "চালু-ঘণ্টা অনুযায়ী" else "দিন অনুযায়ী", byHours, { byHours = it })
                LabeledField(if (byHours) "প্রতি কত ঘণ্টা পরপর" else "প্রতি কত দিন পরপর", interval, { interval = it }, numeric = true)
                Spacer(Modifier.height(4.dp))
                LabeledField("চেকলিস্ট (প্রতি লাইনে একটি)", checklist, { checklist = it }, singleLine = false)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title, byHours, interval, checklist) }) { Text("সংরক্ষণ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } },
    )
}

@Composable
fun WorkOrderListScreen(onBack: () -> Unit, onOpen: (String) -> Unit, vm: WorkOrderListViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val machines by vm.machines.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var reporting by remember { mutableStateOf(false) }
    MessageDialog(message, onDismiss = vm::clearMessage)

    ScreenScaffold(
        title = "ওয়ার্ক অর্ডার", onBack = onBack, onAdd = { reporting = true },
        actions = { if (vm.canManage) TextButton(onClick = vm::generatePm) { Text("PM তৈরি") } },
    ) { padding ->
        SearchableList(
            items = rows, modifier = Modifier.padding(padding),
            searchText = { "${it.wo.woNo} ${it.wo.title} ${it.machineName}" }, emptyText = "কোনো ওয়ার্ক অর্ডার নেই",
            onClick = if (vm.canManage) ({ r: WoRow -> onOpen(r.wo.id) }) else null,
        ) { r: WoRow ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${r.wo.woNo} · ${r.machineName}", style = MaterialTheme.typography.titleSmall)
                Text(woStatusBn(r.wo.status), style = MaterialTheme.typography.bodySmall)
            }
            Text("${woTypeBn(r.wo.type)} · ${priorityBn(r.wo.priority)} · ${r.wo.title}", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (reporting) {
        var machineId by remember { mutableStateOf<String?>(null) }
        var title by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var prio by remember { mutableStateOf(WorkOrderPriority.NORMAL) }
        AlertDialog(
            onDismissRequest = { reporting = false },
            title = { Text("সমস্যা জানান (ব্রেকডাউন)") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DropdownField("মেশিন", machines.map { it.id }, machineId, { id -> machines.firstOrNull { it.id == id }?.name ?: id }, { machineId = it })
                    Spacer(Modifier.height(4.dp)); LabeledField("সমস্যা (সংক্ষেপে)", title, { title = it })
                    Spacer(Modifier.height(4.dp)); LabeledField("বিবরণ", desc, { desc = it }, singleLine = false)
                    Spacer(Modifier.height(4.dp))
                    DropdownField("গুরুত্ব", WorkOrderPriority.values().toList(), prio, { priorityBn(it) }, { prio = it })
                }
            },
            confirmButton = { TextButton(onClick = { vm.report(machineId, title, desc, prio) { reporting = false } }) { Text("জমা দিন") } },
            dismissButton = { TextButton(onClick = { reporting = false }) { Text("বাতিল") } },
        )
    }
}

@Composable
fun WorkOrderDetailScreen(onBack: () -> Unit, vm: WorkOrderDetailViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var completing by remember { mutableStateOf(false) }
    var rejecting by remember { mutableStateOf(false) }
    MessageDialog(message, onDismiss = vm::clearMessage)

    val wo = st.wo
    ScreenScaffold(title = wo?.woNo ?: "ওয়ার্ক অর্ডার", onBack = onBack) { padding ->
        if (wo == null) return@ScreenScaffold
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            KeyValueRow("মেশিন", st.machineName)
            KeyValueRow("ধরন", woTypeBn(wo.type))
            KeyValueRow("গুরুত্ব", priorityBn(wo.priority))
            KeyValueRow("অবস্থা", woStatusBn(wo.status))
            KeyValueRow("জানানো হয়েছে", formatDate(wo.reportedAt))
            Text(wo.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
            wo.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            wo.safetyNote?.let { Text("নিরাপত্তা: $it", color = MaterialTheme.colorScheme.error) }

            if (st.checklist.isNotEmpty()) {
                SectionTitle("চেকলিস্ট")
                st.checklist.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = c.done, onCheckedChange = { vm.toggle(c.id) },
                            enabled = vm.canManage && wo.status != WorkOrderStatus.CLOSED,
                        )
                        Text(c.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (wo.status == WorkOrderStatus.DONE || wo.status == WorkOrderStatus.CLOSED) {
                SectionTitle("কাজের ফল")
                wo.rootCause?.let { KeyValueRow("মূল কারণ", it) }
                wo.actionTaken?.let { KeyValueRow("কী করা হয়েছে", it) }
                KeyValueRow("খরচ", "৳ ${String.format(java.util.Locale.US, "%.2f", wo.costPaisa / 100.0)}")
                if (wo.approvalStatus != ApprovalStatus.NOT_REQUIRED) {
                    KeyValueRow(
                        "অনুমোদন",
                        when (wo.approvalStatus) {
                            ApprovalStatus.PENDING -> "অনুমোদনের অপেক্ষায়"; ApprovalStatus.APPROVED -> "অনুমোদিত"
                            ApprovalStatus.REJECTED -> "প্রত্যাখ্যাত"; else -> ""
                        },
                    )
                }
            }

            if (vm.canManage) {
                Spacer(Modifier.height(12.dp))
                when (wo.status) {
                    WorkOrderStatus.OPEN -> Button(onClick = { vm.move(WorkOrderStatus.IN_PROGRESS) }, modifier = Modifier.fillMaxWidth()) { Text("কাজ শুরু") }
                    WorkOrderStatus.IN_PROGRESS -> {
                        Button(onClick = { completing = true }, modifier = Modifier.fillMaxWidth()) { Text("কাজ সম্পন্ন করুন") }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { vm.move(WorkOrderStatus.WAITING_PARTS) }, modifier = Modifier.fillMaxWidth()) { Text("যন্ত্রাংশের অপেক্ষায়") }
                    }
                    WorkOrderStatus.WAITING_PARTS -> Button(onClick = { vm.move(WorkOrderStatus.IN_PROGRESS) }, modifier = Modifier.fillMaxWidth()) { Text("কাজ আবার শুরু") }
                    WorkOrderStatus.DONE -> {
                        if (wo.approvalStatus == ApprovalStatus.PENDING) {
                            if (vm.canApprove) {
                                Button(onClick = vm::approve, modifier = Modifier.fillMaxWidth()) { Text("খরচ অনুমোদন") }
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(onClick = { rejecting = true }, modifier = Modifier.fillMaxWidth()) { Text("প্রত্যাখ্যান") }
                            } else Text("খরচ বেশি হওয়ায় ম্যানেজারের অনুমোদন লাগবে।", style = MaterialTheme.typography.bodySmall)
                        } else Button(onClick = vm::close, modifier = Modifier.fillMaxWidth()) { Text("ওয়ার্ক অর্ডার বন্ধ করুন") }
                    }
                    WorkOrderStatus.CLOSED -> Unit
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (completing) {
        var cause by remember { mutableStateOf("") }
        var action by remember { mutableStateOf("") }
        var parts by remember { mutableStateOf("") }
        var labor by remember { mutableStateOf("") }
        var other by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { completing = false },
            title = { Text("কাজ সম্পন্ন") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LabeledField("মূল কারণ", cause, { cause = it })
                    Spacer(Modifier.height(4.dp)); LabeledField("কী করা হয়েছে", action, { action = it })
                    Spacer(Modifier.height(4.dp)); LabeledField("যন্ত্রাংশ খরচ (৳)", parts, { parts = it }, numeric = true)
                    Spacer(Modifier.height(4.dp)); LabeledField("মজুরি (৳)", labor, { labor = it }, numeric = true)
                    Spacer(Modifier.height(4.dp)); LabeledField("অন্যান্য (৳)", other, { other = it }, numeric = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.complete(cause, action, parts, labor, other) { completing = false } }) { Text("সংরক্ষণ") } },
            dismissButton = { TextButton(onClick = { completing = false }) { Text("বাতিল") } },
        )
    }
    if (rejecting) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rejecting = false },
            title = { Text("প্রত্যাখ্যান") },
            text = { LabeledField("কারণ (বাধ্যতামূলক)", reason, { reason = it }) },
            confirmButton = { TextButton(onClick = { vm.reject(reason); rejecting = false }) { Text("নিশ্চিত") } },
            dismissButton = { TextButton(onClick = { rejecting = false }) { Text("থাক") } },
        )
    }
}
