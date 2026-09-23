package com.nyora.hasan72341.core.db.migrations

import com.nyora.hasan72341.core.model.DataDrivenMangaSource
import com.nyora.hasan72341.core.parser.datadriven.LEGACY_SOURCE_RENAMES
import com.nyora.hasan72341.core.parser.datadriven.canonicalDataSourceId
import com.nyora.hasan72341.core.parser.datadriven.decodeStoredSourceName
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
 */

/** Source prefix written by the JavaScript bundle that v2.1.6 shipped. */
private const val JAVASCRIPT_PREFIX = "JS_"

/** Source prefix written by the data-driven builds v2.6 to v2.7.3 shipped. */
private const val SHIPPED_DATA_DRIVEN_PREFIX = "DD_"

/**
 * The canonical `data:` identity of a stored source name, or null when the name is not a source
 * this upgrade owns: `LOCAL` and `UNKNOWN` keep working as they are, and the Mihon and numeric
 * identities are left to the purge that retires them.
 */
internal fun upgradedStoredSourceName(raw: String): String? {
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
		else -> return null
	}
	val upgraded = DataDrivenMangaSource.PREFIX + canonicalDataSourceId(storedId)
	return LEGACY_SOURCE_RENAMES[upgraded] ?: upgraded
}

/**
 * The local manga id a row of [storedSourceName] hashes to, which is what every client stamps and
 * what the shipped builds did not: their data-driven adapter kept the engine's relative href and
 * their native adapters stored `<source>|<url>`.
 */
internal fun upgradedMangaId(storedSourceName: String, url: String): String =
	stableMangaId(storedSourceName.removePrefix(DataDrivenMangaSource.PREFIX), url)
