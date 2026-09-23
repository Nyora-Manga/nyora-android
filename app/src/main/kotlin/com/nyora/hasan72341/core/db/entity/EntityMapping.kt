package com.nyora.hasan72341.core.db.entity

import com.nyora.hasan72341.core.model.MangaSource
import org.json.JSONArray
import org.json.JSONObject
import com.nyora.hasan72341.mihon.parsers.model.ContentRating
import com.nyora.hasan72341.mihon.parsers.model.Manga
import com.nyora.hasan72341.mihon.parsers.model.MangaChapter
import com.nyora.hasan72341.mihon.parsers.model.MangaState
import com.nyora.hasan72341.mihon.parsers.model.MangaTag
import com.nyora.hasan72341.mihon.parsers.model.MangaSourceRef
import com.nyora.hasan72341.mihon.parsers.model.SortOrder

fun MangaEntity.toManga(
    tags: Collection<MangaTag> = emptyList(),
    chapters: Collection<MangaChapter>? = null,
): Manga = Manga(
    id = id.toString(),
    title = title,
    altTitles = decodeStringList(altTitles),
    url = url,
    publicUrl = publicUrl,
    rating = rating,
    isNsfw = isNsfw,
    contentRating = ContentRating(contentRating),
    coverUrl = coverUrl,
    largeCoverUrl = largeCoverUrl,
    state = MangaState(state),
    authors = decodeStringList(authors),
    source = decodeSourceRef(source),
    description = description,
    tags = tags.ifEmpty { decodeTags(tags = this.tags) }.toList(),
    chapters = chapters?.toList() ?: decodeChapters(this.chapters),
    unread = unread,
    progress = progress,
)

fun Collection<MangaEntity>.toMangaList() = map { it.toManga() }

fun Manga.toEntity() = MangaEntity(
    id = id,
    title = title,
    altTitles = JSONArray(altTitles).toString(),
    url = url,
    publicUrl = publicUrl,
    rating = rating,
    isNsfw = isNsfw,
    contentRating = contentRating?.name,
    coverUrl = coverUrl,
    largeCoverUrl = largeCoverUrl,
    state = state?.name,
    authors = JSONArray(authors).toString(),
    source = JSONObject().put("name", source.name).toString(),
    description = description,
    tags = JSONArray().apply {
        for (tag in tags) {
            put(
                JSONObject()
                    .put("key", tag.key)
                    .put("title", tag.title),
            )
        }
    }.toString(),
    chapters = JSONArray().apply {
        for (chapter in chapters) {
            put(
                JSONObject()
                    .put("id", chapter.id)
                    .put("title", chapter.title)
                    .put("number", chapter.number)
                    .put("volume", chapter.volume)
                    .put("url", chapter.url)
                    .put("scanlator", chapter.scanlator)
                    .put("uploadDate", chapter.uploadDate)
                    .put("branch", chapter.branch),
            )
        }
    }.toString(),
    unread = unread,
    progress = progress,
)

fun SortOrder(name: String, fallback: SortOrder): SortOrder = runCatching {
    SortOrder.valueOf(name)
}.getOrDefault(fallback)

fun MangaState(name: String?): MangaState? = runCatching {
    name?.let { MangaState.valueOf(it) }
}.getOrNull()

fun ContentRating(name: String?): ContentRating? = runCatching {
    name?.let { ContentRating.valueOf(it) }
}.getOrNull()

private fun decodeStringList(raw: String?): List<String> = runCatching {
    val array = JSONArray(raw ?: "[]")
    buildList(array.length()) {
        for (i in 0 until array.length()) {
            add(array.getString(i))
        }
    }
}.getOrDefault(emptyList())

private fun decodeTags(tags: String): List<MangaTag> = runCatching {
    val array = JSONArray(tags)
    buildList(array.length()) {
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            add(
                MangaTag(
                    key = obj.optString("key"),
                    title = obj.optString("title"),
                ),
            )
        }
    }
}.getOrDefault(emptyList())

private fun decodeChapters(chapters: String): List<MangaChapter> = runCatching {
    val array = JSONArray(chapters)
    buildList(array.length()) {
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            add(
                MangaChapter(
                    id = obj.optString("id"),
                    title = obj.optString("title"),
                    number = obj.optDouble("number", 0.0).toFloat(),
                    volume = obj.optInt("volume", 0),
                    url = obj.optString("url"),
                    scanlator = obj.optString("scanlator").takeIf { it.isNotEmpty() },
                    uploadDate = obj.optLong("uploadDate", 0L),
                    branch = obj.optString("branch").takeIf { it.isNotEmpty() },
                    index = i,
                ),
            )
        }
    }
}.getOrDefault(emptyList())

private fun decodeSourceRef(raw: String): MangaSourceRef {
    val name = runCatching { JSONObject(raw).optString("name") }.getOrDefault(raw)
    return when {
        name == "LOCAL" -> MangaSourceRef.Local
        name == "UNKNOWN" -> MangaSourceRef.Unknown
        name.startsWith("data:") -> runCatching { MangaSourceRef.Data(name) }.getOrDefault(MangaSourceRef.Unknown)
        name.startsWith("MIHON_") || name.startsWith("mihon:") || name.all(Char::isDigit) -> MangaSourceRef.Unknown
        name.startsWith("JS_") -> MangaSourceRef.Script(name)
        else -> MangaSourceRef.Parser(name)
    }
}
