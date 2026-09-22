package com.nyora.hasan72341.core.parser

import com.nyora.hasan72341.core.SourcePatches
import com.nyora.hasan72341.core.cache.MemoryContentCache
import com.nyora.hasan72341.core.model.DataDrivenMangaSource
import com.nyora.hasan72341.core.parser.datadriven.dataDrivenPatchValue
import com.nyora.hasan72341.core.parser.datadriven.stableChapterId
import com.nyora.hasan72341.core.parser.datadriven.stableMangaId
import com.nyora.hasan72341.mihon.parsers.model.Manga
import com.nyora.hasan72341.mihon.parsers.model.MangaChapter
import com.nyora.hasan72341.mihon.parsers.model.MangaListFilter
import com.nyora.hasan72341.mihon.parsers.model.MangaListFilterCapabilities
import com.nyora.hasan72341.mihon.parsers.model.MangaListFilterOptions
import com.nyora.hasan72341.mihon.parsers.model.MangaPage
import com.nyora.hasan72341.mihon.parsers.model.MangaSource
import com.nyora.hasan72341.mihon.parsers.model.MangaSourceRef
import com.nyora.hasan72341.mihon.parsers.model.MangaState
import com.nyora.hasan72341.mihon.parsers.model.MangaTag
import com.nyora.hasan72341.mihon.parsers.model.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

/**
 * Native source for the MangaNato / Mangakakalot / MangaNelo / MangaBat platform — the Android
 * counterpart of the shared `NatoExtensionService`, so every Nyora client parses these sites the
 * same way.
 *
 * All of these domains are one deployment running the same rewritten stack, which the generic
 * `mangabox` engine only partly matches:
 *
 * - the browse grid is `div.list-comic-item-wrap`, and the page ALSO renders a `.item` hero slider
 *   that the engine's container fallback chain matches first — so every browse page returned the
 *   same ~20 slider covers regardless of page or genre;
 * - search uses `div.story_item` instead, so one selector has to cover both;
 * - the chapter list left the title page entirely: `ul.row-content-chapter` is gone, replaced by a
 *   JSON API at `/api/manga/<slug>/chapters`. Scraping yielded a title with zero chapters, which is
 *   why these could be opened but never read;
 * - covers and pages live on `*.2xstorage.com` and 403 without a Referer of the site.
 *
 * The JSON API is paginated by `offset`/`limit` and its 50-item default silently truncates long
 * series, so it has to be walked to the end.
 */
