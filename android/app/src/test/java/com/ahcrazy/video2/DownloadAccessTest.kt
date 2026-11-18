package com.ahcrazy.video2

import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.DownloadState
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.MediaProblem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Video2Application::class)
class DownloadAccessTest {
    @Test fun aServerThatRejectsResumeCanStillServeTheSameFileInOneFullRequest() = runBlocking<Unit> {
        val app = RuntimeEnvironment.getApplication() as Video2Application
        MockWebServer().use { server ->
            // A short container prefix suffices to exercise the transfer; this isn't a decoder test.
            val complete = byteArrayOf(0, 0, 0, 24) + "ftypisom00000000remaining-bytes".toByteArray()
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-15/${complete.size}")
                .setHeader("Content-Type", "video/mp4").setHeader("ETag", "\"stable\"").setBody(Buffer().write(complete, 0, 16)))
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(complete)))
            val remote = server.url("/authorized.mp4?sig=a%2Bb").toString()
            val job = app.downloads.enqueue(DetectedMedia(remote, "test clip", MediaKind.MP4))
            val finished = withTimeout(10_000) { app.downloads.jobs.first { jobs -> jobs.any { it.id == job.id && !it.state.isBusy } } }.first { it.id == job.id }
            assertEquals(finished.errorMessage, DownloadState.COMPLETED, finished.state)
            assertArrayEquals(complete, File(requireNotNull(finished.destPath)).readBytes())
            val requests = List(3) { checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            assertTrue(requests.all { it.path == "/authorized.mp4?sig=a%2Bb" })
            assertEquals(listOf(null, "bytes=16-", null), requests.map { it.getHeader("Range") })
            assertEquals("\"stable\"", requests[1].getHeader("If-Range"))
        }
    }

    @Test fun actualPermissionDenialIsNotAutomaticallyRetried() = runBlocking<Unit> {
        val app = RuntimeEnvironment.getApplication() as Video2Application
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            val job = app.downloads.enqueue(DetectedMedia(server.url("/denied.mp4").toString(), "test clip", MediaKind.MP4))
            val failed = withTimeout(10_000) { app.downloads.jobs.first { jobs -> jobs.any { it.id == job.id && !it.state.isBusy } } }.first { it.id == job.id }
            assertEquals(DownloadState.FAILED, failed.state)
            assertEquals(MediaProblem.ACCESS, failed.problem)
            assertEquals(403, failed.httpStatus)
            assertFalse(failed.canRetry)
            assertEquals(1, server.requestCount)
            assertNull(app.library.byId(job.id))
        }
    }
}
