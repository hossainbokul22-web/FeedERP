package com.feederp.sync

import android.util.Base64
import com.feederp.core.DeviceIdProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

data class SyncResult(
    val pushedRows: Int = 0,
    val pulledRows: Int = 0,
    val skipped: Int = 0,
    val conflicts: List<String> = emptyList(),
)

/** সিঙ্ক ব্যর্থতা; বার্তা সরাসরি ব্যবহারকারীকে দেখানোর মতো বাংলায়। */
class SyncException(message: String) : Exception(message)

/**
 * স্পোক ফোনের ক্লায়েন্ট: আগে নিজের পরিবর্তন হাবে পাঠায় (push), তারপর হাবের নতুন পরিবর্তন নেয় (pull)।
 * cursor প্রতিটি পাতা সফল হওয়ার পরেই এগোয়, তাই মাঝপথে ব্যর্থ হলে পরের বার সেখান থেকেই চলে (রো বসানো idempotent)।
 */
@Singleton
class SyncClient @Inject constructor(
    private val store: SyncStore,
    private val device: DeviceIdProvider,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun newHttp() = HttpClient(CIO) {
        install(HttpTimeout) { connectTimeoutMillis = 6_000; requestTimeoutMillis = 60_000; socketTimeoutMillis = 60_000 }
        expectSuccess = false
    }

    /** হাব খুঁজে পেলে তার পরিচয় (কোড লাগে না)। ঠিকানা ভুল/নাগালের বাইরে হলে [SyncException]। */
    suspend fun hello(host: String, port: Int): SyncHello {
        val http = newHttp()
        try {
            val r = http.get("http://$host:$port/hello")
            if (r.status != HttpStatusCode.OK) throw SyncException("হাব সাড়া দিয়েছে কিন্তু ত্রুটি (${r.status.value})")
            return json.decodeFromString(SyncHello.serializer(), r.bodyAsText())
        } catch (e: SyncException) {
            throw e
        } catch (e: Exception) {
            throw SyncException("হাবে পৌঁছানো যায়নি ($host:$port) — দুই ফোন একই Wi-Fi এ আছে কিনা এবং হাবে সার্ভার চালু আছে কিনা দেখুন")
        } finally {
            http.close()
        }
    }

    suspend fun sync(p: SyncSettings.Pairing): SyncResult {
        store.ensureSetup()
        val http = newHttp()
        try {
            val hello = hello(p.host, p.port)
            if (p.hubDeviceId.isNotEmpty() && hello.hubDeviceId != p.hubDeviceId) {
                throw SyncException("এই ঠিকানায় অন্য একটি হাব পাওয়া গেছে; আবার পেয়ার করুন")
            }
            if (hello.protocol != SyncProtocol.VERSION || hello.dbVersion != store.dbVersion) {
                throw SyncException("অ্যাপের সংস্করণ মেলেনি (হাব: v${hello.dbVersion}, এই ফোন: v${store.dbVersion}); দুই ফোনেই একই সংস্করণ ইনস্টল করুন")
            }
            val hubId = hello.hubDeviceId
            val me = device.deviceId
            var pushed = 0; var pulled = 0; var skipped = 0
            val conflicts = ArrayList<String>()

            // ১) পুশ
            var pushCursor = store.getMeta("push_seq:$hubId")?.toLongOrNull() ?: 0L
            while (true) {
                val ch = store.collectChanges(pushCursor, excludeAuthorDeviceId = hubId)
                if (ch.rows.isNotEmpty()) {
                    val req = PushRequest(me, ch.rows, store.dbVersion)
                    val res = json.decodeFromString(
                        PushResponse.serializer(),
                        call(http, p, "/push", json.encodeToString(PushRequest.serializer(), req)),
                    )
                    pushed += res.applied; skipped += res.skipped; conflicts += res.conflicts
                }
                pushCursor = ch.nextSeq
                store.setMeta("push_seq:$hubId", pushCursor.toString())
                if (!ch.more) break
            }

            // ২) পুল
            var pullCursor = store.getMeta("pull_seq:$hubId")?.toLongOrNull() ?: 0L
            while (true) {
                val req = PullRequest(pullCursor, me, store.dbVersion)
                val res = json.decodeFromString(
                    PullResponse.serializer(),
                    call(http, p, "/pull", json.encodeToString(PullRequest.serializer(), req)),
                )
                if (res.rows.isNotEmpty()) {
                    val applied = store.applyRows(res.rows)
                    pulled += applied.applied; skipped += applied.skipped; conflicts += applied.conflicts
                }
                pullCursor = res.nextSeq
                store.setMeta("pull_seq:$hubId", pullCursor.toString())
                if (!res.more) break
            }
            store.recordConflicts(conflicts)
            return SyncResult(pushed, pulled, skipped, conflicts)
        } finally {
            http.close()
        }
    }

    private suspend fun call(http: HttpClient, p: SyncSettings.Pairing, path: String, plain: String): String {
        val aad = path.toByteArray()
        val body = Base64.encodeToString(SyncCrypto.encrypt(p.key, plain.toByteArray(Charsets.UTF_8), aad), Base64.NO_WRAP)
        val r = try {
            http.post("http://${p.host}:${p.port}$path") { setBody(body) }
        } catch (e: Exception) {
            throw SyncException("সিঙ্কের মাঝে হাবের সংযোগ ছিঁড়ে গেছে — আবার চেষ্টা করুন (যতটুকু হয়েছে তা সংরক্ষিত)")
        }
        val text = r.bodyAsText()
        return when (r.status) {
            HttpStatusCode.OK -> try {
                String(SyncCrypto.decrypt(p.key, Base64.decode(text.trim(), Base64.NO_WRAP), aad), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                throw SyncException("হাবের উত্তর পড়া যায়নি — পেয়ারিং কোড বদলে থাকতে পারে, আবার পেয়ার করুন")
            }
            HttpStatusCode.Forbidden -> throw SyncException("পেয়ারিং কোড ভুল বা হাবে কোড বদলানো হয়েছে; আবার পেয়ার করুন")
            else -> throw SyncException(text.ifBlank { "হাবে ত্রুটি (${r.status.value})" })
        }
    }
}
