package com.nyora.hasan72341.favourites.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomWarnings
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class FavouriteCategoriesDao {

	@Query("SELECT * FROM favourite_categories WHERE category_id = :id AND deleted_at = 0 AND title != '$NYORA_UNCATEGORIZED_CATEGORY_TITLE'")
	abstract suspend fun find(id: Int): FavouriteCategoryEntity

	@Query("SELECT * FROM favourite_categories WHERE deleted_at = 0 AND title != '$NYORA_UNCATEGORIZED_CATEGORY_TITLE' ORDER BY sort_key")
	abstract suspend fun findAll(): List<FavouriteCategoryEntity>

	// Includes soft-deleted rows so category deletions actually propagate on sync push.
	@Query("SELECT * FROM favourite_categories WHERE title != '$NYORA_UNCATEGORIZED_CATEGORY_TITLE' ORDER BY sort_key")
	abstract suspend fun findAllForSync(): List<FavouriteCategoryEntity>

	// Move favourites off non-canonical duplicate categories onto the lowest-id one sharing the title.
	@Query("UPDATE OR IGNORE favourites SET category_id = (SELECT MIN(c.category_id) FROM favourite_categories c WHERE c.deleted_at = 0 AND c.title = (SELECT t.title FROM favourite_categories t WHERE t.category_id = favourites.category_id)) WHERE category_id IN (SELECT c.category_id FROM favourite_categories c WHERE c.deleted_at = 0 AND c.category_id <> (SELECT MIN(c2.category_id) FROM favourite_categories c2 WHERE c2.deleted_at = 0 AND c2.title = c.title))")
	abstract suspend fun repointDuplicateFavourites()

	// Soft-delete every category that is not the lowest id for its title (collapses duplicates).
	@Query("UPDATE favourite_categories SET deleted_at = :now WHERE deleted_at = 0 AND category_id <> (SELECT MIN(c2.category_id) FROM favourite_categories c2 WHERE c2.deleted_at = 0 AND c2.title = favourite_categories.title)")
	abstract suspend fun softDeleteDuplicateCategories(now: Long)

	@Query("SELECT * FROM favourite_categories WHERE deleted_at = 0 AND title != '$NYORA_UNCATEGORIZED_CATEGORY_TITLE' ORDER BY sort_key")
	abstract fun observeAll(): Flow<List<FavouriteCategoryEntity>>

	@Query("SELECT * FROM favourite_categories WHERE deleted_at = 0 AND show_in_lib = 1 AND title != '$NYORA_UNCATEGORIZED_CATEGORY_TITLE' ORDER BY sort_key")
	abstract fun observeAllVisible(): Flow<List<FavouriteCategoryEntity>>

	@Query("SELECT * FROM favourite_categories WHERE category_id = :id AND deleted_at = 0 AND title != '$NYORA_UNCATEGORIZED_CATEGORY_TITLE'")
	abstract fun observe(id: Long): Flow<FavouriteCategoryEntity?>

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insert(category: FavouriteCategoryEntity): Long

	suspend fun delete(id: Long) = setDeletedAt(id, System.currentTimeMillis())

	@Query("UPDATE favourite_categories SET title = :title, `order` = :order, `track` = :tracker, `show_in_lib` = :onShelf WHERE category_id = :id AND title != '$NYORA_UNCATEGORIZED_CATEGORY_TITLE'")
	abstract suspend fun update(id: Long, title: String, order: String, tracker: Boolean, onShelf: Boolean)

	@Query("UPDATE favourite_categories SET `order` = :order WHERE category_id = :id AND title != '$NYORA_UNCATEGORIZED_CATEGORY_TITLE'")
	abstract suspend fun updateOrder(id: Long, order: String)

	@Query("UPDATE favourite_categories SET `track` = :isEnabled WHERE category_id = :id AND title != '$NYORA_UNCATEGORIZED_CATEGORY_TITLE'")
	abstract suspend fun updateTracking(id: Long, isEnabled: Boolean)

	@Query("UPDATE favourite_categories SET `show_in_lib` = :isEnabled WHERE category_id = :id AND title != '$NYORA_UNCATEGORIZED_CATEGORY_TITLE'")
	abstract suspend fun updateVisibility(id: Long, isEnabled: Boolean)

	@Query("UPDATE favourite_categories SET sort_key = :sortKey WHERE category_id = :id AND title != '$NYORA_UNCATEGORIZED_CATEGORY_TITLE'")
	abstract suspend fun updateSortKey(id: Long, sortKey: Int)

	@Query("DELETE FROM favourite_categories WHERE deleted_at != 0 AND deleted_at < :maxDeletionTime")
	abstract suspend fun gc(maxDeletionTime: Long)

	@Query("SELECT MAX(sort_key) FROM favourite_categories WHERE deleted_at = 0 AND title != '$NYORA_UNCATEGORIZED_CATEGORY_TITLE'")
	protected abstract suspend fun getMaxSortKey(): Int?

	@SuppressWarnings(RoomWarnings.QUERY_MISMATCH) // for the new_chapters column
	@Query("SELECT favourite_categories.*, (SELECT SUM(chapters_new) FROM tracks WHERE tracks.manga_id IN (SELECT manga_id FROM favourites WHERE favourites.category_id = favourite_categories.category_id)) AS new_chapters FROM favourite_categories WHERE track = 1 AND show_in_lib = 1 AND deleted_at = 0 AND title != '$NYORA_UNCATEGORIZED_CATEGORY_TITLE' AND new_chapters > 0 ORDER BY new_chapters DESC LIMIT :limit")
	abstract suspend fun getMostUpdatedCategories(limit: Int): List<FavouriteCategoryEntity>

	suspend fun getNextSortKey(): Int {
		return (getMaxSortKey() ?: 0) + 1
	}

	@Upsert
	abstract suspend fun upsert(entity: FavouriteCategoryEntity)

	@Query("UPDATE favourite_categories SET deleted_at = :deletedAt WHERE category_id = :id AND title != '$NYORA_UNCATEGORIZED_CATEGORY_TITLE'")
	protected abstract suspend fun setDeletedAt(id: Long, deletedAt: Long)
}
