package com.nyora.hasan72341.core.parser

import com.nyora.hasan72341.core.cache.MemoryContentCache
import com.nyora.hasan72341.core.model.DataDrivenMangaSource
import com.nyora.hasan72341.core.network.MangaPlusImageInterceptor
import com.nyora.hasan72341.core.parser.datadriven.ProtoMessage
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
import com.nyora.hasan72341.mihon.parsers.model.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.EnumSet
import java.util.Locale
import java.util.UUID

/**
 * Native source for MANGA Plus by SHUEISHA — the official free-manga service.
 *
 * The catalogue marks every `MANGAPLUSPARSER_*` row broken because the generic route asks the API
 * for `format=json`, a convenience mode the service used to offer and no longer does: every such
 * request comes back as a bare nginx `403 Forbidden`, which reads exactly like a geo-block and is
 * why this looked unfixable. The API itself is fine. Its own web client speaks protobuf and needs
 * three things:
 *
 *  - the `SESSION-TOKEN` header (any UUID; it is a session id, not a credential),
 *  - `Origin`/`Referer` of `mangaplus.shueisha.co.jp`,
 *  - and crucially *no* `format` parameter at all.
 *
 * Responses are `application/x-protobuf`, decoded by [ProtoMessage]. The field numbers below were
 * derived by decoding live responses rather than from a `.proto`, so each one is named for what it
 * was observed to carry.
 *
 * MANGA Plus publishes the same catalogue in several languages and the catalogue splits that into
 * one row per language. The API returns every language in one list and tags each title with its
 * own, so each row filters the shared catalogue down to [languageId] — the split is real, not
 * cosmetic: a title exists separately per language with its own id, chapters and covers.
 *
 * Page images are XOR-encrypted with a per-page key which rides on the page's own headers and is
 * undone by [MangaPlusImageInterceptor].
 */
