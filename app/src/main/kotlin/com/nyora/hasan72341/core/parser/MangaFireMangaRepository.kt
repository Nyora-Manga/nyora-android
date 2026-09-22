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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.EnumSet
import java.util.Locale

/**
 * Native MangaFire source. MangaFire relaunched on a JSON API (`/api/titles`) and now requires a
 * build-specific `vrf` signature on its protected routes, which the bundled `mangafire` engine key
 * has no generic implementation for — the catalogue keeps those rows precisely so this repository
 * can serve them.
 *
 * One catalogue is shared across every `MANGAFIRE_*` row; the rows differ only in the chapter
 * language they list, taken from the catalogue id's suffix.
 */
class MangaFireMangaRepository(
	override val source: DataDrivenMangaSource,
	private val okHttpClient: OkHttpClient,
	/** Null disables content caching; only the unit tests, which have no Application, pass null. */
	cache: MemoryContentCache?,
) : CachingMangaRepository(cache), DomainAwareRepository {

	override val domain: String = SITE_DOMAIN

	/** MangaFire's own language code, derived from the catalogue id's suffix. */
	private val langCode: String = mangaFireLanguageCode(source.catalogueId)

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
		val params = buildList {
			when {
				!query.isNullOrBlank() -> add("keyword" to query)
				(order ?: defaultSortOrder) == SortOrder.UPDATED -> add("order[chapter_updated_at]" to "desc")
				else -> add("order[views_30d]" to "desc")
			}
			add("page" to page.toString())
			add("limit" to PAGE_SIZE.toString())
		}
		getJson<ApiResponse<MangaDto>>(protectedApiUrl("/titles", params)).items.map { it.toManga() }
	}

	// -- details + chapters ------------------------------------------------

	override suspend fun getDetailsImpl(manga: Manga): Manga = withContext(Dispatchers.IO) {
		val hid = manga.url.hid()
		val details = getJson<MangaDetailsResponse>(protectedApiUrl("/titles/$hid")).data

		val chapters = ArrayList<MangaChapter>()
		var page = 1
		var lastPage = 1
		do {
			val params = listOf(
				"language" to langCode,
				"sort" to "number",
				"order" to "desc",
				"page" to page.toString(),
				"limit" to CHAPTER_PAGE_SIZE.toString(),
			)
			val response = getJson<ApiResponse<ChapterDto>>(protectedApiUrl("/titles/$hid/chapters", params))
			// `meta.lastPage` is the server's word, re-read on every response, so it alone cannot
			// end the walk: an API answering with a large last page and no items would hold the
			// details screen through one sequential request after another. The empty batch and the
			// cap below are what actually stop it.
			if (response.items.isEmpty()) break
			response.items.forEach { chapters.add(it.toChapter(manga.url)) }
			lastPage = response.meta?.lastPage ?: 1
			page++
		} while (page <= lastPage && page <= CHAPTER_PAGE_CAP)

		// The API answers newest first (order=desc); the app reads and numbers chapters ascending.
		chapters.reverse()
		details.toManga(manga).copy(
			// Keep the caller's id: it is the persisted row's identity, and a details response whose
			// slug has since changed would otherwise orphan its history and favourites.
			id = manga.id,
			chapters = chapters.mapIndexed { index, chapter -> chapter.copy(index = index) },
		)
	}

	// -- pages -------------------------------------------------------------

	override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
		val segments = (BASE_URL + chapter.url).toHttpUrl().pathSegments
		val last = segments.last()
		val relativePath = if (segments.contains("volume")) {
			"/volumes/$last"
		} else {
			"/chapters/${last.substringBefore("-")}"
		}
		// Plain CDN images (no scrambling); the requests still need the site Referer.
		getJson<PagesResponse>(protectedApiUrl(relativePath)).data.pages.mapIndexed { index, page ->
			MangaPage(
				url = page.url,
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

	private val imageHeaders: Map<String, String> = mapOf("Referer" to "$BASE_URL/")

	/** Every protected route carries a `vrf` signature over its path and canonicalized query. */
	private fun protectedApiUrl(
		relativePath: String,
		params: List<Pair<String, String>> = emptyList(),
	): HttpUrl = "$BASE_URL/api$relativePath".toHttpUrl().newBuilder()
		.apply { params.forEach { (field, value) -> addQueryParameter(field, value) } }
		.addQueryParameter("vrf", MangaFireVrf.token(relativePath, params))
		.build()

	private suspend inline fun <reified T> getJson(url: HttpUrl): T = json.decodeFromString(fetchBody(url))

	// Deliberately execute() rather than Call.await(): await() rewraps every failure as
	// `IOException(e.message, e)`, which erases CloudFlareProtectedException and leaves the UI
	// showing a bare "Protected by CloudFlare" with no Solve action.
	private suspend fun fetchBody(url: HttpUrl): String = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url(url)
			// Tag with the source so CloudFlareInterceptor can attach it to the exception it throws;
			// MangaFire is behind Cloudflare and without this a challenge has no Solve action.
			.tag(MangaSource::class.java, source)
			.header("Referer", "$BASE_URL/")
			.header("Accept", "application/json")
			.header("X-Requested-With", "XMLHttpRequest")
			.get()
			.build()
		okHttpClient.newCall(request).execute().use { response ->
			check(response.isSuccessful) {
				"${source.name} request failed with HTTP ${response.code}: ${url.encodedPath}"
			}
			response.body.string()
		}
	}

	// MangaFire manga urls look like /title/<hid>-<slug>; the hid is the leading token.
	private fun String.hid(): String {
		val last = removeSuffix("/").substringAfterLast('/')
		return when {
			last.contains('.') -> last.substringAfterLast('.')
			last.contains('-') -> last.substringBefore('-')
			else -> last
		}
	}

	private fun MangaDto.toManga(): Manga {
		val mangaUrl = "/title/$hid${slug?.let { "-$it" }.orEmpty()}"
		return Manga(
			id = stableMangaId(source.catalogueId, mangaUrl),
			title = title,
			url = mangaUrl,
			publicUrl = BASE_URL + mangaUrl,
			isNsfw = source.nsfw,
			coverUrl = (poster?.large ?: poster?.medium ?: poster?.small).orEmpty(),
			largeCoverUrl = poster?.large,
			source = sourceRef,
		)
	}

	private fun MangaDetailsDto.toManga(seed: Manga): Manga {
		val mangaUrl = "/title/$hid${slug?.let { "-$it" }.orEmpty()}"
		val authorNames = buildList {
			authors?.forEach { add(it.title) }
			artists?.forEach { add(it.title) }
		}.distinct()
		val tags = buildList {
			type?.replaceFirstChar { it.uppercaseChar() }?.let { add(it) }
			genres?.forEach { add(it.title) }
			themes?.forEach { add(it.title) }
		}.map { MangaTag(key = it, title = it) }
		return Manga(
			id = stableMangaId(source.catalogueId, mangaUrl),
			title = title,
			url = mangaUrl,
			publicUrl = BASE_URL + mangaUrl,
			isNsfw = source.nsfw,
			coverUrl = (poster?.large ?: poster?.medium ?: poster?.small)
				.orEmpty().ifEmpty { seed.coverUrl },
			largeCoverUrl = poster?.large ?: seed.largeCoverUrl,
			authors = authorNames,
			description = synopsisHtml?.let { Jsoup.parse(it).text() }.orEmpty(),
			tags = tags,
			state = when (status?.lowercase(Locale.ROOT)) {
				"releasing" -> MangaState.ONGOING
				"finished" -> MangaState.FINISHED
				"on_hiatus" -> MangaState.PAUSED
				"discontinued" -> MangaState.ABANDONED
				else -> null
			},
			source = sourceRef,
		)
	}

	private fun ChapterDto.toChapter(mangaUrl: String): MangaChapter {
		val numStr = number.toString().removeSuffix(".0")
		val url = "$mangaUrl/$id-chapter-$numStr-$langCode"
		return MangaChapter(
			id = stableChapterId(source.catalogueId, url),
			title = buildString {
				append("Ch. ")
				append(numStr)
				if (!name.isNullOrBlank()) {
					append(" - ")
					append(name)
				}
			},
			number = number,
			url = url,
			scanlator = type,
			uploadDate = (createdAt ?: 0L) * 1000L,
		)
	}

	companion object {

		/** Catalogue engine key routed here; no generic engine can sign MangaFire's API. */
		const val ENGINE_KEY = "mangafire"

		private const val SITE_DOMAIN = "mangafire.to"
		private const val BASE_URL = "https://$SITE_DOMAIN"
		/** Titles asked for per browse request; the browse offset steps by it. */
		private const val PAGE_SIZE = 50
		private const val CHAPTER_PAGE_SIZE = 200

		/**
		 * Ceiling on the chapter walk: [CHAPTER_PAGE_SIZE] x this is 40,000 chapters, past anything
		 * the site carries, so it only ever fires on a misbehaving response.
		 */
		private const val CHAPTER_PAGE_CAP = 200
	}
}

