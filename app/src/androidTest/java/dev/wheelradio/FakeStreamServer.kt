package dev.wheelradio

import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hermetic stand-in for Shoutcast/Icecast: a MockWebServer on 127.0.0.1 inside the test process
 * (the app runs in the same process, so the player reaches it over loopback).
 *
 *  - `/ok/<n>.mp3`  -> 200 audio/mpeg, a 90 s 440 Hz tone (assets/qa_tone_440_90s.mp3 of the test APK)
 *  - `/dead/404`    -> 404
 *  - `/dead/close`  -> connection closed before any response
 *  - [outage] = true makes `/ok/` paths behave like `/dead/close` (simulated network outage).
 *
 * One server for the whole instrumentation run, never shut down: RadioService outlives single tests,
 * and a server dying between tests would make the service start its retry/skip cycle at random.
 */
object FakeStreamServer {
    @Volatile var outage = false

    private val hits = ConcurrentHashMap<String, AtomicInteger>()

    private val tone: ByteArray by lazy {
        InstrumentationRegistry.getInstrumentation().context.assets.open("qa_tone_440_90s.mp3").use { it.readBytes() }
    }

    private val server: MockWebServer by lazy {
        tone // load before serving
        MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path?.substringBefore('?') ?: ""
                    hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                    return when {
                        path.startsWith("/ok/") && outage ->
                            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                        path.startsWith("/ok/") -> MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "audio/mpeg")
                            .setHeader("icy-name", "QA fake stream")
                            .setBody(Buffer().write(tone))
                        path == "/dead/404" -> MockResponse().setResponseCode(404).setBody("not found")
                        path == "/dead/close" -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                        // Accepts the connection and never answers, like the black-holed hosts am:16 / am:35.
                        path == "/dead/silent" -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
    }

    /** Must be called off the main thread the first time (it binds a socket). */
    fun url(path: String): String = "http://127.0.0.1:${server.port}$path"

    fun hits(path: String): Int = hits[path]?.get() ?: 0

    fun ok(n: Int) = Station("qa:ok:$n", "QA Stream $n", url("/ok/$n.mp3"), subtitle = "QA fixture $n")
    fun dead404(n: Int = 1) = Station("qa:404:$n", "QA Dead 404 $n", url("/dead/404"), subtitle = "QA dead")
    fun deadClosed(n: Int = 1) = Station("qa:close:$n", "QA Dead Closed $n", url("/dead/close"), subtitle = "QA dead")
    fun deadSilent(n: Int = 1) = Station("qa:silent:$n", "QA Dead Silent $n", url("/dead/silent"), subtitle = "QA dead")
}
