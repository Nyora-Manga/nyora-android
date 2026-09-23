package com.nyora.hasan72341.core.parser

import android.util.Log
import coil3.request.CachePolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import com.nyora.hasan72341.BuildConfig
import com.nyora.hasan72341.core.cache.MemoryContentCache
import com.nyora.hasan72341.core.cache.SafeDeferred
import com.nyora.hasan72341.core.util.MultiMutex
import com.nyora.hasan72341.core.util.ext.processLifecycleScope
import com.nyora.hasan72341.mihon.parsers.model.Manga
import com.nyora.hasan72341.mihon.parsers.model.MangaChapter
import com.nyora.hasan72341.mihon.parsers.model.MangaPage
import com.nyora.hasan72341.mihon.parsers.util.runCatchingCancellable

abstract class CachingMangaRepository(
	/**
	 * Null when there is no content cache to share results through, which is the case in unit tests:
	 * [MemoryContentCache] needs an [android.app.Application]. The work then runs inline instead of
	 * being detached onto the process lifecycle scope, since there is nothing left to share it with.
	 */
	private val cache: MemoryContentCache?,
) : MangaRepository {

	private val detailsMutex = MultiMutex<String>()
	private val relatedMangaMutex = MultiMutex<String>()
	private val pagesMutex = MultiMutex<String>()

	final override suspend fun getDetails(manga: Manga): Manga = getDetails(manga, CachePolicy.ENABLED)

	final override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val cache = cache ?: return getPagesImpl(chapter).distinctById()
		return pagesMutex.withLock(chapter.id) {
			cache.getPages(source, chapter.url)?.let { return it }
			val pages = asyncSafe {
				getPagesImpl(chapter).distinctById()
			}
			cache.putPages(source, chapter.url, pages)
			pages
		}.await()
	}

	final override suspend fun getRelated(seed: Manga): List<Manga> {
		val cache = cache ?: return getRelatedMangaImpl(seed).filterNot { it.id == seed.id }
		return relatedMangaMutex.withLock(seed.id) {
			cache.getRelatedManga(source, seed.url)?.let { return it }
			val related = asyncSafe {
				getRelatedMangaImpl(seed).filterNot { it.id == seed.id }
			}
			cache.putRelatedManga(source, seed.url, related)
			related
		}.await()
	}

	suspend fun getDetails(manga: Manga, cachePolicy: CachePolicy): Manga {
		val cache = cache ?: return getDetailsImpl(manga)
		return detailsMutex.withLock(manga.id) {
			if (cachePolicy.readEnabled) {
				cache.getDetails(source, manga.url)?.let { return it }
			}
			val details = asyncSafe {
				getDetailsImpl(manga)
			}
			if (cachePolicy.writeEnabled) {
				cache.putDetails(source, manga.url, details)
			}
			details
		}.await()
	}

	suspend fun peekDetails(manga: Manga): Manga? {
		return cache?.getDetails(source, manga.url)
	}

	fun invalidateCache() {
		cache?.clear(source)
	}

	protected abstract suspend fun getDetailsImpl(manga: Manga): Manga

	protected abstract suspend fun getRelatedMangaImpl(seed: Manga): List<Manga>

	protected abstract suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage>

	private suspend fun <T> asyncSafe(block: suspend CoroutineScope.() -> T): SafeDeferred<T> {
		var dispatcher = currentCoroutineContext()[CoroutineDispatcher.Key]
		if (dispatcher == null || dispatcher is MainCoroutineDispatcher) {
			dispatcher = Dispatchers.IO
		}
		return SafeDeferred(
			processLifecycleScope.async(dispatcher) {
				runCatchingCancellable { block() }
			},
		)
	}

	private fun List<MangaPage>.distinctById(): List<MangaPage> {
		if (isEmpty()) {
			return emptyList()
		}
		val result = ArrayList<MangaPage>(size)
		val set = HashSet<String>(size)
		for (page in this) {
			if (set.add(page.url)) {
				result.add(page)
			} else if (BuildConfig.DEBUG) {
				Log.w(null, "Duplicate page: $page")
			}
		}
		return result
	}
}
