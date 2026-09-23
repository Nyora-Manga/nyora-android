package com.nyora.hasan72341.sync.supabase

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes restore transactions with outbound cloud synchronization. */
@Singleton
class SupabaseSyncBarrier @Inject constructor() {
	private val mutex = Mutex()

	suspend fun <T> withRestore(block: suspend () -> T): T = mutex.withLock { block() }

	suspend fun <T> withSync(block: suspend () -> T): T = mutex.withLock { block() }
}
