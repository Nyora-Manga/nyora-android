package com.nyora.hasan72341.core.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the backup tables and upgrades every source identity a shipped build wrote.
 *
 * Schema 32 is the last version that shipped, from two lineages: v2.1.6 wrote the JavaScript enum
 * `JS_<ID>` with manga ids already hashed the way every client hashes them, while v2.6 to v2.7.3
 * wrote `DD_<catalogue id>` with manga ids the shipped adapters invented - the engine's relative
 * href for a data-driven row, `<source>|<url>` for the native MangaFire and ToonDex routes. Schema
 * 33 never shipped, so this is the one place both shapes are retired: the source names become the
 * `data:` spelling the catalogue publishes, and a row whose id is not the shared legacy hash is
 * merged onto it so its favourites, history, bookmarks and downloads move with it.
 *
 * The Mihon and numeric identities have no successor and are purged, which is the legacy removal
 * boundary the architecture document draws.
 */
class Migration32To33 : Migration(32, 33) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("PRAGMA defer_foreign_keys = ON")
		createBackupTables(db)
		upgradeSources(db)
		upgradeManga(db)
		purgeRetiredRows(db)
	}

	private fun createBackupTables(db: SupportSQLiteDatabase) {
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `nyora_backup_ledger` (`ledger_id` INTEGER NOT NULL, `archive` BLOB NOT NULL, `baseline` BLOB NOT NULL, PRIMARY KEY(`ledger_id`))",
		)
		db.execSQL("CREATE TABLE IF NOT EXISTS `nyora_backup_identity_map` (`kind` TEXT NOT NULL, `portable_id` TEXT NOT NULL, `local_key` TEXT NOT NULL, PRIMARY KEY(`kind`, `portable_id`))")
		db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_nyora_backup_identity_map_kind_local_key` ON `nyora_backup_identity_map` (`kind`, `local_key`)")
	}

	/**
	 * Moves each retired source row to its `data:` spelling, keeping the flags the reader set. An
	 * already-canonical row wins the collision, because it is the one the current build wrote.
	 */
	private fun upgradeSources(db: SupportSQLiteDatabase) {
		val rows = buildList {
			db.query("SELECT source, enabled, sort_key, added_in, used_at, pinned, cf_state FROM sources").use { cursor ->
				while (cursor.moveToNext()) {
					add(StoredSource(cursor.getString(0), Array<Any?>(6) { cursor.getLong(it + 1) }))
				}
			}
		}
		rows.forEach { row ->
			val upgraded = upgradedStoredSourceName(row.name) ?: return@forEach
			if (upgraded == row.name) return@forEach
			db.execSQL(
				"INSERT OR IGNORE INTO sources (source, enabled, sort_key, added_in, used_at, pinned, cf_state) " +
					"VALUES (?, ?, ?, ?, ?, ?, ?)",
				arrayOf(upgraded, *row.flags),
			)
			db.execSQL("DELETE FROM sources WHERE source = ?", arrayOf(row.name))
		}
	}

	/**
	 * Settles every manga row of a data source on the shared identity: the canonical source name in
	 * the shape the row stored it, the legacy manga id hashed from (source, url), and chapter ids
	 * hashed from the same token.
	 */
	private fun upgradeManga(db: SupportSQLiteDatabase) {
		val rows = buildList {
			db.query("SELECT manga_id, source, url FROM manga").use { cursor ->
				while (cursor.moveToNext()) {
					add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
				}
			}
		}
		rows.forEach { (mangaId, storedSource, url) ->
			val sourceName = upgradedStoredSourceName(storedSource) ?: return@forEach
			val canonicalId = upgradedMangaId(sourceName, url)
			val moved = canonicalId != mangaId
			// Re-key before the merge, while this row still owns the `chapters` blob: the blob is the
			// only place a chapter url is stored, and merging onto a canonical row that already exists
			// keeps that row's blob and drops this one, which would strand the history and bookmark
			// rows travelling with it on chapter ids the runtime can never produce again. A row whose
			// id is already the one every client stamps hashes its chapter ids from the same token, so
			// it cannot be carrying stale ones and its blob - the largest column in the database -
			// needs no reading; a canonical row a merge lands on is such a row by definition.
			if (moved) {
				rekeyChapterIds(db, mangaId, sourceName)
			}
			val settledId = if (moved && mergeLegacyMangaAlias(db, mangaId, canonicalId, sourceName, url)) {
				canonicalId
			} else {
				mangaId
			}
			val upgradedStored = storedSourceName(storedSource, sourceName)
			if (upgradedStored != storedSource) {
				db.execSQL("UPDATE manga SET source = ? WHERE manga_id = ?", arrayOf(upgradedStored, settledId))
			}
		}
	}

	/** `manga.source` is stored bare or as the `{"name":"..."}` object the entity mapper writes. */
	private fun storedSourceName(storedSource: String, sourceName: String): String =
		if (storedSource.trimStart().startsWith('{')) "{\"name\":\"$sourceName\"}" else sourceName

	/**
	 * Removes the Mihon extension and numeric identities. Neither has a data-driven successor to be
	 * renamed to, and a row that cannot name its source cannot be opened, so they go rather than
	 * linger as unresolvable entries.
	 */
	private fun purgeRetiredRows(db: SupportSQLiteDatabase) {
		val purgedSources = countRows(db, "sources", RETIRED_SOURCES)
		val purgedManga = countRows(db, "manga", RETIRED_MANGA_SOURCES)
		db.execSQL("DELETE FROM sources WHERE $RETIRED_SOURCES")
		db.execSQL("DELETE FROM manga WHERE $RETIRED_MANGA_SOURCES")
		if (purgedSources != 0 || purgedManga != 0) {
			Log.i(TAG, "Purged $purgedSources retired source rows and $purgedManga manga rows")
		}
	}

	private fun countRows(db: SupportSQLiteDatabase, table: String, predicate: String): Int =
		db.query("SELECT COUNT(*) FROM $table WHERE $predicate").use { cursor ->
			if (cursor.moveToFirst()) cursor.getInt(0) else 0
		}

	private class StoredSource(val name: String, val flags: Array<Any?>)

	private companion object {

		const val TAG = "Migration32To33"

		const val RETIRED_SOURCES = "source LIKE 'MIHON_%' OR source LIKE 'mihon:%' OR source GLOB '[0-9]*'"

		const val RETIRED_MANGA_SOURCES = "source LIKE '%MIHON_%' OR source LIKE '%mihon:%' " +
			"OR source GLOB '[0-9]*' OR source GLOB '{\"name\":\"[0-9]*\"}'"
	}
}
