package com.nyora.hasan72341.backups.data

import com.nyora.hasan72341.backups.domain.NyoraRestoreRequest
import com.nyora.hasan72341.backups.domain.BackupObserver
import com.nyora.hasan72341.BuildConfig
import com.nyora.hasan72341.core.db.MangaDatabase
import com.nyora.hasan72341.core.util.progress.Progress
import com.nyora.hasan72341.explore.data.MangaSourcesRepository
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.FlowCollector
import com.nyora.hasan72341.sync.supabase.SupabaseSyncBarrier

@Singleton
class BackupRepository @Inject constructor(
	database: MangaDatabase,
	mangaSourcesRepository: MangaSourcesRepository,
	backupObserver: BackupObserver,
	syncBarrier: SupabaseSyncBarrier,
	cloudSyncReconciler: WorkManagerNyoraCloudSyncReconciler,
) {
	private val restoreSyncCoordinator = NyoraRestoreSyncCoordinator(syncBarrier, cloudSyncReconciler)
	private val projector = NyoraRoomBackupProjector(database, BuildConfig.VERSION_NAME)
	private val delegate = NyoraRoomBackupRepository(
		database = database,
		initialSnapshot = projector::snapshot,
		materializer = NyoraRoomPortableMaterializer(database),
		observerGate = NyoraRoomBackupObserverGate(database, backupObserver),
		availableSourceIds = {
			mangaSourcesRepository.allMangaSources.mapTo(linkedSetOf()) { source ->
				"data:${source.jsId.lowercase(Locale.ROOT).replace('_', '-')}"
			}
		},
	)

	suspend fun createBackup(output: OutputStream, progress: FlowCollector<Progress>? = null) {
		progress?.emit(Progress.INDETERMINATE)
		output.write(delegate.exportBytes())
		output.flush()
		progress?.emit(Progress(1, 1))
	}

	suspend fun planBackup(
		input: InputStream,
		mode: NyoraRestoreMode = NyoraRestoreMode.Merge,
	): NyoraRestorePlan = delegate.plan(NyoraBackupStreams.read(input), mode)

	suspend fun restoreBackup(
		input: InputStream,
		request: NyoraRestoreRequest = NyoraRestoreRequest(),
		progress: FlowCollector<Progress>? = null,
	): NyoraRestoreResult {
		progress?.emit(Progress.INDETERMINATE)
		val bytes = NyoraBackupStreams.read(input)
		val result = restoreSyncCoordinator.restore { delegate.apply(bytes, request.mode) }
		progress?.emit(Progress(1, 1))
		return result
	}
}
