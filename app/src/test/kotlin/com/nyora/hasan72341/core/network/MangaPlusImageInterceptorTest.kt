package com.nyora.hasan72341.core.network

import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * MANGA Plus serves its page images XOR-encrypted with a per-page key. Getting the key stream or
 * the header handling wrong does not fail loudly — it hands the reader scrambled bytes, or leaks a
 * private header to the CDN — so both halves are pinned here.
 */
class MangaPlusImageInterceptorTest {

	private val interceptor = MangaPlusImageInterceptor()

	@Test
	fun decodesTheBodyWithTheRepeatingHexKeyStream() {
		val cipher = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
		val chain = chainFor(MangaPlusImageInterceptor.ENGINE_KEY_HEADER to "0f10", cipher)

		val decoded = interceptor.intercept(chain).body.bytes()

		// key stream = [0x0f, 0x10], applied cyclically.
		assertArrayEquals(byteArrayOf(0x0e, 0x12, 0x0c, 0x14, 0x0a), decoded)
	}

	@Test
	fun stripsTheKeyHeaderBeforeTheRequestLeaves() {
		val chain = chainFor(MangaPlusImageInterceptor.ENGINE_KEY_HEADER to "0f10", byteArrayOf(0x01))

		interceptor.intercept(chain)

		assertNull(chain.proceeded.single().header(MangaPlusImageInterceptor.ENGINE_KEY_HEADER))
	}

	@Test
	fun acceptsTheSharedClientsKeyHeaderToo() {
		val chain = chainFor(MangaPlusImageInterceptor.SHARED_KEY_HEADER to "ff", byteArrayOf(0x0f))

		val decoded = interceptor.intercept(chain).body.bytes()

		assertNull(chain.proceeded.single().header(MangaPlusImageInterceptor.SHARED_KEY_HEADER))
		assertArrayEquals(byteArrayOf(0xf0.toByte()), decoded)
	}

	@Test
	fun labelsAnOpaqueBodyAsJpegAndKeepsAKnownImageType() {
		val opaque = chainFor(
			MangaPlusImageInterceptor.ENGINE_KEY_HEADER to "00",
			byteArrayOf(0x01),
			contentType = "application/octet-stream",
		)
		assertEquals("image/jpeg", interceptor.intercept(opaque).body.contentType().toString())

		val png = chainFor(
			MangaPlusImageInterceptor.ENGINE_KEY_HEADER to "00",
			byteArrayOf(0x01),
			contentType = "image/png",
		)
		assertEquals("image/png", interceptor.intercept(png).body.contentType().toString())
	}

	@Test
	fun passesEveryOtherRequestThroughUntouched() {
		val request = Request.Builder().url("https://cdn.example/page.jpg").build()
		val upstream = fakeResponse(request, body = byteArrayOf(0x01, 0x02))
		val chain = FakeInterceptorChain(request) { upstream }

		assertSame(upstream, interceptor.intercept(chain))
	}

	@Test
	fun leavesTheBodyAloneWhenTheKeyIsNotHexadecimal() {
		val chain = chainFor(MangaPlusImageInterceptor.ENGINE_KEY_HEADER to "zz", byteArrayOf(0x01, 0x02))

		assertArrayEquals(byteArrayOf(0x01, 0x02), interceptor.intercept(chain).body.bytes())
	}

	private fun chainFor(
		keyHeader: Pair<String, String>,
		body: ByteArray,
		contentType: String? = null,
	): FakeInterceptorChain {
		val request = Request.Builder()
			.url("https://jumpg-assets.tokyo-cdn.com/page.jpg")
			.header(keyHeader.first, keyHeader.second)
			.build()
		return FakeInterceptorChain(request) { sent -> fakeResponse(sent, body = body, contentType = contentType) }
	}
}
