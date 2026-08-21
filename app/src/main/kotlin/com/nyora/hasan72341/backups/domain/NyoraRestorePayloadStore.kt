package com.nyora.hasan72341.backups.domain

import com.nyora.hasan72341.backups.data.NyoraBackupStreams
import java.io.File

/** Owns the immutable, app-private payload that is previewed and later applied. */
class NyoraRestorePayloadStore(private val directory: File) {
	fun prepare(bytes: ByteArray): File {
		require(directory.mkdirs() || directory.isDirectory) { "Cannot create restore payload directory" }
		return File.createTempFile("restore-", ".nyorabk", directory).also { file ->
			file.outputStream().use { it.write(bytes) }
		}
	}

	fun consume(payload: File): ByteArray {
		val root = directory.canonicalFile
		val candidate = payload.canonicalFile
		require(candidate.parentFile == root) { "Restore payload is outside the private staging directory" }
		return try {
			candidate.inputStream().use(NyoraBackupStreams::read)
		} finally {
			candidate.delete()
		}
	}
}
