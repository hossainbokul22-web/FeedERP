package com.feederp.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCryptoTest {
    private val salt = ByteArray(16) { it.toByte() }

    @Test fun codeFormatAndAlphabet() {
        repeat(50) {
            val c = SyncCrypto.generateCode()
            assertEquals(11, c.length)
            assertEquals('-', c[5])
            assertTrue(SyncCrypto.isValidCode(c))
            assertFalse(c.contains('O') || c.contains('0') || c.contains('I') || c.contains('1'))
        }
        assertNotEquals(SyncCrypto.generateCode(), SyncCrypto.generateCode())
    }

    @Test fun normalizeIgnoresCaseDashesAndSpaces() {
        assertEquals("ABCDEFGHJK", SyncCrypto.normalizeCode("abcde-fghjk"))
        assertEquals("ABCDEFGHJK", SyncCrypto.normalizeCode(" ABCDE FGHJK "))
        assertFalse(SyncCrypto.isValidCode("ABC"))
    }

    @Test fun sameCodeAndSaltGiveSameKeyDifferentSaltDiffers() {
        val k1 = SyncCrypto.deriveKey("ABCDE-FGHJK", salt)
        val k2 = SyncCrypto.deriveKey("abcde fghjk", salt)
        assertArrayEquals(k1, k2)
        assertEquals(32, k1.size)
        assertFalse(k1.contentEquals(SyncCrypto.deriveKey("ABCDE-FGHJK", ByteArray(16) { 9 })))
        assertFalse(k1.contentEquals(SyncCrypto.deriveKey("ABCDE-FGHJL", salt)))
        assertThrows(IllegalArgumentException::class.java) { SyncCrypto.deriveKey("SHORT", salt) }
    }

    @Test fun roundTrip() {
        val key = SyncCrypto.deriveKey("ABCDE-FGHJK", salt)
        val plain = "বিক্রয় ইনভয়েস {\"x\":1}".toByteArray()
        val blob = SyncCrypto.encrypt(key, plain, "/pull".toByteArray())
        assertArrayEquals(plain, SyncCrypto.decrypt(key, blob, "/pull".toByteArray()))
    }

    @Test fun wrongKeyOrTamperedOrWrongPathFails() {
        val key = SyncCrypto.deriveKey("ABCDE-FGHJK", salt)
        val other = SyncCrypto.deriveKey("ABCDE-FGHJL", salt)
        val blob = SyncCrypto.encrypt(key, "secret".toByteArray(), "/push".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { SyncCrypto.decrypt(other, blob, "/push".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { SyncCrypto.decrypt(key, blob, "/pull".toByteArray()) }
        val bad = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { SyncCrypto.decrypt(key, bad, "/push".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { SyncCrypto.decrypt(key, ByteArray(5)) }
    }

    @Test fun eachEncryptionUsesFreshNonce() {
        val key = SyncCrypto.deriveKey("ABCDE-FGHJK", salt)
        val a = SyncCrypto.encrypt(key, "x".toByteArray())
        val b = SyncCrypto.encrypt(key, "x".toByteArray())
        assertFalse(a.contentEquals(b))
    }
}
