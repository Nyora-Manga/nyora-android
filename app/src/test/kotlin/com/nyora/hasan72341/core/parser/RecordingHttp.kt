package com.nyora.hasan72341.core.parser

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * An [OkHttpClient] that answers every call from [responder] without touching the network, and
 * keeps the requests it was asked for. The repositories under test drive their own HTTP, so this is
 * the only seam that can pin what they actually ask the site for.
 */
internal class RecordingHttp(private val responder: (Request) -> Response) {

	/** Every request the repository issued, in order. */
	val requests = mutableListOf<Request>()

	val client: OkHttpClient = OkHttpClient.Builder()
		.addInterceptor { chain ->
			val request = chain.request()
			requests += request
			responder(request)
		}
		.build()

	fun countPath(suffix: String): Int = requests.count { it.url.encodedPath.endsWith(suffix) }
}
