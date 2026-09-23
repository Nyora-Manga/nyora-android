package com.nyora.hasan72341.sync.supabase

import android.content.Context
import com.nyora.hasan72341.backups.data.NyoraSourceIdentity
import com.nyora.hasan72341.core.db.MangaDatabase
import com.nyora.hasan72341.core.exceptions.SyncApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nyora.hasan72341.core.db.entity.MangaEntity
import com.nyora.hasan72341.core.network.BaseHttpClient
import com.nyora.hasan72341.scrobbling.common.data.ScrobblingEntity

@Singleton
class SupabaseSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MangaDatabase,
    @BaseHttpClient private val http: OkHttpClient,
    private val config: SupabaseConfig,
    private val barrier: SupabaseSyncBarrier,
) {

    private val historyDao get() = database.getHistoryDao()
    private val favouritesDao get() = database.getFavouritesDao()
    private val bookmarksDao get() = database.getBookmarksDao()
    private val mangaDao get() = database.getMangaDao()
    private val categoriesDao get() = database.getFavouriteCategoriesDao()
    private val preferencesDao get() = database.getPreferencesDao()
    private val scrobblingDao get() = database.getScrobblingDao()

    /**
     * Remote manga id -> the local id that row hashes to, for the pull in progress.
     *
     * Older clients wrote a server-generated id for a data-catalogue manga, so the same manga can
     * exist in the cloud under both that id and the canonical one. [pullMangaIdentities] fills this
     * map from the whole remote parent set and every dependent pull resolves its `manga_id` through
     * it; [pullAll] clears it first.
     */
    private val pulledMangaIdAliases = HashMap<String, String>()

    /**
     * Remote manga id -> canonical `data:` source of that manga, for the pull in progress; only
     * data-source rows are listed. A history or bookmark row of such a manga whose chapter id is
     * not a hash was written by a shipped build and is re-keyed before it is stored.
     */
    private val pulledMangaSourceIds = HashMap<String, String>()

    /** Local manga id -> urls of its stored chapters, read at most once per pull. */
    private val storedChapterUrlsByManga = HashMap<String, Set<String>>()

    private val JSON_MT = "application/json; charset=utf-8".toMediaType()
    private val syncFunctionUrl get() = "${config.url}/functions/v1/nyora-sync"

    // -- Auth (email/password against the self-hosted OAuth2 server) --

    private fun applyTokenResponse(json: JSONObject, email: String? = null): Boolean {
        val previousUserId = config.userId
        config.accessToken = json.getString("access_token")
        config.refreshToken = json.optString("refresh_token", config.refreshToken)
        config.userId = json.optString("user_id", "")
            .ifBlank { config.parseUserIdFromJwt(config.accessToken) }
        email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let {
            config.accountEmail = it
        }
        if (previousUserId.isNotBlank() && previousUserId != config.userId) {
            config.lastSyncTimestamp = SupabaseConfig.INITIAL_SYNC_TIMESTAMP
        }
        config.saveTokens()
        return config.userId.isNotBlank()
    }

    /** OAuth2 password grant (form-encoded) → POST /auth/token. */
    suspend fun signIn(email: String, password: String): Boolean = barrier.withSync {
        withContext(Dispatchers.IO) {
            if (!config.isConfigured) return@withContext false
            val body = okhttp3.FormBody.Builder()
                .add("grant_type", "password")
                .add("username", email.trim())
                .add("password", password)
                .build()
            val req = Request.Builder().url("${config.url}/auth/token").post(body).build()
            runCatching {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext false
                    applyTokenResponse(JSONObject(resp.body!!.string()), email)
                }
            }.getOrDefault(false)
        }
    }

    /** Create an account → POST /auth/register {email,password}; returns tokens on success. */
    suspend fun register(email: String, password: String): Boolean = barrier.withSync {
        withContext(Dispatchers.IO) {
            if (!config.isConfigured) return@withContext false
            val payload = """{"email":${email.trim().jq},"password":${password.jq}}""".toRequestBody(JSON_MT)
            val req = Request.Builder().url("${config.url}/auth/register").post(payload).build()
            runCatching {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext false
                    applyTokenResponse(JSONObject(resp.body!!.string()), email)
                }
            }.getOrDefault(false)
        }
    }

    suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
        if (!config.isConfigured || config.refreshToken.isBlank()) return@withContext false
        val body = okhttp3.FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", config.refreshToken)
            .build()
        val req = Request.Builder().url("${config.url}/auth/token").post(body).build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                applyTokenResponse(JSONObject(resp.body!!.string()))
            }
        }.getOrDefault(false)
    }

    suspend fun signOut() = barrier.withSync { config.clearTokens() }

    private suspend fun refreshTokenIfExpired(): Boolean {
        // Decode JWT payload to check exp
        val token = config.accessToken
        if (token.isBlank()) return false
        val exp = runCatching {
            val payload = token.split(".")[1]
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            val decoded = String(android.util.Base64.decode(padded, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP))
            JSONObject(decoded).optLong("exp", 0L)
        }.getOrDefault(0L)
        if (exp > 0L && exp * 1000 < System.currentTimeMillis() + 60_000) {
            return refreshToken()
        }
        return true
    }

    // -- Push --

    suspend fun syncNow() = barrier.withSync {
        runSyncOperation {
            val previousCursor = config.lastSyncTimestamp
            val completedThrough = now()
            val isBootstrap = previousCursor == SupabaseConfig.INITIAL_SYNC_TIMESTAMP
            android.util.Log.d("SupabaseSync", "syncNow: bootstrap=$isBootstrap timestamp=$previousCursor")
            pushAll(if (isBootstrap) 0L else parseEpochMilli(previousCursor))
            pullAll(if (isBootstrap) SupabaseConfig.INITIAL_SYNC_TIMESTAMP else previousCursor)
            config.markSyncSucceeded(completedThrough)
            android.util.Log.d("SupabaseSync", "syncNow: complete")
        }
    }

    suspend fun restoreFromCloud() = barrier.withSync {
        runSyncOperation {
            val completedThrough = now()
            pullAll(SupabaseConfig.INITIAL_SYNC_TIMESTAMP)
            config.markSyncSucceeded(completedThrough)
        }
    }

    suspend fun pushAll() = barrier.withSync {
        runSyncOperation {
            pushAll(parseEpochMilli(config.lastSyncTimestamp))
        }
    }

    private suspend fun runSyncOperation(block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        try {
            if (!config.isAuthenticated) {
                throw SyncApiException("Sign in required", 401)
            }
            if (!refreshTokenIfExpired()) {
                throw SyncApiException("Your session expired. Sign in again.", 401)
            }
            block()
        } catch (e: Throwable) {
            config.markSyncFailed(e)
            throw e
        }
    }

    private suspend fun pushAll(cutoff: Long) {
        pushFavourites(cutoff)
        pushHistory(cutoff)
        pushBookmarks(cutoff)
        pushCategories()
        pushMangaCategories()
        pushMangaPrefs()
        pushSourcePrefs()
        pushTracking()
        pushExtensionRepos()
    }

    private suspend fun pushExtensionRepos() {
        val uid = config.userId
        val entities = runCatching { database.getExternalExtensionRepoDao().findAll() }.getOrNull() ?: return
        val now = now()
        
        val text = fetch("nyora_extension_repos?select=type,base_url")
        if (text != null) {
            runCatching {
                val arr = JSONArray(text)
                val localKeys = entities.map { "${it.type.name}|${it.baseUrl}" }.toSet()
                for (i in 0 until arr.length()) {
                    val row = arr.getJSONObject(i)
                    val type = row.getString("type")
                    val baseUrl = row.getString("base_url")
                    val key = "$type|$baseUrl"
                    if (!localKeys.contains(key)) {
                        deleteRemoteRepo(type, baseUrl)
                    }
                }
            }
        }
        
        if (entities.isEmpty()) return
        val rows = JSONArray()
        for (r in entities) {
            rows.put(JSONObject().apply {
                put("user_id", uid)
                put("type", r.type.name)
                put("base_url", r.baseUrl)
                put("name", r.name)
                r.shortName?.let { put("short_name", it) }
                put("website", r.website)
                put("signing_key_fingerprint", r.signingKeyFingerprint)
                put("created_at", r.createdAt)
                put("updated_at", now())
            })
        }
        upsert("nyora_extension_repos", rows)
    }

    private fun deleteRemoteRepo(type: String, baseUrl: String) {
        runCatching {
            val body = JSONObject().apply {
                put("action", "deleteExtensionRepo")
                put("table", "nyora_extension_repos")
                put("type", type)
                put("base_url", baseUrl)
            }.toString().toRequestBody(JSON_MT)
            val req = Request.Builder()
                .url(syncFunctionUrl)
                .header("apikey", config.anonKey)
                .header("Authorization", "Bearer ${config.accessToken}")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("delete repo failed ${resp.code}: ${resp.body?.string().orEmpty()}")
                }
            }
        }
    }

    private suspend fun pushSourcePrefs() {
        val uid = config.userId
        val entities = runCatching { database.getSourcesDao().findAll() }.getOrNull() ?: return
        if (entities.isEmpty()) return
        val rows = JSONArray()
        for (s in entities) {
            val sourceId = NyoraSourceIdentity.canonicalize(s.source) ?: continue
            rows.put(JSONObject().apply {
                put("user_id", uid)
                put("source_id", sourceId)
                put("is_pinned", s.isPinned)
                put("is_enabled", s.isEnabled)
                put("updated_at", now())
            })
        }
        upsert("nyora_source_prefs", rows)
    }

    // -- Tracking (scrobbling) --

    /**
     * Push every scrobbling link/state to nyora_tracking (canonical snake_case schema).
     * ScrobblingEntity has no updated_at column, so all rows are pushed each time with
     * updated_at = now(); LWW on the server resolves conflicts. The entity `id` is a
     * local surrogate and is NOT synced (see NYORA_TRACKING_SCHEMA.md §4).
     */
    private suspend fun pushTracking() {
        val uid = config.userId
        val entities = runCatching {
            scrobblingDao.dumpEnabled().toList()
        }.getOrNull()
        if (entities == null) {
            android.util.Log.w("SupabaseSync", "pushTracking: dumpEnabled returned null")
            return
        }
        if (entities.isEmpty()) {
            android.util.Log.d("SupabaseSync", "pushTracking: no data to push")
            return
        }
        val nowStr = now()
        val rows = JSONArray()
        for (e in entities) {
            val trackerId = scrobblerSlug(e.scrobbler) ?: continue
            val dto = SbTracking(
                trackerId = trackerId,
                mangaId = e.mangaId,
                remoteId = e.targetId.toString(),
                sourceId = "",
                title = "",
                status = canonicalStatus(e.scrobbler, e.status) ?: "",
                score = e.rating,
                lastReadChapter = e.chapter.toFloat(),
                lastReadVolume = 0,
                totalChapters = 0,
                totalVolumes = 0,
                chapterOffset = 0,
                startedAt = "",
                finishedAt = "",
                comment = e.comment ?: "",
                updatedAt = nowStr,
                deletedAt = null,
            )
            rows.put(dto.toRow(uid))
        }
        upsert("nyora_tracking", rows)
        android.util.Log.d("SupabaseSync", "pushTracking: pushed ${rows.length()} rows")
    }

    /**
     * Pull nyora_tracking rows into ScrobblingEntity. tracker_id → scrobbler int, canonical
     * status → service-specific status string, remote_id → target_id. Unknown trackers are
     * ignored. deleted_at tombstones remove the local link.
     */
    private suspend fun pullTracking(cutoff: String) {
        val text = fetch(
            "nyora_tracking?select=tracker_id,manga_id,remote_id,source_id,title,status,score,last_read_chapter,comment,updated_at,deleted_at",
            cutoff,
        ) ?: return
        runCatching {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                try {
                    val row = arr.getJSONObject(i)
                    val dto = SbTracking.fromRow(row)
                    val scrobbler = scrobblerId(dto.trackerId) ?: continue
                    // Older clients wrote server-generated manga ids; the alias map built by
                    // pullMangaIdentities settles them on the row this device holds.
                    val mangaId = canonicalPulledMangaId(dto.mangaId)
                    if (dto.deletedAt != null && dto.deletedAt.isNotBlank()) {
                        scrobblingDao.delete(scrobbler, mangaId)
                        continue
                    }
                    val remoteId = dto.remoteId.toLongOrNull() ?: 0L
                    val existing = scrobblingDao.find(scrobbler, mangaId)
                    scrobblingDao.upsert(ScrobblingEntity(
                        scrobbler = scrobbler,
                        id = existing?.id ?: remoteId.toInt(),
                        mangaId = mangaId,
                        targetId = remoteId,
                        status = serviceStatus(scrobbler, dto.status),
                        chapter = dto.lastReadChapter.toInt(),
                        comment = dto.comment.ifBlank { existing?.comment },
                        rating = dto.score,
                    ))
                } catch (e: Exception) {
                    android.util.Log.e("SupabaseSync", "pullTracking row failed", e)
                }
            }
        }.onFailure { android.util.Log.e("SupabaseSync", "pullTracking failed", it) }
    }

    // scrobbler int <-> canonical tracker_id slug (NYORA_TRACKING_SCHEMA.md §3)
    private fun scrobblerSlug(id: Int): String? = when (id) {
        2 -> "anilist"
        3 -> "myanimelist"
        else -> null
    }

    private fun scrobblerId(slug: String): Int? = when (slug) {
        "anilist" -> 2
        "myanimelist" -> 3
        else -> null
    }

    // Per-service stored status string -> canonical status (NYORA_TRACKING_SCHEMA.md §2).
    // The entity stores the service-specific API status (e.g. AniList "CURRENT"), not the
    // ScrobblingStatus enum name, so mapping is keyed by service.
    private val serviceToCanonicalStatus: Map<Int, Map<String, String>> = mapOf(
        2 to mapOf( // AniList
            "PLANNING" to "planning", "CURRENT" to "reading", "REPEATING" to "rereading",
            "COMPLETED" to "completed", "PAUSED" to "paused", "DROPPED" to "dropped",
        ),
        3 to mapOf( // MyAnimeList
            "plan_to_read" to "planning", "reading" to "reading", "completed" to "completed",
            "on_hold" to "paused", "dropped" to "dropped",
        ),
    )

    private fun canonicalStatus(scrobbler: Int, serviceStatus: String?): String? {
        if (serviceStatus.isNullOrBlank()) return null
        return serviceToCanonicalStatus[scrobbler]?.get(serviceStatus)
    }

    private fun serviceStatus(scrobbler: Int, canonical: String?): String? {
        if (canonical.isNullOrBlank()) return null
        return serviceToCanonicalStatus[scrobbler]?.entries?.firstOrNull { it.value == canonical }?.key
    }

    private suspend fun pushFavourites(cutoff: Long) {
        val uid = config.userId
        val entities = runCatching { favouritesDao.findAll() }.getOrNull()
        if (entities == null) {
            android.util.Log.w("SupabaseSync", "pushFavourites: findAll returned null")
            return
        }
        if (entities.isEmpty()) {
            android.util.Log.d("SupabaseSync", "pushFavourites: no data to push")
            return
        }
        android.util.Log.d("SupabaseSync", "pushFavourites: ${entities.size} items")
        val rows = JSONArray()
        val mangaRows = JSONArray()
        var pushedCount = 0
        for (fm in entities) {
            val f = fm.favourite
            if (f.createdAt <= cutoff && f.deletedAt <= cutoff) continue
            val mangaRow = fm.manga.toRemoteManga(uid, now()) ?: continue
            pushedCount++
            rows.put(JSONObject().apply {
                put("user_id", uid)
                put("manga_id", f.mangaId)
                put("sort_key", f.sortKey)
                put("updated_at", now())
                if (f.deletedAt > 0) put("deleted_at", Instant.ofEpochMilli(f.deletedAt).toString())
            })
            mangaRows.put(mangaRow)
        }
        upsert("nyora_manga", mangaRows)
        upsert("nyora_favourite", rows)
        android.util.Log.d("SupabaseSync", "pushFavourites: pushed $pushedCount items")
    }

    private suspend fun pushHistory(cutoff: Long) {
        val uid = config.userId
        val entities = runCatching { historyDao.findChangedForSync(cutoff, limit = 100_000) }.getOrNull()
        if (entities == null) {
            android.util.Log.w("SupabaseSync", "pushHistory: findChangedForSync returned null")
            return
        }
        if (entities.isEmpty()) {
            android.util.Log.d("SupabaseSync", "pushHistory: no data to push")
            return
        }
        android.util.Log.d("SupabaseSync", "pushHistory: ${entities.size} items")
        val rows = JSONArray()
        val mangaRows = JSONArray()
        var pushedCount = 0
        for (wm in entities) {
            val h = wm.history
            if (h.updatedAt <= cutoff && h.deletedAt <= cutoff) continue
            val mangaRow = wm.manga.toRemoteManga(uid, Instant.ofEpochMilli(h.updatedAt).toString()) ?: continue
            pushedCount++
            mangaRows.put(mangaRow)
            rows.put(JSONObject().apply {
                put("user_id", uid)
                put("manga_id", h.mangaId)
                // Plain source id (not the JSON source_ref) so other clients can resolve the entry's
                // source directly, matching nyora-web's history rows.
                put("source_id", wm.manga.source)
                put("chapter_id", h.chapterId)
                put("page", h.page)
                put("scroll", h.scroll)
                put("percent", h.percent)
                put("chapters_count", h.chaptersCount)
                put("updated_at", Instant.ofEpochMilli(h.updatedAt).toString())
                if (h.deletedAt > 0) put("deleted_at", Instant.ofEpochMilli(h.deletedAt).toString())
            })
        }
        upsert("nyora_manga", mangaRows)
        upsert("nyora_history", rows)
        android.util.Log.d("SupabaseSync", "pushHistory: pushed $pushedCount items")
    }

    private suspend fun pushBookmarks(cutoff: Long) {
        val uid = config.userId
        val map = runCatching { bookmarksDao.findAllIncludingDeleted(offset = 0, limit = 100_000) }.getOrNull() ?: return
        val bookmarks = map.values.flatten()
        if (bookmarks.isEmpty()) return
        val rows = JSONArray()
        var pushedCount = 0
        for (b in bookmarks) {
            if (b.createdAt <= cutoff && b.deletedAt <= cutoff) continue
            pushedCount++
            val stableId = "${b.mangaId}:${b.chapterId}:${b.page}"
            rows.put(JSONObject().apply {
                put("user_id", uid)
                put("id", stableId)
                put("manga_id", b.mangaId)
                put("chapter_id", b.chapterId)
                put("page", b.page)
                put("scroll", b.scroll)
                put("image_url", b.imageUrl)
                put("percent", b.percent)
                put("created_at", Instant.ofEpochMilli(b.createdAt).toString())
                put("updated_at", now())
                if (b.deletedAt > 0) put("deleted_at", Instant.ofEpochMilli(b.deletedAt).toString())
            })
        }
        upsert("nyora_bookmark", rows)
    }

    private suspend fun pushCategories() {
        val uid = config.userId
        // Include soft-deleted rows so category deletions (e.g. duplicate cleanup) propagate.
        val entities = runCatching { categoriesDao.findAllForSync() }.getOrNull() ?: return
        if (entities.isEmpty()) return
        val rows = JSONArray()
        for (c in entities) {
            rows.put(JSONObject().apply {
                put("user_id", uid)
                put("id", c.categoryId.toString())
                put("title", c.title)
                put("sort_key", c.sortKey)
                put("updated_at", now())
                if (c.deletedAt > 0) put("deleted_at", Instant.ofEpochMilli(c.deletedAt).toString())
            })
        }
        upsert("nyora_category", rows)
    }

    private suspend fun pushMangaCategories() {
        val uid = config.userId
        val entities = runCatching { favouritesDao.findAllForSync() }.getOrNull() ?: return
        if (entities.isEmpty()) return
        val rows = JSONArray()
        for (fm in entities) {
            val f = fm.favourite
            if (f.categoryId == 0L) continue
            rows.put(JSONObject().apply {
                put("user_id", uid)
                put("manga_id", f.mangaId)
                put("category_id", f.categoryId.toString())
                put("updated_at", now())
                if (f.deletedAt > 0) put("deleted_at", Instant.ofEpochMilli(f.deletedAt).toString())
            })
        }
        upsert("nyora_manga_category", rows)
    }

    private suspend fun pushMangaPrefs() {
        val uid = config.userId
        val entities = runCatching { preferencesDao.findAll() }.getOrNull() ?: return
        if (entities.isEmpty()) return
        val rows = JSONArray()
        for (p in entities) {
            rows.put(JSONObject().apply {
                put("user_id", uid)
                put("manga_id", p.mangaId)
                put("reader_mode", p.mode.toString())
                put("brightness", p.cfBrightness.toDouble())
                put("contrast", p.cfContrast.toDouble())
                put("saturation", 1.0)
                put("hue", 0.0)
                put("palette", "")
                put("updated_at", now())
            })
        }
        upsert("nyora_manga_prefs", rows)
    }

    // -- Pull --

    suspend fun pullAll() = barrier.withSync {
        runSyncOperation {
            val completedThrough = now()
            pullAll(config.lastSyncTimestamp)
            config.markSyncSucceeded(completedThrough)
        }
    }

    private suspend fun pullAll(cutoff: String) {
        pulledMangaIdAliases.clear()
        pulledMangaSourceIds.clear()
        storedChapterUrlsByManga.clear()
        pullMangaIdentities()
        pullCategories(cutoff)
        pullManga(cutoff)
        pullMangaCategories(cutoff)
        pullFavourites(cutoff)
        pullHistory(cutoff)
        pullBookmarks(cutoff)
        pullMangaPrefs(cutoff)
        pullSourcePrefs(cutoff)
        pullTracking(cutoff)
        pullExtensionRepos()
    }

    private suspend fun pullExtensionRepos() {
        val text = fetch("nyora_extension_repos?select=type,base_url,name,short_name,website,signing_key_fingerprint,created_at") ?: return
        runCatching {
            val arr = JSONArray(text)
            val dao = database.getExternalExtensionRepoDao()
            
            val remoteKeys = mutableSetOf<String>()
            val remoteRepos = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                val row = arr.getJSONObject(i)
                val type = row.getString("type")
                val baseUrl = row.getString("base_url")
                remoteKeys.add("$type|$baseUrl")
                remoteRepos.add(row)
            }
            
            val localRepos = dao.findAll()
            
            for (local in localRepos) {
                val key = "${local.type.name}|${local.baseUrl}"
                if (!remoteKeys.contains(key)) {
                    dao.delete(local.type, local.baseUrl)
                }
            }
            
            for (row in remoteRepos) {
                try {
                    val typeStr = row.getString("type")
                    val type = com.nyora.hasan72341.mihon.extensions.repo.ExternalExtensionType.valueOf(typeStr)
                    val baseUrl = row.getString("base_url")
                    val name = row.getString("name")
                    val shortName = if (row.isNull("short_name")) null else row.getString("short_name")
                    val website = row.getString("website")
                    val fingerprint = row.getString("signing_key_fingerprint")
                    val createdAt = row.getLong("created_at")
                    
                    val existing = dao.get(type, baseUrl)
                    val id = existing?.id ?: 0L
                    dao.upsert(com.nyora.hasan72341.core.db.entity.ExternalExtensionRepoEntity(
                        id = id,
                        type = type,
                        baseUrl = baseUrl,
                        name = name,
                        shortName = shortName,
                        website = website,
                        signingKeyFingerprint = fingerprint,
                        createdAt = createdAt,
                        updatedAt = System.currentTimeMillis(),
                        lastSuccessAt = existing?.lastSuccessAt ?: 0L,
                        lastError = existing?.lastError,
                        version = existing?.version
                    ))
                } catch (e: Exception) {
                    android.util.Log.e("SupabaseSync", "pullExtensionRepo row failed", e)
                }
            }
        }.onFailure { android.util.Log.e("SupabaseSync", "pullExtensionRepos failed", it) }
    }

    private suspend fun pullSourcePrefs(cutoff: String) {
        val text = fetch("nyora_source_prefs?select=source_id,is_pinned,is_enabled,updated_at", cutoff) ?: return
        runCatching {
            val arr = JSONArray(text)
            val dao = database.getSourcesDao()
            val prefs = ArrayList<PulledSourcePref>(arr.length())
            for (i in 0 until arr.length()) {
                try {
                    val row = arr.getJSONObject(i)
                    val sourceId = row.getString("source_id")
                    // Shipped builds pushed their DD_/JS_ spellings; the same upgrade the pull
                    // applies to manga rows brings those prefs to the source they belong to.
                    val canonicalSourceId = canonicalPulledSourceId(sourceId) ?: continue
                    prefs += PulledSourcePref(
                        sourceId = sourceId,
                        canonicalSourceId = canonicalSourceId,
                        isPinned = row.getBoolean("is_pinned"),
                        isEnabled = row.getBoolean("is_enabled"),
                        updatedAt = parseEpochMilliOrZero(row.optString("updated_at", "")),
                    )
                } catch (e: Exception) {
                    android.util.Log.e("SupabaseSync", "pullSourcePrefs row failed", e)
                }
            }
            // A stale DD_/JS_ row and the current data: row of one source apply once, newest
            // first, so backend row order cannot re-enable a source disabled here or hide one.
            for (pref in newestCanonicalSourcePrefs(prefs)) {
                try {
                    dao.setEnabled(pref.canonicalSourceId, pref.isEnabled)
                    dao.setPinned(pref.canonicalSourceId, pref.isPinned)
                } catch (e: Exception) {
                    android.util.Log.e("SupabaseSync", "pullSourcePrefs ${pref.canonicalSourceId} failed", e)
                }
            }
        }.onFailure { android.util.Log.e("SupabaseSync", "pullSourcePrefs failed", it) }
    }

    private suspend fun pullCategories(cutoff: String) {
        val text = fetch("nyora_category?select=id,title,sort_key,deleted_at", cutoff) ?: return
        runCatching {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                try {
                    val row = arr.getJSONObject(i)
                    val idStr = row.getString("id")
                    val id = idStr.toIntOrNull() ?: continue
                    val title = row.getString("title")
                    val sortKey = row.getInt("sort_key")
                    val deleted = !row.isNull("deleted_at")
                    
                    if (deleted) {
                        categoriesDao.delete(id.toLong())
                    } else {
                        categoriesDao.upsert(com.nyora.hasan72341.favourites.data.FavouriteCategoryEntity(
                            categoryId = id,
                            createdAt = System.currentTimeMillis(),
                            sortKey = sortKey,
                            title = title,
                            order = "",
                            track = false,
                            isVisibleInLibrary = true,
                            deletedAt = 0L
                        ))
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SupabaseSync", "pullCategories row failed", e)
                }
            }
            // Collapse any duplicate-title categories that arrived from legacy per-device seeds.
            runCatching {
                categoriesDao.repointDuplicateFavourites()
                categoriesDao.softDeleteDuplicateCategories(System.currentTimeMillis())
            }.onFailure { android.util.Log.e("SupabaseSync", "pullCategories dedup failed", it) }
        }.onFailure { android.util.Log.e("SupabaseSync", "pullCategories failed", it) }
    }

    private suspend fun pullMangaCategories(cutoff: String) {
        val text = fetch("nyora_manga_category?select=manga_id,category_id,updated_at,deleted_at", cutoff) ?: return
        runCatching {
            val arr = JSONArray(text)
            val rows = newestCanonicalJsonRows(arr) {
                "${canonicalPulledMangaId(it.getString("manga_id"))}|${it.getString("category_id")}"
            }
            for (row in rows) {
                try {
                    val mangaId = canonicalPulledMangaId(row.getString("manga_id"))
                    val categoryIdStr = row.getString("category_id")
                    val categoryId = categoryIdStr.toLongOrNull() ?: continue
                    val deleted = !row.isNull("deleted_at")
                    
                    if (deleted) {
                        favouritesDao.delete(mangaId, categoryId)
                    } else {
                        try {
                            favouritesDao.upsert(com.nyora.hasan72341.favourites.data.FavouriteEntity(
                                mangaId = mangaId,
                                categoryId = categoryId,
                                sortKey = 0,
                                isPinned = false,
                                createdAt = System.currentTimeMillis(),
                                deletedAt = 0L
                            ))
                        } catch (e: Exception) {
                            favouritesDao.recover(categoryId, mangaId)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SupabaseSync", "pullMangaCategories row failed", e)
                }
            }
        }.onFailure { android.util.Log.e("SupabaseSync", "pullMangaCategories failed", it) }
    }

    private suspend fun pullMangaPrefs(cutoff: String) {
        val text = fetch("nyora_manga_prefs?select=manga_id,reader_mode,brightness,contrast,updated_at", cutoff) ?: return
        runCatching {
            val arr = JSONArray(text)
            val dao = database.getPreferencesDao()
            val rows = newestCanonicalJsonRows(arr) { canonicalPulledMangaId(it.getString("manga_id")) }
            for (row in rows) {
                try {
                    val mangaId = canonicalPulledMangaId(row.getString("manga_id"))
                    val readerModeStr = row.optString("reader_mode", "0")
                    val mode = readerModeStr.toIntOrNull() ?: 0
                    val brightness = row.optDouble("brightness", 0.0).toFloat()
                    val contrast = row.optDouble("contrast", 1.0).toFloat()
                    
                    val existing = dao.find(mangaId)
                    dao.upsert(com.nyora.hasan72341.core.db.entity.MangaPrefsEntity(
                        mangaId = mangaId,
                        mode = mode,
                        cfBrightness = brightness,
                        cfContrast = contrast,
                        cfInvert = existing?.cfInvert ?: false,
                        cfGrayscale = existing?.cfGrayscale ?: false,
                        cfBookEffect = existing?.cfBookEffect ?: false,
                        cfMultitone = existing?.cfMultitone ?: 0,
                        titleOverride = existing?.titleOverride,
                        coverUrlOverride = existing?.coverUrlOverride,
                        contentRatingOverride = existing?.contentRatingOverride
                    ))
                } catch (e: Exception) {
                    android.util.Log.e("SupabaseSync", "pullMangaPrefs row failed", e)
                }
            }
        }.onFailure { android.util.Log.e("SupabaseSync", "pullMangaPrefs failed", it) }
    }

    /**
     * Maps every remote manga id to the local id this device keys by, before anything is pulled.
     *
     * Deliberately ignores the incremental cutoff: an old or foreign client can update a history,
     * favourite, bookmark or category row without touching its parent, and that dependent row still
     * has to resolve to the manga row this device holds or it misses the foreign key and is dropped.
     * Only the three identity columns are read, so the row pulls below stay incremental. A failure
     * propagates and aborts the pull rather than writing dependent rows under un-aliased ids.
     */
    private suspend fun pullMangaIdentities() {
        val text = fetch("nyora_manga?select=id,url,source_ref") ?: return
        val identities = pulledMangaIdentities(JSONArray(text)) { index, error ->
            android.util.Log.e("SupabaseSync", "pullMangaIdentities row $index failed", error)
        }
        pulledMangaIdAliases.putAll(identities.aliases)
        pulledMangaSourceIds.putAll(identities.sourceIds)
    }

    private suspend fun pullManga(cutoff: String) {
        val text = fetch("nyora_manga?select=id,title,alt_titles,url,public_url,rating,is_nsfw,content_rating,cover_url,large_cover_url,state,authors,source_ref,description,tags,updated_at", cutoff) ?: return
        runCatching {
            val arr = JSONArray(text)
            // The alias map is complete before this runs, so the key a duplicate collapses on never
            // depends on which of the two backend rows happened to arrive first.
            for (row in newestCanonicalJsonRows(arr) { canonicalPulledMangaId(it.getString("id")) }) {
                try {
                    val mangaId = canonicalPulledMangaId(row.getString("id"))
                    val title = row.getString("title")
                    val altTitles = if (row.isNull("alt_titles")) null else row.getString("alt_titles")
                    val url = row.getString("url")
                    val publicUrl = row.getString("public_url")
                    val rating = row.getDouble("rating").toFloat()
                    val isNsfw = row.getBoolean("is_nsfw")
                    val contentRating = if (row.isNull("content_rating")) null else row.getString("content_rating")
                    val coverUrl = row.getString("cover_url")
                    val largeCoverUrl = if (row.isNull("large_cover_url")) null else row.getString("large_cover_url")
                    val state = if (row.isNull("state")) null else row.getString("state")
                    val authors = if (row.isNull("authors")) null else row.getString("authors")
                    // The id above is hashed from the canonical source, so the row has to be stored
                    // under that source too or the manga resolves to a retired, unopenable identity.
                    // decodeSourceRef runs first: other clients also wrote {"source"}, {"id"}, {"type"}
                    // and ".MangaSourceRef." shapes, which the stored-source upgrade does not read.
                    val rawSourceRef = row.getString("source_ref")
                    val source = canonicalPulledSourceId(decodeSourceRef(rawSourceRef))
                        ?.let { JSONObject().put("name", it).toString() }
                        ?: rawSourceRef
                    val description = row.optString("description", "")
                    val tags = row.optString("tags", "[]")

                    val pulled = MangaEntity(
                        id = mangaId,
                        title = title,
                        altTitles = altTitles,
                        url = url,
                        publicUrl = publicUrl,
                        rating = rating,
                        isNsfw = isNsfw,
                        contentRating = contentRating,
                        coverUrl = coverUrl,
                        largeCoverUrl = largeCoverUrl,
                        state = state,
                        authors = authors,
                        source = source,
                        description = description,
                        tags = tags,
                    )
                    // Room's upsert writes every column and the cloud carries no chapter list,
                    // unread count or progress: the row this device holds supplies them, which is
                    // also what pullHistory/pullBookmarks re-key a shipped chapter id against.
                    mangaDao.upsert(pulledMangaWithLocalReaderState(pulled, mangaDao.find(mangaId)))
                } catch (e: Exception) {
                    android.util.Log.e("SupabaseSync", "pullManga row failed", e)
                }
            }
        }.onFailure { android.util.Log.e("SupabaseSync", "pullManga failed", it) }
    }

    private suspend fun pullFavourites(cutoff: String) {
        val text = fetch("nyora_favourite?select=manga_id,updated_at,deleted_at", cutoff) ?: return
        runCatching {
            val arr = JSONArray(text)
            val categories = categoriesDao.findAll()
            val defaultCategoryId = categories.firstOrNull()?.categoryId?.toLong() ?: 1L
            val rows = newestCanonicalJsonRows(arr) { canonicalPulledMangaId(it.getString("manga_id")) }
            for (row in rows) {
                try {
                    val mangaId = canonicalPulledMangaId(row.getString("manga_id"))
                    val deleted = !row.isNull("deleted_at")
                    if (deleted) {
                        favouritesDao.delete(mangaId)
                    } else {
                        val existing = favouritesDao.findAllRaw(mangaId)
                        if (existing.isNotEmpty()) {
                            favouritesDao.recover(mangaId)
                        } else {
                            try {
                                favouritesDao.upsert(com.nyora.hasan72341.favourites.data.FavouriteEntity(
                                    mangaId = mangaId,
                                    categoryId = defaultCategoryId,
                                    sortKey = 0,
                                    isPinned = false,
                                    createdAt = System.currentTimeMillis(),
                                    deletedAt = 0L
                                ))
                            } catch (e: Exception) {
                                favouritesDao.recover(mangaId)
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SupabaseSync", "pullFavourites row failed", e)
                }
            }
        }.onFailure { android.util.Log.e("SupabaseSync", "pullFavourites failed", it) }
    }

    private suspend fun pullHistory(cutoff: String) {
        val text = fetch("nyora_history?select=manga_id,source_id,chapter_id,chapter_title,page,scroll,percent,chapters_count,updated_at,deleted_at", cutoff) ?: return
        runCatching {
            val arr = JSONArray(text)
            val rows = newestCanonicalJsonRows(arr) { canonicalPulledMangaId(it.getString("manga_id")) }
            for (row in rows) {
                try {
                    val remoteMangaId = row.getString("manga_id")
                    val mangaId = canonicalPulledMangaId(remoteMangaId)
                    val deleted = !row.isNull("deleted_at")
                    if (deleted) {
                         historyDao.delete(mangaId)
                     } else {
                         try {
                              historyDao.upsert(com.nyora.hasan72341.history.data.HistoryEntity(
                                  mangaId = mangaId,
                                  createdAt = System.currentTimeMillis(),
                                  updatedAt = parseEpochMilli(row.getString("updated_at")),
                                  chapterId = localChapterId("history", remoteMangaId, mangaId, row.getString("chapter_id")),
                                  page = row.getInt("page"),
                                  scroll = row.optDouble("scroll", 0.0).toFloat(),
                                  percent = row.getDouble("percent").toFloat(),
                                  deletedAt = 0L,
                                  chaptersCount = row.optInt("chapters_count", 0),
                              ))
                         } catch (e: Exception) {
                              android.util.Log.e("SupabaseSync", "pullHistory row failed: $mangaId", e)
                         }
                     }
                } catch (e: Exception) {
                    android.util.Log.e("SupabaseSync", "pullHistory row failed", e)
                }
            }
        }.onFailure { android.util.Log.e("SupabaseSync", "pullHistory failed", it) }
    }

    private suspend fun pullBookmarks(cutoff: String) {
        val text = fetch("nyora_bookmark?select=id,manga_id,chapter_id,chapter_title,page,scroll,image_url,percent,updated_at,deleted_at", cutoff) ?: return
        runCatching {
            val arr = JSONArray(text)
            val toUpsert = mutableListOf<com.nyora.hasan72341.bookmarks.data.BookmarkEntity>()
            val rows = newestCanonicalJsonRows(arr) {
                "${canonicalPulledMangaId(it.getString("manga_id"))}|${it.getString("chapter_id")}|${it.getInt("page")}"
            }
            for (row in rows) {
                try {
                    val remoteMangaId = row.getString("manga_id")
                    val mangaId = canonicalPulledMangaId(remoteMangaId)
                    val chapterId = localChapterId("bookmark", remoteMangaId, mangaId, row.getString("chapter_id"))
                    val page = row.getInt("page")
                    if (!row.isNull("deleted_at")) {
                        bookmarksDao.delete(mangaId, chapterId, page)
                    } else {
                        toUpsert.add(com.nyora.hasan72341.bookmarks.data.BookmarkEntity(
                            mangaId = mangaId,
                            pageId = row.getString("id").takeIf { it.isNotBlank() } ?: "${mangaId}:${chapterId}:${page}",
                            chapterId = chapterId,
                            page = page,
                            scroll = row.optDouble("scroll", 0.0).toInt(),
                            imageUrl = row.optString("image_url", ""),
                            createdAt = parseEpochMilli(row.getString("updated_at")),
                            percent = row.optDouble("percent", 0.0).toFloat(),
                            deletedAt = 0L,
                        ))
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SupabaseSync", "pullBookmarks row failed", e)
                }
            }
            if (toUpsert.isNotEmpty()) {
                try { bookmarksDao.upsert(toUpsert) } catch (e: Exception) {}
            }
        }.onFailure { android.util.Log.e("SupabaseSync", "pullBookmarks failed", it) }
    }

    private suspend fun fetch(table: String, cutoff: String? = null): String? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val (tableName, columns) = table.toSyncRequestParts()
        val body = JSONObject().apply {
            put("action", "select")
            put("table", tableName)
            if (columns.isNotEmpty()) put("columns", JSONArray(columns))
            cutoff?.let { put("since", it) }
        }.toString().toRequestBody(JSON_MT)
        val req = Request.Builder()
            .url(syncFunctionUrl)
            .header("apikey", config.anonKey)
            .header("Authorization", "Bearer ${config.accessToken}")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful) {
                throw SyncApiException("Sync download failed (${resp.code})", resp.code)
            }
            JSONObject(body ?: "{}").optJSONArray("data")?.toString() ?: "[]"
        }
    }

    private fun upsert(table: String, rows: JSONArray) {
        if (rows.length() == 0) return
        runCatching {
            val body = JSONObject().apply {
                put("action", "upsert")
                put("table", table)
                put("rows", rows)
            }.toString().toRequestBody(JSON_MT)
            val req = Request.Builder()
                .url(syncFunctionUrl)
                .header("apikey", config.anonKey)
                .header("Authorization", "Bearer ${config.accessToken}")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw SyncApiException("Sync upload failed (${resp.code})", resp.code)
                }
            }
        }.onFailure {
            android.util.Log.e("SupabaseSync", "upsert $table", it)
            throw it
        }
    }

    private val String.jq: String get() = "\"${replace("\"", "\\\"")}\""

    private fun String.toSyncRequestParts(): Pair<String, List<String>> {
        val parts = split("?select=", limit = 2)
        val tableName = parts[0]
        val columns = parts.getOrNull(1)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        return tableName to columns
    }

    private fun now(): String = Instant.now().toString()

    // Decode the canonical source_ref (a JSON object {"name": ...}); tolerant of a bare string that
    // older/other clients may have written. Mirrors nyora-web's decodeSourceRef.
    private fun decodeSourceRef(raw: String): String {
        val trimmed = raw.trim()
        val name = if (trimmed.startsWith("{")) {
            runCatching {
                val obj = JSONObject(trimmed)
                // Live data has both {"name":..} (web) and {"source":..} (desktop) shapes.
                obj.optString("name")
                    .ifBlank { obj.optString("source") }
                    .ifBlank { obj.optString("id") }
                    .ifBlank { obj.optString("type") }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: trimmed
        } else {
            trimmed
        }
        return name.substringAfterLast(".MangaSourceRef.")
    }

    private fun canonicalPulledMangaId(remoteId: String): String = pulledMangaIdAliases[remoteId] ?: remoteId

    /**
     * The chapter id a pulled history or bookmark row is stored under. A data-source row whose
     * chapter id is not a hash came from a shipped build; it is re-keyed from the chapter url that
     * id carries when the manga's stored chapters know that url, which they do whenever this
     * device has loaded the manga's details: [pullManga] runs first and carries the stored chapter
     * list forward ([pulledMangaWithLocalReaderState]) instead of resetting it.
     *
     * On a fresh install, or a Restore from cloud of a manga this device has never opened, no
     * chapter list is stored yet, so the id is kept as pushed and the log records it: the reader
     * cannot resume from that row (its chapter id reads as 0) until a later pull selects the row
     * again after the details have been loaded, which a Restore from cloud does for every row.
     */
    private suspend fun localChapterId(table: String, remoteMangaId: String, mangaId: String, remoteChapterId: String): String {
        val sourceId = pulledMangaSourceIds[remoteMangaId]
        // Only a shipped-build id of a data-source manga needs the stored chapters read.
        val storedUrls = if (sourceId != null && legacyChapterUrl(remoteChapterId) != null) storedChapterUrlsOf(mangaId) else emptySet()
        val resolved = pulledChapterIdAlias(remoteChapterId, sourceId) { storedUrls }
        if (resolved == null) {
            android.util.Log.w("SupabaseSync", "pull $table: chapter id $remoteChapterId of manga $mangaId kept as pushed; no stored chapter carries its url")
        }
        return resolved ?: remoteChapterId
    }

    private suspend fun storedChapterUrlsOf(mangaId: String): Set<String> =
        storedChapterUrlsByManga.getOrPut(mangaId) {
            storedChapterUrls(mangaDao.find(mangaId)?.chapters.orEmpty())
        }

    /**
     * The rows to apply, with an aliased and an already-canonical row of the same manga collapsed
     * onto the newest of the two. Backend row order is not a conflict policy.
     */
    private fun newestCanonicalJsonRows(rows: JSONArray, key: (JSONObject) -> String): List<JSONObject> {
        val winners = linkedMapOf<String, JSONObject>()
        for (index in 0 until rows.length()) {
            val candidate = rows.optJSONObject(index) ?: continue
            // A row missing the columns the key is built from is still handed to the caller, which
            // logs and skips it per row. Collapsing must never cost more rows than it deduplicates.
            val canonicalKey = runCatching { key(candidate) }.getOrElse { "\u0000malformed:$index" }
            val current = winners[canonicalKey]
            if (current == null || isNewerRemoteRow(candidate, current)) winners[canonicalKey] = candidate
        }
        return winners.values.toList()
    }

    private fun isNewerRemoteRow(candidate: JSONObject, incumbent: JSONObject): Boolean {
        val left = aliasCandidate(candidate)
        val right = aliasCandidate(incumbent)
        return left != right && newestCanonicalAlias(listOf(left, right)) == left
    }

    private fun aliasCandidate(row: JSONObject): CanonicalAliasCandidate {
        val remoteId = row.optString("manga_id", "").ifBlank { row.optString("id", "") }
        return CanonicalAliasCandidate(
            remoteId = remoteId,
            canonicalId = canonicalPulledMangaId(remoteId),
            updatedAt = parseEpochMilliOrZero(row.optString("updated_at", "")),
            deletedAt = parseEpochMilliOrZero(row.optString("deleted_at", "")),
        )
    }

    private fun parseEpochMilliOrZero(text: String): Long = runCatching { parseEpochMilli(text) }.getOrDefault(0L)

    private fun parseEpochMilli(text: String): Long {
        return runCatching { Instant.parse(text).toEpochMilli() }
            .getOrElse { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }
    }
}

