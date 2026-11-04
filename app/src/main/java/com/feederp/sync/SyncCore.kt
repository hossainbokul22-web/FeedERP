package com.feederp.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * সিঙ্কের ধরন প্রতি টেবিলে (ডাটাবেসের কলাম দেখে স্বয়ংক্রিয়ভাবে ঠিক হয়, ৭৪টি টেবিলের আলাদা কোড লাগে না):
 *  - MUTABLE: `id` + `updatedAt` আছে → সর্বশেষ লেখা জেতে (সমান হলে বড় deviceId)।
 *  - APPEND_ONLY: `id` + `createdAt` আছে (updatedAt নেই) → শুধু নতুন রো যোগ হয়, কখনও বদলায় না (স্টক মুভমেন্ট, লেজার ইত্যাদি)।
 *  - LOCAL: সিঙ্ক হয় না (`local_*`, কাউন্টার, সিস্টেম টেবিল, `id` নেই এমন)।
 */
enum class TableKind { MUTABLE, APPEND_ONLY, LOCAL }

object SyncTables {
    const val CHANGE_LOG = "local_change_log"
    private val NAME_OK = Regex("^[a-z][a-z0-9_]*$")

    /** টেবিলের নাম SQL-এ বসানোর আগে যাচাই (রিমোট থেকে আসা নামও এখান দিয়ে যায়)। */
    fun isSafeName(name: String): Boolean = NAME_OK.matches(name)

    fun classify(table: String, columns: Collection<String>): TableKind {
        if (!isSafeName(table)) return TableKind.LOCAL
        if (table.startsWith("local_") || table.startsWith("sqlite_") || table.startsWith("android_") || table.startsWith("room_")) return TableKind.LOCAL
        if (table == "number_counters") return TableKind.LOCAL
        if ("id" !in columns) return TableKind.LOCAL
        return when {
            "updatedAt" in columns -> TableKind.MUTABLE
            "createdAt" in columns -> TableKind.APPEND_ONLY
            else -> TableKind.LOCAL
        }
    }
}

object SyncMerge {
    /**
     * রিমোট থেকে আসা রো লোকালে বসবে কিনা।
     * [localExists] না থাকলে নতুন রো সবসময় বসে। APPEND_ONLY-তে থাকলে কখনও বদলায় না।
     * MUTABLE-তে নতুন [inUpdatedAt] জেতে; সমান হলে বড় deviceId (দুই ফোনেই একই ফল, তাই অভিসারী)।
     */
    fun shouldApply(
        kind: TableKind, localExists: Boolean,
        localUpdatedAt: Long?, localDeviceId: String?,
        inUpdatedAt: Long?, inDeviceId: String?,
    ): Boolean = when (kind) {
        TableKind.LOCAL -> false
        TableKind.APPEND_ONLY -> !localExists
        TableKind.MUTABLE -> when {
            !localExists -> true
            inUpdatedAt == null -> false
            localUpdatedAt == null -> true
            inUpdatedAt != localUpdatedAt -> inUpdatedAt > localUpdatedAt
            else -> (inDeviceId ?: "") > (localDeviceId ?: "")
        }
    }
}

/** SQL বাক্য তৈরি (টেবিল/কলামের নাম আগে [SyncTables.isSafeName] দিয়ে যাচাই করা বাধ্যতামূলক)। */
object SyncSql {
    /** কলামের নামে বড় হাতের অক্ষর থাকে (updatedAt), টেবিলের নামে থাকে না। */
    val COLUMN_OK = Regex("^[A-Za-z][A-Za-z0-9_]*$")

    val createChangeLog = "CREATE TABLE IF NOT EXISTS ${SyncTables.CHANGE_LOG} (" +
        "seq INTEGER PRIMARY KEY AUTOINCREMENT, tbl TEXT NOT NULL, rowId TEXT NOT NULL)"

    /** insert/update-এ পরিবর্তন লগে তোলার ট্রিগার (REPLACE-ও INSERT ট্রিগার চালায়)। */
    fun triggers(table: String): List<String> {
        require(SyncTables.isSafeName(table)) { "টেবিলের নাম অবৈধ" }
        fun t(kind: String) = "CREATE TRIGGER IF NOT EXISTS sync_${kind.lowercase()}_$table AFTER $kind ON $table BEGIN " +
            "INSERT INTO ${SyncTables.CHANGE_LOG}(tbl, rowId) VALUES('$table', NEW.id); END"
        return listOf(t("INSERT"), t("UPDATE"))
    }

    /** [cursor]-এর পরের পরিবর্তিত (টেবিল, রো) জোড়া, পুরনো আগে; একই রো একবারই। প্যারামিটার: cursor, limit। */
    const val changedKeys = "SELECT tbl, rowId, MAX(seq) AS s FROM ${SyncTables.CHANGE_LOG} " +
        "WHERE seq > ? GROUP BY tbl, rowId ORDER BY s LIMIT ?"

