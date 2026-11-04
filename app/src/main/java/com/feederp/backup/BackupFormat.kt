package com.feederp.backup

import com.feederp.sync.SyncCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class BackupMeta(val schemaVersion: Int, val createdAt: Long, val deviceCode: String)

class BackupContents(val meta: BackupMeta, val dbKey: ByteArray, val db: ByteArray)

/**
 * ব্যাকআপ ফাইলের বিশুদ্ধ ফরম্যাট (Android ছাড়া, টেস্টযোগ্য)।
 * ফাইল = "FEBK1" + লবণ(১৬) + AES-256-GCM(নন্স+সাইফারটেক্সট); কী = PBKDF2(ব্যবহারকারীর পাসফ্রেজ, লবণ)।
 * ভেতরে (এনক্রিপ্টেড): স্কিমা ভার্সন, তৈরির সময়, ডিভাইস কোড, ডাটাবেস কী (৩২ বাইট) ও ডাটাবেস ফাইল।
 * ডাটাবেস কী ভেতরে রাখা হয় কারণ ওটা ফোনের Keystore-এ বাঁধা; নইলে নতুন ফোনে ব্যাকআপ খোলা যেত না।
 * পাসফ্রেজ হারালে ব্যাকআপ উদ্ধারের কোনো উপায় নেই। পুরো ফাইল মেমরিতে লোড হয়, তাই [MAX_DB_BYTES] সীমা আছে।
 */
object BackupFormat {
    private val MAGIC = "FEBK1".toByteArray(Charsets.US_ASCII)
    const val MIN_PASSPHRASE = 8
    const val MAX_DB_BYTES = 300L * 1024 * 1024
    private const val ITERATIONS = 200_000
    private const val SALT_BYTES = 16
    private val random = SecureRandom()

    fun passphraseError(p: String): String? = when {
        p.length < MIN_PASSPHRASE -> "পাসফ্রেজ কমপক্ষে $MIN_PASSPHRASE অক্ষরের হতে হবে"
        p.all { it.isDigit() } -> "শুধু সংখ্যার পাসফ্রেজ দুর্বল; অক্ষর মেশান"
        else -> null
    }

    private fun deriveKey(pass: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pass.toCharArray(), salt, ITERATIONS, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun seal(passphrase: String, meta: BackupMeta, dbKey: ByteArray, db: ByteArray, salt: ByteArray = ByteArray(SALT_BYTES).also { random.nextBytes(it) }): ByteArray {
        passphraseError(passphrase)?.let { throw IllegalArgumentException(it) }
        require(dbKey.size == 32) { "ডাটাবেস কী ৩২ বাইটের হতে হবে" }
        require(db.isNotEmpty()) { "ডাটাবেস ফাইল খালি" }
        require(db.size <= MAX_DB_BYTES) { "ডাটাবেস ${MAX_DB_BYTES / 1024 / 1024} MB-এর বেশি; এই পদ্ধতিতে ব্যাকআপ সম্ভব নয়" }
        val buf = ByteArrayOutputStream(db.size + 256)
        DataOutputStream(buf).use {
            it.writeInt(meta.schemaVersion); it.writeLong(meta.createdAt); it.writeUTF(meta.deviceCode)
            it.writeInt(dbKey.size); it.write(dbKey)
            it.writeLong(db.size.toLong()); it.write(db)
        }
        val key = deriveKey(passphrase, salt)
        return MAGIC + salt + SyncCrypto.encrypt(key, buf.toByteArray(), MAGIC)
    }

    /** ভুল পাসফ্রেজ, বদলানো/নষ্ট ফাইল, বা অন্য ফরম্যাট হলে [IllegalArgumentException] (বাংলা বার্তা)। */
    fun open(passphrase: String, file: ByteArray): BackupContents {
        require(file.size > MAGIC.size + SALT_BYTES + 64) { "এটি ব্যাকআপ ফাইল নয় (খুব ছোট)" }
        require(file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "এটি এই অ্যাপের ব্যাকআপ ফাইল নয়" }
        val salt = file.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
        val blob = file.copyOfRange(MAGIC.size + SALT_BYTES, file.size)
        val plain = try {
            SyncCrypto.decrypt(deriveKey(passphrase, salt), blob, MAGIC)
        } catch (e: Exception) {
            throw IllegalArgumentException("পাসফ্রেজ ভুল, অথবা ফাইলটি নষ্ট/বদলানো")
        }
        try {
            DataInputStream(ByteArrayInputStream(plain)).use {
                val version = it.readInt(); val at = it.readLong(); val dev = it.readUTF()
                val kl = it.readInt(); require(kl == 32) { "কী ঠিক নয়" }
                val k = ByteArray(kl); it.readFully(k)
                val dl = it.readLong(); require(dl in 1..MAX_DB_BYTES && dl <= plain.size) { "ডাটাবেসের আকার ঠিক নয়" }
                val d = ByteArray(dl.toInt()); it.readFully(d)
                return BackupContents(BackupMeta(version, at, dev), k, d)
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("ব্যাকআপ ফাইলের গঠন ঠিক নয়")
        }
    }
}
