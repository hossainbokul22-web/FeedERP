package com.feederp.ui.sync

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feederp.ui.common.KeyValueRow
import com.feederp.ui.common.LabeledField
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.formatDateTime
import com.feederp.ui.master.MessageDialog
import com.feederp.ui.master.SectionTitle

@Composable
fun SyncScreen(onBack: () -> Unit, vm: SyncViewModel = hiltViewModel()) {
    val st by vm.status.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageDialog(message, onDismiss = vm::clearMessage)

    ScreenScaffold(title = "সিঙ্ক (Wi-Fi)", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            SectionTitle("এই ফোন")
            LabeledField("ডিভাইস কোড (প্রতি ফোনে আলাদা: S1, S2, M1…)", ui.deviceCode, vm::onDeviceCode)
            Text(
                "দুই ফোনে একই কোড থাকলে ইনভয়েস নম্বর মিলে যাবে ও সিঙ্কে দ্বন্দ্ব হবে। কোড সংরক্ষণ করুন প্রথম সিঙ্কের আগেই।",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = vm::saveDeviceCode, modifier = Modifier.fillMaxWidth()) { Text("কোড সংরক্ষণ") }

            if (st.isHub) HubSection(st.hubRunning, ui, vm) else SpokeSection(st.paired, st.peerName, ui, vm, st.running)

            SectionTitle("সর্বশেষ অবস্থা")
            if (st.lastSyncAt > 0) KeyValueRow("সর্বশেষ সফল সিঙ্ক", formatDateTime(st.lastSyncAt))
            val r = ui.result ?: st.lastResult
            r?.let {
                Text("পাঠানো ${it.pushedRows} · নেওয়া ${it.pulledRows} · বাদ/অপরিবর্তিত ${it.skipped}", style = MaterialTheme.typography.bodyMedium)
                if (it.conflicts.isNotEmpty()) {
                    Text("বসানো যায়নি (${it.conflicts.size}):", color = MaterialTheme.colorScheme.error)
                    it.conflicts.take(20).forEach { c -> Text("• $c", style = MaterialTheme.typography.bodySmall) }
                    Text(
                        "সাধারণ কারণ: দুই ফোনে একই ইউজারনেম/ইনভয়েস নম্বর আলাদা রো-তে তৈরি হয়েছে। একটি ফোনের ডিভাইস কোড বদলে নতুন নম্বর নিন।",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            st.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!st.isHub && st.paired) {
                SectionTitle("স্বয়ংক্রিয় সিঙ্ক")
                com.feederp.ui.master.SwitchRow("প্রতি ~১৫ মিনিটে ব্যাকগ্রাউন্ডে সিঙ্ক (Wi-Fi-তে)", ui.autoSync, vm::setAutoSync)
                Text("Android দেরি করতে পারে; ব্যাটারি-সেভারে বন্ধ থাকতে পারে। জরুরি হলে 'এখনই সিঙ্ক' চাপুন।", style = MaterialTheme.typography.bodySmall)
            }
            SectionTitle("দ্বন্দ্বের ইতিহাস (এই ফোনে, সর্বশেষ ৫০)")
            if (ui.history.isEmpty()) Text("কোনো দ্বন্দ্ব নেই", style = MaterialTheme.typography.bodySmall)
            ui.history.forEach { (at, msg) -> Text("• ${formatDateTime(at)}: $msg", style = MaterialTheme.typography.bodySmall) }
            if (ui.history.isNotEmpty()) OutlinedButton(onClick = vm::clearHistory, modifier = Modifier.fillMaxWidth()) { Text("ইতিহাস মুছুন") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HubSection(running: Boolean, ui: SyncUiState, vm: SyncViewModel) {
    SectionTitle("হাব (এই ফোন কেন্দ্রীয়)")
    KeyValueRow("সার্ভার", if (running) "চালু ✔" else "বন্ধ")
    KeyValueRow("এই ফোনের IP", ui.localIp?.let { "$it:8765" } ?: "Wi-Fi এ যুক্ত নন")
    KeyValueRow("পেয়ারিং কোড", ui.hubCode)
    Text(
        "অন্য ফোনে এই IP ও কোড দিন। কোড কাউকে বলবেন না। সন্দেহ হলে নতুন কোড নিন — তখন সব ফোন আবার পেয়ার করতে হবে।",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (running) OutlinedButton(onClick = vm::stopHub, modifier = Modifier.weight(1f)) { Text("সার্ভার বন্ধ") }
        else Button(onClick = vm::startHub, modifier = Modifier.weight(1f)) { Text("সার্ভার চালু") }
        OutlinedButton(onClick = vm::newCode, modifier = Modifier.weight(1f)) { Text("নতুন কোড") }
    }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = vm::makeSpoke, modifier = Modifier.fillMaxWidth()) { Text("হাব না থেকে সাধারণ ফোন হই") }
    Text(
        "হাব ফোন Wi-Fi এ ও চার্জারে রাখুন এবং এই অ্যাপের জন্য ব্যাটারি অপ্টিমাইজেশন বন্ধ করুন, নইলে অ্যান্ড্রয়েড সার্ভার বন্ধ করে দিতে পারে।",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SpokeSection(paired: Boolean, peerName: String, ui: SyncUiState, vm: SyncViewModel, running: Boolean) {
    SectionTitle("হাবের সাথে সংযোগ")
    if (paired) {
        KeyValueRow("পেয়ার করা হাব", peerName.ifBlank { ui.hostInput })
        Spacer(Modifier.height(6.dp))
        Button(onClick = vm::syncNow, enabled = !ui.busy && !running, modifier = Modifier.fillMaxWidth()) {
            Text(if (ui.busy || running) "সিঙ্ক চলছে…" else "এখনই সিঙ্ক")
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = vm::unpair, modifier = Modifier.fillMaxWidth()) { Text("পেয়ারিং সরান") }
    } else {
        LabeledField("হাবের IP (যেমন 192.168.0.10)", ui.hostInput, vm::onHost)
        Spacer(Modifier.height(6.dp))
        LabeledField("পেয়ারিং কোড (হাবে দেখানো)", ui.codeInput, vm::onCode)
        Spacer(Modifier.height(8.dp))
        Button(onClick = vm::pair, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (ui.busy) "যুক্ত হচ্ছে…" else "যুক্ত হয়ে সিঙ্ক করুন")
        }
        Text("প্রথম সিঙ্কে সব ডাটা নামে, কিছুক্ষণ লাগতে পারে। কোড ভুল হলে কিছুই সংরক্ষিত হয় না।", style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = vm::makeHub, modifier = Modifier.fillMaxWidth()) { Text("এই ফোনকে হাব বানাই") }
}
