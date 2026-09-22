package com.nyora.hasan72341.core.parser

import app.nyora.data.engine.FilterCapabilities
import app.nyora.data.engine.ImageRequest
import app.nyora.data.engine.SourceDef
import app.nyora.data.engine.SourceEngine
import com.nyora.hasan72341.core.model.DataDrivenMangaSource
import com.nyora.hasan72341.core.parser.datadriven.stableChapterId
import com.nyora.hasan72341.core.parser.datadriven.stableMangaId
import com.nyora.hasan72341.core.parser.datadriven.toSourceDef
import com.nyora.hasan72341.mihon.parsers.model.ContentType
import com.nyora.hasan72341.mihon.parsers.model.Manga
import com.nyora.hasan72341.mihon.parsers.model.MangaListFilter
import com.nyora.hasan72341.mihon.parsers.model.MangaPage
import com.nyora.hasan72341.mihon.parsers.model.MangaSourceRef
import com.nyora.hasan72341.mihon.parsers.model.SortOrder
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import app.nyora.core.model.Manga as EngineManga
import app.nyora.core.model.MangaChapter as EngineChapter
import app.nyora.core.model.MangaListFilter as EngineFilter
import app.nyora.core.model.MangaPage as EnginePage
import app.nyora.core.model.MangaTag as EngineTag
import app.nyora.core.model.SortOrder as EngineSortOrder
import org.koitharu.kotatsu.parsers.model.MangaTag as LibMangaTag

/**
 * The repository is the whole contract between the catalogue and the app: a mis-mapped page number,
 * manga id or page header silently changes which rows a browse returns, which history entries
 * resolve, and whether a CDN serves the image at all.
 */
class DataDrivenMangaRepositoryTest {

	private val source = DataDrivenMangaSource(
		catalogueId = "HIPERDEX",
		engineKey = "madara",
		title = "HiperToon",
		locale = "en",
		nsfw = true,
		domain = "hiperdex.com",
		contentType = ContentType.HENTAI_MANGA,
		config = emptyMap(),
		pageSize = 36,
		antiBot = null,
		cfWall = null,
	)

	@Test
	fun listMapsOffsetToPageAndStampsLegacyIds() = runBlocking {
		val engine = FakeEngine(popular = listOf(EngineManga(id = "/manga/x", title = "X", url = "/manga/x")))
		val repo = repository(engine)
		val list = repo.getList(offset = 72, order = SortOrder.POPULARITY, filter = null)
		assertEquals(2, engine.lastPage)
		assertEquals(stableMangaId("HIPERDEX", "/manga/x"), list.single().id)
		assertEquals(MangaSourceRef.Data("data:hiperdex"), list.single().source)
		assertEquals("https://hiperdex.com/manga/x", list.single().publicUrl)
	}

	@Test
	fun updatedOrderUsesLatest() = runBlocking {
		val engine = FakeEngine(latest = listOf(EngineManga(id = "/manga/y", title = "Y", url = "/manga/y")))
		val repo = repository(engine)
		assertEquals(1, repo.getList(offset = 0, order = SortOrder.UPDATED, filter = null).size)
		assertTrue(engine.latestCalled)
	}

	@Test
	fun queryAndTagsUseSearch() = runBlocking {
		val engine = FakeEngine(searchResults = listOf(EngineManga(id = "/manga/z", title = "Z", url = "/manga/z")))
		val repo = repository(engine)
		assertEquals(1, repo.getList(offset = 0, order = null, filter = MangaListFilter(query = "solo")).size)
		assertEquals("solo", engine.lastQuery)
		assertEquals("solo", engine.lastFilter?.query)
	}

	@Test
	fun tagOnlyFilterUsesSearchWithTheCatalogueIdStampedOnTheTag() = runBlocking {
		val engine = FakeEngine(searchResults = emptyList())
		val repo = repository(engine)
		repo.getList(
			offset = 0,
			order = SortOrder.POPULARITY,
			filter = MangaListFilter(tags = setOf(LibMangaTag("Action", "action", source))),
		)
		assertEquals(null, engine.lastQuery)
		assertEquals(setOf(EngineTag(title = "Action", key = "action", source = "HIPERDEX")), engine.lastFilter?.tags)
	}

