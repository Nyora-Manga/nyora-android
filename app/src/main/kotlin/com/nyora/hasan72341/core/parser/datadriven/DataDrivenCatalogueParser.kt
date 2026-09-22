package com.nyora.hasan72341.core.parser.datadriven

import app.nyora.data.engine.EngineRegistry
import com.nyora.hasan72341.core.SourcePatches
import com.nyora.hasan72341.core.model.DataDrivenMangaSource
import com.nyora.hasan72341.core.parser.NatoMangaRepository
import com.nyora.hasan72341.core.parser.ToonDexMangaRepository
import com.nyora.hasan72341.mihon.parsers.model.ContentType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Turns `catalogue.json` into the browsable [DataDrivenMangaSource] list.
 *
 * The rules mirror the shared `DataDrivenCatalog` so every Nyora client browses the same set of
 * sources: malformed and repeated ids are fatal, and rows that are broken, patched dead or retired
 * are dropped, as are rows whose engine nothing can render. Android is a native client with a
 * WebView anti-bot solver, so Cloudflare "Wall B" rows stay browsable here, unlike on the web.
 */
object DataDrivenCatalogueParser {

	/** Engines the registry has no generic implementation for; a native repository serves them. */
	val NATIVE_BACKED_ENGINES: Set<String> = setOf("mangafire", "mangaplus")

	/**
	 * Identities a native repository serves by source name rather than by engine key, taken from
	 * the routing table in `MangaRepository.Factory` so the two cannot drift apart.
	 */
	private val NATIVE_BACKED_SOURCE_NAMES: Set<String> =
		NatoMangaRepository.SOURCE_NAMES + ToonDexMangaRepository.SOURCE_NAMES

	private val ID_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

	/** Row fields the engines read back out of `SourceDef.rawConfig` rather than off the row. */
	private val RUNTIME_METADATA_KEYS = listOf("antiBot", "cfWall", "needsCustomLogic", "availableContentTypes")

	private const val DEFAULT_PAGE_SIZE = 20

	/**
	 * @throws IllegalArgumentException when [json] is not a usable catalogue: unparseable, missing
	 * or empty `sources`, a malformed id, or an id repeated case-insensitively. Skipped rows are
	 * dropped silently, but a catalogue that carries no `sources` array at all never reads as empty.
	 */
	fun parse(json: String): List<DataDrivenMangaSource> = parse(parseDocument(json))

	/** The catalogue as a JSON document, so a caller can read [hashOf] before paying for [parse]. */
	fun parseDocument(json: String): JSONObject = try {
		JSONObject(json)
	} catch (e: Exception) {
		throw IllegalArgumentException("Invalid data-driven catalogue", e)
	}

	/** The generator's content hash of [document], or null for a catalogue that carries none. */
	fun hashOf(document: JSONObject): String? = document.optString("hash").takeIf { it.isNotBlank() }

	/** See [parse]. */
	fun parse(document: JSONObject): List<DataDrivenMangaSource> = try {
		val rows = document.optJSONArray("sources")
			?: throw IllegalArgumentException("Data-driven catalogue must contain a sources array")
		require(rows.length() > 0) { "Data-driven catalogue contains no sources" }
		val seenIds = HashSet<String>(rows.length())
		buildList(rows.length()) {
			for (index in 0 until rows.length()) {
				val row = rows.getJSONObject(index)
				val rowId = row.getString("id")
				require(ID_REGEX.matches(rowId)) { "Invalid data-driven source id: $rowId" }
				require(seenIds.add(rowId.lowercase(Locale.ROOT))) { "Duplicate data-driven source id: $rowId" }
				val engineKey = row.optString("engine")
				if (row.isBrowsable(rowId, engineKey)) {
					add(row.toDataDrivenSource(rowId, engineKey))
				}
			}
		}
	} catch (e: IllegalArgumentException) {
		throw e
	} catch (e: Exception) {
		throw IllegalArgumentException("Invalid data-driven catalogue", e)
	}

	private fun JSONObject.isBrowsable(rowId: String, engineKey: String): Boolean {
		// A native adapter keeps serving its rows even when the catalogue flags the generic route
		// dead, which is how the MangaPlus rows survive the loss of their JSON API and the Nato
		// family survives the mangabox chapter list it can no longer read.
		val isNativelyServed = engineKey in NATIVE_BACKED_ENGINES ||
			DataDrivenMangaSource.PREFIX + rowId.lowercase(Locale.ROOT) in NATIVE_BACKED_SOURCE_NAMES
		val isDead = (optBoolean("broken", false) && !isNativelyServed) ||
			SourcePatches.DEAD_SOURCES.hasDataDrivenPatch(rowId)
		// A retired identity must never re-enter the catalogue under its old id: its successor row
		// already owns the manga and history rows that were hashed from it.
		val isRetired = canonicalDataSourceId(rowId) != rowId.lowercase(Locale.ROOT)
		val isSupported = EngineRegistry.supports(engineKey) || isNativelyServed
		return !isDead && !isRetired && isSupported
	}

