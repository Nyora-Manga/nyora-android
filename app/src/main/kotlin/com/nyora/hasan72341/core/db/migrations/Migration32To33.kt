package com.nyora.hasan72341.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nyora.hasan72341.backups.data.NyoraBackupIdentity
import com.nyora.hasan72341.backups.data.NyoraSourceIdentity
import java.util.Locale

class Migration32To33 : Migration(32, 33) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `nyora_backup_ledger` (`ledger_id` INTEGER NOT NULL, `archive` BLOB NOT NULL, `baseline` BLOB NOT NULL, PRIMARY KEY(`ledger_id`))",
		)
		db.execSQL("CREATE TABLE IF NOT EXISTS `nyora_backup_identity_map` (`kind` TEXT NOT NULL, `portable_id` TEXT NOT NULL, `local_key` TEXT NOT NULL, PRIMARY KEY(`kind`, `portable_id`))")
		db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_nyora_backup_identity_map_kind_local_key` ON `nyora_backup_identity_map` (`kind`, `local_key`)")
		db.execSQL(
			"INSERT OR IGNORE INTO sources (source, enabled, sort_key, added_in, used_at, pinned, cf_state) " +
				"SELECT 'data:' || lower(replace(substr(source, 4), '_', '-')), enabled, sort_key, added_in, used_at, pinned, cf_state FROM sources WHERE source LIKE 'JS_%'",
		)
		db.execSQL("DELETE FROM sources WHERE source LIKE 'JS_%' OR source LIKE 'MIHON_%' OR source LIKE 'mihon:%' OR source GLOB '[0-9]*'")
		canonicalizeMangaIdentities(db)
		db.execSQL(
			"DELETE FROM manga WHERE source LIKE '%MIHON_%' OR source LIKE '%mihon:%' OR source GLOB '[0-9]*' OR source GLOB '{\"name\":\"[0-9]*\"}'",
		)
	}

	private fun canonicalizeMangaIdentities(db: SupportSQLiteDatabase) {
		val rows = buildList {
			db.query("SELECT manga_id, url, source FROM manga").use { cursor ->
				while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
			}
		}
		db.execSQL("PRAGMA defer_foreign_keys = ON")
		rows.forEach { (oldId, contentKey, storedSource) ->
			val sourceName = SOURCE_NAME.find(storedSource)?.groupValues?.get(1) ?: storedSource
			// Retired aliases are accepted only during this bounded database upgrade.
			val migratedSourceName = if (sourceName.startsWith("JS_")) {
				"data:" + sourceName.removePrefix("JS_").lowercase(Locale.ROOT).replace('_', '-')
			} else {
				sourceName
			}
			val canonicalSource = NyoraSourceIdentity.canonicalize(migratedSourceName) ?: return@forEach
			val canonicalId = NyoraBackupIdentity.mangaId(canonicalSource, contentKey)
			val canonicalStoredSource = if (storedSource.trimStart().startsWith('{')) {
				"{\"name\":\"$canonicalSource\"}"
			} else {
				canonicalSource
			}
			if (oldId == canonicalId) {
				db.execSQL("UPDATE manga SET source = ? WHERE manga_id = ?", arrayOf(canonicalStoredSource, oldId))
				return@forEach
			}
			db.execSQL(
				"INSERT OR IGNORE INTO manga (manga_id, title, alt_title, url, public_url, rating, nsfw, content_rating, cover_url, large_cover_url, state, author, source, description, tags, chapters, unread, progress) " +
					"SELECT ?, title, alt_title, url, public_url, rating, nsfw, content_rating, cover_url, large_cover_url, state, author, ?, description, tags, chapters, unread, progress FROM manga WHERE manga_id = ?",
				arrayOf(canonicalId, canonicalStoredSource, oldId),
			)
			DEPENDENT_TABLES.forEach { table ->
				db.execSQL("UPDATE OR IGNORE $table SET manga_id = ? WHERE manga_id = ?", arrayOf(canonicalId, oldId))
			}
			DEPENDENT_TABLES.asReversed().forEach { table ->
				db.execSQL("DELETE FROM $table WHERE manga_id = ?", arrayOf(oldId))
			}
			db.execSQL("DELETE FROM manga WHERE manga_id = ?", arrayOf(oldId))
		}
	}

	private companion object {
		val SOURCE_NAME = Regex("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
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
