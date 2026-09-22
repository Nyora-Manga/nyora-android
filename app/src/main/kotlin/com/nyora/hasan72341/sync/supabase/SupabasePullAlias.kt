package com.nyora.hasan72341.sync.supabase

import com.nyora.hasan72341.backups.data.NyoraSourceIdentity
import com.nyora.hasan72341.core.db.migrations.upgradedStoredSourceName
import com.nyora.hasan72341.core.model.DataDrivenMangaSource
import com.nyora.hasan72341.core.parser.datadriven.stableChapterId
import com.nyora.hasan72341.core.parser.datadriven.stableMangaId
import org.json.JSONArray

/**
 * Pure identity helpers for the cloud pull.
 *
 * A data-catalogue manga id is hashed from (source token, url) on every Nyora client, so the cloud
 * copy of a row is only ever a carrier for that identity: a row an older client wrote under a
 * server-generated id, or under a source spelling the catalogue has since renamed, has to be
 * re-hashed before anything local is keyed by it. Push needs none of this because local ids are
 * canonical already.
 */

/** Delimiter of the `<source>|chapter|<url>` chapter id the shipped builds' native adapters wrote. */
private const val NATIVE_CHAPTER_ID_DELIMITER = "|chapter|"

/**
 * The canonical `data:<catalogue-id>` identity a synced `source_ref` resolves to, or null when the
 * row belongs to a source whose ids this client does not hash (`LOCAL`, `UNKNOWN`, a parser row).
 *
 * Every spelling a shipped client pushed is accepted: the JSON wrapper, `data:`, the `DD_` prefix
 * of v2.6 to v2.7.3 and the bare `JS_<ENUM>` of v2.1.6, through the same upgrade the database
 * migration applies, and the result must be an identity the backup contract accepts.
 */
internal fun canonicalPulledSourceId(storedSourceRef: String): String? {
	val upgraded = upgradedStoredSourceName(storedSourceRef) ?: return null
	return NyoraSourceIdentity.canonicalize(upgraded)
}

/**
 * The local Room id a pulled cloud row settles on, which is [remoteId] itself for every source this
 * client does not hash ids for.
 */
internal fun pulledMangaIdAlias(remoteId: String, storedSourceRef: String, mangaUrl: String): String {
	val sourceId = canonicalPulledSourceId(storedSourceRef) ?: return remoteId
	return stableMangaId(sourceId.removePrefix(DataDrivenMangaSource.PREFIX), mangaUrl)
}

/**
 * What a pull knows about the remote manga rows before it applies any dependent row.
 *
 * @property aliases remote id -> local id, for the rows whose local id differs.
 * @property sourceIds remote id -> canonical `data:` source, for every data-source row, aliased
 * or not, so a dependent row can tell whether its chapter ids are ones this client hashes.
 */
internal class PulledMangaIdentities(
	val aliases: Map<String, String>,
	val sourceIds: Map<String, String>,
)

/**
 * The remote id -> local id map a pull keys every dependent row through, and the source of each
 * data-source row.
 *
 * Built from the complete remote parent set rather than the rows a cutoff selected: a foreign client
 * can update only a history, favourite, bookmark or category row, and that row still has to land
 * under the manga id this device holds. Rows that already carry their canonical id are left out of
 * the alias map, and a row missing a column is reported and skipped so one malformed parent cannot
 * cost the whole map.
 */
internal fun pulledMangaIdentities(
	rows: JSONArray,
	onRowError: (Int, Throwable) -> Unit = { _, _ -> },
): PulledMangaIdentities {
	val aliases = HashMap<String, String>()
	val sourceIds = HashMap<String, String>()
	for (index in 0 until rows.length()) {
		try {
			val row = rows.getJSONObject(index)
			val remoteId = row.getString("id")
			val sourceId = canonicalPulledSourceId(row.getString("source_ref"))
			if (sourceId != null) {
				sourceIds[remoteId] = sourceId
				val localId = stableMangaId(sourceId.removePrefix(DataDrivenMangaSource.PREFIX), row.getString("url"))
				if (localId != remoteId) aliases[remoteId] = localId
			}
		} catch (error: Exception) {
			onRowError(index, error)
		}
	}
	return PulledMangaIdentities(aliases, sourceIds)
}

/** See [pulledMangaIdentities]; the alias half alone. */
internal fun pulledMangaIdAliasMap(
	rows: JSONArray,
	onRowError: (Int, Throwable) -> Unit = { _, _ -> },
): Map<String, String> = pulledMangaIdentities(rows, onRowError).aliases

