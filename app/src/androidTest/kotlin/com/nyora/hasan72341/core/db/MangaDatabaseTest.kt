package com.nyora.hasan72341.core.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.nyora.hasan72341.core.db.migrations.Migration32To33
import com.nyora.hasan72341.core.db.migrations.Migration33To34

@RunWith(AndroidJUnit4::class)
class MangaDatabaseTest {

	@get:Rule
	val helper: MigrationTestHelper = MigrationTestHelper(
		InstrumentationRegistry.getInstrumentation(),
		MangaDatabase::class.java,
	)

	private val migrations = getDatabaseMigrations(InstrumentationRegistry.getInstrumentation().targetContext)

	@Test
	fun versions() {
		assertEquals(1, migrations.first().startVersion)
		repeat(migrations.size) { i ->
			assertEquals(i + 1, migrations[i].startVersion)
			assertEquals(i + 2, migrations[i].endVersion)
		}
		assertEquals(DATABASE_VERSION, migrations.last().endVersion)
	}

	@Test
	fun migrateAll() {
		helper.createDatabase(TEST_DB, 1).close()
		for (migration in migrations) {
			helper.runMigrationsAndValidate(
				TEST_DB,
				migration.endVersion,
				true,
				migration,
			).close()
		}
	}

	@Test
	fun prePopulate() {
		helper.createDatabase(TEST_DB, DATABASE_VERSION).use {
			DatabasePrePopulateCallback().onCreate(it)
		}
	}

	@Test
	fun migration33RenamesJavascriptSourcesAndPurgesRetiredPlaceholders() {
		val db = helper.createDatabase(TEST_DB, 32)
		db.execSQL("INSERT INTO sources VALUES ('JS_MANGADEX', 1, 0, 0, 0, 1, 0)")
		db.execSQL("INSERT INTO sources VALUES ('MIHON_123', 1, 1, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO sources VALUES ('456', 1, 2, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO manga VALUES ('js', 'JS', NULL, '/', '/', 0, 0, NULL, '', NULL, NULL, NULL, '{\"name\":\"JS_MANGADEX\"}', '', '[]', '[]', 0, 0)")
		db.execSQL("INSERT INTO manga VALUES ('bare', 'Bare', NULL, '/', '/', 0, 0, NULL, '', NULL, NULL, NULL, 'JS_BANANASCAN_COM', '', '[]', '[]', 0, 0)")
		db.execSQL("INSERT INTO manga VALUES ('mihon', 'Mihon', NULL, '/', '/', 0, 0, NULL, '', NULL, NULL, NULL, '{\"name\":\"MIHON_123\"}', '', '[]', '[]', 0, 0)")
		db.close()

		val migrated = helper.runMigrationsAndValidate(TEST_DB, 33, true, Migration32To33())
		migrated.query("SELECT source FROM sources ORDER BY source").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals("data:mangadex", cursor.getString(0))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, source FROM manga ORDER BY manga_id").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals("bare", cursor.getString(0))
			assertEquals("data:bananascan-com", cursor.getString(1))
			assertEquals(true, cursor.moveToNext())
			assertEquals("js", cursor.getString(0))
			assertEquals("{\"name\":\"data:mangadex\"}", cursor.getString(1))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.close()
	}

	@Test
	fun migration33KeepsCanonicalSourceStateAndLeavesMangaIdsAlone() {
		val db = helper.createDatabase(TEST_DB, 32)
		db.execSQL("INSERT INTO sources VALUES ('JS_MANGADEX', 0, 1, 10, 11, 0, 1)")
		db.execSQL("INSERT INTO sources VALUES ('data:mangadex', 1, 99, 20, 21, 1, 2)")
		db.execSQL("INSERT INTO manga VALUES ('legacy', 'Legacy', NULL, '/same', '/same', 0, 0, NULL, '', NULL, NULL, NULL, '{\"name\":\"JS_MANGADEX\"}', '', '[]', '[]', 0, 0)")
		db.execSQL("INSERT INTO history VALUES ('legacy', 1, 2, '', 0, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO local_index VALUES ('legacy', '/kept-download')")
		db.close()

		val migrated = helper.runMigrationsAndValidate(TEST_DB, 33, true, Migration32To33())
		migrated.query("SELECT enabled, sort_key, added_in, used_at, pinned, cf_state FROM sources WHERE source = 'data:mangadex'").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(listOf(1, 99, 20, 21, 1, 2), (0..5).map(cursor::getInt))
		}
		migrated.query("SELECT manga_id, title, source FROM manga").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals("legacy", cursor.getString(0))
			assertEquals("Legacy", cursor.getString(1))
			assertEquals("{\"name\":\"data:mangadex\"}", cursor.getString(2))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, created_at FROM history").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals("legacy", cursor.getString(0))
			assertEquals(1L, cursor.getLong(1))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, path FROM local_index").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals("legacy", cursor.getString(0))
			assertEquals("/kept-download", cursor.getString(1))
		}
		migrated.close()
	}

	@Test
	fun migration34RenamesSourcesAndRekeysOnlyTheRowsWhoseHashChanges() {
		val db = helper.createDatabase(TEST_DB, 33)
		db.execSQL("INSERT INTO sources VALUES ('data:manganato-gg', 1, 0, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO sources VALUES ('data:bananascan-com', 1, 1, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO manga VALUES ('$MANGANATO_GG_ID', 'Peak', NULL, '/manga/martial-peak', '/manga/martial-peak', 0, 0, NULL, '', NULL, NULL, NULL, '{\"name\":\"data:manganato-gg\"}', '', '[]', '[]', 0, 0)")
		db.execSQL("INSERT INTO manga VALUES ('$BANANASCAN_ID', 'Banana', NULL, '/manga/x', '/manga/x', 0, 0, NULL, '', NULL, NULL, NULL, 'data:bananascan-com', '', '[]', '[]', 0, 0)")
		db.execSQL("INSERT INTO history VALUES ('$MANGANATO_GG_ID', 7, 8, '', 0, 0, 0, 0, 0)")
		db.close()

		val migrated = helper.runMigrationsAndValidate(TEST_DB, 34, true, Migration33To34())
		migrated.query("SELECT source FROM sources ORDER BY source").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals("data:bananascan_com", cursor.getString(0))
			assertEquals(true, cursor.moveToNext())
			assertEquals("data:manganato", cursor.getString(0))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, source FROM manga ORDER BY title").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(BANANASCAN_ID, cursor.getString(0))
			assertEquals("data:bananascan_com", cursor.getString(1))
			assertEquals(true, cursor.moveToNext())
			assertEquals(MANGANATO_ID, cursor.getString(0))
			assertEquals("{\"name\":\"data:manganato\"}", cursor.getString(1))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, created_at FROM history").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(MANGANATO_ID, cursor.getString(0))
			assertEquals(7L, cursor.getLong(1))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.close()
	}

	private companion object {

		const val TEST_DB = "test-db"

		/** `legacyMangaId("MANGANATO_GG", "/manga/martial-peak")`, the id the retired row was written with. */
		const val MANGANATO_GG_ID = "-1040474111546458730"

		/** `legacyMangaId("MANGANATO", "/manga/martial-peak")`, the id the renamed row hashes to. */
		const val MANGANATO_ID = "7296953528379950235"

		/** `legacyMangaId("BANANASCAN_COM", "/manga/x")`, unchanged by a delimiter-only rename. */
		const val BANANASCAN_ID = "-9055357649434380553"
	}
}
