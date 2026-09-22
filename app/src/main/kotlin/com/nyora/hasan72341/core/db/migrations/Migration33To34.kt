package com.nyora.hasan72341.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nyora.hasan72341.core.parser.datadriven.LEGACY_SOURCE_RENAMES

/**
 * Renames the source spellings schema 33 derived from the retired JavaScript enums to the ones the
 * catalogue publishes.
 *
 * Eight of the renames only swap a delimiter. The legacy manga id is hashed from the parser enum
 * rather than from the stored source string, so those rows keep the id they were written with.
 * `data:manganato-gg` -> `data:manganato` hashes from a different token, and an Asura row whose url
 * still carries the retired `asurascans.com` host loses that host from the hashed url, so rows of
 * those two sources move to the new id along with every row that points at them.
 *
 * Chapter ids are hashed from the source token as well, so every settled row also has the ids in
 * its `chapters` blob recomputed and replayed over the tables that reference a chapter.
 */
class Migration33To34 : Migration(33, 34) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("PRAGMA defer_foreign_keys = ON")
		LEGACY_SOURCE_RENAMES.forEach { (old, new) ->
			db.execSQL("UPDATE OR IGNORE sources SET source = ? WHERE source = ?", arrayOf(new, old))
			db.execSQL("DELETE FROM sources WHERE source = ?", arrayOf(old))
			renameMangaRows(db, old, new)
			renameMangaRows(db, "{\"name\":\"$old\"}", new)
		}
	}

	private fun renameMangaRows(db: SupportSQLiteDatabase, storedSource: String, newSourceName: String) {
		val renamedStoredSource = renamedSource(storedSource) ?: return
		val rows = buildList {
			db.query("SELECT manga_id, url FROM manga WHERE source = ?", arrayOf(storedSource)).use { cursor ->
				while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
			}
		}
		val settledIds = LinkedHashSet<String>()
		rows.forEach { (oldId, url) ->
			val newId = rekeyedMangaId(newSourceName, url)
			settledIds.add(newId)
			if (newId == oldId) return@forEach
			db.execSQL(
				"INSERT OR IGNORE INTO manga (manga_id, title, alt_title, url, public_url, rating, nsfw, content_rating, cover_url, large_cover_url, state, author, source, description, tags, chapters, unread, progress) " +
					"SELECT ?, title, alt_title, url, public_url, rating, nsfw, content_rating, cover_url, large_cover_url, state, author, ?, description, tags, chapters, unread, progress FROM manga WHERE manga_id = ?",
				arrayOf(newId, renamedStoredSource, oldId),
			)
			DEPENDENT_TABLES.forEach { table ->
				db.execSQL("UPDATE OR IGNORE $table SET manga_id = ? WHERE manga_id = ?", arrayOf(newId, oldId))
			}
			DEPENDENT_TABLES.asReversed().forEach { table ->
				db.execSQL("DELETE FROM $table WHERE manga_id = ?", arrayOf(oldId))
			}
			db.execSQL("DELETE FROM manga WHERE manga_id = ?", arrayOf(oldId))
		}
		db.execSQL("UPDATE manga SET source = ? WHERE source = ?", arrayOf(renamedStoredSource, storedSource))
		settledIds.forEach { mangaId -> renameChapterIds(db, mangaId, newSourceName) }
	}

	/**
	 * Re-hashes the chapter ids of one settled manga row.
	 *
	 * A chapter id is hashed from the source token too, so a row that moved to a renamed identity
	 * carries a `chapters` blob, `history.chapter_id`, `bookmarks.chapter_id` and
	 * `tracks.last_chapter_id` the runtime can no longer produce: the next details refresh rewrites
	 * the blob from the new token and the stale ids stop resolving. The chapter urls are in the blob
	 * already, so the new ids are recomputed from there and replayed over the tables that reference
	 * one. A row whose ids already match is left untouched.
	 */
	private fun renameChapterIds(db: SupportSQLiteDatabase, mangaId: String, newSourceName: String) {
		val storedChapters = db.query("SELECT chapters FROM manga WHERE manga_id = ?", arrayOf(mangaId)).use { cursor ->
			if (cursor.moveToFirst()) cursor.getString(0) else null
		} ?: return
		val rekeyed = rekeyedChapters(newSourceName, storedChapters) ?: return
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

	private companion object {

		/** Tables holding a chapter id, none of which constrain it, so a plain update cannot collide. */
		val CHAPTER_ID_COLUMNS = listOf(
			"history" to "chapter_id",
			"bookmarks" to "chapter_id",
			"tracks" to "last_chapter_id",
		)

		val DEPENDENT_TABLES = listOf(
			"history",
			"favourites",
			"preferences",
			"tracks",
			"track_logs",
			"suggestions",
			"bookmarks",
			"scrobblings",
			"stats",
			"local_index",
		)
	}
}
