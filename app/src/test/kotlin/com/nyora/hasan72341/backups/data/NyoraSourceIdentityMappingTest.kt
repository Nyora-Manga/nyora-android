package com.nyora.hasan72341.backups.data

import com.nyora.hasan72341.core.model.DataDrivenMangaSource
import com.nyora.hasan72341.core.model.toMangaSourceRef
import com.nyora.hasan72341.mihon.parsers.model.ContentType
import com.nyora.hasan72341.mihon.parsers.model.MangaSourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NyoraSourceIdentityMappingTest {
	@Test
	fun persistedCanonicalSourceUsesDataReference() {
		assertEquals(MangaSourceRef.Data("data:mangadex"), "data:mangadex".toMangaSourceRef())
	}

	@Test
	fun retiredMihonAndNumericAliasesResolveToUnknown() {
		assertTrue("MIHON_123".toMangaSourceRef() is MangaSourceRef.Unknown)
		assertTrue("123".toMangaSourceRef() is MangaSourceRef.Unknown)
		assertTrue("mihon:123".toMangaSourceRef() is MangaSourceRef.Unknown)
	}

	@Test
	fun runtimeRequiresCanonicalCatalogueIdentity() {
		assertEquals(null, NyoraSourceIdentity.canonicalize("JS_MANGADEX"))
		assertEquals("data:mangadex", NyoraSourceIdentity.canonicalize("data:mangadex"))
		assertEquals(null, NyoraSourceIdentity.canonicalize("MIHON_123"))
		assertEquals(null, NyoraSourceIdentity.canonicalize("123"))
	}

	@Test
	fun dataDrivenSourceExposesCanonicalPortableIdentity() {
		val source = DataDrivenMangaSource(
			catalogueId = "MANGADEX",
			engineKey = "mangadex",
			title = "MangaDex",
			locale = "en",
			nsfw = false,
			domain = "mangadex.org",
			contentType = ContentType.MANGA,
			config = emptyMap(),
			pageSize = 20,
			antiBot = null,
			cfWall = null,
		)

		assertEquals("data:mangadex", source.name)
	}
}
