package com.nyora.hasan72341.core.parser.datadriven

import com.nyora.hasan72341.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refresh has to download the catalogue of the data branch the build bundles: a refresh from
 * any other branch replaces the pinned rows and repairs with whatever that branch carries.
 */
class CatalogueRefreshWorkerTest {

	@Test
	fun theRefreshUrlFollowsThePinnedRef() {
		assertEquals(
			"https://raw.githubusercontent.com/nyora-manga/nyora-data-driven/android-sources-2026-09-20/catalogue.json",
			CatalogueRefreshWorker.catalogueUrl("android-sources-2026-09-20"),
		)
		assertEquals(
			"https://raw.githubusercontent.com/nyora-manga/nyora-data-driven/release/v2.8/catalogue.json",
			CatalogueRefreshWorker.catalogueUrl("release/v2.8"),
		)
		assertEquals(
			"https://raw.githubusercontent.com/nyora-manga/nyora-data-driven/main/catalogue.json",
			CatalogueRefreshWorker.catalogueUrl("main"),
		)
	}

	@Test
	fun aRefThatIsNotAPathSegmentFallsBackToMain() {
		listOf("", "  ", "../main", "a b", "main?x=1", "main#x").forEach { ref ->
			assertEquals(
				ref,
				"https://raw.githubusercontent.com/nyora-manga/nyora-data-driven/main/catalogue.json",
				CatalogueRefreshWorker.catalogueUrl(ref),
			)
		}
	}

	/** The Gradle script reads the ref from `.gitmodules`; the field must never come out blank. */
	@Test
	fun theBuildCarriesAUsableRef() {
		assertTrue(BuildConfig.NYORA_CATALOGUE_REF, BuildConfig.NYORA_CATALOGUE_REF.isNotBlank())
		assertTrue(
			BuildConfig.NYORA_CATALOGUE_REF,
			CatalogueRefreshWorker.catalogueUrl(BuildConfig.NYORA_CATALOGUE_REF).contains("/${BuildConfig.NYORA_CATALOGUE_REF}/"),
		)
	}
}
