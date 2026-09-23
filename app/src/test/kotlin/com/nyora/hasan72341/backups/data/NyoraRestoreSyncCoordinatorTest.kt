package com.nyora.hasan72341.backups.data

import com.nyora.hasan72341.sync.supabase.SupabaseSyncBarrier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class NyoraRestoreSyncCoordinatorTest {
	@Test
	fun `successful commit schedules exactly one cloud reconciliation after leaving barrier`() = runTest {
		val events = mutableListOf<String>()
		val barrier = SupabaseSyncBarrier()
		val coordinator = NyoraRestoreSyncCoordinator(
			barrier = barrier,
			reconcileCloud = { barrier.withSync { events += "reconcile" } },
		)

		coordinator.restore {
			events += "commit"
			"done"
		}

		assertEquals(listOf("commit", "reconcile"), events)
	}

	@Test
	fun `failed transaction never schedules cloud reconciliation`() = runTest {
		var reconciliations = 0
		val coordinator = NyoraRestoreSyncCoordinator(
			barrier = SupabaseSyncBarrier(),
			reconcileCloud = { reconciliations++ },
		)

		try {
			coordinator.restore<Unit> { error("rollback") }
			fail("expected restore failure")
		} catch (expected: IllegalStateException) {
			assertEquals("rollback", expected.message)
		}

		assertEquals(0, reconciliations)
	}
}
