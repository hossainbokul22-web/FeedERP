package com.feederp.ui.distribution

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feederp.data.distribution.CostKinds
import com.feederp.data.distribution.DistStatus
import com.feederp.data.distribution.DriverEntity
import com.feederp.data.distribution.TripEntity
import com.feederp.data.distribution.VehicleEntity
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
fun TripListScreen(onBack: () -> Unit, onNew: () -> Unit, onOpen: (String) -> Unit, onFleet: () -> Unit, vm: TripListViewModel = hiltViewModel()) {
    val trips by vm.trips.collectAsStateWithLifecycle()
    ScreenScaffold(
        title = "ট্রিপ", onBack = onBack, onAdd = if (vm.canManage) onNew else null,
        actions = { TextButton(onClick = onFleet) { Text("গাড়ি/ড্রাইভার") } },
    ) { padding ->
        SearchableList(
            items = trips, modifier = Modifier.padding(padding),
            searchText = { "${it.tripNo} ${it.route}" }, emptyText = "এখনও কোনো ট্রিপ নেই", onClick = { onOpen(it.id) },
        ) { t: TripEntity ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${t.tripNo} · ${t.route}", style = MaterialTheme.typography.titleSmall)
                Text(tripStatusBn(t.status), style = MaterialTheme.typography.bodySmall)
            }
            Text(formatDate(t.plannedDate), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun NewTripScreen(onBack: () -> Unit, onCreated: (String) -> Unit, vm: NewTripViewModel = hiltViewModel()) {
    val vehicles by vm.vehicles.collectAsStateWithLifecycle()
    val drivers by vm.drivers.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var vehicleId by remember { mutableStateOf<String?>(null) }
    var driverId by remember { mutableStateOf<String?>(null) }
    var route by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    MessageDialog(message, onDismiss = vm::clearMessage)

    ScreenScaffold(title = "নতুন ট্রিপ", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            DropdownField("গাড়ি", vehicles.map { it.id }, vehicleId, { id -> vehicles.firstOrNull { it.id == id }?.let { "${it.regNo} (${kgText(it.capacityGrams)} কেজি)" } ?: id }, { vehicleId = it })
            Spacer(Modifier.height(6.dp))
            DropdownField("ড্রাইভার", drivers.map { it.id }, driverId, { id -> drivers.firstOrNull { it.id == id }?.name ?: id }, { driverId = it })
            Spacer(Modifier.height(6.dp))
            LabeledField("রুট / এলাকা", route, { route = it })
            Spacer(Modifier.height(6.dp))
            LabeledField("নোট (ঐচ্ছিক)", note, { note = it })
            SectionTitle("ডেলিভারি নোট বাছাই (প্ল্যান করা, ট্রিপে নেই)")
            if (notes.isEmpty()) Text("এমন কোনো নোট নেই। আগে ডেলিভারি নোট তৈরি করুন।", style = MaterialTheme.typography.bodySmall)
            notes.forEach { o ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = o.note.id in selected, onCheckedChange = { vm.toggle(o.note.id) })
                    Text("${o.note.challanNo} · ${o.partyName}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = { vm.create(vehicleId, driverId, route, note, onCreated) }, modifier = Modifier.fillMaxWidth()) { Text("ট্রিপ তৈরি করুন") }
        }
    }
}

@Composable
fun TripDetailScreen(onBack: () -> Unit, vm: TripDetailViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var startOpen by remember { mutableStateOf(false) }
    var endOpen by remember { mutableStateOf(false) }
    var costOpen by remember { mutableStateOf(false) }
    var deliverStop by remember { mutableStateOf<StopRow?>(null) }
    var failStop by remember { mutableStateOf<StopRow?>(null) }
    MessageDialog(message, onDismiss = vm::clearMessage)

    val trip = st.trip
    ScreenScaffold(title = trip?.tripNo ?: "ট্রিপ", onBack = onBack) { padding ->
        if (trip == null) return@ScreenScaffold
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            KeyValueRow("রুট", trip.route)
            KeyValueRow("গাড়ি", st.vehicle)
            KeyValueRow("ড্রাইভার", st.driver)
            KeyValueRow("অবস্থা", tripStatusBn(trip.status))
            trip.startKm?.let { KeyValueRow("শুরুর কিমি", it.toString()) }
            trip.endKm?.let { KeyValueRow("শেষের কিমি", it.toString()) }

            SectionTitle("স্টপ")
            st.stops.forEach { r ->
                Text("${r.stop.sequence}. ${r.partyName} · ${r.note?.challanNo.orEmpty()} · ${stopStatusBn(r.stop.status)}", style = MaterialTheme.typography.titleSmall)
                r.lines.forEach { l ->
                    Text(
                        "  ${st.itemNames[l.itemId] ?: "?"}: প্ল্যান ${kgText(l.plannedGrams)} · পৌঁছেছে ${kgText(l.deliveredGrams)} কেজি",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                r.stop.failReason?.let { Text("  কারণ: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (vm.canDispatch && trip.status == DistStatus.TRIP_ONROAD && r.stop.status != DistStatus.STOP_DELIVERED) {
                    Row {
                        TextButton(onClick = { deliverStop = r }) { Text("ডেলিভারি নিশ্চিত") }
                        TextButton(onClick = { failStop = r }) { Text("ব্যর্থ") }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            SectionTitle("পরিবহন খরচ")
            if (st.costs.isEmpty()) Text("কোনো খরচ নেই", style = MaterialTheme.typography.bodySmall)
            st.costs.forEach { c ->
                KeyValueRow("${CostKinds.labelBn(c.kind)}${c.litres?.let { " (${it} লি.)" } ?: ""}", "৳ ${String.format(java.util.Locale.US, "%.2f", c.amountPaisa / 100.0)}")
            }
            KeyValueRow("মোট", "৳ ${String.format(java.util.Locale.US, "%.2f", st.costs.sumOf { it.amountPaisa } / 100.0)}")
            if (vm.canManage && trip.status != DistStatus.TRIP_CANCELLED) {
                OutlinedButton(onClick = { costOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("+ খরচ যোগ") }
            }

            Spacer(Modifier.height(12.dp))
            if (trip.status == DistStatus.TRIP_PLANNED && vm.canDispatch) {
                Text("লোড করলে প্রতিটি নোটের মাল FEFO অনুযায়ী QC-পাস লট থেকে স্টক থেকে কমবে।", style = MaterialTheme.typography.bodySmall)
                Button(onClick = vm::load, modifier = Modifier.fillMaxWidth()) { Text("মাল লোড করুন") }
            }
            if (trip.status == DistStatus.TRIP_LOADED && vm.canManage) {
                Button(onClick = { startOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("যাত্রা শুরু") }
            }
            if (trip.status == DistStatus.TRIP_ONROAD && vm.canManage) {
                Button(onClick = { endOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("ট্রিপ শেষ করুন") }
                Text("যে নোটের ডেলিভারি হয়নি, তার মাল স্টকে ফিরে যাবে।", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (startOpen) KmDialog("যাত্রা শুরু", "শুরুর কিমি (ঐচ্ছিক)", { startOpen = false }) { vm.start(it); startOpen = false }
    if (endOpen) KmDialog("ট্রিপ শেষ", "শেষের কিমি (ঐচ্ছিক)", { endOpen = false }) { km -> vm.complete(km) { endOpen = false } }
    if (costOpen) CostDialog({ costOpen = false }) { kind, amount, litres, n -> vm.addCost(kind, amount, litres, n) { costOpen = false } }
    deliverStop?.let { r ->
        var receiver by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        val kgs = remember { mutableStateMapOf<String, String>() }
        AlertDialog(
            onDismissRequest = { deliverStop = null },
            title = { Text("ডেলিভারি: ${r.partyName}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LabeledField("গ্রহীতার নাম", receiver, { receiver = it })
                    Spacer(Modifier.height(4.dp)); LabeledField("ফোন (ঐচ্ছিক)", phone, { phone = it })
                    Text("পুরো মাল পৌঁছালে ঘর ফাঁকা রাখুন; কম হলে কেজি লিখুন।", style = MaterialTheme.typography.bodySmall)
                    r.lines.forEach { l ->
                        Spacer(Modifier.height(4.dp))
                        LabeledField("${st.itemNames[l.itemId] ?: "?"} (লোড ${kgText(l.loadedGrams)})", kgs[l.id].orEmpty(), { kgs[l.id] = it }, numeric = true)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.deliver(r.stop.id, receiver, phone, kgs.toMap()) { deliverStop = null } }) { Text("নিশ্চিত") } },
            dismissButton = { TextButton(onClick = { deliverStop = null }) { Text("বাতিল") } },
        )
    }
    failStop?.let { r ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { failStop = null },
            title = { Text("ব্যর্থ: ${r.partyName}") },
            text = { LabeledField("কারণ (বাধ্যতামূলক)", reason, { reason = it }) },
            confirmButton = { TextButton(onClick = { vm.fail(r.stop.id, reason) { failStop = null } }) { Text("নিশ্চিত") } },
            dismissButton = { TextButton(onClick = { failStop = null }) { Text("থাক") } },
        )
    }
}

@Composable
private fun KmDialog(title: String, label: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var km by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { LabeledField(label, km, { km = it }, numeric = true) },
        confirmButton = { TextButton(onClick = { onOk(km) }) { Text("নিশ্চিত") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } },
    )
}

@Composable
private fun CostDialog(onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var kind by remember { mutableStateOf(CostKinds.FUEL) }
    var amount by remember { mutableStateOf("") }
    var litres by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("পরিবহন খরচ (নগদ)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DropdownField("ধরন", CostKinds.all, kind, { CostKinds.labelBn(it) }, { kind = it })
                Spacer(Modifier.height(4.dp)); LabeledField("অঙ্ক (৳)", amount, { amount = it }, numeric = true)
                if (kind == CostKinds.FUEL) { Spacer(Modifier.height(4.dp)); LabeledField("লিটার (ঐচ্ছিক)", litres, { litres = it }, numeric = true) }
                Spacer(Modifier.height(4.dp)); LabeledField("নোট (ঐচ্ছিক)", note, { note = it })
                Text("সংরক্ষণের পর লেজারে নগদ থেকে খরচ হিসেবে যাবে এবং এই এন্ট্রি আর বদলানো/রিভার্স করা যাবে না।", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(kind, amount, litres, note) }) { Text("সংরক্ষণ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } },
    )
}

@Composable
fun FleetScreen(onBack: () -> Unit, vm: FleetViewModel = hiltViewModel()) {
    val vehicles by vm.vehicles.collectAsStateWithLifecycle()
    val drivers by vm.drivers.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var vDlg by remember { mutableStateOf<VehicleEntity?>(null) }
    var vNew by remember { mutableStateOf(false) }
    var dDlg by remember { mutableStateOf<DriverEntity?>(null) }
    var dNew by remember { mutableStateOf(false) }
    MessageDialog(message, onDismiss = vm::clearMessage)

    ScreenScaffold(title = "গাড়ি ও ড্রাইভার", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            SectionTitle("গাড়ি")
            vehicles.forEach { v ->
                TextButton(onClick = { if (vm.canManage) vDlg = v }) {
                    Text("${v.regNo} · ${v.type} · ${kgText(v.capacityGrams)} কেজি" + (if (v.ownership == DistStatus.HIRED) " · ভাড়া" else "") + (if (!v.active) " · বন্ধ" else ""))
                }
            }
            if (vm.canManage) OutlinedButton(onClick = { vNew = true }) { Text("+ গাড়ি") }
            SectionTitle("ড্রাইভার")
            drivers.forEach { d ->
                TextButton(onClick = { if (vm.canManage) dDlg = d }) { Text("${d.name}" + (d.phone?.let { " · $it" } ?: "") + (if (!d.active) " · বন্ধ" else "")) }
            }
            if (vm.canManage) OutlinedButton(onClick = { dNew = true }) { Text("+ ড্রাইভার") }
        }
    }

    if (vNew || vDlg != null) {
        val v = vDlg
        var reg by remember(v) { mutableStateOf(v?.regNo.orEmpty()) }
        var type by remember(v) { mutableStateOf(v?.type ?: "ট্রাক") }
        var cap by remember(v) { mutableStateOf(v?.capacityGrams?.takeIf { it > 0 }?.let { kgText(it) }.orEmpty()) }
        var hired by remember(v) { mutableStateOf(v?.ownership == DistStatus.HIRED) }
        var owner by remember(v) { mutableStateOf(v?.ownerName.orEmpty()) }
        var active by remember(v) { mutableStateOf(v?.active ?: true) }
        AlertDialog(
            onDismissRequest = { vNew = false; vDlg = null }, title = { Text(if (v == null) "নতুন গাড়ি" else "গাড়ি সম্পাদনা") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LabeledField("নম্বর", reg, { reg = it }); Spacer(Modifier.height(4.dp))
                    LabeledField("ধরন (ট্রাক/পিকআপ/ভ্যান)", type, { type = it }); Spacer(Modifier.height(4.dp))
                    LabeledField("ধারণক্ষমতা (কেজি)", cap, { cap = it }, numeric = true)
                    SwitchRow("ভাড়া গাড়ি", hired, { hired = it })
                    if (hired) LabeledField("মালিকের নাম", owner, { owner = it })
                    SwitchRow("চালু", active, { active = it })
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveVehicle(v?.id, reg, type, cap, hired, owner, active) { vNew = false; vDlg = null } }) { Text("সংরক্ষণ") } },
            dismissButton = { TextButton(onClick = { vNew = false; vDlg = null }) { Text("বাতিল") } },
        )
    }
    if (dNew || dDlg != null) {
        val d = dDlg
        var name by remember(d) { mutableStateOf(d?.name.orEmpty()) }
        var lic by remember(d) { mutableStateOf(d?.licenseNo.orEmpty()) }
        var phone by remember(d) { mutableStateOf(d?.phone.orEmpty()) }
        var active by remember(d) { mutableStateOf(d?.active ?: true) }
        AlertDialog(
            onDismissRequest = { dNew = false; dDlg = null }, title = { Text(if (d == null) "নতুন ড্রাইভার" else "ড্রাইভার সম্পাদনা") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LabeledField("নাম", name, { name = it }); Spacer(Modifier.height(4.dp))
                    LabeledField("লাইসেন্স নম্বর", lic, { lic = it }); Spacer(Modifier.height(4.dp))
                    LabeledField("ফোন", phone, { phone = it })
                    SwitchRow("চালু", active, { active = it })
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveDriver(d?.id, name, lic, phone, active) { dNew = false; dDlg = null } }) { Text("সংরক্ষণ") } },
            dismissButton = { TextButton(onClick = { dNew = false; dDlg = null }) { Text("বাতিল") } },
        )
    }
}
