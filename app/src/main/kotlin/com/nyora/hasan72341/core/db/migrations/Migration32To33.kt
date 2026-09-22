package com.nyora.hasan72341.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Locale

/**
 * Adds the backup tables and retires the JavaScript source names.
 *
 * Manga ids are left alone: the local id of a data source is the legacy hash the JavaScript bundle,
 * desktop and web all stamp, so re-keying the rows here would strand every history, favourite and
 * download that already resolves through it.
 */
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
		renameJavaScriptMangaSources(db)
		db.execSQL(
			"DELETE FROM manga WHERE source LIKE '%MIHON_%' OR source LIKE '%mihon:%' OR source GLOB '[0-9]*' OR source GLOB '{\"name\":\"[0-9]*\"}'",
		)
	}

	/**
	 * Rewrite the source string of every `JS_` manga row, bare or JSON-wrapped, to its `data:` name.
	 * There are only ever a handful of distinct source strings, and the rows keep their ids.
	 */
	private fun renameJavaScriptMangaSources(db: SupportSQLiteDatabase) {
		val storedSources = buildList {
			db.query("SELECT DISTINCT source FROM manga").use { cursor ->
				while (cursor.moveToNext()) add(cursor.getString(0))
			}
		}
		storedSources.forEach { storedSource ->
			val sourceName = SOURCE_NAME.find(storedSource)?.groupValues?.get(1) ?: storedSource
			if (!sourceName.startsWith("JS_")) return@forEach
			// The retired alias is accepted only during this bounded database upgrade.
			val renamed = "data:" + sourceName.removePrefix("JS_").lowercase(Locale.ROOT).replace('_', '-')
			val renamedStoredSource = if (storedSource.trimStart().startsWith('{')) {
				"{\"name\":\"$renamed\"}"
			} else {
				renamed
			}
			db.execSQL("UPDATE manga SET source = ? WHERE source = ?", arrayOf(renamedStoredSource, storedSource))
		}
	}

	private companion object {

		val SOURCE_NAME = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
	}
}
