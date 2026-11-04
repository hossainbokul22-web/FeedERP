package com.feederp.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToLong

/** সব রেকর্ডের প্রাইমারি কী: ডিভাইসে তৈরি UUID, তাই ফোনগুলোর মধ্যে সংঘর্ষ হয় না। */
fun newId(): String = UUID.randomUUID().toString()

/**
 * Hybrid Logical Clock (সরলীকৃত): সবসময় বাড়তে থাকা Long (মিলিসেকেন্ড-ভিত্তিক)।
 * সিঙ্কে অন্য ডিভাইসের টাইমস্ট্যাম্প দেখলে [observe] দিয়ে ঘড়ি এগিয়ে নেওয়া হয়।
 */
@Singleton
class HybridClock @Inject constructor() {
    private var last = 0L

    @Synchronized
    fun next(): Long {
        last = max(System.currentTimeMillis(), last + 1)
        return last
    }

    @Synchronized
    fun observe(remote: Long) {
        if (remote > last) last = remote
    }
}

/** এই ফোনের স্থায়ী আইডি ও ছোট কোড (ইনভয়েস/ওয়ার্ক অর্ডার নম্বরের প্রিফিক্সে ব্যবহৃত, যেমন "S1")। */
@Singleton
class DeviceIdProvider @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    val deviceId: String = prefs.getString("id", null) ?: newId().also {
        prefs.edit().putString("id", it).apply()
    }

    var deviceCode: String
        get() = prefs.getString("code", null) ?: "D" + deviceId.take(2).uppercase(Locale.ROOT)
        set(value) = prefs.edit().putString("code", value.uppercase(Locale.ROOT)).apply()

    var isHub: Boolean
        get() = prefs.getBoolean("is_hub", false)
        set(value) = prefs.edit().putBoolean("is_hub", value).apply()
}

/** প্রতিটি লেখার সময় `updatedAt` ও `deviceId` বসানোর জন্য। */
@Singleton
class Stamper @Inject constructor(
    private val clock: HybridClock,
    private val device: DeviceIdProvider,
) {
    fun now(): Long = clock.next()
    val deviceId: String get() = device.deviceId
    val deviceCode: String get() = device.deviceCode
}

/**
 * টাকা সবসময় Long (পয়সা) হিসেবে সংরক্ষিত, যাতে ফ্লোটিং-পয়েন্ট ভুল না হয়।
 * পরিমাণ (ওজন) গ্রামে, Double।
 */
object Money {
    fun fromTaka(taka: Double): Long = (taka * 100.0).roundToLong()
    fun toTaka(paisa: Long): Double = paisa / 100.0
    fun format(paisa: Long): String = "৳" + String.format(Locale.US, "%,.2f", paisa / 100.0)
    fun parseTaka(text: String): Long? =
        text.replace(",", "").trim().toDoubleOrNull()?.let { fromTaka(it) }
}

object Weight {
    const val G_PER_KG = 1000.0
    const val G_PER_TON = 1_000_000.0
    fun kgToGrams(kg: Double) = kg * G_PER_KG
    fun gramsToKg(g: Double) = g / G_PER_KG
    fun formatKg(grams: Double): String = String.format(Locale.US, "%,.2f কেজি", grams / G_PER_KG)
}