    const val maxSeq = "SELECT COALESCE(MAX(seq), 0) FROM ${SyncTables.CHANGE_LOG}"

    fun selectRow(table: String): String {
        require(SyncTables.isSafeName(table)) { "টেবিলের নাম অবৈধ" }
        return "SELECT * FROM $table WHERE id = ?"
    }

    /** আগে থেকে থাকা রো আপডেট (REPLACE নয়: REPLACE অন্য রো-র ইউনিক মান মুছে ফেলতে পারে)। শেষ প্যারামিটার = id। */
    fun update(table: String, columns: List<String>): String {
        require(SyncTables.isSafeName(table)) { "টেবিলের নাম অবৈধ" }
        val cols = columns.filter { it != "id" }
        require(cols.isNotEmpty() && cols.all { COLUMN_OK.matches(it) }) { "কলামের নাম অবৈধ" }
        return "UPDATE $table SET ${cols.joinToString(", ") { "$it = ?" }} WHERE id = ?"
    }

    fun insert(table: String, columns: List<String>, replace: Boolean): String {
        require(SyncTables.isSafeName(table)) { "টেবিলের নাম অবৈধ" }
        require(columns.isNotEmpty() && columns.all { COLUMN_OK.matches(it) }) { "কলামের নাম অবৈধ" }
        val verb = if (replace) "INSERT OR REPLACE" else "INSERT"
        return "$verb INTO $table (${columns.joinToString(", ")}) VALUES (${columns.joinToString(", ") { "?" }})"
    }
}

/** ডাটাবেস রো ↔ JSON। ইন্টিজার/ডাবল/টেক্সট/NULL সমর্থিত; ব্লব নেই (এই অ্যাপে কোনো ব্লব কলাম নেই)। */
object RowCodec {
    fun toJson(row: Map<String, Any?>): JsonObject = JsonObject(
        row.mapValues { (k, v) ->
            when (v) {
                null -> JsonNull
                is Long -> JsonPrimitive(v)
                is Int -> JsonPrimitive(v.toLong())
                is Short -> JsonPrimitive(v.toLong())
                is Boolean -> JsonPrimitive(if (v) 1L else 0L)
                is Double -> { require(v.isFinite()) { "কলাম $k: অসীম/NaN সিঙ্ক করা যায় না" }; JsonPrimitive(v) }
                is Float -> JsonPrimitive(v.toDouble())
                is String -> JsonPrimitive(v)
                else -> throw IllegalArgumentException("কলাম $k: অসমর্থিত ধরন ${v::class.simpleName}")
            }
        }
    )

    fun fromJson(obj: JsonObject): Map<String, Any?> = obj.mapValues { (_, e) -> primitive(e) }

    private fun primitive(e: JsonElement): Any? {
        if (e is JsonNull) return null
        val p = e as? JsonPrimitive ?: throw IllegalArgumentException("নেস্টেড মান সমর্থিত নয়")
        if (p.isString) return p.content
        return p.longOrNull ?: p.doubleOrNull ?: throw IllegalArgumentException("সংখ্যা পড়া যায়নি: ${p.content}")
    }
}

/** নেটওয়ার্কে যাওয়া বার্তা। সব JSON, পুরোটা [SyncCrypto] দিয়ে এনক্রিপ্ট হয়ে যায়। */
object SyncProtocol {
    /** দুই পাশের সংস্করণ না মিললে সিঙ্ক অস্বীকার (ডাটাবেস কাঠামো আলাদা হলে রো বসানো নিরাপদ নয়)। */
    const val VERSION = 1
    const val PAGE_SIZE = 300
}

@Serializable
data class SyncHello(val hubName: String, val hubDeviceId: String, val saltB64: String, val protocol: Int, val dbVersion: Int)

@Serializable
data class SyncRowDto(val table: String, val row: JsonObject)

/** পুল অনুরোধ: [afterSeq] = পিয়ারের লগে যেখান পর্যন্ত নেওয়া হয়েছে; [requesterDeviceId]-এর নিজের লেখা রো ফেরত যায় না। */
@Serializable
data class PullRequest(val afterSeq: Long, val requesterDeviceId: String, val dbVersion: Int, val protocol: Int = SyncProtocol.VERSION)

/** [nextSeq] = পরের পুলে দেওয়ার cursor; [more] = আরও আছে। */
@Serializable
data class PullResponse(val rows: List<SyncRowDto>, val nextSeq: Long, val more: Boolean)

@Serializable
data class PushRequest(val senderDeviceId: String, val rows: List<SyncRowDto>, val dbVersion: Int, val protocol: Int = SyncProtocol.VERSION)

/** [conflicts] = বসানো যায়নি এমন রো (যেমন একই ইউনিক মান দুই ফোনে আলাদা রো-তে)। */
@Serializable
data class PushResponse(val applied: Int, val skipped: Int, val conflicts: List<String>)
