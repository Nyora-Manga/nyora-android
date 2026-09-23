package com.nyora.hasan72341.backups.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NyoraCurrentRestorePlannerTest {
	@Test
	fun `plan overlays unexported live edit before incoming LWW`() {
		val durable = snapshot("Imported", "2026-08-20T00:00:00.000Z")
		val baseline = snapshot("Imported", "2026-08-20T00:00:00.000Z")
		val live = snapshot("Edited locally", "2026-08-20T00:00:00.000Z")
		val incoming = snapshot("Older remote", "2026-08-21T00:00:00.000Z").copy(
			archiveId = "22222222-2222-2222-2222-222222222222",
		)

		val plan = NyoraCurrentRestorePlanner.plan(
			durable = durable,
			baseline = baseline,
			live = live,
			incoming = incoming,
			mode = NyoraRestoreMode.Merge,
			availableSourceIds = emptySet(),
			observedAt = "2026-08-22T00:00:00.000Z",
			localArchiveId = "33333333-3333-3333-3333-333333333333",
		)

		assertEquals("Edited locally", plan.preview.categories.single().title)
		assertEquals("2026-08-22T00:00:00.000Z", plan.preview.categories.single().updatedAt)
	}

	private fun snapshot(title: String, updatedAt: String) = NyoraBackupSnapshot(
		archiveId = "11111111-1111-1111-1111-111111111111",
		createdAt = "2026-08-20T00:00:00.000Z",
		appVersion = "test",
		platform = "android",
		categories = listOf(NyoraBackupCategory("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", title, 0, updatedAt)),
	)
}
