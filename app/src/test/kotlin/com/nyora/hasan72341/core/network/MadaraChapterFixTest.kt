package com.nyora.hasan72341.core.network

import okhttp3.FormBody
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The recovery only fires on a Madara `manga_get_chapters` post whose id was seen on a title
 * document, so both halves of that match — the form and the `data-id` — decide whether a whole
 * family of sources can list chapters at all.
 */
class MadaraChapterFixTest {

	@Test
	fun readsTheChaptersHolderId() {
		val html = """
			<div class="c-page"></div>
			<div id="manga-chapters-holder" data-id="47353"><i class="fas fa-spinner"></i></div>
		""".trimIndent()

		assertEquals("47353", extractMangaId(html))
	}

	@Test
	fun readsTheChaptersHolderIdRegardlessOfAttributeOrderOrQuoting() {
		val html = """<div data-id='1534' class="x" id='manga-chapters-holder'></div>"""

		assertEquals("1534", extractMangaId(html))
	}

	@Test
	fun returnsNoIdWhenTheHolderIsAbsent() {
		assertNull(extractMangaId("<html><body><h1>Way To Heaven</h1></body></html>"))
		assertNull(extractMangaId("""<div id="manga-chapters-holder"></div>"""))
	}

	@Test
	fun readsTheMangaIdOffTheChapterForm() {
		val form = FormBody.Builder()
			.addEncoded("action", "manga_get_chapters")
			.addEncoded("manga", "47353")
			.build()

		assertEquals("47353", mangaIdOf(form))
	}

	@Test
	fun ignoresFormsThatAreNotChapterRequests() {
		assertNull(mangaIdOf(null))
		assertNull(
			mangaIdOf(
				FormBody.Builder()
					.addEncoded("action", "madara_load_more")
					.addEncoded("manga", "47353")
					.build(),
			),
		)
		assertNull(mangaIdOf(FormBody.Builder().addEncoded("action", "manga_get_chapters").build()))
	}

	@Test
	fun retriesAFailedChapterActionAgainstTheTitlesOwnEndpoint() {
		val host = "retry-${System.nanoTime()}.example"
		val titleUrl = "https://$host/manga/solo-leveling-arise/"
		val titleRequest = Request.Builder().url(titleUrl).get().build()
		MadaraChapterFix.intercept(
			FakeInterceptorChain(titleRequest) {
				fakeResponse(
					it,
					body = """<div id="manga-chapters-holder" data-id="47353"></div>""".toByteArray(),
					contentType = "text/html; charset=utf-8",
				)
			},
		)

		val ajaxRequest = Request.Builder()
			.url("https://$host/wp-admin/admin-ajax.php")
			.post(
				FormBody.Builder()
					.addEncoded("action", "manga_get_chapters")
					.addEncoded("manga", "47353")
					.build(),
			)
			.build()
		val chain = FakeInterceptorChain(ajaxRequest) { sent ->
			if (sent.url.encodedPath.endsWith("/ajax/chapters/")) {
				fakeResponse(sent, body = "<li class=\"wp-manga-chapter\"></li>".toByteArray())
			} else {
				fakeResponse(sent, code = 400, body = "bad request".toByteArray())
			}
		}

		val response = MadaraChapterFix.intercept(chain)

		assertEquals(200, response.code)
		assertEquals("https://$host/manga/solo-leveling-arise/ajax/chapters/", chain.proceeded.last().url.toString())
		assertEquals(titleUrl, chain.proceeded.last().header("Referer"))
	}

	@Test
	fun handsBackTheOriginalFailureWhenTheFallbackFailsToo() {
		val host = "fallback-${System.nanoTime()}.example"
		val titleRequest = Request.Builder().url("https://$host/manga/way-to-heaven/").get().build()
		MadaraChapterFix.intercept(
			FakeInterceptorChain(titleRequest) {
				fakeResponse(
					it,
					body = """<div id="manga-chapters-holder" data-id="9001"></div>""".toByteArray(),
					contentType = "text/html",
				)
			},
		)

		val ajaxRequest = Request.Builder()
			.url("https://$host/wp-admin/admin-ajax.php")
			.post(
				FormBody.Builder()
					.addEncoded("action", "manga_get_chapters")
					.addEncoded("manga", "9001")
					.build(),
			)
			.build()
		val chain = FakeInterceptorChain(ajaxRequest) { sent -> fakeResponse(sent, code = 400, body = "nope".toByteArray()) }

		val response = MadaraChapterFix.intercept(chain)

		assertEquals(400, response.code)
		assertEquals("nope", response.body.string())
	}

