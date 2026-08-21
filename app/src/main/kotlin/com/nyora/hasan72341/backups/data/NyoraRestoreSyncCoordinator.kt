package com.nyora.hasan72341.backups.data

import android.content.Context
import com.nyora.hasan72341.sync.supabase.SupabaseSyncBarrier
import com.nyora.hasan72341.sync.supabase.SupabaseSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

fun interface NyoraCloudSyncReconciler {
	suspend fun reconcile()
}

@Singleton
class WorkManagerNyoraCloudSyncReconciler @Inject constructor(
	@ApplicationContext private val context: Context,
) : NyoraCloudSyncReconciler {
	override suspend fun reconcile() = SupabaseSyncWorker.enqueue(context)
}

class NyoraRestoreSyncCoordinator(
	private val barrier: SupabaseSyncBarrier,
	private val reconcileCloud: suspend () -> Unit,
) {
	constructor(barrier: SupabaseSyncBarrier, reconciler: NyoraCloudSyncReconciler) : this(
		barrier,
		reconciler::reconcile,
	)

	suspend fun <T> restore(block: suspend () -> T): T {
		val result = barrier.withRestore(block)
		reconcileCloud()
		return result
	}
}
