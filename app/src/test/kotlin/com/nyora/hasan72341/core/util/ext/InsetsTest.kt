package com.nyora.hasan72341.core.util.ext

import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Below API 30 `WindowInsetsCompat.Builder` cannot override the display cutout, so a mask that
 * includes it cannot be consumed there: every screen that pads itself by the mask and consumes it
 * would hand its children the cutout a second time. The mask has to leave the cutout out on those
 * APIs and take it from API 30, where the builder covers it.
 */
class InsetsTest {

	@Test
	fun theCutoutJoinsTheMaskFromApi30() {
		val systemBars = WindowInsetsCompat.Type.systemBars()
		val withCutout = systemBars or WindowInsetsCompat.Type.displayCutout()

		assertEquals(systemBars, contentInsetsTypeFor(23))
		assertEquals(systemBars, contentInsetsTypeFor(28))
		assertEquals(systemBars, contentInsetsTypeFor(29))
		assertEquals(withCutout, contentInsetsTypeFor(30))
		assertEquals(withCutout, contentInsetsTypeFor(35))
		assertEquals(withCutout, contentInsetsTypeFor(36))
	}

	@Test
	fun theMaskBelow30NeverCarriesTheCutoutBit() {
		val cutout = WindowInsetsCompat.Type.displayCutout()
		(23 until 30).forEach { sdk ->
			assertEquals("API $sdk", 0, contentInsetsTypeFor(sdk) and cutout)
		}
	}
}
