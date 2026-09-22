package com.nyora.hasan72341.sync.supabase

import com.nyora.hasan72341.core.db.entity.MangaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SupabaseMangaPayloadTest {

	@Test
	fun canonicalSourceKeepsExistingMetadataAndOpaqueMangaId() {
		val source = """{"name":"data:manga_en","title":"Display title","locale":"en"}"""
		val row = manga(source).toRemoteManga("user", "2026-09-17T00:00:00Z")!!

		assertEquals(source, row.getString("source_ref"))
		assertEquals("opaque-existing-id", row.getString("id"))
		assertEquals("A title", row.getString("title"))
		assertEquals("[\"Another title\"]", row.getString("alt_titles"))
		assertEquals("[\"An author\"]", row.getString("authors"))
		assertEquals("Description", row.getString("description"))
		assertEquals("2026-09-17T00:00:00Z", row.getString("updated_at"))
	}

	@Test
	fun unresolvedCanonicalSourcesRemainSyncable() {
		listOf("data:not-installed", """{"name":"data:not-installed"}""").forEach {
			assertNotNull(it, manga(it).toRemoteManga("user", "time"))
		}
	}

	@Test
	fun retiredLocalAndMalformedSourceReferencesDoNotProduceRemoteRows() {
		listOf(
			"JS_MANGADEX", "DD_MANGADEX", "MIHON_123", "mihon:123", "123", "LOCAL", "UNKNOWN",
			"parser:mangadex", "script:mangadex", "data:MANGADEX", "data:", "",
		).forEach {
			assertNull(it, manga(it).toRemoteManga("user", "time"))
			assertNull(it, manga("""{"name":"$it"}""").toRemoteManga("user", "time"))
		}
		listOf("{}", "[]", "{broken", """{"name":null}""", """{"name":123}""").forEach {
			assertNull(it, manga(it).toRemoteManga("user", "time"))
		}
	}

	private fun manga(source: String) = MangaEntity(
		id = "opaque-existing-id",
		title = "A title",
		altTitles = "[\"Another title\"]",
		url = "/manga/a",
		publicUrl = "https://example.com/manga/a",
		rating = 0.8f,
		isNsfw = false,
		contentRating = "SAFE",
		coverUrl = "https://example.com/cover.jpg",
		largeCoverUrl = null,
		state = "ONGOING",
		authors = "[\"An author\"]",
		source = source,
		description = "Description",
	)
}
