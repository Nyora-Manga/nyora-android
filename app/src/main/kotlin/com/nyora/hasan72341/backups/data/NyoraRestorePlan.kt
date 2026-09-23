package com.nyora.hasan72341.backups.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class NyoraRestoreMode {
	@SerialName("merge")
	Merge,

	@SerialName("replace")
	Replace,
	;

	companion object {
		fun parse(value: String?): NyoraRestoreMode? = when (value?.lowercase()) {
			null, "", "merge" -> Merge
			"replace" -> Replace
			else -> null
		}
	}
}

@Serializable
data class NyoraRestoreSectionPlan(
	val additions: Int = 0,
	val updates: Int = 0,
	val deletions: Int = 0,
	val unchanged: Int = 0,
)

@Serializable
data class NyoraRestorePlan(
	val mode: NyoraRestoreMode,
	val archiveId: String,
	val createdAt: String,
	val platform: String,
	val sections: Map<String, NyoraRestoreSectionPlan>,
	val unresolvedSources: List<String>,
	val omissions: Map<String, Int>,
	val warnings: List<String> = emptyList(),
	@Transient
	val preview: NyoraBackupSnapshot = EMPTY_PREVIEW,
) {
	companion object {
		private val EMPTY_PREVIEW = NyoraBackupSnapshot(
			archiveId = "00000000-0000-0000-0000-000000000000",
			createdAt = "1970-01-01T00:00:00.000Z",
			appVersion = "preview",
			platform = "preview",
		)
	}
}

@Serializable
data class NyoraRestoreResult(
	val ok: Boolean = true,
	val plan: NyoraRestorePlan,
	val applied: Map<String, Int>,
)

object NyoraRestorePlanner {

	fun plan(
		current: NyoraBackupSnapshot,
		incoming: NyoraBackupSnapshot,
		mode: NyoraRestoreMode = NyoraRestoreMode.Merge,
		availableSourceIds: Set<String>,
	): NyoraRestorePlan {
		val target = when (mode) {
			NyoraRestoreMode.Merge -> merge(current, incoming)
			NyoraRestoreMode.Replace -> incoming
		}
		NyoraBackupCodec.encode(target)
		val sections = linkedMapOf(
			"sources" to counts(current.sources, target.sources, NyoraBackupSource::id, NyoraBackupSource::deletedAt),
			"categories" to counts(current.categories, target.categories, NyoraBackupCategory::id, NyoraBackupCategory::deletedAt),
			"preferences" to counts(current.preferences, target.preferences, NyoraBackupPreference::id, NyoraBackupPreference::deletedAt),
			"manga" to counts(current.manga, target.manga, NyoraBackupManga::id, NyoraBackupManga::deletedAt),
			"chapters" to counts(current.chapters, target.chapters, NyoraBackupChapter::id, NyoraBackupChapter::deletedAt),
			"library" to counts(current.library, target.library, NyoraBackupLibrary::id, NyoraBackupLibrary::deletedAt),
			"history" to counts(current.history, target.history, NyoraBackupHistory::id, NyoraBackupHistory::deletedAt),
			"bookmarks" to counts(current.bookmarks, target.bookmarks, NyoraBackupBookmark::id, NyoraBackupBookmark::deletedAt),
			"tracking" to counts(current.tracking, target.tracking, NyoraBackupTracking::id, NyoraBackupTracking::deletedAt),
		)
		val unresolved = target.sources.asSequence()
			.filter { it.deletedAt == null && it.id !in availableSourceIds }
			.map { it.id }
			.sorted()
			.toList()
		return NyoraRestorePlan(
			mode = mode,
			archiveId = incoming.archiveId,
			createdAt = incoming.createdAt,
			platform = incoming.platform,
			sections = sections,
			unresolvedSources = unresolved,
			omissions = incoming.omissions,
			warnings = if (unresolved.isEmpty()) emptyList() else listOf(
				"Some sources are valid but unavailable in the current catalogue; their data will be retained.",
			),
			preview = target,
		)
	}

