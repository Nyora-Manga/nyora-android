package com.nyora.hasan72341.backups.domain

import com.nyora.hasan72341.backups.data.NyoraBackupStreams
import java.io.File

/** Owns the immutable, app-private payload that is previewed and later applied. */
class NyoraRestorePayloadStore(
	private val directory: File,
	private val nowMillis: () -> Long = System::currentTimeMillis,
	private val staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS,
	private val maxRetainedPayloads: Int = DEFAULT_MAX_RETAINED_PAYLOADS,
) {
	init {
		require(staleAfterMillis > 0)
		require(maxRetainedPayloads > 0)
	}

	fun prepare(bytes: ByteArray): File {
		require(directory.mkdirs() || directory.isDirectory) { "Cannot create restore payload directory" }
		return File.createTempFile(PREFIX, SUFFIX, directory).also { file ->
			file.outputStream().use { it.write(bytes) }
			file.setLastModified(nowMillis())
			sweep(protected = file)
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

	fun discard(payload: File): Boolean {
		val candidate = checked(payload)
		return !candidate.exists() || candidate.delete()
	}

	private fun sweep(protected: File) {
		val now = nowMillis()
		val managed = directory.listFiles().orEmpty()
			.filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
		managed.filter { it != protected && now - it.lastModified() >= staleAfterMillis }.forEach(File::delete)
		directory.listFiles().orEmpty()
			.filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
			.sortedWith(compareByDescending<File> { it == protected }.thenByDescending(File::lastModified))
			.drop(maxRetainedPayloads)
			.forEach(File::delete)
	}

	private fun checked(payload: File): File {
		val root = directory.canonicalFile
		val candidate = payload.canonicalFile
		require(candidate.parentFile == root) { "Restore payload is outside the private staging directory" }
		return candidate
	}

	private companion object {
		const val PREFIX = "restore-"
		const val SUFFIX = ".nyorabk"
		const val DEFAULT_STALE_AFTER_MILLIS = 24L * 60L * 60L * 1_000L
		const val DEFAULT_MAX_RETAINED_PAYLOADS = 4
	}
}

/** Transfers payload ownership exactly once, deleting it whenever start is rejected or cancelled. */
class NyoraRestorePayloadOwner(
	private val store: NyoraRestorePayloadStore,
	payload: File,
) {
	private var owned: File? = payload

	val payload: File?
		get() = owned

	fun handOff(start: (File) -> Boolean): Boolean {
		val candidate = owned ?: return false
		return if (start(candidate)) {
			owned = null
			true
		} else {
			discard()
			false
		}
	}

	fun discard() {
		owned?.let(store::discard)
		owned = null
	}
}