class NatoMangaRepository(
	override val source: DataDrivenMangaSource,
	private val okHttpClient: OkHttpClient,
	/** Null disables content caching; only the unit tests, which have no Application, pass null. */
	cache: MemoryContentCache?,
	private val domainOverride: () -> String? = { null },
) : CachingMangaRepository(cache), DomainAwareRepository {

	/**
	 * Live domain, most specific first: the user's override, then the shipped domain patch, then the
	 * catalogue value, then the built-in map. The patch is consulted directly rather than only
	 * through the catalogue so a stale cached catalogue cannot pin the source to a dead host — the
	 * Referer is derived from this and the image CDNs 403 a mismatched one.
	 */
	override val domain: String
		get() = domainOverride()?.takeIf { it.isNotBlank() }
			?: SourcePatches.DOMAIN_OVERRIDES.dataDrivenPatchValue(source.catalogueId)
			?: source.domain.takeIf { it.isNotBlank() }
			?: DOMAINS[source.catalogueId.lowercase(Locale.ROOT)]
			?: DEFAULT_DOMAIN

	private val baseUrl: String get() = "https://$domain"

	/** Every one of these sites hotlink-protects its image CDN, so images carry the Referer. */
	private val imageHeaders: Map<String, String> get() = mapOf("Referer" to "$baseUrl/")

	private val sourceRef: MangaSourceRef = MangaSourceRef.Data(source.name)

	private val json = Json { ignoreUnknownKeys = true }

	override val sortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED)

	override var defaultSortOrder: SortOrder
		get() = SortOrder.POPULARITY
		set(value) = Unit

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(isSearchSupported = true)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

	// -- browse ------------------------------------------------------------

	override suspend fun getList(
		offset: Int,
		order: SortOrder?,
		filter: MangaListFilter?,
	): List<Manga> = withContext(Dispatchers.IO) {
		val page = (offset / PAGE_SIZE + 1).coerceAtLeast(1)
		val query = filter?.query?.takeIf { it.isNotBlank() }
		if (query != null) {
			// Search goes through the site's own JSON endpoint rather than /search/story/.
			// Cloudflare challenges the HTML pages but not this one, so search keeps working before
			// any clearance cookie exists. It answers with a single un-paginated batch.
			return@withContext if (page > 1) emptyList() else searchApi(query)
		}
		val url = if (order == SortOrder.UPDATED) {
			"$baseUrl/manga-list/latest-manga?page=$page"
		} else {
			"$baseUrl/manga-list/hot-manga?page=$page"
		}
		fetchDocument(url).select(SELECT_CARD).mapNotNull { it.toManga() }
	}

	/**
	 * The endpoint answers with a JSON array for single-token queries and with the rendered
	 * search-results HTML for multi-word ones (observed on all of these domains, independent of how
	 * the space is encoded). Accept both — the HTML uses the same story_item cards as [SELECT_CARD],
	 * so a multi-word search still returns results instead of silently coming back empty.
	 */
	private suspend fun searchApi(query: String): List<Manga> {
		val url = "$baseUrl/home/search/json".toHttpUrl().newBuilder()
			.addQueryParameter("searchword", query)
			.build()
			.toString()
		val body = fetchBody(url, asJson = true)
		if (!body.trimStart().startsWith("[")) {
			return Jsoup.parse(body, baseUrl).select(SELECT_CARD).mapNotNull { it.toManga() }
		}
		val hits = runCatching { json.decodeFromString<List<SearchDto>>(body) }.getOrNull().orEmpty()
		return hits.mapNotNull { dto ->
			val slug = dto.slug.ifBlank { dto.url.trimEnd('/').substringAfterLast('/') }
			if (slug.isBlank() || dto.name.isBlank()) return@mapNotNull null
			val path = "/manga/$slug"
			Manga(
				id = stableMangaId(source.catalogueId, path),
				title = dto.name.trim(),
				url = path,
				publicUrl = baseUrl + path,
				isNsfw = source.nsfw,
				coverUrl = dto.thumb,
				authors = dto.author.split(',').map { it.trim() }.filter { it.isNotEmpty() },
				source = sourceRef,
			)
		}
	}

	private fun Element.toManga(): Manga? {
		val anchor = selectFirst("a.list-story-item") ?: selectFirst("h3 a") ?: selectFirst("a[href]") ?: return null
		val path = anchor.absUrl("href").ifBlank { return null }.toPathOrNull() ?: return null
		// Sponsored rows sit in the grid wearing the same classes as real cards and link to a short
		// redirect. Every real title lives under /manga/<slug>.
		if (!path.startsWith("/manga/")) return null
		val title = (selectFirst("h3 a")?.text() ?: anchor.attr("title").ifBlank { anchor.text() }).trim()
		if (title.isEmpty()) return null
		val image = selectFirst("img")
		val cover = image?.attr("data-src")?.takeIf { it.isNotBlank() } ?: image?.attr("src").orEmpty()
		return Manga(
			id = stableMangaId(source.catalogueId, path),
			title = title,
			url = path,
			publicUrl = baseUrl + path,
			isNsfw = source.nsfw,
			coverUrl = cover,
			source = sourceRef,
		)
	}

	// -- details -----------------------------------------------------------

	override suspend fun getDetailsImpl(manga: Manga): Manga = withContext(Dispatchers.IO) {
		val path = manga.url.toPathOrNull() ?: manga.url
		// Chapters come from the JSON API, which Cloudflare does not challenge, while the detail
		// page HTML does get challenged. Fetch them first and independently so an unsolved (or
		// expired) clearance costs only the extra metadata, not the whole chapter list.
		val chapters = runCatching { chapters(path) }.getOrNull().orEmpty()
		val document = runCatching { fetchDocument(baseUrl + path) }.getOrNull()
			?: return@withContext manga.copy(chapters = chapters)
		val info = document.select("div.manga-info-top li").associate { row ->
			row.text().substringBefore(':').trim().lowercase(Locale.ROOT) to row.text().substringAfter(':').trim()
		}
		manga.copy(
			title = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: manga.title,
			altTitles = info["alternative"]?.split('/', ';')?.map { it.trim() }?.filter { it.isNotEmpty() }
				.orEmpty(),
			coverUrl = document.ogContent("og:image").ifEmpty { manga.coverUrl },
			state = when (info["status"]?.lowercase(Locale.ROOT)) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				else -> null
			},
			authors = info["author(s)"]?.split(',')?.map { it.trim() }
				?.filter { it.isNotEmpty() && it != "Updating" }.orEmpty(),
			// The 2026 redesign moved the summary to div.description; the older markup stays as a
			// fallback for mirrors still serving the previous skin.
			description = document
				.selectFirst("div.description, #panel-story-info-description, div.panel-story-info-description")
				?.text()?.substringAfter("Description :")?.trim()
				?: document.ogContent("og:description"),
			// Only the genres listed for THIS title — the page also renders the site's full genre
			// menu, and selecting every /genre/ link would tag it with all of them.
			tags = document.select("div.manga-info-top li:contains(Genres) a").mapNotNull { it.toTag() },
			chapters = chapters,
		)
	}

	private fun Element.toTag(): MangaTag? {
		val title = text().trim().ifEmpty { return null }
		return MangaTag(key = attr("href").trimEnd('/').substringAfterLast('/'), title = title)
	}

	/**
	 * Chapters come from the JSON API the title page loads them with — newest first, paginated by
	 * `offset`/`limit`. The 50-item default truncated long series (Martial Peak returned 50 of
	 * 3877), so walk until the API says there is no more. `page`/`per_page` are ignored by the
	 * endpoint; only `offset` and `limit` do anything.
	 */
	private suspend fun chapters(mangaPath: String): List<MangaChapter> {
		val slug = mangaPath.trimEnd('/').substringAfterLast('/')
		// The site's own reader asks for limit=-1 and gets every chapter in one response (verified
		// 3877/3877 on Martial Peak), so the default is a single request. The offset walk below is
		// kept only for a mirror that ignores the sentinel and caps the page — without it such a
		// mirror would silently truncate long series.
		val first = fetchChapters(slug, offset = 0, limit = ALL_CHAPTERS)
		val all = ArrayList(first?.data?.chapters.orEmpty())
		val total = first?.data?.pagination?.total ?: 0
		if (all.isNotEmpty() && all.size < total) {
			var offset = all.size
			while (all.size < total && all.size <= CHAPTER_HARD_CAP) {
				val batch = fetchChapters(slug, offset, CHAPTER_PAGE_SIZE)?.data?.chapters.orEmpty()
				if (batch.isEmpty()) break
				all += batch
				offset += batch.size
			}
		}
		// The API answers newest first; the app reads and numbers chapters ascending.
		return all.asReversed().mapIndexed { index, dto ->
			val url = "$mangaPath/${dto.slug}"
			MangaChapter(
				id = stableChapterId(source.catalogueId, url),
				title = dto.name,
				number = dto.number ?: (index + 1f),
				url = url,
				uploadDate = dto.updatedAt.toEpochMillis(),
				index = index,
			)
		}
	}

	private suspend fun fetchChapters(slug: String, offset: Int, limit: Int): ChaptersResponse? {
		val body = fetchBody("$baseUrl/api/manga/$slug/chapters?offset=$offset&limit=$limit", asJson = true)
		return runCatching { json.decodeFromString<ChaptersResponse>(body) }.getOrNull()
	}

	// -- reader ------------------------------------------------------------

	override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
		val path = chapter.url.toPathOrNull() ?: chapter.url
		fetchDocument(baseUrl + path)
			.select("div.container-chapter-reader img")
			// Sourced first, indexed second: a lazy-loaded img with neither attribute must not leave
			// a hole in the page numbering.
			.mapNotNull { image ->
				image.attr("data-src").takeIf { it.isNotBlank() } ?: image.attr("src").takeIf { it.isNotBlank() }
			}
			.mapIndexed { index, src ->
				MangaPage(
					url = src,
					headers = imageHeaders,
					id = "${chapter.url}|page|$index",
					index = index,
					source = source,
				)
			}
	}

	override suspend fun getPageUrl(page: MangaPage): String = page.url

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = emptyList()

	// -- plumbing ----------------------------------------------------------

	private suspend fun fetchDocument(url: String): Document = Jsoup.parse(fetchBody(url, asJson = false), url)

	// Deliberately execute() rather than Call.await(): await() rewraps every failure as
	// `IOException(e.message, e)`, which erases CloudFlareProtectedException and leaves the UI
	// showing a bare "Protected by CloudFlare" with no Solve action.
	private suspend fun fetchBody(url: String, asJson: Boolean): String = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url(url)
			// Tag with the source so CloudFlareInterceptor can attach it to the exception it throws
			// — without it the challenge surfaces as a bare "Protected by CloudFlare" error with no
			// Solve action, and these sites can never be cleared.
			.tag(MangaSource::class.java, source)
			.header("Referer", "$baseUrl/")
			.apply {
				if (asJson) {
					header("Accept", "application/json")
					header("X-Requested-With", "XMLHttpRequest")
				}
			}
			.get()
			.build()
		okHttpClient.newCall(request).execute().use { response ->
			check(response.isSuccessful) { "${source.name} request failed with HTTP ${response.code}: $url" }
			response.body.string()
		}
	}

	/** Absolute site URL -> the stored path, so a domain move never invalidates saved urls. */
	private fun String.toPathOrNull(): String? = when {
		startsWith("/") -> trimEnd('/')
		startsWith("http") -> runCatching { URI(this).path.trimEnd('/') }
			.getOrNull()?.takeIf { it.isNotEmpty() }

		else -> null
	}

	private fun Document.ogContent(property: String): String =
		selectFirst("meta[property=$property]")?.attr("content")?.trim().orEmpty()

	private fun String?.toEpochMillis(): Long {
		if (isNullOrBlank()) return 0L
		return runCatching {
			SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
				.apply { timeZone = TimeZone.getTimeZone("UTC") }
				.parse(substringBefore('.').removeSuffix("Z"))
				?.time ?: 0L
		}.getOrDefault(0L)
	}

	@Serializable
	private class SearchDto(
		val name: String = "",
		val slug: String = "",
		val url: String = "",
		val thumb: String = "",
		val author: String = "",
	)

	@Serializable
	private class ChaptersResponse(val data: ChaptersData)

	@Serializable
	private class ChaptersData(
		val chapters: List<ChapterDto> = emptyList(),
		val pagination: Pagination? = null,
	)

	@Serializable
	private class Pagination(val total: Int? = null)

	@Serializable
	private class ChapterDto(
		@SerialName("chapter_name") val name: String = "",
		@SerialName("chapter_slug") val slug: String = "",
		@SerialName("chapter_num") val number: Float? = null,
		@SerialName("updated_at") val updatedAt: String? = null,
	)

	companion object {

		/**
		 * Catalogue identities this repository serves. mangakakalottv is the same deployment as
		 * mangakakalot (mangakakalot.tv folded into .gg), and MangaBat now uses the same JSON
		 * chapter API. All are served here because the generic engine cannot read that chapter list.
		 */
		val SOURCE_NAMES = setOf(
			"data:manganato",
			"data:manganelo",
			"data:mangakakalot",
			"data:mangakakalottv",
			"data:mangabat",
		)

		private const val DEFAULT_DOMAIN = "www.natomanga.com"

		/** Cards the site's own manga-list grid renders per page; the browse offset steps by it. */
		private const val PAGE_SIZE = 24

		private val DOMAINS = mapOf(
			"manganato" to "www.natomanga.com",
			"manganelo" to "www.nelomanga.net",
			"mangakakalot" to "www.mangakakalot.gg",
			"mangakakalottv" to "www.mangakakalot.gg",
			"mangabat" to "www.mangabats.com",
		)

		/** Sentinel the site's own reader uses: return every chapter in one response. */
		private const val ALL_CHAPTERS = -1

		/** Fallback page size, only used if a mirror ignores [ALL_CHAPTERS]. */
		private const val CHAPTER_PAGE_SIZE = 500
		private const val CHAPTER_HARD_CAP = 20_000

		// Browse and search render different cards: `list-comic-item-wrap` on the manga lists (which
		// also carries hidden ad placeholders wearing the same class) and `story_item` on the search
		// results page.
		private const val SELECT_CARD = "div.list-comic-item-wrap:not([hidden]), div.story_item"
	}
}
