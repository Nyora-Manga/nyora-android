package com.nyora.hasan72341.backups.data

import com.nyora.hasan72341.bookmarks.data.BookmarkEntity
import com.nyora.hasan72341.core.db.MangaDatabase
import com.nyora.hasan72341.core.db.entity.MangaSourceEntity
import com.nyora.hasan72341.core.db.entity.toEntity
import com.nyora.hasan72341.favourites.data.FavouriteCategoryEntity
import com.nyora.hasan72341.favourites.data.FavouriteEntity
import com.nyora.hasan72341.mihon.parsers.model.Manga
import com.nyora.hasan72341.mihon.parsers.model.MangaChapter
import com.nyora.hasan72341.mihon.parsers.model.MangaSourceRef
import com.nyora.hasan72341.mihon.parsers.model.MangaTag
import com.nyora.hasan72341.scrobbling.common.data.ScrobblingEntity
import com.nyora.hasan72341.scrobbling.common.domain.model.ScrobblerService
import java.time.Instant

class NyoraRoomPortableMaterializer(
	private val database: MangaDatabase,
) : NyoraPortableMaterializer {
	override suspend fun replace(snapshot: NyoraBackupSnapshot) {
		val sql = database.openHelper.writableDatabase
		val now = System.currentTimeMillis()
		sql.execSQL("DELETE FROM sources WHERE source LIKE 'data:%' OR source LIKE 'MIHON_%' OR source LIKE 'mihon:%' OR source GLOB '[0-9]*'")
		snapshot.sources.filter { it.deletedAt == null }.forEachIndexed { index, source ->
			database.getSourcesDao().upsert(
				MangaSourceEntity(source.id, source.installed, index, 0, 0L, source.pinned, 0),
			)
		}
		val chaptersByManga = snapshot.chapters.filter { it.deletedAt == null }.groupBy { it.mangaId }
		snapshot.manga.filter { it.deletedAt == null }.forEach { item ->
			val chapters = chaptersByManga[item.id].orEmpty().mapIndexed { index, chapter ->
				MangaChapter(
					id = chapter.id,
					title = chapter.name,
					number = chapter.number?.toFloat() ?: 0f,
					url = chapter.chapterKey,
					uploadDate = chapter.uploadedAt?.let(::millis) ?: 0L,
					index = index,
				)
			}
			database.getMangaDao().upsert(
				Manga(
					id = item.id,
					title = item.title,
					url = item.contentKey,
					publicUrl = item.contentKey,
					coverUrl = item.coverUrl.orEmpty(),
					authors = listOfNotNull(item.author, item.artist).distinct(),
					source = MangaSourceRef.Data(item.sourceId),
					description = item.description.orEmpty(),
					tags = item.genres.map { MangaTag(it, it) },
					chapters = chapters,
				).toEntity(),
			)
		}
		// Portable collection state is rebuilt from the validated target projection.
		sql.execSQL("DELETE FROM favourites")
		sql.execSQL("DELETE FROM favourite_categories")
		val categoryIds = snapshot.categories.filter { it.deletedAt == null }.associate { category ->
			val localId = database.getNyoraBackupIdentityMapDao().resolveLocalId("category", category.id)
			database.getFavouriteCategoriesDao().upsert(
				FavouriteCategoryEntity(
					categoryId = localId,
					createdAt = millis(category.updatedAt),
					sortKey = category.sortOrder,
					title = category.title,
					order = "MANUAL",
					track = false,
					isVisibleInLibrary = true,
					deletedAt = 0L,
				),
			)
			category.id to localId.toLong()
		}
		snapshot.library.filter { it.deletedAt == null }.forEach { item ->
			item.categoryIds.mapNotNull(categoryIds::get).forEachIndexed { index, categoryId ->
				database.getFavouritesDao().upsert(
					FavouriteEntity(item.mangaId, categoryId, index, false, millis(item.addedAt), 0L),
				)
			}
		}
		// Keep excluded statistics rows intact by soft-deleting absent history instead of deleting it.
		sql.execSQL("UPDATE history SET deleted_at = ? WHERE deleted_at = 0", arrayOf(now))
		snapshot.history.forEach { item ->
			sql.execSQL(
				"INSERT OR REPLACE INTO history (manga_id, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
					arrayOf<Any?>(
					item.mangaId,
					millis(item.updatedAt),
					millis(item.updatedAt),
					item.chapterId.orEmpty(),
					item.page ?: 0,
					0f,
					item.percent?.toFloat() ?: 0f,
					item.deletedAt?.let(::millis) ?: 0L,
					chaptersByManga[item.mangaId].orEmpty().size,
				),
			)
		}
		sql.execSQL("DELETE FROM bookmarks")
		database.getBookmarksDao().upsert(
			snapshot.bookmarks.filter { it.deletedAt == null }.map { item ->
				database.getNyoraBackupIdentityMapDao().recordLocalKey("bookmark", item.id, "${item.mangaId}\u0000${item.id}")
				BookmarkEntity(
					mangaId = item.mangaId,
					pageId = item.id,
					chapterId = item.chapterId.orEmpty(),
					page = item.page ?: 0,
					scroll = 0,
					imageUrl = "",
					createdAt = millis(item.updatedAt),
					percent = 0f,
					deletedAt = 0L,
				)
			},
		)
		sql.execSQL("DELETE FROM scrobblings")
		snapshot.tracking.filter { it.deletedAt == null }.forEach { item ->
			val service = ScrobblerService.entries.firstOrNull { it.name.equals(item.service, true) } ?: return@forEach
			val remoteId = item.id.substringAfter(':', "0").toLongOrNull() ?: return@forEach
			database.getScrobblingDao().upsert(
				ScrobblingEntity(
					scrobbler = service.id,
					id = remoteId.toInt(),
					mangaId = item.mangaId,
					targetId = remoteId,
					status = item.status,
					chapter = item.progress?.toInt() ?: 0,
					comment = null,
					rating = item.score?.toFloat() ?: 0f,
				),
			)
		}
		// Retired unresolved placeholder manga is not portable and is purged once.
		sql.execSQL("DELETE FROM manga WHERE source LIKE '%MIHON_%' OR source LIKE '%mihon:%'")
	}

	private fun millis(timestamp: String): Long = Instant.parse(timestamp).toEpochMilli()
}
