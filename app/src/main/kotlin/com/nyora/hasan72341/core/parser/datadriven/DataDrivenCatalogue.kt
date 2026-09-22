package com.nyora.hasan72341.core.parser.datadriven

import android.content.Context
import androidx.annotation.WorkerThread
import com.nyora.hasan72341.core.model.DataDrivenMangaSource
import com.nyora.hasan72341.core.util.ext.printStackTraceDebug
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the current data-driven catalogue: the bundled asset at first run, the cached copy of the
 * last refresh afterwards. [replace] validates a freshly fetched catalogue with the same parser
 * before it swaps anything, so a bad download leaves the previous snapshot untouched.
 *
 * The cache is bound to the bundled asset it was written over (see [CatalogueCache]): after an app
 * update the new bundle wins over a cache the previous build downloaded, and a refresh whose
 * catalogue hash matches the current snapshot changes nothing.
 *
 * The internal constructor is the seam the unit tests use; Hilt builds the instance through the
 * [Context] one.
 */
@Singleton
class DataDrivenCatalogue internal constructor(
	/** Reads the bundled `catalogue.json`; a build invariant, so a failure here is fatal. */
	private val bundledAsset: () -> ByteArray,
	private val cache: CatalogueCache,
	private val reportError: (Throwable) -> Unit,
) {

	@Inject
	constructor(@ApplicationContext context: Context) : this(
		bundledAsset = { context.assets.open(ASSET_PATH).use { it.readBytes() } },
		cache = CatalogueCache(context.filesDir),
		reportError = { it.printStackTraceDebug(TAG) },
	)

	/** Null until the first snapshot is needed: parsing the catalogue costs too much to do in a constructor. */
	@Volatile
	private var snapshot: Snapshot? = null

	init {
		instance = this
	}

	/** The browsable sources of the current snapshot, in catalogue order. */
	val sources: List<DataDrivenMangaSource>
		get() = currentSnapshot().entries

	/** Bumped by every successful [replace] that swapped the catalogue, so callers can tell a swapped catalogue from a stale one. */
	val revision: Long
		get() = currentSnapshot().revision

	/** The generator's content hash of the catalogue the current snapshot was parsed from, when it carries one. */
	val hash: String?
		get() = currentSnapshot().hash

	/** Resolve a persisted `data:<id>` identity; the id half is matched case-insensitively. */
	fun find(name: String): DataDrivenMangaSource? = currentSnapshot().byName[name.lowercase(Locale.ROOT)]

	/**
	 * Parse the first snapshot now, so the first source lookup does not pay for 400 KB of JSON.
	 * [com.nyora.hasan72341.core.BaseApp] calls this off the main thread during startup.
	 */
	@WorkerThread
	fun warmUp() {
		currentSnapshot()
	}

	/**
	 * Validate [json] and, only if it parses, publish it as the current catalogue and cache it.
	 * A catalogue whose `hash` equals the current snapshot's is the one already serving, so it is
	 * neither re-parsed, cached again nor counted as a new revision.
	 *
	 * @return the number of browsable sources the current catalogue contains.
	 * @throws IllegalArgumentException when [json] is not a usable catalogue.
	 */
	@Synchronized
	fun replace(json: String): Int {
		val document = DataDrivenCatalogueParser.parseDocument(json)
		val hash = DataDrivenCatalogueParser.hashOf(document)
		val current = currentSnapshot()
		if (hash != null && hash == current.hash) {
			return current.entries.size
		}
		val entries = DataDrivenCatalogueParser.parse(document)
		require(entries.isNotEmpty()) { "Data-driven catalogue contains no supported sources" }
		snapshot = Snapshot(entries, current.revision + 1, hash, current.bundledDigest)
		// A cache write that fails only costs the next cold start a read of the bundled asset.
		runCatching { cache.write(json, current.bundledDigest) }.onFailure(reportError)
		return entries.size
	}

	private fun currentSnapshot(): Snapshot = snapshot ?: synchronized(this) {
		snapshot ?: loadInitialSnapshot().also { snapshot = it }
	}

	/**
	 * The cache when it was written over this build's bundled catalogue and still parses, else the
	 * bundled catalogue itself. A cache that fails either test is deleted so it is not retried on
	 * every cold start.
	 */
	private fun loadInitialSnapshot(): Snapshot {
		val bundled = bundledAsset()
		val bundledDigest = sha256Hex(bundled)
		val cached = runCatching { cache.readBoundTo(bundledDigest) }.onFailure(reportError).getOrNull()
		if (cached != null) {
			runCatching { parseSnapshot(cached, bundledDigest) }
				.onFailure {
					reportError(it)
					runCatching { cache.delete() }.onFailure(reportError)
				}
				.getOrNull()
				?.let { return it }
		}
		// The bundled asset is a build invariant: failing loudly here beats browsing no sources.
		return parseSnapshot(String(bundled, Charsets.UTF_8), bundledDigest)
	}

	private fun parseSnapshot(json: String, bundledDigest: String): Snapshot {
		val document = DataDrivenCatalogueParser.parseDocument(json)
		val entries = DataDrivenCatalogueParser.parse(document)
		require(entries.isNotEmpty()) { "Data-driven catalogue contains no supported sources" }
		return Snapshot(entries, revision = 0L, hash = DataDrivenCatalogueParser.hashOf(document), bundledDigest = bundledDigest)
	}

	private class Snapshot(
		val entries: List<DataDrivenMangaSource>,
		val revision: Long,
		val hash: String?,
		/** Digest of the bundled asset this process runs with; every cache write is recorded against it. */
		val bundledDigest: String,
	) {

		/** Keyed by the canonical lower-cased `data:` name the entries already expose. */
		val byName: Map<String, DataDrivenMangaSource> = entries.associateBy { it.name }
	}

	companion object {

		private const val TAG = "DataDrivenCatalogue"
		private const val ASSET_PATH = "nyora/catalogue.json"

		/**
		 * The live singleton, for the few call sites that resolve a persisted source name without
		 * an injector to hand, chiefly the `MangaSource(name)` factory. Set when Hilt builds it.
		 */
		@Volatile
		var instance: DataDrivenCatalogue? = null
			private set
	}
}

/**
 * Resolve a persisted `data:` identity through the renames and retirements that happened after the
 * row was written, so history, favourites and downloads stored under an old spelling still reach
 * their source. Returns null when no row of the current catalogue claims [name].
 */
internal fun DataDrivenCatalogue.findCanonical(name: String): DataDrivenMangaSource? {
	val lowerName = name.lowercase(Locale.ROOT)
	LEGACY_SOURCE_RENAMES[lowerName]?.let { renamed -> find(renamed)?.let { return it } }
	val id = lowerName.removePrefix(DataDrivenMangaSource.PREFIX)
	return find(DataDrivenMangaSource.PREFIX + canonicalDataSourceId(id))
}
