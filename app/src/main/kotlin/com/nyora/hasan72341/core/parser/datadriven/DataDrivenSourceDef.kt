package com.nyora.hasan72341.core.parser.datadriven

import app.nyora.data.engine.EngineConfig
import app.nyora.data.engine.EngineId
import app.nyora.data.engine.SourceDef
import com.nyora.hasan72341.core.model.DataDrivenMangaSource
import app.nyora.data.engine.ContentType as EngineContentType
import com.nyora.hasan72341.mihon.parsers.model.ContentType as AppContentType

/**
 * Build the engine's [SourceDef] from a [DataDrivenMangaSource]. Mirrors the library's
 * VerifyAll.buildSourceDef: the two enum-modeled engines get a typed [EngineConfig] cast from their
 * config; every other engine gets a harmless [EngineConfig.Madara] placeholder plus the raw config
 * map, which is what those engines actually read. Reads a plain `Map` rather than org.json so the
 * bridge carries no JSON dependency of its own.
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

private fun Map<String, Any?>.str(k: String): String? = (this[k] as? String)?.takeIf { it.isNotEmpty() }
private fun Map<String, Any?>.bool(k: String, d: Boolean): Boolean = (this[k] as? Boolean) ?: d
private fun Map<String, Any?>.int(k: String, d: Int): Int = (this[k] as? Number)?.toInt() ?: d

private fun madaraConfig(c: Map<String, Any?>): EngineConfig.Madara {
	val b = EngineConfig.Madara()
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
		forwardCloudflareCookies = c.bool("forwardCloudflareCookies", b.forwardCloudflareCookies),
	)
}

private fun mangaReaderConfig(c: Map<String, Any?>, domain: String): EngineConfig.MangaReader {
	val b = EngineConfig.MangaReader()
	@Suppress("UNCHECKED_CAST")
	val sel = c["selectors"] as? Map<String, Any?>
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
		domains = listOf(domain),
		pageSize = c.int("pageSize", b.pageSize),
		searchPageSize = c.int("searchPageSize", b.searchPageSize),
		listUrl = c.str("listUrl") ?: b.listUrl,
		datePattern = c.str("datePattern") ?: b.datePattern,
		locale = c.str("locale") ?: b.locale,
		userAgent = c.str("userAgent") ?: b.userAgent,
		selectors = selectors,
		encodedSrc = c.bool("encodedSrc", b.encodedSrc),
		netshield = c.bool("netshield", b.netshield),
		cloudflare = c.bool("cloudflare", b.cloudflare),
	)
}
