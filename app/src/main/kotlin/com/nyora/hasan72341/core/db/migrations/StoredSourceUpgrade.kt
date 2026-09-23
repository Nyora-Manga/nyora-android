package com.nyora.hasan72341.core.db.migrations

import com.nyora.hasan72341.core.model.DataDrivenMangaSource
import com.nyora.hasan72341.core.parser.datadriven.DataDrivenCatalogue
import com.nyora.hasan72341.core.parser.datadriven.LEGACY_SOURCE_RENAMES
import com.nyora.hasan72341.core.parser.datadriven.canonicalDataSourceId
import com.nyora.hasan72341.core.parser.datadriven.decodeStoredSourceName
import com.nyora.hasan72341.core.parser.datadriven.findCanonical
import com.nyora.hasan72341.core.parser.datadriven.stableMangaId

/**
 * Pure helpers behind [Migration32To33], kept out of the migration so they can be tested without a
 * database.
 *
 * A schema-32 database can come from either shipped lineage. v2.1.6 stored the retired JavaScript
 * enum `JS_<ID>`, which spelled every delimiter `_`; v2.6 to v2.7.3 stored `DD_<catalogue id>` in
 * the catalogue's own casing, bare in `sources` and wrapped as `{"name":"..."}` in `manga.source`.
 * Schema 33 never shipped, so this upgrade is the one place both shapes are retired, and both have
 * to land on the same `data:` spelling or the same site would read as two sources.
 *
 * Other Nyora clients synced the Kotatsu parser token of a source, prefixed (`parser:MANGADEX`) or
 * bare (`MANGADEX`); those land on the catalogue row of the same id when the catalogue lists one.
 */

/** Source prefix written by the JavaScript bundle that v2.1.6 shipped. */
private const val JAVASCRIPT_PREFIX = "JS_"

/** Source prefix written by the data-driven builds v2.6 to v2.7.3 shipped. */
private const val SHIPPED_DATA_DRIVEN_PREFIX = "DD_"

/** Prefix other Nyora clients put on a Kotatsu parser token when they sync it. */
private const val PARSER_PREFIX = "parser:"

/** A bare Kotatsu parser token as other clients synced it: `MANGADEX`, `MANGAFIRE_EN`. */
private val PARSER_TOKEN = Regex("[A-Z][A-Z0-9_]*")

/** Upper-case identities that are not parser tokens. */
private val NON_SOURCE_TOKENS = setOf("LOCAL", "UNKNOWN", "TEST")

/**
 * The canonical `data:` identity of a stored source name, or null when the name is not a source
 * this upgrade owns: `LOCAL` and `UNKNOWN` keep working as they are, and the Mihon and numeric
 * identities are left to the purge that retires them.
 *
 * A parser token resolves only through [catalogue]: it is a source of this app when a catalogue
 * row claims it, and a guess without the catalogue would turn a tracker's or a foreign parser's
 * token into a `data:` identity nothing can browse. The live catalogue is the default; tests and
 * call sites that run before Hilt built it pass their own or null.
 */
internal fun upgradedStoredSourceName(
	raw: String,
	catalogue: DataDrivenCatalogue? = DataDrivenCatalogue.instance,
): String? {
	val name = decodeStoredSourceName(raw)
	val storedId = when {
		name.startsWith(SHIPPED_DATA_DRIVEN_PREFIX) -> name.removePrefix(SHIPPED_DATA_DRIVEN_PREFIX)
		// The JavaScript enum spelled every delimiter `_` where the catalogue mostly spells it `-`;
		// the handful of catalogue ids that really do carry `_` are put back by the renames below.
		// Some rows reached the enum already migrated, so drop a `data:` the prefix wrapped.
		name.startsWith(JAVASCRIPT_PREFIX) -> name.removePrefix(JAVASCRIPT_PREFIX)
			.removePrefix(DataDrivenMangaSource.PREFIX)
			.replace('_', '-')
		name.startsWith(DataDrivenMangaSource.PREFIX) -> name.removePrefix(DataDrivenMangaSource.PREFIX)
		name.startsWith(PARSER_PREFIX) -> return catalogueSourceForParserToken(name.removePrefix(PARSER_PREFIX), catalogue)
		isParserToken(name) -> return catalogueSourceForParserToken(name, catalogue)
		else -> return null
	}
	val upgraded = DataDrivenMangaSource.PREFIX + canonicalDataSourceId(storedId)
	return LEGACY_SOURCE_RENAMES[upgraded] ?: upgraded
}

private fun isParserToken(name: String): Boolean =
	name !in NON_SOURCE_TOKENS && !name.startsWith("MIHON_") && PARSER_TOKEN.matches(name)

/**
 * The catalogue row [token] names, tried as spelled and with the `-` delimiters the catalogue
 * prefers where the parser enum spelled `_`; null without a catalogue or when no row claims it.
 */
private fun catalogueSourceForParserToken(token: String, catalogue: DataDrivenCatalogue?): String? {
	if (token.isEmpty() || catalogue == null) return null
	val lower = token.lowercase()
	return sequenceOf(lower, lower.replace('_', '-'))
		.distinct()
		.mapNotNull { catalogue.findCanonical(DataDrivenMangaSource.PREFIX + it)?.name }
		.firstOrNull()
}

/**
 * The local manga id a row of [storedSourceName] hashes to, which is what every client stamps and
 * what the shipped builds did not: their data-driven adapter kept the engine's relative href and
 * their native adapters stored `<source>|<url>`.
 */
internal fun upgradedMangaId(storedSourceName: String, url: String): String =
	stableMangaId(storedSourceName.removePrefix(DataDrivenMangaSource.PREFIX), url)
