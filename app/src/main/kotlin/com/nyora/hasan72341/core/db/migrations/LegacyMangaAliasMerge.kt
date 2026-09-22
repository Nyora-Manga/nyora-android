package com.nyora.hasan72341.core.db.migrations

import androidx.sqlite.db.SupportSQLiteDatabase
import com.nyora.hasan72341.core.parser.datadriven.canonicalLegacyMangaUrl
import com.nyora.hasan72341.core.parser.datadriven.decodeStoredSourceName
import com.nyora.hasan72341.core.parser.datadriven.legacyParserSourceName

/**
 * SQL that moves one manga row, and everything pointing at it, onto the legacy identity every
 * client shares.
 *
 * A row written under a retired id can already be referenced by favourites, history, reader state
 * and downloads, so the alias is merged rather than replaced: each dependent table is copied to the
 * canonical id, conflicts are settled on the table's own recency clock, and only then is the alias
 * removed.
 */

/** Dependent tables of a manga row, children before parents so no cascade can erase a merged row. */
private val CHILD_TABLES = listOf(
	"stats",
	"history",
	"favourites",
	"preferences",
	"tracks",
	"suggestions",
	"bookmarks",
	"scrobblings",
	"local_index",
)

/** Tables holding a chapter id, none of which constrain it, so a plain update cannot collide. */
private val CHAPTER_ID_COLUMNS = listOf(
	"history" to "chapter_id",
	"bookmarks" to "chapter_id",
	"tracks" to "last_chapter_id",
)

/**
 * Merges the row [oldId] into [canonicalId] and reports whether it moved.
 *
 * A hash collision must never merge unrelated manga, so an existing canonical row is an alias of
 * this one only when its stored source and url resolve to the same identity. When they do not the
 * row keeps the id it has, which leaves it unresolvable but intact.
 *
 * The `chapters` blob of an already-existing canonical row wins, so the caller settles [oldId]'s
 * chapter ids with [rekeyChapterIds] first: afterwards the urls this row's history and bookmarks
 * were keyed by are gone with its blob.
 */
internal fun mergeLegacyMangaAlias(
	db: SupportSQLiteDatabase,
	oldId: String,
	canonicalId: String,
	sourceName: String,
	url: String,
): Boolean {
	if (!isAliasOfCanonicalRow(db, canonicalId, sourceName, url)) return false
	db.execSQL(
		"INSERT OR IGNORE INTO manga (manga_id, title, alt_title, url, public_url, rating, nsfw, content_rating, " +
			"cover_url, large_cover_url, state, author, source, description, tags, chapters, unread, progress) " +
			"SELECT ?, title, alt_title, url, public_url, rating, nsfw, content_rating, " +
			"cover_url, large_cover_url, state, author, source, description, tags, chapters, unread, progress " +
			"FROM manga WHERE manga_id = ?",
		arrayOf(canonicalId, oldId),
	)
	mergeHistory(db, oldId, canonicalId)
	mergeStats(db, oldId, canonicalId)
	mergeFavourites(db, oldId, canonicalId)
	mergePreferences(db, oldId, canonicalId)
	mergeTracks(db, oldId, canonicalId)
	mergeSuggestions(db, oldId, canonicalId)
	mergeBookmarks(db, oldId, canonicalId)
	mergeScrobblings(db, oldId, canonicalId)
	copyByMangaId(db, oldId, canonicalId, "local_index", "manga_id, path", "?, path")
	// track_logs has its own surrogate primary key, so a direct update cannot collide.
	db.execSQL("UPDATE track_logs SET manga_id = ? WHERE manga_id = ?", arrayOf(canonicalId, oldId))
	CHILD_TABLES.forEach { table ->
		db.execSQL("DELETE FROM $table WHERE manga_id = ?", arrayOf(oldId))
	}
	db.execSQL("DELETE FROM manga WHERE manga_id = ?", arrayOf(oldId))
	return true
}

