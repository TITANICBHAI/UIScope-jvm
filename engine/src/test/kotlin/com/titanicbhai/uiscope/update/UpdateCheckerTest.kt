package com.titanicbhai.uiscope.update

import com.titanicbhai.uiscope.AppInfo
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.process.DownloadResult
import com.titanicbhai.uiscope.process.HttpTextResponse
import com.titanicbhai.uiscope.process.NetworkGateway
import com.titanicbhai.uiscope.testing.blocking
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration

class UpdateCheckerTest {
    private class FakeGateway(private val reply: Outcome<HttpTextResponse>) : NetworkGateway {
        val gets = AtomicInteger()
        val downloads = AtomicInteger()
        val urls = mutableListOf<String>()

        override suspend fun getText(url: String, timeout: Duration): Outcome<HttpTextResponse> {
            gets.incrementAndGet()
            urls += url
            return reply
        }

        override suspend fun download(url: String, target: Path, timeout: Duration, onProgress: (Long, Long?) -> Unit): Outcome<DownloadResult> {
            downloads.incrementAndGet()
            error("the update check must never download")
        }
    }

    private fun info(version: String = "1.0.0", store: Boolean = false) = AppInfo(
        "UIScope", "TBTechs", "TITANICBHAI", version, "2026-01-01", "abc", "https://github.com/tbtechs/uiscope",
        "https://api.github.com/repos/tbtechs/uiscope/releases/latest", "https://github.com/tbtechs/uiscope/issues", "https://example.org/privacy", "https://example.org/docs", "https://example.org/support", store,
    )

    private fun ok(body: String) = Outcome.Success(HttpTextResponse(200, body))

    private fun release(tag: String, extra: String = "") = """{"tag_name":"$tag","html_url":"https://github.com/tbtechs/uiscope/releases/tag/$tag"$extra}"""

    private fun check(gateway: FakeGateway, version: String = "1.0.0", store: Boolean = false, enabled: Boolean = true): UpdateCheck {
        var result: UpdateCheck? = null
        blocking { result = UpdateChecker(gateway, info(version, store), { enabled }).check() }
        return result!!
    }

    @Test
    fun `a newer release is offered with its release page and nothing is downloaded`() {
        val g = FakeGateway(ok(release("v1.1.0")))
        val r = assertIs<UpdateCheck.Available>(check(g))
        assertEquals("1.1.0", r.version)
        assertEquals("https://github.com/tbtechs/uiscope/releases/tag/v1.1.0", r.whatsNewUrl)
        assertEquals(listOf("https://api.github.com/repos/tbtechs/uiscope/releases/latest"), g.urls)
        assertEquals(0, g.downloads.get())
    }

    @Test
    fun `the same or an older release is up to date and a downgrade is never offered`() {
        assertEquals(UpdateCheck.UpToDate, check(FakeGateway(ok(release("v1.0.0")))))
        assertEquals(UpdateCheck.UpToDate, check(FakeGateway(ok(release("0.9.0")))))
        assertEquals(UpdateCheck.UpToDate, check(FakeGateway(ok(release("1.9.0"))), version = "1.10.0"))
    }

    @Test
    fun `drafts and pre-releases are not offered to people on a stable version`() {
        assertEquals(UpdateCheck.UpToDate, check(FakeGateway(ok(release("v2.0.0", ",\"prerelease\":true")))))
        assertEquals(UpdateCheck.UpToDate, check(FakeGateway(ok(release("v2.0.0", ",\"draft\":true")))))
        assertEquals(UpdateCheck.UpToDate, check(FakeGateway(ok(release("v2.0.0-beta.1")))))
    }

    @Test
    fun `the Store build and the opt-out make no network call at all`() {
        val store = FakeGateway(ok(release("v9.0.0")))
        assertEquals(UpdateCheck.Disabled, check(store, store = true))
        assertEquals(0, store.gets.get())
        val off = FakeGateway(ok(release("v9.0.0")))
        assertEquals(UpdateCheck.Disabled, check(off, enabled = false))
        assertEquals(0, off.gets.get())
    }

    @Test
    fun `a release link to another host is replaced by the repository releases page`() {
        for (url in listOf("https://evil.example/download.exe", "http://github.com/tbtechs/uiscope/releases/tag/v2", "javascript:alert(1)", "not a url at all", "file:///C:/x")) {
            val body = """{"tag_name":"v2.0.0","html_url":"${url.replace("\\", "\\\\")}"}"""
            val r = assertIs<UpdateCheck.Available>(check(FakeGateway(ok(body))))
            assertEquals("https://github.com/tbtechs/uiscope/releases", r.whatsNewUrl, "link '$url' was trusted")
        }
        assertEquals("https://github.com/tbtechs/uiscope/releases", assertIs<UpdateCheck.Available>(check(FakeGateway(ok("""{"tag_name":"v2.0.0"}""")))).whatsNewUrl)
    }

    @Test
    fun `network failures and hostile answers are Failed results and never throw`() {
        val failures = listOf(
            Outcome.Failure(CodedError(ErrorCodes.NETWORK_CONNECT, "offline")),
            Outcome.Failure(CodedError(ErrorCodes.NETWORK_HTTP_STATUS, "403")),
            ok(""), ok("not json"), ok("[]"), ok("\"v2.0.0\""), ok("{}"), ok("""{"tag_name":5}"""), ok("""{"tag_name":"latest"}"""), ok("""{"tag_name":null}"""),
            ok("x".repeat(3_000_000)),
        )
        for (reply in failures) {
            @Suppress("UNCHECKED_CAST")
            val r = check(FakeGateway(reply as Outcome<HttpTextResponse>))
            assertIs<UpdateCheck.Failed>(r)
        }
        assertIs<UpdateCheck.Failed>(check(FakeGateway(ok(release("v2.0.0"))), version = "not-a-version"))
    }
}
