package com.nyora.hasan72341.sync.supabase

import com.nyora.hasan72341.core.model.DataDrivenMangaSource
import com.nyora.hasan72341.core.parser.datadriven.LEGACY_SOURCE_RENAMES
import com.nyora.hasan72341.core.parser.datadriven.canonicalDataSourceId
import com.nyora.hasan72341.core.parser.datadriven.decodeStoredSourceName
import com.nyora.hasan72341.core.parser.datadriven.stableMangaId
import java.util.Locale

/**
 * Pure identity helpers for the cloud pull.
 *
 * A data-catalogue manga id is hashed from (source token, url) on every Nyora client, so the cloud
 * copy of a row is only ever a carrier for that identity: a row an older client wrote under a
 * server-generated id, or under a source spelling the catalogue has since renamed, has to be
 * re-hashed before anything local is keyed by it. Push needs none of this because local ids are
 * canonical already.
 */

/** Retired wrappers an older Android or web client stored a catalogue identity inside. */
private val DATA_SOURCE_WRAPPERS = listOf(DataDrivenMangaSource.PREFIX, "JS_data:", "DD_")

/**
 * The canonical `data:<catalogue-id>` identity a synced `source_ref` resolves to, or null when the
 * row belongs to a source whose ids this client does not hash (`LOCAL`, `UNKNOWN`, a parser row).
 */
internal fun canonicalPulledSourceId(storedSourceRef: String): String? {
	val decoded = decodeStoredSourceName(storedSourceRef)
	val bare = DATA_SOURCE_WRAPPERS.firstNotNullOfOrNull { wrapper ->
		decoded.removePrefix(wrapper).takeIf { it != decoded }
	} ?: return null
	if (bare.isEmpty()) return null
	val renamed = LEGACY_SOURCE_RENAMES[DataDrivenMangaSource.PREFIX + bare.lowercase(Locale.ROOT)]
		?.removePrefix(DataDrivenMangaSource.PREFIX)
		?: bare
	return DataDrivenMangaSource.PREFIX + canonicalDataSourceId(renamed)
}

/**
 * The local Room id a pulled cloud row settles on, which is [remoteId] itself for every source this
 * client does not hash ids for.
 */
internal fun pulledMangaIdAlias(remoteId: String, storedSourceRef: String, mangaUrl: String): String {
	val sourceId = canonicalPulledSourceId(storedSourceRef) ?: return remoteId
	return stableMangaId(sourceId.removePrefix(DataDrivenMangaSource.PREFIX), mangaUrl)
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
