package com.nyora.hasan72341.details.ui.pager.pages

import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.network.HttpException
import coil3.network.NetworkHeaders
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import coil3.request.Options
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import com.nyora.hasan72341.core.model.MangaSource
import com.nyora.hasan72341.core.network.MangaHttpClient
import com.nyora.hasan72341.core.network.imageproxy.ImageProxyInterceptor
import com.nyora.hasan72341.core.parser.MangaPageRequest
import com.nyora.hasan72341.core.parser.MangaRepository
import com.nyora.hasan72341.core.util.MimeTypes
import com.nyora.hasan72341.core.util.ext.fetch
import com.nyora.hasan72341.core.util.ext.isNetworkUri
import com.nyora.hasan72341.core.util.ext.toMimeTypeOrNull
import com.nyora.hasan72341.local.data.LocalStorageCache
import com.nyora.hasan72341.local.data.PageCache
import com.nyora.hasan72341.mihon.parsers.model.MangaPage
import com.nyora.hasan72341.mihon.parsers.util.mimeType
import com.nyora.hasan72341.mihon.parsers.util.requireBody
import com.nyora.hasan72341.mihon.parsers.util.runCatchingCancellable
import com.nyora.hasan72341.reader.domain.PageLoader
import javax.inject.Inject

class MangaPageFetcher(
	private val okHttpClient: OkHttpClient,
	private val pagesCache: LocalStorageCache,
	private val options: Options,
	private val page: MangaPage,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val imageProxyInterceptor: ImageProxyInterceptor,
	private val imageLoader: ImageLoader,
) : Fetcher {

	override suspend fun fetch(): FetchResult? {
		if (!page.preview.isNullOrEmpty()) {
			runCatchingCancellable {
				imageLoader.fetch(checkNotNull(page.preview), options)
			}.onSuccess {
				return it
			}
		}
		val repo = mangaRepositoryFactory.create(MangaSource(page.source?.name))
		val pageRequest = repo.getPageRequest(page)
		val pageUrl = pageRequest.url
		if (options.diskCachePolicy.readEnabled) {
			pagesCache[pageUrl]?.let { file ->
				return SourceFetchResult(
					source = ImageSource(file.toOkioPath(), options.fileSystem),
					mimeType = MimeTypes.getMimeTypeFromExtension(file.name)?.toString(),
					dataSource = DataSource.DISK,
				)
			}
		}
		return loadPage(pageRequest)
	}

	private suspend fun loadPage(pageRequest: MangaPageRequest): FetchResult? =
		if (pageRequest.url.toUri().isNetworkUri()) {
			fetchPage(pageRequest)
		} else {
			imageLoader.fetch(pageRequest.url, options)
		}

	private suspend fun fetchPage(pageRequest: MangaPageRequest): FetchResult {
		val pageUrl = pageRequest.url
		val request = PageLoader.createPageRequest(pageUrl, pageRequest.headers)
		return imageProxyInterceptor.interceptPageRequest(request, okHttpClient).use { response ->
			if (!response.isSuccessful) {
				throw HttpException(response.toNetworkResponse())
			}
			val mimeType = response.mimeType?.toMimeTypeOrNull()
			val file = response.requireBody().use {
				pagesCache.set(pageUrl, it.source(), mimeType)
			}
			SourceFetchResult(
				source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
				mimeType = mimeType?.toString(),
				dataSource = DataSource.NETWORK,
			)
		}
	}

	private fun Response.toNetworkResponse() = NetworkResponse(
		code = code,
		requestMillis = sentRequestAtMillis,
		responseMillis = receivedResponseAtMillis,
		headers = headers.toNetworkHeaders(),
		body = body?.source()?.let(::NetworkResponseBody),
		delegate = this,
	)

	private fun Headers.toNetworkHeaders(): NetworkHeaders {
		val headers = NetworkHeaders.Builder()
		for ((key, values) in this) {
			headers.add(key, values)
		}
		return headers.build()
	}

	class Factory @Inject constructor(
		@MangaHttpClient private val okHttpClient: OkHttpClient,
		@PageCache private val pagesCache: LocalStorageCache,
		private val mangaRepositoryFactory: MangaRepository.Factory,
		private val imageProxyInterceptor: ImageProxyInterceptor,
	) : Fetcher.Factory<MangaPage> {

		override fun create(data: MangaPage, options: Options, imageLoader: ImageLoader) = MangaPageFetcher(
			okHttpClient = okHttpClient,
			pagesCache = pagesCache,
			options = options,
			page = data,
			mangaRepositoryFactory = mangaRepositoryFactory,
			imageProxyInterceptor = imageProxyInterceptor,
			imageLoader = imageLoader,
		)
	}
}
