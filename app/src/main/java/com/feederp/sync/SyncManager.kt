package com.feederp.sync

import android.util.Base64
import com.feederp.core.DeviceIdProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** স্ক্রিন ও সার্ভিসের জন্য একমাত্র প্রবেশপথ: হাব চালু/বন্ধ, পেয়ারিং, "এখনই সিঙ্ক"। */
@Singleton
class SyncManager @Inject constructor(
    private val server: SyncServer,
    private val client: SyncClient,
    private val store: SyncStore,
    private val settings: SyncSettings,
    private val device: DeviceIdProvider,
) {
    private val syncLock = Mutex()

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    val isHub: Boolean get() = device.isHub
    val pairing: SyncSettings.Pairing? get() = settings.pairing()
    val hubCode: String get() = settings.hubCode

    /** এই ফোনকে হাব বানায় (আগের স্পোক-পেয়ারিং মুছে)। সার্ভার চালু করা আলাদা ধাপ ([startHub])। */
    fun becomeHub() {
        device.isHub = true
        settings.clearPairing()
        publish()
    }

    fun becomeSpoke() {
        stopHub()
        device.isHub = false
        publish()
    }

    suspend fun startHub() {
        check(device.isHub) { "এই ফোন হাব নয়" }
        store.ensureSetup()
        server.start()
        publish()
    }

    fun stopHub() { server.stop(); publish() }

    fun regenerateHubCode() {
        settings.regenerateHubCode()
        if (server.isRunning) { server.stop(); server.start() }   // নতুন কী দিয়ে আবার চালু
        publish()
    }

    /**
     * স্পোক হিসেবে হাবের সাথে যুক্ত: হাব খুঁজে, কোড থেকে কী বানিয়ে একটি পূর্ণ সিঙ্ক চালিয়ে দেখে।
     * সফল হলে তবেই পেয়ারিং সংরক্ষণ হয় (ভুল কোডে কিছুই সংরক্ষিত হয় না)।
     */
    suspend fun pair(host: String, code: String, port: Int = SyncSettings.DEFAULT_PORT): SyncResult {
        require(!device.isHub) { "হাব ফোন অন্য হাবে পেয়ার হতে পারে না" }
        require(host.isNotBlank()) { "হাবের IP ঠিকানা দিন" }
        require(SyncCrypto.isValidCode(code)) { "পেয়ারিং কোড ১০ অক্ষরের (যেমন ABCDE-FGHJK)" }
        val h = host.trim()
        val hello = client.hello(h, port)
        val key = SyncCrypto.deriveKey(code, Base64.decode(hello.saltB64, Base64.NO_WRAP))
        val p = SyncSettings.Pairing(h, port, key, hello.hubDeviceId, hello.hubName)
        val result = runSync(p)
        settings.savePairing(p)
        publish()
        return result
    }

    fun unpair() { settings.clearPairing(); publish() }

    var autoSync: Boolean
        get() = settings.autoSync
        set(v) { settings.autoSync = v }

    suspend fun recentConflicts() = store.recentConflicts()
    suspend fun clearConflicts() = store.clearConflicts()

    /** পেয়ার করা হাবের সাথে সিঙ্ক। একসাথে একটাই চলে; ত্রুটি [SyncException]। */
    suspend fun syncNow(): SyncResult {
        val p = settings.pairing() ?: throw SyncException("আগে হাবের সাথে পেয়ার করুন")
        return runSync(p)
    }

    private suspend fun runSync(p: SyncSettings.Pairing): SyncResult = syncLock.withLock {
        _status.value = _status.value.copy(running = true, lastError = null)
        try {
            val r = client.sync(p)
            settings.lastSyncAt = System.currentTimeMillis()
            try { store.compactLog() } catch (_: Exception) { /* সংকোচন ব্যর্থ হলে সিঙ্ক ব্যর্থ নয় */ }
            _status.value = snapshot().copy(running = false, lastResult = r)
            r
        } catch (e: SyncException) {
            _status.value = snapshot().copy(running = false, lastError = e.message)
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: "সিঙ্কে অজানা ত্রুটি"
            _status.value = snapshot().copy(running = false, lastError = msg)
            throw SyncException(msg)
        }
    }

    private fun snapshot() = SyncStatus(
        isHub = device.isHub, hubRunning = server.isRunning, paired = settings.pairing() != null,
        peerName = settings.pairing()?.hubName.orEmpty(), lastSyncAt = settings.lastSyncAt,
        lastResult = _status.value.lastResult, lastError = _status.value.lastError,
    )

    fun publish() { _status.value = snapshot().copy(running = _status.value.running) }
}

data class SyncStatus(
    val isHub: Boolean = false,
    val hubRunning: Boolean = false,
    val paired: Boolean = false,
    val peerName: String = "",
    val lastSyncAt: Long = 0L,
    val running: Boolean = false,
    val lastResult: SyncResult? = null,
    val lastError: String? = null,
)
