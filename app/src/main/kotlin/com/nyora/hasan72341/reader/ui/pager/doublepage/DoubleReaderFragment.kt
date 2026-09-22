package com.nyora.hasan72341.reader.ui.pager.doublepage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import com.nyora.hasan72341.R
import com.nyora.hasan72341.ai.MangaTranslator
import com.nyora.hasan72341.core.os.NetworkState
import com.nyora.hasan72341.core.prefs.AppSettings
import com.nyora.hasan72341.core.ui.list.lifecycle.RecyclerViewLifecycleDispatcher
import com.nyora.hasan72341.core.util.ext.firstVisibleItemPosition
import com.nyora.hasan72341.databinding.FragmentReaderDoubleBinding
import com.nyora.hasan72341.reader.domain.PageLoader
import com.nyora.hasan72341.reader.ui.ReaderState
import com.nyora.hasan72341.reader.ui.pager.BaseReaderAdapter
import com.nyora.hasan72341.reader.ui.pager.BaseReaderFragment
import com.nyora.hasan72341.reader.ui.pager.ReaderPage
import javax.inject.Inject
import kotlin.math.absoluteValue

import com.nyora.hasan72341.reader.ui.pager.standard.PageHolder
import com.nyora.hasan72341.reader.ui.pager.BasePageHolder

@AndroidEntryPoint
open class DoubleReaderFragment : BaseReaderFragment<FragmentReaderDoubleBinding>() {

	@Inject
	lateinit var networkState: NetworkState

	@Inject
	lateinit var pageLoader: PageLoader

	@Inject
	lateinit var translator: MangaTranslator

	@Inject
	lateinit var settings: AppSettings

	private var recyclerLifecycleDispatcher: RecyclerViewLifecycleDispatcher? = null

	private var spineOffset = 0

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentReaderDoubleBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(
		binding: FragmentReaderDoubleBinding,
		savedInstanceState: Bundle?,
	) {
		super.onViewBindingCreated(binding, savedInstanceState)
		with(binding.recyclerView) {
			adapter = readerAdapter
			recyclerLifecycleDispatcher = RecyclerViewLifecycleDispatcher().also {
				addOnScrollListener(it)
			}
			addOnScrollListener(PageScrollListener())
			addOnChildAttachStateChangeListener(SpineOffsetListener())
			DoublePageSnapHelper(settings).attachToRecyclerView(this)
		}
	}

	override fun onDestroyView() {
		recyclerLifecycleDispatcher = null
		requireViewBinding().recyclerView.adapter = null
		super.onDestroyView()
	}

	override suspend fun onPagesChanged(pages: List<ReaderPage>, pendingState: ReaderState?) = coroutineScope {
		val items = launch {
			requireAdapter().setItems(pages)
			yield()
			viewBinding?.recyclerView?.let { rv ->
				recyclerLifecycleDispatcher?.invalidate(rv)
			}
		}
		if (pendingState != null) {
			var position = pages.indexOfFirst {
				it.chapterId.toString() == pendingState.chapterId && it.index == pendingState.page
			}
			items.join()
			if (position != -1) {
				position = position.toPagePosition()
				requireViewBinding().recyclerView.firstVisibleItemPosition = position
				notifyPageChanged(position, position + 1)
			} else {
				Snackbar.make(requireView(), R.string.not_found_404, Snackbar.LENGTH_SHORT)
					.show()
			}
		} else {
			items.join()
		}
	}

	override fun onCreateAdapter() = DoublePagesAdapter(
		lifecycleOwner = viewLifecycleOwner,
		loader = pageLoader,
		readerSettingsProducer = viewModel.readerSettingsProducer,
		networkState = networkState,
		exceptionResolver = exceptionResolver,
		translator = translator,
	)

	override fun setSpineOffset(px: Int) {
		if (px == spineOffset) {
			return
		}
		spineOffset = px
		val recyclerView = viewBinding?.recyclerView ?: return
		for (i in 0 until recyclerView.childCount) {
			applySpineOffset(recyclerView, recyclerView.getChildAt(i))
		}
	}

