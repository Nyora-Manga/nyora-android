package com.nyora.hasan72341.core.db.migrations

import com.nyora.hasan72341.core.model.DataDrivenMangaSource
import com.nyora.hasan72341.core.parser.datadriven.LEGACY_SOURCE_RENAMES
import com.nyora.hasan72341.core.parser.datadriven.stableChapterId
import org.json.JSONArray
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
 * A `manga.chapters` blob re-hashed for a renamed source, with the old -> new chapter id map the
 * migration replays over the tables that point at a chapter.
 */
internal class RekeyedChapters(val chapters: String, val ids: Map<String, String>)

/**
 * The `manga.chapters` blob of [newSourceName] with every chapter id re-hashed, or null when the
 * blob cannot be read or every id already matches.
 *
 * A chapter id embeds the source token exactly like a manga id does, so a row that moves to a
 * renamed identity has to take its chapter ids with it or the reader cannot resume: the next
 * details refresh rewrites the blob with ids hashed from the new token while `history.chapter_id`
 * still holds one hashed from the retired token. The chapter url travels inside the blob, which is
 * all [stableChapterId] needs to recompute the id.
 */
internal fun rekeyedChapters(newSourceName: String, storedChapters: String): RekeyedChapters? {
	val catalogueId = newSourceName.removePrefix(DataDrivenMangaSource.PREFIX)
	val chapters = runCatching { JSONArray(storedChapters) }.getOrNull() ?: return null
	val ids = LinkedHashMap<String, String>()
	var changed = false
	for (i in 0 until chapters.length()) {
		val chapter = chapters.optJSONObject(i) ?: continue
		val url = chapter.optString("url")
		if (url.isEmpty()) continue
		val oldId = chapter.optString("id")
		val newId = stableChapterId(catalogueId, url)
		if (newId == oldId) continue
		chapter.put("id", newId)
		changed = true
		if (oldId.isNotEmpty()) {
			ids[oldId] = newId
		}
	}
	return if (changed) RekeyedChapters(chapters.toString(), ids) else null
}
