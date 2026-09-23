package com.nyora.hasan72341.backups.data

/** Result of the export-only comparison between durable DTOs and the last Room materialization. */
data class NyoraLiveBackupReconciliation(
	val durable: NyoraBackupSnapshot,
	val baseline: NyoraBackupSnapshot,
	val changed: Boolean,
)

/**
 * Retains exact imported DTOs while turning structural Room changes into portable rows exactly once.
 * The baseline is never exposed as backup state; it is only the last materialized Room projection.
 */
object NyoraLiveBackupReconciler {
	fun reconcile(
		durable: NyoraBackupSnapshot,
		baseline: NyoraBackupSnapshot,
		live: NyoraBackupSnapshot,
		updatedAt: String,
		archiveId: String,
	): NyoraLiveBackupReconciliation {
		var changed = false
		fun markChanged() { changed = true }
		val sources = rows(durable.sources, baseline.sources, live.sources, NyoraBackupSource::id,
			{ it.copy(catalogueRevision = "", preferences = emptyMap(), updatedAt = "") },
			{ saved, row -> saved?.copy(installed = row.installed, pinned = row.pinned, updatedAt = updatedAt, deletedAt = row.deletedAt) ?: row.copy(updatedAt = updatedAt) },
			{ it.copy(updatedAt = updatedAt, deletedAt = updatedAt) }, ::markChanged)
		val categories = rows(durable.categories, baseline.categories, live.categories, NyoraBackupCategory::id,
			{ it.copy(updatedAt = "") },
			{ _, row -> row.copy(updatedAt = updatedAt) }, { it.copy(updatedAt = updatedAt, deletedAt = updatedAt) }, ::markChanged)
		val preferences = rows(durable.preferences, baseline.preferences, live.preferences, NyoraBackupPreference::id,
			{ it.copy(updatedAt = "") },
			{ _, row -> row.copy(updatedAt = updatedAt) }, { it.copy(updatedAt = updatedAt, deletedAt = updatedAt) }, ::markChanged)
		val manga = rows(durable.manga, baseline.manga, live.manga, NyoraBackupManga::id,
			{ it.copy(artist = null, updatedAt = "") },
			{ saved, row -> saved?.copy(title = row.title, description = row.description, author = row.author, genres = row.genres, coverUrl = row.coverUrl, updatedAt = updatedAt, deletedAt = row.deletedAt) ?: row.copy(updatedAt = updatedAt) },
			{ it.copy(updatedAt = updatedAt, deletedAt = updatedAt) }, ::markChanged)
		val chapters = rows(durable.chapters, baseline.chapters, live.chapters, NyoraBackupChapter::id,
			{ it.copy(updatedAt = "") },
			{ _, row -> row.copy(updatedAt = updatedAt) }, { it.copy(updatedAt = updatedAt, deletedAt = updatedAt) }, ::markChanged)
		val library = rows(durable.library, baseline.library, live.library, NyoraBackupLibrary::id,
			{ it.copy(updatePolicy = null, updatedAt = "") },
			{ saved, row -> saved?.copy(addedAt = row.addedAt, categoryIds = row.categoryIds, updatedAt = updatedAt, deletedAt = row.deletedAt) ?: row.copy(updatedAt = updatedAt) },
			{ it.copy(updatedAt = updatedAt, deletedAt = updatedAt) }, ::markChanged)
		val history = rows(durable.history, baseline.history, live.history, NyoraBackupHistory::id,
			{ it.copy(readDurationSeconds = null, updatedAt = "") },
			{ saved, row -> saved?.copy(chapterId = row.chapterId, page = row.page, percent = row.percent, updatedAt = updatedAt, deletedAt = row.deletedAt) ?: row.copy(updatedAt = updatedAt) },
			{ it.copy(updatedAt = updatedAt, deletedAt = updatedAt) }, ::markChanged)
		val bookmarks = rows(durable.bookmarks, baseline.bookmarks, live.bookmarks, NyoraBackupBookmark::id,
			{ it.copy(note = null, updatedAt = "") },
			{ saved, row -> saved?.copy(chapterId = row.chapterId, page = row.page, updatedAt = updatedAt, deletedAt = row.deletedAt) ?: row.copy(updatedAt = updatedAt) },
			{ it.copy(updatedAt = updatedAt, deletedAt = updatedAt) }, ::markChanged)
		val tracking = rows(durable.tracking, baseline.tracking, live.tracking, NyoraBackupTracking::id,
			{ it.copy(updatedAt = "") },
			{ _, row -> row.copy(updatedAt = updatedAt) }, { it.copy(updatedAt = updatedAt, deletedAt = updatedAt) }, ::markChanged)
		val refreshed = if (!changed) durable else durable.copy(
			archiveId = archiveId,
			createdAt = updatedAt,
			platform = "android",
			sources = sources,
			categories = categories,
			preferences = preferences,
			manga = manga,
			chapters = chapters,
			library = library,
			history = history,
			bookmarks = bookmarks,
			tracking = tracking,
			omissions = live.omissions,
		)
		return NyoraLiveBackupReconciliation(refreshed, live, changed)
	}

	private fun <T> rows(
		durable: List<T>,
		baseline: List<T>,
		live: List<T>,
		id: (T) -> String,
		structural: (T) -> T,
		overlay: (T?, T) -> T,
		tombstone: (T) -> T,
		onChanged: () -> Unit,
	): List<T> {
		val durableById = durable.associateBy(id)
		val baselineById = baseline.associateBy(id)
		val liveById = live.associateBy(id)
		val result = durableById.toMutableMap()
		for (key in baselineById.keys + liveById.keys) {
			val before = baselineById[key]
			val after = liveById[key]
			when {
				before == null && after != null -> {
					result[key] = overlay(durableById[key], after)
					onChanged()
				}
				before != null && after == null -> {
					result[key] = tombstone(durableById[key] ?: before)
					onChanged()
				}
				before != null && after != null && structural(before) != structural(after) -> {
					result[key] = overlay(durableById[key], after)
					onChanged()
				}
			}
		}
		return result.values.sortedBy(id)
	}
}
