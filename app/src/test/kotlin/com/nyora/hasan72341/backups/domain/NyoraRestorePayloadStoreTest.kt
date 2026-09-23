package com.nyora.hasan72341.backups.domain

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NyoraRestorePayloadStoreTest {
	@Test
	fun `prepared payload remains the exact previewed bytes when provider data changes`() {
		val directory = Files.createTempDirectory("nyora-restore-payload").toFile()
		val previewed = byteArrayOf(1, 2, 3, 4)
		val store = NyoraRestorePayloadStore(directory)

		val prepared = store.prepare(previewed)
		previewed.fill(9)

		assertArrayEquals(byteArrayOf(1, 2, 3, 4), store.consume(prepared))
	}

	@Test
	fun `failed start and dismissal discard an owned payload while successful handoff preserves it`() {
		val directory = Files.createTempDirectory("nyora-restore-payload").toFile()
		val store = NyoraRestorePayloadStore(directory)
		val failed = NyoraRestorePayloadOwner(store, store.prepare(byteArrayOf(1)))

		assertFalse(failed.handOff { false })
		assertTrue(directory.listFiles().isNullOrEmpty())

		val dismissed = NyoraRestorePayloadOwner(store, store.prepare(byteArrayOf(2)))
		dismissed.discard()
		assertTrue(directory.listFiles().isNullOrEmpty())

		val started = NyoraRestorePayloadOwner(store, store.prepare(byteArrayOf(3)))
		assertTrue(started.handOff { true })
		assertFalse(started.handOff { throw AssertionError("payload was handed off twice") })
		started.discard()
		assertArrayEquals(byteArrayOf(3), store.consume(checkNotNull(directory.listFiles()).single()))
	}

	@Test
	fun `preparing a payload sweeps stale process leftovers and bounds retained files`() {
		val directory = Files.createTempDirectory("nyora-restore-payload").toFile()
		var now = 100_000L
		val store = NyoraRestorePayloadStore(
			directory = directory,
			nowMillis = { now },
			staleAfterMillis = 1_000L,
			maxRetainedPayloads = 2,
		)
		val stale = store.prepare(byteArrayOf(1))
		stale.setLastModified(now - 2_000L)
		now += 1

		store.prepare(byteArrayOf(2))
		store.prepare(byteArrayOf(3))
		val latest = store.prepare(byteArrayOf(4))

		assertFalse(stale.exists())
		assertEquals(2, checkNotNull(directory.listFiles()).size)
		assertTrue(latest.exists())
	}

	@Test
	fun `a payload prepared before process death can be consumed by a new store instance`() {
		val directory = Files.createTempDirectory("nyora-restore-payload").toFile()
		val prepared = NyoraRestorePayloadStore(directory).prepare(byteArrayOf(7, 8))

		val recovered = NyoraRestorePayloadStore(directory).consume(prepared)

		assertArrayEquals(byteArrayOf(7, 8), recovered)
		assertTrue(directory.listFiles().isNullOrEmpty())
	}

	@Test
	fun `configuration destruction retains ownership until the recreated host finishes`() {
		val directory = Files.createTempDirectory("nyora-restore-payload").toFile()
		val store = NyoraRestorePayloadStore(directory)
		val owner = NyoraRestorePayloadOwner(store, store.prepare(byteArrayOf(5)))

		owner.onHostDestroyed(isChangingConfigurations = true)

		assertTrue(owner.payload?.exists() == true)
		owner.onHostDestroyed(isChangingConfigurations = false)
		assertTrue(directory.listFiles().isNullOrEmpty())
	}

	@Test
	fun `final viewmodel clear discards a payload retained across configuration change`() {
		val directory = Files.createTempDirectory("nyora-restore-payload").toFile()
		val store = NyoraRestorePayloadStore(directory)
		val owner = NyoraRestorePayloadOwner(store, store.prepare(byteArrayOf(6)))
		owner.onHostDestroyed(isChangingConfigurations = true)

		owner.onViewModelCleared()

		assertTrue(directory.listFiles().isNullOrEmpty())
	}
}
