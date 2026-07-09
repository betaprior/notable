package com.ethran.notable.ink

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Control-channel client for the inkhub session broker running on the laptop.
 *
 * One request per connection, newline-delimited JSON:
 *   -> {"v":1,"type":"open_doc","doc":"/notes/foo.xoj"}
 *   <- {"type":"ok","udp_port":5556,"local_path":"..."} | {"type":"error","error":"..."}
 *
 * The hub maps the Dropbox path to its local file, finds or spawns the
 * xournal instance for it, ensures it is listening for ink, and returns the
 * UDP port to stream to.
 */
object InkHubClient {
    private val json = Json { ignoreUnknownKeys = true }

    sealed class OpenResult {
        /** [token] is the per-session UDP auth token (16 hex chars), if the hub issued one. */
        data class Ok(val udpPort: Int, val token: String) : OpenResult()
        data class Error(val message: String) : OpenResult()
    }

    suspend fun openDoc(
        host: String, hubPort: Int, dropboxPath: String, secret: String = "",
    ): OpenResult =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, hubPort), CONNECT_TIMEOUT_MS)
                    // generous read timeout: the hub may need to spawn xournal
                    s.soTimeout = READ_TIMEOUT_MS
                    val doc = json.encodeToString(String.serializer(), dropboxPath)
                    val sec = if (secret.isBlank()) ""
                    else ""","secret":${json.encodeToString(String.serializer(), secret)}"""
                    val req = """{"v":1,"type":"open_doc","doc":$doc$sec}""" + "\n"
                    s.getOutputStream().let { it.write(req.toByteArray()); it.flush() }
                    val line = BufferedReader(InputStreamReader(s.getInputStream())).readLine()
                        ?: return@withContext OpenResult.Error("hub closed the connection")
                    val obj = json.parseToJsonElement(line).jsonObject
                    if (obj["type"]?.jsonPrimitive?.content == "ok") {
                        val port = obj["udp_port"]?.jsonPrimitive?.int
                            ?: return@withContext OpenResult.Error("hub reply missing udp_port")
                        OpenResult.Ok(port, obj["token"]?.jsonPrimitive?.content ?: "")
                    } else {
                        OpenResult.Error(
                            obj["error"]?.jsonPrimitive?.content ?: "unknown hub error"
                        )
                    }
                }
            } catch (e: Exception) {
                OpenResult.Error(e.message ?: e.javaClass.simpleName)
            }
        }

    /**
     * Register this device's FCM token with the hub so the laptop's "Stream from
     * tablet" can push a wake here. One request per connection, same as openDoc.
     * Returns true on an "ok" reply.
     */
    suspend fun registerPush(
        host: String, hubPort: Int, token: String, secret: String = "",
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, hubPort), CONNECT_TIMEOUT_MS)
                    s.soTimeout = CONNECT_TIMEOUT_MS
                    val tk = json.encodeToString(String.serializer(), token)
                    val sec = if (secret.isBlank()) ""
                    else ""","secret":${json.encodeToString(String.serializer(), secret)}"""
                    val req = """{"v":1,"type":"register_push","token":$tk$sec}""" + "\n"
                    s.getOutputStream().let { it.write(req.toByteArray()); it.flush() }
                    val line = BufferedReader(InputStreamReader(s.getInputStream())).readLine()
                        ?: return@withContext false
                    json.parseToJsonElement(line).jsonObject["type"]
                        ?.jsonPrimitive?.content == "ok"
                }
            } catch (e: Exception) {
                false
            }
        }

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 30_000
}