/**
 * Re-hashes the chapter ids of one manga row and of everything pointing at its chapters.
 *
 * A chapter id embeds the source token exactly like a manga id does, so a row whose identity moves
 * carries a `chapters` blob, `history.chapter_id`, `bookmarks.chapter_id` and
 * `tracks.last_chapter_id` the runtime can no longer produce: the next details refresh rewrites the
 * blob from the new token and the stale ids stop resolving. The chapter urls are in the blob
 * already, so the new ids are recomputed from there and replayed over the tables that reference
 * one. A row whose ids already match is left untouched.
 */
internal fun rekeyChapterIds(db: SupportSQLiteDatabase, mangaId: String, sourceName: String) {
	val storedChapters = db.query("SELECT chapters FROM manga WHERE manga_id = ?", arrayOf(mangaId)).use { cursor ->
		if (cursor.moveToFirst()) cursor.getString(0) else null
	} ?: return
	val rekeyed = rekeyedChapters(sourceName, storedChapters) ?: return
	db.execSQL("UPDATE manga SET chapters = ? WHERE manga_id = ?", arrayOf(rekeyed.chapters, mangaId))
	if (rekeyed.ids.isEmpty()) return
	CHAPTER_ID_COLUMNS.forEach { (table, column) ->
		val storedIds = buildList {
			db.query("SELECT DISTINCT $column FROM $table WHERE manga_id = ?", arrayOf(mangaId)).use { cursor ->
				while (cursor.moveToNext()) add(cursor.getString(0))
			}
		}
		storedIds.forEach { chapterId ->
			val newChapterId = rekeyed.ids[chapterId] ?: return@forEach
			db.execSQL(
				"UPDATE $table SET $column = ? WHERE manga_id = ? AND $column = ?",
				arrayOf(newChapterId, mangaId, chapterId),
			)
		}
	}
}

private fun isAliasOfCanonicalRow(
	db: SupportSQLiteDatabase,
	canonicalId: String,
	sourceName: String,
	url: String,
): Boolean = db.query("SELECT source, url FROM manga WHERE manga_id = ?", arrayOf(canonicalId)).use { cursor ->
	if (!cursor.moveToFirst()) return@use true
	val existingSource = decodeStoredSourceName(cursor.getString(0))
	val existingUrl = cursor.getString(1)
	legacyParserSourceName(existingSource) == legacyParserSourceName(sourceName) &&
		canonicalLegacyMangaUrl(existingSource, existingUrl) == canonicalLegacyMangaUrl(sourceName, url)
}

/**
 * History is the parent of stats. Materialise the canonical history row first, copy its stats, and
 * only then remove the alias so no cascade can erase reading-time data. When both rows exist the
 * most recently updated reading position wins, keeping the earliest creation time.
 */
private fun mergeHistory(db: SupportSQLiteDatabase, oldId: String, canonicalId: String) {
	db.execSQL(
		"INSERT OR IGNORE INTO history (manga_id, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters) " +
			"SELECT ?, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters " +
			"FROM history WHERE manga_id = ?",
		arrayOf(canonicalId, oldId),
	)
	db.execSQL(
		"""
		UPDATE history
		SET created_at = MIN(created_at, (SELECT old.created_at FROM history old WHERE old.manga_id = ?)),
		    updated_at = (SELECT old.updated_at FROM history old WHERE old.manga_id = ?),
		    chapter_id = (SELECT old.chapter_id FROM history old WHERE old.manga_id = ?),
		    page = (SELECT old.page FROM history old WHERE old.manga_id = ?),
		    scroll = (SELECT old.scroll FROM history old WHERE old.manga_id = ?),
		    percent = (SELECT old.percent FROM history old WHERE old.manga_id = ?),
		    deleted_at = (SELECT old.deleted_at FROM history old WHERE old.manga_id = ?),
		    chapters = (SELECT old.chapters FROM history old WHERE old.manga_id = ?)
		WHERE manga_id = ?
		  AND EXISTS (SELECT 1 FROM history old WHERE old.manga_id = ?)
		  AND (SELECT MAX(old.updated_at, old.deleted_at) FROM history old WHERE old.manga_id = ?) >
		      MAX(updated_at, deleted_at)
		""".trimIndent(),
		arrayOf(oldId, oldId, oldId, oldId, oldId, oldId, oldId, oldId, canonicalId, oldId, oldId),
	)
}

