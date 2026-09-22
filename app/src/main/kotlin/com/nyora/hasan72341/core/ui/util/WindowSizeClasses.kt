package com.nyora.hasan72341.core.ui.util

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.fragment.app.Fragment
import androidx.window.layout.WindowMetricsCalculator
import com.nyora.hasan72341.core.util.ext.findActivity

enum class WindowWidthClass {
	COMPACT,
	MEDIUM,
	EXPANDED,
}

enum class WindowHeightClass {
	COMPACT,
	MEDIUM,
	EXPANDED,
}

/**
 * Size buckets of the current app window, as opposed to the device.
 * Use these where a decision depends on how much room the window actually has right now;
 * `R.bool.is_tablet` remains the right tool for picking resources.
 */
data class WindowSizeClasses(
	val width: WindowWidthClass,
	val height: WindowHeightClass,
) {

	val isAtLeastMediumWidth: Boolean
		get() = width != WindowWidthClass.COMPACT

	companion object {

		private const val MEDIUM_WIDTH_DP = 600f
		private const val EXPANDED_WIDTH_DP = 840f
		private const val MEDIUM_HEIGHT_DP = 480f
		private const val EXPANDED_HEIGHT_DP = 900f

		fun of(activity: Activity): WindowSizeClasses {
			val bounds = WindowMetricsCalculator.getOrCreate()
				.computeCurrentWindowMetrics(activity)
				.bounds
			val density = activity.resources.displayMetrics.density
			return fromDp(bounds.width() / density, bounds.height() / density)
		}

		/**
		 * Falls back to the configuration when [context] is not backed by an activity,
		 * e.g. inside the layout editor.
		 */
		fun of(context: Context): WindowSizeClasses {
			context.findActivity()?.let { return of(it) }
			val configuration = context.resources.configuration
			return fromDp(
				widthDp = configuration.screenWidthDp.toFloat(),
				heightDp = configuration.screenHeightDp.toFloat(),
			)
		}

		fun fromDp(widthDp: Float, heightDp: Float) = WindowSizeClasses(
			width = when {
				widthDp < MEDIUM_WIDTH_DP -> WindowWidthClass.COMPACT
				widthDp < EXPANDED_WIDTH_DP -> WindowWidthClass.MEDIUM
				else -> WindowWidthClass.EXPANDED
			},
			height = when {
				heightDp < MEDIUM_HEIGHT_DP -> WindowHeightClass.COMPACT
				heightDp < EXPANDED_HEIGHT_DP -> WindowHeightClass.MEDIUM
				else -> WindowHeightClass.EXPANDED
			},
		)
	}
}

val Activity.windowSizeClasses: WindowSizeClasses
	get() = WindowSizeClasses.of(this)

val Fragment.windowSizeClasses: WindowSizeClasses
	get() = WindowSizeClasses.of(requireActivity())

val View.windowSizeClasses: WindowSizeClasses
	get() = WindowSizeClasses.of(context)
