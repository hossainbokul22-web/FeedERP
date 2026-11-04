package com.feederp.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

object PinHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    fun newSalt(): String {
        val b = ByteArray(16); SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }

    fun hash(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), Base64.decode(salt, Base64.NO_WRAP), ITERATIONS, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun verify(pin: String, salt: String, expectedHash: String): Boolean =
        MessageDigest.isEqual(hash(pin, salt).toByteArray(), expectedHash.toByteArray())
}

/**
 * ডাটাবেসের এনক্রিপশন কী: প্রথম চালুতে র‍্যান্ডম ৩২ বাইট তৈরি হয়, Android Keystore এর AES-GCM কী দিয়ে
 * মুড়ে SharedPreferences এ রাখা হয়। ফোন ছাড়া ডাটাবেস ফাইল খুলে পড়া যায় না।
 */
@Singleton
class DbKeyProvider @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("dbkey", Context.MODE_PRIVATE)
    private val alias = "feederp_db_wrap_key"

    fun passphrase(): ByteArray {
        val stored = prefs.getString("wrapped", null)
        if (stored != null) return unwrap(stored)
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("wrapped", wrap(raw)).apply()
        return raw
    }

    /**
     * ব্যাকআপ রিস্টোরের সময় ডাটাবেসের কী বদলানো। আগের মোড়ানো কী "wrapped_prev"-এ রাখা হয় (হাতে উদ্ধারের জন্য)।
     * commit() ব্যবহার করা হয় যাতে অ্যাপ বন্ধ হওয়ার আগেই লেখা শেষ হয়।
     */
    fun replacePassphrase(raw: ByteArray) {
        require(raw.size == 32) { "ডাটাবেস কী ৩২ বাইটের হতে হবে" }
        val old = prefs.getString("wrapped", null)
        val ok = prefs.edit().putString("wrapped_prev", old).putString("wrapped", wrap(raw)).commit()
        check(ok) { "নতুন কী সংরক্ষণ করা যায়নি" }
    }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun wrap(raw: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        val out = c.iv + c.doFinal(raw)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun unwrap(encoded: String): ByteArray {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        val body = all.copyOfRange(12, all.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, iv)) }
        return c.doFinal(body)
    }
}
