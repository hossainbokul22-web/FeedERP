package com.feederp.ui.qc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.feederp.inventory.QcStatus
import com.feederp.qc.QcCalc
import com.feederp.qc.QcParameters
import com.feederp.qc.QcRecommendation
import com.feederp.ui.common.KeyValueRow
import com.feederp.ui.common.LabeledField
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.SearchableList
import com.feederp.ui.common.formatDate
import com.feederp.ui.master.DropdownField
import com.feederp.ui.master.MessageDialog
import com.feederp.ui.master.SectionTitle
import com.feederp.ui.master.SwitchRow
import java.util.Locale

@Composable
fun QcQueueScreen(onBack: () -> Unit, onOpen: (String) -> Unit, vm: QcQueueViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val showAll by vm.showAll.collectAsStateWithLifecycle()
    ScreenScaffold(title = "QC (মান নিয়ন্ত্রণ)", onBack = onBack) { padding ->
        Column(Modifier.padding(padding)) {
            SwitchRow("সব লট দেখান (শুধু অপেক্ষমাণ/হোল্ড নয়)", showAll, vm::setShowAll, Modifier.padding(horizontal = 12.dp))
            SearchableList(
                items = rows,
                searchText = { "${it.itemName} ${it.lot.batchNo}" },
                emptyText = if (showAll) "কোনো লট নেই" else "QC-র অপেক্ষায় কোনো লট নেই ✔",
                onClick = { onOpen(it.lot.id) },
            ) { r: QcQueueRow ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(r.itemName, style = MaterialTheme.typography.titleMedium)
                    Text(QcStatus.labelBn(r.lot.qcStatus), style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "লট ${r.lot.batchNo}" + (r.lot.expiryAt?.let { " · মেয়াদ ${formatDate(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun QcLotScreen(onBack: () -> Unit, vm: QcLotViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var decisionNote by remember { mutableStateOf("") }
    MessageDialog(message, onDismiss = vm::clearMessage)

    val lot = st.lot
    ScreenScaffold(title = if (st.itemName.isBlank()) "লট QC" else "QC: ${st.itemName}", onBack = onBack) { padding ->
        if (lot == null) return@ScreenScaffold
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            KeyValueRow("লট / ব্যাচ নম্বর", lot.batchNo)
            KeyValueRow("বর্তমান স্ট্যাটাস", QcStatus.labelBn(lot.qcStatus))
            lot.mfgAt?.let { KeyValueRow("উৎপাদন", formatDate(it)) }
            lot.expiryAt?.let { KeyValueRow("মেয়াদ", formatDate(it)) }

            SectionTitle("টেস্ট ফল (প্রতি প্যারামিটারের সর্বশেষটাই গণ্য)")
            when (st.recommendation) {
                QcRecommendation.NO_TESTS -> Text("এখনও কোনো টেস্ট নেই।", style = MaterialTheme.typography.bodySmall)
                QcRecommendation.ALL_PASS -> Text("সব প্যারামিটার পাস ✔", color = MaterialTheme.colorScheme.primary)
                QcRecommendation.SOME_FAILED -> Text(
                    "ব্যর্থ: " + st.failedParameters.joinToString(", ") { QcParameters.nameBn(it) },
                    color = MaterialTheme.colorScheme.error,
                )
            }
            st.tests.forEach { t ->
                val limits = listOfNotNull(t.specMin?.let { "ন্যূনতম ${num(it)}" }, t.specMax?.let { "সর্বোচ্চ ${num(it)}" })
                Text(
                    (if (t.passed) "✔ " else "✖ ") + "${QcParameters.nameBn(t.parameter)}: ${num(t.value)} ${t.unit.orEmpty()}" +
                        if (limits.isNotEmpty()) " (${limits.joinToString(", ")})" else "",
                    color = if (t.passed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SectionTitle("নতুন টেস্ট এন্ট্রি")
            DropdownField(
                label = "প্যারামিটার",
                options = QcParameters.standard.map { it.key } + QcParameters.OTHER,
                selected = form.param,
                optionLabel = { if (it == QcParameters.OTHER) "অন্যান্য (নিজে লিখুন)" else QcParameters.nameBn(it) },
                onSelect = vm::selectParam,
            )
            if (form.param == QcParameters.OTHER) {
                Spacer(Modifier.height(6.dp))
                LabeledField("প্যারামিটারের নাম", form.customName, vm::onCustomName)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                LabeledField("মান", form.value, vm::onValue, Modifier.weight(2f), numeric = true)
                LabeledField("একক", form.unit, vm::onUnit, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField("সর্বনিম্ন সীমা", form.min, vm::onMin, Modifier.weight(1f), numeric = true)
                LabeledField("সর্বোচ্চ সীমা", form.max, vm::onMax, Modifier.weight(1f), numeric = true)
            }
            Text("কমপক্ষে একটি সীমা দিন। সীমা আইন/স্পেক অনুযায়ী আপনি ঠিক করবেন।", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            LabeledField("নোট (ঐচ্ছিক)", form.note, vm::onNote)
            Spacer(Modifier.height(8.dp))
            Button(onClick = vm::addTest, modifier = Modifier.fillMaxWidth()) { Text("টেস্ট সংরক্ষণ") }

            SectionTitle("সিদ্ধান্ত")
            Text(
                "পাস: সব টেস্ট পাস হলে সরাসরি; ব্যর্থ/টেস্টহীন হলে শুধু ম্যানেজার, কারণসহ। হোল্ড ও বাতিলে কারণ বাধ্যতামূলক।",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            LabeledField("সিদ্ধান্তের কারণ/নোট", decisionNote, { decisionNote = it }, singleLine = false)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.decide(QcCalc.PASSED, decisionNote) { decisionNote = "" } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("পাস করুন") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { vm.decide(QcCalc.HOLD, decisionNote) { decisionNote = "" } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("হোল্ডে রাখুন") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { vm.decide(QcCalc.REJECTED, decisionNote) { decisionNote = "" } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("বাতিল (রিজেক্ট)") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun num(d: Double): String =
    if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e12) d.toLong().toString()
    else String.format(Locale.US, "%.3f", d).trimEnd('0').trimEnd('.')
