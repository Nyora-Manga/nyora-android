package com.nyora.hasan72341.reader.ui

import android.content.res.Configuration
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import com.nyora.hasan72341.core.prefs.AppSettings
import com.nyora.hasan72341.core.prefs.ReaderMode
import com.nyora.hasan72341.core.util.ext.findKeyByValue
import com.nyora.hasan72341.reader.ui.pager.BaseReaderFragment
import com.nyora.hasan72341.reader.ui.pager.doublepage.DoubleReaderFragment
import com.nyora.hasan72341.reader.ui.pager.doublereversed.ReversedDoubleReaderFragment
import com.nyora.hasan72341.reader.ui.pager.reversed.ReversedReaderFragment
import com.nyora.hasan72341.reader.ui.pager.standard.PagerReaderFragment
import com.nyora.hasan72341.reader.ui.pager.vertical.VerticalReaderFragment
import com.nyora.hasan72341.reader.ui.pager.webtoon.WebtoonReaderFragment
import java.util.EnumMap

class ReaderManager(
	private val fragmentManager: FragmentManager,
	private val container: FragmentContainerView,
	settings: AppSettings,
) {

	private val modeMap = EnumMap<ReaderMode, Class<out BaseReaderFragment<*>>>(ReaderMode::class.java)

	private var spineOffset = 0

	var isDoublePageMode: Boolean = false
		private set

	init {
		val useDoublePages = isLandscape() && settings.isReaderDoubleOnLandscape
		invalidateTypesMap(useDoublePages)
	}

	val currentReader: BaseReaderFragment<*>?
		get() = fragmentManager.findFragmentById(container.id) as? BaseReaderFragment<*>

	val currentMode: ReaderMode?
		get() {
			val readerClass = currentReader?.javaClass ?: return null
			return modeMap.findKeyByValue(readerClass)
		}

	fun replace(newMode: ReaderMode) {
		val readerClass = requireNotNull(modeMap[newMode])
		fragmentManager.commit {
			setReorderingAllowed(true)
			replace(container.id, readerClass, null, null)
			runOnCommit { currentReader?.setSpineOffset(spineOffset) }
		}
	}

	/**
	 * Width to keep clear at the gutter of a spread, remembered so that a reader created later
	 * gets it too.
	 */
	fun setSpineOffset(px: Int) {
		spineOffset = px
		currentReader?.setSpineOffset(px)
	}

	fun setDoubleReaderMode(isEnabled: Boolean) {
		val mode = currentMode
		val prevReader = currentReader?.javaClass
		invalidateTypesMap(isEnabled)
		val newReader = modeMap[mode]
		if (mode != null && newReader != prevReader) {
			replace(mode)
		}
	}

	private fun invalidateTypesMap(useDoublePages: Boolean) {
		isDoublePageMode = useDoublePages
		modeMap[ReaderMode.STANDARD] = if (useDoublePages) {
			DoubleReaderFragment::class.java
		} else {
			PagerReaderFragment::class.java
		}
		modeMap[ReaderMode.REVERSED] = if (useDoublePages) {
			ReversedDoubleReaderFragment::class.java
		} else {
			ReversedReaderFragment::class.java
		}
		modeMap[ReaderMode.WEBTOON] = WebtoonReaderFragment::class.java
		modeMap[ReaderMode.VERTICAL] = VerticalReaderFragment::class.java
	}

	private fun isLandscape() = container.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

	fun translateCurrentPage() {
		currentReader?.translateCurrentPage()
	}

	fun translatePageAt(index: Int) {
		currentReader?.translatePageAt(index)
	}
}
