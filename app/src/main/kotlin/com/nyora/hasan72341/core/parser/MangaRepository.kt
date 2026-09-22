package com.nyora.hasan72341.core.parser

import android.content.Context
import androidx.annotation.AnyThread
import androidx.collection.ArrayMap
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nyora.hasan72341.core.cache.MemoryContentCache
import com.nyora.hasan72341.core.model.DataDrivenMangaSource
import com.nyora.hasan72341.core.model.LocalMangaSource
import com.nyora.hasan72341.core.model.MangaSourceInfo
import com.nyora.hasan72341.core.model.TestMangaSource
import com.nyora.hasan72341.core.model.UnknownMangaSource
import com.nyora.hasan72341.core.network.MangaHttpClient
import com.nyora.hasan72341.core.parser.datadriven.DataDrivenCatalogue
import com.nyora.hasan72341.core.parser.datadriven.findCanonical
import com.nyora.hasan72341.core.prefs.SourceSettings
import com.nyora.hasan72341.local.data.LocalMangaRepository
import com.nyora.hasan72341.mihon.parsers.MangaLoaderContext
import com.nyora.hasan72341.mihon.parsers.config.ConfigKey
import com.nyora.hasan72341.mihon.parsers.model.Manga
import com.nyora.hasan72341.mihon.parsers.model.MangaChapter
import com.nyora.hasan72341.mihon.parsers.model.MangaListFilter
import com.nyora.hasan72341.mihon.parsers.model.MangaListFilterCapabilities
import com.nyora.hasan72341.mihon.parsers.model.MangaListFilterOptions
import com.nyora.hasan72341.mihon.parsers.model.MangaPage
import com.nyora.hasan72341.mihon.parsers.model.MangaParserSource
import com.nyora.hasan72341.mihon.parsers.model.MangaSource
import com.nyora.hasan72341.mihon.parsers.model.SortOrder
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

interface MangaRepository {

	val source: MangaSource

	val sortOrders: Set<SortOrder>

	var defaultSortOrder: SortOrder

	val filterCapabilities: MangaListFilterCapabilities

	suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga>

	suspend fun getDetails(manga: Manga): Manga

	suspend fun getPages(chapter: MangaChapter): List<MangaPage>

	suspend fun getPageUrl(page: MangaPage): String

	/**
	 * The full image request for [page]: its url plus every header the source needs to serve it.
	 * Sources that need nothing beyond the page's own headers use this default.
	 */
	suspend fun getPageRequest(page: MangaPage): MangaPageRequest =
		MangaPageRequest(url = getPageUrl(page), headers = page.headers)

	suspend fun getFilterOptions(): MangaListFilterOptions

	suspend fun getRelated(seed: Manga): List<Manga>

	suspend fun find(manga: Manga): Manga? {
		val list = getList(0, SortOrder.RELEVANCE, MangaListFilter(query = manga.title))
		return list.find { x -> x.id == manga.id }
	}

	@Singleton
	class Factory @Inject constructor(
		@ApplicationContext private val context: Context,
		private val localMangaRepository: LocalMangaRepository,
		private val loaderContext: MangaLoaderContext,
		private val contentCache: MemoryContentCache,
		private val mirrorSwitcher: MirrorSwitcher,
		private val dataDrivenCatalogue: DataDrivenCatalogue,
		@MangaHttpClient private val okHttpClient: OkHttpClient,
	) {

		private val cache = ArrayMap<MangaSource, WeakReference<MangaRepository>>()

		@AnyThread
		fun create(source: MangaSource): MangaRepository {
			when (source) {
				is MangaSourceInfo -> return create(source.mangaSource)
				LocalMangaSource -> return localMangaRepository
				UnknownMangaSource -> return EmptyMangaRepository(source)
			}
			cache[source]?.get()?.let { return it }
			return synchronized(cache) {
				cache[source]?.get()?.let { return it }
				val repository = createRepository(source)
				if (repository != null) {
					cache[source] = WeakReference(repository)
					repository
				} else {
					EmptyMangaRepository(source)
				}
			}
		}

		private fun createRepository(source: MangaSource): MangaRepository? = when (source) {
			TestMangaSource -> TestMangaRepository(
				loaderContext = loaderContext,
				cache = contentCache,
			)

			is DataDrivenMangaSource -> createDataDrivenRepository(source)

			else -> {
				if (source.name.startsWith(DataDrivenMangaSource.PREFIX)) {
					// A row persisted before a rename or retirement arrives here as an anonymous
					// source; the same canonicalisation the source factory applies finds its entry.
					dataDrivenCatalogue.findCanonical(source.name)?.let {
						return createDataDrivenRepository(it)
					}
				}
				null
			}
		}

		private fun createDataDrivenRepository(source: DataDrivenMangaSource): MangaRepository {
			val settings = SourceSettings(context, source)
			val domainKey = ConfigKey.Domain(source.domain)
			return DataDrivenMangaRepository(
				source = source,
				okHttpClient = okHttpClient,
				cache = contentCache,
				// The user's per-source domain, or null while they are still on the catalogue's.
				domainOverride = { settings[domainKey].takeIf { it != source.domain } },
			)
		}
	}
}

/** A repository whose source has an effective domain, which page and cover requests take a Referer from. */
interface DomainAwareRepository {

	val domain: String
}

/** An image request for one manga page: the resolved url plus the headers the source serves it under. */
data class MangaPageRequest(
	val url: String,
	val headers: Map<String, String> = emptyMap(),
)
