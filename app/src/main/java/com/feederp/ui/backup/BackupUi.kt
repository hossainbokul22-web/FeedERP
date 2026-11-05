package com.feederp.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
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
import com.feederp.ui.common.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.feederp.backup.BackupRepository
import com.feederp.core.MenuItem
import com.feederp.core.ModuleEntry
import com.feederp.core.Permission
import com.feederp.ui.common.KeyValueRow
import com.feederp.ui.common.LabeledField
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.formatDateTime
import com.feederp.ui.master.BaseViewModel
import com.feederp.ui.master.MessageDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(private val repo: BackupRepository) : BaseViewModel() {
    private val _last = MutableStateFlow(repo.lastBackupAt())
    val last: StateFlow<Long?> = _last.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _restored = MutableStateFlow(false)
    val restored: StateFlow<Boolean> = _restored.asStateFlow()

    fun export(pass: String, out: OutputStream) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val size = repo.export(pass, out)
                _last.value = repo.lastBackupAt()
                showMessage("ব্যাকআপ সম্পন্ন (${size / 1024} KB)। ফাইল ও পাসফ্রেজ আলাদা নিরাপদ জায়গায় রাখুন।")
            } catch (e: Exception) {
                showMessage(e.message ?: "ব্যাকআপ ব্যর্থ")
            } finally {
                try { out.close() } catch (_: Exception) {}
                _busy.value = false
            }
        }
    }

    fun restore(pass: String, input: InputStream) {
        viewModelScope.launch {
            _busy.value = true
            try {
                repo.restore(pass, input)
                _restored.value = true
            } catch (e: Exception) {
                showMessage(e.message ?: "রিস্টোর ব্যর্থ")
            } finally {
                try { input.close() } catch (_: Exception) {}
                _busy.value = false
            }
        }
    }
}

@Composable
fun BackupScreen(onBack: () -> Unit, vm: BackupViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val last by vm.last.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val restored by vm.restored.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageDialog(message, onDismiss = vm::clearMessage)

    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }   // EXPORT | RESTORE — ফাইল বাছাইয়ের আগে নিশ্চিতকরণ
    var confirmRestore by remember { mutableStateOf(false) }

    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            val out = ctx.contentResolver.openOutputStream(uri)
            if (out != null) vm.export(pass, out)
        }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val input = ctx.contentResolver.openInputStream(uri)
            if (input != null) vm.restore(pass, input)
        }
    }

    ScreenScaffold(title = "ব্যাকআপ ও রিস্টোর", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            KeyValueRow("শেষ ব্যাকআপ", last?.let { formatDateTime(it) } ?: "কখনও নেওয়া হয়নি")
            Spacer(Modifier.height(8.dp))
            Text(
                "ব্যাকআপ ফাইল পাসফ্রেজ দিয়ে এনক্রিপ্ট হয়। পাসফ্রেজ হারালে ব্যাকআপ আর খোলা যাবে না — কেউ উদ্ধার করতে পারবে না। " +
                    "নিয়মিত (যেমন প্রতিদিন) ব্যাকআপ নিয়ে ফাইল পেনড্রাইভ/অন্য ফোনে রাখুন; স্বয়ংক্রিয় ব্যাকআপ নেই।",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.OutlinedTextField(
                value = pass, onValueChange = { pass = it }, label = { Text("পাসফ্রেজ (কমপক্ষে ৮ অক্ষর)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.OutlinedTextField(
                value = pass2, onValueChange = { pass2 = it }, label = { Text("পাসফ্রেজ আবার (শুধু ব্যাকআপের সময়)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                enabled = !busy, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val err = com.feederp.backup.BackupFormat.passphraseError(pass)
                    when {
                        err != null -> pending = err
                        pass != pass2 -> pending = "দুই পাসফ্রেজ মেলেনি"
                        else -> createLauncher.launch("feederp-backup-${java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())}.febk")
                    }
                },
            ) { Text(if (busy) "অপেক্ষা করুন..." else "ব্যাকআপ নিন") }
            Spacer(Modifier.height(16.dp))
            Text("রিস্টোর", style = MaterialTheme.typography.titleSmall)
            Text(
                "রিস্টোর করলে এই ফোনের বর্তমান সব ডাটা ব্যাকআপের অবস্থা দিয়ে বদলে যাবে (আগেরটা ফোনে 'pre-restore' নামে থেকে যায়)। " +
                    "শেষে অ্যাপ বন্ধ করে আবার খুলতে হবে।",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(enabled = !busy && pass.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = { confirmRestore = true }) { Text("ব্যাকআপ ফাইল থেকে রিস্টোর") }
        }
    }
    pending?.let { msg -> MessageDialog(msg, onDismiss = { pending = null }) }
    if (confirmRestore) com.feederp.ui.common.ConfirmDialog(
        "রিস্টোর", "এই ফোনের বর্তমান ডাটা বদলে যাবে। নিশ্চিত?",
        { confirmRestore = false; openLauncher.launch(arrayOf("*/*")) }, { confirmRestore = false },
    )
    if (restored) AlertDialog(
        onDismissRequest = {}, title = { Text("রিস্টোর সম্পন্ন") },
        text = { Text("অ্যাপ এখন বন্ধ হবে। আবার খুলে লগইন করুন।") },
        confirmButton = { TextButton(onClick = { android.os.Process.killProcess(android.os.Process.myPid()) }) { Text("অ্যাপ বন্ধ করুন") } },
    )
}

object BackupModule : ModuleEntry {
    const val ROUTE = "backup/main"
    override val menu: List<MenuItem> = listOf(MenuItem(ROUTE, "ব্যাকআপ ও রিস্টোর", "Backup & Restore", Permission.BACKUP, "সিস্টেম"))
    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.composable(ROUTE) { BackupScreen(onBack = { nav.popBackStack() }) }
    }
}
