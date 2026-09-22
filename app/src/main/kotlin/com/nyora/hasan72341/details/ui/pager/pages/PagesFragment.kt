package com.nyora.hasan72341.details.ui.pager.pages

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ActionMode
import androidx.collection.ArraySet
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import com.nyora.hasan72341.R
import com.nyora.hasan72341.core.exceptions.resolve.SnackbarErrorObserver
import com.nyora.hasan72341.core.nav.ReaderIntent
import com.nyora.hasan72341.core.nav.dismissParentDialog
import com.nyora.hasan72341.core.nav.router
import com.nyora.hasan72341.core.prefs.AppSettings
import com.nyora.hasan72341.core.ui.BaseFragment
import com.nyora.hasan72341.core.ui.list.BoundsScrollListener
import com.nyora.hasan72341.core.ui.list.ListSelectionController
import com.nyora.hasan72341.core.ui.list.OnListItemClickListener
import com.nyora.hasan72341.core.ui.util.PagerNestedScrollHelper
import com.nyora.hasan72341.core.ui.util.RecyclerViewOwner
import com.nyora.hasan72341.core.util.RecyclerViewScrollCallback
import com.nyora.hasan72341.core.util.ext.consumeAll
import com.nyora.hasan72341.core.util.ext.contentInsetsType
import com.nyora.hasan72341.core.util.ext.findAppCompatDelegate
import com.nyora.hasan72341.core.util.ext.findParentCallback
import com.nyora.hasan72341.core.util.ext.observe
import com.nyora.hasan72341.core.util.ext.observeEvent
import com.nyora.hasan72341.core.util.ext.setTextAndVisible
import com.nyora.hasan72341.core.util.ext.showOrHide
import com.nyora.hasan72341.databinding.FragmentPagesBinding
import com.nyora.hasan72341.details.ui.pager.ChaptersPagesViewModel
import com.nyora.hasan72341.details.ui.pager.EmptyMangaReason
import com.nyora.hasan72341.list.ui.GridSpanResolver
import com.nyora.hasan72341.list.ui.adapter.ListItemType
import com.nyora.hasan72341.list.ui.adapter.TypedListSpacingDecoration
import com.nyora.hasan72341.list.ui.model.ListModel
import com.nyora.hasan72341.reader.ui.PageSaveHelper
import com.nyora.hasan72341.reader.ui.ReaderNavigationCallback
import com.nyora.hasan72341.reader.ui.ReaderState
import com.nyora.hasan72341.reader.ui.pager.ReaderPage
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class PagesFragment :
	BaseFragment<FragmentPagesBinding>(),
	OnListItemClickListener<PageThumbnail>,
	RecyclerViewOwner,
	ListSelectionController.Callback {

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var pageSaveHelperFactory: PageSaveHelper.Factory

	private val parentViewModel by ChaptersPagesViewModel.ActivityVMLazy(this)
	private val viewModel by viewModels<PagesViewModel>()
	private lateinit var pageSaveHelper: PageSaveHelper

	private var thumbnailsAdapter: PageThumbnailAdapter? = null
	private var spanResolver: GridSpanResolver? = null
	private var scrollListener: ScrollListener? = null
	private var selectionController: ListSelectionController? = null

	private val spanSizeLookup = SpanSizeLookup()

	override val recyclerView: RecyclerView?
		get() = viewBinding?.recyclerView

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		pageSaveHelper = pageSaveHelperFactory.create(this)
		combine(
			parentViewModel.mangaDetails,
			parentViewModel.readingState,
			parentViewModel.selectedBranch,
		) { details, readingState, branch ->
			if (details != null && (details.isLoaded || details.chapters.isNotEmpty())) {
				PagesViewModel.State(details.filterChapters(branch), readingState, branch)
			} else {
				null
			}
		}.flowOn(Dispatchers.IO)
			.observe(this, viewModel::updateState)
	}

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentPagesBinding {
		return FragmentPagesBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: FragmentPagesBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		spanResolver = GridSpanResolver(binding.root.resources)
		selectionController = ListSelectionController(
			appCompatDelegate = checkNotNull(findAppCompatDelegate()),
			decoration = PagesSelectionDecoration(binding.root.context),
			registryOwner = this,
			callback = this,
		)
		thumbnailsAdapter = PageThumbnailAdapter(
			clickListener = this@PagesFragment,
		)
		viewModel.gridScale.observe(viewLifecycleOwner, ::onGridScaleChanged) // before rv initialization
		with(binding.recyclerView) {
			addItemDecoration(TypedListSpacingDecoration(context, false))
			checkNotNull(selectionController).attachToRecyclerView(this)
			adapter = thumbnailsAdapter
			setHasFixedSize(true)
			PagerNestedScrollHelper(this).bind(viewLifecycleOwner)
			addOnLayoutChangeListener(spanResolver)
			addOnScrollListener(ScrollListener().also { scrollListener = it })
			(layoutManager as GridLayoutManager).let {
				it.spanSizeLookup = spanSizeLookup
				it.spanCount = checkNotNull(spanResolver).spanCount
			}
		}
		parentViewModel.emptyReason.observe(viewLifecycleOwner, ::onNoChaptersChanged)
		viewModel.thumbnails.observe(viewLifecycleOwner, ::onThumbnailsChanged)
		viewModel.onPageSaved.observeEvent(this, PagesSavedObserver(binding.recyclerView))
		viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(binding.recyclerView, this))
		combine(
			viewModel.isLoading,
			viewModel.thumbnails,
		) { loading, content ->
			loading && content.isEmpty()
		}.observe(viewLifecycleOwner) {
			binding.progressBar.showOrHide(it)
		}
		viewModel.isLoadingUp.observe(viewLifecycleOwner) { binding.progressBarTop.showOrHide(it) }
		viewModel.isLoadingDown.observe(viewLifecycleOwner) { binding.progressBarBottom.showOrHide(it) }
	}

	override fun onDestroyView() {
		spanResolver = null
		scrollListener = null
		thumbnailsAdapter = null
		selectionController = null
		spanSizeLookup.invalidateCache()
		super.onDestroyView()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeBask = contentInsetsType
		val barsInsets = insets.getInsets(typeBask)
		viewBinding?.recyclerView?.setPadding(
			barsInsets.left,
			barsInsets.top,
			barsInsets.right,
			barsInsets.bottom,
		)
		return insets.consumeAll(typeBask)
	}

	override fun onItemClick(item: PageThumbnail, view: View) {
		if (selectionController?.onItemClick(item.page.url) == true) {
			return
		}
		val listener = findParentCallback(ReaderNavigationCallback::class.java)
		if (listener != null && listener.onPageSelected(item.page)) {
			dismissParentDialog()
		} else {
			router.openReader(
				ReaderIntent.Builder(view.context)
					.manga(parentViewModel.getMangaOrNull() ?: return)
					.state(ReaderState(item.page.chapterId.toString(), item.page.index, 0))
					.build(),
			)
		}
	}

	override fun onItemLongClick(item: PageThumbnail, view: View): Boolean {
		return selectionController?.onItemLongClick(view, item.page.url) == true
	}

	override fun onItemContextClick(item: PageThumbnail, view: View): Boolean {
		return selectionController?.onItemContextClick(view, item.page.url) == true
	}

	override fun onSelectionChanged(controller: ListSelectionController, count: Int) {
		viewBinding?.recyclerView?.invalidateItemDecorations()
	}

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu,
	): Boolean {
		menuInflater.inflate(R.menu.mode_pages, menu)
		return true
	}

	override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_save -> {
				viewModel.savePages(pageSaveHelper, collectSelectedPages())
				mode?.finish()
				true
			}

			else -> false
		}
	}

	private suspend fun onThumbnailsChanged(list: List<ListModel>) {
		val adapter = thumbnailsAdapter ?: return
		if (adapter.itemCount == 0) {
			var position = list.indexOfFirst { it is PageThumbnail && it.isCurrent }
			if (position > 0) {
				val spanCount = spanResolver?.spanCount ?: 0
				val offset = if (position > spanCount + 1) {
					(resources.getDimensionPixelSize(R.dimen.manga_list_details_item_height) * 0.6).roundToInt()
				} else {
					position = 0
					0
				}
				val scrollCallback = RecyclerViewScrollCallback(requireViewBinding().recyclerView, position, offset)
				adapter.emit(list)
				scrollCallback.run()
			} else {
				adapter.emit(list)
			}
		} else {
			adapter.emit(list)
		}
		spanSizeLookup.invalidateCache()
		viewBinding?.recyclerView?.let {
			scrollListener?.postInvalidate(it)
		}
	}

	private fun onGridScaleChanged(scale: Float) {
		spanSizeLookup.invalidateCache()
		spanResolver?.setGridSize(scale, requireViewBinding().recyclerView)
	}

	private fun onNoChaptersChanged(reason: EmptyMangaReason?) {
		with(viewBinding ?: return) {
			textViewHolder.setTextAndVisible(reason?.msgResId ?: 0)
			recyclerView.isInvisible = reason != null
		}
	}

	private fun collectSelectedPages(): Set<ReaderPage> {
		val checkedIds = selectionController?.peekCheckedIds() ?: return emptySet()
		val items = thumbnailsAdapter?.items ?: return emptySet()
		val result = ArraySet<ReaderPage>(checkedIds.size)
		for (item in items) {
			if (item is PageThumbnail && item.page.url in checkedIds) {
				result.add(item.page)
			}
		}
		return result
	}

	private inner class ScrollListener : BoundsScrollListener(3, 3) {

		override fun onScrolledToStart(recyclerView: RecyclerView) {
			viewModel.loadPrevChapter()
		}

		override fun onScrolledToEnd(recyclerView: RecyclerView) {
			viewModel.loadNextChapter()
		}
	}

	private inner class SpanSizeLookup : GridLayoutManager.SpanSizeLookup() {

		init {
			isSpanIndexCacheEnabled = true
			isSpanGroupIndexCacheEnabled = true
		}

		override fun getSpanSize(position: Int): Int {
			val total = (viewBinding?.recyclerView?.layoutManager as? GridLayoutManager)?.spanCount ?: return 1
			return when (thumbnailsAdapter?.getItemViewType(position)) {
				ListItemType.PAGE_THUMB.ordinal -> 1
				else -> total
			}
		}

		fun invalidateCache() {
			invalidateSpanGroupIndexCache()
			invalidateSpanIndexCache()
		}
	}
}
