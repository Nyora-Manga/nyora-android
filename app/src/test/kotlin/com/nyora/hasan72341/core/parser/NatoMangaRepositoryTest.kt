package com.nyora.hasan72341.core.parser

import com.nyora.hasan72341.core.model.DataDrivenMangaSource
import com.nyora.hasan72341.core.network.fakeResponse
import com.nyora.hasan72341.mihon.parsers.model.ContentType
import com.nyora.hasan72341.mihon.parsers.model.Manga
import com.nyora.hasan72341.mihon.parsers.model.MangaSourceRef
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Details are fetched in two independent halves, and what happens when one of them fails decides
 * more than the screen in front of the user: `CachingMangaRepository` stores whatever comes back in
 * `MemoryContentCache`, so a chapterless result returned *normally* is re-served on every later
 * open, with no error and no way to retry.
 */
class NatoMangaRepositoryTest {

	private val source = DataDrivenMangaSource(
		catalogueId = "MANGANATO",
		engineKey = "mangabox",
		title = "MangaNato",
		locale = "en",
		nsfw = false,
		domain = "test.example",
		contentType = ContentType.MANGA,
		config = emptyMap(),
		pageSize = 24,
		antiBot = null,
		cfWall = null,
	)

	private val seed = Manga(
		id = "seed",
		title = "Seed Title",
		url = "/manga/solo-leveling",
		source = MangaSourceRef.Data("data:manganato"),
	)

	private val chapterJson = """
		{"data":{"chapters":[{"chapter_name":"Chapter 1","chapter_slug":"chapter-1","chapter_num":1.0,
		"updated_at":"2024-01-01T00:00:00"}],"pagination":{"total":1}}}
	""".trimIndent()

	private val detailsHtml = """
		<html><head><meta property="og:image" content="https://cdn.example/c.jpg"></head>
		<body><h1>Solo Leveling</h1>
		<div class="manga-info-top"><ul><li>Status : Ongoing</li></ul></div></body></html>
	""".trimIndent()

	@Test
	fun detailsFailLoudlyWhenNeitherTheChapterApiNorThePageAnswers() = runBlocking {
		val repo = repository { request -> fakeResponse(request, code = 503, body = "down".toByteArray()) }

		try {
			repo.getDetails(seed)
			fail("a seed title with zero chapters must not be returned as a normal, cacheable result")
		} catch (e: IllegalStateException) {
			// The detail page is the half Cloudflare challenges, so its failure is the one raised.
			assertTrue(e.message, e.message!!.contains("https://test.example/manga/solo-leveling"))
			assertTrue(e.message, !e.message!!.contains("/api/manga/"))
		}
	}

	@Test
	fun detailsFailLoudlyWhenOnlyTheChapterApiIsDown() = runBlocking {
		val repo = repository { request ->
			if (request.url.encodedPath.startsWith("/api/")) {
				fakeResponse(request, code = 503, body = "down".toByteArray())
			} else {
				fakeResponse(request, body = detailsHtml.toByteArray(), contentType = "text/html")
			}
		}

		try {
			repo.getDetails(seed)
			fail("metadata without the chapter list is still a chapterless result")
		} catch (e: IllegalStateException) {
			assertTrue(e.message, e.message!!.contains("/api/manga/solo-leveling/chapters"))
		}
	}

	@Test
	fun detailsDegradeToChaptersOnlyWhenJustThePageIsChallenged() = runBlocking {
		val repo = repository { request ->
			if (request.url.encodedPath.startsWith("/api/")) {
				fakeResponse(request, body = chapterJson.toByteArray(), contentType = "application/json")
			} else {
				fakeResponse(request, code = 403, body = "blocked".toByteArray())
			}
		}

		val details = repo.getDetails(seed)

		// The whole point of fetching the two halves apart: an unsolved clearance costs the extra
		// metadata, not the chapter list.
		assertEquals(1, details.chapters.size)
		assertEquals("Seed Title", details.title)
	}

	@Test
	fun aTitleThatGenuinelyHasNoChaptersIsStillReturned() = runBlocking {
		val repo = repository { request ->
			if (request.url.encodedPath.startsWith("/api/")) {
				fakeResponse(
					request,
					body = """{"data":{"chapters":[],"pagination":{"total":0}}}""".toByteArray(),
					contentType = "application/json",
				)
			} else {
				fakeResponse(request, body = detailsHtml.toByteArray(), contentType = "text/html")
			}
		}

		val details = repo.getDetails(seed)

		assertEquals(0, details.chapters.size)
		assertEquals("Solo Leveling", details.title)
	}

	private fun repository(responder: (Request) -> Response) = NatoMangaRepository(
		source = source,
		okHttpClient = RecordingHttp(responder).client,
		cache = null,
		domainOverride = { "test.example" },
	)
}
