package com.nyora.hasan72341.core

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.room.InvalidationTracker
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nyora.hasan72341.js.ParserOtaWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import com.nyora.hasan72341.BuildConfig
import com.nyora.hasan72341.core.db.MangaDatabase
import com.nyora.hasan72341.core.os.AppValidator
import com.nyora.hasan72341.core.prefs.AppSettings
import com.nyora.hasan72341.core.util.ext.processLifecycleScope
import com.nyora.hasan72341.local.data.LocalStorageChanges
import com.nyora.hasan72341.local.data.index.LocalMangaIndex
import com.nyora.hasan72341.local.domain.model.LocalManga
import com.nyora.hasan72341.settings.work.WorkScheduleManager
import com.nyora.hasan72341.sync.supabase.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import okhttp3.internal.platform.PlatformRegistry
import org.conscrypt.Conscrypt
import java.security.Security
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
open class BaseApp : Application(), Configuration.Provider {

	@Inject
	lateinit var databaseObserversProvider: Provider<Set<@JvmSuppressWildcards InvalidationTracker.Observer>>

	@Inject
	lateinit var activityLifecycleCallbacks: Set<@JvmSuppressWildcards ActivityLifecycleCallbacks>

	@Inject
	lateinit var database: Provider<MangaDatabase>

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var workerFactory: HiltWorkerFactory

	@Inject
	lateinit var appValidator: AppValidator

	@Inject
	lateinit var workScheduleManager: WorkScheduleManager

	@Inject
	lateinit var localMangaIndexProvider: Provider<LocalMangaIndex>

	@Inject
	@LocalStorageChanges
	lateinit var localStorageChanges: MutableSharedFlow<LocalManga?>

	@Inject
	lateinit var supabaseConfig: SupabaseConfig

	override val workManagerConfiguration: Configuration
		get() = Configuration.Builder()
			.setWorkerFactory(workerFactory)
			.build()

	override fun onCreate() {
		super.onCreate()
		PlatformRegistry.applicationContext = this // TODO replace with OkHttp.initialize
		AppCompatDelegate.setDefaultNightMode(settings.theme)
		
		// TLS 1.3 support for Android < 10
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			Security.insertProviderAt(Conscrypt.newProvider(), 1)
		}
		setupActivityLifecycleCallbacks()
		processLifecycleScope.launch(Dispatchers.IO) {
			setupDatabaseObservers()
			localStorageChanges.collect(localMangaIndexProvider.get())
		}
		workScheduleManager.init()
		setupParserOtaUpdate()
		supabaseConfig.configure(
			url = BuildConfig.SUPABASE_URL.ifBlank { "https://fqguzcoytnbnjwaddakn.supabase.co" },
			anonKey = BuildConfig.SUPABASE_ANON_KEY.ifBlank { "sb_publishable_RZTcdZZlzb_UhYAxtB09AQ_URTEftE4" }
		)
	}

	private fun setupParserOtaUpdate() {
		val constraints = Constraints.Builder()
			.setRequiredNetworkType(NetworkType.CONNECTED)
			.build()
		val oneTimeRequest = OneTimeWorkRequestBuilder<ParserOtaWorker>()
			.setConstraints(constraints)
			.build()
		val periodicRequest = PeriodicWorkRequestBuilder<ParserOtaWorker>(12, TimeUnit.HOURS)
			.setConstraints(constraints)
			.build()
		val workManager = WorkManager.getInstance(this)
		workManager.enqueueUniqueWork(
			"ParserOtaUpdateNow",
			ExistingWorkPolicy.REPLACE,
			oneTimeRequest
		)
		workManager.enqueueUniquePeriodicWork(
			"ParserOtaUpdate",
			ExistingPeriodicWorkPolicy.KEEP,
			periodicRequest
		)
	}

	override fun attachBaseContext(base: Context) {
		super.attachBaseContext(base)
		// ACRA removed
	} 

	@WorkerThread
	private fun setupDatabaseObservers() {
		val tracker = database.get().invalidationTracker
		databaseObserversProvider.get().forEach {
			tracker.addObserver(it)
		}
	}

	private fun setupActivityLifecycleCallbacks() {
		activityLifecycleCallbacks.forEach {
			registerActivityLifecycleCallbacks(it)
		}
	}
}
