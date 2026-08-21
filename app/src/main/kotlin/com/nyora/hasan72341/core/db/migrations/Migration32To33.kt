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
	}
}
