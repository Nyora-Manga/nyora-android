package com.nyora.hasan72341.core.db.migrations

import com.nyora.hasan72341.core.parser.datadriven.stableMangaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceRenamesTest {

	@Test
	fun bareAndJsonSourcesAreRenamed() {
		assertEquals("data:bananascan_com", renamedSource("data:bananascan-com"))
		assertEquals("{\"name\":\"data:mangafire_en\"}", renamedSource("{\"name\":\"data:mangafire-en\"}"))
		assertNull(renamedSource("data:mangadex"))
	}

	@Test
	fun manganatoGgRowsRekeyToTheManganatoIdentity() {
		assertEquals("7296953528379950235", rekeyedMangaId("data:manganato", "/manga/martial-peak"))
	}

	@Test
	fun asuraRowsRekeyToTheHostlessIdentity() {
		assertEquals("3717704593480770000", rekeyedMangaId("data:asurascans", "https://asurascans.com/comics/solo-leveling"))
	}

	/**
	 * The eight delimiter renames hash to the id the retired `JS_` parser stamped, so rows stored
	 * under the old spelling keep their manga id and need no dependent-row re-key.
	 */
	@Test
	fun renamedSpellingsKeepTheirMangaIds() {
		assertEquals("-9055357649434380553", rekeyedMangaId("data:bananascan_com", "/manga/x"))
		assertEquals(stableMangaId("bananascan_com", "/manga/x"), rekeyedMangaId("data:bananascan_com", "/manga/x"))
	}
}
