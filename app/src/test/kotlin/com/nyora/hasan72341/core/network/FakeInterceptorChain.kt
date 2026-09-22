package com.nyora.hasan72341.core.network

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.TimeUnit

/**
 * Minimal [Interceptor.Chain] for interceptor unit tests. It records every request handed to
 * [proceed] and answers it from [responder]; the connection and timeout half of the interface is
 * never touched by an application interceptor, so it stays unimplemented.
 */
internal class FakeInterceptorChain(
	private val initialRequest: Request,
	private val responder: (Request) -> Response,
) : Interceptor.Chain {

	/** Every request that actually went out, in order — the retry cases assert on the second one. */
	val proceeded = mutableListOf<Request>()

	override fun request(): Request = initialRequest

	override fun proceed(request: Request): Response {
		proceeded += request
		return responder(request)
	}

	override fun connection(): Connection? = null

	override fun call(): Call = throw UnsupportedOperationException()

	override fun connectTimeoutMillis(): Int = 0

	override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

	override fun readTimeoutMillis(): Int = 0

	override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

	override fun writeTimeoutMillis(): Int = 0

	override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
}

internal fun fakeResponse(
	request: Request,
	code: Int = 200,
	body: ByteArray = ByteArray(0),
	contentType: String? = null,
): Response = Response.Builder()
	.request(request)
	.protocol(Protocol.HTTP_1_1)
	.code(code)
	.message(if (code == 200) "OK" else "Error")
	.body(body.toResponseBody(contentType?.toMediaTypeOrNull()))
	.build()