/** `MANGAFIRE_ES_LA` -> `es-la`; every unexpected suffix falls back to English. */
internal fun mangaFireLanguageCode(catalogueId: String): String =
	when (catalogueId.uppercase(Locale.ROOT).removePrefix("MANGAFIRE_")) {
		"ES" -> "es"
		"ES_LA", "ESLA" -> "es-la"
		"FR" -> "fr"
		"JA" -> "ja"
		"PT" -> "pt"
		"PT_BR", "PTBR" -> "pt-br"
		else -> "en"
	}

// -- API DTOs (ported from the verified Mihon MangaFire reference) -----------

@Serializable
private class ApiResponse<T>(val items: List<T> = emptyList(), val meta: ApiMeta? = null)

@Serializable
private class ApiMeta(val lastPage: Int = 1)

@Serializable
private class MangaDto(
	val hid: String,
	val slug: String? = null,
	val title: String,
	val poster: PosterDto? = null,
)

@Serializable
private class MangaDetailsResponse(val data: MangaDetailsDto)

@Serializable
private class MangaDetailsDto(
	val hid: String,
	val slug: String? = null,
	val title: String,
	val type: String? = null,
	val status: String? = null,
	val poster: PosterDto? = null,
	val synopsisHtml: String? = null,
	val authors: List<EntityDto>? = null,
	val artists: List<EntityDto>? = null,
	val genres: List<EntityDto>? = null,
	val themes: List<EntityDto>? = null,
)

@Serializable
private class PosterDto(val small: String? = null, val medium: String? = null, val large: String? = null)

@Serializable
private class EntityDto(val title: String)

@Serializable
private class ChapterDto(
	val id: Int,
	val number: Float,
	val name: String? = null,
	val createdAt: Long? = null,
	val type: String? = null,
)

@Serializable
private class PagesResponse(val data: ChapterDataDto)

@Serializable
private class ChapterDataDto(val pages: List<PageDto>)

@Serializable
private class PageDto(val url: String)
