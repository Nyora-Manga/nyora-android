package com.nyora.hasan72341.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.nyora.hasan72341.backups.data.NyoraBackupLedgerDao
import com.nyora.hasan72341.backups.data.NyoraBackupLedgerEntity
import com.nyora.hasan72341.backups.data.NyoraBackupIdentityMapDao
import com.nyora.hasan72341.backups.data.NyoraBackupIdentityMapEntity
import com.nyora.hasan72341.bookmarks.data.BookmarkEntity
import com.nyora.hasan72341.bookmarks.data.BookmarksDao
import com.nyora.hasan72341.core.db.dao.ExternalExtensionRepoDao
import com.nyora.hasan72341.core.db.dao.MangaDao
import com.nyora.hasan72341.core.db.dao.MangaSourcesDao
import com.nyora.hasan72341.core.db.dao.PreferencesDao
import com.nyora.hasan72341.core.db.dao.TrackLogsDao
import com.nyora.hasan72341.core.db.entity.ExternalExtensionRepoEntity
import com.nyora.hasan72341.core.db.entity.MangaEntity
import com.nyora.hasan72341.core.db.entity.MangaPrefsEntity
import com.nyora.hasan72341.core.db.entity.MangaSourceEntity
import com.nyora.hasan72341.core.db.migrations.Migration10To11
import com.nyora.hasan72341.core.db.migrations.Migration11To12
import com.nyora.hasan72341.core.db.migrations.Migration12To13
import com.nyora.hasan72341.core.db.migrations.Migration13To14
import com.nyora.hasan72341.core.db.migrations.Migration14To15
import com.nyora.hasan72341.core.db.migrations.Migration15To16
import com.nyora.hasan72341.core.db.migrations.Migration16To17
import com.nyora.hasan72341.core.db.migrations.Migration17To18
import com.nyora.hasan72341.core.db.migrations.Migration18To19
import com.nyora.hasan72341.core.db.migrations.Migration19To20
import com.nyora.hasan72341.core.db.migrations.Migration1To2
import com.nyora.hasan72341.core.db.migrations.Migration20To21
import com.nyora.hasan72341.core.db.migrations.Migration21To22
import com.nyora.hasan72341.core.db.migrations.Migration22To23
import com.nyora.hasan72341.core.db.migrations.Migration23To24
import com.nyora.hasan72341.core.db.migrations.Migration24To23
import com.nyora.hasan72341.core.db.migrations.Migration24To25
import com.nyora.hasan72341.core.db.migrations.Migration25To26
import com.nyora.hasan72341.core.db.migrations.Migration26To27
import com.nyora.hasan72341.core.db.migrations.Migration27To28
import com.nyora.hasan72341.core.db.migrations.Migration28To29
import com.nyora.hasan72341.core.db.migrations.Migration29To30
import com.nyora.hasan72341.core.db.migrations.Migration30To31
import com.nyora.hasan72341.core.db.migrations.Migration31To32
import com.nyora.hasan72341.core.db.migrations.Migration32To33
import com.nyora.hasan72341.core.db.migrations.Migration33To34
import com.nyora.hasan72341.core.db.migrations.Migration2To3
import com.nyora.hasan72341.core.db.migrations.Migration3To4
import com.nyora.hasan72341.core.db.migrations.Migration4To5
import com.nyora.hasan72341.core.db.migrations.Migration5To6
import com.nyora.hasan72341.core.db.migrations.Migration6To7
import com.nyora.hasan72341.core.db.migrations.Migration7To8
import com.nyora.hasan72341.core.db.migrations.Migration8To9
import com.nyora.hasan72341.core.db.migrations.Migration9To10
import com.nyora.hasan72341.core.util.ext.processLifecycleScope
import com.nyora.hasan72341.favourites.data.FavouriteCategoriesDao
import com.nyora.hasan72341.favourites.data.FavouriteCategoryEntity
import com.nyora.hasan72341.favourites.data.FavouriteEntity
import com.nyora.hasan72341.favourites.data.FavouritesDao
import com.nyora.hasan72341.history.data.HistoryDao
import com.nyora.hasan72341.history.data.HistoryEntity
import com.nyora.hasan72341.local.data.index.LocalMangaIndexDao
import com.nyora.hasan72341.local.data.index.LocalMangaIndexEntity
import com.nyora.hasan72341.scrobbling.common.data.ScrobblingDao
import com.nyora.hasan72341.scrobbling.common.data.ScrobblingEntity
import com.nyora.hasan72341.stats.data.StatsDao
import com.nyora.hasan72341.stats.data.StatsEntity
import com.nyora.hasan72341.suggestions.data.SuggestionDao
import com.nyora.hasan72341.suggestions.data.SuggestionEntity
import com.nyora.hasan72341.tracker.data.TrackEntity
import com.nyora.hasan72341.tracker.data.TrackLogEntity
import com.nyora.hasan72341.tracker.data.TracksDao
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val DATABASE_VERSION = 34

