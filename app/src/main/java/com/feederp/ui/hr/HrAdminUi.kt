package com.feederp.ui.hr

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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.lifecycle.viewModelScope
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.data.hr.EmployeeStatus
import com.feederp.data.hr.HrEmployeeDocEntity
import com.feederp.data.hr.HrEmployeeEntity
import com.feederp.data.hr.HrHolidayEntity
import com.feederp.data.hr.HrLeaveRequestEntity
import com.feederp.data.hr.HrLeaveTypeEntity
import com.feederp.data.hr.HrTrainingEntity
import com.feederp.data.hr.LeaveStatus
import com.feederp.hr.HrAdminRepository
import com.feederp.hr.HrRepository
import com.feederp.hr.LeaveBalanceView
import com.feederp.hr.LeaveCalc
import com.feederp.ui.common.LabeledField
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.formatDate
import com.feederp.ui.master.BaseViewModel
import com.feederp.ui.master.DateField
import com.feederp.ui.master.DropdownField
import com.feederp.ui.master.MessageDialog
import com.feederp.ui.master.SectionTitle
import com.feederp.ui.master.parseNum
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

fun leaveStatusBn(s: LeaveStatus) = when (s) {
    LeaveStatus.PENDING -> "অপেক্ষমান"; LeaveStatus.APPROVED -> "অনুমোদিত"; LeaveStatus.REJECTED -> "প্রত্যাখ্যাত"; LeaveStatus.CANCELLED -> "বাতিল"
}

fun dayKeyText(k: Int): String = "${k % 100}/${k / 100 % 100}/${k / 10000}"

