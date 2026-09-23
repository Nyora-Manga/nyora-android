package com.nyora.hasan72341.backups.ui.restore

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.annotation.CheckResult
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import com.nyora.hasan72341.R
import com.nyora.hasan72341.backups.data.BackupRepository
import com.nyora.hasan72341.backups.data.NyoraRestoreMode
import com.nyora.hasan72341.backups.domain.NyoraRestoreRequest
import com.nyora.hasan72341.backups.domain.NyoraRestorePayloadStore
import com.nyora.hasan72341.backups.ui.BaseBackupRestoreService
import com.nyora.hasan72341.core.nav.AppRouter
import com.nyora.hasan72341.core.util.ext.checkNotificationPermission
import com.nyora.hasan72341.core.util.CompositeResult
import com.nyora.hasan72341.core.util.ext.powerManager
import com.nyora.hasan72341.core.util.ext.printStackTraceDebug
import com.nyora.hasan72341.core.util.ext.withPartialWakeLock
import com.nyora.hasan72341.core.util.progress.Progress
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.io.File
import android.net.Uri
import javax.inject.Inject
import androidx.appcompat.R as appcompatR

@AndroidEntryPoint
@SuppressLint("InlinedApi")
class RestoreService : BaseBackupRestoreService() {

	override val notificationTag = TAG
	override val isRestoreService = true

	@Inject
	lateinit var repository: BackupRepository

	override suspend fun IntentJobContext.processIntent(intent: Intent) {
		val notification = buildNotification(Progress.INDETERMINATE)
		setForeground(
			FOREGROUND_NOTIFICATION_ID,
			notification,
			ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
		)
		val payload = intent.getStringExtra(AppRouter.KEY_DATA)?.let(::File) ?: throw FileNotFoundException()
		val mode = requireNotNull(NyoraRestoreMode.parse(intent.getStringExtra(EXTRA_MODE)))
		val request = NyoraRestoreRequest(mode, intent.getBooleanExtra(EXTRA_REPLACE_CONFIRMED, false))
		powerManager.withPartialWakeLock(TAG) {
			val progress = MutableStateFlow(Progress.INDETERMINATE)
			val progressUpdateJob = if (checkNotificationPermission(CHANNEL_ID)) {
				launch {
					progress.collect {
						notificationManager.notify(FOREGROUND_NOTIFICATION_ID, buildNotification(it))
					}
				}
			} else {
				null
			}
			val payloadStore = NyoraRestorePayloadStore(File(cacheDir, RestoreViewModel.RESTORE_PAYLOAD_DIRECTORY))
			payloadStore.consume(payload).inputStream().use { input ->
				repository.restoreBackup(input, request, progress)
			}
			progressUpdateJob?.cancelAndJoin()
			showResultNotification(Uri.fromFile(payload), CompositeResult.success())
		}
	}

	private fun IntentJobContext.buildNotification(progress: Progress): Notification {
		return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setContentTitle(getString(R.string.restoring_backup))
			.setPriority(NotificationCompat.PRIORITY_HIGH)
			.setDefaults(0)
			.setSilent(true)
			.setOngoing(true)
			.setProgress(
				progress.total.coerceAtLeast(0),
				progress.progress.coerceAtLeast(0),
				progress.isIndeterminate,
			)
			.setContentText(
				if (progress.isIndeterminate) {
					getString(R.string.processing_)
				} else {
					getString(R.string.fraction_pattern, progress.progress, progress.total)
				},
			)
			.setSmallIcon(android.R.drawable.stat_sys_upload)
			.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
			.setCategory(NotificationCompat.CATEGORY_PROGRESS)
			.addAction(
				appcompatR.drawable.abc_ic_clear_material,
				applicationContext.getString(android.R.string.cancel),
				getCancelIntent(),
			).build()
	}

	companion object {

		private const val TAG = "RESTORE"
		private const val FOREGROUND_NOTIFICATION_ID = 39
		private const val EXTRA_MODE = "nyora_restore_mode"
		private const val EXTRA_REPLACE_CONFIRMED = "nyora_restore_replace_confirmed"

		@CheckResult
		fun start(context: Context, payload: File, request: NyoraRestoreRequest = NyoraRestoreRequest()): Boolean = try {
			val intent = Intent(context, RestoreService::class.java)
			intent.putExtra(AppRouter.KEY_DATA, payload.absolutePath)
			intent.putExtra(EXTRA_MODE, request.mode.name.lowercase())
			intent.putExtra(EXTRA_REPLACE_CONFIRMED, request.replaceConfirmed)
			ContextCompat.startForegroundService(context, intent)
			true
		} catch (e: Exception) {
			e.printStackTraceDebug("RestoreService::start")
			false
		}
	}
}
