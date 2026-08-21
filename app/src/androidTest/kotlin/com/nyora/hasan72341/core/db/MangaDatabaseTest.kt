package com.nyora.hasan72341.core.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.nyora.hasan72341.core.db.migrations.Migration32To33

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
	fun migration33CanonicalizesJavascriptAliasesAndPurgesRetiredPlaceholders() {
		val db = helper.createDatabase(TEST_DB, 32)
		db.execSQL("INSERT INTO sources VALUES ('JS_MANGADEX', 1, 0, 0, 0, 1, 0)")
		db.execSQL("INSERT INTO sources VALUES ('MIHON_123', 1, 1, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO sources VALUES ('456', 1, 2, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO manga VALUES ('js', 'JS', NULL, '/', '/', 0, 0, NULL, '', NULL, NULL, NULL, '{\"name\":\"JS_MANGADEX\"}', '', '[]', '[]', 0, 0)")
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
			assertEquals("75a88ec1ec756eda79dc155156c04d17fbe51a25e9237bc3569e48967ac9d4eb", cursor.getString(0))
			assertEquals("{\"name\":\"data:mangadex\"}", cursor.getString(1))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.close()
	}

	@Test
	fun migration33KeepsCanonicalSourceStateAndRepointsAliasMangaChildrenCollisionSafely() {
		val canonicalId = "c5b126166cb0895a0210e9cbfc175c9a3faf351c94340e2177448cc22b9b98b7"
		val db = helper.createDatabase(TEST_DB, 32)
		db.execSQL("INSERT INTO sources VALUES ('JS_MANGADEX', 0, 1, 10, 11, 0, 1)")
		db.execSQL("INSERT INTO sources VALUES ('data:mangadex', 1, 99, 20, 21, 1, 2)")
		db.execSQL("INSERT INTO manga VALUES ('legacy', 'Legacy', NULL, '/same', '/same', 0, 0, NULL, '', NULL, NULL, NULL, '{\"name\":\"JS_MANGADEX\"}', '', '[]', '[]', 0, 0)")
		db.execSQL("INSERT INTO manga VALUES ('$canonicalId', 'Canonical', NULL, '/same', '/same', 0, 0, NULL, '', NULL, NULL, NULL, '{\"name\":\"data:mangadex\"}', '', '[]', '[]', 0, 0)")
		db.execSQL("INSERT INTO history VALUES ('legacy', 1, 2, '', 0, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO history VALUES ('$canonicalId', 10, 20, '', 0, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO local_index VALUES ('legacy', '/kept-download')")
		db.close()

		val migrated = helper.runMigrationsAndValidate(TEST_DB, 33, true, Migration32To33())
		migrated.query("SELECT enabled, sort_key, added_in, used_at, pinned, cf_state FROM sources WHERE source = 'data:mangadex'").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(listOf(1, 99, 20, 21, 1, 2), (0..5).map(cursor::getInt))
		}
		migrated.query("SELECT manga_id, title, source FROM manga").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(canonicalId, cursor.getString(0))
			assertEquals("Canonical", cursor.getString(1))
			assertEquals("{\"name\":\"data:mangadex\"}", cursor.getString(2))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, created_at FROM history").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(canonicalId, cursor.getString(0))
			assertEquals(10L, cursor.getLong(1))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, path FROM local_index").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(canonicalId, cursor.getString(0))
			assertEquals("/kept-download", cursor.getString(1))
		}
		migrated.close()
	}

	private companion object {

		const val TEST_DB = "test-db"
	}
}
