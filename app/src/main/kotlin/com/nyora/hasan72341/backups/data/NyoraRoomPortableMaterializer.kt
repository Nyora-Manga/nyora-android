package com.nyora.hasan72341.backups.data

import com.nyora.hasan72341.bookmarks.data.BookmarkEntity
import com.nyora.hasan72341.core.db.MangaDatabase
import com.nyora.hasan72341.core.db.entity.MangaSourceEntity
import com.nyora.hasan72341.core.db.entity.toEntity
import com.nyora.hasan72341.core.model.DataDrivenMangaSource
import com.nyora.hasan72341.core.parser.datadriven.stableMangaId
import com.nyora.hasan72341.favourites.data.FavouriteCategoryEntity
import com.nyora.hasan72341.favourites.data.FavouriteEntity
import com.nyora.hasan72341.favourites.data.NYORA_UNCATEGORIZED_PORTABLE_ID
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
		val identityMap = database.getNyoraBackupIdentityMapDao()
		val previouslyManagedCategoryIds = mappedNumericLocalIds("category")
		sql.execSQL("DELETE FROM sources WHERE source LIKE 'data:%' OR source LIKE 'JS_%' OR source LIKE 'MIHON_%' OR source LIKE 'mihon:%' OR source GLOB '[0-9]*'")
		snapshot.sources.filter { it.deletedAt == null }.forEachIndexed { index, source ->
			database.getSourcesDao().upsert(
				MangaSourceEntity(source.id, source.installed, index, 0, 0L, source.pinned, 0),
			)
		}
		val chaptersByManga = snapshot.chapters.filter { it.deletedAt == null }.groupBy { it.mangaId }
		// A portable id is a SHA-256 of (source, content key); a local row keeps the legacy 64-bit hash the
		// runtime, the migrations and the sync wire all key by. Every portable reference below is resolved
		// through this map, and a mapping the projector already recorded wins so a row the device holds
		// under some other id is updated in place instead of duplicated.
		val localMangaIds = snapshot.manga.associate { item ->
			val localId = identityMap.findLocalKey("manga", item.id)
				?: stableMangaId(item.sourceId.removePrefix(DataDrivenMangaSource.PREFIX), item.contentKey)
			identityMap.recordLocalKeyIfAbsent("manga", item.id, localId)
			item.id to localId
		}
		// Manga owns device-only children (downloads, statistics and reader preferences). Never delete it here:
		// an absent portable row is excluded from the ledger/library while its local Room graph remains intact.
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
					id = localMangaIds.getValue(item.id),
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
		// Portable collection state is rebuilt without touching rows owned by non-portable manga.
		deleteManagedRows("favourites")
		val targetCategoryIds = linkedSetOf<Int>()
		val categoryIds = snapshot.categories.filter { it.deletedAt == null }.associate { category ->
			val localId = identityMap.resolveLocalId("category", category.id)
			targetCategoryIds += localId
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
		val uncategorized = snapshot.library.any { it.deletedAt == null && it.categoryIds.isEmpty() }
		val uncategorizedId = if (uncategorized) {
			identityMap.resolveLocalId("category", NYORA_UNCATEGORIZED_PORTABLE_ID).also { localId ->
				targetCategoryIds += localId
				database.getFavouriteCategoriesDao().upsert(
					FavouriteCategoryEntity(
						categoryId = localId,
						createdAt = now,
						sortKey = Int.MAX_VALUE,
						title = UNCATEGORIZED_DISPLAY_TITLE,
						order = "MANUAL",
						track = false,
						isVisibleInLibrary = true,
						deletedAt = 0L,
					),
				)
			}.toLong()
		} else null
		(previouslyManagedCategoryIds - targetCategoryIds).forEach { categoryId ->
			if (!hasFavouriteReference(categoryId)) {
				sql.execSQL("DELETE FROM favourite_categories WHERE category_id = ?", arrayOf(categoryId))
			}
		}
		snapshot.library.filter { it.deletedAt == null }.forEach { item ->
			val localMangaId = localMangaIds[item.mangaId] ?: return@forEach
			val localCategories = item.categoryIds.mapNotNull(categoryIds::get).ifEmpty { listOfNotNull(uncategorizedId) }
			localCategories.forEachIndexed { index, categoryId ->
				database.getFavouritesDao().upsert(
					FavouriteEntity(localMangaId, categoryId, index, false, millis(item.addedAt), 0L),
				)
			}
		}
		// Keep excluded statistics and non-portable history intact; only managed portable rows are reconciled.
		sql.execSQL(
			"UPDATE history SET deleted_at = ? WHERE deleted_at = 0 AND manga_id IN ($MANAGED_MANGA_IDS_SQL)",
			arrayOf(now),
		)
		snapshot.history.forEach { item ->
			val localMangaId = localMangaIds[item.mangaId] ?: return@forEach
			val values = arrayOf<Any?>(
				localMangaId,
				millis(item.updatedAt),
				millis(item.updatedAt),
				item.chapterId.orEmpty(),
				item.page ?: 0,
				0f,
				item.percent?.toFloat() ?: 0f,
				item.deletedAt?.let(::millis) ?: 0L,
				chaptersByManga[item.mangaId].orEmpty().size,
			)
			sql.execSQL(
				"INSERT OR IGNORE INTO history (manga_id, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
				values,
			)
			sql.execSQL(
				"UPDATE history SET created_at = ?, updated_at = ?, chapter_id = ?, page = ?, scroll = ?, percent = ?, deleted_at = ?, chapters = ? WHERE manga_id = ?",
				arrayOf(values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8], values[0]),
			)
		}
		deleteManagedRows("bookmarks")
		database.getBookmarksDao().upsert(
			snapshot.bookmarks.filter { it.deletedAt == null }.mapNotNull { item ->
				val localMangaId = localMangaIds[item.mangaId] ?: return@mapNotNull null
				identityMap.recordLocalKey("bookmark", item.id, "$localMangaId\u0000${item.id}")
				BookmarkEntity(
					mangaId = localMangaId,
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
		deleteManagedRows("scrobblings")
		snapshot.tracking.filter { it.deletedAt == null }.forEach { item ->
			val service = ScrobblerService.entries.firstOrNull { it.name.equals(item.service, true) } ?: return@forEach
			val localMangaId = localMangaIds[item.mangaId] ?: return@forEach
			val localId = identityMap.resolveLocalId("tracking:${item.service.lowercase()}", item.id)
			database.getScrobblingDao().upsert(
				ScrobblingEntity(
					scrobbler = service.id,
					id = localId,
					mangaId = localMangaId,
					targetId = localId.toLong(),
					status = item.status,
					chapter = item.progress?.toInt() ?: 0,
					comment = null,
					rating = item.score?.toFloat() ?: 0f,
				),
			)
		}
	}

	private fun millis(timestamp: String): Long = Instant.parse(timestamp).toEpochMilli()

	private fun mappedNumericLocalIds(kind: String): Set<Int> = buildSet {
		database.openHelper.readableDatabase.query(
			"SELECT local_key FROM nyora_backup_identity_map WHERE kind = ?",
			arrayOf(kind),
		).use { cursor -> while (cursor.moveToNext()) cursor.getString(0).toIntOrNull()?.let(::add) }
	}

	private fun hasFavouriteReference(categoryId: Int): Boolean = database.openHelper.readableDatabase.query(
		"SELECT EXISTS(SELECT 1 FROM favourites WHERE category_id = ?)",
		arrayOf(categoryId),
	).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) != 0 }

	private fun deleteManagedRows(table: String) {
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM $table WHERE manga_id IN ($MANAGED_MANGA_IDS_SQL)",
		)
	}

	private companion object {
		const val UNCATEGORIZED_DISPLAY_TITLE = "\u0000nyora internal uncategorized"
		const val MANAGED_MANGA_IDS_SQL =
			"SELECT manga_id FROM manga WHERE source LIKE 'data:%' OR source LIKE '%\"data:%' " +
				"UNION SELECT local_key FROM nyora_backup_identity_map WHERE kind = 'manga'"
	}
}
