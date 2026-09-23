package com.nyora.hasan72341.bookmarks.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import com.nyora.hasan72341.core.db.entity.MangaEntity

@Dao
abstract class BookmarksDao {
	@Query("SELECT * FROM bookmarks ORDER BY manga_id, page_id")
	abstract suspend fun findAllForBackup(): List<BookmarkEntity>

	@Query("SELECT * FROM bookmarks WHERE page_id = :pageId AND deleted_at = 0")
	abstract suspend fun find(pageId: String): BookmarkEntity?

	@Transaction
	@Query(
		"SELECT * FROM manga JOIN bookmarks ON bookmarks.manga_id = manga.manga_id WHERE bookmarks.deleted_at = 0 ORDER BY percent LIMIT :limit OFFSET :offset",
	)
	abstract suspend fun findAll(offset: Int, limit: Int): Map<MangaEntity, List<BookmarkEntity>>

	@Transaction
	@Query(
		"SELECT * FROM manga JOIN bookmarks ON bookmarks.manga_id = manga.manga_id ORDER BY percent LIMIT :limit OFFSET :offset",
	)
	abstract suspend fun findAllIncludingDeleted(offset: Int, limit: Int): Map<MangaEntity, List<BookmarkEntity>>

	@Query("SELECT * FROM bookmarks WHERE manga_id = :mangaId AND chapter_id = :chapterId AND page = :page AND deleted_at = 0 ORDER BY percent")
	abstract fun observe(mangaId: String, chapterId: String, page: Int): Flow<BookmarkEntity?>

	@Query("SELECT * FROM bookmarks WHERE manga_id = :mangaId AND deleted_at = 0 ORDER BY percent")
	abstract fun observe(mangaId: String): Flow<List<BookmarkEntity>>

	@Transaction
	@Query(
		"SELECT * FROM manga JOIN bookmarks ON bookmarks.manga_id = manga.manga_id WHERE bookmarks.deleted_at = 0 ORDER BY percent",
	)
	abstract fun observe(): Flow<Map<MangaEntity, List<BookmarkEntity>>>

	@Insert
	abstract suspend fun insert(entity: BookmarkEntity)

	@Query("UPDATE bookmarks SET deleted_at = :deletedAt WHERE page_id = :pageId")
	abstract suspend fun softDelete(pageId: String, deletedAt: Long): Int

	suspend fun delete(entity: BookmarkEntity) {
		softDelete(entity.pageId, System.currentTimeMillis())
	}

	suspend fun delete(pageId: String): Int {
		return softDelete(pageId, System.currentTimeMillis())
	}

	@Query("UPDATE bookmarks SET deleted_at = :deletedAt WHERE manga_id = :mangaId AND chapter_id = :chapterId AND page = :page")
	abstract suspend fun softDelete(mangaId: String, chapterId: String, page: Int, deletedAt: Long): Int

	suspend fun delete(mangaId: String, chapterId: String, page: Int): Int {
		return softDelete(mangaId, chapterId, page, System.currentTimeMillis())
	}

	@Upsert
	abstract suspend fun upsert(bookmarks: Collection<BookmarkEntity>)

	fun dump(): Flow<Pair<MangaEntity, List<BookmarkEntity>>> = flow {
		val window = 4
		var offset = 0
		while (currentCoroutineContext().isActive) {
			val list = findAll(offset, window)
			if (list.isEmpty()) {
				break
			}
			offset += window
			list.forEach { emit(it.key to it.value) }
		}
	}
}
