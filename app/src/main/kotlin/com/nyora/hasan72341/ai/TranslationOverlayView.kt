package com.nyora.hasan72341.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

class TranslationOverlayView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
	private companion object {
		const val MIN_TEXT_SIZE = 10f
		const val MAX_TEXT_SIZE = 42f
		const val WIDTH_FILL_TARGET_RATIO = 0.96f
	}

	private var blocks: List<TranslatedBlock> = emptyList()
	private var ssiv: SubsamplingScaleImageView? = null

	private val bgPaint = Paint().apply {
		style = Paint.Style.FILL
		isAntiAlias = true
	}

	private val textPaint = TextPaint().apply {
		isAntiAlias = true
		typeface = android.graphics.Typeface.create("casual", android.graphics.Typeface.NORMAL)
		letterSpacing = 0.0f
	}

	private data class CachedDrawData(
		val layout: StaticLayout?,
		val expandedBgRectSource: RectF,
		val textColor: Int,
		val shadowColor: Int,
		val cornerRadius: Float,
		val blurRadius: Float,
		val textSize: Float
	)

	// Cache to completely eliminate layout recalculations during zoom/pan (which runs at 60fps)
	private val drawCache = mutableMapOf<String, CachedDrawData>()

	init {
		setLayerType(LAYER_TYPE_SOFTWARE, null)
	}

	fun setup(ssiv: SubsamplingScaleImageView) {
		this.ssiv = ssiv
	}

	fun setBlocks(newBlocks: List<TranslatedBlock>) {
		this.blocks = newBlocks
		rebuildCache()
		invalidate()
	}

	private fun rebuildCache() {
		drawCache.clear()
		for (block in blocks) {
			val cacheKey = "${block.id}_${block.state}_${block.translatedText.hashCode()}"
			
			val sourceRect = RectF(block.boundingBox)
			val padding = 18f
			val minWidth = 100f
			val targetWidth = (sourceRect.width() - (padding * 2)).coerceAtLeast(minWidth)
			val targetHeight = (sourceRect.height() - (padding * 2)).coerceAtLeast(30f)
			
			// 1. Typesetting Logic (Done once per block state, NOT every frame)
			val bestLayout = if (block.state != TranslationState.TRANSLATING && block.translatedText.isNotEmpty()) {
				findBestFitLayout(
					text = block.translatedText,
					targetWidth = targetWidth.toInt(),
					targetHeight = targetHeight,
				)
			} else {
				null
			}

			val textWidth = bestLayout?.width?.toFloat() ?: 0f
			val textHeight = bestLayout?.height?.toFloat() ?: 0f

			// 2. Expand background rect to fit text
			val bgRect = RectF(sourceRect)
			if (bestLayout != null) {
				if (textWidth + padding * 2 > bgRect.width()) {
					val diff = (textWidth + padding * 2) - bgRect.width()
					bgRect.inset(-diff / 2f, 0f)
				}
				if (textHeight + padding * 2 > bgRect.height()) {
					val diff = (textHeight + padding * 2) - bgRect.height()
					bgRect.inset(0f, -diff / 2f)
				}
			}
			bgRect.inset(-4f, -4f) // Blur bleed room

			// 3. Colors
			val r = Color.red(block.backgroundColor)
			val g = Color.green(block.backgroundColor)
			val b = Color.blue(block.backgroundColor)
			val bgBrightness = r * 0.299 + g * 0.587 + b * 0.114
			val isDarkBg = bgBrightness < 128
			
			val textColor = if (isDarkBg) {
				Color.WHITE 
			} else {
				when (block.state) {
					TranslationState.MT -> Color.parseColor("#333333")
					TranslationState.DOWNLOADING_MODELS -> Color.parseColor("#1976D2")
					else -> Color.BLACK
				}
			}
			val shadowColor = if (isDarkBg) Color.BLACK else Color.WHITE

			drawCache[cacheKey] = CachedDrawData(
				layout = bestLayout,
				expandedBgRectSource = bgRect,
				textColor = textColor,
				shadowColor = shadowColor,
				cornerRadius = (bgRect.height() * 0.35f).coerceIn(12f, 40f),
				blurRadius = 6f,
				textSize = textPaint.textSize
			)
		}
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val ssiv = ssiv ?: return
		if (blocks.isEmpty() || drawCache.isEmpty()) return

		val scale = ssiv.scale
		val anchorPoint = ssiv.sourceToViewCoord(0f, 0f) ?: return

		canvas.save()
		
		// Translate and scale the canvas once for the entire drawing pass
		canvas.translate(anchorPoint.x, anchorPoint.y)
		canvas.scale(scale, scale)

		bgPaint.maskFilter = android.graphics.BlurMaskFilter(6f, android.graphics.BlurMaskFilter.Blur.NORMAL)

		// Pass 1: Draw all speech bubble backgrounds first
		blocks.forEach { block ->
			val cacheKey = "${block.id}_${block.state}_${block.translatedText.hashCode()}"
			val cached = drawCache[cacheKey] ?: return@forEach
			bgPaint.color = block.backgroundColor
			canvas.drawRoundRect(cached.expandedBgRectSource, cached.cornerRadius, cached.cornerRadius, bgPaint)
		}

		// Pass 2: Draw all text layers on top of all backgrounds
		blocks.forEach { block ->
			val cacheKey = "${block.id}_${block.state}_${block.translatedText.hashCode()}"
			val cached = drawCache[cacheKey] ?: return@forEach

			cached.layout?.let { layout ->
				textPaint.color = cached.textColor
				textPaint.textSize = cached.textSize
				textPaint.setShadowLayer(4f, 0f, 0f, cached.shadowColor)

				val horizontalOffset = (cached.expandedBgRectSource.width() - layout.width) / 2f
				val verticalOffset = (cached.expandedBgRectSource.height() - layout.height) / 2f
				
				canvas.save()
				canvas.translate(cached.expandedBgRectSource.left + horizontalOffset, cached.expandedBgRectSource.top + verticalOffset)
				layout.draw(canvas)
				canvas.restore()
				
				textPaint.clearShadowLayer()
			}
		}
		
		canvas.restore()
	}

	private fun findBestFitLayout(
		text: String,
		targetWidth: Int,
		targetHeight: Float,
	): StaticLayout {
		val baseSize = estimateOcrTextSize(targetHeight)
		val words = text.split(Regex("\\s+"))

		var adjustedTargetWidth = targetWidth
		var bestSize = baseSize
		var found = false

		// 1. Try to find the largest size (capped at baseSize down to 8f) where BOTH width and height fit
		val startSize = baseSize.coerceAtMost(MAX_TEXT_SIZE).toInt()
		for (size in startSize downTo 8) {
			textPaint.textSize = size.toFloat()
			val longestWordWidth = words.maxOfOrNull { textPaint.measureText(it) } ?: 0f
			if (longestWordWidth <= adjustedTargetWidth * WIDTH_FILL_TARGET_RATIO) {
				val testLayout = createLayout(text, adjustedTargetWidth)
				if (testLayout.height <= targetHeight) {
					bestSize = size.toFloat()
					found = true
					break
				}
			}
		}

		// 2. If even at size 8f it doesn't fit, expand layout width slightly
		if (!found) {
			bestSize = 8f
			textPaint.textSize = 8f
			val longestWordWidth = words.maxOfOrNull { textPaint.measureText(it) } ?: 0f
			val requiredWidth = (longestWordWidth / WIDTH_FILL_TARGET_RATIO).toInt()
			if (requiredWidth > adjustedTargetWidth) {
				adjustedTargetWidth = requiredWidth
			}
		}

		textPaint.textSize = bestSize
		return createLayout(text, adjustedTargetWidth)
	}

	private fun estimateOcrTextSize(targetHeight: Float): Float {
		return (targetHeight * 0.42f).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
	}

	private fun createLayout(text: String, width: Int): StaticLayout {
		return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
			.setAlignment(Layout.Alignment.ALIGN_CENTER)
			.setLineSpacing(0f, 1.25f)
			// LineBreaker.BREAK_STRATEGY_SIMPLE is a compile-time int constant equal to the
			// pre-API-29 Layout.BREAK_STRATEGY_SIMPLE it replaces, so it is inlined by the
			// compiler and safe to reference unconditionally down to minSdk; StaticLayout.Builder
			// itself has existed since API 23. Referencing Layout's own constant instead trips
			// lint's WrongConstant check because setBreakStrategy is annotated against LineBreaker.
			.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE)
			.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
			.build()
	}

	fun update() {
		invalidate()
	}
}
