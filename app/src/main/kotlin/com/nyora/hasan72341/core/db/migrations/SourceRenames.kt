package com.nyora.hasan72341.core.db.migrations

import com.nyora.hasan72341.core.model.DataDrivenMangaSource
import com.nyora.hasan72341.core.parser.datadriven.LEGACY_SOURCE_RENAMES
import com.nyora.hasan72341.core.parser.datadriven.stableMangaId
import java.util.Locale

/**
 * Pure helpers behind [Migration33To34], kept out of the migration so they can be tested without a
 * database.
 *
 * Schema 33 derived every source name from the retired JavaScript enum, lower-cased and with `_`
 * turned into `-`. Ten of those spellings are not the ones the catalogue publishes, so schema 34
 * renames them.
 */

/** `manga.source` is stored either bare or as the `{"name":"..."}` object the entity mapper writes. */
private val JSON_SOURCE_NAME = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")

/**
 * The renamed value of a stored `manga.source`, in the shape it was stored in, or null when the
 * catalogue never renamed that source.
 */
internal fun renamedSource(stored: String): String? {
	val jsonName = JSON_SOURCE_NAME.find(stored)?.groupValues?.get(1)
	val renamed = LEGACY_SOURCE_RENAMES[(jsonName ?: stored).lowercase(Locale.ROOT)] ?: return null
	return if (jsonName == null) renamed else "{\"name\":\"$renamed\"}"
}

/**
 * The manga id a row of [newSourceName] hashes to once renamed, so the migration can tell the
 * renames that keep the legacy id from the two that do not.
 */
internal fun rekeyedMangaId(newSourceName: String, url: String): String =
	stableMangaId(newSourceName.removePrefix(DataDrivenMangaSource.PREFIX), url)
