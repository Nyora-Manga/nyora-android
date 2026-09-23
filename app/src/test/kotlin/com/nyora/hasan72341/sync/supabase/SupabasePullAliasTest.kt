package com.nyora.hasan72341.sync.supabase

import com.nyora.hasan72341.core.db.entity.MangaEntity
import com.nyora.hasan72341.core.db.entity.toEntity
import com.nyora.hasan72341.core.parser.datadriven.stableChapterId
import com.nyora.hasan72341.core.parser.datadriven.stableMangaId
import com.nyora.hasan72341.mihon.parsers.model.Manga
import com.nyora.hasan72341.mihon.parsers.model.MangaChapter
import com.nyora.hasan72341.mihon.parsers.model.MangaSourceRef
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

	/** v2.1.6 pushed the stored `{"name":"JS_<ENUM>"}` as source_ref; its enum spelled every delimiter `_`. */
	@Test
	fun `a bare JavaScript enum source_ref resolves to the catalogue identity`() {
		assertEquals("data:hiperdex", canonicalPulledSourceId("""{"name":"JS_HIPERDEX"}"""))
		assertEquals("data:hiperdex", canonicalPulledSourceId("JS_HIPERDEX"))
		assertEquals("data:manganato", canonicalPulledSourceId("JS_MANGANATO_GG"))
		assertEquals("data:bananascan_com", canonicalPulledSourceId("JS_BANANASCAN_COM"))
		assertEquals("data:mangafire_en", canonicalPulledSourceId("""{"name":"JS_MANGAFIRE_EN"}"""))
		assertEquals(
			stableMangaId("hiperdex", "/manga/x"),
			pulledMangaIdAlias("abc", """{"name":"JS_HIPERDEX"}""", "/manga/x"),
		)
	}

	/** A prefix with nothing behind it, or a spelling the contract rejects, is not a source. */
	@Test
	fun `an upgraded name that is not a canonical identity is rejected`() {
		listOf("JS_", "DD_", "data:", "DD_bad id", """{"name":"data:Upper Case"}""").forEach { sourceRef ->
			assertNull(sourceRef, canonicalPulledSourceId(sourceRef))
			assertEquals(sourceRef, "abc", pulledMangaIdAlias("abc", sourceRef, "/manga/x"))
		}
	}

	@Test
	fun `the identity map lists the source of every data-source row, aliased or canonical`() {
		val canonical = stableMangaId("hiperdex", "/manga/x")
		val rows = JSONArray(
			"""
			[
				{"id":"$canonical","url":"/manga/x","source_ref":"{\"name\":\"data:hiperdex\"}"},
				{"id":"def","url":"/manga/y","source_ref":"JS_BANANASCAN_COM"},
				{"id":"ghi","url":"/manga/z","source_ref":"{\"name\":\"LOCAL\"}"}
			]
			""",
		)

		val identities = pulledMangaIdentities(rows)

		assertEquals(mapOf("def" to stableMangaId("bananascan_com", "/manga/y")), identities.aliases)
		assertEquals(mapOf(canonical to "data:hiperdex", "def" to "data:bananascan_com"), identities.sourceIds)
	}

	/** The v2.6–v2.7.3 data-driven adapter stored the engine id, an href; its native adapters `<source>|chapter|<url>`. */
	@Test
	fun `a shipped chapter id carries its chapter url`() {
		assertEquals("/manga/x/chapter-1", legacyChapterUrl("/manga/x/chapter-1"))
		assertEquals("/manga/x/chapter-1", legacyChapterUrl("DD_MANGAFIRE_EN|chapter|/manga/x/chapter-1"))
		assertEquals("https://site/manga/x/1", legacyChapterUrl("https://site/manga/x/1"))
		// A hash, an engine's numeric id, and nothing at all carry no url.
		assertNull(legacyChapterUrl(stableChapterId("hiperdex", "/manga/x/chapter-1")))
		assertNull(legacyChapterUrl("-9223372036854775808"))
		assertNull(legacyChapterUrl("42"))
		assertNull(legacyChapterUrl(""))
		assertNull(legacyChapterUrl("DD_MANGAFIRE_EN|chapter|"))
	}

	@Test
	fun `a shipped chapter id of a data-source manga is re-keyed when the manga stores that chapter`() {
		val stored = setOf("/manga/x/chapter-1", "/manga/x/chapter-2")

		assertEquals(
			stableChapterId("hiperdex", "/manga/x/chapter-1"),
			pulledChapterIdAlias("/manga/x/chapter-1", "data:hiperdex") { stored },
		)
		assertEquals(
			stableChapterId("mangafire_en", "/manga/x/chapter-2"),
			pulledChapterIdAlias("DD_MANGAFIRE_EN|chapter|/manga/x/chapter-2", "data:mangafire_en") { stored },
		)
	}

	@Test
	fun `a hash and a row of a source this client does not hash for are kept as pushed`() {
		val hash = stableChapterId("hiperdex", "/manga/x/chapter-1")
		var storedRead = false

		assertEquals(hash, pulledChapterIdAlias(hash, "data:hiperdex") { storedRead = true; emptySet() })
		assertEquals("/manga/x/chapter-1", pulledChapterIdAlias("/manga/x/chapter-1", null) { storedRead = true; emptySet() })
		assertFalse(storedRead)
	}

	@Test
	fun `a shipped chapter id whose url no stored chapter carries cannot be settled`() {
		assertNull(pulledChapterIdAlias("/manga/x/chapter-9", "data:hiperdex") { setOf("/manga/x/chapter-1") })
		assertNull(pulledChapterIdAlias("/manga/x/chapter-9", "data:hiperdex") { emptySet() })
	}

	@Test
	fun `stored chapter urls come out of the chapters blob`() {
		val blob = """[{"id":"1","url":"/manga/x/chapter-1"},{"id":"2","url":"/manga/x/chapter-2"},{"id":"3"},"junk"]"""

		assertEquals(setOf("/manga/x/chapter-1", "/manga/x/chapter-2"), storedChapterUrls(blob))
		assertEquals(emptySet<String>(), storedChapterUrls("[]"))
		assertEquals(emptySet<String>(), storedChapterUrls("not json"))
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

	// -- source prefs --

	/** The shipped builds pushed `DD_`/`JS_` rows; this build pushes `data:`; all three name one local source. */
	@Test
	fun `source prefs pushed under several spellings of one source apply once, newest first`() {
		val stale = PulledSourcePref("DD_HIPERDEX", "data:hiperdex", isPinned = true, isEnabled = false, updatedAt = 20L)
		val current = PulledSourcePref("data:hiperdex", "data:hiperdex", isPinned = false, isEnabled = true, updatedAt = 10L)
		val other = PulledSourcePref("data:manganato", "data:manganato", isPinned = false, isEnabled = false, updatedAt = 5L)

		assertEquals(listOf(stale, other), newestCanonicalSourcePrefs(listOf(current, stale, other)))
		assertEquals(listOf(other, stale), newestCanonicalSourcePrefs(listOf(other, stale, current)))
		assertEquals(listOf(current), newestCanonicalSourcePrefs(listOf(stale.copy(updatedAt = 1L), current)))
	}

	@Test
	fun `an exact tie between source pref rows prefers the canonical spelling`() {
		val shipped = PulledSourcePref("JS_HIPERDEX", "data:hiperdex", isPinned = true, isEnabled = false, updatedAt = 20L)
		val canonical = PulledSourcePref("data:hiperdex", "data:hiperdex", isPinned = false, isEnabled = true, updatedAt = 20L)

		assertEquals(listOf(canonical), newestCanonicalSourcePrefs(listOf(shipped, canonical)))
		assertEquals(listOf(canonical), newestCanonicalSourcePrefs(listOf(canonical, shipped)))
	}

	/** Rows without a timestamp all read as 0; the choice must still not depend on backend order. */
	@Test
	fun `two shipped spellings that tie collapse the same way whichever arrived last`() {
		val dd = PulledSourcePref("DD_HIPERDEX", "data:hiperdex", isPinned = false, isEnabled = false, updatedAt = 0L)
		val js = PulledSourcePref("JS_HIPERDEX", "data:hiperdex", isPinned = true, isEnabled = true, updatedAt = 0L)

		assertEquals(newestCanonicalSourcePrefs(listOf(dd, js)), newestCanonicalSourcePrefs(listOf(js, dd)))
		assertEquals(1, newestCanonicalSourcePrefs(listOf(dd, js)).size)
		assertEquals(emptyList<PulledSourcePref>(), newestCanonicalSourcePrefs(emptyList()))
	}

	// -- manga rows --

	@Test
	fun `a pulled manga row carries the local chapters, unread and progress forward`() {
		val local = localManga(title = "Old title", chapterUrl = "/manga/x/chapter-1", unread = 3, progress = 0.5f)
		val pulled = pulledManga(title = "New title")

		val stored = pulledMangaWithLocalReaderState(pulled, local)

		assertEquals("New title", stored.title)
		assertEquals(local.chapters, stored.chapters)
		assertEquals(3, stored.unread)
		assertEquals(0.5f, stored.progress, 0f)
		// Nothing but the reader state comes from the local row.
		assertEquals(pulled.copy(chapters = local.chapters, unread = 3, progress = 0.5f), stored)
	}

	@Test
	fun `a pulled manga this device has never stored keeps the entity defaults`() {
		val pulled = pulledManga(title = "New title")

		val stored = pulledMangaWithLocalReaderState(pulled, null)

		assertEquals(pulled, stored)
		assertEquals("[]", stored.chapters)
		assertEquals(0, stored.unread)
	}

	/** The chapters blob is the one `Manga.toEntity()` writes, so the re-key reads the real shape. */
	@Test
	fun `a shipped chapter id resolves through the chapters the pulled row carries forward`() {
		val url = "/manga/x/chapter-1"
		val stored = pulledMangaWithLocalReaderState(pulledManga(title = "x"), localManga(title = "x", chapterUrl = url))
		val storedUrls = { storedChapterUrls(stored.chapters) }

		assertEquals(setOf(url), storedUrls())
		assertEquals(stableChapterId("hiperdex", url), pulledChapterIdAlias(url, "data:hiperdex", storedUrls))
		assertEquals(stableChapterId("hiperdex", url), pulledChapterIdAlias("DD_HIPERDEX|chapter|$url", "data:hiperdex", storedUrls))
	}

	/** Fresh install, or a manga never opened on this device: nothing to re-key against, the id is kept as pushed. */
	@Test
	fun `a shipped chapter id of a manga without stored chapters cannot be settled`() {
		val stored = pulledMangaWithLocalReaderState(pulledManga(title = "x"), null)

		assertNull(pulledChapterIdAlias("/manga/x/chapter-1", "data:hiperdex") { storedChapterUrls(stored.chapters) })
	}

	/** What [SupabaseSync.pullManga] builds from a cloud row: metadata only, entity defaults elsewhere. */
	private fun pulledManga(title: String) = MangaEntity(
		id = stableMangaId("hiperdex", "/manga/x"),
		title = title,
		altTitles = null,
		url = "/manga/x",
		publicUrl = "https://site/manga/x",
		rating = -1f,
		isNsfw = false,
		contentRating = null,
		coverUrl = "",
		largeCoverUrl = null,
		state = null,
		authors = null,
		source = """{"name":"data:hiperdex"}""",
	)

	/** The row this device stored after loading the details, through the real serializer. */
	private fun localManga(title: String, chapterUrl: String, unread: Int = 0, progress: Float = 0f) = Manga(
		id = stableMangaId("hiperdex", "/manga/x"),
		title = title,
		url = "/manga/x",
		publicUrl = "https://site/manga/x",
		source = MangaSourceRef.Data("data:hiperdex"),
		chapters = listOf(MangaChapter(id = stableChapterId("hiperdex", chapterUrl), title = "Chapter 1", number = 1f, url = chapterUrl)),
		unread = unread,
		progress = progress,
	).toEntity()
}
