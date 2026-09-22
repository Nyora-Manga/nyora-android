package com.nyora.hasan72341.core.parser.datadriven

import android.content.Context
import androidx.annotation.WorkerThread
import com.nyora.hasan72341.core.model.DataDrivenMangaSource
import com.nyora.hasan72341.core.util.ext.printStackTraceDebug
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the current data-driven catalogue: the bundled asset at first run, the cached copy of the
 * last refresh afterwards. [replace] validates a freshly fetched catalogue with the same parser
 * before it swaps anything, so a bad download leaves the previous snapshot untouched.
 */
@Singleton
class DataDrivenCatalogue @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	private val cacheFile = File(context.filesDir, CACHE_FILE_NAME)

	/** Null until the first snapshot is needed: parsing the catalogue costs too much to do in a constructor. */
	@Volatile
	private var snapshot: Snapshot? = null

	init {
		instance = this
	}

	/** The browsable sources of the current snapshot, in catalogue order. */
	val sources: List<DataDrivenMangaSource>
		get() = currentSnapshot().entries

	/** Bumped by every successful [replace], so callers can tell a swapped catalogue from a stale one. */
	val revision: Long
		get() = currentSnapshot().revision

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
	 *
	 * @return the number of browsable sources it contains.
	 * @throws IllegalArgumentException when [json] is not a usable catalogue.
	 */
	@Synchronized
	fun replace(json: String): Int {
		val entries = DataDrivenCatalogueParser.parse(json)
		require(entries.isNotEmpty()) { "Data-driven catalogue contains no supported sources" }
		// Reading the previous revision must not parse a snapshot this call is about to discard.
		snapshot = Snapshot(entries, (snapshot?.revision ?: 0L) + 1)
		// A cache write that fails only costs the next cold start a read of the bundled asset.
		runCatching { writeCache(json) }.onFailure { it.printStackTraceDebug(TAG) }
		return entries.size
	}

	private fun currentSnapshot(): Snapshot = snapshot ?: synchronized(this) {
		snapshot ?: loadInitialSnapshot().also { snapshot = it }
	}

	private fun loadInitialSnapshot(): Snapshot {
		val cached = runCatching {
			if (cacheFile.isFile) DataDrivenCatalogueParser.parse(cacheFile.readText()) else null
		}.onFailure { it.printStackTraceDebug(TAG) }.getOrNull()
		if (!cached.isNullOrEmpty()) {
			return Snapshot(cached, revision = 0L)
		}
		// The bundled asset is a build invariant: failing loudly here beats browsing no sources.
		val bundled = context.assets.open(ASSET_PATH).use { it.reader(Charsets.UTF_8).readText() }
		return Snapshot(DataDrivenCatalogueParser.parse(bundled), revision = 0L)
	}

	private fun writeCache(json: String) {
		val temp = File(context.filesDir, "$CACHE_FILE_NAME.tmp")
		temp.writeText(json)
		if (!temp.renameTo(cacheFile)) {
			cacheFile.delete()
			check(temp.renameTo(cacheFile)) { "Could not replace the data-driven catalogue cache" }
		}
	}

	private class Snapshot(
		val entries: List<DataDrivenMangaSource>,
		val revision: Long,
	) {

		/** Keyed by the canonical lower-cased `data:` name the entries already expose. */
		val byName: Map<String, DataDrivenMangaSource> = entries.associateBy { it.name }
	}

	companion object {

		private const val TAG = "DataDrivenCatalogue"
		private const val ASSET_PATH = "nyora/catalogue.json"
		private const val CACHE_FILE_NAME = "datadriven-catalogue.json"

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
