package com.nyora.hasan72341.core.network

import dagger.Lazy
import com.nyora.hasan72341.core.model.MangaSource
import com.nyora.hasan72341.core.parser.DomainAwareRepository
import com.nyora.hasan72341.core.parser.MangaLoaderContextImpl
import com.nyora.hasan72341.core.parser.MangaRepository
import com.nyora.hasan72341.core.parser.ParserMangaRepository
import com.nyora.hasan72341.core.util.ext.printStackTraceDebug
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Interceptor.Chain
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import com.nyora.hasan72341.mihon.parsers.model.MangaParserSource
import com.nyora.hasan72341.mihon.parsers.model.MangaSource
import com.nyora.hasan72341.mihon.parsers.util.mergeWith
import com.nyora.hasan72341.mihon.parsers.util.runCatchingCancellable
import java.net.IDN
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommonHeadersInterceptor @Inject constructor(
	private val mangaRepositoryFactoryLazy: Lazy<MangaRepository.Factory>,
	private val mangaLoaderContextLazy: Lazy<MangaLoaderContextImpl>,
) : Interceptor {

	override fun intercept(chain: Chain): Response {
		val request = chain.request()
		val source = request.tag(MangaSource::class.java)
			?: request.headers[CommonHeaders.MANGA_SOURCE]?.let { MangaSource(it) }
		val repository = source?.let {
			runCatchingCancellable { mangaRepositoryFactoryLazy.get().create(it) }.getOrNull()
		}
		val parserRepository = repository as? ParserMangaRepository
		val headersBuilder = request.headers.newBuilder()
			.removeAll(CommonHeaders.MANGA_SOURCE)
		parserRepository?.getRequestHeaders()?.let {
			headersBuilder.mergeWith(it, replaceExisting = false)
		}
		if (headersBuilder[CommonHeaders.USER_AGENT] == null) {
			headersBuilder[CommonHeaders.USER_AGENT] = mangaLoaderContextLazy.get().getDefaultUserAgent()
		}
		if (headersBuilder[CommonHeaders.REFERER] == null) {
			// Derive a Referer from the source domain — native parser sources expose it directly,
			// every other kind through DomainAwareRepository. Manganato et al. gate images on it.
			val domain = (parserRepository?.domain ?: (repository as? DomainAwareRepository)?.domain)
				?.takeUnless { it.isBlank() }
			if (domain != null) {
				headersBuilder.trySet(CommonHeaders.REFERER, "https://${IDN.toASCII(domain)}/")
			}
		}
		val newRequest = request.newBuilder().headers(headersBuilder.build()).build()
		return parserRepository?.interceptSafe(ProxyChain(chain, newRequest)) ?: chain.proceed(newRequest)
	}

	private fun Headers.Builder.trySet(name: String, value: String) = try {
		set(name, value)
	} catch (e: IllegalArgumentException) {
		e.printStackTraceDebug("CommonHeadersInterceptor::trySet")
	}

	private fun Interceptor.interceptSafe(chain: Chain): Response = runCatchingCancellable {
		intercept(chain)
	}.getOrElse { e ->
		if (e is IOException || e is Error) {
			throw e
		} else {
			// only IOException can be safely thrown from an Interceptor
			throw IOException("Error in interceptor: ${e.message}", e)
		}
	}

	private class ProxyChain(
		private val delegate: Chain,
		private val request: Request,
	) : Chain by delegate {

		override fun request(): Request = request
	}
}
