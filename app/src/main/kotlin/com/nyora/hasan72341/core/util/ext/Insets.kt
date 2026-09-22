package com.nyora.hasan72341.core.util.ext

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type.InsetsType

/**
 * System bars plus the display cutout.
 *
 * Since the app targets API 35+ the window is always laid out under the cutout, so insetting by
 * [WindowInsetsCompat.Type.systemBars] alone leaves content beneath the notch or punch-hole of
 * devices that have one. It is most visible in landscape and on foldable cover screens, where the
 * cutout sits on a side edge and the status bar inset is zero.
 */
val contentInsetsType: Int
	get() = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

fun Insets.end(view: View): Int {
	return if (view.isRtl) left else right
}

fun Insets.start(view: View): Int {
	return if (view.isRtl) right else left
}

val WindowInsetsCompat.contentInsets: Insets
	get() = getInsets(contentInsetsType)

fun WindowInsetsCompat.consumeContentInsets(
	left: Boolean = false,
	top: Boolean = false,
	right: Boolean = false,
	bottom: Boolean = false,
): WindowInsetsCompat {
	val barsInsets = contentInsets
	val insets = Insets.of(
		if (left) 0 else barsInsets.left,
		if (top) 0 else barsInsets.top,
		if (right) 0 else barsInsets.right,
		if (bottom) 0 else barsInsets.bottom,
	)
	return WindowInsetsCompat.Builder(this)
		.setInsets(contentInsetsType, insets)
		.build()
}

fun WindowInsetsCompat.consume(
	v: View,
	@InsetsType typeMask: Int,
	start: Boolean = false,
	top: Boolean = false,
	end: Boolean = false,
	bottom: Boolean = false,
): WindowInsetsCompat {
	val insets = getInsets(typeMask)
	val newInsets = Insets.of(
		/* left = */ if (if (v.isRtl) end else start) 0 else insets.left,
		/* top = */ if (top) 0 else insets.top,
		/* right = */ if (if (v.isRtl) start else end) 0 else insets.right,
		/* bottom = */ if (bottom) 0 else insets.bottom,
	)
	return WindowInsetsCompat.Builder(this)
		.setInsets(typeMask, newInsets)
		.build()
}

fun WindowInsetsCompat.consumeAll(
	@InsetsType typeMask: Int,
): WindowInsetsCompat = WindowInsetsCompat.Builder(this)
	.setInsets(typeMask, Insets.NONE)
	.build()

fun WindowInsetsCompat.consumeContentInsets(
	view: View,
	start: Boolean = false,
	top: Boolean = false,
	end: Boolean = false,
	bottom: Boolean = false,
): WindowInsetsCompat = consume(view, contentInsetsType, start, top, end, bottom)

fun WindowInsetsCompat.consumeAllContentInsets() = consumeAll(contentInsetsType)

@Deprecated("")
fun Insets.consume(
	view: View,
	start: Boolean = false,
	top: Boolean = false,
	end: Boolean = false,
	bottom: Boolean = false,
): Insets = Insets.of(
	/* left = */ if (if (view.isRtl) end else start) 0 else this.left,
	/* top = */ if (top) 0 else this.top,
	/* right = */ if (if (view.isRtl) start else end) 0 else this.right,
	/* bottom = */ if (bottom) 0 else this.bottom,
)
