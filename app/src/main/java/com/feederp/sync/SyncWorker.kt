package com.feederp.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncEntryPoint { fun syncManager(): SyncManager }

/**
 * স্পোক ফোনে সময়ে সময়ে স্বয়ংক্রিয় সিঙ্ক। হাব ফোনে বা পেয়ার না থাকলে কিছু করে না।
 * ব্যর্থ হলে (হাব বন্ধ/Wi-Fi নেই) নীরবে সফল ধরে নেয় — পরের নির্ধারিত রানে আবার চেষ্টা; ত্রুটি সিঙ্ক পর্দায় দেখা যায়।
 * সীমা: Android ন্যূনতম ১৫ মিনিট ব্যবধান দেয় এবং ব্যাটারি-সেভারে দেরি/বন্ধ করতে পারে; Wi-Fi-তে ইন্টারনেট না থাকলে "আনমিটারড"
 * শর্ত পূরণ হবে কি না ডিভাইসে পরীক্ষা হয়নি। নিশ্চিত দরকারে "এখনই সিঙ্ক" বাটন আছে। হিল্ট-ওয়ার্ক ব্যবহার না করে EntryPoint দিয়ে।
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val m = EntryPointAccessors.fromApplication(applicationContext, SyncEntryPoint::class.java).syncManager()
        if (m.isHub || m.pairing == null) return Result.success()
        return try { m.syncNow(); Result.success() } catch (e: Exception) { Result.success() }
    }
}

object SyncScheduler {
    private const val NAME = "feederp_auto_sync"

    fun apply(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) { wm.cancelUniqueWork(NAME); return }
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}
