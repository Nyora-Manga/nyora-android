package com.nyora.hasan72341.backups.domain

import com.nyora.hasan72341.backups.data.NyoraRestoreMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NyoraBackupFlowTest {
	@Test
	fun usesNyoraArchiveExtensionAndMimeEverywhere() {
		assertEquals(".nyorabk", NyoraBackupFiles.EXTENSION)
		assertEquals("application/vnd.nyora.backup", NyoraBackupFiles.MIME_TYPE)
		assertEquals("nyora_20260821-1234.nyorabk", NyoraBackupFiles.fileName("Nyora", "20260821-1234"))
		assertTrue(NyoraBackupFiles.isSupported("library.nyorabk"))
		listOf("library.bk.zip", "library.json", "library.tachibk", "library.zip").forEach {
			assertFalse(NyoraBackupFiles.isSupported(it))
		}
	}

	@Test
	fun restoreDefaultsToMergeAndReplaceRequiresExplicitConfirmation() {
		assertEquals(NyoraRestoreMode.Merge, NyoraRestoreRequest().mode)
		assertEquals(NyoraRestoreMode.Replace, NyoraRestoreRequest(NyoraRestoreMode.Replace, replaceConfirmed = true).mode)
		try {
			NyoraRestoreRequest(NyoraRestoreMode.Replace)
			fail("Replace restore must require explicit confirmation")
		} catch (_: IllegalArgumentException) {
			// Expected.
		}
	}
}
