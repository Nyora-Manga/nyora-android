package com.nyora.hasan72341.backups.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

object NyoraBackupStreams {
	private const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024

	fun read(input: InputStream): ByteArray = ByteArrayOutputStream().use { output ->
		val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
		var total = 0
		while (true) {
			val count = input.read(buffer)
			if (count < 0) break
			total += count
			if (total > MAX_ARCHIVE_BYTES) {
				throw NyoraBackupCodec.InvalidBackupException("Archive exceeds the configured compressed size limit")
			}
			output.write(buffer, 0, count)
		}
		output.toByteArray()
	}
}
