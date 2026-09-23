package com.nyora.hasan72341.mihon.parsers.model

import kotlinx.serialization.Serializable

/**
 * The actual source identity. Aliased to the parser library's
 * [org.koitharu.kotatsu.parsers.model.MangaSource] interface so that [MangaParserSource],
 * native [ContentSource]-backed sources and local/unknown/external/JS sources can all be
 * treated uniformly.
 */
typealias MangaSource = org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * Lightweight, serializable reference to a source, used for storage and passing around without
 * resolving the concrete [MangaSource].
 */
@Serializable
sealed interface MangaSourceRef {
	val name: String

	@Serializable
	data object Local : MangaSourceRef {
		override val name = "LOCAL"
	}

	@Serializable
	data object Unknown : MangaSourceRef {
		override val name = "UNKNOWN"
	}

	/** Canonical Nyora data-catalogue source identity used by portable storage. */
	@Serializable
	data class Data(override val name: String) : MangaSourceRef {
		init {
			require(Regex("data:[a-z0-9][a-z0-9._-]*").matches(name))
		}
	}

	/** A native parser source (an entry of [MangaParserSource]). */
	@Serializable
	data class Parser(override val name: String) : MangaSourceRef

	/** A JS ("JS_"-prefixed) source backed by the Nyora JS engine. */
	@Serializable
	data class Script(override val name: String) : MangaSourceRef

}

@Serializable
data class MangaRepo(
	val name: String,
	val indexUrl: String,
)

val defaultRepos: List<MangaRepo> = listOf(
	MangaRepo(
		name = "Keiyoushi",
		indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
	),
)
