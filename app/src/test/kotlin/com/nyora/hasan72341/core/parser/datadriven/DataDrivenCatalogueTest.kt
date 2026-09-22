package com.nyora.hasan72341.core.parser.datadriven

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The catalogue decides which sources every screen can browse, so the precedence between the
 * bundled asset and the refresh cache is a user-visible contract: an app update must start from
 * its own bundled catalogue, a refresh must only move it forward, and an unchanged refresh must
 * change nothing.
 */
class DataDrivenCatalogueTest {

	@get:Rule
	val folder = TemporaryFolder()

	private val errors = mutableListOf<Throwable>()

	@Test
	fun coldStartServesTheBundledCatalogueAndCachesNothing() {
		val catalogue = catalogue(bundled = catalogueJson("hiperdex", hash = "bundle-1"))

		assertEquals(listOf("data:hiperdex"), catalogue.sources.map { it.name })
		assertEquals(0L, catalogue.revision)
		assertEquals("bundle-1", catalogue.hash)
		assertFalse(cacheFile().exists())
		assertTrue(errors.isEmpty())
	}

	@Test
	fun replacePublishesCachesAndRecordsTheBundleItWasWrittenOver() {
		val bundled = catalogueJson("hiperdex", hash = "bundle-1")
		val catalogue = catalogue(bundled)

		val count = catalogue.replace(catalogueJson("hiperdex", "beta", hash = "remote-2"))

		assertEquals(2, count)
		assertEquals(listOf("data:hiperdex", "data:beta"), catalogue.sources.map { it.name })
		assertEquals(1L, catalogue.revision)
		assertEquals("remote-2", catalogue.hash)
		assertEquals(catalogueJson("hiperdex", "beta", hash = "remote-2"), cacheFile().readText())
		assertEquals(sha256Hex(bundled.toByteArray()), bindingFile().readText())
		assertTrue(errors.isEmpty())
	}

	@Test
	fun theCacheServesTheNextColdStartOverTheSameBundle() {
		val bundled = catalogueJson("hiperdex", hash = "bundle-1")
		catalogue(bundled).replace(catalogueJson("hiperdex", "beta", hash = "remote-2"))

		val restarted = catalogue(bundled)

		assertEquals(listOf("data:hiperdex", "data:beta"), restarted.sources.map { it.name })
		assertEquals("remote-2", restarted.hash)
		assertEquals(0L, restarted.revision)
	}

	@Test
	fun aCacheWrittenOverAnotherBundleIsDiscardedByTheUpdatedBuild() {
		catalogue(catalogueJson("hiperdex", hash = "bundle-1"))
			.replace(catalogueJson("hiperdex", "beta", hash = "remote-2"))

		// The update bundles a catalogue the cached download predates: the bundle has to win.
		val updated = catalogue(catalogueJson("hiperdex", "gamma", hash = "bundle-3"))

		assertEquals(listOf("data:hiperdex", "data:gamma"), updated.sources.map { it.name })
		assertEquals("bundle-3", updated.hash)
		assertFalse(cacheFile().exists())
		assertFalse(bindingFile().exists())
		assertTrue(errors.isEmpty())
	}

	@Test
	fun aRefreshWithTheCurrentHashChangesNothing() {
		val catalogue = catalogue(catalogueJson("hiperdex", hash = "bundle-1"))

		// Same hash, different body: the hash is the generator's word that the content is the same.
		val count = catalogue.replace(catalogueJson("hiperdex", "beta", hash = "bundle-1"))

		assertEquals(1, count)
		assertEquals(listOf("data:hiperdex"), catalogue.sources.map { it.name })
		assertEquals(0L, catalogue.revision)
		assertFalse(cacheFile().exists())
	}

	@Test
	fun aCatalogueWithoutAHashIsAlwaysApplied() {
		val catalogue = catalogue(catalogueJson("hiperdex", hash = null))

		catalogue.replace(catalogueJson("hiperdex", "beta", hash = null))

		assertEquals(2, catalogue.sources.size)
		assertEquals(1L, catalogue.revision)
		assertNull(catalogue.hash)
	}

	@Test
	fun anInvalidRefreshLeavesTheSnapshotAndTheCacheAlone() {
		val catalogue = catalogue(catalogueJson("hiperdex", hash = "bundle-1"))
		catalogue.replace(catalogueJson("hiperdex", "beta", hash = "remote-2"))

		assertThrows(IllegalArgumentException::class.java) { catalogue.replace("not json") }
		assertThrows(IllegalArgumentException::class.java) { catalogue.replace("""{"sources":[]}""") }

		assertEquals(2, catalogue.sources.size)
		assertEquals(1L, catalogue.revision)
		assertEquals("remote-2", catalogue.hash)
		assertEquals(catalogueJson("hiperdex", "beta", hash = "remote-2"), cacheFile().readText())
	}

	@Test
	fun anUnreadableCacheIsDeletedAndTheBundleServes() {
		val bundled = catalogueJson("hiperdex", hash = "bundle-1")
		cacheFile().writeText("{ corrupt")
		bindingFile().writeText(sha256Hex(bundled.toByteArray()))

		val catalogue = catalogue(bundled)

		assertEquals(listOf("data:hiperdex"), catalogue.sources.map { it.name })
		assertFalse(cacheFile().exists())
		assertEquals(1, errors.size)
	}

	@Test
	fun aCacheWithoutABindingRecordIsNeverTrusted() {
		val bundled = catalogueJson("hiperdex", hash = "bundle-1")
		cacheFile().writeText(catalogueJson("hiperdex", "beta", hash = "remote-2"))

		val catalogue = catalogue(bundled)

		assertEquals(listOf("data:hiperdex"), catalogue.sources.map { it.name })
		assertFalse(cacheFile().exists())
	}

	@Test
	fun theBindingRuleIsExact() {
		assertTrue(isCatalogueCacheBoundTo("abc", "abc"))
		assertTrue(isCatalogueCacheBoundTo("ABC", "abc"))
		assertFalse(isCatalogueCacheBoundTo("abd", "abc"))
		assertFalse(isCatalogueCacheBoundTo("", "abc"))
		assertFalse(isCatalogueCacheBoundTo(null, "abc"))
	}

	@Test
	fun theBundleDigestIsSha256OfTheAssetBytes() {
		assertEquals(
			"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
			sha256Hex(ByteArray(0)),
		)
		assertEquals(
			"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
			sha256Hex("abc".toByteArray()),
		)
	}

	private fun catalogue(bundled: String) = DataDrivenCatalogue(
		bundledAsset = { bundled.toByteArray() },
		cache = CatalogueCache(folder.root),
		reportError = { errors += it },
	)

	private fun cacheFile(): File = File(folder.root, "datadriven-catalogue.json")

	private fun bindingFile(): File = File(folder.root, "datadriven-catalogue.json.bundled")

	private fun catalogueJson(vararg ids: String, hash: String?): String {
		val rows = ids.joinToString(",") { id ->
			"""{"id":"$id","name":"$id","lang":"en","engine":"madara","domain":"$id.example","broken":false,"pageSize":20,"config":{}}"""
		}
		val hashField = if (hash == null) "" else ""","hash":"$hash""""
		return """{"sources":[$rows]$hashField}"""
	}
}
