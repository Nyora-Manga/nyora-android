package com.nyora.hasan72341.backups.domain

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.annotation.VisibleForTesting
import com.google.common.io.ByteStreams
import com.nyora.hasan72341.backups.data.BackupRepository
import com.nyora.hasan72341.core.db.MangaDatabase
import com.nyora.hasan72341.core.prefs.AppSettings
import com.nyora.hasan72341.explore.data.MangaSourcesRepository
import com.nyora.hasan72341.js.NyoraJsSourcesManager
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Provider

class AppBackupAgent : BackupAgent() {
	@Inject
	lateinit var nyoraJsSourcesManager: Provider<NyoraJsSourcesManager>

	override fun onBackup(
		oldState: ParcelFileDescriptor?,
		data: BackupDataOutput?,
		newState: ParcelFileDescriptor?
	) = Unit

	override fun onRestore(
		data: BackupDataInput?,
		appVersionCode: Int,
		newState: ParcelFileDescriptor?
	) = Unit

	override fun onFullBackup(data: FullBackupDataOutput) {
		super.onFullBackup(data)

		val file = createBackupFile(
			this,
			createRepository(applicationContext),
		)
		try {
			fullBackupFile(file, data)
		} finally {
			file.delete()
		}
	}

	override fun onRestoreFile(
		data: ParcelFileDescriptor,
		size: Long,
		destination: File?,
		type: Int,
		mode: Long,
		mtime: Long
	) {
		if (NyoraBackupFiles.isSupported(destination?.name)) {
			restoreBackupFile(
				data.fileDescriptor,
				size,
				createRepository(applicationContext),
			)
			destination?.delete()
		} else {
			super.onRestoreFile(data, size, destination, type, mode, mtime)
		}
	}

	@VisibleForTesting
	fun createBackupFile(context: Context, repository: BackupRepository): File {
		val file = BackupUtils.createTempFile(context)
		file.outputStream().use { output ->
			runBlocking {
				repository.createBackup(output, null)
			}
		}
		return file
	}

	@VisibleForTesting
	fun restoreBackupFile(fd: FileDescriptor, size: Long, repository: BackupRepository) {
		ByteStreams.limit(FileInputStream(fd), size).use { input ->
			runBlocking {
				repository.restoreBackup(input)
			}
		}
	}

	private fun createRepository(context: Context): BackupRepository {
		val database = MangaDatabase(context)
		return BackupRepository(
			database = database,
			backupObserver = BackupObserver(context),
			mangaSourcesRepository = MangaSourcesRepository(
				context = context,
				db = database,
				settings = AppSettings(context),
				nyoraJsSourcesManager = nyoraJsSourcesManager.get(),
			),
		)
	}
}
