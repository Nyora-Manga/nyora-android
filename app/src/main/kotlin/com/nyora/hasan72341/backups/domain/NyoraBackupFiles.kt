package com.nyora.hasan72341.backups.domain

import com.nyora.hasan72341.backups.data.NyoraRestoreMode
import java.util.Locale

object NyoraBackupFiles {
	const val EXTENSION = ".nyorabk"
	const val MIME_TYPE = "application/vnd.nyora.backup"

	fun fileName(appName: String, timestamp: String): String =
		appName.replace(' ', '_').lowercase(Locale.ROOT) + "_" + timestamp + EXTENSION

	fun isSupported(fileName: String?): Boolean =
		fileName?.lowercase(Locale.ROOT)?.endsWith(EXTENSION) == true

	fun requireSupported(fileName: String?) {
		require(isSupported(fileName)) { "Only .nyorabk version-1 archives are supported" }
	}
}

data class NyoraRestoreRequest(
	val mode: NyoraRestoreMode = NyoraRestoreMode.Merge,
	val replaceConfirmed: Boolean = false,
) {
	init {
		require(mode != NyoraRestoreMode.Replace || replaceConfirmed) {
			"Replace restore requires explicit confirmation"
		}
	}
}
