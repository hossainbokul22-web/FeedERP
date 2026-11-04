package com.feederp.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCoreTest {
    @Test fun classifyByColumns() {
        assertEquals(TableKind.MUTABLE, SyncTables.classify("items", listOf("id", "name", "updatedAt", "deviceId")))
        assertEquals(TableKind.APPEND_ONLY, SyncTables.classify("stock_movements", listOf("id", "createdAt")))
        assertEquals(TableKind.LOCAL, SyncTables.classify("local_gps_points", listOf("id", "createdAt")))
        assertEquals(TableKind.LOCAL, SyncTables.classify("number_counters", listOf("key", "last")))
        assertEquals(TableKind.LOCAL, SyncTables.classify("no_id", listOf("a", "updatedAt")))
        assertEquals(TableKind.LOCAL, SyncTables.classify("room_master_table", listOf("id", "updatedAt")))
        assertEquals(TableKind.LOCAL, SyncTables.classify("x; DROP TABLE y", listOf("id", "updatedAt")))
        // updatedAt থাকলে সেটাই অগ্রাধিকার (createdAt সহ হলেও)
        assertEquals(TableKind.MUTABLE, SyncTables.classify("t", listOf("id", "createdAt", "updatedAt")))
    }

    @Test fun lastWriterWinsAndTieBreaksByDevice() {
        val k = TableKind.MUTABLE
        assertTrue(SyncMerge.shouldApply(k, false, null, null, 5, "A"))
        assertTrue(SyncMerge.shouldApply(k, true, 5, "A", 6, "B"))
        assertFalse(SyncMerge.shouldApply(k, true, 6, "A", 5, "B"))
        // সমান সময়: বড় deviceId জেতে — দুই পাশে একই সিদ্ধান্ত
        assertTrue(SyncMerge.shouldApply(k, true, 5, "A", 5, "B"))
        assertFalse(SyncMerge.shouldApply(k, true, 5, "B", 5, "A"))
        assertFalse(SyncMerge.shouldApply(k, true, 5, "A", 5, "A"))
        assertFalse(SyncMerge.shouldApply(k, true, 5, "A", null, "B"))
        assertTrue(SyncMerge.shouldApply(k, true, null, null, 1, "B"))
    }

    @Test fun appendOnlyNeverOverwritesAndLocalNeverApplies() {
        assertTrue(SyncMerge.shouldApply(TableKind.APPEND_ONLY, false, null, null, null, null))
        assertFalse(SyncMerge.shouldApply(TableKind.APPEND_ONLY, true, null, null, 99, "Z"))
        assertFalse(SyncMerge.shouldApply(TableKind.LOCAL, false, null, null, 1, "A"))
    }

    @Test fun mergeIsSymmetricSoBothPhonesConverge() {
        // দুই ফোনে একই রো-র দুই সংস্করণ; যে-ই কাকে পাঠাক, শেষে একই সংস্করণ থাকে
        val a = 10L to "A"; val b = 10L to "B"
        val aKeepsA = !SyncMerge.shouldApply(TableKind.MUTABLE, true, a.first, a.second, b.first, b.second)
        val bTakesA = SyncMerge.shouldApply(TableKind.MUTABLE, true, b.first, b.second, a.first, a.second)
        assertFalse(aKeepsA)   // A নিজেরটা ছেড়ে B-র নেয়
        assertFalse(bTakesA)   // B নিজেরটাই রাখে
    }

    @Test fun sqlBuilders() {
        val tr = SyncSql.triggers("sales_invoices")
        assertEquals(2, tr.size)
        assertTrue(tr[0].contains("AFTER INSERT ON sales_invoices") && tr[1].contains("AFTER UPDATE ON sales_invoices"))
        assertEquals("SELECT * FROM items WHERE id = ?", SyncSql.selectRow("items"))
        assertEquals(
            "INSERT OR REPLACE INTO items (id, updatedAt) VALUES (?, ?)",
            SyncSql.insert("items", listOf("id", "updatedAt"), replace = true),
        )
        assertEquals("INSERT INTO items (id) VALUES (?)", SyncSql.insert("items", listOf("id"), replace = false))
    }

    @Test fun sqlBuildersRejectInjection() {
        assertThrows(IllegalArgumentException::class.java) { SyncSql.triggers("a; DROP TABLE b") }
        assertThrows(IllegalArgumentException::class.java) { SyncSql.selectRow("items WHERE 1=1 --") }
        assertThrows(IllegalArgumentException::class.java) { SyncSql.insert("items", listOf("id) VALUES (1); --"), true) }
        assertThrows(IllegalArgumentException::class.java) { SyncSql.insert("items", emptyList(), true) }
    }

    @Test fun rowCodecRoundTripKeepsTypes() {
        val row = mapOf<String, Any?>("id" to "abc", "qty" to 12.5, "qtyWhole" to 5.0, "paisa" to 123456789012L, "flag" to true, "n" to null, "i" to 3)
        val text = Json.encodeToString(JsonObject.serializer(), RowCodec.toJson(row))
        val back = RowCodec.fromJson(Json.parseToJsonElement(text) as JsonObject)
        assertEquals("abc", back["id"])
        assertEquals(12.5, back["qty"])
        assertEquals(123456789012L, back["paisa"])
        assertEquals(1L, back["flag"])
        assertNull(back["n"])
        assertEquals(3L, back["i"])
        // 5.0 দশমিকসহ লেখা হয় তাই Double-ই থাকে (REAL কলাম Long হয়ে যায় না)
        assertEquals(5.0, back["qtyWhole"])
    }

    @Test fun rowCodecRejectsNonFiniteAndUnknownTypes() {
        assertThrows(IllegalArgumentException::class.java) { RowCodec.toJson(mapOf("x" to Double.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { RowCodec.toJson(mapOf("x" to Double.POSITIVE_INFINITY)) }
        assertThrows(IllegalArgumentException::class.java) { RowCodec.toJson(mapOf("x" to byteArrayOf(1))) }
    }

    @Test fun stringThatLooksLikeNumberStaysString() {
        val back = RowCodec.fromJson(Json.parseToJsonElement("""{"phone":"01712345678","n":1712345678}""") as JsonObject)
        assertEquals("01712345678", back["phone"])
        assertEquals(1712345678L, back["n"])
    }
}
