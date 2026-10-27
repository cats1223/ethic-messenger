package com.ethic.messenger

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import com.ethic.messenger.data.Api
import com.ethic.messenger.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/** Checks GitHub releases for a newer APK and installs it (the app is not in Google Play). */
object Updater {
    private const val LATEST = "https://api.github.com/repos/cats1223/ethic-messenger/releases/latest"
    data class Update(val version: String, val apkUrl: String, val notes: String)

    val available = MutableStateFlow<Update?>(null)
    val downloading = MutableStateFlow(false)

    private fun parts(v: String) = v.trimStart('v').split('.').map { it.toIntOrNull() ?: 0 }
    private fun newer(a: String, b: String): Boolean {
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    fun check() = Repo.scope.launch {
        runCatching {
            val body = withContext(Dispatchers.IO) {
                Api.http.newCall(Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()).execute().use { it.body.string() }
            }
            val r = Api.json.parseToJsonElement(body).jsonObject
            val tag = r["tag_name"]!!.jsonPrimitive.content
            if (!newer(tag, BuildConfig.VERSION_NAME)) return@runCatching
            val apk = r["assets"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["name"]!!.jsonPrimitive.content.endsWith(".apk") } ?: return@runCatching
            available.value = Update(tag.trimStart('v'), apk["browser_download_url"]!!.jsonPrimitive.content, r["body"]?.jsonPrimitive?.content.orEmpty())
        }
    }

    /** Downloads the APK with the system downloader, then opens the installer. */
    fun install(ctx: Context) {
        val u = available.value ?: return
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val req = DownloadManager.Request(Uri.parse(u.apkUrl))
            .setTitle("Ethic Messenger ${u.version}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, "Ethic-Messenger-${u.version}.apk")
        val id = dm.enqueue(req)
        downloading.value = true
        Repo.scope.launch {
            while (true) {
                delay(700)
                val status = dm.query(DownloadManager.Query().setFilterById(id))?.use { c -> if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else -1 } ?: -1
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    downloading.value = false
                    val uri = dm.getUriForDownloadedFile(id) ?: break
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                    break
                }
                if (status == DownloadManager.STATUS_FAILED || status == -1) { downloading.value = false; Repo.toast("Не удалось скачать обновление"); break }
            }
        }
    }
}
