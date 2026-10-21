package com.footagevault.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.footagevault.app.MainActivity
import com.footagevault.app.R

object Notify {
    const val CH_PROGRESS = "backup_progress"
    const val CH_EVENTS = "backup_events"
    const val ID_PROGRESS = 1
    private const val ID_SAFE = 2
    private const val ID_SIGNIN = 3
    private const val ID_PROBLEM = 4

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Backing up", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_EVENTS, "Backup updates", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun open(ctx: Context, screen: String? = null) = PendingIntent.getActivity(
        ctx, screen.hashCode(), Intent(ctx, MainActivity::class.java).putExtra("screen", screen)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(ctx: Context, title: String, text: String, pct: Int) =
        NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle(title).setContentText(text)
            .setProgress(100, pct, pct <= 0)
            .setOngoing(true).setSilent(true)
            .setContentIntent(open(ctx))
            .build()

    private fun post(ctx: Context, id: Int, title: String, text: String, screen: String?) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(ctx).notify(id, NotificationCompat.Builder(ctx, CH_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(open(ctx, screen)).build())
    }

    fun safeToDelete(ctx: Context, n: Int, size: String) =
        post(ctx, ID_SAFE, "Free up $size", "$n video${if (n == 1) " is" else "s are"} safely backed up to YouTube. Tap to remove them from your phone.", "free")

    fun signInNeeded(ctx: Context) =
        post(ctx, ID_SIGNIN, "Backup paused", "Sign in to YouTube again to keep backing up.", null)

    fun problem(ctx: Context, n: Int) =
        post(ctx, ID_PROBLEM, "Upload problem", "$n video${if (n == 1) "" else "s"} couldn't be backed up. Tap to see why.", "problems")
}