	private fun JSONObject.toDataDrivenSource(rowId: String, engineKey: String): DataDrivenMangaSource {
		val domain = (SourcePatches.DOMAIN_OVERRIDES.dataDrivenPatchValue(rowId) ?: getString("domain"))
			.sanitizeDomain()
		require(domain.isNotEmpty()) { "Data-driven source $rowId has no domain" }
		val config = LinkedHashMap<String, Any?>()
		optJSONObject("config")?.let { config.putAll(it.toValueMap()) }
		for (key in RUNTIME_METADATA_KEYS) {
			if (has(key) && !isNull(key)) {
				config[key] = unwrap(get(key))
			}
		}
		val pageSize = (config["pageSize"] as? Number)?.toInt() ?: optInt("pageSize", DEFAULT_PAGE_SIZE)
		if (!config.containsKey("pageSize")) {
			config["pageSize"] = pageSize
		}
		return DataDrivenMangaSource(
			catalogueId = rowId,
			engineKey = engineKey,
			title = SourcePatches.TITLE_OVERRIDES.dataDrivenPatchValue(rowId)
				?: optString("name").ifEmpty { rowId },
			locale = optString("lang").ifEmpty { "en" },
			nsfw = optBoolean("nsfw", false),
			domain = domain,
			contentType = contentTypeOf(optString("contentType")),
			config = config,
			pageSize = pageSize,
			antiBot = stringOrNull("antiBot"),
			cfWall = stringOrNull("cfWall"),
		)
	}

	private fun contentTypeOf(raw: String): ContentType = when (raw.uppercase(Locale.ROOT)) {
		"MANHWA" -> ContentType.MANHWA
		"MANHUA" -> ContentType.MANHUA
		"HENTAI", "HENTAI_MANGA" -> ContentType.HENTAI_MANGA
		"COMICS" -> ContentType.COMICS
		"NOVEL" -> ContentType.NOVEL
		"ONE_SHOT" -> ContentType.ONE_SHOT
		"DOUJINSHI" -> ContentType.DOUJINSHI
		"IMAGE_SET" -> ContentType.IMAGE_SET
		"OTHER" -> ContentType.OTHER
		else -> ContentType.MANGA
	}
}

/** Strip any scheme and path so a stray URL in the catalogue cannot derail URL building. */
private fun String.sanitizeDomain(): String = trim().substringAfter("://").substringBefore('/')

private fun JSONObject.stringOrNull(name: String): String? = optString(name).takeIf { it.isNotBlank() }

private fun JSONObject.toValueMap(): Map<String, Any?> = buildMap(length()) {
	for (key in keys()) {
		put(key, unwrap(this@toValueMap.get(key)))
	}
}

private fun JSONArray.toValueList(): List<Any?> = List(length()) { index -> unwrap(get(index)) }

private fun unwrap(value: Any?): Any? = when (value) {
	is JSONObject -> value.toValueMap()
	is JSONArray -> value.toValueList()
	JSONObject.NULL -> null
	else -> value
}

/** Patch keys are written either bare or as `DD_<id>`; the row id itself is never rewritten. */
private fun String.withoutDataDrivenPrefix(): String =
	if (startsWith("DD_", ignoreCase = true)) substring(3) else this

/** The patch entry for a catalogue row, accepting either spelling of the key. */
internal fun <T> Map<String, T>.dataDrivenPatchValue(rowId: String): T? {
	val bareId = rowId.withoutDataDrivenPrefix()
	return entries.firstOrNull { (key, _) -> key.equals("DD_$bareId", ignoreCase = true) }?.value
		?: entries.firstOrNull { (key, _) -> key.withoutDataDrivenPrefix().equals(bareId, ignoreCase = true) }?.value
}

private fun Set<String>.hasDataDrivenPatch(rowId: String): Boolean {
	val bareId = rowId.withoutDataDrivenPrefix()
	return any { key -> key.withoutDataDrivenPrefix().equals(bareId, ignoreCase = true) }
}
