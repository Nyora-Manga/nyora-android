package com.nyora.hasan72341.core.ui.util

import androidx.window.layout.FoldingFeature
import org.junit.Assert.assertEquals
import org.junit.Test

class FoldSupportTest {

	@Test
	fun horizontalHalfOpenedFoldIsTabletop() {
		assertEquals(
			Posture.TABLETOP,
			postureOf(
				orientation = FoldingFeature.Orientation.HORIZONTAL,
				state = FoldingFeature.State.HALF_OPENED,
				isSeparating = true,
			),
		)
	}

	@Test
	fun verticalHalfOpenedFoldIsBook() {
		assertEquals(
			Posture.BOOK,
			postureOf(
				orientation = FoldingFeature.Orientation.VERTICAL,
				state = FoldingFeature.State.HALF_OPENED,
				isSeparating = true,
			),
		)
	}

	@Test
	fun flatFoldHasNoPosture() {
		assertEquals(
			Posture.NONE,
			postureOf(
				orientation = FoldingFeature.Orientation.HORIZONTAL,
				state = FoldingFeature.State.FLAT,
				isSeparating = true,
			),
		)
		assertEquals(
			Posture.NONE,
			postureOf(
				orientation = FoldingFeature.Orientation.VERTICAL,
				state = FoldingFeature.State.FLAT,
				isSeparating = true,
			),
		)
	}

	@Test
	fun foldThatDoesNotSplitTheWindowHasNoPosture() {
		assertEquals(
			Posture.NONE,
			postureOf(
				orientation = FoldingFeature.Orientation.VERTICAL,
				state = FoldingFeature.State.HALF_OPENED,
				isSeparating = false,
			),
		)
	}

	@Test
	fun absentFoldHasNoPosture() {
		assertEquals(
			Posture.NONE,
			postureOf(orientation = null, state = null, isSeparating = false),
		)
	}
}
