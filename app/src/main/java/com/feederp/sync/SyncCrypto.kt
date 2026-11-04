package com.feederp.sync

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * লোকাল Wi-Fi এ সব বার্তা পেয়ারিং কোড থেকে বানানো কী দিয়ে AES-256-GCM এনক্রিপ্ট হয়।
 * ভুল কোড হলে ডিক্রিপ্ট ব্যর্থ হয় (GCM ট্যাগ মেলে না), তাই আলাদা পাসওয়ার্ড-যাচাই লাগে না।
 * সীমা: কোডের শক্তি ~৫০ বিট; কেউ হাবের ট্রাফিক রেকর্ড করে অফলাইনে অনুমান চালাতে পারে (PBKDF2 তা ধীর করে, বন্ধ করে না)।
 * তাই কোড অন্যকে বলবেন না এবং সন্দেহ হলে হাবে "নতুন কোড" নিন (সব ফোন আবার পেয়ার করতে হবে)।
 */
object SyncCrypto {
    private const val ITERATIONS = 120_000
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"   // O/0, I/1 বাদ
    private val random = SecureRandom()

    /** ১০ অক্ষরের কোড, পড়ার সুবিধায় "ABCDE-FGHJK" আকারে (৩২^১০ = ৫০ বিট)। */
    fun generateCode(): String {
        val s = CharArray(10) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return String(s, 0, 5) + "-" + String(s, 5, 5)
    }

    /** ইউজারের টাইপ করা কোড স্বাভাবিক রূপে (বড় হাতের, ড্যাশ/স্পেস বাদ)। */
    fun normalizeCode(input: String): String = input.uppercase().filter { it in ALPHABET }

    fun isValidCode(input: String): Boolean = normalizeCode(input).length == 10

    fun newSalt(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    fun deriveKey(code: String, salt: ByteArray): ByteArray {
        val norm = normalizeCode(code)
        require(norm.length == 10) { "পেয়ারিং কোড ১০ অক্ষরের হতে হবে" }
        val spec = PBEKeySpec(norm.toCharArray(), salt, ITERATIONS, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** ফলাফল = নন্স(১২ বাইট) + সাইফারটেক্সট। [aad] (যেমন অনুরোধের পথ) বাঁধা থাকে, বদলালে ডিক্রিপ্ট হয় না। */
    fun encrypt(key: ByteArray, plain: ByteArray, aad: ByteArray = ByteArray(0), nonce: ByteArray = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }): ByteArray {
        require(nonce.size == NONCE_BYTES) { "নন্স ১২ বাইট হতে হবে" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(aad)
        return nonce + c.doFinal(plain)
    }

    /** ভুল কী/বদলানো বার্তায় [IllegalArgumentException] (বাংলা বার্তা)। */
    fun decrypt(key: ByteArray, blob: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(blob.size > NONCE_BYTES + TAG_BITS / 8) { "বার্তা অসম্পূর্ণ" }
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, NONCE_BYTES)))
            c.updateAAD(aad)
            return c.doFinal(blob, NONCE_BYTES, blob.size - NONCE_BYTES)
        } catch (e: java.security.GeneralSecurityException) {
            throw IllegalArgumentException("বার্তা পড়া যায়নি — পেয়ারিং কোড ভুল অথবা বার্তা বদলে গেছে")
        }
    }
}
