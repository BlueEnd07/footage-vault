package com.footagevault.app.net

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.footagevault.app.data.Item
import com.footagevault.app.data.combinedProgress
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The phone sends a few videos at once; each must be able to stop cleanly between chunks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ParallelUploadTest {
    private lateinit var server: MockWebServer
    private val yt = YouTube { "test-token" }
    private val cr get() = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun file(bytes: Int): Uri =
        Uri.fromFile(File.createTempFile("clip", ".mp4").apply { deleteOnExit(); writeBytes(ByteArray(bytes)) })

    /** Answers each chunk with 308 (or 200 for the last one), like YouTube's resumable endpoint. */
    private fun youtubeLike(delayMs: Long = 0) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val range = request.getHeader("Content-Range")!!  // bytes a-b/size
            val (a, b) = range.removePrefix("bytes ").substringBefore("/").split("-").map { it.toLong() }
            val size = range.substringAfter("/").toLong()
            val r = if (b + 1 >= size) MockResponse().setResponseCode(200).setBody("""{"id":"v${request.path}"}""")
            else MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-$b")
            return r.setHeadersDelay(delayMs, TimeUnit.MILLISECONDS).also { assertTrue(a <= b) }
        }
    }

    @Test
    fun `two uploads run side by side and both finish`() = runBlocking {
        server.dispatcher = youtubeLike(delayMs = 300)
        val size = 9L * 1024 * 1024  // two chunks each
        val start = System.nanoTime()
        val ids = listOf("/a", "/b").map { path ->
            async(Dispatchers.Default) { yt.uploadFrom(server.url(path).toString(), cr, file(size.toInt()), size, 0) {}.getString("id") }
        }.awaitAll()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(listOf("v/a", "v/b"), ids)
        assertEquals(4, server.requestCount)
        assertTrue("uploads should overlap (took $ms ms)", ms < 4 * 300)
    }

    @Test
    fun `a cancelled upload stops after the chunk it is sending`() = runBlocking {
        server.dispatcher = youtubeLike(delayMs = 500)
        val size = 30L * 1024 * 1024  // four chunks
        val sent = mutableListOf<Long>()
        val job = launch(Dispatchers.Default) {
            yt.uploadFrom(server.url("/c").toString(), cr, file(size.toInt()), size, 0) { sent += it }
        }
        server.takeRequest(5, TimeUnit.SECONDS)  // first chunk is on its way
        job.cancel()
        job.join()
        assertEquals("only the chunk in flight is sent", 1, server.requestCount)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `combined progress is weighted by size`() {
        fun item(size: Long, p: Float) = Item(id = size, mediaId = null, uri = null, name = "x", size = size, durationMs = 0,
            width = 0, height = 0, recorded = 0, lat = null, lon = null, place = null, bucket = null, status = "uploading",
            ytId = null, progress = p, error = null, uploadUrl = null, attempts = 0, favorite = false, favSynced = true,
            onPhone = true, privacy = null, geoChecked = true)
        assertEquals(0.75f, combinedProgress(listOf(item(300, 1f), item(100, 0f))), 0.001f)
        assertEquals(0f, combinedProgress(emptyList()), 0f)
    }
}
