package com.nyora.hasan72341.js

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** One entry of `assets/parsers_sources.json`. */
@Serializable
private data class JsSourceMeta(
    val id: String,
    val className: String = "",
    val title: String = "",
    val locale: String = "en",
    val domain: String = "",
    val family: String = "",
    val isNsfw: Boolean = false,
)

/**
 * Loads the staged JS source catalogue (`assets/parsers_sources.json`) and exposes it as
 * [NyoraJsMangaSource]s, plus the shared [NyoraJsEngine]. Analogous to MihonExtensionManager.
 */
@Singleton
class NyoraJsSourcesManager @Inject constructor(
    @ApplicationContext private val context: Context,
    val engine: NyoraJsEngine,
    private val otaUpdater: NyoraJsOtaUpdater,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val sources: List<NyoraJsMangaSource> by lazy { load() }
    private val byName: Map<String, NyoraJsMangaSource> by lazy { sources.associateBy { it.name } }
    private val byPortableName: Map<String, NyoraJsMangaSource> by lazy { sources.associateBy { it.portableName } }

    private fun load(): List<NyoraJsMangaSource> = runCatching {
        val text = otaUpdater.sources()
            ?: context.assets.open("parsers_sources.json").bufferedReader().use { it.readText() }
        json.decodeFromString<List<JsSourceMeta>>(text).map { m ->
            NyoraJsMangaSource(
                jsId = m.id,
                title = m.title.ifBlank { m.id },
                locale = m.locale.ifBlank { "en" },
                domain = m.domain,
                nsfw = m.isNsfw,
            )
        }
    }.onFailure { Log.e("NyoraJS", "Failed to load JS sources", it) }.getOrElse { emptyList() }

    fun getJsMangaSources(): List<NyoraJsMangaSource> = sources

    fun getByName(name: String): NyoraJsMangaSource? = byName[name] ?: byPortableName[name]
}
