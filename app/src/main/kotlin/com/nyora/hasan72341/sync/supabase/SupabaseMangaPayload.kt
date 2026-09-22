package com.nyora.hasan72341.sync.supabase

import com.nyora.hasan72341.backups.data.NyoraSourceIdentity
import com.nyora.hasan72341.core.db.entity.MangaEntity
import org.json.JSONObject

internal fun MangaEntity.toRemoteManga(uid: String, updatedAt: String): JSONObject? {
    val sourceName = runCatching { JSONObject(source).getString("name") }.getOrDefault(source)
    if (NyoraSourceIdentity.canonicalize(sourceName) == null) return null

    return JSONObject().apply {
        put("user_id", uid)
        put("id", id)
        put("title", title)
        put("alt_titles", altTitles ?: "[]")
        put("url", url)
        put("public_url", publicUrl)
        put("rating", rating)
        put("is_nsfw", isNsfw)
        contentRating?.let { put("content_rating", it) }
        put("cover_url", coverUrl)
        largeCoverUrl?.let { put("large_cover_url", it) }
        state?.let { put("state", it) }
        put("authors", authors ?: "[]")
        put("source_ref", source)
        put("description", description)
        put("tags", tags)
        put("updated_at", updatedAt)
    }
}