	@Test
	fun readsAHolderThatSitsDeepInsideALargeDocument() {
		val host = "deep-${System.nanoTime()}.example"
		val titleUrl = "https://$host/manga/way-of-the-devil/"
		// The holder is the last thing on a real title page, well past the first buffer's worth, and
		// its `data-id` can sit before the id attribute.
		val html = "<p>" + "padding ".repeat(40_000) +
			"""</p><div data-id='9100' class="c-page" id='manga-chapters-holder'></div>"""
		rememberTitle(titleUrl, html.toByteArray())

		val chain = failingChapterAction(host, "9100")
		val response = MadaraChapterFix.intercept(chain)

		assertEquals(200, response.code)
		assertEquals("https://$host/manga/way-of-the-devil/ajax/chapters/", chain.proceeded.last().url.toString())
	}

	@Test
	fun readsAHolderWhoseNameAlsoAppearsInAScriptFirst() {
		val host = "script-${System.nanoTime()}.example"
		val titleUrl = "https://$host/manga/the-beginning-after-the-end/"
		// Madara themes name the holder in inline script long before the tag is rendered, and that
		// mention carries no id.
		val html = """<script>jQuery('#manga-chapters-holder').on('load', fn);</script>""" +
			"<p>" + "padding ".repeat(2_000) + "</p>" + HOLDER_HTML
		rememberTitle(titleUrl, html.toByteArray())

		val chain = failingChapterAction(host, "47353")
		val response = MadaraChapterFix.intercept(chain)

		assertEquals(200, response.code)
		assertEquals("$titleUrl" + "ajax/chapters/", chain.proceeded.last().url.toString())
	}

	@Test
	fun doesNotScanSearchOrListingResponses() {
		// A global search fans one of these out across every source at once, so it is exactly the
		// traffic that must not be buffered and scanned on the shared client.
		val host = "search-${System.nanoTime()}.example"
		rememberTitle("https://$host/?s=solo&post_type=wp-manga", HOLDER_HTML.toByteArray())

		val chain = failingChapterAction(host, "47353")
		val response = MadaraChapterFix.intercept(chain)

		assertEquals(400, response.code)
		assertEquals(1, chain.proceeded.size)
	}

	@Test
	fun doesNotScanTheReadersChapterDocuments() {
		val host = "reader-${System.nanoTime()}.example"
		rememberTitle("https://$host/manga/solo-leveling/chapter-12/", HOLDER_HTML.toByteArray())

		val chain = failingChapterAction(host, "47353")
		val response = MadaraChapterFix.intercept(chain)

		assertEquals(400, response.code)
		assertEquals(1, chain.proceeded.size)
	}

	@Test
	fun doesNotBufferADocumentLongerThanThePeekBound() {
		val host = "huge-${System.nanoTime()}.example"
		val html = "<p>" + "x".repeat(600 * 1024) + "</p>" + HOLDER_HTML
		rememberTitle("https://$host/manga/endless/", html.toByteArray())

		val chain = failingChapterAction(host, "47353")
		val response = MadaraChapterFix.intercept(chain)

		assertEquals(400, response.code)
		assertEquals(1, chain.proceeded.size)
	}

	@Test
	fun doesNotScanNonHtmlResponses() {
		val host = "json-${System.nanoTime()}.example"
		rememberTitle("https://$host/manga/solo-leveling/", HOLDER_HTML.toByteArray(), "application/json")

		val chain = failingChapterAction(host, "47353")
		val response = MadaraChapterFix.intercept(chain)

		assertEquals(400, response.code)
		assertEquals(1, chain.proceeded.size)
	}

	/** Passes one document through the interceptor, which is how a title url gets remembered. */
	private fun rememberTitle(url: String, html: ByteArray, contentType: String? = "text/html; charset=utf-8") {
		MadaraChapterFix.intercept(
			FakeInterceptorChain(Request.Builder().url(url).get().build()) {
				fakeResponse(it, body = html, contentType = contentType)
			},
		)
	}

	/** A `manga_get_chapters` post the site answers with 400, and a working per-title endpoint. */
	private fun failingChapterAction(host: String, mangaId: String): FakeInterceptorChain {
		val request = Request.Builder()
			.url("https://$host/wp-admin/admin-ajax.php")
			.post(
				FormBody.Builder()
					.addEncoded("action", "manga_get_chapters")
					.addEncoded("manga", mangaId)
					.build(),
			)
			.build()
		return FakeInterceptorChain(request) { sent ->
			if (sent.url.encodedPath.endsWith("/ajax/chapters/")) {
				fakeResponse(sent, body = "<li class=\"wp-manga-chapter\"></li>".toByteArray())
			} else {
				fakeResponse(sent, code = 400, body = "bad request".toByteArray())
			}
		}
	}

	private companion object {

		const val HOLDER_HTML = """<div id="manga-chapters-holder" data-id="47353"></div>"""
	}
}
