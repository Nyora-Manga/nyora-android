package com.nyora.hasan72341.core.parser.datadriven

import app.nyora.core.model.SortOrder
import app.nyora.data.engine.EngineConfig
import app.nyora.data.engine.EngineId
import app.nyora.data.engine.FilterCapabilities
import app.nyora.data.engine.StaticTag
import com.nyora.hasan72341.core.model.DataDrivenMangaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The engines read their selectors, capabilities, sort orders and vocabularies off the TYPED
 * [EngineConfig], not off `rawConfig`, so anything the bridge fails to map is silently dropped and
 * the source browses differently here than it does on web and desktop.
 */
class DataDrivenSourceDefTest {

	@Test
	fun madaraCarriesSelectorsCapabilitiesSortOrdersAndImages() {
		val cfg = madara(
			"""
			"selectors":{"chapter":"li.wp-manga-chapter:contains(Capitulo)","page":"div#all img",
			"bodyPage":"div.main","desc":"div.summary__content","genre":".sgeneros a","date":".chapterdate",
			"state":"div b:contains(state)","alt":"div span.alt","testAsync":"div.listing-chapters_wrap"},
			"capabilities":{"search":true,"tagsExclusion":false,"year":true},
			"sortOrders":["ALPHABETICAL","UPDATED","POPULARITY"],
			"images":{"stripStyleParam":true,"imgAttrCandidates":["data-src"],"pageImgSelector":"img.page"},
			"staticTags":[{"key":"Action","title":"Action"},{"key":"drama"}],
			"extraStatusMap":{"Publicandose":"ONGOING"}
			""".trimIndent().replace("\n", ""),
		)
		assertEquals("li.wp-manga-chapter:contains(Capitulo)", cfg.selectors.chapter)
		assertEquals("div#all img", cfg.selectors.page)
		assertEquals("div.main", cfg.selectors.bodyPage)
		assertEquals("div.summary__content", cfg.selectors.desc)
		assertEquals(".sgeneros a", cfg.selectors.genre)
		assertEquals(".chapterdate", cfg.selectors.date)
		assertEquals("div b:contains(state)", cfg.selectors.state)
		assertEquals("div span.alt", cfg.selectors.alt)
		assertEquals("div.listing-chapters_wrap", cfg.selectors.testAsync)
		assertFalse(cfg.capabilities.tagsExclusion)
		assertTrue(cfg.capabilities.year)
		// Untouched capability keys keep the engine default rather than resetting to false.
		assertTrue(cfg.capabilities.multipleTags)
		assertEquals(listOf(SortOrder.ALPHABETICAL, SortOrder.UPDATED, SortOrder.POPULARITY), cfg.sortOrders)
		assertTrue(cfg.images.stripStyleParam)
		assertEquals(listOf("data-src"), cfg.images.imgAttrCandidates)
		assertEquals("img.page", cfg.images.pageImgSelector)
		assertEquals(listOf(StaticTag("Action", "Action"), StaticTag("drama", "drama")), cfg.staticTags)
		assertEquals(mapOf("Publicandose" to "ONGOING"), cfg.extraStatusMap)
	}