	private fun merge(current: NyoraBackupSnapshot, incoming: NyoraBackupSnapshot): NyoraBackupSnapshot =
		incoming.copy(
			sources = mergeRows(current.sources, incoming.sources, NyoraBackupSource::id, NyoraBackupSource::updatedAt, NyoraBackupSource::deletedAt, current.archiveId, incoming.archiveId),
			categories = mergeRows(current.categories, incoming.categories, NyoraBackupCategory::id, NyoraBackupCategory::updatedAt, NyoraBackupCategory::deletedAt, current.archiveId, incoming.archiveId),
			preferences = mergeRows(current.preferences, incoming.preferences, NyoraBackupPreference::id, NyoraBackupPreference::updatedAt, NyoraBackupPreference::deletedAt, current.archiveId, incoming.archiveId),
			manga = mergeRows(current.manga, incoming.manga, NyoraBackupManga::id, NyoraBackupManga::updatedAt, NyoraBackupManga::deletedAt, current.archiveId, incoming.archiveId),
			chapters = mergeRows(current.chapters, incoming.chapters, NyoraBackupChapter::id, NyoraBackupChapter::updatedAt, NyoraBackupChapter::deletedAt, current.archiveId, incoming.archiveId),
			library = mergeRows(current.library, incoming.library, NyoraBackupLibrary::id, NyoraBackupLibrary::updatedAt, NyoraBackupLibrary::deletedAt, current.archiveId, incoming.archiveId),
			history = mergeRows(current.history, incoming.history, NyoraBackupHistory::id, NyoraBackupHistory::updatedAt, NyoraBackupHistory::deletedAt, current.archiveId, incoming.archiveId),
			bookmarks = mergeRows(current.bookmarks, incoming.bookmarks, NyoraBackupBookmark::id, NyoraBackupBookmark::updatedAt, NyoraBackupBookmark::deletedAt, current.archiveId, incoming.archiveId),
			tracking = mergeRows(current.tracking, incoming.tracking, NyoraBackupTracking::id, NyoraBackupTracking::updatedAt, NyoraBackupTracking::deletedAt, current.archiveId, incoming.archiveId),
		)

	private fun <T> mergeRows(
		current: List<T>,
		incoming: List<T>,
		id: (T) -> String,
		updatedAt: (T) -> String,
		deletedAt: (T) -> String?,
		currentArchiveId: String,
		incomingArchiveId: String,
	): List<T> {
		val local = current.associateBy(id)
		val remote = incoming.associateBy(id)
		return (local.keys + remote.keys).sorted().map { key ->
			val left = local[key]
			val right = remote[key]
			when {
				left == null -> requireNotNull(right)
				right == null -> left
				effectiveClock(right, updatedAt, deletedAt) > effectiveClock(left, updatedAt, deletedAt) -> right
				effectiveClock(right, updatedAt, deletedAt) < effectiveClock(left, updatedAt, deletedAt) -> left
				incomingArchiveId > currentArchiveId -> right
				else -> left
			}
		}
	}

	private fun <T> effectiveClock(row: T, updatedAt: (T) -> String, deletedAt: (T) -> String?): String =
		maxOf(updatedAt(row), deletedAt(row).orEmpty())

	private fun <T> counts(
		before: List<T>,
		after: List<T>,
		id: (T) -> String,
		deletedAt: (T) -> String?,
	): NyoraRestoreSectionPlan {
		val old = before.associateBy(id)
		val new = after.associateBy(id)
		var additions = 0
		var updates = 0
		var deletions = 0
		var unchanged = 0
		for (key in old.keys + new.keys) {
			val left = old[key]
			val right = new[key]
			when {
				right == null -> deletions++
				left == null && deletedAt(right) != null -> deletions++
				left == null -> additions++
				left == right -> unchanged++
				deletedAt(right) != null && deletedAt(left) == null -> deletions++
				else -> updates++
			}
		}
		return NyoraRestoreSectionPlan(additions, updates, deletions, unchanged)
	}
}
