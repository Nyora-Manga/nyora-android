package com.nyora.hasan72341.core.parser.datadriven

import app.nyora.core.model.SortOrder
import app.nyora.data.engine.EngineConfig
import app.nyora.data.engine.EngineId
import app.nyora.data.engine.FilterCapabilities
import app.nyora.data.engine.SourceDef
import app.nyora.data.engine.StaticTag
import com.nyora.hasan72341.core.model.DataDrivenMangaSource
import app.nyora.data.engine.ContentType as EngineContentType
import com.nyora.hasan72341.mihon.parsers.model.ContentType as AppContentType

/**
 * Build the engine's [SourceDef] from a [DataDrivenMangaSource]. Mirrors the shared
 * `DataDrivenCatalog.typedConfig`: the two enum-modeled engines get a typed [EngineConfig] built
 * from their config, and every other engine gets a harmless [EngineConfig.Madara] placeholder plus
 * the raw config map, which is what those engines actually read. Reads a plain `Map` rather than
 * org.json so the bridge carries no JSON dependency of its own.
 *
 * The typed config has to be complete, not just the scalar knobs: [EngineConfig.Madara] and
 * [EngineConfig.MangaReader] are where `MadaraEngine` and `MangaReaderEngine` read their selectors,
 * filter capabilities, sort orders, image rules, static tags and state/type vocabularies from, so
 * anything dropped here silently changes how a source browses compared to web and desktop.
 */
fun DataDrivenMangaSource.toSourceDef(): SourceDef {
	val c = config
	val typed: EngineConfig = when (engineKey) {
		EngineId.MANGAREADER.key -> mangaReaderConfig(c, domain)
		else -> madaraConfig(c)
	}
	val engineEnum =
		if (engineKey == EngineId.MANGAREADER.key) EngineId.MANGAREADER else EngineId.MADARA
	return SourceDef(
		id = catalogueId,
		name = title,
		lang = locale,
		nsfw = nsfw,
		contentType = contentType.toEngineContentType(),
		engine = engineEnum,
		domain = domain,
		config = typed,
		rawConfig = c,
	)
}

/** The engine models one adult type and no video, so the app's finer types fold onto it. */
private fun AppContentType.toEngineContentType(): EngineContentType = when (this) {
	AppContentType.MANGA -> EngineContentType.MANGA
	AppContentType.MANHWA -> EngineContentType.MANHWA
	AppContentType.MANHUA -> EngineContentType.MANHUA
	AppContentType.HENTAI_MANGA,
	AppContentType.HENTAI_NOVEL,
	AppContentType.HENTAI_VIDEO -> EngineContentType.HENTAI
	AppContentType.COMICS -> EngineContentType.COMICS
	AppContentType.NOVEL -> EngineContentType.NOVEL
	AppContentType.ONE_SHOT -> EngineContentType.ONE_SHOT
	AppContentType.DOUJINSHI -> EngineContentType.DOUJINSHI
	AppContentType.IMAGE_SET,
	AppContentType.ARTIST_CG,
	AppContentType.GAME_CG -> EngineContentType.IMAGE_SET
	AppContentType.VIDEO,
	AppContentType.OTHER -> EngineContentType.OTHER
}

private fun madaraConfig(c: Map<String, Any?>): EngineConfig.Madara {
	val b = EngineConfig.Madara()
	val sel = c.map("selectors")
	val img = c.map("images")
	return b.copy(
		pageSize = c.int("pageSize", b.pageSize),
		locale = c.str("locale") ?: b.locale,
		datePattern = c.str("datePattern") ?: b.datePattern,
		tagPrefix = c.str("tagPrefix") ?: b.tagPrefix,
		listUrl = c.str("listUrl") ?: b.listUrl,
		withoutAjax = c.bool("withoutAjax", b.withoutAjax),
		postReq = c.bool("postReq", b.postReq),
		postDataReq = c.str("postDataReq") ?: b.postDataReq,
		stylePage = c.str("stylePage") ?: b.stylePage,
		authorSearchSupported = c.bool("authorSearchSupported", b.authorSearchSupported),
		sortOrders = c.enumList<SortOrder>("sortOrders") ?: b.sortOrders,
		capabilities = c.capabilities(b.capabilities),
		// Older rows spell the same selectors flat as `selectChapter`, `selectPage`, ...
		selectors = b.selectors.copy(
			chapter = sel.str("chapter") ?: c.str("selectChapter"),
			page = sel.str("page") ?: c.str("selectPage"),
			bodyPage = sel.str("bodyPage") ?: c.str("selectBodyPage"),
			desc = sel.str("desc") ?: c.str("selectDesc"),
			genre = sel.str("genre") ?: c.str("selectGenre"),
			date = sel.str("date") ?: c.str("selectDate"),
			state = sel.str("state") ?: c.str("selectState"),
			alt = sel.str("alt") ?: c.str("selectAlt"),
			testAsync = sel.str("testAsync") ?: c.str("selectTestAsync"),
		),
		images = b.images.copy(
			stripStyleParam = img?.bool("stripStyleParam", b.images.stripStyleParam) ?: b.images.stripStyleParam,
			imgAttrCandidates = img.strList("imgAttrCandidates") ?: b.images.imgAttrCandidates,
			pageImgSelector = img.str("pageImgSelector"),
		),
		staticTags = c.staticTags() ?: b.staticTags,
		extraStatusMap = c.strMap("extraStatusMap") ?: b.extraStatusMap,
		forwardCloudflareCookies = c.bool("forwardCloudflareCookies", b.forwardCloudflareCookies),
	)
}

