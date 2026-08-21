package com.nyora.hasan72341.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration32To33 : Migration(32, 33) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `nyora_backup_ledger` (`ledger_id` INTEGER NOT NULL, `archive` BLOB NOT NULL, `baseline` BLOB NOT NULL, PRIMARY KEY(`ledger_id`))",
		)
		db.execSQL("CREATE TABLE IF NOT EXISTS `nyora_backup_identity_map` (`kind` TEXT NOT NULL, `portable_id` TEXT NOT NULL, `local_key` TEXT NOT NULL, PRIMARY KEY(`kind`, `portable_id`))")
		db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_nyora_backup_identity_map_kind_local_key` ON `nyora_backup_identity_map` (`kind`, `local_key`)")
		db.execSQL(
			"INSERT OR REPLACE INTO sources (source, enabled, sort_key, added_in, used_at, pinned, cf_state) " +
				"SELECT 'data:' || lower(replace(substr(source, 4), '_', '-')), enabled, sort_key, added_in, used_at, pinned, cf_state FROM sources WHERE source LIKE 'JS_%'",
		)
		db.execSQL("DELETE FROM sources WHERE source LIKE 'JS_%' OR source LIKE 'MIHON_%' OR source LIKE 'mihon:%' OR source GLOB '[0-9]*'")
		db.execSQL(
			"UPDATE manga SET source = '{\"name\":\"data:' || lower(replace(substr(source, 13, length(source) - 14), '_', '-')) || '\"}' " +
				"WHERE substr(source, 1, 12) = '{\"name\":\"JS_'",
		)
		db.execSQL("UPDATE manga SET source = 'data:' || lower(replace(substr(source, 4), '_', '-')) WHERE source LIKE 'JS_%'")
		db.execSQL(
			"DELETE FROM manga WHERE source LIKE '%MIHON_%' OR source LIKE '%mihon:%' OR source GLOB '[0-9]*' OR source GLOB '{\"name\":\"[0-9]*\"}'",
		)
	}
}
