package com.feederp.ui.hr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feederp.data.hr.AttendanceStatus
import com.feederp.data.hr.EmploymentType
import com.feederp.data.hr.LoanKind
import com.feederp.data.hr.PayBasis
import com.feederp.data.hr.PayrollStatus
import com.feederp.ui.common.ConfirmDialog
import com.feederp.ui.common.KeyValueRow
import com.feederp.ui.common.LabeledField
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.SearchableList
import com.feederp.ui.common.formatDate
import com.feederp.ui.master.DateField
import com.feederp.ui.master.DropdownField
import com.feederp.ui.master.MessageDialog
import com.feederp.ui.master.SectionTitle

// ---------------- কর্মী তালিকা ----------------

@Composable
fun EmployeeListScreen(
    onBack: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit,
    onAttendance: () -> Unit, onPayroll: () -> Unit, onLoans: () -> Unit, onAdjust: () -> Unit, onAdmin: () -> Unit,
    vm: EmployeeListViewModel = hiltViewModel(),
) {
    val list by vm.employees.collectAsStateWithLifecycle()
    ScreenScaffold(title = "কর্মী", onBack = onBack, onAdd = if (vm.canEdit) onNew else null) { padding ->
        Column(Modifier.padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onAttendance) { Text("হাজিরা") }
                TextButton(onClick = onPayroll) { Text("বেতন") }
                TextButton(onClick = onLoans) { Text("অগ্রিম/ঋণ") }
                TextButton(onClick = onAdjust) { Text("বোনাস/জরিমানা") }
                TextButton(onClick = onAdmin) { Text("ছুটি/আরও") }
            }
            SearchableList(
                items = list, searchText = { "${it.empNo} ${it.name} ${it.department} ${it.designation}" },
                emptyText = "এখনও কোনো কর্মী নেই", onClick = { onOpen(it.id) },
            ) { e ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${e.empNo} · ${e.name}", style = MaterialTheme.typography.titleSmall)
                    Text(employeeStatusBn(e.status), style = MaterialTheme.typography.bodySmall)
                }
                Text("${e.department} · ${e.designation} · ${employmentTypeBn(e.employmentType)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ---------------- কর্মী সম্পাদনা + বেতন কাঠামো ----------------

@Composable
fun EmployeeEditScreen(onBack: () -> Unit, vm: EmployeeEditViewModel = hiltViewModel()) {
    val emp by vm.employee.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageDialog(message, onDismiss = vm::clearMessage)

    var name by remember { mutableStateOf("") }
    var dept by remember { mutableStateOf("") }
    var desig by remember { mutableStateOf("") }
    var join by remember { mutableStateOf<Long?>(null) }
    var type by remember { mutableStateOf(EmploymentType.PERMANENT) }
    var phone by remember { mutableStateOf("") }
    var nid by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("CASH") }
    var account by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(emp) {
        val e = emp
        if (e != null && !loaded) {
            name = e.name; dept = e.department; desig = e.designation; join = e.joinDate; type = e.employmentType
            phone = e.phone.orEmpty(); nid = e.nid.orEmpty(); method = e.payMethod; account = e.payAccount.orEmpty(); loaded = true
        }
    }

    var salaryOpen by remember { mutableStateOf(false) }
    var resignOpen by remember { mutableStateOf(false) }

    ScreenScaffold(title = emp?.name ?: "নতুন কর্মী", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            emp?.let { KeyValueRow("কর্মী নং", it.empNo); KeyValueRow("অবস্থা", employeeStatusBn(it.status)) }
            LabeledField("নাম", name, { name = it })
            Spacer(Modifier.height(6.dp)); LabeledField("বিভাগ", dept, { dept = it })
            Spacer(Modifier.height(6.dp)); LabeledField("পদবি", desig, { desig = it })
            Spacer(Modifier.height(6.dp)); DateField("যোগদানের তারিখ", join, { join = it })
            Spacer(Modifier.height(6.dp))
            DropdownField("ধরন", EmploymentType.values().toList(), type, { employmentTypeBn(it) }, { type = it })
            Spacer(Modifier.height(6.dp)); LabeledField("ফোন", phone, { phone = it })
            Spacer(Modifier.height(6.dp)); LabeledField("NID", nid, { nid = it })
            Spacer(Modifier.height(6.dp))
            DropdownField("বেতন পাওয়ার মাধ্যম", listOf("CASH", "BANK", "MOBILE_BANKING"), method, { payMethodBn(it) }, { method = it })
            if (method != "CASH") { Spacer(Modifier.height(6.dp)); LabeledField("অ্যাকাউন্ট নম্বর", account, { account = it }) }
            Spacer(Modifier.height(10.dp))
            if (vm.canEdit) Button(
                onClick = { vm.save(name, dept, desig, join, type, phone, nid, method, account) { if (emp == null) onBack() } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("সংরক্ষণ") }

            if (emp != null && vm.canSalary) {
                SectionTitle("বেতন কাঠামো")
                OutlinedButton(onClick = { salaryOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("নতুন বেতন কাঠামো যোগ করুন") }
            }
            if (emp != null && vm.canEdit && emp?.status == com.feederp.data.hr.EmployeeStatus.ACTIVE) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { resignOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("ছেড়ে গেছেন / চাকরি শেষ") }
            }
        }
    }
    if (salaryOpen) SalaryDialog(onDismiss = { salaryOpen = false }, vm = vm)
    if (resignOpen) ResignDialog(onDismiss = { resignOpen = false }, vm = vm, onDone = onBack)
}

@Composable
private fun SalaryDialog(onDismiss: () -> Unit, vm: EmployeeEditViewModel) {
    var basis by remember { mutableStateOf(PayBasis.MONTHLY) }
    var from by remember { mutableStateOf<Long?>(System.currentTimeMillis()) }
    var basic by remember { mutableStateOf("") }
    var house by remember { mutableStateOf("") }
    var medical by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("") }
    var other by remember { mutableStateOf("") }
    var ot by remember { mutableStateOf("") }
    var daily by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("বেতন কাঠামো (টাকায়)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DropdownField("ভিত্তি", PayBasis.values().toList(), basis, { payBasisBn(it) }, { basis = it })
                Spacer(Modifier.height(6.dp)); DateField("কার্যকর তারিখ", from, { from = it })
                when (basis) {
                    PayBasis.MONTHLY -> {
                        Spacer(Modifier.height(6.dp)); LabeledField("মূল বেতন", basic, { basic = it }, numeric = true)
                        Spacer(Modifier.height(6.dp)); LabeledField("বাসা ভাড়া", house, { house = it }, numeric = true)
                        Spacer(Modifier.height(6.dp)); LabeledField("চিকিৎসা", medical, { medical = it }, numeric = true)
                        Spacer(Modifier.height(6.dp)); LabeledField("যাতায়াত", transport, { transport = it }, numeric = true)
                        Spacer(Modifier.height(6.dp)); LabeledField("অন্যান্য ভাতা", other, { other = it }, numeric = true)
                    }
                    PayBasis.DAILY -> { Spacer(Modifier.height(6.dp)); LabeledField("দৈনিক মজুরি", daily, { daily = it }, numeric = true) }
                    PayBasis.PIECE -> Text("পিস-রেট কর্মীর মজুরি আসে বোনাস/জরিমানা পর্দার 'পিস-কাজ' থেকে।", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(6.dp)); LabeledField("ওভারটাইম রেট/ঘণ্টা (ফাঁকা = ডিফল্ট)", ot, { ot = it }, numeric = true)
            }
        },
        confirmButton = { TextButton(onClick = { vm.saveSalary(basis, from, basic, house, medical, transport, other, ot, daily, onDismiss) }) { Text("সংরক্ষণ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } },
    )
}

@Composable
private fun ResignDialog(onDismiss: () -> Unit, vm: EmployeeEditViewModel, onDone: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    var at by remember { mutableStateOf<Long?>(System.currentTimeMillis()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("চাকরি শেষ") },
        text = {
            Column {
                LabeledField("কারণ", reason, { reason = it })
                Spacer(Modifier.height(6.dp)); DateField("শেষ কর্মদিবস", at, { at = it })
            }
        },
        confirmButton = { TextButton(onClick = { vm.resign(reason, at) { onDismiss(); onDone() } }) { Text("নিশ্চিত") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } },
    )
}

// ---------------- হাজিরা ----------------

@Composable
fun AttendanceScreen(onBack: () -> Unit, vm: AttendanceViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageDialog(message, onDismiss = vm::clearMessage)
    val ld = java.time.LocalDate.of(day / 10000, day / 100 % 100, day % 100)
    var dayMs by remember(day) { mutableStateOf<Long?>(ld.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()) }
    var editing by remember { mutableStateOf<AttendanceRow?>(null) }

    ScreenScaffold(title = "হাজিরা", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(12.dp)) {
            DateField("তারিখ", dayMs, { it?.let { ms -> dayMs = ms; vm.setDay(ms) } })
            Text("সাপ্তাহিক ছুটি শুক্রবার ধরা হয়। হাজিরা না তুললে মাসিক বেতনভোগী উপস্থিত ধরা হয়, দৈনিক/পিস কর্মী নয়।", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                items(rows) { r ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("${r.employee.empNo} · ${r.employee.name}", style = MaterialTheme.typography.titleSmall)
                                val rec = r.record
                                Text(
                                    if (rec == null) "তোলা হয়নি" else attendanceBn(rec.status) +
                                        (if (rec.lateMinutes > 0) " · দেরি ${rec.lateMinutes} মি." else "") +
                                        (if (rec.overtimeMinutes > 0) " · ওটি ${rec.overtimeMinutes} মি." else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (vm.canEdit) TextButton(onClick = { editing = r }) { Text("বদলান") }
                        }
                    }
                }
            }
        }
    }
    editing?.let { r ->
        var status by remember(r) { mutableStateOf(r.record?.status ?: AttendanceStatus.PRESENT) }
        var late by remember(r) { mutableStateOf((r.record?.lateMinutes ?: 0).toString()) }
        var ot by remember(r) { mutableStateOf((r.record?.overtimeMinutes ?: 0).toString()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(r.employee.name) },
            text = {
                Column {
                    DropdownField("অবস্থা", AttendanceStatus.values().toList(), status, { attendanceBn(it) }, { status = it })
                    Spacer(Modifier.height(6.dp)); LabeledField("দেরি (মিনিট)", late, { late = it }, numeric = true)
                    Spacer(Modifier.height(6.dp)); LabeledField("ওভারটাইম (মিনিট)", ot, { ot = it }, numeric = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.mark(r.employee.id, status, late, ot); editing = null }) { Text("সংরক্ষণ") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("বাতিল") } },
        )
    }
}

// ---------------- বেতন রান তালিকা ----------------

@Composable
fun PayrollListScreen(onBack: () -> Unit, onOpen: (String) -> Unit, vm: PayrollListViewModel = hiltViewModel()) {
    val runs by vm.runs.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageDialog(message, onDismiss = vm::clearMessage)
    var month by remember { mutableStateOf(currentMonth()) }
    ScreenScaffold(title = "বেতন", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(12.dp)) {
            if (vm.canGenerate) {
                DropdownField("মাস", monthOptions(), month, { monthLabel(it) }, { month = it })
                Spacer(Modifier.height(6.dp))
                Button(onClick = { vm.generate(month) { id -> onOpen(id) } }, modifier = Modifier.fillMaxWidth()) { Text("এই মাসের বেতন হিসাব করুন (খসড়া)") }
                Spacer(Modifier.height(8.dp))
            }
            if (runs.isEmpty()) Text("এখনও কোনো বেতন রান নেই", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(runs) { r ->
                    Card(Modifier.fillMaxWidth().clickableCard { onOpen(r.id) }) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(monthLabel(r.month), style = MaterialTheme.typography.titleSmall)
                                Text(payrollStatusBn(r.status), style = MaterialTheme.typography.bodySmall)
                            }
                            Text("নিট ৳ ${takaText(r.totalNetPaisa)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.clickableCard(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))

// ---------------- বেতন রান বিস্তারিত ----------------

@Composable
fun PayrollRunScreen(onBack: () -> Unit, vm: PayrollRunViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageDialog(message, onDismiss = vm::clearMessage)
    var confirmApprove by remember { mutableStateOf(false) }
    var confirmPay by remember { mutableStateOf(false) }
    val run = st.run
    ScreenScaffold(title = run?.let { "বেতন ${monthLabel(it.month)}" } ?: "বেতন", onBack = onBack) { padding ->
        if (run == null) return@ScreenScaffold
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            KeyValueRow("অবস্থা", payrollStatusBn(run.status))
            KeyValueRow("মোট (Gross)", "৳ ${takaText(run.totalGrossPaisa)}")
            KeyValueRow("মোট কর্তন", "৳ ${takaText(run.totalDeductionPaisa)}")
            KeyValueRow("মোট নিট", "৳ ${takaText(run.totalNetPaisa)}")
            Spacer(Modifier.height(6.dp))
            when (run.status) {
                PayrollStatus.DRAFT -> {
                    if (vm.canPay) OutlinedButton(onClick = vm::regenerate, modifier = Modifier.fillMaxWidth()) { Text("আবার হিসাব করুন") }
                    if (vm.canApprove) Button(onClick = { confirmApprove = true }, modifier = Modifier.fillMaxWidth()) { Text("অনুমোদন (লেজারে পোস্ট হবে)") }
                    else Text("অনুমোদন শুধু অনুমোদনের অনুমতিসহ ব্যবহারকারী (যেমন মালিক) দিতে পারেন।", style = MaterialTheme.typography.bodySmall)
                }
                PayrollStatus.APPROVED -> if (vm.canPay) Button(onClick = { confirmPay = true }, modifier = Modifier.fillMaxWidth()) { Text("বেতন পরিশোধ করুন") }
                PayrollStatus.PAID -> run.paidAt?.let { KeyValueRow("পরিশোধ", formatDate(it)) }
            }
            SectionTitle("পে-স্লিপ")
            st.slips.forEach { r ->
                val s = r.slip
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${r.empNo} · ${r.name}", style = MaterialTheme.typography.titleSmall)
                            Text("৳ ${takaText(s.netPaisa)}", style = MaterialTheme.typography.titleSmall)
                        }
                        Text("${payBasisBn(s.payBasis)} · উপস্থিত ${s.presentDays} · ছুটি ${s.paidLeaveDays} · অনুপস্থিত ${s.absentDays}", style = MaterialTheme.typography.bodySmall)
                        Text("মোট ${takaText(s.grossPaisa)} (ওটি ${takaText(s.overtimePaisa)}, বোনাস ${takaText(s.bonusPaisa)}, পিস ${takaText(s.pieceWagePaisa)})", style = MaterialTheme.typography.bodySmall)
                        if (s.totalDeductionPaisa > 0) Text(
                            "কর্তন ${takaText(s.totalDeductionPaisa)} (অনুপস্থিত ${takaText(s.absentDeductionPaisa)}, জরিমানা ${takaText(s.fineDeductionPaisa)}, ঋণ ${takaText(s.loanDeductionPaisa)}, অগ্রিম ${takaText(s.advanceDeductionPaisa)})",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
    if (confirmApprove) ConfirmDialog("অনুমোদন", "অনুমোদনের পর এই মাসের বেতন আর বদলানো যাবে না এবং লেজারে খরচ পোস্ট হবে। নিশ্চিত?", { confirmApprove = false; vm.approve() }, { confirmApprove = false })
    if (confirmPay) ConfirmDialog("পরিশোধ", "সব কর্মীর নিট বেতন পরিশোধ হিসেবে লেজারে পোস্ট হবে। নিশ্চিত?", { confirmPay = false; vm.pay() }, { confirmPay = false })
}

// ---------------- অগ্রিম / ঋণ ----------------

@Composable
fun LoansScreen(onBack: () -> Unit, vm: LoansViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val emps by vm.activeEmployees.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageDialog(message, onDismiss = vm::clearMessage)
    var open by remember { mutableStateOf(false) }
    ScreenScaffold(title = "অগ্রিম / ঋণ", onBack = onBack, onAdd = if (vm.canIssue) ({ open = true }) else null) { padding ->
        SearchableList(
            items = rows, modifier = Modifier.padding(padding), searchText = { it.name }, emptyText = "কোনো অগ্রিম/ঋণ নেই",
        ) { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${r.name} · ${loanKindBn(r.loan.kind)}", style = MaterialTheme.typography.titleSmall)
                Text(if (r.loan.status == com.feederp.data.hr.LoanStatus.CLEARED) "শোধ" else "চলমান", style = MaterialTheme.typography.bodySmall)
            }
            Text("মূল ৳ ${takaText(r.loan.principalPaisa)} · কিস্তি ৳ ${takaText(r.loan.installmentPaisa)} · বাকি ৳ ${takaText(r.balancePaisa)}", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (open) {
        var empId by remember { mutableStateOf<String?>(null) }
        var kind by remember { mutableStateOf(LoanKind.ADVANCE) }
        var amount by remember { mutableStateOf("") }
        var inst by remember { mutableStateOf("") }
        var method by remember { mutableStateOf("CASH") }
        var note by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("অগ্রিম/ঋণ প্রদান") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DropdownField("কর্মী", emps.map { it.id }, empId, { id -> emps.firstOrNull { it.id == id }?.name ?: id }, { empId = it })
                    Spacer(Modifier.height(6.dp)); DropdownField("ধরন", LoanKind.values().toList(), kind, { loanKindBn(it) }, { kind = it })
                    Spacer(Modifier.height(6.dp)); LabeledField("অঙ্ক (টাকা)", amount, { amount = it }, numeric = true)
                    Spacer(Modifier.height(6.dp)); LabeledField("মাসিক কিস্তি (টাকা)", inst, { inst = it }, numeric = true)
                    Spacer(Modifier.height(6.dp)); DropdownField("কীভাবে দেওয়া হলো", listOf("CASH", "BANK", "MOBILE_BANKING"), method, { payMethodBn(it) }, { method = it })
                    Spacer(Modifier.height(6.dp)); LabeledField("নোট", note, { note = it })
                }
            },
            confirmButton = { TextButton(onClick = { vm.issue(empId, kind, amount, inst, method, note) { open = false } }) { Text("প্রদান") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("বাতিল") } },
        )
    }
}

// ---------------- বোনাস / জরিমানা / পিস-কাজ ----------------

@Composable
fun AdjustmentsScreen(onBack: () -> Unit, vm: AdjustmentsViewModel = hiltViewModel()) {
    val month by vm.month.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val emps by vm.employees.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageDialog(message, onDismiss = vm::clearMessage)
    var mode by remember { mutableStateOf<String?>(null) }   // BONUS | FINE | PIECE
    var empId by remember { mutableStateOf<String?>(null) }
    var text1 by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("কেজি") }
    var dayMs by remember { mutableStateOf<Long?>(System.currentTimeMillis()) }
    fun reset() { mode = null; empId = null; text1 = ""; amount = ""; qty = "" }

    ScreenScaffold(title = "বোনাস / জরিমানা / পিস-কাজ", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            DropdownField("বেতন মাস", monthOptions(), month, { monthLabel(it) }, { vm.setMonth(it) })
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (vm.canSalary) { OutlinedButton(onClick = { mode = "BONUS" }) { Text("+ বোনাস") }; OutlinedButton(onClick = { mode = "FINE" }) { Text("+ জরিমানা") } }
                if (vm.canHr) OutlinedButton(onClick = { mode = "PIECE" }) { Text("+ পিস-কাজ") }
            }
            SectionTitle("এই মাসের বোনাস ও জরিমানা")
            if (rows.isEmpty()) Text("কিছু নেই", style = MaterialTheme.typography.bodySmall)
            rows.forEach { r ->
                Text("${r.label} · ${r.name} · ${r.title} · ৳ ${takaText(r.amountPaisa)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
            }
            Text("পিস-কাজ যোগ হলে সেই মাসের বেতন রান আবার হিসাব করুন। অনুমোদিত মাসে কিছু বদলানো যায় না।", style = MaterialTheme.typography.bodySmall)
        }
    }
    mode?.let { m ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { reset() },
            title = { Text(when (m) { "BONUS" -> "বোনাস"; "FINE" -> "জরিমানা"; else -> "পিস-কাজ" }) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DropdownField("কর্মী", emps.map { it.id }, empId, { id -> emps.firstOrNull { it.id == id }?.name ?: id }, { empId = it })
                    if (m == "PIECE") {
                        Spacer(Modifier.height(6.dp)); DateField("তারিখ", dayMs, { dayMs = it })
                        Spacer(Modifier.height(6.dp)); LabeledField("বিভাগ/কাজ", text1, { text1 = it })
                        Spacer(Modifier.height(6.dp)); LabeledField("পরিমাণ", qty, { qty = it }, numeric = true)
                        Spacer(Modifier.height(6.dp)); LabeledField("একক (কেজি/ব্যাগ)", unit, { unit = it })
                        Spacer(Modifier.height(6.dp)); LabeledField("প্রতি এককের রেট (টাকা)", amount, { amount = it }, numeric = true)
                    } else {
                        Spacer(Modifier.height(6.dp)); LabeledField(if (m == "BONUS") "বোনাসের নাম" else "কারণ", text1, { text1 = it })
                        Spacer(Modifier.height(6.dp)); LabeledField("অঙ্ক (টাকা)", amount, { amount = it }, numeric = true)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (m) {
                        "BONUS" -> vm.addBonus(empId, text1, amount) { reset() }
                        "FINE" -> vm.addFine(empId, text1, amount) { reset() }
                        else -> vm.addPiece(empId, dayMs, text1, qty, amount, unit) { reset() }
                    }
                }) { Text("সংরক্ষণ") }
            },
            dismissButton = { TextButton(onClick = { reset() }) { Text("বাতিল") } },
        )
    }
}
