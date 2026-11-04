package com.feederp.ui.sync

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.feederp.core.DeviceIdProvider
import com.feederp.sync.HubService
import com.feederp.sync.SyncManager
import com.feederp.sync.SyncResult
import com.feederp.sync.SyncSettings
import com.feederp.sync.SyncStatus
import com.feederp.ui.master.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject

data class SyncUiState(
    val deviceCode: String = "",
    val hubCode: String = "",
    val localIp: String? = null,
    val hostInput: String = "",
    val codeInput: String = "",
    val busy: Boolean = false,
    val result: SyncResult? = null,
    val autoSync: Boolean = false,
    val history: List<Pair<Long, String>> = emptyList(),
)

object DeviceCodeRule {
    private val OK = Regex("^[A-Z0-9]{2,4}$")
    fun normalize(s: String): String = s.trim().uppercase()
    fun error(s: String): String? = if (OK.matches(normalize(s))) null else "ডিভাইস কোড ২–৪ অক্ষর/সংখ্যার হতে হবে (যেমন S1, M1)"
}

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val manager: SyncManager,
    private val device: DeviceIdProvider,
    @ApplicationContext private val context: Context,
) : BaseViewModel() {
    val status: StateFlow<SyncStatus> = manager.status

    private val _ui = MutableStateFlow(SyncUiState())
    val ui: StateFlow<SyncUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        manager.publish()
        _ui.update {
            it.copy(
                deviceCode = device.deviceCode,
                hubCode = if (manager.isHub) manager.hubCode else "",
                localIp = findLocalIp(),
                hostInput = if (it.hostInput.isBlank()) manager.pairing?.host.orEmpty() else it.hostInput,
                autoSync = manager.autoSync,
            )
        }
        loadHistory()
    }

    private fun loadHistory() = launchSafe { val h = manager.recentConflicts(); _ui.update { it.copy(history = h) } }

    fun clearHistory() = launchSafe { manager.clearConflicts(); loadHistory() }

    fun setAutoSync(on: Boolean) {
        manager.autoSync = on
        com.feederp.sync.SyncScheduler.apply(context, on)
        _ui.update { it.copy(autoSync = on) }
    }

    fun onDeviceCode(v: String) = _ui.update { it.copy(deviceCode = v.uppercase().take(4)) }
    fun onHost(v: String) = _ui.update { it.copy(hostInput = v.trim()) }
    fun onCode(v: String) = _ui.update { it.copy(codeInput = v.uppercase()) }

    fun saveDeviceCode() = launchSafe {
        DeviceCodeRule.error(_ui.value.deviceCode)?.let { throw IllegalArgumentException(it) }
        device.deviceCode = DeviceCodeRule.normalize(_ui.value.deviceCode)
        showMessage("ডিভাইস কোড সংরক্ষিত: ${device.deviceCode}। এখন থেকে নতুন ইনভয়েস নম্বর এই কোড দিয়ে শুরু হবে।")
    }

    fun makeHub() = launchSafe {
        manager.becomeHub()
        HubService.start(context)
        refresh()
    }

    fun makeSpoke() { HubService.stop(context); manager.becomeSpoke(); refresh() }

    fun startHub() { HubService.start(context); refresh() }
    fun stopHub() { HubService.stop(context); manager.stopHub(); refresh() }

    fun newCode() = launchSafe { manager.regenerateHubCode(); refresh() }

    fun pair() = launchSafe {
        val s = _ui.value
        _ui.update { it.copy(busy = true, result = null) }
        try {
            val r = manager.pair(s.hostInput, s.codeInput)
            _ui.update { it.copy(result = r, codeInput = "") }
        } finally {
            _ui.update { it.copy(busy = false) }
        }
    }

    fun syncNow() = launchSafe {
        _ui.update { it.copy(busy = true, result = null) }
        try {
            _ui.update { it.copy(result = manager.syncNow()) }
        } finally {
            _ui.update { it.copy(busy = false) }
            loadHistory()
        }
    }

    fun unpair() { manager.unpair(); refresh() }

    /** এই ফোনের Wi-Fi/LAN IPv4 (স্পোকে এটাই হাবের ঠিকানা হিসেবে লিখতে হবে)। */
    private fun findLocalIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    } catch (e: Exception) { null }
}
