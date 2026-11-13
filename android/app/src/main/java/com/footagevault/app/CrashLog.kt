package com.footagevault.app

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.ZonedDateTime

/**
 * When the app crashes, what went wrong is saved to a file. On the next start the app offers to send it
 * (WhatsApp, email…), so a crash can be reported without a computer or a cable.
 */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "last-crash.txt")

    /** Call once at start-up. Saves the crash, then lets Android close the app as usual. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { file(app).writeText(report(app, thread, e)) }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun report(ctx: Context, thread: Thread, e: Throwable): String {
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        val version = runCatching {
            val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${p.versionName} (${if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()})"
        }.getOrDefault("?")
        return buildString {
            appendLine("Footage Vault crash report")
            appendLine("When: ${ZonedDateTime.now()}")
            appendLine("App: $version")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(trace.take(60_000))  // keeps the share small enough for any app
        }
    }

    /** The saved report from the last crash, or null. */
    fun last(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(ctx: Context) { file(ctx).delete() }

    /** Opens the share sheet with the report as text. */
    fun share(ctx: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Footage Vault crash report")
            .putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(send, "Send crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
