package com.nyora.hasan72341.backups.data

/** Builds a restore plan from the durable archive after observing current Room state. */
object NyoraCurrentRestorePlanner {
	fun plan(
		durable: NyoraBackupSnapshot,
		baseline: NyoraBackupSnapshot,
		live: NyoraBackupSnapshot,
		incoming: NyoraBackupSnapshot,
		mode: NyoraRestoreMode,
		availableSourceIds: Set<String>,
		observedAt: String,
		localArchiveId: String,
	): NyoraRestorePlan {
		val current = NyoraLiveBackupReconciler.reconcile(
			durable = durable,
			baseline = baseline,
			live = live,
			updatedAt = observedAt,
			archiveId = localArchiveId,
		).durable
		return NyoraRestorePlanner.plan(current, incoming, mode, availableSourceIds)
	}
}
