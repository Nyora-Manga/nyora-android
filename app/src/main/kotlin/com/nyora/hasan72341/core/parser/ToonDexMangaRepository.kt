package com.nyora.hasan72341.core.parser

import com.nyora.hasan72341.core.cache.MemoryContentCache
import com.nyora.hasan72341.core.model.DataDrivenMangaSource
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
import java.time.Instant
import java.util.EnumSet
import java.util.Locale

/**
 * Native ToonTop source (was Toonily.me -> toondex.io -> toontop.io), on its JSON API which the
 * bundled `madtheme` engine no longer matches. Two catalogue rows, `data:toonily_me` and
 * `data:beehentai`, are the same site and are both served here.
 *
 * API endpoints:
 *   GET /titles/search?sort=popular|latest&page=N   and   ?q=<query>&page=N   -> browse/search
 *   GET /titles/{id}                                                          -> details
 *   GET /titles/{id}/chapters?page=N                                          -> chapter list
 *
 * Page images do NOT come from the API — its `/images` route only returns previews — but from the
 * `__NEXT_DATA__` payload of the public reader document, exactly as the shared
 * `ToonDexExtensionService` reads them.
 */
class ToonDexMangaRepository(
	override val source: DataDrivenMangaSource,
	private val okHttpClient: OkHttpClient,
	/** Null disables content caching; only the unit tests, which have no Application, pass null. */
	cache: MemoryContentCache?,
) : CachingMangaRepository(cache), DomainAwareRepository {

	/** Cookies, Referer and the Cloudflare clearance all target the reader host, not the API host. */
	override val domain: String = SITE_DOMAIN

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
		val page = offset / PAGE_SIZE + 1
		val query = filter?.query
		val url = "$API_URL/titles/search".toHttpUrl().newBuilder().apply {
			if (!query.isNullOrBlank()) {
				addQueryParameter("q", query)
			} else {
				addQueryParameter("sort", if ((order ?: defaultSortOrder) == SortOrder.UPDATED) "latest" else "popular")
			}
			addQueryParameter("page", page.toString())
		}.build().toString()
		getJson<Envelope<SearchData>>(url).data.items.map { it.toManga() }
	}

	// -- details + chapters ------------------------------------------------

	override suspend fun getDetailsImpl(manga: Manga): Manga = withContext(Dispatchers.IO) {
		val id = manga.url.trim('/')
		val title = getJson<Envelope<TitleData>>("$API_URL/titles/$id").data.title

		val chapters = ArrayList<MangaChapter>()
		var page = 1
		var hasNext: Boolean
		do {
			val url = "$API_URL/titles/$id/chapters".toHttpUrl().newBuilder()
				.addQueryParameter("page", page.toString())
				.build().toString()
			val data = getJson<Envelope<ChaptersData>>(url).data
			data.chapters.forEach { chapters.add(it.toChapter(id)) }
			hasNext = data.pagination?.hasNext == true
			page++
		} while (hasNext && page <= CHAPTER_PAGE_CAP)

		// The API answers newest first; the app reads and numbers chapters ascending.
		chapters.reverse()
		title.toManga(manga).copy(
			// Keep the caller's id: it is the persisted row's identity, and a details response that
			// normalized the title id would otherwise orphan its history and favourites.
			id = manga.id,
			chapters = chapters.mapIndexed { index, chapter -> chapter.copy(index = index) },
		)
	}

	// -- pages -------------------------------------------------------------

	override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
		val path = chapter.url.trim('/')
		val html = fetchBody(
			"$SITE_URL/$path",
			"text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
		)
		val nextData = Jsoup.parse(html, "$SITE_URL/")
			.selectFirst("script#__NEXT_DATA__")
			?.data()
			?.takeIf { it.isNotBlank() }
			?: error("ToonTop chapter data is unavailable")
		val images = json.decodeFromString<NextDocument>(nextData).props.pageProps.initialChapter.images
		check(images.isNotEmpty()) { "ToonTop chapter has no available pages" }
		images.mapIndexed { index, image ->
			MangaPage(
				url = image,
				headers = imageHeaders,
				id = "${chapter.url}|page|$index",
				index = index,
				source = source,
			)
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String = page.url

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = emptyList()

	// -- http --------------------------------------------------------------

	private val imageHeaders: Map<String, String> = mapOf("Referer" to "$SITE_URL/")

	// Deliberately execute() rather than Call.await(): await() rewraps every failure as
	// `IOException(e.message, e)`, which erases CloudFlareProtectedException and leaves the UI
	// showing a bare "Protected by CloudFlare" with no Solve action.
	private suspend fun fetchBody(url: String, accept: String): String = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url(url)
			// Tag with the source so CloudFlareInterceptor can attach it to the exception it throws;
			// without it a challenge has no Solve action and this source can never be cleared.
			.tag(MangaSource::class.java, source)
			.header("Referer", "$SITE_URL/")
			.header("Accept", accept)
			.get()
			.build()
		okHttpClient.newCall(request).execute().use { response ->
			check(response.isSuccessful) { "${source.name} request failed with HTTP ${response.code}: $url" }
			response.body.string()
		}
	}

	private suspend inline fun <reified T> getJson(url: String): T =
		json.decodeFromString(fetchBody(url, "application/json"))

	private fun TitleItem.toManga(): Manga = Manga(
		id = stableMangaId(source.catalogueId, id),
		title = name,
		url = id,
		publicUrl = SITE_URL + url,
		isNsfw = source.nsfw,
		coverUrl = cover.orEmpty(),
		largeCoverUrl = cover,
		source = sourceRef,
	)

	private fun TitleFull.toManga(seed: Manga): Manga {
		val tags = buildList {
			genres?.forEach { add(it.name) }
			themes?.forEach { add(it.name) }
		}.distinct().map { MangaTag(key = it, title = it) }
		return Manga(
			id = stableMangaId(source.catalogueId, id),
			title = name,
			url = id,
			publicUrl = SITE_URL + (url ?: "/$slug"),
			isNsfw = source.nsfw,
			coverUrl = cover.orEmpty().ifEmpty { seed.coverUrl },
			largeCoverUrl = cover ?: seed.largeCoverUrl,
			authors = authors?.map { it.name }.orEmpty(),
			description = summary?.let { Jsoup.parse(it).text() }.orEmpty(),
			tags = tags,
			state = when (status?.lowercase(Locale.ROOT)) {
				"ongoing", "releasing" -> MangaState.ONGOING
				"completed", "finished" -> MangaState.FINISHED
				"hiatus", "on hold" -> MangaState.PAUSED
				"cancelled", "dropped" -> MangaState.ABANDONED
				else -> null
			},
			source = sourceRef,
		)
	}

	private fun ChapterItem.toChapter(mangaId: String): MangaChapter {
		// The "<mangaSlug>/<chapterSlug>" path (the API's url field) drives the reader page route.
		val path = (url ?: "$mangaId/$id").trim('/')
		return MangaChapter(
			id = stableChapterId(source.catalogueId, path),
			title = name ?: number?.let { "Chapter ${it.toString().removeSuffix(".0")}" } ?: "Chapter",
			number = number ?: 0f,
			url = path,
			uploadDate = updatedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L,
		)
	}

	companion object {

		/** Catalogue identities this repository serves: the same site under two catalogue rows. */
		val SOURCE_NAMES = setOf("data:toonily_me", "data:beehentai")

		private const val SITE_DOMAIN = "toontop.io"
		private const val SITE_URL = "https://$SITE_DOMAIN"
		private const val API_URL = "https://api.toontop.io"
		/** Titles the search API answers with per page; the browse offset steps by it. */
		private const val PAGE_SIZE = 20

		/** Guard against an API that never stops claiming another page of chapters. */
		private const val CHAPTER_PAGE_CAP = 200
	}
}

