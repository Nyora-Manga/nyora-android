package com.nyora.hasan72341.settings.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nyora.hasan72341.backups.data.BackupRepository
import com.nyora.hasan72341.backups.data.NyoraBackupCodec
import com.nyora.hasan72341.backups.domain.AppBackupAgent
import com.nyora.hasan72341.core.db.MangaDatabase
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppBackupAgentTest {
	@get:Rule
	var hiltRule = HiltAndroidRule(this)

	@Inject lateinit var backupRepository: BackupRepository
	@Inject lateinit var database: MangaDatabase

	@Before
	fun setUp() {
		hiltRule.inject()
		database.clearAllTables()
	}

	@Test
	fun fullBackupPayloadIsOnlyThePortableNyoraArchive() {
		val file = AppBackupAgent().createBackupFile(
			InstrumentationRegistry.getInstrumentation().targetContext,
			backupRepository,
		)
		try {
			assertTrue(file.name.endsWith(".nyorabk"))
			val snapshot = NyoraBackupCodec.decode(file.readBytes())
			assertEquals("android", snapshot.platform)
			assertTrue(snapshot.preferences.isEmpty())
		} finally {
			file.delete()
		}
	}
}
