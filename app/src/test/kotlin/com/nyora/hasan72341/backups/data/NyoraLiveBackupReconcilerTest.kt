package com.nyora.hasan72341.backups.data

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

	private fun category(title: String, updatedAt: String) = NyoraBackupCategory(CATEGORY_ID, title, 0, updatedAt)

	private fun snapshot(vararg categories: NyoraBackupCategory) = NyoraBackupSnapshot(
		archiveId = "11111111-1111-1111-1111-111111111111",
		createdAt = IMPORTED_AT,
		appVersion = "test",
		platform = "android",
		categories = categories.toList(),
	)

	private companion object {
		const val CATEGORY_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
		const val IMPORTED_AT = "2026-08-20T00:00:00.000Z"
		const val LOCAL_AT = "2026-08-22T00:00:00.000Z"
		const val LOCAL_ARCHIVE = "22222222-2222-2222-2222-222222222222"
	}
}