class MangaPlusMangaRepository(
	override val source: DataDrivenMangaSource,
	private val okHttpClient: OkHttpClient,
	/** Null disables content caching; only the unit tests, which have no Application, pass null. */
	cache: MemoryContentCache?,
) : CachingMangaRepository(cache), DomainAwareRepository {

	override val domain: String = SITE_DOMAIN

	/** The API's language id for this row, e.g. `MANGAPLUSPARSER_ES` -> 1 (Spanish). */
	private val languageId: Int =
		LANGUAGES[source.catalogueId.substringAfterLast('_').uppercase(Locale.ROOT)] ?: LANGUAGE_ENGLISH

	private val sourceRef: MangaSourceRef = MangaSourceRef.Data(source.name)

	/** The whole catalogue in one response, so browsing and searching do not re-download it. */
	@Volatile
	private var cachedCatalogue: Pair<Long, List<Manga>>? = null

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
		val query = filter?.query?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
		val all = when {
			query != null -> catalogue().filter { manga ->
				manga.title.lowercase(Locale.ROOT).contains(query) ||
					manga.authors.any { it.lowercase(Locale.ROOT).contains(query) }
			}

			order == SortOrder.UPDATED || order == SortOrder.NEWEST -> ranking().ifEmpty { catalogue() }
			else -> catalogue()
		}
		// There is no server-side paging — the API hands over the whole list, as it does to the
		// official clients — so the browse window is cut straight out of it by offset.
		if (offset >= all.size) emptyList() else all.subList(offset, minOf(offset + PAGE_SIZE, all.size)).toList()
	}

	/**
	 * `title_list/allV2` is the whole catalogue in one response: no paging and no server-side
	 * search. Cached briefly so a scroll does not re-download it for every window.
	 */
	private suspend fun catalogue(): List<Manga> {
		cachedCatalogue
			?.takeIf { System.currentTimeMillis() - it.first < CATALOGUE_TTL_MS }
			?.let { return it.second }
		val titles = api("/title_list/allV2")
			.message(FIELD_SUCCESS)
			?.message(FIELD_ALL_TITLES)
			?.messages(FIELD_ALL_TITLES_GROUP)
			.orEmpty()
			.flatMap { group -> group.messages(FIELD_GROUP_TITLES) }
			.filter { (it.int(TITLE_LANGUAGE) ?: LANGUAGE_ENGLISH) == languageId }
			.mapNotNull { it.toManga() }
		cachedCatalogue = System.currentTimeMillis() to titles
		return titles
	}

	/** The catalogue is alphabetical; "updated" is the ranking endpoint's ordering. */
	private suspend fun ranking(): List<Manga> = runCatching {
		api("/title_list/rankingV2?type=hottest")
			.message(FIELD_SUCCESS)
			?.message(FIELD_RANKING)
			?.messages(FIELD_RANKING_GROUP)
			.orEmpty()
			.flatMap { it.messages(FIELD_GROUP_TITLES) }
			.filter { (it.int(TITLE_LANGUAGE) ?: LANGUAGE_ENGLISH) == languageId }
			.mapNotNull { it.toManga() }
	}.getOrDefault(emptyList())

	private fun ProtoMessage.toManga(): Manga? {
		val id = long(TITLE_ID) ?: return null
		val name = string(TITLE_NAME)?.takeIf { it.isNotBlank() } ?: return null
		val url = "/titles/$id"
		return Manga(
			id = stableMangaId(source.catalogueId, url),
			title = name,
			url = url,
			publicUrl = "$BASE_URL$url",
			isNsfw = source.nsfw,
			coverUrl = string(TITLE_PORTRAIT).orEmpty(),
			authors = listOfNotNull(string(TITLE_AUTHOR)?.takeIf { it.isNotBlank() }),
			source = sourceRef,
		)
	}

	// -- details -----------------------------------------------------------

	override suspend fun getDetailsImpl(manga: Manga): Manga = withContext(Dispatchers.IO) {
		val titleId = manga.url.trimEnd('/').substringAfterLast('/')
		val detail = api("/title_detailV3?title_id=$titleId")
			.message(FIELD_SUCCESS)
			?.message(FIELD_TITLE_DETAIL)
			?: error("${source.name} returned no detail for title $titleId")
		val title = detail.message(DETAIL_TITLE)

		// Chapters arrive in groups (the site paginates them as "1-50", "51-100", …); the groups are
		// already in reading order, as are the chapters inside each.
		val chapters = detail.messages(DETAIL_CHAPTER_GROUPS)
			.flatMap { it.messages(GROUP_CHAPTERS) }
			.mapIndexedNotNull { index, chapter ->
				val chapterId = chapter.long(CHAPTER_ID) ?: return@mapIndexedNotNull null
				val name = chapter.string(CHAPTER_NAME).orEmpty()
				val subtitle = chapter.string(CHAPTER_SUBTITLE).orEmpty()
				val url = "/viewer/$chapterId"
				MangaChapter(
					id = stableChapterId(source.catalogueId, url),
					title = listOf(name, subtitle).filter { it.isNotBlank() }.joinToString(": "),
					number = index + 1f,
					url = url,
					uploadDate = (chapter.long(CHAPTER_START_AT) ?: 0L) * 1000L,
					index = index,
				)
			}

		manga.copy(
			title = title?.string(TITLE_NAME)?.takeIf { it.isNotBlank() } ?: manga.title,
			coverUrl = detail.string(DETAIL_COVER)
				?: title?.string(TITLE_PORTRAIT).orEmpty().ifEmpty { manga.coverUrl },
			authors = listOfNotNull(title?.string(TITLE_AUTHOR)?.takeIf { it.isNotBlank() }),
			description = detail.string(DETAIL_OVERVIEW).orEmpty(),
			chapters = chapters,
		)
	}

	// -- reader ------------------------------------------------------------

	override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
		val chapterId = chapter.url.trimEnd('/').substringAfterLast('/')
		val viewer = api("/manga_viewer_v3?chapter_id=$chapterId&split=yes&img_quality=high")
			.message(FIELD_SUCCESS)
			?.message(FIELD_VIEWER)
			?: error("${source.name} returned no pages for chapter $chapterId")

		// The viewer list also carries non-image entries (the end-of-chapter cards); they are dropped
		// before indexing so they leave no hole in the page numbering.
		viewer.messages(VIEWER_PAGES)
			.mapNotNull { wrapper ->
				val page = wrapper.message(PAGE_IMAGE) ?: return@mapNotNull null
				val image = page.string(IMAGE_URL)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
				image to page.string(IMAGE_KEY).orEmpty()
			}
			.mapIndexed { index, (image, key) ->
				MangaPage(
					url = image,
					// MangaPlusImageInterceptor strips the key and XORs the bytes back. Absent for
					// the pages MANGA Plus serves unencrypted.
					headers = if (key.isBlank()) {
						imageHeaders
					} else {
						imageHeaders + (MangaPlusImageInterceptor.SHARED_KEY_HEADER to key)
					},
					id = "${chapter.url}|page|$index",
					index = index,
					source = source,
				)
			}
	}

	override suspend fun getPageUrl(page: MangaPage): String = page.url

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = emptyList()

	// -- plumbing ----------------------------------------------------------

	private val imageHeaders: Map<String, String> = mapOf("Referer" to "$BASE_URL/")

	// Deliberately execute() rather than Call.await(): await() rewraps every failure as
	// `IOException(e.message, e)`, which erases CloudFlareProtectedException and leaves the UI
	// showing a bare "Protected by CloudFlare" with no Solve action.
	private suspend fun api(path: String): ProtoMessage = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url(API_URL + path)
			.tag(MangaSource::class.java, source)
			// Any UUID: the API only checks that a session id is present.
			.header("SESSION-TOKEN", UUID.randomUUID().toString())
			.header("Origin", BASE_URL)
			.header("Referer", "$BASE_URL/")
			.header("Accept", "*/*")
			.get()
			.build()
		okHttpClient.newCall(request).execute().use { response ->
			check(response.isSuccessful) {
				"${source.name} request failed with HTTP ${response.code}: $path"
			}
			ProtoMessage(response.body.bytes())
		}
	}

	companion object {

		/** Catalogue engine key routed here; the rows stay in the catalogue only for this adapter. */
		const val ENGINE_KEY = "mangaplus"

		private const val SITE_DOMAIN = "mangaplus.shueisha.co.jp"
		private const val BASE_URL = "https://$SITE_DOMAIN"
		private const val API_URL = "https://jumpg-webapi.tokyo-cdn.com/api"

		/** Titles handed back per browse window; the browse offset steps by it. */
		private const val PAGE_SIZE = 40
		private const val CATALOGUE_TTL_MS = 10 * 60 * 1000L

		// Field numbers, read off live responses (see the class comment).
		private const val FIELD_SUCCESS = 1 // Response.success
		private const val FIELD_ALL_TITLES = 25 // SuccessResult.allTitlesViewV2
		private const val FIELD_ALL_TITLES_GROUP = 1 // AllTitlesViewV2.groups
		private const val FIELD_GROUP_TITLES = 2 // group.titles
		private const val FIELD_RANKING = 6 // SuccessResult.titleRankingView
		private const val FIELD_RANKING_GROUP = 1
		private const val FIELD_TITLE_DETAIL = 8 // SuccessResult.titleDetailView
		private const val FIELD_VIEWER = 10 // SuccessResult.mangaViewer

		private const val TITLE_ID = 1
		private const val TITLE_NAME = 2
		private const val TITLE_AUTHOR = 3
		private const val TITLE_PORTRAIT = 4
		private const val TITLE_LANGUAGE = 7 // absent means English

		private const val DETAIL_TITLE = 1
		private const val DETAIL_COVER = 2
		private const val DETAIL_OVERVIEW = 3
		private const val DETAIL_CHAPTER_GROUPS = 28
		private const val GROUP_CHAPTERS = 2

		private const val CHAPTER_ID = 2
		private const val CHAPTER_NAME = 3
		private const val CHAPTER_SUBTITLE = 4
		private const val CHAPTER_START_AT = 6

		private const val VIEWER_PAGES = 1
		private const val PAGE_IMAGE = 1
		private const val IMAGE_URL = 1
		private const val IMAGE_KEY = 5

		private const val LANGUAGE_ENGLISH = 0

		/**
		 * Catalogue id suffix -> the API's language id. The catalogue splits MANGA Plus per language
		 * and the API tags each title the same way, so this is what makes `MANGAPLUSPARSER_ES` show
		 * Spanish titles and nothing else.
		 *
		 * Derived by decoding the live catalogue and matching titles to ids: Russian resolved against
		 * "Чёрный клевер", Thai against "2.5 มิติ ริริสะ", Vietnamese against "KAIJU NO.8 (Quái vật số
		 * 8)". Note 8 is unused and Vietnamese is 9.
		 */
		private val LANGUAGES = mapOf(
			"EN" to 0,
			"ES" to 1,
			"FR" to 2,
			"ID" to 3,
			"PT" to 4,
			"PTBR" to 4,
			"RU" to 5,
			"TH" to 6,
			"DE" to 7,
			"VI" to 9,
		)
	}
}