@HiltViewModel
class HrAdminViewModel @Inject constructor(
    private val repo: HrAdminRepository,
    hr: HrRepository,
    private val session: SessionManager,
) : BaseViewModel() {
    val canHr: Boolean get() = session.can(Permission.HR)
    val canApprove: Boolean get() = session.can(Permission.APPROVE)

    val employees: StateFlow<List<HrEmployeeEntity>> = hr.observeEmployees().map { l -> l.filter { it.status == EmployeeStatus.ACTIVE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val types: StateFlow<List<HrLeaveTypeEntity>> = repo.observeTypes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val requests: StateFlow<List<HrLeaveRequestEntity>> = repo.observeRequests().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val holidays: StateFlow<List<HrHolidayEntity>> = repo.observeHolidays().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val trainings: StateFlow<List<HrTrainingEntity>> = repo.observeTrainings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _docEmployee = MutableStateFlow<String?>(null)
    val docEmployee: StateFlow<String?> = _docEmployee.asStateFlow()
    private val _docs = MutableStateFlow<List<HrEmployeeDocEntity>>(emptyList())
    val docs: StateFlow<List<HrEmployeeDocEntity>> = _docs.asStateFlow()
    private val _alerts = MutableStateFlow<List<String>>(emptyList())
    val alerts: StateFlow<List<String>> = _alerts.asStateFlow()
    private val _balances = MutableStateFlow<List<LeaveBalanceView>>(emptyList())
    val balances: StateFlow<List<LeaveBalanceView>> = _balances.asStateFlow()

    init {
        if (canHr) launchSafe { repo.seedDefaults() }
        refreshAlerts()
    }

    private fun refreshAlerts() = launchSafe {
        val until = System.currentTimeMillis() + 30L * 86_400_000L
        val names = employees.value.associate { it.id to it.name }
        val docs = repo.expiringDocs(until).map { "কাগজ: ${it.title} — ${names[it.employeeId] ?: "?"} (মেয়াদ ${formatDate(it.expiryAt ?: 0)})" }
        val certs = repo.expiringCertificates(until).map { "সনদ — ${names[it.employeeId] ?: "?"} (মেয়াদ ${formatDate(it.certificateExpiryAt ?: 0)})" }
        _alerts.value = docs + certs
    }

    fun selectDocEmployee(id: String) {
        _docEmployee.value = id
        viewModelScope.launch { repo.observeDocs(id).collect { _docs.value = it } }
    }

    fun loadBalances(employeeId: String) = launchSafe {
        _balances.value = repo.balances(employeeId, java.time.LocalDate.now().year)
    }

    fun ensureBalances() = launchSafe {
        val n = repo.ensureYearBalances(java.time.LocalDate.now().year)
        showMessage("$n টি প্রাপ্য ছুটি তৈরি হয়েছে (আগেরগুলো অপরিবর্তিত)।")
    }

    fun requestLeave(empId: String?, typeId: String?, from: Long?, to: Long?, reason: String, onDone: () -> Unit) = launchSafe(onDone) {
        repo.requestLeave(
            empId ?: throw IllegalArgumentException("কর্মী বাছাই করুন"), typeId ?: throw IllegalArgumentException("ছুটির ধরন বাছাই করুন"),
            dayKeyOf(from ?: throw IllegalArgumentException("শুরুর তারিখ দিন")), dayKeyOf(to ?: from), reason,
        )
    }

    fun decide(id: String, approve: Boolean, note: String) = launchSafe { repo.decide(id, approve, note) }
    fun cancel(id: String) = launchSafe { repo.cancelPending(id) }

    fun addHoliday(from: Long?, name: String, onDone: () -> Unit) = launchSafe(onDone) {
        repo.saveHoliday(dayKeyOf(from ?: throw IllegalArgumentException("তারিখ দিন")), name)
    }
    fun deleteHoliday(dayKey: Int) = launchSafe { repo.deleteHoliday(dayKey) }

    fun addTraining(title: String, kind: String, at: Long?, hours: String, trainer: String, onDone: () -> Unit) = launchSafe(onDone) {
        repo.saveTraining(title, kind, at ?: System.currentTimeMillis(), if (hours.isBlank()) 0.0 else parseNum(hours) ?: throw IllegalArgumentException("ঘণ্টা ঠিক নয়"), trainer, null)
    }
    fun addAttendee(trainingId: String, empId: String?, expiry: Long?, onDone: () -> Unit) = launchSafe(onDone) {
        repo.addAttendee(trainingId, empId ?: throw IllegalArgumentException("কর্মী বাছাই করুন"), expiry)
        refreshAlerts()
    }

    fun addDoc(title: String, note: String, expiry: Long?, onDone: () -> Unit) = launchSafe(onDone) {
        repo.saveDoc(_docEmployee.value ?: throw IllegalArgumentException("কর্মী বাছাই করুন"), title, note, expiry)
        refreshAlerts()
    }
}

@Composable
fun HrAdminScreen(onBack: () -> Unit, vm: HrAdminViewModel = hiltViewModel()) {
    val message by vm.message.collectAsStateWithLifecycle()
    MessageDialog(message, onDismiss = vm::clearMessage)
    var tab by remember { mutableStateOf(0) }
    val titles = listOf("ছুটি", "ছুটির দিন", "প্রশিক্ষণ", "কাগজপত্র")
    ScreenScaffold(title = "এইচআর প্রশাসন", onBack = onBack) { padding ->
        Column(Modifier.padding(padding)) {
            ScrollableTabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                when (tab) {
                    0 -> LeaveTab(vm)
                    1 -> HolidayTab(vm)
                    2 -> TrainingTab(vm)
                    else -> DocsTab(vm)
                }
            }
        }
    }
}

@Composable
private fun LeaveTab(vm: HrAdminViewModel) {
    val emps by vm.employees.collectAsStateWithLifecycle()
    val types by vm.types.collectAsStateWithLifecycle()
    val reqs by vm.requests.collectAsStateWithLifecycle()
    val balances by vm.balances.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var deciding by remember { mutableStateOf<Pair<HrLeaveRequestEntity, Boolean>?>(null) }
    var balEmp by remember { mutableStateOf<String?>(null) }
    Text("সাপ্তাহিক ছুটি শুক্রবার; সরকারি ছুটি 'ছুটির দিন' ট্যাবে দিন। অনুমোদনে হাজিরা নিজে বসে যায়।", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(6.dp))
    if (vm.canHr) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { open = true }) { Text("+ আবেদন") }
            OutlinedButton(onClick = vm::ensureBalances) { Text("এই বছরের প্রাপ্য তৈরি") }
        }
    }
    SectionTitle("প্রাপ্য ছুটি দেখুন")
    DropdownField("কর্মী", emps.map { it.id }, balEmp, { id -> emps.firstOrNull { it.id == id }?.name ?: id }, { balEmp = it; vm.loadBalances(it) })
    if (balEmp != null && balances.isEmpty()) Text("এই বছরের প্রাপ্য ঠিক করা নেই", style = MaterialTheme.typography.bodySmall)
    balances.forEach { b -> Text("${b.type.name}: প্রাপ্য ${b.entitledDays}, নেওয়া/অপেক্ষমান ${b.committedDays}, বাকি ${b.remainingDays}", style = MaterialTheme.typography.bodyMedium) }
    SectionTitle("আবেদন")
    if (reqs.isEmpty()) Text("কোনো আবেদন নেই", style = MaterialTheme.typography.bodySmall)
    reqs.forEach { r ->
        Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text("${emps.firstOrNull { it.id == r.employeeId }?.name ?: "?"} · ${types.firstOrNull { it.id == r.leaveTypeId }?.name ?: "?"}", style = MaterialTheme.typography.titleSmall)
                Text("${dayKeyText(r.fromDay)} – ${dayKeyText(r.toDay)} · ${r.days} কার্যদিবস · ${leaveStatusBn(r.status)}", style = MaterialTheme.typography.bodySmall)
                r.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                r.decisionNote?.let { Text("সিদ্ধান্তের নোট: $it", style = MaterialTheme.typography.bodySmall) }
                if (r.status == LeaveStatus.PENDING) Row {
                    if (vm.canApprove) { TextButton(onClick = { deciding = r to true }) { Text("অনুমোদন") }; TextButton(onClick = { deciding = r to false }) { Text("প্রত্যাখ্যান") } }
                    if (vm.canHr) TextButton(onClick = { vm.cancel(r.id) }) { Text("বাতিল") }
                }
            }
        }
    }
    if (open) {
        var emp by remember { mutableStateOf<String?>(null) }
        var type by remember { mutableStateOf<String?>(null) }
        var from by remember { mutableStateOf<Long?>(System.currentTimeMillis()) }
        var to by remember { mutableStateOf<Long?>(System.currentTimeMillis()) }
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { open = false }, title = { Text("ছুটির আবেদন") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DropdownField("কর্মী", emps.map { it.id }, emp, { id -> emps.firstOrNull { it.id == id }?.name ?: id }, { emp = it })
                    Spacer(Modifier.height(6.dp)); DropdownField("ধরন", types.map { it.id }, type, { id -> types.firstOrNull { it.id == id }?.name ?: id }, { type = it })
                    Spacer(Modifier.height(6.dp)); DateField("শুরু", from, { from = it })
                    Spacer(Modifier.height(6.dp)); DateField("শেষ", to, { to = it })
                    Spacer(Modifier.height(6.dp)); LabeledField("কারণ", reason, { reason = it })
                }
            },
            confirmButton = { TextButton(onClick = { vm.requestLeave(emp, type, from, to, reason) { open = false } }) { Text("জমা") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("বাতিল") } },
        )
    }
    deciding?.let { (r, approve) ->
        var note by remember(r) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { deciding = null }, title = { Text(if (approve) "অনুমোদন" else "প্রত্যাখ্যান") },
            text = { LabeledField(if (approve) "নোট (ঐচ্ছিক)" else "কারণ (আবশ্যক)", note, { note = it }) },
            confirmButton = { TextButton(onClick = { vm.decide(r.id, approve, note); deciding = null }) { Text("নিশ্চিত") } },
            dismissButton = { TextButton(onClick = { deciding = null }) { Text("বাতিল") } },
        )
    }
}

