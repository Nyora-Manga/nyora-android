package com.nyora.hasan72341.backups.data

import com.nyora.hasan72341.backups.domain.BackupObserver
import com.nyora.hasan72341.core.db.MangaDatabase
import com.nyora.hasan72341.core.db.TABLE_FAVOURITES
import com.nyora.hasan72341.core.db.TABLE_FAVOURITE_CATEGORIES
import com.nyora.hasan72341.core.db.TABLE_HISTORY

class NyoraRoomBackupObserverGate(
	private val database: MangaDatabase,
	private val observer: BackupObserver,
) : NyoraBackupObserverGate {
	override suspend fun setSuppressed(suppressed: Boolean) {
		if (suppressed) database.invalidationTracker.removeObserver(observer)
		else database.invalidationTracker.addObserver(observer)
	}

	override suspend fun reconcile() {
		observer.onInvalidated(setOf(TABLE_HISTORY, TABLE_FAVOURITES, TABLE_FAVOURITE_CATEGORIES))
	}
}
