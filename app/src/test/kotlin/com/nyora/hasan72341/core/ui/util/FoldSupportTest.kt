package com.nyora.hasan72341.core.ui.util

import androidx.window.layout.FoldingFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

	@Test
	fun singlePageIsConfinedToTheHalfLeftOfACentredHinge() {
		// 1000 wide, hinge 480..520: both halves are 480, the left one wins the tie
		assertEquals(0 to 520, bookSinglePageInsets(containerWidth = 1000, hingeLeft = 480, hingeRight = 520))
	}

	@Test
	fun singlePageIsConfinedToTheLargerHalf() {
		// left half is 300, right half is 640 -- the page goes right of the hinge
		assertEquals(360 to 0, bookSinglePageInsets(containerWidth = 1000, hingeLeft = 300, hingeRight = 360))
		// mirrored: left half is 640, right half is 300
		assertEquals(0 to 360, bookSinglePageInsets(containerWidth = 1000, hingeLeft = 640, hingeRight = 700))
	}

	@Test
	fun singlePageInsetsNeverLeaveTheHingeCrossingTheContent() {
		val width = 1000
		for (hingeLeft in 1 until width - 1) {
			val hingeRight = minOf(hingeLeft + 40, width - 1)
			val (left, right) = bookSinglePageInsets(width, hingeLeft, hingeRight)
			val contentLeft = left
			val contentRight = width - right
			assertTrue(
				"hinge $hingeLeft..$hingeRight crosses content $contentLeft..$contentRight",
				contentRight <= hingeLeft || contentLeft >= hingeRight,
			)
			assertTrue("content collapsed at hinge $hingeLeft", contentRight > contentLeft)
		}
	}

	@Test
	fun guidelineOffsetIsTheHingeEdgeFacingTheSecondPane() {
		// LTR: the second pane is the right half, so the guideline sits on the hinge's right edge
		assertEquals(560, hingeGuidelineOffset(hingeLeft = 520, hingeRight = 560, isRtl = false))
		// RTL: the second pane is the left half; guide_end is measured from the left edge there,
		// so the offset is the hinge's left edge, not the window width minus it
		assertEquals(520, hingeGuidelineOffset(hingeLeft = 520, hingeRight = 560, isRtl = true))
	}
}
