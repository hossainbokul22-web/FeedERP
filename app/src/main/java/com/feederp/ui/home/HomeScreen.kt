package com.feederp.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.feederp.core.MenuItem
import com.feederp.core.SessionManager
import com.feederp.core.UserSession
import com.feederp.ui.AppModules
import com.feederp.ui.auth.AuthRepository
import com.feederp.ui.common.ScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val auth: AuthRepository,
    private val backup: com.feederp.backup.BackupRepository,
    val themeSettings: com.feederp.ui.theme.ThemeSettings,
) : ViewModel() {
    val session: StateFlow<UserSession?> = sessionManager.current

    /** বর্তমান ইউজারের রোল অনুযায়ী দেখানোর মতো মেনু, গ্রুপ ধরে সাজানো। */
    fun visibleMenu(): Map<String, List<MenuItem>> =
        AppModules.all.flatMap { it.menu }
            .filter { sessionManager.can(it.permission) }
            .groupBy { it.group }

    /** মালিকের জন্য: ৭ দিনের বেশি ব্যাকআপ নেওয়া না হলে সতর্কবার্তা (স্বয়ংক্রিয় ব্যাকআপ নেই, তাই মনে করিয়ে দেওয়া)। */
    fun backupWarning(): String? {
        if (!sessionManager.can(com.feederp.core.Permission.BACKUP)) return null
        val last = backup.lastBackupAt() ?: return "এখনও কোনো ব্যাকআপ নেওয়া হয়নি — 'ব্যাকআপ ও রিস্টোর' থেকে নিন।"
        val days = (System.currentTimeMillis() - last) / 86_400_000L
        return if (days >= 7) "শেষ ব্যাকআপ $days দিন আগে — নতুন ব্যাকআপ নিন।" else null
    }

    fun logout(done: () -> Unit) {
        viewModelScope.launch {
            auth.logout()
            done()
        }
    }
}

@Composable
fun HomeScreen(onNavigate: (String) -> Unit, onLoggedOut: () -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val session by vm.session.collectAsStateWithLifecycle()
    val groups = vm.visibleMenu()

    ScreenScaffold(
        title = session?.let { "স্বাগতম, ${it.displayName}" } ?: "ফিড ERP",
        actions = {
            IconButton(onClick = { vm.themeSettings.cycle() }) {
                Icon(androidx.compose.material.icons.Icons.Filled.Brightness6, contentDescription = "থিম বদলান")
            }
            IconButton(onClick = { vm.logout(onLoggedOut) }) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "লগআউট")
            }
        },
    ) { padding ->
        if (groups.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("এই রোলের জন্য এখনও কোনো মডিউল চালু নেই।", style = MaterialTheme.typography.bodyLarge)
            }
            return@ScreenScaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            vm.backupWarning()?.let { w ->
                item(key = "backup-warning") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(w, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            groups.forEach { (group, entries) ->
                item(key = "h-$group") {
                    Text(group, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                items(entries, key = { it.route }) { menu ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onNavigate(menu.route) }) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(menu.titleBn, style = MaterialTheme.typography.titleMedium)
                            Text(menu.titleEn, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