/**
 * The chapter url a chapter id written by a shipped build carries, or null when [remoteChapterId]
 * is not such an id.
 *
 * Every id this client and the other current clients write is the legacy hash, a signed 64-bit
 * decimal. The v2.6 to v2.7.3 data-driven adapter stored the engine's id, which the engines set to
 * the chapter href, and its native adapters stored `<source>|chapter|<url>`. A numeric id is left
 * alone: it is either a hash already or an engine id no url can be recovered from.
 */
internal fun legacyChapterUrl(remoteChapterId: String): String? {
	if (remoteChapterId.isBlank() || remoteChapterId.toLongOrNull() != null) return null
	return remoteChapterId.substringAfter(NATIVE_CHAPTER_ID_DELIMITER, remoteChapterId).takeIf { it.isNotBlank() }
}

/**
 * The local chapter id a pulled history or bookmark row settles on: [remoteChapterId] itself when
 * it is already numeric or the manga is not a data source ([sourceId] null), the hash re-keyed from
 * the url the shipped id carries when that url is one of the manga's [storedChapterUrls], and null
 * when the row cannot be settled here and has to be stored as it came.
 */
internal fun pulledChapterIdAlias(
	remoteChapterId: String,
	sourceId: String?,
	storedChapterUrls: () -> Collection<String>,
): String? {
	if (sourceId == null) return remoteChapterId
	val url = legacyChapterUrl(remoteChapterId) ?: return remoteChapterId
	if (url !in storedChapterUrls()) return null
	return stableChapterId(sourceId.removePrefix(DataDrivenMangaSource.PREFIX), url)
}

/** The chapter urls of a stored `manga.chapters` blob; empty when the blob cannot be read. */
internal fun storedChapterUrls(chaptersBlob: String): Set<String> {
	val chapters = runCatching { JSONArray(chaptersBlob) }.getOrNull() ?: return emptySet()
	val urls = LinkedHashSet<String>(chapters.length())
	for (index in 0 until chapters.length()) {
		val url = chapters.optJSONObject(index)?.optString("url").orEmpty()
		if (url.isNotEmpty()) urls.add(url)
	}
	return urls
}

internal data class CanonicalAliasCandidate(
	val remoteId: String,
	val canonicalId: String,
	val updatedAt: Long,
	val deletedAt: Long,
)

/**
 * Order-independent last-write-wins choice after a legacy id and its canonical id collapse onto one
 * local key. Backend row order is not a conflict policy, so pick the newest effective
 * update/tombstone; on an exact tie prefer the row that is already canonical.
 */
internal fun newestCanonicalAlias(candidates: List<CanonicalAliasCandidate>): CanonicalAliasCandidate? =
	candidates.maxWithOrNull(
		compareBy<CanonicalAliasCandidate> { maxOf(it.updatedAt, it.deletedAt) }
			.thenBy { if (it.remoteId == it.canonicalId) 1 else 0 }
			.thenBy { it.remoteId },
	)

/**
 * A `nyora_source_prefs` row as pulled.
 *
 * @property sourceId the `source_id` as the pushing client spelled it.
 * @property canonicalSourceId the `data:` source it applies to ([canonicalPulledSourceId]).
 * @property updatedAt the row's `updated_at` in epoch millis, 0 when the row carries none.
 */
internal data class PulledSourcePref(
	val sourceId: String,
	val canonicalSourceId: String,
	val isPinned: Boolean,
	val isEnabled: Boolean,
	val updatedAt: Long,
)

/**
 * One pref per local source after the rows of every spelling a client pushed for it (the shipped
 * builds' `DD_`/`JS_` and the current `data:`) collapse onto [PulledSourcePref.canonicalSourceId].
 *
 * The same order-independent rule as [newestCanonicalAlias]: the newest `updated_at` wins, an
 * exact tie goes to the row already spelled canonically, then to the lowest spelling, so a stale
 * row can never re-enable or hide a source depending on which row the backend returned last.
 * Sources keep the order they were first seen in.
 */
internal fun newestCanonicalSourcePrefs(prefs: List<PulledSourcePref>): List<PulledSourcePref> =
	prefs.groupBy { it.canonicalSourceId }.values.mapNotNull { candidates ->
		candidates.maxWithOrNull(
			compareBy<PulledSourcePref> { it.updatedAt }
				.thenBy { if (it.sourceId == it.canonicalSourceId) 1 else 0 }
				.thenBy { it.sourceId },
		)
	}
