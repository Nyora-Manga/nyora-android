package com.nyora.hasan72341.core.ui.util

import android.graphics.Rect
import android.view.View
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Device postures the layout reacts to. Only a hinge that actually splits the window into two
 * physical areas gets a posture, so layout code can assume there is something worth avoiding.
 */
enum class Posture {
	NONE,
	TABLETOP,
	BOOK,
}

/**
 * Maps the attributes of a folding feature onto a [Posture].
 * Kept apart from [FoldSupport] so that it can be tested without a window.
 */
fun postureOf(
	orientation: FoldingFeature.Orientation?,
	state: FoldingFeature.State?,
	isSeparating: Boolean,
): Posture = when {
	!isSeparating || state != FoldingFeature.State.HALF_OPENED -> Posture.NONE
	orientation == FoldingFeature.Orientation.HORIZONTAL -> Posture.TABLETOP
	orientation == FoldingFeature.Orientation.VERTICAL -> Posture.BOOK
	else -> Posture.NONE
}

/**
 * Current folding feature of the activity's window, in window coordinates.
 */
class FoldSupport(private val activity: ComponentActivity) {

	private val foldFlow = MutableStateFlow<FoldingFeature?>(null)

	val fold: StateFlow<FoldingFeature?> = foldFlow.asStateFlow()

	val posture: Posture
		get() {
			val fold = foldFlow.value ?: return Posture.NONE
			return postureOf(fold.orientation, fold.state, fold.isSeparating)
		}

	val isTabletop: Boolean
		get() = posture == Posture.TABLETOP

	val isBook: Boolean
		get() = posture == Posture.BOOK

	fun start() {
		activity.lifecycleScope.launch {
			activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
				WindowInfoTracker.getOrCreate(activity)
					.windowLayoutInfo(activity)
					.collect { info ->
						foldFlow.value = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
					}
			}
		}
	}

	/**
	 * [FoldingFeature.bounds] translated from window coordinates into [view]'s own coordinates,
	 * or `null` when there is no fold or the fold does not split the window.
	 */
	fun hingeBoundsIn(view: View): Rect? {
		val fold = foldFlow.value ?: return null
		if (!fold.isSeparating) {
			return null
		}
		val location = IntArray(2)
		view.getLocationInWindow(location)
		val bounds = fold.bounds
		return Rect(
			bounds.left - location[0],
			bounds.top - location[1],
			bounds.right - location[0],
			bounds.bottom - location[1],
		)
	}
}