private fun mergeStats(db: SupportSQLiteDatabase, oldId: String, canonicalId: String) {
	copyByMangaId(db, oldId, canonicalId, "stats", "manga_id, started_at, duration, pages", "?, started_at, duration, pages")
	db.execSQL(
		"""
		UPDATE stats
		SET duration = MAX(duration, COALESCE((
		      SELECT old.duration FROM stats old
		      WHERE old.manga_id = ? AND old.started_at = stats.started_at
		    ), duration)),
		    pages = MAX(pages, COALESCE((
		      SELECT old.pages FROM stats old
		      WHERE old.manga_id = ? AND old.started_at = stats.started_at
		    ), pages))
		WHERE manga_id = ?
		""".trimIndent(),
		arrayOf(oldId, oldId, canonicalId),
	)
}

/**
 * Use the row's effective update clock (creation vs tombstone). Resurrecting an older active alias
 * over a newer deletion is data loss, and SQLite row order is not a policy.
 */
private fun mergeFavourites(db: SupportSQLiteDatabase, oldId: String, canonicalId: String) {
	copyByMangaId(
		db,
		oldId,
		canonicalId,
		"favourites",
		"manga_id, category_id, sort_key, pinned, created_at, deleted_at",
		"?, category_id, sort_key, pinned, created_at, deleted_at",
	)
	db.execSQL(
		"""
		UPDATE favourites
		SET sort_key = (SELECT old.sort_key FROM favourites old
		      WHERE old.manga_id = ? AND old.category_id = favourites.category_id),
		    pinned = (SELECT old.pinned FROM favourites old
		      WHERE old.manga_id = ? AND old.category_id = favourites.category_id),
		    created_at = (SELECT old.created_at FROM favourites old
		      WHERE old.manga_id = ? AND old.category_id = favourites.category_id),
		    deleted_at = (SELECT old.deleted_at FROM favourites old
		      WHERE old.manga_id = ? AND old.category_id = favourites.category_id)
		WHERE manga_id = ?
		  AND EXISTS (SELECT 1 FROM favourites old
		    WHERE old.manga_id = ? AND old.category_id = favourites.category_id)
		  AND (SELECT MAX(old.created_at, old.deleted_at) FROM favourites old
		    WHERE old.manga_id = ? AND old.category_id = favourites.category_id) >
		      MAX(created_at, deleted_at)
		""".trimIndent(),
		arrayOf(oldId, oldId, oldId, oldId, canonicalId, oldId, oldId),
	)
}

/**
 * Preferences have no timestamp. Preserve every explicit canonical choice, filling only fields that
 * are still at their neutral value from the alias row.
 */
private fun mergePreferences(db: SupportSQLiteDatabase, oldId: String, canonicalId: String) {
	copyByMangaId(
		db,
		oldId,
		canonicalId,
		"preferences",
		"manga_id, mode, cf_brightness, cf_contrast, cf_invert, cf_grayscale, cf_book, " +
			"cf_multitone, title_override, cover_override, content_rating_override",
		"?, mode, cf_brightness, cf_contrast, cf_invert, cf_grayscale, cf_book, " +
			"cf_multitone, title_override, cover_override, content_rating_override",
	)
	db.execSQL(
		"""
		UPDATE preferences
		SET mode = CASE WHEN mode = -1 THEN COALESCE((
		      SELECT old.mode FROM preferences old WHERE old.manga_id = ?
		    ), mode) ELSE mode END,
		    cf_brightness = CASE WHEN cf_brightness = 0 THEN COALESCE((
		      SELECT old.cf_brightness FROM preferences old WHERE old.manga_id = ?
		    ), cf_brightness) ELSE cf_brightness END,
		    cf_contrast = CASE WHEN cf_contrast = 0 THEN COALESCE((
		      SELECT old.cf_contrast FROM preferences old WHERE old.manga_id = ?
		    ), cf_contrast) ELSE cf_contrast END,
		    cf_invert = MAX(cf_invert, COALESCE((
		      SELECT old.cf_invert FROM preferences old WHERE old.manga_id = ?
		    ), cf_invert)),
		    cf_grayscale = MAX(cf_grayscale, COALESCE((
		      SELECT old.cf_grayscale FROM preferences old WHERE old.manga_id = ?
		    ), cf_grayscale)),
		    cf_book = MAX(cf_book, COALESCE((
		      SELECT old.cf_book FROM preferences old WHERE old.manga_id = ?
		    ), cf_book)),
		    cf_multitone = CASE WHEN cf_multitone = 0 THEN COALESCE((
		      SELECT old.cf_multitone FROM preferences old WHERE old.manga_id = ?
		    ), cf_multitone) ELSE cf_multitone END,
		    title_override = COALESCE(title_override, (
		      SELECT old.title_override FROM preferences old WHERE old.manga_id = ?
		    )),
		    cover_override = COALESCE(cover_override, (
		      SELECT old.cover_override FROM preferences old WHERE old.manga_id = ?
		    )),
		    content_rating_override = COALESCE(content_rating_override, (
		      SELECT old.content_rating_override FROM preferences old WHERE old.manga_id = ?
		    ))
		WHERE manga_id = ?
		""".trimIndent(),
		arrayOf(oldId, oldId, oldId, oldId, oldId, oldId, oldId, oldId, oldId, oldId, canonicalId),
	)
}

