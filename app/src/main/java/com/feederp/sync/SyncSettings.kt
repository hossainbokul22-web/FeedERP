package com.feederp.sync

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * সিঙ্ক কনফিগারেশন (অ্যাপের ব্যক্তিগত SharedPreferences)।
 * হাব: পেয়ারিং কোড + salt। স্পোক: হাবের ঠিকানা, ডেরাইভ করা কী ও হাবের আইডি।
 * সীমা: কী এখানে সাধারণ টেক্সটে থাকে (অ্যান্ড্রয়েডের অ্যাপ-স্যান্ডবক্স রক্ষা করে; রুট করা ফোনে নয়)।
 */
@Singleton
class SyncSettings @Inject constructor(@ApplicationContext context: Context) {
    private val p = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    companion object { const val DEFAULT_PORT = 8765 }

    // ---- হাব ----
    var hubName: String
        get() = p.getString("hub_name", null) ?: "Feed ERP হাব"
        set(v) = p.edit().putString("hub_name", v).apply()

    /** হাবের কোড; প্রথমবার নিজে তৈরি হয়। */
    val hubCode: String
        get() = p.getString("hub_code", null) ?: SyncCrypto.generateCode().also { p.edit().putString("hub_code", it).apply() }

    val hubSalt: ByteArray
        get() = p.getString("hub_salt", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: SyncCrypto.newSalt().also { p.edit().putString("hub_salt", Base64.encodeToString(it, Base64.NO_WRAP)).apply() }

    /** নতুন কোড ও salt: পুরনো সব স্পোক বিচ্ছিন্ন হবে, আবার পেয়ার করতে হবে। */
    fun regenerateHubCode() {
        p.edit().remove("hub_code").remove("hub_salt").apply()
        hubCode; hubSalt
    }

    var hubPort: Int
        get() = p.getInt("hub_port", DEFAULT_PORT)
        set(v) = p.edit().putInt("hub_port", v).apply()

    fun hubKey(): ByteArray = SyncCrypto.deriveKey(hubCode, hubSalt)

    /** স্পোকে ব্যাকগ্রাউন্ড সময়ে সময়ে সিঙ্ক (WorkManager) চালু কি না। */
    var autoSync: Boolean
        get() = p.getBoolean("auto_sync", false)
        set(v) = p.edit().putBoolean("auto_sync", v).apply()

    // ---- স্পোক ----
    data class Pairing(val host: String, val port: Int, val key: ByteArray, val hubDeviceId: String, val hubName: String)

    fun pairing(): Pairing? {
        val host = p.getString("peer_host", null) ?: return null
        val key = p.getString("peer_key", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return Pairing(host, p.getInt("peer_port", DEFAULT_PORT), key, p.getString("peer_id", "") ?: "", p.getString("peer_name", "") ?: "")
    }

    fun savePairing(x: Pairing) {
        p.edit().putString("peer_host", x.host).putInt("peer_port", x.port)
            .putString("peer_key", Base64.encodeToString(x.key, Base64.NO_WRAP))
            .putString("peer_id", x.hubDeviceId).putString("peer_name", x.hubName).apply()
    }

    fun clearPairing() { p.edit().remove("peer_host").remove("peer_port").remove("peer_key").remove("peer_id").remove("peer_name").apply() }

    var lastSyncAt: Long
        get() = p.getLong("last_sync", 0L)
        set(v) = p.edit().putLong("last_sync", v).apply()
}
