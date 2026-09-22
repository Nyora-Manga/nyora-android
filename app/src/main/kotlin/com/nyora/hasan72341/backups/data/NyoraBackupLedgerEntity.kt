package com.nyora.hasan72341.backups.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "nyora_backup_ledger")
data class NyoraBackupLedgerEntity(
	@PrimaryKey
	@ColumnInfo(name = "ledger_id")
	val id: Int = SINGLETON_ID,
	@ColumnInfo(name = "archive")
	val archive: ByteArray,
	@ColumnInfo(name = "baseline")
	val baseline: ByteArray,
) {
	companion object {
		const val SINGLETON_ID = 1
	}
}

@Entity(
	tableName = "nyora_backup_identity_map",
	primaryKeys = ["kind", "portable_id"],
	indices = [Index(value = ["kind", "local_key"], unique = true)],
)
data class NyoraBackupIdentityMapEntity(
	val kind: String,
	@ColumnInfo(name = "portable_id") val portableId: String,
	@ColumnInfo(name = "local_key") val localKey: String,
)

@Dao
interface NyoraBackupLedgerDao {
	@Query("SELECT * FROM nyora_backup_ledger WHERE ledger_id = 1")
	suspend fun get(): NyoraBackupLedgerEntity?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun put(entity: NyoraBackupLedgerEntity)
}

@Dao
abstract class NyoraBackupIdentityMapDao {
	@Query("SELECT local_key FROM nyora_backup_identity_map WHERE kind = :kind AND portable_id = :portableId")
	abstract suspend fun findLocalKey(kind: String, portableId: String): String?

	@Query("SELECT portable_id FROM nyora_backup_identity_map WHERE kind = :kind AND local_key = :localKey")
	abstract suspend fun findPortableId(kind: String, localKey: String): String?

	@Query(
		"""
		SELECT COALESCE(MAX(candidate), 0) + 1
		FROM (
			SELECT CAST(local_key AS INTEGER) AS candidate
			FROM nyora_backup_identity_map
			WHERE kind = :kind
			UNION ALL
			SELECT category_id AS candidate
			FROM favourite_categories
			WHERE :kind = 'category'
		)
		""",
	)
	protected abstract suspend fun nextNumericLocalKey(kind: String): Int

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insert(entity: NyoraBackupIdentityMapEntity)

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	protected abstract suspend fun insertIfAbsent(entities: List<NyoraBackupIdentityMapEntity>)

	@androidx.room.Transaction
	open suspend fun resolveLocalId(kind: String, portableId: String): Int {
		findLocalKey(kind, portableId)?.toIntOrNull()?.let { return it }
		val next = nextNumericLocalKey(kind).coerceAtLeast(1)
		insert(NyoraBackupIdentityMapEntity(kind, portableId, next.toString()))
		return next
	}

	@androidx.room.Transaction
	open suspend fun recordLocalKey(kind: String, portableId: String, localKey: String) {
		val existing = findLocalKey(kind, portableId)
		if (existing == null) insert(NyoraBackupIdentityMapEntity(kind, portableId, localKey))
		else require(existing == localKey) { "Portable identity is already mapped" }
	}

	/**
	 * Records a batch of mappings, keeping whichever got there first.
	 *
	 * Manga ids are derived rather than allocated, and two retired spellings of one source hash to
	 * the same local row, so a taken local key is a legitimate collision here rather than the
	 * programming error [recordLocalKey] reports. One batched insert is also one commit: these
	 * mappings are recorded per manga row, so inserting them one at a time would commit once per
	 * row of the library.
	 */
	open suspend fun recordLocalKeysIfAbsent(entries: List<NyoraBackupIdentityMapEntity>) {
		if (entries.isNotEmpty()) insertIfAbsent(entries)
	}
}