/** Tracker state has an explicit recency clock, so the newer complete record wins. */
private fun mergeTracks(db: SupportSQLiteDatabase, oldId: String, canonicalId: String) {
	copyByMangaId(
		db,
		oldId,
		canonicalId,
		"tracks",
		"manga_id, last_chapter_id, chapters_new, last_check_time, last_chapter_date, last_result, last_error",
		"?, last_chapter_id, chapters_new, last_check_time, last_chapter_date, last_result, last_error",
	)
	db.execSQL(
		"""
		UPDATE tracks
		SET last_chapter_id = (SELECT old.last_chapter_id FROM tracks old WHERE old.manga_id = ?),
		    chapters_new = (SELECT old.chapters_new FROM tracks old WHERE old.manga_id = ?),
		    last_check_time = (SELECT old.last_check_time FROM tracks old WHERE old.manga_id = ?),
		    last_chapter_date = (SELECT old.last_chapter_date FROM tracks old WHERE old.manga_id = ?),
		    last_result = (SELECT old.last_result FROM tracks old WHERE old.manga_id = ?),
		    last_error = (SELECT old.last_error FROM tracks old WHERE old.manga_id = ?)
		WHERE manga_id = ?
		  AND EXISTS (SELECT 1 FROM tracks old WHERE old.manga_id = ?)
		  AND (SELECT old.last_check_time FROM tracks old WHERE old.manga_id = ?) > last_check_time
		""".trimIndent(),
		arrayOf(oldId, oldId, oldId, oldId, oldId, oldId, canonicalId, oldId, oldId),
	)
}

private fun mergeSuggestions(db: SupportSQLiteDatabase, oldId: String, canonicalId: String) {
	copyByMangaId(db, oldId, canonicalId, "suggestions", "manga_id, relevance, created_at", "?, relevance, created_at")
	db.execSQL(
		"""
		UPDATE suggestions
		SET relevance = MAX(relevance, COALESCE((
		      SELECT old.relevance FROM suggestions old WHERE old.manga_id = ?
		    ), relevance)),
		    created_at = MIN(created_at, COALESCE((
		      SELECT old.created_at FROM suggestions old WHERE old.manga_id = ?
		    ), created_at))
		WHERE manga_id = ?
		""".trimIndent(),
		arrayOf(oldId, oldId, canonicalId),
	)
}

