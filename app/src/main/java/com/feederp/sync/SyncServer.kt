package com.feederp.sync

import android.util.Base64
import com.feederp.core.DeviceIdProvider
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * হাব ফোনে চলা ছোট HTTP সার্ভার। `/hello` খোলা (শুধু নাম, salt, সংস্করণ — গোপন কিছু নেই);
 * `/pull` ও `/push` এর বডি পুরোটা AES-GCM এনক্রিপ্টেড; কোড ভুল হলে ডিক্রিপ্ট ব্যর্থ → ৪০৩।
 * একসাথে একটি সিঙ্ক-অনুরোধ চলে (Mutex), তাই দুই স্পোক একই সময়ে লিখলেও ডাটাবেস ঠিক থাকে।
 */
@Singleton
class SyncServer @Inject constructor(
    private val store: SyncStore,
    private val settings: SyncSettings,
    private val device: DeviceIdProvider,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var engine: ApplicationEngine? = null

    val isRunning: Boolean get() = engine != null

    @Synchronized
    fun start() {
        if (engine != null) return
        val key = settings.hubKey()
        engine = embeddedServer(CIO, port = settings.hubPort, host = "0.0.0.0") {
            routing {
                get("/hello") {
                    val hello = SyncHello(
                        hubName = settings.hubName, hubDeviceId = device.deviceId,
                        saltB64 = Base64.encodeToString(settings.hubSalt, Base64.NO_WRAP),
                        protocol = SyncProtocol.VERSION, dbVersion = store.dbVersion,
                    )
                    call.respondText(json.encodeToString(SyncHello.serializer(), hello), ContentType.Application.Json)
                }
                post("/pull") {
                    handle(call.receiveText(), "/pull", key) { plain ->
                        val req = json.decodeFromString(PullRequest.serializer(), plain)
                        checkVersion(req.protocol, req.dbVersion)
                        val ch = store.collectChanges(req.afterSeq, excludeAuthorDeviceId = req.requesterDeviceId)
                        json.encodeToString(PullResponse.serializer(), PullResponse(ch.rows, ch.nextSeq, ch.more))
                    }.let { (status, body) -> call.respondText(body, ContentType.Text.Plain, status) }
                }
                post("/push") {
                    handle(call.receiveText(), "/push", key) { plain ->
                        val req = json.decodeFromString(PushRequest.serializer(), plain)
                        checkVersion(req.protocol, req.dbVersion)
                        json.encodeToString(PushResponse.serializer(), store.applyRows(req.rows))
                    }.let { (status, body) -> call.respondText(body, ContentType.Text.Plain, status) }
                }
            }
        }.also { it.start(wait = false) }
    }

    @Synchronized
    fun stop() {
        engine?.stop(500, 1500)
        engine = null
    }

    private fun checkVersion(protocol: Int, dbVersion: Int) {
        if (protocol != SyncProtocol.VERSION || dbVersion != store.dbVersion) {
            throw VersionMismatch("অ্যাপের সংস্করণ মেলেনি (হাব: ডাটাবেস v${store.dbVersion}, আপনার: v$dbVersion); দুই ফোনেই একই সংস্করণ ইনস্টল করুন")
        }
    }

    private class VersionMismatch(msg: String) : Exception(msg)

    private suspend fun handle(
        body: String, path: String, key: ByteArray, work: suspend (String) -> String,
    ): Pair<HttpStatusCode, String> {
        val aad = path.toByteArray()
        val plain = try {
            String(SyncCrypto.decrypt(key, Base64.decode(body.trim(), Base64.NO_WRAP), aad), Charsets.UTF_8)
        } catch (e: Exception) {
            return HttpStatusCode.Forbidden to "পেয়ারিং কোড ভুল"
        }
        return try {
            val out = lock.withLock { work(plain) }
            HttpStatusCode.OK to Base64.encodeToString(SyncCrypto.encrypt(key, out.toByteArray(Charsets.UTF_8), aad), Base64.NO_WRAP)
        } catch (e: VersionMismatch) {
            HttpStatusCode.Conflict to (e.message ?: "সংস্করণ মেলেনি")
        } catch (e: Exception) {
            HttpStatusCode.InternalServerError to "হাবে ত্রুটি: ${e.message ?: e::class.simpleName}"
        }
    }
}
