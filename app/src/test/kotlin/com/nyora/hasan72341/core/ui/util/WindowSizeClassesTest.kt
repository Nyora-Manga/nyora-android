package com.nyora.hasan72341.core.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowSizeClassesTest {

	@Test
	fun compactWidthBelowSixHundredDp() {
		val classes = WindowSizeClasses.fromDp(599f, 800f)
		assertEquals(WindowWidthClass.COMPACT, classes.width)
		assertEquals(WindowHeightClass.MEDIUM, classes.height)
		assertFalse(classes.isAtLeastMediumWidth)
	}

	@Test
	fun mediumWidthFromSixHundredDp() {
		val classes = WindowSizeClasses.fromDp(600f, 479f)
		assertEquals(WindowWidthClass.MEDIUM, classes.width)
		assertEquals(WindowHeightClass.COMPACT, classes.height)
		assertTrue(classes.isAtLeastMediumWidth)
	}

	@Test
	fun expandedWidthAndHeight() {
		val classes = WindowSizeClasses.fromDp(1280f, 900f)
		assertEquals(WindowWidthClass.EXPANDED, classes.width)
		assertEquals(WindowHeightClass.EXPANDED, classes.height)
		assertTrue(classes.isAtLeastMediumWidth)
	}

	@Test
	fun classBoundariesAreInclusiveLower() {
		assertEquals(WindowWidthClass.MEDIUM, WindowSizeClasses.fromDp(839f, 0f).width)
		assertEquals(WindowWidthClass.EXPANDED, WindowSizeClasses.fromDp(840f, 0f).width)
		assertEquals(WindowHeightClass.MEDIUM, WindowSizeClasses.fromDp(0f, 480f).height)
		assertEquals(WindowHeightClass.EXPANDED, WindowSizeClasses.fromDp(0f, 900f).height)
	}
}