	@Test
	fun detailsKeepMangaIdAndDeriveChapterIds() = runBlocking {
		val chapter = EngineChapter(id = "/c/1", url = "/manga/x/c1", number = 1f, title = "1")
		val engine = FakeEngine(
			details = EngineManga(id = "/manga/x", title = "X", url = "/manga/x", chapters = listOf(chapter)),
		)
		val manga = repository(engine).getDetails(
			Manga(id = "keep", title = "X", url = "/manga/x", source = MangaSourceRef.Data("data:hiperdex")),
		)
		assertEquals("keep", manga.id)
		assertEquals(stableChapterId("HIPERDEX", "/manga/x/c1"), manga.chapters.single().id)
		// A title equal to the chapter number is dropped; the UI already renders "Chapter 1".
		assertEquals("", manga.chapters.single().title)
	}

	@Test
	fun pageRequestMergesRefererPageAndResolvedHeaders() = runBlocking {
		val engine = FakeEngine(resolved = ImageRequest("https://cdn/x.jpg", mapOf("X-Nyora-Mangaplus-Key" to "ab")))
		val request = repository(engine).getPageRequest(
			MangaPage(url = "/p/1", headers = mapOf("Cookie" to "c"), source = source),
		)
		assertEquals("https://cdn/x.jpg", request.url)
		assertEquals(
			mapOf("Referer" to "https://hiperdex.com/", "Cookie" to "c", "X-Nyora-Mangaplus-Key" to "ab"),
			request.headers,
		)
	}

	@Test
	fun domainOverrideFromSettingsWins() = runBlocking {
		val repo = repository(FakeEngine(), domainOverride = { "mirror.example" })
		assertEquals("mirror.example", repo.domain)
		// The Referer has to follow the override too, or the mirror's CDN rejects every page.
		val request = repo.getPageRequest(MangaPage(url = "/p/1"))
		assertEquals("https://mirror.example/", request.headers["Referer"])
	}

	private fun repository(
		engine: SourceEngine,
		domainOverride: () -> String? = { null },
	) = DataDrivenMangaRepository(
		source = source,
		okHttpClient = OkHttpClient(),
		cache = null,
		domainOverride = domainOverride,
		engineFactory = { _, _ -> engine },
	)

	private inner class FakeEngine(
		private val popular: List<EngineManga> = emptyList(),
		private val latest: List<EngineManga> = emptyList(),
		private val searchResults: List<EngineManga> = emptyList(),
		private val details: EngineManga? = null,
		private val resolved: ImageRequest? = null,
	) : SourceEngine {

		var lastPage: Int = -1
			private set

		var latestCalled: Boolean = false
			private set

		var lastQuery: String? = null
			private set

		var lastFilter: EngineFilter? = null
			private set

		override val source: SourceDef = this@DataDrivenMangaRepositoryTest.source.toSourceDef()

		override val availableSortOrders: Set<EngineSortOrder> =
			setOf(EngineSortOrder.POPULARITY, EngineSortOrder.UPDATED)

		override val capabilities: FilterCapabilities = FilterCapabilities()

		override suspend fun getPopular(page: Int): List<EngineManga> {
			lastPage = page
			return popular
		}

		override suspend fun getLatest(page: Int): List<EngineManga> {
			lastPage = page
			latestCalled = true
			return latest
		}

		override suspend fun search(page: Int, query: String?, filter: EngineFilter): List<EngineManga> {
			lastPage = page
			lastQuery = query
			lastFilter = filter
			return searchResults
		}

		override suspend fun getAvailableTags(): Set<EngineTag> = emptySet()

		override suspend fun getDetails(manga: EngineManga): EngineManga = checkNotNull(details)

		override suspend fun getPageList(chapter: EngineChapter): List<EnginePage> = emptyList()

		override suspend fun getPageImageUrl(page: EnginePage): String = page.url

		override suspend fun resolvePageImageRequest(page: EnginePage): ImageRequest =
			resolved ?: ImageRequest(page.url)
	}
}