package com.nyora.hasan72341.backups.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NyoraRestorePlannerTest {

	@Test
	fun mergeUsesEffectiveClockTombstonesAndArchiveIdTieBreak() {
		val current = snapshot(
			archiveId = "11111111-1111-1111-1111-111111111111",
			category = category("Old", "2026-08-20T10:00:00.000Z"),
			history = history(91, 0.95, "2026-08-20T10:00:00.000Z"),
			bookmark = bookmark("keep", "2026-08-22T10:00:00.000Z"),
		)
		val incoming = snapshot(
			archiveId = "22222222-2222-2222-2222-222222222222",
			category = category("Renamed", "2026-08-21T10:00:00.000Z"),
			history = history(0, 0.0, "2026-08-21T10:00:00.000Z"),
			bookmark = bookmark(
				note = "deleted",
				updatedAt = "2026-08-19T10:00:00.000Z",
				deletedAt = "2026-08-23T10:00:00.000Z",
			),
		)

		val plan = NyoraRestorePlanner.plan(current, incoming, NyoraRestoreMode.Merge, emptySet())

		assertEquals("Renamed", plan.preview.categories.single().title)
		assertEquals(0, plan.preview.history.single().page)
		assertEquals("2026-08-23T10:00:00.000Z", plan.preview.bookmarks.single().deletedAt)
		assertEquals(listOf(SOURCE_ID), plan.unresolvedSources)
		assertEquals(NyoraRestoreSectionPlan(updates = 1), plan.sections.getValue("categories"))
		assertEquals(NyoraRestoreSectionPlan(deletions = 1), plan.sections.getValue("bookmarks"))
	}

	@Test
	fun equalEffectiveClockUsesLexicallyGreaterArchiveId() {
		val current = snapshot(
			archiveId = "11111111-1111-1111-1111-111111111111",
			category = category("Current", "2026-08-21T10:00:00.000Z"),
		)
		val incoming = snapshot(
			archiveId = "22222222-2222-2222-2222-222222222222",
			category = category("Incoming", "2026-08-21T10:00:00.000Z"),
		)

		val plan = NyoraRestorePlanner.plan(current, incoming, NyoraRestoreMode.Merge, setOf(SOURCE_ID))

		assertEquals("Incoming", plan.preview.categories.single().title)
	}

	@Test
	fun replaceMatchesIncomingAndPlanningIsPure() {
		val current = snapshot(
			archiveId = "11111111-1111-1111-1111-111111111111",
			category = category("Local", "2026-08-23T10:00:00.000Z"),
			bookmark = bookmark("local", "2026-08-23T10:00:00.000Z"),
		)
		val before = current.copy()
		val incoming = snapshot(
			archiveId = "22222222-2222-2222-2222-222222222222",
			category = category("Archive", "2026-08-20T10:00:00.000Z"),
			bookmark = null,
		)

		val plan = NyoraRestorePlanner.plan(current, incoming, NyoraRestoreMode.Replace, setOf(SOURCE_ID))

		assertEquals(incoming, plan.preview)
		assertEquals(before, current)
		assertEquals(NyoraRestoreSectionPlan(updates = 1), plan.sections.getValue("categories"))
		assertEquals(NyoraRestoreSectionPlan(deletions = 1), plan.sections.getValue("bookmarks"))
		assertTrue(plan.omissions.isEmpty())
	}

	private fun snapshot(
		archiveId: String,
		category: NyoraBackupCategory,
		history: NyoraBackupHistory = history(1, 0.1, "2026-08-20T10:00:00.000Z"),
		bookmark: NyoraBackupBookmark? = bookmark("note", "2026-08-20T10:00:00.000Z"),
	) = NyoraBackupSnapshot(
		archiveId = archiveId,
		createdAt = "2026-08-21T08:30:00.000Z",
		appVersion = "test",
		platform = "android-test",
		sources = listOf(NyoraBackupSource(SOURCE_ID, "r1", true, false, updatedAt = "2026-08-20T00:00:00.000Z")),
		categories = listOf(category),
		manga = listOf(NyoraBackupManga(MANGA_ID, SOURCE_ID, "/manga", "Manga", "2026-08-20T00:00:00.000Z")),
		chapters = listOf(NyoraBackupChapter(CHAPTER_ID, MANGA_ID, "/chapter", "Chapter", "2026-08-20T00:00:00.000Z")),
		library = listOf(NyoraBackupLibrary(MANGA_ID, "2026-08-20T00:00:00.000Z", listOf(CATEGORY_ID))),
		history = listOf(history),
		bookmarks = listOfNotNull(bookmark),
	)

	private fun category(title: String, updatedAt: String) =
		NyoraBackupCategory(CATEGORY_ID, title, 0, updatedAt)

	private fun history(page: Int, percent: Double, updatedAt: String) =
		NyoraBackupHistory(MANGA_ID, CHAPTER_ID, page, percent, updatedAt)

	private fun bookmark(note: String, updatedAt: String, deletedAt: String? = null) =
		NyoraBackupBookmark(BOOKMARK_ID, MANGA_ID, CHAPTER_ID, 1, note, updatedAt, deletedAt)

	private companion object {
		const val SOURCE_ID = "data:source"
		const val MANGA_ID = "ff767544cc03b5b9d6fb245cea2646bf115e7ffaa10143fefa03703a9b8f5d5c"
		const val CHAPTER_ID = "7d33ab72ea7e112f1eb2e8e049141d0cde8eadee0a52fdb7131012711fab7c44"
		const val CATEGORY_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
		const val BOOKMARK_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
	}
}
