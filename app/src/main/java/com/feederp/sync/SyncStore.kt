package com.feederp.sync

import android.database.Cursor
import android.database.SQLException
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.feederp.core.HybridClock
import com.feederp.data.AppDatabase
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ডাটাবেসের সাথে সিঙ্কের সংযোগ (SQLCipher ডাটাবেসে কাঁচা SQL):
 *  - [ensureSetup]: পরিবর্তন-লগ টেবিল ও ট্রিগার বসায়; প্রথমবার বিদ্যমান সব রো লগে তোলে (যাতে প্রথম সিঙ্কে সবই যায়)।
 *  - [collectChanges]: cursor-এর পরের পরিবর্তিত রো JSON-এ।
 *  - [applyRows]: আসা রো নিয়ম মেনে মার্জ ([SyncMerge])। ইউনিক-মান দ্বন্দ্বে রো বাদ যায়, লিস্টে জমা হয়; কিছুই মুছে ফেলা হয় না।
 * সীমা: লগ কখনও ছাঁটা হয় না (ধাপ ৩-এ ব্যাকআপ/ক্লিনআপের সাথে আসবে); কম্পাইল ও ডিভাইসে পরীক্ষা হয়নি।
 */
@Singleton
class SyncStore @Inject constructor(
    private val db: AppDatabase,
    private val clock: HybridClock,
) {
    private data class TableInfo(val kind: TableKind, val columns: List<String>)

    private val sdb: SupportSQLiteDatabase get() = db.openHelper.writableDatabase
    @Volatile private var tables: Map<String, TableInfo>? = null

    // ---------------- সেটআপ ----------------

    suspend fun ensureSetup() {
        db.withTransaction {
            val s = sdb
            s.execSQL(SyncSql.createChangeLog)
            s.execSQL("CREATE TABLE IF NOT EXISTS local_sync_meta (k TEXT PRIMARY KEY NOT NULL, v TEXT NOT NULL)")
            s.execSQL("CREATE TABLE IF NOT EXISTS local_sync_conflicts (k INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, msg TEXT NOT NULL)")
            val info = discover(s)
            tables = info
            val firstTime = getMetaInternal(s, "backfilled") == null
            for ((name, t) in info) {
                if (t.kind == TableKind.LOCAL) continue
                SyncSql.triggers(name).forEach { s.execSQL(it) }
                if (firstTime) s.execSQL("INSERT INTO ${SyncTables.CHANGE_LOG}(tbl, rowId) SELECT '$name', id FROM $name")
            }
            if (firstTime) setMetaInternal(s, "backfilled", "1")
        }
    }

    private fun discover(s: SupportSQLiteDatabase): Map<String, TableInfo> {
        val names = ArrayList<String>()
        s.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c -> while (c.moveToNext()) names += c.getString(0) }
        val out = LinkedHashMap<String, TableInfo>()
        for (n in names.sorted()) {
            if (!SyncTables.isSafeName(n)) continue
            val cols = ArrayList<String>()
            s.query("PRAGMA table_info($n)").use { c ->
                val idx = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) cols += c.getString(idx)
            }
            out[n] = TableInfo(SyncTables.classify(n, cols), cols)
        }
        return out
    }

    private suspend fun info(): Map<String, TableInfo> = tables ?: run { ensureSetup(); tables!! }

    /** সিঙ্ক হয় এমন টেবিলের তালিকা (স্ক্রিনে দেখানোর জন্য)। */
    suspend fun syncedTables(): List<String> = info().filterValues { it.kind != TableKind.LOCAL }.keys.toList()

    // ---------------- মেটা (পিয়ার cursor ইত্যাদি) ----------------

    suspend fun getMeta(key: String): String? = getMetaInternal(sdb, key)
    suspend fun setMeta(key: String, value: String) = setMetaInternal(sdb, key, value)

    private fun getMetaInternal(s: SupportSQLiteDatabase, key: String): String? =
        s.query("SELECT v FROM local_sync_meta WHERE k = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun setMetaInternal(s: SupportSQLiteDatabase, key: String, value: String) {
        s.execSQL("INSERT OR REPLACE INTO local_sync_meta(k, v) VALUES (?, ?)", arrayOf(key, value))
    }

    suspend fun maxSeq(): Long {
        info()
        return sdb.query(SyncSql.maxSeq).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }

    val dbVersion: Int get() = sdb.version

    // ---------------- দ্বন্দ্বের ইতিহাস ও লগ ছাঁটা ----------------

    /** বসানো যায়নি এমন রো-র বার্তা এই ফোনে জমিয়ে রাখে (সর্বশেষ ২০০টি); সিঙ্ক হয় না (local_ টেবিল)। */
    suspend fun recordConflicts(msgs: List<String>) {
        if (msgs.isEmpty()) return
        info()
        db.withTransaction {
            val s = sdb
            val now = System.currentTimeMillis()
            msgs.forEach { s.execSQL("INSERT INTO local_sync_conflicts(at, msg) VALUES (?, ?)", arrayOf<Any>(now, it)) }
            s.execSQL("DELETE FROM local_sync_conflicts WHERE k NOT IN (SELECT k FROM local_sync_conflicts ORDER BY k DESC LIMIT 200)")
        }
    }

    suspend fun recentConflicts(limit: Int = 50): List<Pair<Long, String>> {
        info()
        val out = ArrayList<Pair<Long, String>>()
        sdb.query("SELECT at, msg FROM local_sync_conflicts ORDER BY k DESC LIMIT ?", arrayOf<Any>(limit)).use { c ->
            while (c.moveToNext()) out += c.getLong(0) to c.getString(1)
        }
        return out
    }

    suspend fun clearConflicts() { info(); sdb.execSQL("DELETE FROM local_sync_conflicts") }

    /**
     * পরিবর্তন-লগ সংকোচন: প্রতিটি (টেবিল, রো)-র শুধু সর্বশেষ এন্ট্রি রাখে। এটি নিরাপদ — [SyncSql.changedKeys] আগে থেকেই
     * প্রতি রো-র MAX(seq) ধরে, তাই যেকোনো cursor-এ ফলাফল একই থাকে; কোনো ফোনের পরিবর্তন হারায় না। ফেরত: মোছা সারি।
     */
    suspend fun compactLog(): Int {
        info()
        val before = sdb.query("SELECT COUNT(*) FROM ${SyncTables.CHANGE_LOG}").use { if (it.moveToFirst()) it.getInt(0) else 0 }
        sdb.execSQL("DELETE FROM ${SyncTables.CHANGE_LOG} WHERE seq NOT IN (SELECT MAX(seq) FROM ${SyncTables.CHANGE_LOG} GROUP BY tbl, rowId)")
        val after = sdb.query("SELECT COUNT(*) FROM ${SyncTables.CHANGE_LOG}").use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return before - after
    }

    // ---------------- পরিবর্তন সংগ্রহ ----------------

    data class Changes(val rows: List<SyncRowDto>, val nextSeq: Long, val more: Boolean)

    /**
     * [afterSeq]-এর পরের পরিবর্তিত রো, সর্বোচ্চ [limit] টি লগ-এন্ট্রি। [excludeAuthorDeviceId] নিজে লেখা রো ফেরত পাঠানো এড়ায়
     * (cursor তবুও এগোয়)। একই রো একাধিকবার বদলালে শুধু বর্তমান অবস্থা যায়।
     */
    suspend fun collectChanges(afterSeq: Long, excludeAuthorDeviceId: String?, limit: Int = SyncProtocol.PAGE_SIZE): Changes {
        val known = info()
        val keys = ArrayList<Triple<String, String, Long>>()
        sdb.query(SyncSql.changedKeys, arrayOf<Any>(afterSeq, limit)).use { c ->
            while (c.moveToNext()) keys += Triple(c.getString(0), c.getString(1), c.getLong(2))
        }
        val rows = ArrayList<SyncRowDto>()
        for ((table, rowId, _) in keys) {
            val t = known[table] ?: continue
            if (t.kind == TableKind.LOCAL) continue
            val row = sdb.query(SyncSql.selectRow(table), arrayOf(rowId)).use { c -> if (c.moveToFirst()) readRow(c) else null } ?: continue
            if (excludeAuthorDeviceId != null && row["deviceId"] == excludeAuthorDeviceId) continue
            rows += SyncRowDto(table, RowCodec.toJson(row))
        }
        return Changes(rows, keys.lastOrNull()?.third ?: afterSeq, more = keys.size >= limit)
    }

    private fun readRow(c: Cursor): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        for (i in 0 until c.columnCount) {
            m[c.getColumnName(i)] = when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                Cursor.FIELD_TYPE_STRING -> c.getString(i)
                else -> throw IllegalStateException("কলাম ${c.getColumnName(i)}: ব্লব সিঙ্ক করা যায় না")
            }
        }
        return m
    }

    // ---------------- আসা রো মার্জ ----------------

    /** সব রো একটি ট্রানজ্যাকশনে। একটি রো-র ত্রুটি অন্যগুলো আটকায় না; ত্রুটি [PushResponse.conflicts]-এ বাংলা বার্তায়। */
    suspend fun applyRows(rows: List<SyncRowDto>): PushResponse {
        val known = info()
        var applied = 0; var skipped = 0
        val conflicts = ArrayList<String>()
        db.withTransaction {
            val s = sdb
            for (dto in rows) {
                val t = known[dto.table]
                if (t == null || t.kind == TableKind.LOCAL || !SyncTables.isSafeName(dto.table)) { skipped++; continue }
                try {
                    if (applyOne(s, dto.table, t, dto.row)) applied++ else skipped++
                } catch (e: SQLException) {
                    conflicts += "${dto.table}: ${(dto.row["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "?"} বসানো যায়নি (${e.message?.take(120)})"
                } catch (e: IllegalArgumentException) {
                    conflicts += "${dto.table}: ${e.message}"
                }
            }
        }
        return PushResponse(applied, skipped, conflicts)
    }

    private fun applyOne(s: SupportSQLiteDatabase, table: String, t: TableInfo, json: JsonObject): Boolean {
        val incoming = RowCodec.fromJson(json).filterKeys { it in t.columns }
        val id = incoming["id"] as? String ?: throw IllegalArgumentException("রো-তে id নেই")
        // কোনো ফোনের ঘড়ি ভুলভাবে অনেক এগিয়ে থাকলে আমাদের ঘড়িও যেন চিরতরে সেদিকে না টানে: ১ দিনের বেশি ভবিষ্যতের সময় ধরা হয় না
        val limit = System.currentTimeMillis() + 86_400_000L
        for (col in listOf("updatedAt", "createdAt")) (incoming[col] as? Long)?.takeIf { it <= limit }?.let(clock::observe)

        var exists = false; var localUpdated: Long? = null; var localDevice: String? = null
        val probe = if (t.kind == TableKind.MUTABLE) "SELECT updatedAt, deviceId FROM $table WHERE id = ?" else "SELECT 1 FROM $table WHERE id = ?"
        s.query(probe, arrayOf(id)).use { c ->
            if (c.moveToFirst()) {
                exists = true
                if (t.kind == TableKind.MUTABLE) { localUpdated = c.getLong(0); localDevice = if (c.isNull(1)) null else c.getString(1) }
            }
        }
        val go = SyncMerge.shouldApply(
            t.kind, exists, localUpdated, localDevice, incoming["updatedAt"] as? Long, incoming["deviceId"] as? String,
        )
        if (!go) return false
        val cols = incoming.keys.toList()
        if (exists) {
            val setCols = cols.filter { it != "id" }
            val args = (setCols.map { incoming[it] } + id).toTypedArray()
            s.execSQL(SyncSql.update(table, setCols), args)
        } else {
            s.execSQL(SyncSql.insert(table, cols, replace = false), cols.map { incoming[it] }.toTypedArray())
        }
        return true
    }
}
