package com.nyora.hasan72341.core.parser

import app.nyora.data.engine.EngineContext
import app.nyora.data.engine.EngineRegistry
import app.nyora.data.engine.SourceDef
import app.nyora.data.engine.SourceEngine
import app.nyora.data.engine.UnsupportedImageRequestException
import com.nyora.hasan72341.core.cache.MemoryContentCache
import com.nyora.hasan72341.core.model.DataDrivenMangaSource
import com.nyora.hasan72341.core.network.CommonHeaders
import com.nyora.hasan72341.core.parser.datadriven.AndroidEngineContext
import com.nyora.hasan72341.core.parser.datadriven.stableChapterId
import com.nyora.hasan72341.core.parser.datadriven.stableMangaId
import com.nyora.hasan72341.core.parser.datadriven.toSourceDef
import com.nyora.hasan72341.mihon.parsers.model.ContentRating
import com.nyora.hasan72341.mihon.parsers.model.Manga
import com.nyora.hasan72341.mihon.parsers.model.MangaChapter
import com.nyora.hasan72341.mihon.parsers.model.MangaListFilter
import com.nyora.hasan72341.mihon.parsers.model.MangaListFilterCapabilities
import com.nyora.hasan72341.mihon.parsers.model.MangaListFilterOptions
import com.nyora.hasan72341.mihon.parsers.model.MangaPage
import com.nyora.hasan72341.mihon.parsers.model.MangaSourceRef
import com.nyora.hasan72341.mihon.parsers.model.MangaState
import com.nyora.hasan72341.mihon.parsers.model.MangaTag
import com.nyora.hasan72341.mihon.parsers.model.SortOrder
import com.nyora.hasan72341.mihon.parsers.util.runCatchingCancellable
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.parsers.InternalParsersApi
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import app.nyora.core.model.ContentRating as EngineContentRating
import app.nyora.core.model.Manga as EngineManga
import app.nyora.core.model.MangaChapter as EngineChapter
import app.nyora.core.model.MangaListFilter as EngineFilter
import app.nyora.core.model.MangaPage as EnginePage
import app.nyora.core.model.MangaState as EngineMangaState
import app.nyora.core.model.MangaTag as EngineTag
import app.nyora.core.model.SortOrder as EngineSortOrder
import app.nyora.data.engine.ContentType as EngineContentType
import org.koitharu.kotatsu.parsers.model.ContentRating as LibContentRating
import org.koitharu.kotatsu.parsers.model.ContentType as LibContentType
import org.koitharu.kotatsu.parsers.model.MangaState as LibMangaState
import org.koitharu.kotatsu.parsers.model.MangaTag as LibMangaTag

/**
 * Runs one catalogue row on the bundled data-driven engine its `engineKey` names, and maps between
 * the engine's model and the app's. The mapping is the identity contract with the other Nyora
 * clients: manga and chapter ids are the legacy hashes desktop and web stamp, so a row browsed here
 * resolves to the same history, favourite and sync entry everywhere.
 *
 * [domainOverride] supplies the user's per-source domain without dragging an Android `Context` into
 * the repository, and [engineFactory] is the seam focused tests bind a fake engine through.
 */
