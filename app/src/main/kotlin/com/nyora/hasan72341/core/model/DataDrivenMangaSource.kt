package com.nyora.hasan72341.core.model

import com.nyora.hasan72341.core.parser.datadriven.legacyParserSourceName
import com.nyora.hasan72341.mihon.parsers.model.ContentSource
import com.nyora.hasan72341.mihon.parsers.model.ContentType
import com.nyora.hasan72341.mihon.parsers.model.MangaSource
import java.util.Locale

/**
 * A source defined by catalogue data rather than code: [engineKey] picks a bundled engine that
 * renders the site from [domain] plus [config].
 *
 * [name] is the portable identity every Nyora client persists, so it is always the canonical
 * lower-cased `data:<id>` form. [catalogueId] keeps the row's original spelling, which the engine
 * needs for `SourceDef.id` and which the legacy manga-id hash is derived from.
 */
data class DataDrivenMangaSource(
	val catalogueId: String,
	val engineKey: String,
	val title: String,
	override val locale: String,
	val nsfw: Boolean,
	/** Host only, with the `SourcePatches` relocation applied. */
	val domain: String,
	override val contentType: ContentType,
	/** The row's `config` object plus the runtime metadata the engines read back out of it. */
	val config: Map<String, Any?>,
	val pageSize: Int,
	val antiBot: String?,
	/** Cloudflare wall class; `"B"` rows need the WebView solver, which Android has. */
	val cfWall: String?,
) : MangaSource, ContentSource {

	override val name: String
		get() = PREFIX + catalogueId.lowercase(Locale.ROOT)

	/** The retired parser enum this row's persisted manga and chapter ids are hashed from. */
	val legacyParserName: String
		get() = legacyParserSourceName(catalogueId)

	/**
	 * Identity is the [name] alone. Source instances are map keys (the repository factory's cache,
	 * `MemoryContentCache`), and the generated equality would span the whole [config] map, so a
	 * catalogue refresh that only re-tuned a selector would strand every entry keyed by the old row.
	 */
	override fun equals(other: Any?): Boolean =
		this === other || (other is DataDrivenMangaSource && other.name == name)

	override fun hashCode(): Int = name.hashCode()

	companion object {

		const val PREFIX = "data:"
	}
}
