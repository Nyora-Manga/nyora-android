package com.nyora.hasan72341.core.parser.datadriven

import org.json.JSONObject

/**
 * Stable manga identity shared with the legacy Kotatsu bridge, desktop client and web bundle.
 *
 * Ported from `nyora-shared-datadriven`'s `LegacyMangaId.kt`; kept in this module rather than
 * depending on the shared multiplatform module to avoid a new Gradle dependency for a handful of
 * pure functions. Kotlin [Long] arithmetic intentionally supplies the required signed 64-bit
 * wraparound.
 */

/** Source identities renamed after the catalogue made delimiters consistent. */
internal val LEGACY_SOURCE_RENAMES: Map<String, String> = mapOf(
	"data:bananascan-com" to "data:bananascan_com",
	"data:hastateam-reader" to "data:hastateam_reader",
	"data:cosmic-scans" to "data:cosmic_scans",
	"data:komikdewasa-online" to "data:komikdewasa_online",
	"data:manga-scantrad" to "data:manga_scantrad",
	"data:manhwa-es" to "data:manhwa_es",
	"data:mangafire-en" to "data:mangafire_en",
	"data:mangafire-ja" to "data:mangafire_ja",
	"data:asurascans-us" to "data:asurascans",
	"data:manganato-gg" to "data:manganato",
)

internal fun legacyMangaId(sourceName: String, mangaUrl: String): Long {
	var hash = 1125899906842597L
	for (char in sourceName + canonicalLegacyMangaUrl(sourceName, mangaUrl)) {
		hash = 31 * hash + char.code
	}
	return hash
}

internal fun canonicalLegacyMangaUrl(sourceName: String, mangaUrl: String): String {
	val legacyName = legacyParserSourceName(sourceName)
	if (legacyName == "ASURASCANS_US") {
		return mangaUrl.replaceFirst(
			Regex("^https?://(?:www\\.)?asurascans\\.com(?=/|$)", RegexOption.IGNORE_CASE),
			"",
		)
	}
	if (legacyName != "WEBTOONS_EN") return mangaUrl
	val titleNo = Regex("(?:[?&]title_no=)([^&#]+)", RegexOption.IGNORE_CASE)
		.find(mangaUrl)
		?.groupValues
		?.get(1)
	return titleNo ?: mangaUrl
}

/**
 * Parser identity historically used to derive manga ids for a data/native route.
 *
 * Most catalogue ids differ from their retired parser enum only by case. The MangaNato
 * family has one intentional rename (`manganelo` -> `MANGANELO_COM`), so keep that bridge
 * explicit rather than deriving ids from a platform-specific `data:`/`DD_` wrapper.
 */
internal fun legacyParserSourceName(sourceIdentity: String): String {
	val bare = sourceIdentity
		.removePrefix("JS_")
		.removePrefix("DD_")
		.removePrefix("data:")
		.removePrefix("parser:")
		.removePrefix("script:")
	return when (bare.lowercase()) {
		"manganato" -> "MANGANATO"
		"manganelo" -> "MANGANELO_COM"
		"mangakakalot" -> "MANGAKAKALOT"
		"mangakakalottv" -> "MANGAKAKALOTTV"
		// Catalogue ids were made delimiter-consistent after these parser enums shipped.
		// The historical spellings are part of the persisted manga-id contract.
		"mangafire_es_la" -> "MANGAFIRE_ESLA"
		"mangafire_pt_br" -> "MANGAFIRE_PTBR"
		"maid-id" -> "MAID_ID"
		"shiro-doujin" -> "SHIRO_DOUJIN"
		// The broken KomikIndo.info row moved to the live same-domain MangaSusuku definition.
		// Keep the retired parser enum as its manga-hash and history lookup identity.
		"komikindo-info", "mangasusuku" -> "KOMIKINDO_INFO"
		"mangabat" -> "HMANGABAT"
		"asurascans" -> "ASURASCANS_US"
		"webtoons" -> "WEBTOONS_EN"
		else -> bare.uppercase()
	}
}

/** Current catalogue id for a retired data row whose site has a verified live successor. */
internal fun canonicalDataSourceId(sourceId: String): String = when (sourceId.lowercase()) {
	"asurascans_us" -> "asurascans"
	"komikindo-info" -> "mangasusuku"
	else -> sourceId.lowercase()
}

/** Local Room manga id for a data source, matching the JavaScript bundle's hash. */
internal fun stableMangaId(catalogueId: String, mangaUrl: String): String {
	val sourceName = legacyParserSourceName(catalogueId)
	return legacyMangaId(sourceName, canonicalLegacyMangaUrl(sourceName, mangaUrl)).toString()
}

/** Local Room chapter id, keeping the bundle's `" chapter " + chapterUrl` convention. */
internal fun stableChapterId(catalogueId: String, chapterUrl: String): String {
	val sourceName = legacyParserSourceName(catalogueId)
	return legacyMangaId(sourceName, " chapter " + chapterUrl).toString()
}

/**
 * Normalize the shapes written by Android, web and older desktop sync clients. Some Android builds
 * wrapped an already-JSON source a second time, so unwrap a small bounded number of layers.
 */
internal fun decodeStoredSourceName(raw: String): String {
	var current = raw.trim().substringAfterLast(".MangaSourceRef.")
	repeat(4) {
		if (!current.startsWith("{")) return current
		val decoded = runCatching {
			val obj = JSONObject(current)
			sequenceOf("name", "source", "id", "type")
				.mapNotNull { key -> if (obj.has(key) && !obj.isNull(key)) obj.optString(key) else null }
				.firstOrNull { it.isNotBlank() }
		}.getOrNull() ?: return current
		val next = decoded.trim().substringAfterLast(".MangaSourceRef.")
		if (next == current) return current
		current = next
	}
	return current
}
