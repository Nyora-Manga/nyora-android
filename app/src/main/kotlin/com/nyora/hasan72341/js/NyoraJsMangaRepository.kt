package com.nyora.hasan72341.js

import com.nyora.hasan72341.core.cache.MemoryContentCache
import com.nyora.hasan72341.core.parser.CachingMangaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import com.nyora.hasan72341.mihon.parsers.model.ContentRating
import com.nyora.hasan72341.mihon.parsers.model.Manga
import com.nyora.hasan72341.mihon.parsers.model.MangaChapter
import com.nyora.hasan72341.mihon.parsers.model.MangaListFilter
import com.nyora.hasan72341.mihon.parsers.model.MangaListFilterCapabilities
import com.nyora.hasan72341.mihon.parsers.model.MangaListFilterOptions
import com.nyora.hasan72341.mihon.parsers.model.MangaPage
import com.nyora.hasan72341.mihon.parsers.model.MangaSourceRef
import com.nyora.hasan72341.mihon.parsers.model.MangaState
import com.nyora.hasan72341.mihon.parsers.model.MangaTag
import com.nyora.hasan72341.mihon.parsers.model.SortOrder

/** Adapts one JS-bundle parser (run via [NyoraJsEngine]) to the app's repository interface. */
class NyoraJsMangaRepository(
    override val source: NyoraJsMangaSource,
    cache: MemoryContentCache,
    private val engine: NyoraJsEngine,
) : CachingMangaRepository(cache) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val sortOrders: Set<SortOrder> = setOf(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override var defaultSortOrder: SortOrder = SortOrder.POPULARITY

    override val filterCapabilities: MangaListFilterCapabilities
        @OptIn(org.koitharu.kotatsu.parsers.InternalParsersApi::class)
        get() = MangaListFilterCapabilities(isSearchSupported = true)

    // The JS parsers are page-based; track a 1-based page from the framework's item offset.
    private var lastOffset = -1
    private var page = 1

    override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> =
        withContext(Dispatchers.IO) {
            if (offset == 0) page = 1 else if (offset > lastOffset) page++
            lastOffset = offset
            val args = buildJsonObject {
                put("page", page)
                put("order", (order ?: defaultSortOrder).name)
                putJsonObject("filter") {
                    filter?.query?.takeIf { it.isNotBlank() }?.let { put("query", it) }
                }
            }
            val result = engine.run(source.jsId, "list", args.toString())
            runCatching { json.decodeFromString<List<JsManga>>(result) }
                .getOrDefault(emptyList())
                .map { it.toManga() }
        }

    override suspend fun getDetailsImpl(manga: Manga): Manga = withContext(Dispatchers.IO) {
        val args = buildJsonObject {
            put("url", manga.url)
            put("title", manga.title)
        }
        val result = engine.run(source.jsId, "details", args.toString())
        val js = runCatching { json.decodeFromString<JsManga>(result) }.getOrNull()
            ?: return@withContext manga
        js.toManga().copy(id = manga.id)
    }

    override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
        val args = buildJsonObject {
            put("url", chapter.url)
            chapter.branch?.let { put("branch", it) }
        }
        val result = engine.run(source.jsId, "pages", args.toString())
        runCatching { json.decodeFromString<List<JsPage>>(result) }
            .getOrDefault(emptyList())
            .map { MangaPage(url = it.url, headers = it.headers ?: emptyMap(), source = source) }
    }

    override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = emptyList()

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    @OptIn(org.koitharu.kotatsu.parsers.InternalParsersApi::class)
    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions(availableTags = emptySet(), availableStates = emptySet())

    // Stable, deterministic ids (mirrors the app's `generateContentId` scheme) so a JS manga
    // resolves to the same row across history/favourites/sessions.
    private fun mangaId(url: String): String =
        ("${source.name}|$url".hashCode().toLong() and Long.MAX_VALUE).toString()

    private fun chapterId(url: String): String =
        ("${source.name}|chapter|$url".hashCode().toLong() and Long.MAX_VALUE).toString()

    private fun JsManga.toManga(): Manga = Manga(
        // Trust the bundle-stamped canonical id (nyoraId) so it matches the other platforms.
        id = this.id?.takeIf { it.isNotBlank() } ?: mangaId(url),
        title = title,
        altTitles = altTitles ?: emptyList(),
        url = url,
        publicUrl = publicUrl ?: url,
        rating = rating?.toFloat() ?: -1f,
        isNsfw = source.nsfw,
        contentRating = contentRating?.let { runCatching { ContentRating.valueOf(it.uppercase()) }.getOrNull() },
        coverUrl = coverUrl ?: "",
        largeCoverUrl = largeCoverUrl,
        state = state?.let { runCatching { MangaState.valueOf(it.uppercase()) }.getOrNull() },
        authors = authors ?: emptyList(),
        source = MangaSourceRef.Data(source.portableName),
        description = description ?: "",
        tags = (tags ?: emptyList()).map { MangaTag(key = it.key, title = it.title) },
        chapters = (chapters ?: emptyList()).map { it.toChapter() },
    )

    private fun JsChapter.toChapter(): MangaChapter = MangaChapter(
        id = this.id?.takeIf { it.isNotBlank() } ?: chapterId(url),
        title = title ?: "",
        number = number?.toFloat() ?: 0f,
        volume = volume?.toInt() ?: 0,
        url = url,
        scanlator = scanlator,
        uploadDate = uploadDate?.toLong() ?: 0L,
        branch = branch,
    )
}

// JSON shapes the parser methods return (matches the iOS JSMangaParser decode structs).

@Serializable
private data class JsManga(
    val id: String? = null,
    val url: String,
    val publicUrl: String? = null,
    val coverUrl: String? = null,
    val largeCoverUrl: String? = null,
    val title: String,
    val altTitles: List<String>? = null,
    val rating: Double? = null,
    val tags: List<JsTag>? = null,
    val state: String? = null,
    val authors: List<String>? = null,
    val contentRating: String? = null,
    val description: String? = null,
    val chapters: List<JsChapter>? = null,
)

@Serializable
private data class JsTag(val title: String, val key: String)

@Serializable
private data class JsChapter(
    val id: String? = null,
    val url: String,
    val title: String? = null,
    val number: Double? = null,
    val volume: Double? = null,
    val branch: String? = null,
    val uploadDate: Double? = null,
    val scanlator: String? = null,
)

@Serializable
private data class JsPage(
    val id: String? = null,
    val url: String,
    val preview: String? = null,
    // Per-page request headers from the JS parser (e.g. Manganato's image-hotlink Referer).
    val headers: Map<String, String>? = null,
)
