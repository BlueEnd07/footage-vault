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

    data class Update(val versionCode: Long, val name: String, val notes: String, val apkUrl: String,
                      val size: Long, val sha256: String?)

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
            val all = (0 until assets.length()).map { assets.getJSONObject(it) }
            val apk = all.firstOrNull { it.optString("name").endsWith(".apk") } ?: continue
            // GitHub adds a "digest" (sha256:...) to every asset. An APK with no checksum is not offered.
            val hash = apk.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 }
                ?: checksumAsset(all, apk.optString("name")) ?: continue
            if (best == null || code > best.versionCode)
                best = Update(code, rel.optString("name").ifBlank { "Version $code" }, rel.optString("body"),
                    apk.getString("browser_download_url"), apk.optLong("size"), hash.lowercase())
        }
        best?.takeIf { it.versionCode > installedVersion(ctx) }
    }

    /** A `<apk>.sha256` asset, if the release has one instead of GitHub's digest. */
    private fun checksumAsset(assets: List<JSONObject>, apkName: String): String? {
        val asset = assets.firstOrNull { it.optString("name") == "$apkName.sha256" } ?: return null
        val req = Request.Builder().url(asset.getString("browser_download_url")).build()
        return http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body?.string()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
                ?.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
        }
    }

    /** Downloads the APK into the app's cache and refuses it unless the checksum matches. */
    suspend fun download(ctx: Context, u: Update, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val expected = u.sha256 ?: throw IOException("This update has no checksum, so it wasn't downloaded")
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "FootageVault-${u.versionCode}.apk")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
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
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (!got.equals(expected, ignoreCase = true)) {
            out.delete()
            throw IOException("The update file is damaged or wasn't signed by the project")
        }
        out
    }

    /** Android allows this only after "Install unknown apps" is switched on for Footage Vault. */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) = ctx.startActivity(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** Checks the APK really is this app, newer, and signed with the same key, before handing it to Android. */
    fun install(ctx: Context, apk: File, u: Update) {
        val flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        val archive = ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw IOException("The update file isn't a valid app")
        if (archive.packageName != ctx.packageName) throw IOException("The update is for a different app")
        val code = archive.longVersionCode
        if (code < installedVersion(ctx)) throw IOException("The update is older than this version")
        val theirs = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.sorted().orEmpty()
        val ours = ctx.packageManager.getPackageInfo(ctx.packageName, flags).signingInfo
            ?.apkContentsSigners?.map { it.toCharsString() }?.sorted().orEmpty()
        if (theirs.isEmpty() || theirs != ours) throw IOException("The update was signed with a different key")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Suppress("unused") private fun JSONObject.str(k: String) = optString(k)
}
