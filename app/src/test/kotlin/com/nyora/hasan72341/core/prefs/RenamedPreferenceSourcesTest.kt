package com.nyora.hasan72341.core.prefs

import com.nyora.hasan72341.core.parser.datadriven.CatalogueCache
import com.nyora.hasan72341.core.parser.datadriven.DataDrivenCatalogue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenamedPreferenceSourcesTest {

	@get:Rule
	val folder = TemporaryFolder()

	@Test
	fun parserPreferenceFilesFollowTheSameIdentityUpgradeAsStoredSources() {
		val catalogue = DataDrivenCatalogue(
			bundledAsset = { javaClass.getResource("/datadriven/catalogue-sample.json")!!.readBytes() },
			cache = CatalogueCache(folder.root),
			reportError = { throw it },
		)
		val moves = renamedPreferenceSources(
			listOf("HIPERDEX.xml", "parser:KOMIKZOID.xml", "MANGAFIRE_EN.xml",
				"ANILIST.xml", "LOCAL.xml", "MIHON_123.xml", "data:hiperdex.xml"),
			catalogue,
		).toMap()
		assertEquals("data:hiperdex", moves["HIPERDEX"])
		assertEquals("data:komikzoid", moves["parser:KOMIKZOID"])
		assertEquals("data:mangafire_en", moves["MANGAFIRE_EN"])
		for (untouched in listOf("ANILIST", "LOCAL", "MIHON_123", "data:hiperdex")) {
			assertFalse(moves.containsKey(untouched))
		}
	}

	@Test
	fun parserPreferenceFilesAreNotGuessedWithoutACatalogue() {
		val moves = renamedPreferenceSources(
			listOf("HIPERDEX.xml", "parser:HIPERDEX.xml", "DD_HIPERDEX.xml"),
			catalogue = null,
		).toMap()
		assertFalse(moves.containsKey("HIPERDEX"))
		assertFalse(moves.containsKey("parser:HIPERDEX"))
		assertEquals("data:hiperdex", moves["DD_HIPERDEX"])
	}


	/**
	 * A v2.6-v2.7.3 install keeps its per-source settings in `DD_<catalogue id>.xml` and a v2.1.6
	 * install in `JS_<ID>.xml`. Both spellings have to move onto the `data:` file the running build
	 * reads, or a configured mirror domain or user agent comes up as its default after the upgrade.
	 */
	@Test
	fun shippedSpellingsMoveOntoTheirCanonicalDataFile() {
		val moves = renamedPreferenceSources(
			listOf(
				"DD_HIPERDEX.xml",
				"DD_ASURASCANS_US.xml",
				"JS_BANANASCAN_COM.xml",
				"JS_MANGANATO_GG.xml",
			),
		).toMap()
		assertEquals("data:hiperdex", moves["DD_HIPERDEX"])
		assertEquals("data:asurascans", moves["DD_ASURASCANS_US"])
		assertEquals("data:bananascan_com", moves["JS_BANANASCAN_COM"])
		assertEquals("data:manganato", moves["JS_MANGANATO_GG"])
	}

	/** The schema 34 renames keep moving whether or not the directory listing is readable. */
	@Test
	fun schemaRenamesAreStillMigrated() {
		val moves = renamedPreferenceSources(emptyList()).toMap()
		assertEquals("data:mangafire_en", moves["data:mangafire-en"])
		assertEquals("data:manganato", moves["data:manganato-gg"])
	}

	/** Everything else in `shared_prefs` belongs to the app, not to a retired source spelling. */
	@Test
	fun unrelatedPreferenceFilesAreLeftAlone() {
		val moves = renamedPreferenceSources(
			listOf(
				"com.nyora.hasan72341.debug_preferences.xml",
				"data:manganato.xml",
				"DD_HIPERDEX",
				"LOCAL.xml",
				"MIHON_123.xml",
			),
		).map { it.first }
		assertFalse(moves.contains("com.nyora.hasan72341.debug_preferences"))
		assertFalse(moves.contains("data:manganato"))
		assertFalse(moves.contains("DD_HIPERDEX"))
		assertFalse(moves.contains("LOCAL"))
		assertFalse(moves.contains("MIHON_123"))
	}

	/** Two shipped spellings of one site must not race: the listing is walked in a fixed order. */
	@Test
	fun shippedSpellingsAreOrderedDeterministically() {
		val listed = listOf("JS_MANGANATO_GG.xml", "DD_MANGANATO.xml")
		val forwards = renamedPreferenceSources(listed).map { it.first }
		val backwards = renamedPreferenceSources(listed.reversed()).map { it.first }
		assertEquals(forwards, backwards)
		assertTrue(forwards.indexOf("DD_MANGANATO") < forwards.indexOf("JS_MANGANATO_GG"))
	}
}
