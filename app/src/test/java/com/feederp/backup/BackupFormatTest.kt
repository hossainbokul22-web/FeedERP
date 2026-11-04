package com.feederp.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupFormatTest {
    private val meta = BackupMeta(schemaVersion = 7, createdAt = 1_700_000_000_000L, deviceCode = "S1")
    private val key = ByteArray(32) { it.toByte() }
    private val db = ByteArray(5000) { (it * 31).toByte() }

    @Test fun roundTrip() {
        val f = BackupFormat.seal("pass-word-1", meta, key, db)
        val c = BackupFormat.open("pass-word-1", f)
        assertEquals(meta, c.meta)
        assertArrayEquals(key, c.dbKey)
        assertArrayEquals(db, c.db)
    }

    @Test fun wrongPassphraseRejected() {
        val f = BackupFormat.seal("pass-word-1", meta, key, db)
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.open("pass-word-2", f) }
    }

    @Test fun tamperedFileRejected() {
        val f = BackupFormat.seal("pass-word-1", meta, key, db)
        f[f.size - 10] = (f[f.size - 10].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.open("pass-word-1", f) }
    }

    @Test fun foreignFileRejected() {
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.open("pass-word-1", ByteArray(500) { 7 }) }
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.open("pass-word-1", ByteArray(10)) }
    }

    @Test fun weakPassphraseRejected() {
        assertNotNull(BackupFormat.passphraseError("short"))
        assertNotNull(BackupFormat.passphraseError("12345678"))
        assertNull(BackupFormat.passphraseError("bangla-feed-9"))
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.seal("abc", meta, key, db) }
    }

    @Test fun badInputsRejectedOnSeal() {
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.seal("pass-word-1", meta, ByteArray(16), db) }
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.seal("pass-word-1", meta, key, ByteArray(0)) }
    }

    @Test fun twoSealsDiffer() {
        val a = BackupFormat.seal("pass-word-1", meta, key, db)
        val b = BackupFormat.seal("pass-word-1", meta, key, db)
        assert(!a.contentEquals(b))   // নতুন লবণ ও নন্স
    }
}
