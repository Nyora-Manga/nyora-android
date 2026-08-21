package com.nyora.hasan72341.backups.domain

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
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
}
