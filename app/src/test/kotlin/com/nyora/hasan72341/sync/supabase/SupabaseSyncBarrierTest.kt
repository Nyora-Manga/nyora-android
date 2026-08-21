package com.nyora.hasan72341.sync.supabase

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SupabaseSyncBarrierTest {
	@Test
	fun `outbound sync waits until restore releases the write barrier`() = runTest {
		val barrier = SupabaseSyncBarrier()
		val restoreEntered = CompletableDeferred<Unit>()
		val releaseRestore = CompletableDeferred<Unit>()
		val syncEntered = CompletableDeferred<Unit>()
		val events = mutableListOf<String>()

		val restore = async {
			barrier.withRestore {
				events += "restore"
				restoreEntered.complete(Unit)
				releaseRestore.await()
			}
		}
		restoreEntered.await()
		val sync = async {
			barrier.withSync {
				events += "sync"
				syncEntered.complete(Unit)
			}
		}

		assertFalse(syncEntered.isCompleted)
		releaseRestore.complete(Unit)
		restore.await()
		sync.await()
		assertEquals(listOf("restore", "sync"), events)
	}
}
