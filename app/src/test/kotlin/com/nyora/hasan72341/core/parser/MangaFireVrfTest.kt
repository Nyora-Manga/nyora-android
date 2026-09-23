package com.nyora.hasan72341.core.parser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MangaFire rejects an unsigned or mis-signed request outright, so a signer that drifts by one byte
 * takes the whole source down with no other symptom. These are the same golden tokens the shared
 * desktop signer is pinned against — this port swapped `java.util.Base64` (API 26) for okio, and
 * that swap has to be invisible.
 */
class MangaFireVrfTest {

	@Test
	fun signsProtectedRoutesExactlyLikeTheCurrentWebsiteBuild() {
		assertEquals("8sK3xtqdFQ", MangaFireVrf.token("/titles"))
		assertEquals("8vPRXa1JjvVTxq_RmQ", MangaFireVrf.token("/chapters/123"))
	}

	@Test
	fun canonicalizesFieldsBeforeSigning() {
		val params = listOf(
			"order[chapter_updated_at]" to "desc",
			"page" to "1",
			"limit" to "50",
		)

		assertEquals(
			"limit=50&order[chapter_updated_at]=desc&page=1",
			MangaFireVrf.canonicalQuery(params),
		)
		assertEquals(
			"8sK3xtqdFZdD1d-yEmkP1xkAaXb7oSJTkOK_4EZWBxSbApw9C1IPTNIupXVcAcNBlbFbZDCl",
			MangaFireVrf.token("/titles", params),
		)
	}

	@Test
	fun canonicalTextStaysUnescapedLikeTheBrowserImplementation() {
		val params = listOf(
			"page" to "1",
			"keyword" to "hello world",
			"limit" to "50",
		)

		assertEquals(
			"8sK3xtqdFZfetBhus6bRAi4MlLAdRqSLal6BIi-MQuF735x4lLiBezrZoA",
			MangaFireVrf.token("/titles", params),
		)
	}
}
