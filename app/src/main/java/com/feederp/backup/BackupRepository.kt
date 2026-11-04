package com.feederp.backup

import android.content.Context
import com.feederp.core.DbKeyProvider
import com.feederp.core.Permission
import com.feederp.core.SessionManager
import com.feederp.core.Stamper
import com.feederp.data.AppDatabase
import com.feederp.inventory.AuditRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ডাটাবেসের পূর্ণ ব্যাকআপ ও রিস্টোর (শুধু Permission.BACKUP — মালিক)।
 *
 * ব্যাকআপ: WAL চেকপয়েন্ট (TRUNCATE) → লেখার লক ধরে (অন্য কেউ লিখতে পারে না) ডাটাবেস ফাইল কপি → [BackupFormat]-এ পাসফ্রেজ দিয়ে এনক্রিপ্ট।
 * ফাইল কোথায় যাবে তা UI ঠিক করে (OutputStream), তাই ফোনের ফাইল-ম্যানেজার/ড্রাইভ/পেনড্রাইভ যেকোনোটিতে রাখা যায়।
 * রিস্টোর: পাসফ্রেজ যাচাই → স্কিমা ভার্সন মিলতে হবে → ডাটাবেস বন্ধ → বর্তমান ফাইল "feederp.db.pre-restore" নামে সরানো → নতুন ফাইল বসানো
 * → কী বদলানো। এরপর অ্যাপ অবশ্যই বন্ধ করে আবার খুলতে হবে ([RestoreResult.restartRequired])।
 * সীমা: স্বয়ংক্রিয় ব্যাকআপ নেই (হাতে নিতে হয়); ভিন্ন স্কিমা ভার্সনের ব্যাকআপ রিস্টোর হয় না; ফাইল মেমরিতে লোড হয় (সীমা [BackupFormat.MAX_DB_BYTES]);
 * রিস্টোর করা ফোনে সিঙ্ক-কার্সর ব্যাকআপের সময়ের অবস্থায় ফিরে যায় — একই ডিভাইস কোডে দুটি ফোন একসাথে চালাবেন না।
 * কম্পাইল ও ডিভাইসে পরীক্ষা হয়নি।
 */
data class RestoreResult(val meta: BackupMeta, val restartRequired: Boolean = true)

@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val keys: DbKeyProvider,
    private val stamper: Stamper,
    private val session: SessionManager,
    private val audit: AuditRepository,
) {
    companion object { const val DB_NAME = "feederp.db" }   // DatabaseModule-এর নামের সাথে মিলতে হবে

    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val dbFile: File get() = context.getDatabasePath(DB_NAME)

    fun lastBackupAt(): Long? = prefs.getLong("lastBackupAt", 0L).takeIf { it > 0 }

    /** ব্যাকআপ নিয়ে [out]-এ লেখে (বন্ধ করে না)। ফেরত: ফাইলের আকার (বাইট)। */
    suspend fun export(passphrase: String, out: OutputStream): Long = withContext(Dispatchers.IO) {
        session.require(Permission.BACKUP)
        BackupFormat.passphraseError(passphrase)?.let { throw IllegalArgumentException(it) }
        val snapshot = consistentCopy()
        val sealed = BackupFormat.seal(
            passphrase, BackupMeta(snapshot.version, System.currentTimeMillis(), stamper.deviceCode), keys.passphrase(), snapshot.bytes,
        )
        out.write(sealed); out.flush()
        prefs.edit().putLong("lastBackupAt", System.currentTimeMillis()).apply()
        audit.log("BACKUP_EXPORT", "database", null, "${sealed.size} bytes")
        sealed.size.toLong()
    }

    private class Snapshot(val bytes: ByteArray, val version: Int)

    private suspend fun consistentCopy(): Snapshot {
        val sdb = db.openHelper.writableDatabase
        val file = dbFile
        val wal = File(file.path + "-wal")
        repeat(8) { attempt ->
            sdb.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            sdb.beginTransaction()   // লেখার লক: এখন কেউ কমিট/চেকপয়েন্ট করতে পারবে না
            try {
                if (!wal.exists() || wal.length() == 0L) {
                    require(file.length() in 1..BackupFormat.MAX_DB_BYTES) { "ডাটাবেস ফাইলের আকার সীমার বাইরে" }
                    return Snapshot(file.readBytes(), sdb.version)
                }
            } finally {
                sdb.endTransaction()
            }
            delay(200L * (attempt + 1))
        }
        throw IllegalStateException("এখন ডাটাবেস ব্যস্ত; কিছুক্ষণ পরে আবার চেষ্টা করুন")
    }

    /** ব্যাকআপ থেকে রিস্টোর। সফল হলে অ্যাপ বন্ধ করে আবার খুলতে হবে; তার আগে আর কিছু করবেন না। */
    suspend fun restore(passphrase: String, input: InputStream): RestoreResult = withContext(Dispatchers.IO) {
        session.require(Permission.BACKUP)
        val bytes = input.readBytes()
        val contents = BackupFormat.open(passphrase, bytes)
        val currentVersion = db.openHelper.writableDatabase.version
        require(contents.meta.schemaVersion == currentVersion) {
            "ব্যাকআপ ভিন্ন ভার্সনের (ব্যাকআপ ${contents.meta.schemaVersion}, অ্যাপ $currentVersion); একই ভার্সনের অ্যাপে রিস্টোর করুন"
        }
        db.close()
        val base = dbFile
        val side = listOf("-wal", "-shm", "-journal").map { File(base.path + it) }
        val prev = File(base.path + ".pre-restore")
        val tmp = File(base.path + ".restoring")
        var moved = false
        try {
            tmp.writeBytes(contents.db)
            side.forEach { if (it.exists()) it.delete() }
            if (prev.exists()) prev.delete()
            if (base.exists()) { check(base.renameTo(prev)) { "বর্তমান ফাইল সরানো যায়নি" }; moved = true }
            check(tmp.renameTo(base)) { "নতুন ফাইল বসানো যায়নি" }
            keys.replacePassphrase(contents.dbKey)
        } catch (e: Exception) {
            // ফাইল বসানো ব্যর্থ: আগেরটা ফিরিয়ে আনার চেষ্টা (কী তখনও বদলায়নি)
            if (moved && !base.exists() && prev.exists()) prev.renameTo(base)
            tmp.delete()
            throw IllegalStateException("রিস্টোর ব্যর্থ (${e.message}); অ্যাপ বন্ধ করে আবার খুলুন — আগের ডাটা অক্ষত থাকার কথা", e)
        }
        RestoreResult(contents.meta)
    }
}
