package com.nyora.hasan72341.backups.data

import androidx.room.withTransaction
import com.nyora.hasan72341.core.db.MangaDatabase
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

fun interface NyoraPortableMaterializer {
	suspend fun replace(snapshot: NyoraBackupSnapshot)

	companion object {
		val None = NyoraPortableMaterializer { }
	}
}

interface NyoraBackupObserverGate {
	suspend fun setSuppressed(suppressed: Boolean)
	suspend fun reconcile()

	companion object {
		val None = object : NyoraBackupObserverGate {
			override suspend fun setSuppressed(suppressed: Boolean) = Unit
			override suspend fun reconcile() = Unit
		}
	}
}

class NyoraRoomBackupRepository(
	private val database: MangaDatabase,
	private val availableSourceIds: suspend () -> Set<String>,
	private val initialSnapshot: suspend () -> NyoraBackupSnapshot = { EMPTY_SNAPSHOT },
	private val materializer: NyoraPortableMaterializer = NyoraPortableMaterializer.None,
	private val observerGate: NyoraBackupObserverGate = NyoraBackupObserverGate.None,
	private val now: () -> Instant = Instant::now,
	private val newArchiveId: () -> UUID = UUID::randomUUID,
	// Durably pairs the local rows with the portable ids they export under. Reading a snapshot and
	// previewing a restore must not write, so this runs only inside the two write transactions below,
	// where a failure rolls it back with everything else.
	private val persistIdentities: suspend () -> Unit = {},
) {

	suspend fun readSnapshot(): NyoraBackupSnapshot = database.getNyoraBackupLedgerDao().get()?.let {
		NyoraBackupCodec.decode(it.archive)
	} ?: initialSnapshot()

	suspend fun exportBytes(): ByteArray = database.withTransaction {
		persistIdentities()
		val live = initialSnapshot()
		val existing = database.getNyoraBackupLedgerDao().get()
		if (existing == null) {
			val bytes = NyoraBackupCodec.encode(live)
			database.getNyoraBackupLedgerDao().put(NyoraBackupLedgerEntity(archive = bytes, baseline = bytes))
			return@withTransaction bytes
		}
		val reconciliation = NyoraLiveBackupReconciler.reconcile(
			durable = NyoraBackupCodec.decode(existing.archive),
			baseline = NyoraBackupCodec.decode(existing.baseline),
			live = live,
			updatedAt = TIMESTAMP_FORMAT.format(now()),
			archiveId = newArchiveId().toString().lowercase(),
		)
		if (!reconciliation.changed) return@withTransaction existing.archive
		val archive = NyoraBackupCodec.encode(reconciliation.durable)
		val baseline = NyoraBackupCodec.encode(reconciliation.baseline)
		database.getNyoraBackupLedgerDao().put(NyoraBackupLedgerEntity(archive = archive, baseline = baseline))
		archive
	}

	suspend fun plan(
		bytes: ByteArray,
		mode: NyoraRestoreMode = NyoraRestoreMode.Merge,
	): NyoraRestorePlan {
		val incoming = NyoraBackupCodec.decode(bytes)
		return planAgainstCurrent(incoming, mode)
	}

	suspend fun apply(
		bytes: ByteArray,
		mode: NyoraRestoreMode = NyoraRestoreMode.Merge,
	): NyoraRestoreResult {
		val incoming = NyoraBackupCodec.decode(bytes)
		var appliedPlan: NyoraRestorePlan? = null
		observerGate.setSuppressed(true)
		try {
			database.withTransaction {
				persistIdentities()
				val plan = planAgainstCurrent(incoming, mode)
				materializer.replace(plan.preview)
				val baseline = NyoraBackupCodec.encode(initialSnapshot())
				database.getNyoraBackupLedgerDao().put(
					NyoraBackupLedgerEntity(archive = NyoraBackupCodec.encode(plan.preview), baseline = baseline),
				)
				appliedPlan = plan
			}
		} finally {
			observerGate.setSuppressed(false)
		}
		observerGate.reconcile()
		val plan = checkNotNull(appliedPlan)
		return NyoraRestoreResult(
			plan = plan,
			applied = plan.sections.mapValues { (_, count) -> count.additions + count.updates + count.deletions },
		)
	}

	private suspend fun planAgainstCurrent(
		incoming: NyoraBackupSnapshot,
		mode: NyoraRestoreMode,
	): NyoraRestorePlan {
		val live = initialSnapshot()
		val existing = database.getNyoraBackupLedgerDao().get()
		if (existing == null) return NyoraRestorePlanner.plan(live, incoming, mode, availableSourceIds())
		return NyoraCurrentRestorePlanner.plan(
			durable = NyoraBackupCodec.decode(existing.archive),
			baseline = NyoraBackupCodec.decode(existing.baseline),
			live = live,
			incoming = incoming,
			mode = mode,
			availableSourceIds = availableSourceIds(),
			observedAt = TIMESTAMP_FORMAT.format(now()),
			localArchiveId = newArchiveId().toString().lowercase(),
		)
	}

	private companion object {
		val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter
			.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
			.withZone(ZoneOffset.UTC)
		val EMPTY_SNAPSHOT = NyoraBackupSnapshot(
			archiveId = "00000000-0000-0000-0000-000000000000",
			createdAt = "1970-01-01T00:00:00.000Z",
			appVersion = "android",
			platform = "android",
		)
	}
}
