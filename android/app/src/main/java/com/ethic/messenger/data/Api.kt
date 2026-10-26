package com.ethic.messenger.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiError(message: String, val status: Int = 0) : Exception(message)

object Api {
    val BASE: String = com.ethic.messenger.BuildConfig.API_BASE
    val WS = BASE.replaceFirst("https", "wss") + "/ws"

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    @Volatile var token: String? = null

    fun abs(url: String?): String? = when {
        url == null -> null
        url.startsWith("http") || url.startsWith("content:") || url.startsWith("file:") -> url
        else -> BASE + url
    }

    private val JSON_TYPE = "application/json".toMediaType()

    suspend fun call(path: String, method: String = "GET", body: Map<String, Any?>? = null): JsonObject =
        withContext(Dispatchers.IO) {
            val rb = body?.let { json.encodeToString(JsonObject.serializer(), toJson(it) as JsonObject).toRequestBody(JSON_TYPE) }
            val req = Request.Builder().url(BASE + path).apply {
                token?.let { header("Authorization", "Bearer $it") }
                method(method, rb ?: if (method == "GET" || method == "DELETE") null else "{}".toRequestBody(JSON_TYPE))
            }.build()
            val res = try { http.newCall(req).execute() } catch (e: IOException) { throw ApiError("Нет соединения с сервером") }
            res.use { r ->
                val text = r.body.string()
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
                if (!r.isSuccessful) {
                    val msg = (obj["error"] as? JsonPrimitive)?.content ?: "Ошибка ${r.code}"
                    if (r.code == 401 && token != null && path != "/api/login") Repo.onUnauthorized()
                    throw ApiError(msg, r.code)
                }
                obj
            }
        }

    inline fun <reified T> JsonObject.field(name: String): T = json.decodeFromJsonElement(this[name]!!)

    /** Streams a file to /api/upload. Returns (url, size, ext). */
    suspend fun upload(
        ctx: Context,
        uri: Uri,
        kind: String,
        name: String,
        onProgress: (Float) -> Unit = {},
    ): Triple<String, Long, String?> = withContext(Dispatchers.IO) {
        val total = runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        val body = object : RequestBody() {
            override fun contentType(): MediaType = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = total
            override fun writeTo(sink: BufferedSink) {
                val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Не удалось открыть файл")
                input.source().use { src ->
                    var sent = 0L
                    while (true) {
                        val n = src.read(sink.buffer, 64 * 1024)
                        if (n == -1L) break
                        sent += n
                        sink.flush()
                        if (total > 0) onProgress(sent.toFloat() / total)
                    }
                }
            }
        }
        val q = "kind=$kind&name=" + URLEncoder.encode(name, "UTF-8")
        val req = Request.Builder().url("$BASE/api/upload?$q").header("Authorization", "Bearer $token").post(body).build()
        val res = try { http.newCall(req).execute() } catch (e: IOException) { throw ApiError("Нет соединения") }
        res.use { r ->
            val obj = runCatching { json.parseToJsonElement(r.body.string()).jsonObject }.getOrDefault(JsonObject(emptyMap()))
            if (!r.isSuccessful) throw ApiError((obj["error"] as? JsonPrimitive)?.content ?: "Ошибка ${r.code}", r.code)
            Triple(
                obj["url"]!!.jsonPrimitive.content,
                obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                (obj["ext"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            )
        }
    }

    fun toJson(v: Any?): JsonElement = when (v) {
        null -> kotlinx.serialization.json.JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
        is List<*> -> kotlinx.serialization.json.JsonArray(v.map { toJson(it) })
        else -> JsonPrimitive(v.toString())
    }
}
