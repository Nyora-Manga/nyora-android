package com.nyora.hasan72341.backups.data

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import com.nyora.hasan72341.core.db.MangaDatabase
import com.nyora.hasan72341.list.domain.ListSortOrder

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
		val projector = NyoraRoomBackupProjector(database, "test")
		val realMaterializer = NyoraRoomPortableMaterializer(database)
		NyoraRoomBackupRepository(
			database,
			{ setOf(SOURCE_ID) },
			projector::snapshot,
			realMaterializer,
		)
			.apply(NyoraBackupCodec.encode(initial), NyoraRestoreMode.Replace)
		val events = mutableListOf<String>()
		val repository = NyoraRoomBackupRepository(
			database = database,
			availableSourceIds = { setOf(SOURCE_ID) },
			materializer = NyoraPortableMaterializer {
				events += "materialize"
				realMaterializer.replace(it)
				throw IllegalStateException("injected")
			},
			initialSnapshot = projector::snapshot,
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
		assertEquals("Initial", database.getFavouriteCategoriesDao().findAllForSync().single().title)
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

	@Test
	fun realProjectorAndMaterializerPreserveCategoryTrackingAndUncategorizedIdentity() = runBlocking {
		val name = "nyora-backup-${UUID.randomUUID()}"
		databaseNames += name
		val database = database(name)
		val projector = NyoraRoomBackupProjector(database, "test")
		val repository = NyoraRoomBackupRepository(
			database = database,
			availableSourceIds = { setOf(SOURCE_ID) },
			initialSnapshot = projector::snapshot,
			materializer = NyoraRoomPortableMaterializer(database),
		)
		val laterCategory = "ffffffff-ffff-ffff-ffff-ffffffffffff"
		val earlierCategory = "00000000-0000-0000-0000-000000000001"
		val mangaOne = NyoraBackupIdentity.mangaId(SOURCE_ID, "/one")
		val mangaTwo = NyoraBackupIdentity.mangaId(SOURCE_ID, "/two")
		val shaTrackingId = "anilist:sha256:9c1185a5c5e9fc54612808977ee8f548b2258d31"
		val timestamp = "2026-08-21T00:00:00.000Z"
		val incoming = NyoraBackupSnapshot(
			archiveId = "44444444-4444-4444-4444-444444444444",
			createdAt = timestamp,
			appVersion = "test",
			platform = "android-test",
			sources = listOf(NyoraBackupSource(SOURCE_ID, "r1", updatedAt = timestamp)),
			categories = listOf(
				NyoraBackupCategory(laterCategory, "Later", 0, timestamp),
				NyoraBackupCategory(earlierCategory, "__nyora_uncategorized__", 1, timestamp),
			),
			manga = listOf(
				NyoraBackupManga(mangaOne, SOURCE_ID, "/one", "One", timestamp),
				NyoraBackupManga(mangaTwo, SOURCE_ID, "/two", "Two", timestamp),
			),
			library = listOf(
				NyoraBackupLibrary(mangaOne, timestamp, listOf(laterCategory, earlierCategory)),
				NyoraBackupLibrary(mangaTwo, timestamp, emptyList()),
			),
			tracking = listOf(
				NyoraBackupTracking(shaTrackingId, mangaOne, "anilist", "reading", timestamp),
			),
		)

		repository.apply(NyoraBackupCodec.encode(incoming), NyoraRestoreMode.Replace)
		val projected = projector.snapshot()

		assertEquals(setOf(laterCategory, earlierCategory), projected.library.single { it.mangaId == mangaOne }.categoryIds.toSet())
		assertEquals(emptyList<String>(), projected.library.single { it.mangaId == mangaTwo }.categoryIds)
		assertTrue(projected.categories.any { it.id == earlierCategory && it.title == "__nyora_uncategorized__" })
		assertEquals(shaTrackingId, projected.tracking.single().id)
		assertEquals(2, database.getFavouritesDao().findAllForBackup().size)
		assertEquals(1, database.getScrobblingDao().findAllForBackup().size)
		assertEquals(2, database.getFavouriteCategoriesDao().findAll().size)
		assertEquals(2, database.getFavouriteCategoriesDao().findAllForSync().size)
		assertTrue(database.getFavouritesDao().findAll().any { it.manga.id == mangaTwo })
		assertTrue(database.getFavouritesDao().findAllForSync().none { it.manga.id == mangaTwo })
		assertTrue(database.getFavouritesDao().observeCategories(mangaTwo).first().isEmpty())
		assertTrue(
			database.getFavouritesDao().observeAll(ListSortOrder.UPDATED, emptySet(), 10).first()
				.any { it.manga.id == mangaTwo },
		)
		database.openHelper.readableDatabase.query(
			"SELECT COUNT(*) FROM favourite_categories WHERE title = '__nyora_uncategorized__'",
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals(1, cursor.getInt(0))
		}
		database.openHelper.readableDatabase.query(
			"SELECT c.show_in_lib FROM favourite_categories c JOIN nyora_backup_identity_map m ON CAST(c.category_id AS TEXT) = m.local_key WHERE m.kind = 'category' AND m.portable_id = '__nyora_uncategorized__'",
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals(1, cursor.getInt(0))
		}
		database.close()
	}

	@Test
	fun previewAndApplyReconcileLiveRoomAtTheTransactionBoundary() = runBlocking {
		val name = "nyora-backup-${UUID.randomUUID()}"
		databaseNames += name
		val database = database(name)
		val projector = NyoraRoomBackupProjector(database, "test")
		val events = mutableListOf<String>()
		val repository = NyoraRoomBackupRepository(
			database = database,
			availableSourceIds = { setOf(SOURCE_ID) },
			initialSnapshot = projector::snapshot,
			materializer = NyoraRoomPortableMaterializer(database),
			observerGate = object : NyoraBackupObserverGate {
				override suspend fun setSuppressed(suppressed: Boolean) {
					events += if (suppressed) "pause" else "resume"
				}
				override suspend fun reconcile() { events += "reconcile" }
			},
			now = { Instant.parse("2026-08-22T00:00:00.000Z") },
			newArchiveId = { UUID.fromString("33333333-3333-3333-3333-333333333333") },
		)
		val imported = snapshot("11111111-1111-1111-1111-111111111111", "Imported", "2026-08-20T00:00:00.000Z")
		repository.apply(NyoraBackupCodec.encode(imported), NyoraRestoreMode.Replace)
		events.clear()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE favourite_categories SET title = 'Edited locally' WHERE deleted_at = 0",
		)
		val ledgerBeforePreview = checkNotNull(database.getNyoraBackupLedgerDao().get()).archive
		val incoming = snapshot("22222222-2222-2222-2222-222222222222", "Older remote", "2026-08-21T00:00:00.000Z")

		val plan = repository.plan(NyoraBackupCodec.encode(incoming), NyoraRestoreMode.Merge)

		assertEquals("Edited locally", plan.preview.categories.single().title)
		assertEquals("2026-08-22T00:00:00.000Z", plan.preview.categories.single().updatedAt)
		assertArrayEquals(ledgerBeforePreview, checkNotNull(database.getNyoraBackupLedgerDao().get()).archive)
		assertTrue(events.isEmpty())
		repository.apply(NyoraBackupCodec.encode(incoming), NyoraRestoreMode.Merge)
		assertEquals(listOf("pause", "resume", "reconcile"), events)
		assertEquals("Edited locally", database.getFavouriteCategoriesDao().findAllForSync().single().title)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE favourite_categories SET deleted_at = ?",
			arrayOf(Instant.parse("2026-08-23T00:00:00.000Z").toEpochMilli()),
		)
		val deletePlan = repository.plan(NyoraBackupCodec.encode(incoming), NyoraRestoreMode.Merge)
		assertEquals("2026-08-23T00:00:00.000Z", deletePlan.preview.categories.single().deletedAt)
		repository.apply(NyoraBackupCodec.encode(incoming), NyoraRestoreMode.Merge)
		assertTrue(database.getFavouriteCategoriesDao().findAllForSync().isEmpty())
		database.close()
	}

	@Test
	fun replaceExcludesAbsentPortableMangaWithoutDeletingNonportableChildren() = runBlocking {
		val name = "nyora-backup-${UUID.randomUUID()}"
		databaseNames += name
		val database = database(name)
		val projector = NyoraRoomBackupProjector(database, "test")
		val repository = NyoraRoomBackupRepository(
			database = database,
			availableSourceIds = { setOf(SOURCE_ID) },
			initialSnapshot = projector::snapshot,
			materializer = NyoraRoomPortableMaterializer(database),
		)
		val timestamp = "2026-08-21T00:00:00.000Z"
		val mangaId = NyoraBackupIdentity.mangaId(SOURCE_ID, "/old")
		val populated = NyoraBackupSnapshot(
			archiveId = "55555555-5555-5555-5555-555555555555",
			createdAt = timestamp,
			appVersion = "test",
			platform = "android-test",
			sources = listOf(NyoraBackupSource(SOURCE_ID, "r1", updatedAt = timestamp)),
			manga = listOf(NyoraBackupManga(mangaId, SOURCE_ID, "/old", "Old", timestamp)),
			library = listOf(NyoraBackupLibrary(mangaId, timestamp, emptyList())),
		)
		repository.apply(NyoraBackupCodec.encode(populated), NyoraRestoreMode.Replace)
		assertEquals(1, database.getMangaDao().findAllForBackup().size)
		val sql = database.openHelper.writableDatabase
		sql.execSQL("INSERT INTO local_index (manga_id, path) VALUES (?, ?)", arrayOf(mangaId, "/download"))
		sql.execSQL("INSERT INTO preferences (manga_id, mode, cf_brightness, cf_contrast, cf_invert, cf_grayscale, cf_book, cf_multitone, title_override, cover_override, content_rating_override) VALUES (?, 1, 1, 1, 0, 0, 0, 0, NULL, NULL, NULL)", arrayOf(mangaId))
		sql.execSQL("INSERT INTO history (manga_id, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters) VALUES (?, 1, 1, '', 0, 0, 0, 0, 0)", arrayOf(mangaId))
		sql.execSQL("INSERT INTO stats (manga_id, started_at, duration, pages) VALUES (?, 1, 2, 3)", arrayOf(mangaId))
		val empty = populated.copy(
			archiveId = "77777777-7777-7777-7777-777777777777",
			manga = emptyList(),
		)

		repository.apply(NyoraBackupCodec.encode(empty), NyoraRestoreMode.Replace)

		assertEquals(emptyList<NyoraBackupManga>(), repository.readSnapshot().manga)
		assertTrue(database.getFavouritesDao().findAllForBackup().isEmpty())
		assertEquals(1, database.getMangaDao().findAllForBackup().size)
		listOf("local_index", "preferences", "stats").forEach { table ->
			sql.query("SELECT COUNT(*) FROM $table WHERE manga_id = ?", arrayOf(mangaId)).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals("$table must survive portable replacement", 1, cursor.getInt(0))
			}
		}
		database.close()
	}

	@Test
	fun restoringExistingHistoryUpdatesInPlaceWithoutDeletingStatistics() = runBlocking {
		val name = "nyora-backup-${UUID.randomUUID()}"
		databaseNames += name
		val database = database(name)
		val projector = NyoraRoomBackupProjector(database, "test")
		val repository = NyoraRoomBackupRepository(
			database = database,
			availableSourceIds = { setOf(SOURCE_ID) },
			initialSnapshot = projector::snapshot,
			materializer = NyoraRoomPortableMaterializer(database),
		)
		val timestamp = "2026-08-21T00:00:00.000Z"
		val mangaId = NyoraBackupIdentity.mangaId(SOURCE_ID, "/history")
		val snapshot = NyoraBackupSnapshot(
			archiveId = "88888888-8888-8888-8888-888888888888",
			createdAt = timestamp,
			appVersion = "test",
			platform = "android-test",
			sources = listOf(NyoraBackupSource(SOURCE_ID, "r1", updatedAt = timestamp)),
			manga = listOf(NyoraBackupManga(mangaId, SOURCE_ID, "/history", "History", timestamp)),
			history = listOf(NyoraBackupHistory(mangaId, page = 2, percent = 0.5, updatedAt = timestamp)),
		)
		repository.apply(NyoraBackupCodec.encode(snapshot), NyoraRestoreMode.Replace)
		val sql = database.openHelper.writableDatabase
		sql.execSQL("INSERT INTO stats (manga_id, started_at, duration, pages) VALUES (?, 1, 2, 3)", arrayOf(mangaId))

		repository.apply(NyoraBackupCodec.encode(snapshot), NyoraRestoreMode.Replace)

		sql.query("SELECT duration, pages FROM stats WHERE manga_id = ?", arrayOf(mangaId)).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals(2L, cursor.getLong(0))
			assertEquals(3, cursor.getInt(1))
		}
		database.close()
	}

	@Test
	fun mergeAndReplacePreserveNonportableCollectionRowsAndExcludeThemFromLedger() = runBlocking {
		val name = "nyora-backup-${UUID.randomUUID()}"
		databaseNames += name
		val database = database(name)
		val sql = database.openHelper.writableDatabase
		val localMangaId = "local-manga"
		val localCategoryId = 42
		sql.execSQL("INSERT INTO manga VALUES (?, 'Local title', 'Alt', '/local', '/local-public', 0.7, 0, 'SAFE', 'cover', 'large', 'ONGOING', 'Author', '{\"name\":\"LOCAL\"}', 'Local description', '[\"tag\"]', '[]', 3, 0.4)", arrayOf(localMangaId))
		sql.execSQL("INSERT INTO favourite_categories VALUES (?, 100, 7, 'Local category', 'ALPHABETIC', 1, 1, 0)", arrayOf(localCategoryId))
		sql.execSQL("INSERT INTO nyora_backup_identity_map VALUES ('category', '99999999-9999-9999-9999-999999999999', ?)", arrayOf(localCategoryId.toString()))
		sql.execSQL("INSERT INTO favourites VALUES (?, ?, 5, 1, 101, 0)", arrayOf(localMangaId, localCategoryId))
		sql.execSQL("INSERT INTO history VALUES (?, 102, 103, 'local-chapter', 4, 0.25, 0.5, 0, 9)", arrayOf(localMangaId))
		sql.execSQL("INSERT INTO bookmarks VALUES (?, 'local-page', 'local-chapter', 4, 8, 'image', 104, 0.5, 0)", arrayOf(localMangaId))
		sql.execSQL("INSERT INTO scrobblings VALUES (0, 991, ?, 123, 'READING', 4, 'note', 0.8)", arrayOf(localMangaId))
		sql.execSQL("INSERT INTO local_index VALUES (?, '/download/local')", arrayOf(localMangaId))
		sql.execSQL("INSERT INTO preferences VALUES (?, 2, 0.8, 1.2, 1, 0, 1, 1, 'Override', 'cover-override', 'SAFE')", arrayOf(localMangaId))
		sql.execSQL("INSERT INTO stats VALUES (?, 105, 600, 12)", arrayOf(localMangaId))
		val protectedQueries = linkedMapOf(
			"manga" to "SELECT * FROM manga WHERE manga_id = 'local-manga'",
			"category" to "SELECT * FROM favourite_categories WHERE category_id = 42",
			"favourite" to "SELECT * FROM favourites WHERE manga_id = 'local-manga'",
			"history" to "SELECT * FROM history WHERE manga_id = 'local-manga'",
			"bookmark" to "SELECT * FROM bookmarks WHERE manga_id = 'local-manga'",
			"tracking" to "SELECT * FROM scrobblings WHERE manga_id = 'local-manga'",
			"download" to "SELECT * FROM local_index WHERE manga_id = 'local-manga'",
			"preferences" to "SELECT * FROM preferences WHERE manga_id = 'local-manga'",
			"statistics" to "SELECT * FROM stats WHERE manga_id = 'local-manga'",
		)
		val before = protectedQueries.mapValues { dumpRows(sql.query(it.value)) }
		val repository = NyoraRoomBackupRepository(
			database = database,
			availableSourceIds = { setOf(SOURCE_ID) },
			initialSnapshot = NyoraRoomBackupProjector(database, "test")::snapshot,
			materializer = NyoraRoomPortableMaterializer(database),
		)
		val timestamp = "2026-08-21T00:00:00.000Z"
		val portableId = NyoraBackupIdentity.mangaId(SOURCE_ID, "/portable")
		val incoming = NyoraBackupSnapshot(
			archiveId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
			createdAt = timestamp,
			appVersion = "test",
			platform = "android-test",
			sources = listOf(NyoraBackupSource(SOURCE_ID, "r1", updatedAt = timestamp)),
			manga = listOf(NyoraBackupManga(portableId, SOURCE_ID, "/portable", "Portable", timestamp)),
		)

		repository.apply(NyoraBackupCodec.encode(incoming), NyoraRestoreMode.Merge)
		assertEquals(before, protectedQueries.mapValues { dumpRows(sql.query(it.value)) })
		assertTrue(database.getFavouriteCategoriesDao().findAll().any { it.categoryId == localCategoryId })
		assertProtectedRowsExcluded(repository.readSnapshot(), localMangaId, "99999999-9999-9999-9999-999999999999")

		repository.apply(
			NyoraBackupCodec.encode(incoming.copy(archiveId = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff")),
			NyoraRestoreMode.Replace,
		)
		assertEquals(before, protectedQueries.mapValues { dumpRows(sql.query(it.value)) })
		assertTrue(database.getFavouriteCategoriesDao().findAll().any { it.categoryId == localCategoryId })
		assertProtectedRowsExcluded(repository.readSnapshot(), localMangaId, "99999999-9999-9999-9999-999999999999")
		database.close()
	}

	private fun assertProtectedRowsExcluded(snapshot: NyoraBackupSnapshot, localMangaId: String, categoryId: String) {
		assertTrue(snapshot.manga.none { it.id == localMangaId })
		assertTrue(snapshot.categories.none { it.id == categoryId })
		assertTrue(snapshot.library.none { it.mangaId == localMangaId })
		assertTrue(snapshot.history.none { it.mangaId == localMangaId })
		assertTrue(snapshot.bookmarks.none { it.mangaId == localMangaId })
		assertTrue(snapshot.tracking.none { it.mangaId == localMangaId })
	}

	private fun dumpRows(cursor: Cursor): List<List<Any?>> = cursor.use {
		buildList {
			while (it.moveToNext()) add((0 until it.columnCount).map { column ->
				when (it.getType(column)) {
					Cursor.FIELD_TYPE_NULL -> null
					Cursor.FIELD_TYPE_INTEGER -> it.getLong(column)
					Cursor.FIELD_TYPE_FLOAT -> it.getDouble(column)
					Cursor.FIELD_TYPE_BLOB -> it.getBlob(column).toList()
					else -> it.getString(column)
				}
			})
		}
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
