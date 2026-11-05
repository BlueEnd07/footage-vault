package com.footagevault.app

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.footagevault.app.data.Db
import com.footagevault.app.data.Prefs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Screenshots of the real app on the PC (Robolectric), with made-up videos: for checking the look of a change.
 * Run: .\gradlew :app:testDebugUnitTest --tests com.footagevault.app.UiShots   →   PNGs in app/build/ui-shots
 * A plain Application is used so nothing is scheduled; nothing here touches YouTube or a real library.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xhdpi")
class UiShots {
    @get:Rule val rule = createEmptyComposeRule()
    private val ctx: Application = ApplicationProvider.getApplicationContext()
    private val out = File("build/ui-shots").apply { mkdirs() }

    private var scenario: ActivityScenario<MainActivity>? = null

    @org.junit.Before fun freshDb() {
        // Db is a singleton; each Robolectric test gets a new app, so drop the previous test's instance.
        Db::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        // Backup scheduling goes to WorkManager's test stand-in: nothing runs.
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
    }

    private fun seed(theme: String) {
        Prefs(ctx).apply { onboarded = true; signedIn = true; channelTitle = "Lokesh's Footage"; this.theme = theme; gridColumns = 4 }
        val db = Db.get(ctx)
        val hues = listOf(0xFF3B6EA8, 0xFF7A4E2D, 0xFF2E7D5B, 0xFFB5651D, 0xFF4A4E69, 0xFF9C2F44, 0xFF5E8C31, 0xFF1F5F7A, 0xFFC08A3E, 0xFF6B3E75)
        val places = listOf("Goa", null, "Hyderabad", "Coorg", null, "Mumbai")
        val day = 24 * 3600 * 1000L
        val now = System.currentTimeMillis()
        var n = 0
        for (d in 0 until 6) for (k in 0 until listOf(9, 3, 7, 2, 12, 5)[d]) {
            n++
            val thumb = File(ctx.cacheDir, "t$n.png")
            if (!thumb.exists()) thumb.outputStream().use { thumbBitmap(hues[n % hues.size], hues[(n * 3 + 1) % hues.size]).compress(Bitmap.CompressFormat.PNG, 90, it) }
            val status = when {
                d == 0 && k < 2 -> "uploading"; d == 0 && k < 5 -> "queued"; d == 0 && k == 5 -> "processing"
                d == 1 && k == 0 -> "failed"; else -> "backed_up"
            }
            db.insert(ContentValues().apply {
                put("name", "VID_$n.mp4"); put("size", 50_000_000L + n * 7_000_000L); put("duration_ms", (n * 37_000L) % 600_000 + 8_000)
                put("width", 1920); put("height", 1080); put("recorded", now - d * day - k * 600_000L)
                places[d]?.let { put("place", it) }
                put("status", status); if (status == "uploading") put("progress", 0.4f)
                if (status == "failed") put("error", "Upload kept failing (network?)")
                if (d == 0 && k == 3) { put("attempts", 1); put("error", "Network problem"); put("progress", 0.25f) }
                if (status == "backed_up") { put("yt_id", "yt$n"); put("privacy", "private") }
                put("favorite", if (n % 7 == 0) 1 else 0)
                put("on_phone", if (d >= 4) 0 else 1)  // older days: phone copy freed, thumbnail kept
                put("thumb", thumb.absolutePath)
                put("kind", if (n % 5 == 0) "photo" else "video")
            })
        }
        // Live numbers for the two uploads, as the backup job would report them.
        val t0 = now - 20_000
        for (up in db.items("status='uploading'")) {
            com.footagevault.app.work.UploadLive.start(up.id, up.size, if (up.id % 2L == 0L) up.size / 5 else 0, t0)
            com.footagevault.app.work.UploadLive.progress(up.id, (up.size * 0.4f).toLong(), t0 + 15_000)
        }
        db.addAlbum("PL1", "Goa trip 2026"); db.addAlbum("PL2", "Family")
        for (i in 10..16) db.addToAlbum("PL1", "yt$i", "x$i")
        for (i in 20..23) db.addToAlbum("PL2", "yt$i", "x$i")
    }

    /** The "uri" column is empty, so thumbnails come from the made-up files above. */
    private fun thumbBitmap(a: Long, b: Long): Bitmap {
        val bmp = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val p = Paint().apply { shader = LinearGradient(0f, 0f, 160f, 160f, a.toInt(), b.toInt(), Shader.TileMode.CLAMP) }
        Canvas(bmp).drawRect(0f, 0f, 160f, 160f, p)
        return bmp
    }

    private fun settle() { repeat(30) { Thread.sleep(40); rule.waitForIdle() } }

    private fun shot(name: String) {
        settle()
        scenario!!.onActivity { a ->
            val v = a.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun scrollTo(text: String) = rule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(text))

    private fun screens(tag: String) {
        rule.onNodeWithText("Videos").performClick()
        shot("$tag-1-videos")
        rule.onAllNodesWithContentDescription("VID_9.mp4")[0].performTouchInput { longClick() }
        shot("$tag-2-selecting")
        rule.onNodeWithContentDescription("Cancel").performClick()
        rule.onAllNodesWithContentDescription("VID_5.mp4")[0].performClick()  // a photo: no video player needed
        shot("$tag-6-viewer")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("Library").performClick()
        shot("$tag-3-library")
        rule.onNodeWithText("Uploading").performClick()
        shot("$tag-7-uploading")
        rule.onNodeWithContentDescription("Back").performClick()
        scrollTo("Goa trip 2026"); rule.onNodeWithText("Goa trip 2026").performClick()
        shot("$tag-4-album")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithContentDescription("Settings").performClick()
        shot("$tag-5-settings")
    }

    /** One run (state carries over between tests in one JVM): first-run screen, then every main screen in light and dark. */
    @Test fun tour() {
        seed("light")
        Prefs(ctx).onboarded = false
        org.robolectric.Shadows.shadowOf(ctx).grantPermissions(*com.footagevault.app.data.Permissions.request())
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }.use {
            shot("light-0-onboarding")
            rule.onNodeWithText("Only new videos from now on").performClick()
            screens("light")
            rule.onNodeWithText("Dark").performClick()
            rule.onNodeWithContentDescription("Back").performClick()
            screens("dark")
            // Small phone with large text: where layouts break first.
            rule.onNodeWithText("Light").performClick()
            rule.onNodeWithContentDescription("Back").performClick()
            org.robolectric.RuntimeEnvironment.setQualifiers("w360dp-h740dp-xhdpi")
            org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
            it.recreate()
            screens("small")
        }
    }
}