@Database(
	entities = [
		MangaEntity::class, HistoryEntity::class,
		FavouriteCategoryEntity::class, FavouriteEntity::class, MangaPrefsEntity::class, TrackEntity::class,
		TrackLogEntity::class, SuggestionEntity::class, BookmarkEntity::class, ScrobblingEntity::class,
		MangaSourceEntity::class, StatsEntity::class, LocalMangaIndexEntity::class, ExternalExtensionRepoEntity::class,
		NyoraBackupLedgerEntity::class,
		NyoraBackupIdentityMapEntity::class,
	],
	version = DATABASE_VERSION,
)
abstract class MangaDatabase : RoomDatabase() {

	abstract fun getHistoryDao(): HistoryDao

	abstract fun getMangaDao(): MangaDao

	abstract fun getFavouritesDao(): FavouritesDao

	abstract fun getPreferencesDao(): PreferencesDao

	abstract fun getFavouriteCategoriesDao(): FavouriteCategoriesDao

	abstract fun getTracksDao(): TracksDao

	abstract fun getTrackLogsDao(): TrackLogsDao

	abstract fun getSuggestionDao(): SuggestionDao

	abstract fun getBookmarksDao(): BookmarksDao

	abstract fun getScrobblingDao(): ScrobblingDao

	abstract fun getSourcesDao(): MangaSourcesDao

	abstract fun getStatsDao(): StatsDao

	abstract fun getLocalMangaIndexDao(): LocalMangaIndexDao

	abstract fun getExternalExtensionRepoDao(): ExternalExtensionRepoDao

	abstract fun getNyoraBackupLedgerDao(): NyoraBackupLedgerDao

	abstract fun getNyoraBackupIdentityMapDao(): NyoraBackupIdentityMapDao
}

fun getDatabaseMigrations(context: Context): Array<Migration> = arrayOf(
	Migration1To2(),
	Migration2To3(),
	Migration3To4(),
	Migration4To5(),
	Migration5To6(),
	Migration6To7(),
	Migration7To8(),
	Migration8To9(),
	Migration9To10(),
	Migration10To11(),
	Migration11To12(),
	Migration12To13(),
	Migration13To14(),
	Migration14To15(),
	Migration15To16(),
	Migration16To17(context),
	Migration17To18(),
	Migration18To19(),
	Migration19To20(),
	Migration20To21(),
	Migration21To22(),
	Migration22To23(),
	Migration23To24(),
	Migration24To23(),
	Migration24To25(),
	Migration25To26(),
	Migration26To27(),
	Migration27To28(),
	Migration28To29(),
	Migration29To30(),
	Migration30To31(),
	Migration31To32(),
	Migration32To33(),
	Migration33To34(),
)

fun MangaDatabase(context: Context): MangaDatabase = Room
	.databaseBuilder(context, MangaDatabase::class.java, "nyora-db")
	.addMigrations(*getDatabaseMigrations(context))
	.addCallback(DatabasePrePopulateCallback())
	.build()

fun InvalidationTracker.removeObserverAsync(observer: InvalidationTracker.Observer) {
	val scope = processLifecycleScope
	if (scope.isActive) {
		processLifecycleScope.launch(Dispatchers.IO, CoroutineStart.ATOMIC) {
			removeObserver(observer)
		}
	}
}
