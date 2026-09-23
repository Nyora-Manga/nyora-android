package com.nyora.hasan72341.backups.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NyoraCanonicalSourceIdentityTest {

	@Test
	fun canonicalSourcesArePreservedWithoutCatalogueAvailability() {
		listOf("data:mangadex", "data:not-installed", "data:source_en.v2").forEach {
			assertEquals(it, NyoraSourceIdentity.canonicalize(it))
		}
	}

	@Test
	fun runtimeDoesNotTranslateRetiredSourceAliases() {
		listOf("JS_MANGADEX", "DD_MANGADEX", "parser:mangadex", "script:mangadex", "MIHON_123", "mihon:123", "123").forEach {
			assertNull(it, NyoraSourceIdentity.canonicalize(it))
		}
	}

	@Test
	fun malformedAndLocalSourceIdentitiesAreExcluded() {
		listOf("", "LOCAL", "UNKNOWN", "local", "data:", "data:MANGADEX", "data:-source", "data:source/other", " data:mangadex", "data:mangadex\n").forEach {
			assertNull(it, NyoraSourceIdentity.canonicalize(it))
		}
	}
}
