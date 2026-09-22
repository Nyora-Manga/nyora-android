package com.nyora.hasan72341.core.parser.datadriven

import java.io.File
import java.security.MessageDigest

/**
 * The on-disk copy of the last refreshed catalogue, bound to the bundled catalogue it was written
 * over.
 *
 * A refresh cache outlives an app update, and the update's bundled catalogue may carry rows and
 * repairs the cached download predates. The digest of the bundled asset is therefore recorded next
 * to the cache, and a cache recorded against any other bundle is thrown away, so every build starts
 * from its own catalogue and only moves forward from there.
 */
internal class CatalogueCache(dir: File) {

	private val file = File(dir, CACHE_FILE_NAME)
	private val temp = File(dir, "$CACHE_FILE_NAME.tmp")
	private val binding = File(dir, BINDING_FILE_NAME)

	/** The digest the cache was recorded against, or null when there is no readable record. */
	val recordedBundledDigest: String?
		get() = runCatching { binding.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

	/**
	 * The cached catalogue when it was written over the bundle whose digest is [bundledDigest];
	 * a cache written over any other bundle is deleted and null is returned.
	 */
	fun readBoundTo(bundledDigest: String): String? {
		if (!file.isFile) return null
		if (!isCatalogueCacheBoundTo(recordedBundledDigest, bundledDigest)) {
			delete()
			return null
		}
		return file.readText()
	}

	/** Atomically replace the cache with [json], recorded against [bundledDigest]. */
	fun write(json: String, bundledDigest: String) {
		temp.writeText(json)
		if (!temp.renameTo(file)) {
			file.delete()
			check(temp.renameTo(file)) { "Could not replace the data-driven catalogue cache" }
		}
		binding.writeText(bundledDigest)
	}

	fun delete() {
		file.delete()
		binding.delete()
		temp.delete()
	}

	companion object {

		private const val CACHE_FILE_NAME = "datadriven-catalogue.json"
		private const val BINDING_FILE_NAME = "$CACHE_FILE_NAME.bundled"
	}
}

/**
 * Whether a cache recorded against [recordedDigest] may serve in place of the bundle whose digest
 * is [bundledDigest]: only a cache that was written over this very bundle, so an app update always
 * starts from its own bundled catalogue.
 */
internal fun isCatalogueCacheBoundTo(recordedDigest: String?, bundledDigest: String): Boolean =
	!recordedDigest.isNullOrEmpty() && recordedDigest.equals(bundledDigest, ignoreCase = true)

/** Lower-case hex SHA-256 of [bytes], the digest the cache binding is keyed by. */
internal fun sha256Hex(bytes: ByteArray): String =
	MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
		(byte.toInt() and 0xff).toString(16).padStart(2, '0')
	}
