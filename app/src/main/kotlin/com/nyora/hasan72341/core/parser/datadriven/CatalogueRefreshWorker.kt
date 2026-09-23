package com.nyora.hasan72341.core.parser.datadriven

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nyora.hasan72341.BuildConfig
import com.nyora.hasan72341.core.network.BaseHttpClient
import com.nyora.hasan72341.core.util.ext.printStackTraceDebug
import com.nyora.hasan72341.explore.data.MangaSourcesRepository
import com.nyora.hasan72341.mihon.parsers.util.runCatchingCancellable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Keeps the data-driven catalogue current: once a day it downloads the published catalogue of the
 * data branch this build bundles (`.gitmodules` pins it, `BuildConfig.NYORA_CATALOGUE_REF` carries
 * it) and hands it to [DataDrivenCatalogue.replace], which swaps the snapshot only when the
 * download parses and differs from the catalogue already serving. A failed refresh therefore leaves
 * the previous catalogue serving every source, and a refresh can never fall back to another branch.
 *
 * After a swap the source table is reconciled at once, so rows the new catalogue adds reach the
 * source list (default-enabled per the onboarding language choice) and rows it retired leave it,
 * without waiting for the next launch.
 */
@HiltWorker
class CatalogueRefreshWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted workerParams: WorkerParameters,
	private val catalogue: DataDrivenCatalogue,
	@BaseHttpClient private val httpClient: OkHttpClient,
	private val sourcesRepository: MangaSourcesRepository,
) : CoroutineWorker(context, workerParams) {

	override suspend fun doWork(): Result = runCatchingCancellable {
		refresh(httpClient, catalogue, sourcesRepository)
	}.onFailure {
		it.printStackTraceDebug(TAG)
	}.fold(
		onSuccess = { Result.success() },
		onFailure = { Result.failure() },
	)

	/** Lets [runNow] reach the same dependencies from a call site that has no injector. */
	@EntryPoint
	@InstallIn(SingletonComponent::class)
	interface Dependencies {

		fun dataDrivenCatalogue(): DataDrivenCatalogue

		@BaseHttpClient
		fun baseHttpClient(): OkHttpClient

		fun mangaSourcesRepository(): MangaSourcesRepository
	}

	companion object {

		private const val TAG = "CatalogueRefresh"
		private const val UNIQUE_WORK_NAME = "CatalogueRefresh"
		private const val CATALOGUE_REPOSITORY = "https://raw.githubusercontent.com/nyora-manga/nyora-data-driven"

		/** Git ref names this worker will put in a URL path: no spaces and no `..` segments. */
		private val REF_REGEX = Regex("[A-Za-z0-9._/-]+")

		/** The published catalogue of the ref this build's data submodule is pinned to. */
		private val CATALOGUE_URL: String = catalogueUrl(BuildConfig.NYORA_CATALOGUE_REF)

		/** `catalogue.json` of [ref] in the data repository; a ref that is not a usable path segment falls back to `main`. */
		internal fun catalogueUrl(ref: String): String {
			val trimmed = ref.trim()
			val safeRef = trimmed.takeIf { REF_REGEX.matches(it) && !it.contains("..") } ?: "main"
			return "$CATALOGUE_REPOSITORY/$safeRef/catalogue.json"
		}

		/** The JavaScript parser OTA this worker replaces; upgraded installs still have it enqueued. */
		private val RETIRED_WORK_NAMES = arrayOf("ParserOtaUpdate", "ParserOtaUpdateNow")

		/** Enqueue the daily refresh, keeping an already scheduled one so its period never restarts. */
		fun schedule(context: Context) {
			val request = PeriodicWorkRequestBuilder<CatalogueRefreshWorker>(24, TimeUnit.HOURS)
				.setConstraints(
					Constraints.Builder()
						.setRequiredNetworkType(NetworkType.CONNECTED)
						.build(),
				)
				.build()
			val workManager = WorkManager.getInstance(context)
			// Without this an upgraded install keeps retrying a worker whose class is gone.
			RETIRED_WORK_NAMES.forEach(workManager::cancelUniqueWork)
			workManager.enqueueUniquePeriodicWork(
				UNIQUE_WORK_NAME,
				ExistingPeriodicWorkPolicy.KEEP,
				request,
			)
		}

		/**
		 * Refresh the catalogue immediately, for the settings screen's explicit check.
		 *
		 * @return the number of sources the new catalogue serves, or the failure that kept the old one.
		 */
		suspend fun runNow(context: Context): kotlin.Result<Int> {
			val dependencies = EntryPointAccessors.fromApplication<Dependencies>(context.applicationContext)
			return runCatchingCancellable {
				refresh(dependencies.baseHttpClient(), dependencies.dataDrivenCatalogue(), dependencies.mangaSourcesRepository())
			}
		}

		private suspend fun refresh(
			httpClient: OkHttpClient,
			catalogue: DataDrivenCatalogue,
			sourcesRepository: MangaSourcesRepository,
		): Int = withContext(Dispatchers.IO) {
			val request = Request.Builder()
				.url(CATALOGUE_URL)
				.build()
			val body = httpClient.newCall(request).execute().use { response ->
				check(response.isSuccessful) { "HTTP ${response.code} fetching the source catalogue" }
				response.body?.string().orEmpty()
			}
			val count = catalogue.replace(body)
			sourcesRepository.assimilateFromCatalogue()
			count
		}
	}
}