@Composable
private fun HolidayTab(vm: HrAdminViewModel) {
    val list by vm.holidays.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    if (vm.canHr) Button(onClick = { open = true }) { Text("+ ছুটির দিন") }
    Spacer(Modifier.height(6.dp))
    if (list.isEmpty()) Text("কোনো সরকারি/প্রতিষ্ঠান ছুটি যোগ করা নেই", style = MaterialTheme.typography.bodySmall)
    list.forEach { h ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${dayKeyText(h.dayKey)} — ${h.name}", style = MaterialTheme.typography.bodyMedium)
            if (vm.canHr) TextButton(onClick = { vm.deleteHoliday(h.dayKey) }) { Text("মুছুন") }
        }
    }
    if (open) {
        var at by remember { mutableStateOf<Long?>(System.currentTimeMillis()) }
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { open = false }, title = { Text("ছুটির দিন") },
            text = { Column { DateField("তারিখ", at, { at = it }); Spacer(Modifier.height(6.dp)); LabeledField("নাম", name, { name = it }) } },
            confirmButton = { TextButton(onClick = { vm.addHoliday(at, name) { open = false } }) { Text("সংরক্ষণ") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("বাতিল") } },
        )
    }
}

@Composable
private fun TrainingTab(vm: HrAdminViewModel) {
    val list by vm.trainings.collectAsStateWithLifecycle()
    val emps by vm.employees.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var attendeeFor by remember { mutableStateOf<HrTrainingEntity?>(null) }
    if (alerts.isNotEmpty()) {
        SectionTitle("মেয়াদ শেষ হচ্ছে (৩০ দিনের মধ্যে)")
        alerts.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (vm.canHr) Button(onClick = { open = true }) { Text("+ প্রশিক্ষণ/সেফটি") }
    Spacer(Modifier.height(6.dp))
    list.forEach { t ->
        Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text("${t.title} (${if (t.kind == "SAFETY") "সেফটি" else "প্রশিক্ষণ"})", style = MaterialTheme.typography.titleSmall)
                Text("${formatDate(t.conductedAt)} · ${t.durationHours} ঘণ্টা${t.trainer?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                if (vm.canHr) TextButton(onClick = { attendeeFor = t }) { Text("+ অংশগ্রহণকারী") }
            }
        }
    }
    if (open) {
        var title by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf("TRAINING") }
        var at by remember { mutableStateOf<Long?>(System.currentTimeMillis()) }
        var hours by remember { mutableStateOf("") }
        var trainer by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { open = false }, title = { Text("প্রশিক্ষণ") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LabeledField("শিরোনাম", title, { title = it })
                    Spacer(Modifier.height(6.dp)); DropdownField("ধরন", listOf("TRAINING", "SAFETY"), kind, { if (it == "SAFETY") "সেফটি" else "প্রশিক্ষণ" }, { kind = it })
                    Spacer(Modifier.height(6.dp)); DateField("তারিখ", at, { at = it })
                    Spacer(Modifier.height(6.dp)); LabeledField("ঘণ্টা", hours, { hours = it }, numeric = true)
                    Spacer(Modifier.height(6.dp)); LabeledField("প্রশিক্ষক", trainer, { trainer = it })
                }
            },
            confirmButton = { TextButton(onClick = { vm.addTraining(title, kind, at, hours, trainer) { open = false } }) { Text("সংরক্ষণ") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("বাতিল") } },
        )
    }
    attendeeFor?.let { t ->
        var emp by remember(t) { mutableStateOf<String?>(null) }
        var exp by remember(t) { mutableStateOf<Long?>(null) }
        AlertDialog(
            onDismissRequest = { attendeeFor = null }, title = { Text(t.title) },
            text = {
                Column {
                    DropdownField("কর্মী", emps.map { it.id }, emp, { id -> emps.firstOrNull { it.id == id }?.name ?: id }, { emp = it })
                    Spacer(Modifier.height(6.dp)); DateField("সনদের মেয়াদ (ঐচ্ছিক)", exp, { exp = it }, clearable = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.addAttendee(t.id, emp, exp) { attendeeFor = null } }) { Text("যোগ") } },
            dismissButton = { TextButton(onClick = { attendeeFor = null }) { Text("বন্ধ") } },
        )
    }
}

