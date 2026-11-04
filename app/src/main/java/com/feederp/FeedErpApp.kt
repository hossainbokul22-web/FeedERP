package com.feederp

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FeedErpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        // স্পোকে স্বয়ংক্রিয় সিঙ্ক চালু থাকলে নির্ধারণ বজায় রাখা (UPDATE নীতি, তাই বারবার ডাকা নিরাপদ)
        try { com.feederp.sync.SyncScheduler.apply(this, com.feederp.sync.SyncSettings(this).autoSync) } catch (_: Exception) {}
    }
}
