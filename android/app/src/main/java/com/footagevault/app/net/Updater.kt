package com.footagevault.app.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Updates from the project's GitHub Releases. A release tagged "android-v<versionCode>"
 * with a FootageVault.apk attached is offered to every installed copy with a lower versionCode.
 * Android still shows its own "Update this app?" confirmation.
 */
object Updater {
    const val REPO = "BlueEnd07/footage-vault"
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    data class Update(val versionCode: Long, val name: String, val notes: String, val apkUrl: String, val size: Long)

    fun installedVersion(ctx: Context): Long = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
    fun installedName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** The newest release if it's newer than this app, else null. */
    suspend fun check(ctx: Context): Update? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases?per_page=10")
            .header("Accept", "application/vnd.github+json").build()
        val text = http.newCall(req).execute().use { r -> if (!r.isSuccessful) return@withContext null; r.body?.string().orEmpty() }
        val arr = org.json.JSONArray(text)
        var best: Update? = null
        for (i in 0 until arr.length()) {
            val rel = arr.getJSONObject(i)
            if (rel.optBoolean("draft") || rel.optBoolean("prerelease")) continue
            val code = Regex("""android-v(\d+)""").find(rel.optString("tag_name"))?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val assets = rel.optJSONArray("assets") ?: continue
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") } ?: continue
            if (best == null || code > best.versionCode)
                best = Update(code, rel.optString("name").ifBlank { "Version $code" }, rel.optString("body"),
                    apk.getString("browser_download_url"), apk.optLong("size"))
        }
        best?.takeIf { it.versionCode > installedVersion(ctx) }
    }

    /** Downloads the APK into the app's cache. */
    suspend fun download(ctx: Context, u: Update, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "FootageVault-${u.versionCode}.apk")
        http.newCall(Request.Builder().url(u.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed (${r.code})")
            val body = r.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: u.size
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        out
    }

    /** Android allows this only after "Install unknown apps" is switched on for Footage Vault. */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) = ctx.startActivity(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Suppress("unused") private fun JSONObject.str(k: String) = optString(k)
}
