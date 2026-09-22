package com.nyora.hasan72341.core.db.migrations

import com.nyora.hasan72341.core.parser.datadriven.stableMangaId
import org.json.JSONArray
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
		assertEquals("7296953528379950235", upgradedMangaId("data:manganato", "/manga/martial-peak"))
	}

	@Test
	fun asuraRowsRekeyToTheHostlessIdentity() {
		assertEquals("3717704593480770000", upgradedMangaId("data:asurascans", "https://asurascans.com/comics/solo-leveling"))
	}

	/**
	 * The eight delimiter renames hash to the id the retired `JS_` parser stamped, so rows stored
	 * under the old spelling keep their manga id and need no dependent-row re-key.
	 */
	@Test
	fun renamedSpellingsKeepTheirMangaIds() {
		assertEquals("-9055357649434380553", upgradedMangaId("data:bananascan_com", "/manga/x"))
		assertEquals(stableMangaId("bananascan_com", "/manga/x"), upgradedMangaId("data:bananascan_com", "/manga/x"))
	}

	/**
	 * A chapter id embeds the source token too, so the rows that move to a renamed identity must
	 * take their chapter ids with them: the blob is rewritten and the old -> new map is what the
	 * migration replays over `history.chapter_id`, `bookmarks.chapter_id` and
	 * `tracks.last_chapter_id`.
	 */
	@Test
	fun manganatoGgChapterIdsRekeyToTheManganatoIdentity() {
		val stored = chaptersBlob(MANGANATO_GG_CHAPTER_ID, "/chapter/martial-peak-1")
		val rekeyed = requireNotNull(rekeyedChapters("data:manganato", stored))

		assertEquals(mapOf(MANGANATO_GG_CHAPTER_ID to MANGANATO_CHAPTER_ID), rekeyed.ids)
		val chapter = JSONArray(rekeyed.chapters).getJSONObject(0)
		assertEquals(MANGANATO_CHAPTER_ID, chapter.getString("id"))
		assertEquals("/chapter/martial-peak-1", chapter.getString("url"))
		assertEquals("Chapter 1", chapter.getString("title"))
	}

	/** A delimiter-only rename hashes to the token the blob was written with, so nothing moves. */
	@Test
	fun renamedSpellingsKeepTheirChapterIds() {
		val stored = chaptersBlob("-3293541545326521030", "/manga/x/1")

		assertNull(rekeyedChapters("data:bananascan_com", stored))
	}

	@Test
	fun unreadableOrEmptyChapterBlobsAreLeftAlone() {
		assertNull(rekeyedChapters("data:manganato", "[]"))
		assertNull(rekeyedChapters("data:manganato", ""))
		assertNull(rekeyedChapters("data:manganato", "not json"))
	}

	/** A chapter without a url cannot be re-hashed, and must not lose the id it has. */
	@Test
	fun chaptersWithoutAUrlKeepTheirId() {
		val stored = "[{\"id\":\"kept\",\"url\":\"\"}]"

		assertNull(rekeyedChapters("data:manganato", stored))
	}

	private fun chaptersBlob(id: String, url: String): String =
		"[{\"id\":\"$id\",\"title\":\"Chapter 1\",\"number\":1.0,\"volume\":0,\"url\":\"$url\"," +
			"\"scanlator\":null,\"uploadDate\":0,\"branch\":null}]"

	private companion object {

		/** `legacyMangaId("MANGANATO_GG", " chapter /chapter/martial-peak-1")`, the retired stamp. */
		const val MANGANATO_GG_CHAPTER_ID = "-6598312688466032814"

		/** `legacyMangaId("MANGANATO", " chapter /chapter/martial-peak-1")`, what the runtime produces. */
		const val MANGANATO_CHAPTER_ID = "-5537510363614834579"
	}
}