// -- API DTOs ---------------------------------------------------------------

@Serializable
private class Envelope<T>(val data: T)

@Serializable
private class Pagination(@SerialName("has_next") val hasNext: Boolean = false)

@Serializable
private class SearchData(val items: List<TitleItem> = emptyList(), val pagination: Pagination? = null)

@Serializable
private class TitleItem(
	val id: String,
	val url: String,
	val name: String,
	val cover: String? = null,
)

@Serializable
private class TitleData(val title: TitleFull)

@Serializable
private class TitleFull(
	val id: String,
	val url: String? = null,
	val name: String,
	val slug: String? = null,
	val cover: String? = null,
	val summary: String? = null,
	val status: String? = null,
	val authors: List<NamedRef>? = null,
	val genres: List<NamedRef>? = null,
	val themes: List<NamedRef>? = null,
)

@Serializable
private class NamedRef(val name: String)

@Serializable
private class ChaptersData(val chapters: List<ChapterItem> = emptyList(), val pagination: Pagination? = null)

@Serializable
private class ChapterItem(
	val id: String,
	val url: String? = null,
	val name: String? = null,
	val number: Float? = null,
	@SerialName("updated_at") val updatedAt: String? = null,
)

// Public reader document: { props: { pageProps: { initialChapter: { images: [...] } } } }

@Serializable
private class NextDocument(val props: NextDocumentProps)

@Serializable
private class NextDocumentProps(val pageProps: NextPageProps)

@Serializable
private class NextPageProps(val initialChapter: NextChapter)

@Serializable
private class NextChapter(val images: List<String> = emptyList())
