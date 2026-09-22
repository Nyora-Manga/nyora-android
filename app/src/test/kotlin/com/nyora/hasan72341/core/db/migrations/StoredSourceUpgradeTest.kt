package com.nyora.hasan72341.core.db.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoredSourceUpgradeTest {

	/**
	 * The shipped v2.6-v2.7.3 builds stored `DD_<catalogue id>` in the catalogue's own casing, bare
	 * in `sources` and JSON-wrapped in `manga.source`, while a v2.1.6 install stored the retired
	 * `JS_` enum. Both have to land on the one spelling the catalogue publishes.
	 */
	@Test
	fun ddAndJsSpellingsBecomeCanonicalDataNames() {
		assertEquals("data:hiperdex", upgradedStoredSourceName("DD_HIPERDEX"))
		assertEquals("data:hiperdex", upgradedStoredSourceName("{\"name\":\"DD_HIPERDEX\"}"))
		assertEquals("data:asurascans", upgradedStoredSourceName("DD_ASURASCANS_US"))
		assertEquals("data:bananascan_com", upgradedStoredSourceName("JS_BANANASCAN_COM"))
		assertEquals("data:manganato", upgradedStoredSourceName("JS_MANGANATO_GG"))
		assertEquals("data:mangadex", upgradedStoredSourceName("data:mangadex"))
		assertNull(upgradedStoredSourceName("LOCAL"))
		assertNull(upgradedStoredSourceName("MIHON_123"))
	}

	/** A catalogue id the shipped build wrote in its own casing, and one it lower-cased already. */
	@Test
	fun ddNamesKeepTheCatalogueIdWhateverItsCasing() {
		assertEquals("data:asurascans", upgradedStoredSourceName("DD_asurascans"))
		assertEquals("data:mangafire_en", upgradedStoredSourceName("DD_MANGAFIRE_EN"))
		assertEquals("data:mangasusuku", upgradedStoredSourceName("DD_komikindo-info"))
	}

	/** Rows the purge owns, and the identities that are not a source name at all. */
	@Test
	fun retiredAndNonSourceNamesAreLeftToThePurge() {
		assertNull(upgradedStoredSourceName("mihon:123"))
		assertNull(upgradedStoredSourceName("456"))
		assertNull(upgradedStoredSourceName("UNKNOWN"))
		assertNull(upgradedStoredSourceName("{\"name\":\"MIHON_123\"}"))
	}

	/** An already-migrated name that sync or an older upgrade re-wrapped in the retired prefix. */
	@Test
	fun doublePrefixedNamesUpgradeOnce() {
		assertEquals("data:mangadex", upgradedStoredSourceName("JS_data:mangadex"))
		assertEquals("data:bananascan_com", upgradedStoredSourceName("data:bananascan-com"))
	}

	@Test
	fun mangaIdsRekeyToTheLegacyHash() {
		assertEquals("3412299933579011492", upgradedMangaId("data:mangadex", "/title/abc"))
		assertEquals("3717704593480770000", upgradedMangaId("data:asurascans", "https://asurascans.com/comics/solo-leveling"))
	}
}
