package com.nyora.hasan72341.backups.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.nyora.hasan72341.core.db.MangaDatabase

@RunWith(AndroidJUnit4::class)
class NyoraRoomBackupRepositoryTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val databaseNames = mutableListOf<String>()

	@After
	fun cleanup() {
		databaseNames.forEach(context::deleteDatabase)
	}

	@Test
	fun previewIsPureAndAppliedLedgerSurvivesDatabaseRestart() = runBlocking {
		val name = "nyora-backup-${UUID.randomUUID()}"
		databaseNames += name
		var database = database(name)
		val repository = NyoraRoomBackupRepository(database, { setOf(SOURCE_ID) })
		val initial = snapshot("11111111-1111-1111-1111-111111111111", "Initial", "2026-08-20T00:00:00.000Z")
		repository.apply(NyoraBackupCodec.encode(initial), NyoraRestoreMode.Replace)
		val incoming = snapshot("22222222-2222-2222-2222-222222222222", "Newer", "2026-08-21T00:00:00.000Z")

		val plan = repository.plan(NyoraBackupCodec.encode(incoming))

		assertEquals("Newer", plan.preview.categories.single().title)
		assertEquals(initial, repository.readSnapshot())
		repository.apply(NyoraBackupCodec.encode(incoming))
		database.close()

		database = database(name)
		assertEquals(incoming, NyoraRoomBackupRepository(database, { setOf(SOURCE_ID) }).readSnapshot())
		database.close()
	}

	@Test
	fun failureRollsBackLedgerAndResumesObserversWithoutReconciliation() = runBlocking {
		val name = "nyora-backup-${UUID.randomUUID()}"
		databaseNames += name
		val database = database(name)
		val initial = snapshot("11111111-1111-1111-1111-111111111111", "Initial", "2026-08-20T00:00:00.000Z")
		NyoraRoomBackupRepository(database, { setOf(SOURCE_ID) })
			.apply(NyoraBackupCodec.encode(initial), NyoraRestoreMode.Replace)
		val events = mutableListOf<String>()
		val repository = NyoraRoomBackupRepository(
			database = database,
			availableSourceIds = { setOf(SOURCE_ID) },
			materializer = NyoraPortableMaterializer {
				events += "materialize"
				throw IllegalStateException("injected")
			},
			observerGate = object : NyoraBackupObserverGate {
				override suspend fun setSuppressed(suppressed: Boolean) {
					events += if (suppressed) "pause" else "resume"
				}

				override suspend fun reconcile() {
					events += "reconcile"
				}
			},
		)

		try {
			repository.apply(
				NyoraBackupCodec.encode(snapshot("22222222-2222-2222-2222-222222222222", "Incoming", "2026-08-21T00:00:00.000Z")),
				NyoraRestoreMode.Replace,
			)
			throw AssertionError("Expected injected restore failure")
		} catch (error: IllegalStateException) {
			assertEquals("injected", error.message)
		}

		assertEquals(initial, repository.readSnapshot())
		assertEquals(listOf("pause", "materialize", "resume"), events)
		assertTrue(database.isOpen)
		database.close()
	}

	@Test
	fun portableIdentityMappingIsPersistentAndCollisionSafe() = runBlocking {
		val name = "nyora-backup-${UUID.randomUUID()}"
		databaseNames += name
		var database = database(name)
		val dao = database.getNyoraBackupIdentityMapDao()

		val first = dao.resolveLocalId("category", "Aa")
		val collidingHash = dao.resolveLocalId("category", "BB")
		val bookmark = dao.resolveLocalId("bookmark", "Aa")

		assertTrue(first > 0)
		assertTrue(collidingHash > 0)
		assertTrue(bookmark > 0)
		assertTrue(first != collidingHash)
		database.close()
		database = database(name)
		assertEquals(first, database.getNyoraBackupIdentityMapDao().resolveLocalId("category", "Aa"))
		assertEquals(collidingHash, database.getNyoraBackupIdentityMapDao().resolveLocalId("category", "BB"))
		database.close()
	}

	private fun database(name: String): MangaDatabase = Room.databaseBuilder(context, MangaDatabase::class.java, name)
		.allowMainThreadQueries()
		.build()

	private fun snapshot(archiveId: String, title: String, updatedAt: String): NyoraBackupSnapshot =
		NyoraBackupSnapshot(
			archiveId = archiveId,
			createdAt = "2026-08-21T08:30:00.000Z",
			appVersion = "test",
			platform = "android-test",
			sources = listOf(NyoraBackupSource(SOURCE_ID, "r1", updatedAt = updatedAt)),
			categories = listOf(NyoraBackupCategory(CATEGORY_ID, title, 0, updatedAt)),
		)

	private companion object {
		const val SOURCE_ID = "data:source"
		const val CATEGORY_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
	}
}
