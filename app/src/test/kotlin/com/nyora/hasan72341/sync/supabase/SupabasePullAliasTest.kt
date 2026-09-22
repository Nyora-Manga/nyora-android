package com.nyora.hasan72341.sync.supabase

import com.nyora.hasan72341.core.parser.datadriven.stableMangaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupabasePullAliasTest {

	@Test
	fun `a data source row aliases the remote id to the legacy local id`() {
		assertEquals(
			stableMangaId("hiperdex", "/manga/x"),
			pulledMangaIdAlias("abc", """{"name":"data:hiperdex"}""", "/manga/x"),
		)
		assertEquals(
			stableMangaId("hiperdex", "/manga/x"),
			pulledMangaIdAlias("abc", "data:hiperdex", "/manga/x"),
		)
	}

	@Test
	fun `an already canonical remote id aliases to itself`() {
		val canonical = stableMangaId("hiperdex", "/manga/x")

		assertEquals(canonical, pulledMangaIdAlias(canonical, """{"name":"data:hiperdex"}""", "/manga/x"))
	}

	@Test
	fun `a row of a source this client does not hash ids for keeps the remote id`() {
		listOf(
			"LOCAL",
			"UNKNOWN",
			"""{"name":"LOCAL"}""",
			"""{"name":"UNKNOWN"}""",
			"MANGADEX",
			"parser:mangadex",
			"mihon:123",
			"",
			"{}",
		).forEach { sourceRef ->
			assertEquals(sourceRef, "abc", pulledMangaIdAlias("abc", sourceRef, "/manga/x"))
			assertNull(sourceRef, canonicalPulledSourceId(sourceRef))
		}
	}

	@Test
	fun `retired source wrappers resolve to the catalogue identity`() {
		val canonical = stableMangaId("hiperdex", "/manga/x")

		listOf(
			"DD_HIPERDEX",
			"JS_data:hiperdex",
			"data:HIPERDEX",
			"""{"name":"{\"name\":\"data:hiperdex\"}"}""",
			"com.nyora.hasan72341.mihon.parsers.model.MangaSourceRef.data:hiperdex",
		).forEach { sourceRef ->
			assertEquals(sourceRef, "data:hiperdex", canonicalPulledSourceId(sourceRef))
			assertEquals(sourceRef, canonical, pulledMangaIdAlias("abc", sourceRef, "/manga/x"))
		}
	}

	@Test
	fun `renamed and retired catalogue spellings hash from the current source id`() {
		assertEquals("data:bananascan_com", canonicalPulledSourceId("data:bananascan-com"))
		assertEquals(
			stableMangaId("bananascan_com", "/manga/x"),
			pulledMangaIdAlias("abc", "data:bananascan-com", "/manga/x"),
		)
		assertEquals("data:asurascans", canonicalPulledSourceId("data:asurascans-us"))
		assertEquals(
			stableMangaId("asurascans", "/comics/solo-leveling"),
			pulledMangaIdAlias("abc", "data:asurascans-us", "https://asurascans.com/comics/solo-leveling"),
		)
		assertEquals("data:mangasusuku", canonicalPulledSourceId("data:komikindo-info"))
		assertEquals(
			stableMangaId("mangasusuku", "/manga/y"),
			pulledMangaIdAlias("abc", "data:komikindo-info", "/manga/y"),
		)
	}

	@Test
	fun `colliding aliases collapse onto the newest row`() {
		val legacy = CanonicalAliasCandidate("abc", "canonical", updatedAt = 20L, deletedAt = 0L)
		val canonical = CanonicalAliasCandidate("canonical", "canonical", updatedAt = 10L, deletedAt = 0L)

		assertEquals(legacy, newestCanonicalAlias(listOf(canonical, legacy)))
		assertEquals(legacy, newestCanonicalAlias(listOf(legacy, canonical)))
	}

	@Test
	fun `a tombstone newer than its alias wins and an exact tie prefers the canonical row`() {
		val tombstoned = CanonicalAliasCandidate("abc", "canonical", updatedAt = 5L, deletedAt = 30L)
		val live = CanonicalAliasCandidate("canonical", "canonical", updatedAt = 20L, deletedAt = 0L)

		assertEquals(tombstoned, newestCanonicalAlias(listOf(live, tombstoned)))
		assertEquals(
			live,
			newestCanonicalAlias(listOf(live, tombstoned.copy(deletedAt = 0L, updatedAt = 20L))),
		)
		assertNull(newestCanonicalAlias(emptyList()))
	}
}