class DataDrivenMangaRepository(
	override val source: DataDrivenMangaSource,
	okHttpClient: OkHttpClient,
	/** Null disables content caching; only the unit tests, which have no [android.app.Application], pass null. */
	cache: MemoryContentCache?,
	private val domainOverride: () -> String? = { null },
	engineFactory: (SourceDef, EngineContext) -> SourceEngine = { def, ctx ->
		EngineRegistry.create(source.engineKey, def, ctx)
	},
) : CachingMangaRepository(cache), DomainAwareRepository {

	/**
	 * Exposed so `CommonHeadersInterceptor` can attach the source Referer. The Referer has to follow
	 * the effective domain — several of these image CDNs 403 a mismatched one.
	 */
	override val domain: String
		get() = domainOverride()?.takeIf { it.isNotBlank() } ?: source.domain

	private val referer: String
		get() = "https://$domain/"

	private val catalogueId: String = source.catalogueId

	private val sourceRef: MangaSourceRef = MangaSourceRef.Data(source.name)

	private val isNsfwSource: Boolean = source.nsfw

	private val pageSize: Int = source.pageSize.takeIf { it > 0 } ?: DEFAULT_PAGE_SIZE

	// The engine reads its domain from ctx.prefs, so the shipped domain patches and the user's
	// per-source override reach it. The UA is deliberately NOT seeded here — CommonHeadersInterceptor
	// owns it, and a second value set here would disagree with the one that earned the Cloudflare
	// clearance cookie.
	private val engineContext: EngineContext = AndroidEngineContext(
		client = okHttpClient,
		source = source,
		effectiveDomain = { domain },
	)

	private val engine: SourceEngine by lazy { engineFactory(source.toSourceDef(), engineContext) }

	/**
	 * The engine's own chapter id by the app chapter id, for the chapters this repository mapped.
	 *
	 * The app id is the legacy hash every client shares, which is not what the engines key a page
	 * request on: most set their id to the chapter url, but MangAdventure and the Iken API need the
	 * numeric id their series listing returns. The id is recorded here when a chapter is mapped and
	 * handed back with the page request; only ids that differ from the url are worth keeping.
	 */
	private val engineChapterIds = ConcurrentHashMap<String, String>()

	// Read while rendering the source list, so it must never throw on a bad-config engine.
	override val sortOrders: Set<SortOrder>
		get() = runCatching {
			engine.availableSortOrders.mapNotNullTo(LinkedHashSet()) { it.toApp() }
		}.getOrNull()?.takeIf { it.isNotEmpty() } ?: setOf(SortOrder.POPULARITY)

	override var defaultSortOrder: SortOrder = SortOrder.POPULARITY

	override val filterCapabilities: MangaListFilterCapabilities
		@OptIn(InternalParsersApi::class)
		get() = runCatching {
			engine.capabilities.let {
				MangaListFilterCapabilities(
					isMultipleTagsSupported = it.multipleTags,
					isTagsExclusionSupported = it.tagsExclusion,
					isSearchSupported = it.search,
					isSearchWithFiltersSupported = it.searchWithFilters,
					isYearSupported = it.year,
					isAuthorSearchSupported = it.authorSearch,
				)
			}
		}.getOrDefault(MangaListFilterCapabilities())

	override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
		val page = offset / pageSize
		val results = when {
			filter != null && filter.isConstrained() -> {
				val engineFilter = filter.toEngine()
				engine.search(page, engineFilter.query, engineFilter)
			}

			order == SortOrder.UPDATED || order == SortOrder.NEWEST -> engine.getLatest(page)
			else -> engine.getPopular(page)
		}
		return results.map { it.toApp() }
	}

	override suspend fun getDetailsImpl(manga: Manga): Manga {
		val stub = EngineManga(id = manga.url, title = manga.title, url = manga.url)
		// Keep the caller's id: it is the persisted row's identity, and a details response that
		// normalized the url would otherwise orphan its history and favourites.
		return engine.getDetails(stub).toApp().copy(id = manga.id)
	}

	override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> {
		val engineChapter = EngineChapter(
			id = engineChapterId(chapter),
			title = chapter.title.ifBlank { null },
			number = chapter.number,
			volume = chapter.volume,
			url = chapter.url,
			scanlator = chapter.scanlator,
			uploadDate = chapter.uploadDate,
			branch = chapter.branch,
		)
		return engine.getPageList(engineChapter).mapIndexed { index, page ->
			MangaPage(
				url = page.url,
				headers = page.headers,
				id = page.id,
				index = index,
				preview = page.preview,
				source = source,
			)
		}
	}

	/**
	 * The id the engine gave [chapter], or the chapter url when the map is cold: a fresh process, or
	 * a repository created after the details were served from the content cache. The url is the id
	 * for every engine except the ones keyed by a numeric id, and those publish the id only in the
	 * series chapter list, so when the url names the series that list is read again to recover it.
	 */
	private suspend fun engineChapterId(chapter: MangaChapter): String {
		engineChapterIds[chapter.id]?.let { return it }
		val seriesUrl = SERIES_URL_OF_CHAPTER[source.engineKey]?.invoke(chapter.url) ?: return chapter.url
		runCatchingCancellable {
			val series = EngineManga(id = seriesUrl, title = "", url = seriesUrl)
			engine.getDetails(series).chapters.orEmpty().forEach { rememberEngineChapterId(it) }
		}
		return engineChapterIds[chapter.id] ?: chapter.url
	}

	/** Record the engine id of [chapter] under its app id and return that app id. */
	private fun rememberEngineChapterId(chapter: EngineChapter): String {
		val appId = stableChapterId(catalogueId, chapter.url)
		if (chapter.id != chapter.url && chapter.id.isNotEmpty()) {
			engineChapterIds[appId] = chapter.id
		}
		return appId
	}

	override suspend fun getPageUrl(page: MangaPage): String = getPageRequest(page).url

	override suspend fun getPageRequest(page: MangaPage): MangaPageRequest {
		val enginePage = EnginePage(
			url = page.url,
			id = page.id.ifEmpty { page.url },
			headers = page.headers,
		)
		val resolved = try {
			engine.resolvePageImageRequest(enginePage)
		} catch (e: UnsupportedImageRequestException) {
			// The reader reports an IllegalStateException as "this page cannot be opened"; an
			// engine-specific exception type would surface as a crash-worthy unknown error.
			throw IllegalStateException(e.message, e)
		}
		return MangaPageRequest(
			url = resolved.url,
			// Hotlink-protected CDNs need the source Referer; the per-page headers carry what the
			// engine attached to this page (e.g. MANGA Plus's XOR key), and the resolved request wins.
			headers = buildMap {
				put(CommonHeaders.REFERER, referer)
				putAll(page.headers)
				putAll(resolved.headers)
			},
		)
	}

	@OptIn(InternalParsersApi::class)
	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = engine.getAvailableTags().mapTo(LinkedHashSet()) { LibMangaTag(it.title, it.key, source) },
		// RESTRICTED is a library state no data source publishes.
		availableStates = EnumSet.complementOf(EnumSet.of(LibMangaState.RESTRICTED)),
		availableContentTypes = availableContentTypes(),
	)

	// The engines expose no related-manga endpoint; the app degrades to a title search elsewhere.
	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = emptyList()

	private fun availableContentTypes(): Set<LibContentType> {
		val configured = source.config[KEY_AVAILABLE_CONTENT_TYPES] as? List<*> ?: return emptySet()
		return configured.mapNotNullTo(LinkedHashSet()) { value ->
			val name = value as? String ?: return@mapNotNullTo null
			LibContentType.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
		}
	}

	private fun absoluteUrl(value: String): String = when {
		value.isBlank() || value.startsWith("http://") || value.startsWith("https://") -> value
		value.startsWith("//") -> "https:$value"
		else -> "https://$domain/${value.trimStart('/')}"
	}

	// ---- app filter -> engine filter ----

	/** Anything beyond a plain sort order has to go through the engine's search endpoint. */
	private fun MangaListFilter.isConstrained(): Boolean = !query.isNullOrBlank() ||
		tags.isNotEmpty() ||
		tagsExclude.isNotEmpty() ||
		states.isNotEmpty() ||
		types.isNotEmpty() ||
		contentRating.isNotEmpty() ||
		year > 0 ||
		author != null

	private fun MangaListFilter.toEngine(): EngineFilter = EngineFilter(
		query = query?.takeIf { it.isNotBlank() },
		tags = tags.mapTo(LinkedHashSet()) { it.toEngine() },
		tagsExclude = tagsExclude.mapTo(LinkedHashSet()) { it.toEngine() },
		states = states.mapNotNullTo(LinkedHashSet()) { it.toEngine() },
		types = types.mapNotNullTo(LinkedHashSet()) { it.toEngine() },
		contentRating = contentRating.mapNotNullTo(LinkedHashSet()) { it.toEngine() },
		year = year.coerceAtLeast(0),
		author = author,
		locale = locale,
	)

	private fun LibMangaTag.toEngine(): EngineTag = EngineTag(title = title, key = key, source = catalogueId)

	/** RESTRICTED has no engine counterpart, so a filter that asks for it simply drops that state. */
	private fun LibMangaState.toEngine(): EngineMangaState? =
		EngineMangaState.entries.firstOrNull { it.name == name }

	private fun LibContentType.toEngine(): EngineContentType? =
		EngineContentType.entries.firstOrNull { it.name == name }

	private fun LibContentRating.toEngine(): EngineContentRating? =
		EngineContentRating.entries.firstOrNull { it.name == name }

	// ---- engine model -> app model ----

	private fun EngineManga.toApp(): Manga = Manga(
		id = stableMangaId(catalogueId, url),
		title = title,
		altTitles = altTitles.toList(),
		url = url,
		publicUrl = publicUrl.ifBlank { absoluteUrl(url) },
		rating = rating,
		isNsfw = isNsfw || isNsfwSource || contentRating == EngineContentRating.ADULT,
		contentRating = contentRating?.toApp(),
		coverUrl = absoluteUrl(coverUrl.orEmpty()),
		largeCoverUrl = largeCoverUrl?.let(::absoluteUrl),
		state = state?.toApp(),
		authors = authors.toList(),
		source = sourceRef,
		description = description.orEmpty(),
		tags = tags.map { MangaTag(key = it.key, title = it.title) },
		chapters = chapters.orEmpty().mapIndexed { index, chapter -> chapter.toApp(index) },
	)

	private fun EngineChapter.toApp(index: Int): MangaChapter = MangaChapter(
		id = rememberEngineChapterId(this),
		// Drop a title that is just the chapter number — the UI formats "Chapter {number}" and would
		// otherwise render the redundant "Chapter 1 - 1".
		title = title?.trim().orEmpty().let { if (it == numberDisplay(number)) "" else it },
		number = number,
		volume = volume,
		url = url,
		scanlator = scanlator,
		uploadDate = uploadDate,
		branch = branch,
		index = index,
	)

	private fun numberDisplay(number: Float): String = when {
		number <= 0f -> ""
		number % 1f == 0f -> number.toInt().toString()
		else -> number.toString()
	}

	private fun EngineContentRating.toApp(): ContentRating? =
		ContentRating.entries.firstOrNull { it.name == name }

	private fun EngineMangaState.toApp(): MangaState? =
		MangaState.entries.firstOrNull { it.name == name }

	private fun EngineSortOrder.toApp(): SortOrder? =
		SortOrder.entries.firstOrNull { it.name == name }

	private companion object {

		/** Catalogue rows without a page size list 20 entries per page, like the shared clients assume. */
		private const val DEFAULT_PAGE_SIZE = 20

		private const val KEY_AVAILABLE_CONTENT_TYPES = "availableContentTypes"

		/**
		 * For the engines whose page request needs an id the chapter url does not carry: the series
		 * url the chapter url names, so the series chapter list (and its ids) can be read again.
		 * MangAdventure lists chapters at `/reader/{slug}/{volume}/{number}/`, the series at
		 * `/reader/{slug}/`. The Iken API wants its numeric id too, but its engine falls back to the
		 * reader HTML at the chapter url when the id is not numeric, so the url alone serves there.
		 */
		private val SERIES_URL_OF_CHAPTER: Map<String, (String) -> String?> = mapOf(
			"mangadventure" to ::mangadventureSeriesUrl,
		)
	}
}

/** The MangAdventure series url (`/reader/{slug}/`) a chapter url (`/reader/{slug}/{volume}/{number}/`) names, or null. */
internal fun mangadventureSeriesUrl(chapterUrl: String): String? {
	val path = if ("://" in chapterUrl) chapterUrl.substringAfter("://").substringAfter('/', "") else chapterUrl
	val segments = path.split('/').filter { it.isNotEmpty() }
	val reader = segments.indexOf("reader")
	if (reader < 0 || reader + 2 >= segments.size) return null
	return "/reader/${segments[reader + 1]}/"
}