	@Test
	fun madaraAcceptsTheFlatSelectorSpelling() {
		val cfg = madara(""""selectPage":"div.reading-content img","selectChapter":"li.wp-manga-chapter"""")
		assertEquals("div.reading-content img", cfg.selectors.page)
		assertEquals("li.wp-manga-chapter", cfg.selectors.chapter)
	}

	@Test
	fun madaraWithoutConfigKeepsEngineDefaults() {
		val cfg = madara("")
		assertEquals(EngineConfig.Madara().selectors, cfg.selectors)
		assertEquals(EngineConfig.Madara().capabilities, cfg.capabilities)
		assertEquals(EngineConfig.Madara().images, cfg.images)
		assertNull(cfg.sortOrders)
		assertTrue(cfg.staticTags.isEmpty())
	}

	@Test
	fun mangaReaderCarriesCapabilitiesStatesTypesAndMirrorDomains() {
		val cfg = mangaReader(
			""""capabilities":{"tagsExclusion":false,"authorSearch":true},
			"sortOrders":["UPDATED","POPULARITY"],"defaultSortOrder":"POPULARITY",
			"availableStates":["ONGOING","FINISHED"],"availableContentTypes":["MANGA","MANHWA"],
			"domains":["mirror.one","example.com"],"statusOverrides":{"Berjalan":"ONGOING"},
			"relativeDateWords":{"hari":"day"}""".trimIndent().replace("\n", ""),
		)
		assertFalse(cfg.capabilities.tagsExclusion)
		assertTrue(cfg.capabilities.authorSearch)
		assertTrue(cfg.capabilities.search)
		assertEquals(listOf(SortOrder.UPDATED, SortOrder.POPULARITY), cfg.sortOrders)
		assertEquals(SortOrder.POPULARITY, cfg.defaultSortOrder)
		assertEquals(listOf("ONGOING", "FINISHED"), cfg.availableStates)
		assertEquals(listOf("MANGA", "MANHWA"), cfg.availableContentTypes)
		// The catalogue domain leads and is never repeated.
		assertEquals(listOf("example.com", "mirror.one"), cfg.domains)
		assertEquals(mapOf("Berjalan" to "ONGOING"), cfg.statusOverrides)
		assertEquals(mapOf("hari" to "day"), cfg.relativeDateWords)
	}

	@Test
	fun mangaReaderEmptyStateListStaysEmpty() {
		// "this source offers no state filter" must not read as "this source said nothing".
		assertEquals(emptyList<String>(), mangaReader(""""availableStates":[]""").availableStates)
	}

	@Test
	fun bundledCatalogueTypedConfigsCarryTheirKnobs() {
		val bundled = File("../data/catalogue.json").takeIf { it.isFile }
			?: File("../../nyora-shared-datadriven/data/catalogue.json")
		val byId = DataDrivenCatalogueParser.parse(bundled.readText()).associateBy { it.catalogueId }

		val doujin = byId.getValue("DOUJIN_HENTAI_NET").toSourceDef()
		val doujinCfg = doujin.config as EngineConfig.Madara
		assertEquals(EngineId.MADARA, doujin.engine)
		assertEquals("li.wp-manga-chapter:contains(Capitulo)", doujinCfg.selectors.chapter)
		assertEquals("div#all img", doujinCfg.selectors.page)
		assertEquals(listOf(SortOrder.ALPHABETICAL, SortOrder.UPDATED, SortOrder.POPULARITY), doujinCfg.sortOrders)

		val baca = byId.getValue("bacakomik").toSourceDef().config as EngineConfig.MangaReader
		assertEquals(listOf("ONGOING", "FINISHED"), baca.availableStates)
		assertEquals(listOf("MANGA", "MANHWA", "MANHUA", "COMICS"), baca.availableContentTypes)
		assertTrue(baca.capabilities.year)

		val desu = byId.getValue("manhwadesu").toSourceDef().config as EngineConfig.MangaReader
		assertFalse(desu.capabilities.tagsExclusion)
		assertEquals("manhwadesu.im", desu.domains.first())
		assertEquals(5, desu.domains.size)

		// Every knob a row ships has to arrive on the typed config with the value the row wrote.
		var mapped = 0
		for (source in byId.values) {
			val raw = source.config
			val typed = source.toSourceDef().config
			// Engines other than these two read rawConfig; their typed config is a placeholder.
			val capabilities = when {
				typed is EngineConfig.Madara && source.engineKey == EngineId.MADARA.key -> typed.capabilities
				typed is EngineConfig.MangaReader -> typed.capabilities
				else -> continue
			}
			mapped += assertCapabilities(source.catalogueId, raw["capabilities"], capabilities)
			val sortOrders = when (typed) {
				is EngineConfig.Madara -> typed.sortOrders
				is EngineConfig.MangaReader -> typed.sortOrders
			}
			(raw["sortOrders"] as? List<*>)?.let { expected ->
				assertEquals(
					"${source.catalogueId}.sortOrders",
					expected.map { SortOrder.valueOf(it as String) },
					sortOrders,
				)
				mapped++
			}
			when (typed) {
				is EngineConfig.Madara -> mapped += assertMadaraSelectors(source.catalogueId, raw, typed)
				is EngineConfig.MangaReader -> {
					(raw["availableStates"] as? List<*>)?.let {
						assertEquals("${source.catalogueId}.availableStates", it, typed.availableStates)
						mapped++
					}
					(raw["availableContentTypes"] as? List<*>)?.let {
						assertEquals("${source.catalogueId}.availableContentTypes", it, typed.availableContentTypes)
						mapped++
					}
					(raw["domains"] as? List<*>)?.let {
						assertTrue(source.catalogueId, typed.domains.containsAll(it.map { d -> d.toString() }))
						mapped++
					}
				}
			}
		}
		// The browsable slice of the bundled catalogue currently carries 190 of them; the floor only
		// has to be high enough that a builder quietly dropping a whole knob cannot pass.
		assertTrue("expected a meaningful number of typed knobs, got $mapped", mapped > 150)
	}

	private fun assertCapabilities(id: String, raw: Any?, typed: FilterCapabilities): Int {
		val expected = raw as? Map<*, *> ?: return 0
		val actual = mapOf(
			"multipleTags" to typed.multipleTags,
			"tagsExclusion" to typed.tagsExclusion,
			"search" to typed.search,
			"searchWithFilters" to typed.searchWithFilters,
			"year" to typed.year,
			"authorSearch" to typed.authorSearch,
		)
		var checked = 0
		for ((key, value) in expected) {
			if (value is Boolean && actual.containsKey(key)) {
				assertEquals("$id.capabilities.$key", value, actual[key])
				checked++
			}
		}
		return checked
	}

	private fun assertMadaraSelectors(id: String, raw: Map<String, Any?>, typed: EngineConfig.Madara): Int {
		val expected = raw["selectors"] as? Map<*, *> ?: return 0
		val actual = mapOf(
			"chapter" to typed.selectors.chapter,
			"page" to typed.selectors.page,
			"bodyPage" to typed.selectors.bodyPage,
			"desc" to typed.selectors.desc,
			"genre" to typed.selectors.genre,
			"date" to typed.selectors.date,
			"state" to typed.selectors.state,
			"alt" to typed.selectors.alt,
			"testAsync" to typed.selectors.testAsync,
		)
		var checked = 0
		for ((key, value) in expected) {
			if (value is String && value.isNotBlank() && actual.containsKey(key)) {
				assertEquals("$id.selectors.$key", value, actual[key])
				checked++
			}
		}
		return checked
	}

	private fun madara(configBody: String): EngineConfig.Madara =
		source("madara", configBody).toSourceDef().config as EngineConfig.Madara

	private fun mangaReader(configBody: String): EngineConfig.MangaReader =
		source("mangareader", configBody).toSourceDef().config as EngineConfig.MangaReader

	private fun source(engine: String, configBody: String): DataDrivenMangaSource {
		val json = """{"sources":[{"id":"probe","name":"Probe","lang":"en","engine":"$engine",
			"domain":"example.com","config":{$configBody}}]}""".trimIndent().replace("\n", "")
		return DataDrivenCatalogueParser.parse(json).single()
	}
}