@Composable
private fun DocsTab(vm: HrAdminViewModel) {
    val emps by vm.employees.collectAsStateWithLifecycle()
    val sel by vm.docEmployee.collectAsStateWithLifecycle()
    val docs by vm.docs.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    if (alerts.isNotEmpty()) {
        SectionTitle("মেয়াদ শেষ হচ্ছে (৩০ দিনের মধ্যে)")
        alerts.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    DropdownField("কর্মী", emps.map { it.id }, sel, { id -> emps.firstOrNull { it.id == id }?.name ?: id }, { vm.selectDocEmployee(it) })
    if (sel != null && vm.canHr) { Spacer(Modifier.height(6.dp)); Button(onClick = { open = true }) { Text("+ কাগজ") } }
    docs.forEach { d ->
        Text("${d.title}${d.expiryAt?.let { " — মেয়াদ ${formatDate(it)}" } ?: ""}${d.filePath?.let { " ($it)" } ?: ""}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
    }
    if (open) {
        var title by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        var exp by remember { mutableStateOf<Long?>(null) }
        AlertDialog(
            onDismissRequest = { open = false }, title = { Text("কাগজ") },
            text = {
                Column {
                    LabeledField("কাগজের নাম (NID/লাইসেন্স/চুক্তি...)", title, { title = it })
                    Spacer(Modifier.height(6.dp)); LabeledField("কোথায় রাখা/নোট", note, { note = it })
                    Spacer(Modifier.height(6.dp)); DateField("মেয়াদ (ঐচ্ছিক)", exp, { exp = it }, clearable = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.addDoc(title, note, exp) { open = false } }) { Text("সংরক্ষণ") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("বাতিল") } },
        )
    }
}
