package com.feederp.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
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
import com.feederp.ui.common.Text
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
import com.feederp.ui.common.HexIcon
import com.feederp.ui.common.ScreenScaffold
import com.feederp.ui.common.moduleIcon
import com.feederp.ui.theme.AppLang
import com.feederp.ui.theme.LocalLang
import com.feederp.ui.theme.tr
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val auth: AuthRepository,
    private val backup: com.feederp.backup.BackupRepository,
    val themeSettings: com.feederp.ui.theme.ThemeSettings,
    val languageSettings: com.feederp.ui.theme.LanguageSettings,
    dashboardRepo: com.feederp.dashboard.DashboardRepository,
) : ViewModel() {
    val dashboard: StateFlow<com.feederp.dashboard.DashboardData?> = dashboardRepo.observe()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), null)

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

    val canEditCompany: Boolean get() = sessionManager.can(com.feederp.core.Permission.USERS)

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
    val dash by vm.dashboard.collectAsStateWithLifecycle()

    ScreenScaffold(
        title = session?.let { tr("স্বাগতম, ${it.displayName}", "Welcome, ${it.displayName}") } ?: tr("ফিড ERP", "Feed ERP"),
        actions = {
            androidx.compose.material3.TextButton(onClick = { vm.languageSettings.toggle() }) {
                Text(
                    tr("English", "বাংলা"),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
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
                Text(tr("এই রোলের জন্য এখনও কোনো মডিউল চালু নেই।", "No modules are enabled for this role yet."), style = MaterialTheme.typography.bodyLarge)
            }
            return@ScreenScaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "dashboard") { DashboardHeader(dash) }
            if (vm.canEditCompany) item(key = "company") { CompanyNameCard() }
            vm.backupWarning()?.let { w ->
                item(key = "backup-warning") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(w, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            groups.forEach { (group, entries) ->
                item(key = "h-$group") {
                    Text(groupLabel(group), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                items(entries.chunked(2), key = { row -> row.first().route }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { menu ->
                            Card(Modifier.weight(1f).clickable { onNavigate(menu.route) }) {
                                androidx.compose.foundation.layout.Column(Modifier.padding(14.dp)) {
                                    HexIcon(moduleIcon(menu.titleEn))
                                    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                                    Text(tr(menu.titleBn, menu.titleEn), style = MaterialTheme.typography.titleMedium)
                                    if (LocalLang.current == AppLang.BN) Text(menu.titleEn, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private val GROUP_EN = mapOf(
    "মাস্টার ডাটা" to "Master Data", "মান নিয়ন্ত্রণ" to "Quality Control", "ফর্মুলা ও প্রোডাকশন" to "Formulation & Production",
    "বিক্রয়" to "Sales", "এইচআর" to "HR", "ইনভেন্টরি" to "Inventory", "ক্রয়" to "Purchase", "রক্ষণাবেক্ষণ" to "Maintenance",
    "R&D" to "R&D", "হিসাব" to "Accounts", "ডিস্ট্রিবিউশন" to "Distribution", "সিস্টেম" to "System",
)

@Composable
private fun groupLabel(group: String): String = tr(group, GROUP_EN[group] ?: group)