	/**
	 * The two pages of a spread meet at the gutter, so each of them is padded away from it by half
	 * of the offset. The item width is left alone, keeping snapping and scrolling as they were.
	 */
	private fun applySpineOffset(recyclerView: RecyclerView, child: View) {
		val position = recyclerView.getChildAdapterPosition(child)
		if (position == RecyclerView.NO_POSITION) {
			return
		}
		val isFirstOfSpread = position and 1 == 0
		val padding = spineOffset / 2
		child.updatePaddingRelative(
			start = if (isFirstOfSpread) 0 else padding,
			end = if (isFirstOfSpread) padding else 0,
		)
	}

	override fun onZoomIn() {
		(viewBinding ?: return).recyclerView.visiblePageHolders()
			.filterIsInstance<com.nyora.hasan72341.core.ui.widgets.ZoomControl.ZoomControlListener>()
			.forEach { it.onZoomIn() }
	}

	override fun translateCurrentPage() {
		(viewBinding ?: return).recyclerView.visiblePageHolders()
			.forEach { it.translatePage() }
	}

	override fun translatePageAt(index: Int) {
		(viewBinding ?: return).recyclerView.findViewHolderForAdapterPosition(index.toPagePosition())?.let {
			(it as? BasePageHolder<*>)?.translatePage()
		}
	}

	override fun onZoomOut() {
		(viewBinding ?: return).recyclerView.visiblePageHolders()
			.filterIsInstance<com.nyora.hasan72341.core.ui.widgets.ZoomControl.ZoomControlListener>()
			.forEach { it.onZoomOut() }
	}

	override fun switchPageBy(delta: Int) {
		if (delta.absoluteValue > 1 || !isAnimationEnabled()) {
			switchPageTo(getCurrentItem() + delta + delta, false)
			return
		}
		val rv = viewBinding?.recyclerView ?: return
		val distance = rv.width * delta
		rv.smoothScrollBy(distance, 0, AccelerateDecelerateInterpolator())
	}

	override fun switchPageTo(position: Int, smooth: Boolean) {
		val lm = viewBinding?.recyclerView?.layoutManager as? LinearLayoutManager ?: return
		val targetPosition = position.toPagePosition()
		lm.scrollToPositionWithOffset(targetPosition, 0)
	}

	override fun getCurrentState(): ReaderState? = viewBinding?.run {
		val adapter = recyclerView.adapter as? BaseReaderAdapter<*>
		val page = adapter?.getItemOrNull(getCurrentItem()) ?: return@run null
		ReaderState(
			chapterId = page.chapterId.toString(),
			page = page.index,
			scroll = 0,
		)
	}

	protected open fun notifyPageChanged(lowerPos: Int, upperPos: Int) {
		viewModel.onCurrentPageChanged(lowerPos, upperPos)
	}

	private fun getCurrentItem() = (requireViewBinding().recyclerView.layoutManager as LinearLayoutManager)
		.findFirstCompletelyVisibleItemPosition().toPagePosition()

	private fun Int.toPagePosition() = this and 1.inv()

	private inner class SpineOffsetListener : RecyclerView.OnChildAttachStateChangeListener {

		override fun onChildViewAttachedToWindow(view: View) {
			val recyclerView = viewBinding?.recyclerView ?: return
			applySpineOffset(recyclerView, view)
		}

		override fun onChildViewDetachedFromWindow(view: View) = Unit
	}

	private inner class PageScrollListener : RecyclerView.OnScrollListener() {

		private var firstPos = RecyclerView.NO_POSITION
		private var lastPos = RecyclerView.NO_POSITION

		override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
			super.onScrolled(recyclerView, dx, dy)
			val lm = recyclerView.layoutManager as? LinearLayoutManager
			if (lm == null) {
				firstPos = RecyclerView.NO_POSITION
				lastPos = RecyclerView.NO_POSITION
				return
			}
			val newFirstPos = lm.findFirstVisibleItemPosition()
			val newLastPos = lm.findLastVisibleItemPosition()
			if (newFirstPos != firstPos || newLastPos != lastPos) {
				firstPos = newFirstPos
				lastPos = newLastPos
				notifyPageChanged(newFirstPos, newLastPos)
			}
		}
	}
}
