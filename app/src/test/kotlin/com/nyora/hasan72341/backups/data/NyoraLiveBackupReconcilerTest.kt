package com.nyora.hasan72341.backups.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NyoraLiveBackupReconcilerTest {
	@Test
	fun `post-import local edit is stamped once and repeated export is stable`() {
		val imported = snapshot(category("Imported", IMPORTED_AT))
		val baseline = snapshot(category("Imported", IMPORTED_AT))
		val live = snapshot(category("Edited locally", "2099-01-01T00:00:00.000Z"))

		val first = NyoraLiveBackupReconciler.reconcile(imported, baseline, live, LOCAL_AT, LOCAL_ARCHIVE)
		assertEquals("Edited locally", first.durable.categories.single().title)
		assertEquals(LOCAL_AT, first.durable.categories.single().updatedAt)
		assertEquals(LOCAL_ARCHIVE, first.durable.archiveId)

		val second = NyoraLiveBackupReconciler.reconcile(first.durable, first.baseline, live, "2026-08-23T00:00:00.000Z", "33333333-3333-3333-3333-333333333333")
		assertEquals(first.durable, second.durable)
	}

	@Test
	fun `hard-deleted baseline row becomes a durable tombstone`() {
		val imported = snapshot(category("Imported", IMPORTED_AT))
		val result = NyoraLiveBackupReconciler.reconcile(imported, imported, snapshot(), LOCAL_AT, LOCAL_ARCHIVE)

		assertEquals(LOCAL_AT, result.durable.categories.single().deletedAt)
		assertEquals(LOCAL_AT, result.durable.categories.single().updatedAt)
	}

	@Test
	fun `preview reconciliation is pure`() {
		val durable = snapshot(category("Imported", IMPORTED_AT))
		val baseline = snapshot(category("Imported", IMPORTED_AT))
		val beforeDurable = durable.copy()
		val beforeBaseline = baseline.copy()

		NyoraLiveBackupReconciler.reconcile(durable, baseline, snapshot(category("Local", LOCAL_AT)), LOCAL_AT, LOCAL_ARCHIVE)

		assertEquals(beforeDurable, durable)
		assertEquals(beforeBaseline, baseline)
	}

	@Test
	fun `baseline-equal live row preserves exact imported durable fields`() {
		val imported = snapshot(category("Imported", IMPORTED_AT).copy(deletedAt = null))
		val baseline = snapshot(category("Imported", "2026-01-01T00:00:00.000Z"))
		val live = snapshot(category("Imported", "2099-01-01T00:00:00.000Z"))

		val result = NyoraLiveBackupReconciler.reconcile(imported, baseline, live, LOCAL_AT, LOCAL_ARCHIVE)

		assertEquals(imported, result.durable)
		assertNotEquals(live.categories.single().updatedAt, result.durable.categories.single().updatedAt)
	}

	@Test
	fun `native soft delete is observed instead of hidden as a clock-only change`() {
		val durable = snapshot(category("Imported", IMPORTED_AT))
		val baseline = snapshot(category("Imported", IMPORTED_AT))
		val live = snapshot(category("Imported", IMPORTED_AT).copy(deletedAt = NATIVE_DELETE_AT))

		val result = NyoraLiveBackupReconciler.reconcile(durable, baseline, live, LOCAL_AT, LOCAL_ARCHIVE)

		assertEquals(NATIVE_DELETE_AT, result.durable.categories.single().deletedAt)
		assertEquals(LOCAL_AT, result.durable.categories.single().updatedAt)
	}

	@Test
	fun `local structural overlay retains archive-only durable fields`() {
		val durable = richSnapshot(title = "Imported", page = 3).copy(
			sources = listOf(richSnapshot("Imported", 3).sources.single().copy(
				catalogueRevision = "remote-revision",
				preferences = mapOf("language" to JsonPrimitive("ja")),
			)),
			manga = listOf(richSnapshot("Imported", 3).manga.single().copy(artist = "Kentaro Miura")),
			library = listOf(richSnapshot("Imported", 3).library.single().copy(updatePolicy = "always")),
			history = listOf(richSnapshot("Imported", 3).history.single().copy(readDurationSeconds = 99)),
			bookmarks = listOf(richSnapshot("Imported", 3).bookmarks.single().copy(note = "archive note")),
		)
		val baseline = richSnapshot(title = "Imported", page = 3)
		val live = richSnapshot(title = "Edited locally", page = 8)

		val result = NyoraLiveBackupReconciler.reconcile(durable, baseline, live, LOCAL_AT, LOCAL_ARCHIVE).durable

		assertEquals("Edited locally", result.manga.single().title)
		assertEquals("Kentaro Miura", result.manga.single().artist)
		assertEquals("always", result.library.single().updatePolicy)
		assertEquals(99L, result.history.single().readDurationSeconds)
		assertEquals(8, result.history.single().page)
		assertEquals("archive note", result.bookmarks.single().note)
		assertEquals("remote-revision", result.sources.single().catalogueRevision)
		assertEquals(JsonPrimitive("ja"), result.sources.single().preferences.getValue("language"))
	}

	private fun category(title: String, updatedAt: String) = NyoraBackupCategory(CATEGORY_ID, title, 0, updatedAt)

	private fun snapshot(vararg categories: NyoraBackupCategory) = NyoraBackupSnapshot(
		archiveId = "11111111-1111-1111-1111-111111111111",
		createdAt = IMPORTED_AT,
		appVersion = "test",
		platform = "android",
		categories = categories.toList(),
	)

	private fun richSnapshot(title: String, page: Int): NyoraBackupSnapshot {
		val mangaId = "a".repeat(64)
		return NyoraBackupSnapshot(
			archiveId = "11111111-1111-1111-1111-111111111111",
			createdAt = IMPORTED_AT,
			appVersion = "test",
			platform = "android",
			sources = listOf(NyoraBackupSource("data:source", "local-revision", pinned = false, updatedAt = IMPORTED_AT)),
			manga = listOf(NyoraBackupManga(mangaId, "data:source", "/manga", title, IMPORTED_AT)),
			library = listOf(NyoraBackupLibrary(mangaId, IMPORTED_AT)),
			history = listOf(NyoraBackupHistory(mangaId, page = page, updatedAt = IMPORTED_AT)),
			bookmarks = listOf(NyoraBackupBookmark(CATEGORY_ID, mangaId, page = page, updatedAt = IMPORTED_AT)),
		)
	}

	private companion object {
		const val CATEGORY_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
		const val IMPORTED_AT = "2026-08-20T00:00:00.000Z"
		const val LOCAL_AT = "2026-08-22T00:00:00.000Z"
		const val LOCAL_ARCHIVE = "22222222-2222-2222-2222-222222222222"
		const val NATIVE_DELETE_AT = "2026-08-21T12:00:00.000Z"
	}
}