private fun mergeBookmarks(db: SupportSQLiteDatabase, oldId: String, canonicalId: String) {
	copyByMangaId(
		db,
		oldId,
		canonicalId,
		"bookmarks",
		"manga_id, page_id, chapter_id, page, scroll, image, created_at, percent, deleted_at",
		"?, page_id, chapter_id, page, scroll, image, created_at, percent, deleted_at",
	)
	db.execSQL(
		"""
		UPDATE bookmarks
		SET chapter_id = (SELECT old.chapter_id FROM bookmarks old
		      WHERE old.manga_id = ? AND old.page_id = bookmarks.page_id),
		    page = (SELECT old.page FROM bookmarks old
		      WHERE old.manga_id = ? AND old.page_id = bookmarks.page_id),
		    scroll = (SELECT old.scroll FROM bookmarks old
		      WHERE old.manga_id = ? AND old.page_id = bookmarks.page_id),
		    image = (SELECT old.image FROM bookmarks old
		      WHERE old.manga_id = ? AND old.page_id = bookmarks.page_id),
		    created_at = (SELECT old.created_at FROM bookmarks old
		      WHERE old.manga_id = ? AND old.page_id = bookmarks.page_id),
		    percent = (SELECT old.percent FROM bookmarks old
		      WHERE old.manga_id = ? AND old.page_id = bookmarks.page_id),
		    deleted_at = (SELECT old.deleted_at FROM bookmarks old
		      WHERE old.manga_id = ? AND old.page_id = bookmarks.page_id)
		WHERE manga_id = ?
		  AND EXISTS (SELECT 1 FROM bookmarks old
		    WHERE old.manga_id = ? AND old.page_id = bookmarks.page_id)
		  AND (SELECT MAX(old.created_at, old.deleted_at) FROM bookmarks old
		    WHERE old.manga_id = ? AND old.page_id = bookmarks.page_id) >
		      MAX(created_at, deleted_at)
		""".trimIndent(),
		arrayOf(oldId, oldId, oldId, oldId, oldId, oldId, oldId, canonicalId, oldId, oldId),
	)
}

/**
 * A scrobbling is one logical tracker link per scrobbler, keyed by a device-local surrogate id.
 * Keep the established canonical surrogate even when the alias carries further progress, then
 * replace the whole logical key.
 */
private fun mergeScrobblings(db: SupportSQLiteDatabase, oldId: String, canonicalId: String) {
	val candidates = buildList {
		db.query(
			"SELECT scrobbler, id, manga_id, target_id, status, chapter, comment, rating FROM scrobblings " +
				"WHERE manga_id = ? OR manga_id = ?",
			arrayOf(canonicalId, oldId),
		).use { cursor ->
			while (cursor.moveToNext()) {
				add(
					MigrationScrobbling(
						scrobbler = cursor.getInt(0),
						id = cursor.getInt(1),
						mangaId = cursor.getString(2),
						targetId = cursor.getLong(3),
						status = if (cursor.isNull(4)) null else cursor.getString(4),
						chapter = cursor.getInt(5),
						comment = if (cursor.isNull(6)) null else cursor.getString(6),
						rating = cursor.getFloat(7),
					),
				)
			}
		}
	}
	candidates.groupBy { it.scrobbler }.forEach { (scrobbler, logicalRows) ->
		val winner = logicalRows.maxWith(
			compareBy<MigrationScrobbling> { it.chapter }
				.thenBy { it.rating }
				.thenBy { it.status.orEmpty() }
				.thenBy { it.comment.orEmpty() }
				.thenBy { it.targetId }
				.thenBy { if (it.mangaId == canonicalId) 1 else 0 }
				.thenBy { -it.id.toLong() },
		)
		val stableId = logicalRows.filter { it.mangaId == canonicalId }.minOfOrNull { it.id } ?: winner.id
		db.execSQL(
			"DELETE FROM scrobblings WHERE scrobbler = ? AND (manga_id = ? OR manga_id = ?)",
			arrayOf(scrobbler, canonicalId, oldId),
		)
		db.execSQL(
			"INSERT INTO scrobblings (scrobbler, id, manga_id, target_id, status, chapter, comment, rating) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
			arrayOf<Any?>(
				scrobbler,
				stableId,
				canonicalId,
				winner.targetId,
				winner.status,
				winner.chapter,
				winner.comment,
				winner.rating,
			),
		)
	}
}

private fun copyByMangaId(
	db: SupportSQLiteDatabase,
	oldId: String,
	canonicalId: String,
	table: String,
	columns: String,
	projection: String,
) {
	db.execSQL(
		"INSERT OR IGNORE INTO $table ($columns) SELECT $projection FROM $table WHERE manga_id = ?",
		arrayOf(canonicalId, oldId),
	)
}

private data class MigrationScrobbling(
	val scrobbler: Int,
	val id: Int,
	val mangaId: String,
	val targetId: Long,
	val status: String?,
	val chapter: Int,
	val comment: String?,
	val rating: Float,
)
