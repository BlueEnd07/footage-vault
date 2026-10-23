package com.footagevault.app.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Probing a resumable session is what stops a finished upload from being resumed from the end. */
class YouTubeUploadTest {
    private lateinit var server: MockWebServer
    private lateinit var yt: YouTube

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        yt = YouTube { "test-token" }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueue(code: Int, body: String = "", headers: Map<String, String> = emptyMap()) {
        val r = MockResponse().setResponseCode(code).setBody(body)
        headers.forEach { (k, v) -> r.setHeader(k, v) }
        server.enqueue(r)
    }

    @Test
    fun `308 with a range means resume from the next byte`() = runBlocking {
        enqueue(308, headers = mapOf("Range" to "bytes=0-8388607"))
        val state = yt.probeUpload(server.url("/session").toString(), 20_000_000)
        assertTrue(state is YouTube.UploadState.Resume)
        assertEquals(8_388_608L, (state as YouTube.UploadState.Resume).offset)
    }

    @Test
    fun `308 without a range starts a new session instead of resuming from zero`() = runBlocking {
        enqueue(308)
        assertTrue(yt.probeUpload(server.url("/session").toString(), 20_000_000) is YouTube.UploadState.Expired)
    }

    @Test
    fun `a range covering the whole file starts a new session`() = runBlocking {
        enqueue(308, headers = mapOf("Range" to "bytes=0-19999999"))
        assertTrue(yt.probeUpload(server.url("/session").toString(), 20_000_000) is YouTube.UploadState.Expired)
    }

    @Test
    fun `a finished upload is reported complete instead of resuming at the end`() = runBlocking {
        enqueue(200, """{"id":"abc123","status":{"privacyStatus":"private"}}""")
        val state = yt.probeUpload(server.url("/session").toString(), 20_000_000)
        assertTrue(state is YouTube.UploadState.Complete)
        assertEquals("abc123", (state as YouTube.UploadState.Complete).video.get("id"))
    }

    @Test
    fun `201 also counts as complete`() = runBlocking {
        enqueue(201, """{"id":"xyz"}""")
        val state = yt.probeUpload(server.url("/session").toString(), 100)
        assertTrue(state is YouTube.UploadState.Complete)
    }

    @Test
    fun `404 and 410 mean the session expired`() = runBlocking {
        enqueue(404)
        assertTrue(yt.probeUpload(server.url("/session").toString(), 100) is YouTube.UploadState.Expired)
        enqueue(410)
        assertTrue(yt.probeUpload(server.url("/session").toString(), 100) is YouTube.UploadState.Expired)
    }

    @Test
    fun `a server error is retryable rather than treated as a new session`() = runBlocking {
        enqueue(503, "busy")
        try {
            yt.probeUpload(server.url("/session").toString(), 100)
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(503, e.code)
        }
    }
}
