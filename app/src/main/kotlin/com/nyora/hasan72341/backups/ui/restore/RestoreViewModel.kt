package com.nyora.hasan72341.backups.ui.restore

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.nyora.hasan72341.backups.data.BackupRepository
import com.nyora.hasan72341.backups.data.NyoraRestoreMode
import com.nyora.hasan72341.backups.data.NyoraRestorePlan
import com.nyora.hasan72341.backups.data.NyoraBackupStreams
import com.nyora.hasan72341.backups.domain.NyoraBackupFiles
import com.nyora.hasan72341.backups.domain.NyoraRestoreRequest
import com.nyora.hasan72341.core.nav.AppRouter
import com.nyora.hasan72341.core.ui.BaseViewModel
import com.nyora.hasan72341.core.util.ext.getFileDisplayName
import com.nyora.hasan72341.core.util.ext.toUriOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.time.Instant
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

@HiltViewModel
class RestoreViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val repository: BackupRepository,
	@ApplicationContext context: Context,
) : BaseViewModel() {
	val uri = savedStateHandle.get<String>(AppRouter.KEY_FILE)?.toUriOrNull()
	val plan = MutableStateFlow<NyoraRestorePlan?>(null)
	val mode = MutableStateFlow(NyoraRestoreMode.Merge)
	val backupDate = MutableStateFlow<Date?>(null)

	private val contentResolver = context.contentResolver
	private var archiveBytes: ByteArray? = null
	private var replaceConfirmed = false

	init {
		launchLoadingJob(Dispatchers.IO) {
			val source = uri ?: throw FileNotFoundException()
			NyoraBackupFiles.requireSupported(contentResolver.getFileDisplayName(source))
			archiveBytes = checkNotNull(contentResolver.openInputStream(source)).use(NyoraBackupStreams::read)
			replan(NyoraRestoreMode.Merge)
		}
	}

	fun selectMerge() {
		replaceConfirmed = false
		launchLoadingJob(Dispatchers.IO) { replan(NyoraRestoreMode.Merge) }
	}

	fun selectReplaceConfirmed() {
		replaceConfirmed = true
		launchLoadingJob(Dispatchers.IO) { replan(NyoraRestoreMode.Replace) }
	}

	fun restoreRequest(): NyoraRestoreRequest = NyoraRestoreRequest(mode.value, replaceConfirmed)

	private suspend fun replan(selectedMode: NyoraRestoreMode) {
		val bytes = checkNotNull(archiveBytes)
		val preview = repository.planBackup(ByteArrayInputStream(bytes), selectedMode)
		mode.value = selectedMode
		plan.value = preview
		backupDate.value = Date.from(Instant.parse(preview.createdAt))
	}
}
