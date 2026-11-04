package com.feederp.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * হাব ফোনে সিঙ্ক সার্ভার চালু রাখে (ফোরগ্রাউন্ড সার্ভিস, স্থায়ী নোটিফিকেশনসহ), যাতে অ্যাপ বন্ধ করলেও স্পোক সিঙ্ক করতে পারে।
 * শুরু: [start]; বন্ধ: [stop]। সীমা: অ্যান্ড্রয়েডের ব্যাটারি-অপ্টিমাইজেশন সার্ভিস মেরে দিতে পারে — হাব ফোনে
 * "ব্যাটারি অপ্টিমাইজেশন: নিষেধ নেই" সেট করুন এবং ফোন চার্জারে/Wi-Fi এ রাখুন।
 */
@AndroidEntryPoint
class HubService : Service() {
    @Inject lateinit var manager: SyncManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        scope.launch {
            try {
                manager.startHub()
            } catch (e: Exception) {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        manager.stopHub()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "সিঙ্ক হাব", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Feed ERP হাব চালু")
            .setContentText("অন্য ফোনগুলো সিঙ্ক করতে পারবে")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "hub"
        private const val NOTIF_ID = 4101

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, HubService::class.java))
        }

        fun stop(context: Context) { context.stopService(Intent(context, HubService::class.java)) }
    }
}
