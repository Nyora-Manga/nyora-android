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
}
