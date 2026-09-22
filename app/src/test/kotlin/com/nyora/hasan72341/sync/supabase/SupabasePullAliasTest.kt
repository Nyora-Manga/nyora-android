package com.nyora.hasan72341.sync.supabase

import com.nyora.hasan72341.core.parser.datadriven.stableMangaId
import org.json.JSONArray
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
	fun `the alias map covers every parent row, not only the ones a cutoff selected`() {
		val rows = JSONArray(
			"""
			[
				{"id":"abc","url":"/manga/x","source_ref":"{\"name\":\"data:hiperdex\"}"},
				{"id":"def","url":"/manga/y","source_ref":"data:bananascan-com"},
				{"id":"ghi","url":"/manga/z","source_ref":"{\"name\":\"LOCAL\"}"}
			]
			""",
		)

		val aliases = pulledMangaIdAliasMap(rows)

		// A history row whose parent was not itself updated still resolves to the local id.
		assertEquals(stableMangaId("hiperdex", "/manga/x"), aliases["abc"])
		assertEquals(stableMangaId("bananascan_com", "/manga/y"), aliases["def"])
		// A source this client does not hash ids for is left out so the remote id is kept.
		assertNull(aliases["ghi"])
		assertEquals(2, aliases.size)
	}

	@Test
	fun `a malformed parent row is reported and skipped without costing the map`() {
		val rows = JSONArray(
			"""
			[
				{"id":"abc","source_ref":"data:hiperdex"},
				{"id":"def","url":"/manga/y","source_ref":"data:hiperdex"}
			]
			""",
		)
		val failures = mutableListOf<Int>()

		val aliases = pulledMangaIdAliasMap(rows) { index, _ -> failures += index }

		assertEquals(listOf(0), failures)
		assertEquals(stableMangaId("hiperdex", "/manga/y"), aliases["def"])
		assertEquals(1, aliases.size)
	}

	@Test
	fun `an already canonical parent row adds no alias`() {
		val canonical = stableMangaId("hiperdex", "/manga/x")
		val rows = JSONArray("""[{"id":"$canonical","url":"/manga/x","source_ref":"data:hiperdex"}]""")

		assertEquals(emptyMap<String, String>(), pulledMangaIdAliasMap(rows))
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
