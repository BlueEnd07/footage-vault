package com.footagevault.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.footagevault.app.data.Prefs
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import kotlin.math.abs

/**
 * YouTube's embedded player in its own full-screen window (unlisted / public videos), with
 * swipe left/right to move through the same list as the gallery viewer.
 *
 * A plain window instead of the Compose pager: a WebView video inside the pager can end up
 * drawn behind the app (sound but a black picture).
 *
 * Extras: ids = YouTube id per position ("" = not playable here, e.g. a video on the phone),
 * labels = date/time per position, index = where to start.
 * Result: the index the user ended on, so the gallery viewer can follow.
 */
class YouTubePlayerActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var label: TextView
    private lateinit var prev: TextView
    private lateinit var next: TextView
    private var customView: View? = null
    private var ids: Array<String> = emptyArray()
    private var labels: Array<String> = emptyArray()
    private var index = 0
    private lateinit var prefs: Prefs
    private var playingId = ""  // video currently in the player
    private val ui = Handler(Looper.getMainLooper())
    private val hideChrome = Runnable { listOf(label, prev, next).forEach { it.animate().alpha(0f).setDuration(250).start() } }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ids = intent.getStringArrayExtra(EXTRA_IDS) ?: arrayOf(intent.getStringExtra(EXTRA_ID) ?: return finish())
        labels = intent.getStringArrayExtra(EXTRA_LABELS) ?: Array(ids.size) { "" }
        index = savedInstanceState?.getInt("index") ?: intent.getIntExtra(EXTRA_INDEX, 0)
        if (ids.getOrNull(index).isNullOrBlank()) return finish()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        prefs = Prefs(this)
        val root = SwipeLayout(this, onSwipe = ::step, onDown = { finishWithIndex(handoff = false) }, onTouch = ::showChrome)
            .apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = object : WebChromeClient() {
                // YouTube's own fullscreen button
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    customView = view
                    root.addView(view, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
                override fun onHideCustomView() {
                    customView?.let { root.removeView(it) }
                    customView = null
                }
            }
        }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        label = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0x99000000.toInt()) }
        }
        root.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(16) })
        prev = arrow("‹") { step(-1) }
        next = arrow("›") { step(1) }
        root.addView(prev, FrameLayout.LayoutParams(dp(48), dp(64), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(8) })
        root.addView(next, FrameLayout.LayoutParams(dp(48), dp(64), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(8) })
        setContentView(root)

        // YouTube needs to know who embeds it (else "error 153"): give the page an https origin.
        playingId = ids[index]
        web.loadDataWithBaseURL("$ORIGIN/", html(playingId, resumeSeconds(playingId)), "text/html", "utf-8", null)
        showChrome()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun arrow(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x66000000) }
        setOnClickListener { onClick() }
    }

    private fun showChrome() {
        label.text = labels.getOrNull(index).orEmpty()
        label.visibility = if (label.text.isNullOrBlank()) View.GONE else View.VISIBLE
        prev.visibility = if (index > 0) View.VISIBLE else View.INVISIBLE
        next.visibility = if (index < ids.size - 1) View.VISIBLE else View.INVISIBLE
        listOf(label, prev, next).forEach { it.animate().cancel(); it.alpha = 1f }
        ui.removeCallbacks(hideChrome)
        ui.postDelayed(hideChrome, 2500)
    }

    /** Next / previous video. A video that can't play here (on the phone) is shown by the gallery viewer instead. */
    private fun step(dir: Int) {
        val to = index + dir
        if (to !in ids.indices) return
        index = to
        val id = ids[to]
        if (id.isBlank()) { finishWithIndex(handoff = true); return }
        saveSpot(playingId)
        playingId = id
        web.evaluateJavascript("load(${JSONObject.quote(id)}, ${resumeSeconds(id)})", null)
        web.animate().cancel()
        web.translationX = if (dir > 0) dp(60).toFloat() else -dp(60).toFloat()
        web.alpha = 0.3f
        web.animate().translationX(0f).alpha(1f).setDuration(220).start()
        showChrome()
    }

    /** handoff = the next video plays in the gallery viewer (it's on the phone, or private). */
    private fun finishWithIndex(handoff: Boolean) {
        if (isFinishing) return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_INDEX, index).putExtra(EXTRA_HANDOFF, handoff))
        saveSpot(playingId) { finish() }
    }

    private fun resumeSeconds(id: String) = prefs.resumeAt("y$id") / 1000

    /** Remembers where a long video stopped (videos over a minute, unless nearly finished). */
    private fun saveSpot(id: String, then: () -> Unit = {}) {
        if (id.isBlank()) return then()
        web.evaluateJavascript("(player && player.getCurrentTime) ? player.getCurrentTime() + ',' + player.getDuration() : ''") { v ->
            val parts = v.trim('"').split(',').mapNotNull { it.toDoubleOrNull() }
            if (parts.size == 2) {
                val (pos, dur) = parts
                prefs.setResumeAt("y$id", if (dur > 60 && pos > 5 && pos < dur - 5) (pos * 1000).toLong() else 0)
            }
            then()
        }
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("index", index) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (customView != null) web.webChromeClient?.onHideCustomView() else finishWithIndex(handoff = false)
    }

    override fun onPause() { super.onPause(); web.onPause() }
    override fun onResume() { super.onResume(); web.onResume() }
    override fun onDestroy() { ui.removeCallbacksAndMessages(null); web.destroy(); super.onDestroy() }

    /** Lets taps reach YouTube's controls but takes clear horizontal swipes for itself. */
    private class SwipeLayout(ctx: Context, val onSwipe: (Int) -> Unit, val onDown: () -> Unit, val onTouch: () -> Unit) : FrameLayout(ctx) {
        private var x0 = 0f
        private var y0 = 0f
        private var tracking = false
        private val minSwipe = 80 * resources.displayMetrics.density

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.x; y0 = e.y
                    // The bottom strip is YouTube's progress bar and buttons: dragging there is seeking.
                    tracking = e.y < height * 0.78f
                    onTouch()
                }
                MotionEvent.ACTION_MOVE -> if (tracking) {
                    val dx = e.x - x0; val dy = e.y - y0
                    if (abs(dx) > minSwipe && abs(dx) > 2 * abs(dy)) {
                        tracking = false
                        onSwipe(if (dx < 0) 1 else -1)
                        return true  // the rest of this gesture is ours
                    }
                    if (dy > minSwipe * 1.5f && dy > 2 * abs(dx)) {  // swipe down: close, like the viewer
                        tracking = false
                        onDown()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent) = true
    }

    companion object {
        const val EXTRA_INDEX = "index"
        const val EXTRA_HANDOFF = "handoff"
        private const val EXTRA_ID = "yt_id"
        private const val EXTRA_IDS = "yt_ids"
        private const val EXTRA_LABELS = "labels"
        private const val ORIGIN = "https://com.footagevault.app"

        fun intent(ctx: Context, ids: Array<String>, labels: Array<String>, index: Int) =
            Intent(ctx, YouTubePlayerActivity::class.java)
                .putExtra(EXTRA_IDS, ids).putExtra(EXTRA_LABELS, labels).putExtra(EXTRA_INDEX, index)

        /** YouTube IFrame API, so the next video loads in the same player (no page reload). */
        private fun html(id: String, start: Long) = """
            <!doctype html><html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="referrer" content="strict-origin-when-cross-origin">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
            #p,iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style></head>
            <body><div id="p"></div>
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
              var player, pending = null;
              function onYouTubeIframeAPIReady() {
                player = new YT.Player('p', { width: '100%', height: '100%', videoId: ${JSONObject.quote(id)},
                  playerVars: { autoplay: 1, playsinline: 1, rel: 0, modestbranding: 1, fs: 1, start: $start, origin: '$ORIGIN' },
                  events: { onReady: function (e) { e.target.playVideo(); if (pending) { e.target.loadVideoById(pending); pending = null; } } } });
              }
              function load(id, start) {
                var v = { videoId: id, startSeconds: start || 0 };
                if (player && player.loadVideoById) player.loadVideoById(v); else pending = v;
              }
            </script></body></html>
        """.trimIndent()
    }
}