/**
 * DTO for the canonical `nyora_tracking` row (snake_case, see NYORA_TRACKING_SCHEMA.md §1).
 * Serializes/parses the full canonical field set so Android round-trips losslessly with
 * iOS/desktop even for columns Android does not yet store locally.
 */
private data class SbTracking(
    val trackerId: String,
    val mangaId: String,
    val remoteId: String,
    val sourceId: String,
    val title: String,
    val status: String,
    val score: Float,
    val lastReadChapter: Float,
    val lastReadVolume: Int,
    val totalChapters: Int,
    val totalVolumes: Int,
    val chapterOffset: Int,
    val startedAt: String,
    val finishedAt: String,
    val comment: String,
    val updatedAt: String,
    val deletedAt: String?,
) {
    fun toRow(uid: String): JSONObject = JSONObject().apply {
        put("user_id", uid)
        put("tracker_id", trackerId)
        put("manga_id", mangaId)
        put("remote_id", remoteId)
        put("source_id", sourceId)
        put("title", title)
        put("status", status)
        put("score", score.toDouble())
        put("last_read_chapter", lastReadChapter.toDouble())
        put("last_read_volume", lastReadVolume)
        put("total_chapters", totalChapters)
        put("total_volumes", totalVolumes)
        put("chapter_offset", chapterOffset)
        put("started_at", startedAt)
        put("finished_at", finishedAt)
        put("comment", comment)
        put("updated_at", updatedAt)
        deletedAt?.let { put("deleted_at", it) }
    }

    companion object {
        fun fromRow(row: JSONObject): SbTracking = SbTracking(
            trackerId = row.optString("tracker_id", ""),
            mangaId = row.optString("manga_id", ""),
            remoteId = row.optString("remote_id", ""),
            sourceId = row.optString("source_id", ""),
            title = row.optString("title", ""),
            status = row.optString("status", ""),
            score = row.optDouble("score", 0.0).toFloat(),
            lastReadChapter = row.optDouble("last_read_chapter", 0.0).toFloat(),
            lastReadVolume = row.optInt("last_read_volume", 0),
            totalChapters = row.optInt("total_chapters", 0),
            totalVolumes = row.optInt("total_volumes", 0),
            chapterOffset = row.optInt("chapter_offset", 0),
            startedAt = row.optString("started_at", ""),
            finishedAt = row.optString("finished_at", ""),
            comment = row.optString("comment", ""),
            updatedAt = row.optString("updated_at", ""),
            deletedAt = if (row.isNull("deleted_at")) null else row.optString("deleted_at", ""),
        )
    }
}