private fun mangaReaderConfig(c: Map<String, Any?>, domain: String): EngineConfig.MangaReader {
	val b = EngineConfig.MangaReader()
	val sel = c.map("selectors")
	val selectors = if (sel == null) b.selectors else EngineConfig.MangaReader.Selectors(
		mangaList = sel.str("mangaList") ?: b.selectors.mangaList,
		mangaListImg = sel.str("mangaListImg") ?: b.selectors.mangaListImg,
		mangaListTitle = sel.str("mangaListTitle") ?: b.selectors.mangaListTitle,
		chapter = sel.str("chapter") ?: b.selectors.chapter,
		description = sel.str("description") ?: b.selectors.description,
		page = sel.str("page") ?: b.selectors.page,
		script = sel.str("script") ?: b.selectors.script,
		testScript = sel.str("testScript") ?: b.selectors.testScript,
	)
	return b.copy(
		// The catalogue domain always leads; config mirrors follow it as fallbacks.
		domains = (listOf(domain) + c.strList("domains").orEmpty()).distinct(),
		pageSize = c.int("pageSize", b.pageSize),
		searchPageSize = c.int("searchPageSize", b.searchPageSize),
		listUrl = c.str("listUrl") ?: b.listUrl,
		datePattern = c.str("datePattern") ?: b.datePattern,
		locale = c.str("locale") ?: b.locale,
		userAgent = c.str("userAgent") ?: b.userAgent,
		defaultSortOrder = c.enumOf<SortOrder>("defaultSortOrder") ?: b.defaultSortOrder,
		sortOrders = c.enumList<SortOrder>("sortOrders") ?: b.sortOrders,
		capabilities = c.capabilities(b.capabilities),
		availableStates = c.strList("availableStates") ?: b.availableStates,
		availableContentTypes = c.strList("availableContentTypes") ?: b.availableContentTypes,
		selectors = selectors,
		encodedSrc = c.bool("encodedSrc", b.encodedSrc),
		netshield = c.bool("netshield", b.netshield),
		cloudflare = c.bool("cloudflare", b.cloudflare),
		statusOverrides = c.strMap("statusOverrides") ?: b.statusOverrides,
		relativeDateWords = c.strMap("relativeDateWords") ?: b.relativeDateWords,
	)
}

// --- config readers -------------------------------------------------------------------------
// Nullable receivers so an absent nested object reads the same as an object without the key.

private fun Map<String, Any?>?.str(k: String): String? = (this?.get(k) as? String)?.takeIf { it.isNotBlank() }

private fun Map<String, Any?>.bool(k: String, d: Boolean): Boolean = (this[k] as? Boolean) ?: d

private fun Map<String, Any?>.int(k: String, d: Int): Int = (this[k] as? Number)?.toInt() ?: d

@Suppress("UNCHECKED_CAST")
private fun Any?.asValueMap(): Map<String, Any?>? = this as? Map<String, Any?>

private fun Map<String, Any?>.map(k: String): Map<String, Any?>? = this[k].asValueMap()

/** An empty array stays an empty list: "this source offers none" is not "this source is silent". */
private fun Map<String, Any?>?.strList(k: String): List<String>? =
	(this?.get(k) as? List<*>)?.mapNotNull { (it as? String)?.takeIf { s -> s.isNotBlank() } }

private fun Map<String, Any?>.strMap(k: String): Map<String, String>? = map(k)?.let { raw ->
	buildMap(raw.size) {
		for ((key, value) in raw) {
			if (value is String) put(key, value)
		}
	}
}

private fun Map<String, Any?>.staticTags(): List<StaticTag>? = (this["staticTags"] as? List<*>)?.mapNotNull { entry ->
	val tag = entry.asValueMap() ?: return@mapNotNull null
	val key = tag.str("key") ?: return@mapNotNull null
	StaticTag(key = key, title = tag.str("title") ?: key)
}

private inline fun <reified T : Enum<T>> Map<String, Any?>.enumOf(k: String): T? = str(k)?.let { value ->
	enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
}

private inline fun <reified T : Enum<T>> Map<String, Any?>.enumList(k: String): List<T>? =
	strList(k)?.mapNotNull { value -> enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } }

private fun Map<String, Any?>.capabilities(base: FilterCapabilities): FilterCapabilities {
	val value = map("capabilities") ?: return base
	return base.copy(
		multipleTags = value.bool("multipleTags", base.multipleTags),
		tagsExclusion = value.bool("tagsExclusion", base.tagsExclusion),
		search = value.bool("search", base.search),
		searchWithFilters = value.bool("searchWithFilters", base.searchWithFilters),
		year = value.bool("year", base.year),
		authorSearch = value.bool("authorSearch", base.authorSearch),
	)
}
