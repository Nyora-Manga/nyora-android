package com.nyora.hasan72341.suggestions.data

import com.nyora.hasan72341.core.network.BaseHttpClient
import com.nyora.hasan72341.tachiyomi.network.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal MangaBaka series metadata used by the source-less preview (synopsis + total chapters).
 * Separate from the AniList discovery feed and from the MangaBaka *scrobbler*.
 */
data class MangaBakaSeriesInfo(
	val title: String,
	val coverUrl: String?,
	val description: String?,
	val totalChapters: String?,
	val status: String?,
	val type: String?,
	val year: Int?,
	val rating: Double?,
	val genres: List<String>,
)

@Singleton
class MangaBakaMetaRepository @Inject constructor(
	@BaseHttpClient private val okHttpClient: OkHttpClient,
) {
	private val json = Json { ignoreUnknownKeys = true; isLenient = true }

	/** First usable MangaBaka series matching [query], or null. */
	suspend fun searchFirst(query: String): MangaBakaSeriesInfo? {
		val url = SEARCH_URL.toHttpUrl().newBuilder()
			.addQueryParameter("q", query)
			.addQueryParameter("limit", "5")
			.build()
		val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
		val body = okHttpClient.newCall(request).await().use { resp ->
			if (!resp.isSuccessful) return null
			resp.body?.string() ?: return null
		}
		// Decode each row independently so one malformed entry doesn't discard the whole result.
		val rows = runCatching { json.parseToJsonElement(body).jsonObject["data"]?.jsonArray }.getOrNull().orEmpty()
		return rows.asSequence()
			.mapNotNull { runCatching { json.decodeFromJsonElement<SeriesDto>(it) }.getOrNull() }
			.firstOrNull { it.state != "merged" && !it.title.isNullOrBlank() }
			?.toInfo()
	}

	private fun SeriesDto.toInfo() = MangaBakaSeriesInfo(
		title = title ?: romanized_title ?: native_title ?: "Untitled",
		coverUrl = cover?.bestUrl(),
		description = description?.takeIf { it.isNotBlank() },
		totalChapters = total_chapters?.takeIf { it.isNotBlank() },
		status = status?.takeIf { it.isNotBlank() },
		type = type?.takeIf { it.isNotBlank() },
		year = year,
		rating = rating,
		genres = genres.map(::prettyGenre),
	)

	@Serializable
	private data class SeriesDto(
		val title: String? = null,
		val romanized_title: String? = null,
		val native_title: String? = null,
		val description: String? = null,
		val total_chapters: String? = null,
		val status: String? = null,
		val type: String? = null,
		val year: Int? = null,
		val rating: Double? = null,
		val state: String? = null,
		val cover: CoverDto? = null,
		val genres: List<String> = emptyList(),
	)

	@Serializable
	private data class CoverDto(
		val x350: CoverVariant? = null,
		val x250: CoverVariant? = null,
		val raw: CoverRaw? = null,
	) {
		fun bestUrl(): String? = x350?.x1 ?: x250?.x1 ?: raw?.url
	}

	@Serializable
	private data class CoverVariant(val x1: String? = null)

	@Serializable
	private data class CoverRaw(val url: String? = null)

	// "school_life" -> "School Life"
	private fun prettyGenre(raw: String): String =
		raw.split('_').joinToString(" ") { part ->
			part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
		}

	private companion object {
		const val SEARCH_URL = "https://api.mangabaka.org/v1/series/search"
	}
}
