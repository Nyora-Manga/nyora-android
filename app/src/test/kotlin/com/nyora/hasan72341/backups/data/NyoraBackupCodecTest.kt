package com.nyora.hasan72341.backups.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NyoraBackupCodecTest {

	@Test
	fun decodesSharedGoldenArchiveWithEntityParity() {
		val bytes = requireNotNull(javaClass.classLoader?.getResource("backup/v1/golden-minimal.nyorabk"))
			.readBytes()

		val snapshot = NyoraBackupCodec.decode(bytes)

		assertEquals(1, snapshot.schemaVersion)
		assertEquals("123e4567-e89b-12d3-a456-426614174000", snapshot.archiveId)
		assertEquals("2026-08-21T12:34:56.789Z", snapshot.createdAt)
		assertEquals("2.0.7", snapshot.appVersion)
		assertEquals("desktop", snapshot.platform)
		assertEquals("portable-data", snapshot.mode)
		assertEquals(listOf("data:mangadex"), snapshot.sources.map { it.id })
		assertEquals(listOf("Reading"), snapshot.categories.map { it.title })
		assertEquals(
			listOf("c5ef7bb1a3fd99addd09ee105f6de9bc2a1e77285a27d2d906597a06ec76bf3a"),
			snapshot.manga.map { it.id },
		)
		assertEquals(
			listOf("bfabb5a378d533daea8d03f85903f1a9e5553a23dbd4bd201173179da95a6277"),
			snapshot.chapters.map { it.id },
		)
		assertEquals(1, snapshot.library.size)
		assertEquals(1, snapshot.history.size)
		assertEquals(1, snapshot.bookmarks.size)
		assertEquals(1, snapshot.tracking.size)
	}

	@Test
	fun reEncodesSharedGoldenArchiveDeterministically() {
		val bytes = requireNotNull(javaClass.classLoader?.getResource("backup/v1/golden-minimal.nyorabk"))
			.readBytes()

		assertArrayEquals(bytes, NyoraBackupCodec.encode(NyoraBackupCodec.decode(bytes)))
	}
}
