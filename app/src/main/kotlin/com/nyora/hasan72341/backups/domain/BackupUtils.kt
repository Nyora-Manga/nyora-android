package com.nyora.hasan72341.backups.domain

import android.content.Context
import androidx.annotation.CheckResult
import com.nyora.hasan72341.R
import com.nyora.hasan72341.core.util.ext.printStackTraceDebug
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date

object BackupUtils {

	private const val DIR_BACKUPS = "backups"
	private val dateTimeFormat = SimpleDateFormat("yyyyMMdd-HHmm")

	@CheckResult
	fun createTempFile(context: Context): File {
		val dir = getAppBackupDir(context)
		dir.mkdirs()
		return File(dir, generateFileName(context))
	}

	fun getAppBackupDir(context: Context) = context.run {
		getExternalFilesDir(DIR_BACKUPS) ?: File(filesDir, DIR_BACKUPS)
	}

	fun parseBackupDateTime(fileName: String): Date? = try {
		dateTimeFormat.parse(fileName.substringAfterLast('_').substringBefore('.'))
	} catch (e: ParseException) {
		e.printStackTraceDebug("BackupUtils::parseBackupDateTime")
		null
	}

	fun generateFileName(context: Context) = buildString {
		append(NyoraBackupFiles.fileName(context.getString(R.string.app_name), dateTimeFormat.format(Date())))
	}
}
