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
import org.junit.Test

/**
 * The chapter list is walked page by page, sequentially, while the details screen waits on it — so
 * what ends the walk is a user-visible property, not an implementation detail. `meta.lastPage` is
 * re-read from every response and cannot be trusted to end it on its own.
 */
class MangaFireMangaRepositoryTest {

	/** Matches the repository's own ceiling; a change to either should fail this test. */
	private val chapterPageCap = 200

	private val source = DataDrivenMangaSource(
		catalogueId = "MANGAFIRE_EN",
		engineKey = "mangafire",
		title = "MangaFire",
		locale = "en",
		nsfw = false,
		domain = "mangafire.to",
		contentType = ContentType.MANGA,
		config = emptyMap(),
		pageSize = 50,
		antiBot = null,
		cfWall = null,
	)

	private val seed = Manga(
		id = "seed",
		title = "Solo Leveling",
		url = "/title/abc-solo-leveling",
		source = MangaSourceRef.Data("data:mangafire_en"),
	)

	private val detailsJson = """{"data":{"hid":"abc","slug":"solo-leveling","title":"Solo Leveling"}}"""

	@Test
	fun theChapterWalkStopsOnAnEmptyBatchHoweverManyPagesTheApiClaims() = runBlocking {
		val http = RecordingHttp { request ->
			if (request.url.encodedPath.endsWith("/chapters")) {
				fakeResponse(request, body = """{"items":[],"meta":{"lastPage":5000}}""".toByteArray())
			} else {
				fakeResponse(request, body = detailsJson.toByteArray())
			}
		}

		val details = repository(http).getDetails(seed)

		assertEquals(0, details.chapters.size)
		// Without the empty-batch break this would have issued 5000 sequential requests.
		assertEquals(1, http.countPath("/chapters"))
	}

	@Test
	fun theChapterWalkIsCappedWhenTheApiKeepsClaimingMorePages() = runBlocking {
		var served = 0
		val http = RecordingHttp { request ->
			if (request.url.encodedPath.endsWith("/chapters")) {
				served++
				fakeResponse(
					request,
					body = """{"items":[{"id":$served,"number":$served.0}],"meta":{"lastPage":5000}}""".toByteArray(),
				)
			} else {
				fakeResponse(request, body = detailsJson.toByteArray())
			}
		}

		val details = repository(http).getDetails(seed)

		assertEquals(chapterPageCap, http.countPath("/chapters"))
		assertEquals(chapterPageCap, details.chapters.size)
	}

	@Test
	fun aNormalChapterListEndsAtTheApisLastPage() = runBlocking {
		val http = RecordingHttp { request ->
			if (request.url.encodedPath.endsWith("/chapters")) {
				val page = request.url.queryParameter("page")?.toInt() ?: 1
				fakeResponse(
					request,
					body = """{"items":[{"id":$page,"number":$page.0}],"meta":{"lastPage":3}}""".toByteArray(),
				)
			} else {
				fakeResponse(request, body = detailsJson.toByteArray())
			}
		}

		val details = repository(http).getDetails(seed)

		assertEquals(3, http.countPath("/chapters"))
		assertEquals(3, details.chapters.size)
		// The API answers newest first; the app numbers ascending.
		assertEquals(listOf(0, 1, 2), details.chapters.map { it.index })
		assertEquals(listOf(3f, 2f, 1f), details.chapters.map { it.number })
	}

	private fun repository(http: RecordingHttp) = MangaFireMangaRepository(
		source = source,
		okHttpClient = http.client,
		cache = null,
	)
}
