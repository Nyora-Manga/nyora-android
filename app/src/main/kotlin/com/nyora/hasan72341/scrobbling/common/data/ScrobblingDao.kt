package com.nyora.hasan72341.scrobbling.common.data

import androidx.room.*
import androidx.room.Query
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

@Dao
abstract class ScrobblingDao {
	@Query("SELECT * FROM scrobblings ORDER BY scrobbler, id, manga_id")
	abstract suspend fun findAllForBackup(): List<ScrobblingEntity>

	@Query("SELECT * FROM scrobblings WHERE scrobbler = :scrobbler AND manga_id = :mangaId")
	abstract suspend fun find(scrobbler: Int, mangaId: String): ScrobblingEntity?

	@Query("SELECT * FROM scrobblings WHERE scrobbler = :scrobbler AND manga_id = :mangaId")
	abstract fun observe(scrobbler: Int, mangaId: String): Flow<ScrobblingEntity?>

	@Query("SELECT * FROM scrobblings WHERE scrobbler = :scrobbler")
	abstract fun observe(scrobbler: Int): Flow<List<ScrobblingEntity>>

	@Upsert
	abstract suspend fun upsert(entity: ScrobblingEntity)

	@Query("DELETE FROM scrobblings WHERE scrobbler = :scrobbler AND manga_id = :mangaId")
	abstract suspend fun delete(scrobbler: Int, mangaId: String)

	@Query("SELECT * FROM scrobblings ORDER BY scrobbler LIMIT :limit OFFSET :offset")
	protected abstract suspend fun findAll(offset: Int, limit: Int): List<ScrobblingEntity>

	fun dumpEnabled(): Flow<ScrobblingEntity> = flow {
		val window = 10
		var offset = 0
		while (currentCoroutineContext().isActive) {
			val list = findAll(offset, window)
			if (list.isEmpty()) {
				break
			}
			offset += window
			list.forEach { emit(it) }
		}
	}
}
