package com.nyora.hasan72341.core.parser.datadriven

import org.junit.Assert.assertEquals
import org.junit.Test

class StableMangaIdentityTest {

	@Test
	fun legacyIdMatchesTheJavaScriptBundleHash() {
		assertEquals("3412299933579011492", stableMangaId("mangadex", "/title/abc"))
		assertEquals("-9055357649434380553", stableMangaId("bananascan_com", "/manga/x"))
		assertEquals("7296953528379950235", stableMangaId("manganato", "/manga/martial-peak"))
		assertEquals("-7227368456062652028", stableMangaId("HWAGO", "/manga/y"))
	}

	@Test
	fun chapterIdKeepsTheBundleConvention() {
		assertEquals("-6526893976154477273", stableChapterId("DANKE", "/read/manga/x/1/"))
	}

	@Test
	fun retiredAsuraRowsHashLikeTheParserEnumWithoutTheHost() {
		assertEquals("ASURASCANS_US", legacyParserSourceName("asurascans"))
		assertEquals("3717704593480770000", stableMangaId("asurascans", "/comics/solo-leveling"))
		assertEquals("3717704593480770000", stableMangaId("asurascans_us", "https://asurascans.com/comics/solo-leveling"))
	}

	@Test
	fun webtoonsHashesTheTitleNumber() {
		assertEquals("1411467923069797397", stableMangaId("webtoons", "/en/x/list?title_no=12345&x=1"))
	}

	@Test
	fun parserNameBridgesKeepHistoricalSpellings() {
		assertEquals("MANGANELO_COM", legacyParserSourceName("data:manganelo"))
		assertEquals("MANGAFIRE_ESLA", legacyParserSourceName("MANGAFIRE_ES_LA"))
		assertEquals("HMANGABAT", legacyParserSourceName("mangabat"))
		assertEquals("KOMIKINDO_INFO", legacyParserSourceName("mangasusuku"))
		assertEquals("asurascans", canonicalDataSourceId("ASURASCANS_US"))
		assertEquals("hiperdex", canonicalDataSourceId("HIPERDEX"))
	}

	@Test
	fun storedSourceNamesUnwrapNestedJson() {
		assertEquals("data:mangadex", decodeStoredSourceName("{\"name\":\"{\\\"name\\\":\\\"data:mangadex\\\"}\"}"))
		assertEquals("data:mangadex", decodeStoredSourceName("com.x.MangaSourceRef.data:mangadex"))
		assertEquals("data:mangadex", decodeStoredSourceName("data:mangadex"))
	}
}
