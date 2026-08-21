package com.nyora.hasan72341.backups.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Version-1 portable Nyora backup snapshot, independent of local database IDs. */
@Serializable
data class NyoraBackupSnapshot(
    val schemaVersion: Int = SCHEMA_VERSION,
    val archiveId: String,
    val createdAt: String,
    val appVersion: String,
    val platform: String,
    val mode: String = MODE_PORTABLE_DATA,
    val sources: List<NyoraBackupSource> = emptyList(),
    val categories: List<NyoraBackupCategory> = emptyList(),
    val preferences: List<NyoraBackupPreference> = emptyList(),
    val manga: List<NyoraBackupManga> = emptyList(),
    val chapters: List<NyoraBackupChapter> = emptyList(),
    val library: List<NyoraBackupLibrary> = emptyList(),
    val history: List<NyoraBackupHistory> = emptyList(),
    val bookmarks: List<NyoraBackupBookmark> = emptyList(),
    val tracking: List<NyoraBackupTracking> = emptyList(),
    val omissions: Map<String, Int> = emptyMap(),
) {
    companion object {
        const val FORMAT = "app.nyora.backup"
        const val SCHEMA_VERSION = 1
        const val MODE_PORTABLE_DATA = "portable-data"
    }
}

@Serializable
data class NyoraBackupSource(
    val id: String,
    val catalogueRevision: String,
    val installed: Boolean = false,
    val pinned: Boolean = false,
    val preferences: Map<String, JsonElement> = emptyMap(),
    val updatedAt: String,
    val deletedAt: String? = null,
)

@Serializable
data class NyoraBackupCategory(
    val id: String,
    val title: String,
    val sortOrder: Int,
    val updatedAt: String,
    val deletedAt: String? = null,
)

@Serializable
data class NyoraBackupPreference(
    val key: String,
    val value: JsonElement,
    val updatedAt: String,
    val deletedAt: String? = null,
    val id: String = key,
)

@Serializable
data class NyoraBackupManga(
    val id: String,
    val sourceId: String,
    val contentKey: String,
    val title: String,
    val updatedAt: String,
    val deletedAt: String? = null,
    val description: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val genres: List<String> = emptyList(),
    val coverUrl: String? = null,
)

@Serializable
data class NyoraBackupChapter(
    val id: String,
    val mangaId: String,
    val chapterKey: String,
    val name: String,
    val updatedAt: String,
    val deletedAt: String? = null,
    val number: Double? = null,
    val uploadedAt: String? = null,
)

@Serializable
data class NyoraBackupLibrary(
    val mangaId: String,
    val addedAt: String,
    val categoryIds: List<String> = emptyList(),
    val updatePolicy: String? = null,
    val updatedAt: String = addedAt,
    val deletedAt: String? = null,
    val id: String = mangaId,
)

@Serializable
data class NyoraBackupHistory(
    val mangaId: String,
    val chapterId: String? = null,
    val page: Int? = null,
    val percent: Double? = null,
    val updatedAt: String,
    val readDurationSeconds: Long? = null,
    val deletedAt: String? = null,
    val id: String = "$mangaId:${chapterId.orEmpty()}",
)

@Serializable
data class NyoraBackupBookmark(
    val id: String,
    val mangaId: String,
    val chapterId: String? = null,
    val page: Int? = null,
    val note: String? = null,
    val updatedAt: String,
    val deletedAt: String? = null,
)

@Serializable
data class NyoraBackupTracking(
    val id: String,
    val mangaId: String,
    val service: String,
    val status: String,
    val updatedAt: String,
    val progress: Double? = null,
    val score: Double? = null,
    val deletedAt: String? = null,
)
