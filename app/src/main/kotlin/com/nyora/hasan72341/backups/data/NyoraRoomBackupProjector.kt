package com.nyora.hasan72341.backups.data

import com.nyora.hasan72341.core.db.MangaDatabase
import com.nyora.hasan72341.core.db.entity.toManga
import com.nyora.hasan72341.scrobbling.common.domain.model.ScrobblerService
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject

class NyoraRoomBackupProjector(
	private val database: MangaDatabase,
	private val appVersion: String,
	private val now: () -> Instant = Instant::now,
	private val newArchiveId: () -> UUID = UUID::randomUUID,
) {
	suspend fun snapshot(): NyoraBackupSnapshot {
		val createdAt = timestamp(now().toEpochMilli())
		val mangaRows = database.getMangaDao().findAllForBackup()
		val canonicalByLocalId = mangaRows.mapNotNull { row ->
			val sourceId = NyoraSourceIdentity.canonicalize(storedSourceName(row.source)) ?: return@mapNotNull null
			row.id to MangaProjection(row, sourceId, NyoraBackupIdentity.mangaId(sourceId, row.url))
		}.toMap()
		val sourceRows = database.getSourcesDao().findAll()
		val sourceIds = (sourceRows.mapNotNull { NyoraSourceIdentity.canonicalize(it.source) } +
			canonicalByLocalId.values.map { it.sourceId }).toSortedSet()
		val categoryRows = database.getFavouriteCategoriesDao().findAllForSync()
		val identityMap = database.getNyoraBackupIdentityMapDao()
		val categoryProjections = categoryRows.map { row ->
			val portableId = identityMap.findPortableId("category", row.categoryId.toString())
				?: portableUuid("category", row.categoryId.toString())
			row.categoryId.toLong() to NyoraBackupCategory(
				id = portableId,
				title = row.title,
				sortOrder = row.sortKey,
				updatedAt = timestamp(maxOf(row.createdAt, row.deletedAt)),
				deletedAt = row.deletedAt.takeIf { it > 0 }?.let(::timestamp),
			)
		}
		val categories = categoryProjections.map { it.second }.sortedBy { it.id }
		val categoryByLocalId = categoryProjections.associate { (localId, portable) -> localId to portable.id }
		val favourites = database.getFavouritesDao().findAllForBackup()
		val liveFavourites = favourites.filter { it.deletedAt == 0L && canonicalByLocalId.containsKey(it.mangaId) }
		val manga = canonicalByLocalId.values.map { projection ->
			val model = projection.row.toManga()
			NyoraBackupManga(
				id = projection.portableId,
				sourceId = projection.sourceId,
				contentKey = projection.row.url,
				title = projection.row.title,
				updatedAt = createdAt,
				description = projection.row.description.takeIf(String::isNotEmpty),
				author = model.authors.firstOrNull(),
				artist = null,
				genres = model.tags.map { it.title },
				coverUrl = projection.row.coverUrl.takeIf(String::isNotEmpty),
			)
		}.sortedBy { it.id }
		val chapters = canonicalByLocalId.values.flatMap { projection ->
			projection.row.toManga().chapters.map { chapter ->
				NyoraBackupChapter(
					id = NyoraBackupIdentity.chapterId(projection.portableId, chapter.url),
					mangaId = projection.portableId,
					chapterKey = chapter.url,
					name = chapter.title,
					updatedAt = createdAt,
					number = chapter.number.toDouble(),
					uploadedAt = chapter.uploadDate.takeIf { it > 0 }?.let(::timestamp),
				)
			}
		}.sortedBy { it.id }
		val chapterByLocalId = canonicalByLocalId.values.flatMap { projection ->
			projection.row.toManga().chapters.map { chapter ->
				(projection.row.id to chapter.id) to NyoraBackupIdentity.chapterId(projection.portableId, chapter.url)
			}
		}.toMap()
		val library = liveFavourites.groupBy { it.mangaId }.mapNotNull { (localId, rows) ->
			val projection = canonicalByLocalId[localId] ?: return@mapNotNull null
			val addedAt = timestamp(rows.minOf { it.createdAt })
			NyoraBackupLibrary(
				mangaId = projection.portableId,
				addedAt = addedAt,
				categoryIds = rows.mapNotNull { categoryByLocalId[it.categoryId] }.distinct().sorted(),
				updatedAt = timestamp(rows.maxOf { maxOf(it.createdAt, it.deletedAt) }),
			)
		}.sortedBy { it.id }
		val history = database.getHistoryDao().findAllForBackup().mapNotNull { row ->
			val projection = canonicalByLocalId[row.mangaId] ?: return@mapNotNull null
			NyoraBackupHistory(
				mangaId = projection.portableId,
				chapterId = chapterByLocalId[row.mangaId to row.chapterId],
				page = row.page,
				percent = row.percent.toDouble(),
				updatedAt = timestamp(row.updatedAt),
				deletedAt = row.deletedAt.takeIf { it > 0 }?.let(::timestamp),
			)
		}.sortedBy { it.id }
		val bookmarks = database.getBookmarksDao().findAllForBackup().mapNotNull { row ->
			val projection = canonicalByLocalId[row.mangaId] ?: return@mapNotNull null
			NyoraBackupBookmark(
				id = identityMap.findPortableId("bookmark", "${row.mangaId}\u0000${row.pageId}")
					?: portableUuid("bookmark", "${row.mangaId}\u0000${row.pageId}"),
				mangaId = projection.portableId,
				chapterId = chapterByLocalId[row.mangaId to row.chapterId],
				page = row.page,
				note = null,
				updatedAt = timestamp(maxOf(row.createdAt, row.deletedAt)),
				deletedAt = row.deletedAt.takeIf { it > 0 }?.let(::timestamp),
			)
		}.sortedBy { it.id }
		val tracking = database.getScrobblingDao().findAllForBackup().mapNotNull { row ->
			val projection = canonicalByLocalId[row.mangaId] ?: return@mapNotNull null
			val service = ScrobblerService.entries.firstOrNull { it.id == row.scrobbler }?.name?.lowercase()
				?: return@mapNotNull null
			NyoraBackupTracking(
				id = identityMap.findPortableId("tracking:$service", row.id.toString()) ?: "$service:${row.targetId}",
				mangaId = projection.portableId,
				service = service,
				status = row.status?.lowercase()?.replace('_', '-')?.takeIf { it.matches(Regex("[a-z][a-z0-9-]*")) } ?: "reading",
				updatedAt = createdAt,
				progress = row.chapter.toDouble(),
				score = row.rating.toDouble(),
			)
		}.sortedBy { it.id }
		return NyoraBackupSnapshot(
			archiveId = newArchiveId().toString().lowercase(),
			createdAt = createdAt,
			appVersion = appVersion,
			platform = "android",
			sources = sourceIds.map { sourceId ->
				val local = sourceRows.firstOrNull { NyoraSourceIdentity.canonicalize(it.source) == sourceId }
				NyoraBackupSource(
					id = sourceId,
					catalogueRevision = appVersion,
					installed = local != null,
					pinned = local?.isPinned == true,
					preferences = emptyMap<String, JsonPrimitive>(),
					updatedAt = createdAt,
				)
			},
			categories = categories,
			manga = manga,
			chapters = chapters,
			library = library,
			history = history,
			bookmarks = bookmarks,
			tracking = tracking,
			omissions = linkedMapOf(
				"downloads" to tableCount("local_index"),
				"statistics" to tableCount("stats"),
				"deviceReaderPreferences" to tableCount("preferences"),
				"savedFilters" to 0,
			),
		)
	}

	private fun tableCount(table: String): Int = database.openHelper.readableDatabase
		.query("SELECT COUNT(*) FROM $table").use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

	private fun storedSourceName(raw: String): String = runCatching { JSONObject(raw).optString("name") }.getOrDefault(raw)

	private fun portableUuid(kind: String, key: String): String = UUID.nameUUIDFromBytes(
		"$kind\u0000$key".toByteArray(StandardCharsets.UTF_8),
	).toString().lowercase()

	private fun timestamp(millis: Long): String = TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(millis.coerceAtLeast(0)))

	private data class MangaProjection(
		val row: com.nyora.hasan72341.core.db.entity.MangaEntity,
		val sourceId: String,
		val portableId: String,
	)

	private companion object {
		val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter
			.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
			.withZone(ZoneOffset.UTC)
	}
}
