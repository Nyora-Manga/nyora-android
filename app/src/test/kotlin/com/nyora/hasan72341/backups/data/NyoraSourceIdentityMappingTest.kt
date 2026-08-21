package com.nyora.hasan72341.backups.data

import com.nyora.hasan72341.core.model.toMangaSourceRef
import com.nyora.hasan72341.mihon.parsers.model.MangaSourceRef
import com.nyora.hasan72341.js.NyoraJsMangaSource
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
	fun resolvesKnownJavascriptAliasOnceToCanonicalCatalogueIdentity() {
		assertEquals("data:mangadex", NyoraSourceIdentity.canonicalize("JS_MANGADEX"))
		assertEquals("data:mangadex", NyoraSourceIdentity.canonicalize("data:mangadex"))
		assertEquals(null, NyoraSourceIdentity.canonicalize("MIHON_123"))
		assertEquals(null, NyoraSourceIdentity.canonicalize("123"))
	}

	@Test
	fun dataDrivenSourceExposesCanonicalPortableIdentity() {
		val source = NyoraJsMangaSource("MANGADEX", "MangaDex", "en", "mangadex.org", false)

		assertEquals("data:mangadex", source.portableName)
	}
}
