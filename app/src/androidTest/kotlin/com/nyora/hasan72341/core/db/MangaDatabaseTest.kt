package com.nyora.hasan72341.core.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
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

	/**
	 * A v2.1.6 database: the JavaScript enum, whose rows already carry the shared legacy manga id,
	 * plus the Mihon and numeric identities that have no successor to be renamed to.
	 */
	@Test
	fun migration33RenamesJavascriptSourcesAndPurgesRetiredPlaceholders() {
		val db = helper.createDatabase(TEST_DB, 32)
		db.execSQL("INSERT INTO sources VALUES ('JS_MANGADEX', 1, 0, 0, 0, 1, 0)")
		db.execSQL("INSERT INTO sources VALUES ('MIHON_123', 1, 1, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO sources VALUES ('456', 1, 2, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO manga VALUES ('$MANGADEX_ROOT_ID', 'JS', NULL, '/', '/', 0, 0, NULL, '', NULL, NULL, NULL, '{\"name\":\"JS_MANGADEX\"}', '', '[]', '[]', 0, 0)")
		db.execSQL("INSERT INTO manga VALUES ('$BANANASCAN_ROOT_ID', 'Bare', NULL, '/', '/', 0, 0, NULL, '', NULL, NULL, NULL, 'JS_BANANASCAN_COM', '', '[]', '[]', 0, 0)")
		db.execSQL("INSERT INTO manga VALUES ('mihon', 'Mihon', NULL, '/', '/', 0, 0, NULL, '', NULL, NULL, NULL, '{\"name\":\"MIHON_123\"}', '', '[]', '[]', 0, 0)")
		db.close()

		val migrated = helper.runMigrationsAndValidate(TEST_DB, 33, true, Migration32To33())
		migrated.query("SELECT source FROM sources ORDER BY source").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals("data:mangadex", cursor.getString(0))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, source FROM manga ORDER BY title").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(BANANASCAN_ROOT_ID, cursor.getString(0))
			assertEquals("data:bananascan_com", cursor.getString(1))
			assertEquals(true, cursor.moveToNext())
			assertEquals(MANGADEX_ROOT_ID, cursor.getString(0))
			assertEquals("{\"name\":\"data:mangadex\"}", cursor.getString(1))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.close()
	}

	@Test
	fun migration33KeepsCanonicalSourceStateAndLegacyMangaIds() {
		val db = helper.createDatabase(TEST_DB, 32)
		db.execSQL("INSERT INTO sources VALUES ('JS_MANGADEX', 0, 1, 10, 11, 0, 1)")
		db.execSQL("INSERT INTO sources VALUES ('data:mangadex', 1, 99, 20, 21, 1, 2)")
		db.execSQL("INSERT INTO manga VALUES ('$MANGADEX_SAME_ID', 'Legacy', NULL, '/same', '/same', 0, 0, NULL, '', NULL, NULL, NULL, '{\"name\":\"JS_MANGADEX\"}', '', '[]', '[]', 0, 0)")
		db.execSQL("INSERT INTO history VALUES ('$MANGADEX_SAME_ID', 1, 2, '', 0, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO local_index VALUES ('$MANGADEX_SAME_ID', '/kept-download')")
		db.close()

		val migrated = helper.runMigrationsAndValidate(TEST_DB, 33, true, Migration32To33())
		migrated.query("SELECT enabled, sort_key, added_in, used_at, pinned, cf_state FROM sources WHERE source = 'data:mangadex'").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(listOf(1, 99, 20, 21, 1, 2), (0..5).map(cursor::getInt))
		}
		migrated.query("SELECT manga_id, title, source FROM manga").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(MANGADEX_SAME_ID, cursor.getString(0))
			assertEquals("Legacy", cursor.getString(1))
			assertEquals("{\"name\":\"data:mangadex\"}", cursor.getString(2))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, created_at FROM history").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(MANGADEX_SAME_ID, cursor.getString(0))
			assertEquals(1L, cursor.getLong(1))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, path FROM local_index").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(MANGADEX_SAME_ID, cursor.getString(0))
			assertEquals("/kept-download", cursor.getString(1))
		}
		migrated.close()
	}

	@Test
	fun migration34RenamesSourcesAndRekeysOnlyTheRowsWhoseHashChanges() {
		val db = helper.createDatabase(TEST_DB, 33)
		db.execSQL("INSERT INTO sources VALUES ('data:manganato-gg', 1, 0, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO sources VALUES ('data:bananascan-com', 1, 1, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO manga VALUES ('$MANGANATO_GG_ID', 'Peak', NULL, '/manga/martial-peak', '/manga/martial-peak', 0, 0, NULL, '', NULL, NULL, NULL, '{\"name\":\"data:manganato-gg\"}', '', '[]', '${chaptersBlob(MANGANATO_GG_CHAPTER_ID, "/chapter/martial-peak-1")}', 0, 0)")
		db.execSQL("INSERT INTO manga VALUES ('$BANANASCAN_ID', 'Banana', NULL, '/manga/x', '/manga/x', 0, 0, NULL, '', NULL, NULL, NULL, 'data:bananascan-com', '', '[]', '${chaptersBlob(BANANASCAN_CHAPTER_ID, "/manga/x/1")}', 0, 0)")
		db.execSQL("INSERT INTO history VALUES ('$MANGANATO_GG_ID', 7, 8, '$MANGANATO_GG_CHAPTER_ID', 3, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO bookmarks VALUES ('$MANGANATO_GG_ID', 'page-1', '$MANGANATO_GG_CHAPTER_ID', 3, 0, '', 0, 0, 0)")
		db.execSQL("INSERT INTO tracks VALUES ('$MANGANATO_GG_ID', '$MANGANATO_GG_CHAPTER_ID', 2, 0, 0, 0, NULL)")
		db.close()

		val migrated = helper.runMigrationsAndValidate(TEST_DB, 34, true, Migration33To34())
		migrated.query("SELECT source FROM sources ORDER BY source").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals("data:bananascan_com", cursor.getString(0))
			assertEquals(true, cursor.moveToNext())
			assertEquals("data:manganato", cursor.getString(0))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, source, chapters FROM manga ORDER BY title").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(BANANASCAN_ID, cursor.getString(0))
			assertEquals("data:bananascan_com", cursor.getString(1))
			assertEquals(BANANASCAN_CHAPTER_ID, chapterIdOf(cursor.getString(2)))
			assertEquals(true, cursor.moveToNext())
			assertEquals(MANGANATO_ID, cursor.getString(0))
			assertEquals("{\"name\":\"data:manganato\"}", cursor.getString(1))
			assertEquals(MANGANATO_CHAPTER_ID, chapterIdOf(cursor.getString(2)))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, created_at, chapter_id FROM history").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(MANGANATO_ID, cursor.getString(0))
			assertEquals(7L, cursor.getLong(1))
			assertEquals(MANGANATO_CHAPTER_ID, cursor.getString(2))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, chapter_id FROM bookmarks").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(MANGANATO_ID, cursor.getString(0))
			assertEquals(MANGANATO_CHAPTER_ID, cursor.getString(1))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, last_chapter_id FROM tracks").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(MANGANATO_ID, cursor.getString(0))
			assertEquals(MANGANATO_CHAPTER_ID, cursor.getString(1))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.close()
	}

	/**
	 * A database written by the shipped v2.6-v2.7.3 builds: `DD_` source names in the catalogue's own
	 * casing, JSON-wrapped in `manga.source`, and manga ids the shipped adapters invented - the
	 * engine's relative href for a data-driven row, `<source>|<url>` for the native MangaFire route.
	 * Both have to land on the catalogue spelling and the shared legacy id, taking their chapter ids
	 * and the rows that point at them along, while a v2.1.6 row on the same database is left where it
	 * already is.
	 */
	@Test
	fun migration34UpgradesDatabasesWrittenByTheShippedBuilds() {
		val db = helper.createDatabase(TEST_DB, 32)
		db.execSQL("INSERT INTO sources VALUES ('DD_HIPERDEX', 1, 0, 5, 6, 1, 0)")
		db.execSQL("INSERT INTO sources VALUES ('JS_BANANASCAN_COM', 1, 1, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO manga VALUES ('/manga/x', 'Hiperdex', NULL, '/manga/x', '/manga/x', 0, 0, NULL, '', NULL, NULL, NULL, '{\"name\":\"DD_HIPERDEX\"}', '', '[]', '${chaptersBlob("/manga/x/chapter-1", "/manga/x/chapter-1")}', 0, 0)")
		db.execSQL("INSERT INTO manga VALUES ('DD_MANGAFIRE_EN|/manga/y', 'MangaFire', NULL, '/manga/y', '/manga/y', 0, 0, NULL, '', NULL, NULL, NULL, '{\"name\":\"DD_MANGAFIRE_EN\"}', '', '[]', '${chaptersBlob("DD_MANGAFIRE_EN|chapter|/manga/y/en/chapter-1", "/manga/y/en/chapter-1")}', 0, 0)")
		db.execSQL("INSERT INTO manga VALUES ('$BANANASCAN_B_ID', 'Banana', NULL, '/b', '/b', 0, 0, NULL, '', NULL, NULL, NULL, 'JS_BANANASCAN_COM', '', '[]', '[]', 0, 0)")
		db.execSQL("INSERT INTO history VALUES ('/manga/x', 7, 8, '/manga/x/chapter-1', 3, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO bookmarks VALUES ('/manga/x', 'page-1', '/manga/x/chapter-1', 3, 0, '', 0, 0, 0)")
		db.execSQL("INSERT INTO local_index VALUES ('DD_MANGAFIRE_EN|/manga/y', '/kept-download')")
		db.close()

		val migrated = helper.runMigrationsAndValidate(TEST_DB, 34, true, Migration32To33(), Migration33To34())
		migrated.query("SELECT source, added_in FROM sources ORDER BY source").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals("data:bananascan_com", cursor.getString(0))
			assertEquals(true, cursor.moveToNext())
			assertEquals("data:hiperdex", cursor.getString(0))
			assertEquals(5L, cursor.getLong(1))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, source, chapters FROM manga ORDER BY title").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(BANANASCAN_B_ID, cursor.getString(0))
			assertEquals("data:bananascan_com", cursor.getString(1))
			assertEquals(true, cursor.moveToNext())
			assertEquals(HIPERDEX_ID, cursor.getString(0))
			assertEquals("{\"name\":\"data:hiperdex\"}", cursor.getString(1))
			assertEquals(HIPERDEX_CHAPTER_ID, chapterIdOf(cursor.getString(2)))
			assertEquals(true, cursor.moveToNext())
			assertEquals(MANGAFIRE_ID, cursor.getString(0))
			assertEquals("{\"name\":\"data:mangafire_en\"}", cursor.getString(1))
			assertEquals(MANGAFIRE_CHAPTER_ID, chapterIdOf(cursor.getString(2)))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, created_at, chapter_id FROM history").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(HIPERDEX_ID, cursor.getString(0))
			assertEquals(7L, cursor.getLong(1))
			assertEquals(HIPERDEX_CHAPTER_ID, cursor.getString(2))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, chapter_id FROM bookmarks").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(HIPERDEX_ID, cursor.getString(0))
			assertEquals(HIPERDEX_CHAPTER_ID, cursor.getString(1))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, path FROM local_index").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(MANGAFIRE_ID, cursor.getString(0))
			assertEquals("/kept-download", cursor.getString(1))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.close()
	}

	/**
	 * The two ways a shipped row meets a row that already holds the canonical id: a v2.1.6 install
	 * upgraded to v2.6, which left a hashed row and an engine-href row for the same manga, and one
	 * v2.6 install that used both the native and the data-driven route for the same site. The
	 * canonical row keeps its own `chapters` blob, so the alias has to carry its history and
	 * bookmarks over as hashed chapter ids or the resume position stops resolving.
	 */
	@Test
	fun migration33MergesShippedAliasesOntoAnExistingCanonicalRow() {
		val db = helper.createDatabase(TEST_DB, 32)
		db.execSQL("INSERT INTO sources VALUES ('JS_HIPERDEX', 1, 0, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO sources VALUES ('DD_HIPERDEX', 1, 1, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO sources VALUES ('DD_MANGAFIRE_EN', 1, 2, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO manga VALUES ('$HIPERDEX_ID', 'Hiperdex', NULL, '/manga/x', '/manga/x', 0, 0, NULL, '', NULL, NULL, NULL, '{\"name\":\"JS_HIPERDEX\"}', '', '[]', '${chaptersBlob(HIPERDEX_CHAPTER_ID, "/manga/x/chapter-1")}', 0, 0)")
		db.execSQL("INSERT INTO manga VALUES ('/manga/x', 'Hiperdex alias', NULL, '/manga/x', '/manga/x', 0, 0, NULL, '', NULL, NULL, NULL, '{\"name\":\"DD_HIPERDEX\"}', '', '[]', '${chaptersBlob("/manga/x/chapter-1", "/manga/x/chapter-1")}', 0, 0)")
		db.execSQL("INSERT INTO manga VALUES ('DD_MANGAFIRE_EN|/manga/y', 'MangaFire', NULL, '/manga/y', '/manga/y', 0, 0, NULL, '', NULL, NULL, NULL, '{\"name\":\"DD_MANGAFIRE_EN\"}', '', '[]', '${chaptersBlob("DD_MANGAFIRE_EN|chapter|/manga/y/en/chapter-1", "/manga/y/en/chapter-1")}', 0, 0)")
		db.execSQL("INSERT INTO manga VALUES ('/manga/y', 'MangaFire', NULL, '/manga/y', '/manga/y', 0, 0, NULL, '', NULL, NULL, NULL, '{\"name\":\"DD_MANGAFIRE_EN\"}', '', '[]', '${chaptersBlob("/manga/y/en/chapter-1", "/manga/y/en/chapter-1")}', 0, 0)")
		db.execSQL("INSERT INTO history VALUES ('/manga/x', 7, 8, '/manga/x/chapter-1', 3, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO bookmarks VALUES ('/manga/x', 'page-1', '/manga/x/chapter-1', 3, 0, '', 0, 0, 0)")
		db.execSQL("INSERT INTO history VALUES ('/manga/y', 9, 10, '/manga/y/en/chapter-1', 4, 0, 0, 0, 0)")
		db.execSQL("INSERT INTO tracks VALUES ('/manga/y', '/manga/y/en/chapter-1', 2, 0, 0, 0, NULL)")
		db.close()

		val migrated = helper.runMigrationsAndValidate(TEST_DB, 34, true, Migration32To33(), Migration33To34())
		migrated.query("SELECT manga_id, source, chapters FROM manga ORDER BY title").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(HIPERDEX_ID, cursor.getString(0))
			assertEquals("{\"name\":\"data:hiperdex\"}", cursor.getString(1))
			assertEquals(HIPERDEX_CHAPTER_ID, chapterIdOf(cursor.getString(2)))
			assertEquals(true, cursor.moveToNext())
			assertEquals(MANGAFIRE_ID, cursor.getString(0))
			assertEquals("{\"name\":\"data:mangafire_en\"}", cursor.getString(1))
			assertEquals(MANGAFIRE_CHAPTER_ID, chapterIdOf(cursor.getString(2)))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, created_at, chapter_id FROM history ORDER BY created_at").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(HIPERDEX_ID, cursor.getString(0))
			assertEquals(7L, cursor.getLong(1))
			assertEquals(HIPERDEX_CHAPTER_ID, cursor.getString(2))
			assertEquals(true, cursor.moveToNext())
			assertEquals(MANGAFIRE_ID, cursor.getString(0))
			assertEquals(9L, cursor.getLong(1))
			assertEquals(MANGAFIRE_CHAPTER_ID, cursor.getString(2))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, chapter_id FROM bookmarks").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(HIPERDEX_ID, cursor.getString(0))
			assertEquals(HIPERDEX_CHAPTER_ID, cursor.getString(1))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.query("SELECT manga_id, last_chapter_id FROM tracks").use { cursor ->
			assertEquals(true, cursor.moveToFirst())
			assertEquals(MANGAFIRE_ID, cursor.getString(0))
			assertEquals(MANGAFIRE_CHAPTER_ID, cursor.getString(1))
			assertEquals(false, cursor.moveToNext())
		}
		migrated.close()
	}

	private fun chapterIdOf(chapters: String): String = JSONArray(chapters).getJSONObject(0).getString("id")

	private companion object {

		const val TEST_DB = "test-db"

		fun chaptersBlob(id: String, url: String): String =
			"""[{"id":"$id","title":"Chapter 1","number":1.0,"volume":0,"url":"$url","uploadDate":0}]"""

		/** `legacyMangaId("MANGANATO_GG", "/manga/martial-peak")`, the id the retired row was written with. */
		const val MANGANATO_GG_ID = "-1040474111546458730"

		/** `legacyMangaId("MANGANATO", "/manga/martial-peak")`, the id the renamed row hashes to. */
		const val MANGANATO_ID = "7296953528379950235"

		/** `legacyMangaId("BANANASCAN_COM", "/manga/x")`, unchanged by a delimiter-only rename. */
		const val BANANASCAN_ID = "-9055357649434380553"

		/** `legacyMangaId("MANGANATO_GG", " chapter /chapter/martial-peak-1")`, the retired stamp. */
		const val MANGANATO_GG_CHAPTER_ID = "-6598312688466032814"

		/** `legacyMangaId("MANGANATO", " chapter /chapter/martial-peak-1")`, what the runtime produces. */
		const val MANGANATO_CHAPTER_ID = "-5537510363614834579"

		/** `legacyMangaId("BANANASCAN_COM", " chapter /manga/x/1")`, unchanged by the rename. */
		const val BANANASCAN_CHAPTER_ID = "-3293541545326521030"

		/** `legacyMangaId("MANGADEX", "/")`, the id a v2.1.6 install already stamped. */
		const val MANGADEX_ROOT_ID = "-4289199082712012185"

		/** `legacyMangaId("MANGADEX", "/same")`, likewise already canonical. */
		const val MANGADEX_SAME_ID = "-3837397259635318963"

		/** `legacyMangaId("BANANASCAN_COM", "/")`, likewise already canonical. */
		const val BANANASCAN_ROOT_ID = "-5433525588274807002"

		/** `legacyMangaId("BANANASCAN_COM", "/b")`, likewise already canonical. */
		const val BANANASCAN_B_ID = "-2418596573133052420"

		/** `legacyMangaId("HIPERDEX", "/manga/x")`, replacing the engine href the shipped build stored. */
		const val HIPERDEX_ID = "5371261123069218510"

		/** `legacyMangaId("HIPERDEX", " chapter /manga/x/chapter-1")`, replacing the engine chapter id. */
		const val HIPERDEX_CHAPTER_ID = "-3104756316653850237"

		/** `legacyMangaId("MANGAFIRE_EN", "/manga/y")`, replacing the shipped `<source>|<url>` id. */
		const val MANGAFIRE_ID = "-1794076043248338830"

		/** `legacyMangaId("MANGAFIRE_EN", " chapter /manga/y/en/chapter-1")`, replacing the piped one. */
		const val MANGAFIRE_CHAPTER_ID = "7678141027855688635"
	}
}
