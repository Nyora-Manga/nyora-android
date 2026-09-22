package com.nyora.hasan72341.core.parser.datadriven

import com.nyora.hasan72341.mihon.parsers.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DataDrivenCatalogueParserTest {

	private val sample = javaClass.getResource("/datadriven/catalogue-sample.json")!!.readText()

	@Test
	fun keepsSupportedLiveRows() {
		val ids = DataDrivenCatalogueParser.parse(sample).map { it.name }
		assertEquals(listOf("data:hiperdex", "data:komikzoid", "data:mangafire_en"), ids)
	}

	@Test
	fun rejectsDuplicateIdsIgnoringCase() {
		val duplicated = """{"sources":[${row("dupe")},${row("DUPE")}]}"""
		assertThrows(IllegalArgumentException::class.java) { DataDrivenCatalogueParser.parse(duplicated) }
	}

	@Test
	fun appliesDomainAndTitlePatches() {
		val astra = DataDrivenCatalogueParser.parse(catalogue(id = "ASTRASCANS", domain = "astrascans.com")).single()
		assertEquals("astracomic.com", astra.domain)
		assertEquals("Astra Comic", astra.title)
	}

	@Test
	fun wallBRowsStayBrowsableOnAndroid() {
		val komikzoid = DataDrivenCatalogueParser.parse(sample).single { it.name == "data:komikzoid" }
		assertEquals("B", komikzoid.cfWall)
		assertEquals("cloudflare", komikzoid.antiBot)
		// The engines read the wall flag back out of the raw config map.
		assertEquals("B", komikzoid.config["cfWall"])
	}

	@Test
	fun readsContentTypeAndPagingFromTheRow() {
		val sources = DataDrivenCatalogueParser.parse(sample).associateBy { it.name }
		val hiperdex = sources.getValue("data:hiperdex")
		assertEquals(ContentType.HENTAI_MANGA, hiperdex.contentType)
		assertEquals(36, hiperdex.pageSize)
		assertEquals(36, hiperdex.config["pageSize"])
		// A scheme and trailing path never reach the URL builder.
		assertEquals("hiperdex.com", hiperdex.domain)
		assertEquals(ContentType.MANGA, sources.getValue("data:komikzoid").contentType)
	}

	/**
	 * The published catalogue flags `manganato` broken because the generic mangabox route stopped
	 * reading its chapter list, but `NatoMangaRepository` serves that row without the engine. A row
	 * a native adapter serves has to survive the flag exactly as the engine-keyed MangaPlus rows
	 * do, or the site disappears the first time an install refreshes its catalogue.
	 */
	@Test
	fun rowsANativeAdapterServesSurviveTheBrokenFlag() {
		val sources = DataDrivenCatalogueParser.parse(brokenRowCatalogue("manganato", engine = "mangabox"))
		assertEquals(listOf("data:manganato"), sources.map { it.name })
	}

	/** A broken row no native adapter serves stays dropped: nothing can render it. */
	@Test
	fun brokenRowsWithoutANativeAdapterAreDropped() {
		val sources = DataDrivenCatalogueParser.parse(brokenRowCatalogue("EXAMPLESCANS", engine = "madara"))
		assertTrue(sources.isEmpty())
	}

	@Test
	fun malformedIdIsRejected() {
		assertThrows(IllegalArgumentException::class.java) { DataDrivenCatalogueParser.parse(catalogue(id = "bad id")) }
	}

	@Test
	fun emptySourcesArrayIsRejected() {
		assertThrows(IllegalArgumentException::class.java) { DataDrivenCatalogueParser.parse("""{"sources":[]}""") }
	}

	@Test
	fun bundledCatalogueParses() {
		val bundled = File("../data/catalogue.json").takeIf { it.isFile }
			?: File("../../nyora-shared-datadriven/data/catalogue.json")
		val sources = DataDrivenCatalogueParser.parse(bundled.readText())
		assertTrue(sources.size > 500)
		assertTrue(sources.none { it.name != it.name.lowercase() })
		assertEquals(1, sources.count { it.name == "data:manganato" })
	}

	private fun catalogue(id: String, domain: String = "example.com"): String =
		"""{"sources":[${row(id, domain)}]}"""

	private fun brokenRowCatalogue(id: String, engine: String): String =
		"""{"sources":[{"id":"$id","name":"Example","lang":"en","engine":"$engine",""" +
			""""domain":"example.com","broken":true,"pageSize":24,"config":{}}]}"""

	private fun row(id: String, domain: String = "example.com"): String =
		"""{"id":"$id","name":"Example","lang":"en","engine":"madara","domain":"$domain","pageSize":24,"config":{}}"""
}
