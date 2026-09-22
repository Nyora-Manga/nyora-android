package com.nyora.hasan72341.core.network

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Decrypts MANGA Plus page images. Their bytes are XOR-encrypted with a per-page key, which reaches
 * this interceptor as a request header: [ENGINE_KEY_HEADER] from the bundled `mangaplus` engine,
 * [SHARED_KEY_HEADER] from the shared desktop/web service. Either way the header is stripped before
 * the request leaves (the CDN must never see it), the encrypted bytes are downloaded, and the XOR is
 * undone here instead of by a proxy.
 *
 * Every other request passes through untouched.
 */
class MangaPlusImageInterceptor : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val original = chain.request()
		val keyHeader = KEY_HEADERS.firstOrNull { !original.header(it).isNullOrEmpty() }
			?: return chain.proceed(original)
		val key = original.header(keyHeader).orEmpty()

		val response = chain.proceed(original.newBuilder().removeHeader(keyHeader).build())
		val body = response.body
		val decoded = body.bytes().decodeXorCipher(key)
		return response.newBuilder()
			.body(decoded.toResponseBody(body.contentType().toImageType()))
			.build()
	}

	/**
	 * Mirrors the shared decoder: the key is a run of hex pairs read as a byte stream, and
	 * `plain[i] = cipher[i] xor keyStream[i % keyStream.size]`. A key that is not hexadecimal leaves
	 * the bytes alone rather than throwing — an exception here would surface as a failed page load
	 * for every image of the chapter.
	 */
	private fun ByteArray.decodeXorCipher(key: String): ByteArray {
		val keyStream = key.chunked(2).mapNotNull { it.toIntOrNull(16) }
		if (keyStream.isEmpty()) return this
		return ByteArray(size) { i -> (this[i].toInt() xor keyStream[i % keyStream.size]).toByte() }
	}

	/** MANGA Plus serves its decrypted pages as opaque bytes; the reader needs an image type. */
	private fun MediaType?.toImageType(): MediaType = when {
		this == null -> DEFAULT_IMAGE_TYPE
		type == "application" && subtype == "octet-stream" -> DEFAULT_IMAGE_TYPE
		else -> this
	}

	companion object {

		/** Header the bundled data-driven `mangaplus` engine attaches the key to. */
		const val ENGINE_KEY_HEADER = "X-Nyora-Mangaplus-Key"

		/** Header the shared desktop/web service attaches the key to. */
		const val SHARED_KEY_HEADER = "X-Nyora-Image-Key"

		private val KEY_HEADERS = listOf(ENGINE_KEY_HEADER, SHARED_KEY_HEADER)

		private val DEFAULT_IMAGE_TYPE = "image/jpeg".toMediaType()
	}
}
