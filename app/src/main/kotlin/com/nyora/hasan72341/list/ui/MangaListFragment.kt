package com.nyora.hasan72341.list.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.appcompat.view.ActionMode
import androidx.collection.ArraySet
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import com.nyora.hasan72341.R
import com.nyora.hasan72341.alternatives.ui.AutoFixService
import com.nyora.hasan72341.core.exceptions.resolve.ExceptionResolver
import com.nyora.hasan72341.core.exceptions.resolve.SnackbarErrorObserver
import com.nyora.hasan72341.core.model.isLocal
import com.nyora.hasan72341.core.nav.router
import com.nyora.hasan72341.core.prefs.AppSettings
import com.nyora.hasan72341.core.prefs.ListMode
import com.nyora.hasan72341.core.ui.BaseFragment
import com.nyora.hasan72341.core.ui.dialog.buildAlertDialog
import com.nyora.hasan72341.core.ui.list.FitHeightGridLayoutManager
import com.nyora.hasan72341.core.ui.list.FitHeightLinearLayoutManager
import com.nyora.hasan72341.core.ui.list.ListSelectionController
import com.nyora.hasan72341.core.ui.list.PaginationScrollListener
import com.nyora.hasan72341.core.ui.list.fastscroll.FastScroller
import com.nyora.hasan72341.core.ui.util.RecyclerViewOwner
import com.nyora.hasan72341.core.ui.util.ReversibleActionObserver
import com.nyora.hasan72341.core.ui.widgets.TipView
import com.nyora.hasan72341.core.util.ShareHelper
import com.nyora.hasan72341.core.util.ext.addMenuProvider
import com.nyora.hasan72341.core.util.ext.consumeAll
import com.nyora.hasan72341.core.util.ext.contentInsetsType
import com.nyora.hasan72341.core.util.ext.findAppCompatDelegate
import com.nyora.hasan72341.core.util.ext.observe
import com.nyora.hasan72341.core.util.ext.observeEvent
import com.nyora.hasan72341.core.util.ext.viewLifecycleScope
import com.nyora.hasan72341.databinding.FragmentListBinding
import com.nyora.hasan72341.list.domain.ListFilterOption
import com.nyora.hasan72341.list.domain.QuickFilterListener
import com.nyora.hasan72341.list.ui.adapter.ListItemType
import com.nyora.hasan72341.list.ui.adapter.MangaListAdapter
import com.nyora.hasan72341.list.ui.adapter.MangaListListener
import com.nyora.hasan72341.list.ui.adapter.TypedListSpacingDecoration
import com.nyora.hasan72341.list.ui.model.ListHeader
import com.nyora.hasan72341.list.ui.model.ListModel
import com.nyora.hasan72341.list.ui.model.MangaListModel
import com.nyora.hasan72341.list.ui.size.DynamicItemSizeResolver
import com.nyora.hasan72341.main.ui.owners.AppBarOwner
import com.nyora.hasan72341.mihon.parsers.model.Manga
import com.nyora.hasan72341.mihon.parsers.model.MangaTag
import com.nyora.hasan72341.search.ui.MangaListActivity
import javax.inject.Inject

@AndroidEntryPoint
abstract class MangaListFragment :
	BaseFragment<FragmentListBinding>(),
	PaginationScrollListener.Callback,
	MangaListListener,
	RecyclerViewOwner,
	SwipeRefreshLayout.OnRefreshListener,
	ListSelectionController.Callback,
	FastScroller.FastScrollListener {

	@Inject
	lateinit var coil: ImageLoader

	@Inject
	lateinit var settings: AppSettings

	private var listAdapter: MangaListAdapter? = null
	private var paginationListener: PaginationScrollListener? = null
	private var selectionController: ListSelectionController? = null
	private var spanResolver: GridSpanResolver? = null
	private val spanSizeLookup = SpanSizeLookup()
	open val isSwipeRefreshEnabled = true

	protected abstract val viewModel: MangaListViewModel

	protected val selectedItemsIds: Set<String>
		get() = selectionController?.snapshot().orEmpty()

	protected val selectedItems: Set<Manga>
		get() = collectSelectedItems()

	override val recyclerView: RecyclerView?
		get() = viewBinding?.recyclerView

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentListBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		listAdapter = onCreateAdapter()
		spanResolver = GridSpanResolver(binding.root.resources)
		selectionController = ListSelectionController(
			appCompatDelegate = checkNotNull(findAppCompatDelegate()),
			decoration = MangaSelectionDecoration(binding.root.context),
			registryOwner = this,
			callback = this,
		)
		paginationListener = PaginationScrollListener(4, this)
		with(binding.recyclerView) {
			setHasFixedSize(true)
			adapter = listAdapter
			checkNotNull(selectionController).attachToRecyclerView(this)
			addItemDecoration(TypedListSpacingDecoration(context, false))
			addOnScrollListener(checkNotNull(paginationListener))
			fastScroller.setFastScrollListener(this@MangaListFragment)
		}
		with(binding.swipeRefreshLayout) {
			setOnRefreshListener(this@MangaListFragment)
			isEnabled = isSwipeRefreshEnabled
		}
		addMenuProvider(MangaListMenuProvider(this))

		viewModel.listMode.observe(viewLifecycleOwner, ::onListModeChanged)
		viewModel.gridScale.observe(viewLifecycleOwner, ::onGridScaleChanged)
		viewModel.isLoading.observe(viewLifecycleOwner, ::onLoadingStateChanged)
		viewModel.content.observe(viewLifecycleOwner, ::onListChanged)
		viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(binding.recyclerView, this))
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.recyclerView))
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = contentInsetsType
		val barsInsets = insets.getInsets(typeMask)
		val basePadding = v.resources.getDimensionPixelOffset(R.dimen.list_spacing_normal)
		viewBinding?.recyclerView?.setPadding(
			left = barsInsets.left + basePadding,
			top = basePadding,
			right = barsInsets.right + basePadding,
			bottom = barsInsets.bottom + basePadding,
		)
		return insets.consumeAll(typeMask)
	}

	override fun onDestroyView() {
		listAdapter = null
		paginationListener = null
		selectionController = null
		spanResolver = null
		spanSizeLookup.invalidateCache()
		super.onDestroyView()
	}

	override fun onItemClick(item: MangaListModel, view: View) {
		if (selectionController?.onItemClick(item.id) != true) {
			val manga = item.toMangaWithOverride()
			if ((activity as? MangaListActivity)?.showPreview(manga) != true) {
				router.openDetails(manga)
			}
		}
	}

	override fun onItemLongClick(item: MangaListModel, view: View): Boolean {
		return selectionController?.onItemLongClick(view, item.id) == true
	}

	override fun onItemContextClick(item: MangaListModel, view: View): Boolean {
		return selectionController?.onItemContextClick(view, item.id) == true
	}

	override fun onReadClick(manga: Manga, view: View) {
		if (selectionController?.onItemClick(manga.id) != true) {
			router.openReader(manga)
		}
	}

	override fun onTagClick(manga: Manga, tag: MangaTag, view: View) {
		if (selectionController?.onItemClick(manga.id) != true) {
			router.showTagDialog(tag)
		}
	}

	@CallSuper
	override fun onRefresh() {
		requireViewBinding().swipeRefreshLayout.isRefreshing = true
		viewModel.onRefresh()
	}

	private suspend fun onListChanged(list: List<ListModel>) {
		listAdapter?.emit(list)
		spanSizeLookup.invalidateCache()
		viewBinding?.recyclerView?.let {
			paginationListener?.postInvalidate(it)
		}
	}

	private fun resolveException(e: Throwable) {
		if (ExceptionResolver.canResolve(e)) {
			viewLifecycleScope.launch {
				if (exceptionResolver.resolve(e)) {
					viewModel.onRetry()
				}
			}
		} else {
			viewModel.onRetry()
		}
	}

	@CallSuper
	protected open fun onLoadingStateChanged(isLoading: Boolean) {
		requireViewBinding().swipeRefreshLayout.isEnabled = requireViewBinding().swipeRefreshLayout.isRefreshing ||
			isSwipeRefreshEnabled && !isLoading
		if (!isLoading) {
			requireViewBinding().swipeRefreshLayout.isRefreshing = false
		}
	}

	protected open fun onCreateAdapter(): MangaListAdapter {
		return MangaListAdapter(
			listener = this,
			sizeResolver = DynamicItemSizeResolver(resources, viewLifecycleOwner, settings, adjustWidth = false),
		)
	}

	override fun onFilterOptionClick(option: ListFilterOption) {
		selectionController?.clear()
		(viewModel as? QuickFilterListener)?.toggleFilterOption(option)
	}

	override fun onFilterClick(view: View?) = Unit

	override fun onEmptyActionClick() = Unit

	override fun onListHeaderClick(item: ListHeader, view: View) = Unit

	override fun onPrimaryButtonClick(tipView: TipView) = Unit

	override fun onSecondaryButtonClick(tipView: TipView) = Unit

	override fun onRetryClick(error: Throwable) {
		resolveException(error)
	}

	private fun onGridScaleChanged(scale: Float) {
		spanSizeLookup.invalidateCache()
		spanResolver?.setGridSize(scale, requireViewBinding().recyclerView)
	}

	private fun onListModeChanged(mode: ListMode) {
		spanSizeLookup.invalidateCache()
		with(requireViewBinding().recyclerView) {
			removeOnLayoutChangeListener(spanResolver)
			when (mode) {
				ListMode.LIST -> {
					layoutManager = FitHeightLinearLayoutManager(context)
				}

				ListMode.DETAILED_LIST -> {
					layoutManager = FitHeightLinearLayoutManager(context)
				}

				ListMode.GRID -> {
					layoutManager = FitHeightGridLayoutManager(context, checkNotNull(spanResolver).spanCount).also {
						it.spanSizeLookup = spanSizeLookup
					}
					addOnLayoutChangeListener(spanResolver)
				}
			}
		}
	}

	@CallSuper
	override fun onPrepareActionMode(controller: ListSelectionController, mode: ActionMode?, menu: Menu): Boolean {
		val hasNoLocal = selectedItems.none { it.isLocal }
		val isSingleSelection = controller.count == 1
		menu.findItem(R.id.action_save)?.isVisible = hasNoLocal
		menu.findItem(R.id.action_fix)?.isVisible = hasNoLocal
		menu.findItem(R.id.action_edit_override)?.isVisible = isSingleSelection
		return super.onPrepareActionMode(controller, mode, menu)
	}

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu
	): Boolean {
		return menu.hasVisibleItems()
	}

	override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_select_all -> {
				val ids = listAdapter?.items?.mapNotNull {
					(it as? MangaListModel)?.id
				} ?: return false
				selectionController?.addAll(ids)
				true
			}

			R.id.action_share -> {
				ShareHelper(requireContext()).shareMangaLinks(selectedItems)
				mode?.finish()
				true
			}

			R.id.action_favourite -> {
				router.showFavoriteDialog(selectedItems)
				mode?.finish()
				true
			}

			R.id.action_save -> {
				router.showDownloadDialog(selectedItems, viewBinding?.recyclerView)
				mode?.finish()
				true
			}

			R.id.action_edit_override -> {
				router.openMangaOverrideConfig(selectedItems.singleOrNull() ?: return false)
				mode?.finish()
				true
			}

			R.id.action_fix -> {
				val itemsSnapshot = selectedItemsIds
				buildAlertDialog(context ?: return false, isCentered = true) {
					setTitle(item.title)
					setIcon(item.icon)
					setMessage(R.string.manga_fix_prompt)
					setNegativeButton(android.R.string.cancel, null)
					setPositiveButton(R.string.fix) { _, _ ->
						AutoFixService.start(context, itemsSnapshot.mapNotNull { it.toLongOrNull() })
						mode?.finish()
					}
				}.show()
				true
			}

			else -> false
		}
	}

	override fun onSelectionChanged(controller: ListSelectionController, count: Int) {
		viewBinding?.recyclerView?.invalidateItemDecorations()
	}

	override fun onFastScrollStart(fastScroller: FastScroller) {
		(activity as? AppBarOwner)?.appBar?.setExpanded(false, true)
		requireViewBinding().swipeRefreshLayout.isEnabled = false
	}

	override fun onFastScrollStop(fastScroller: FastScroller) {
		requireViewBinding().swipeRefreshLayout.isEnabled = isSwipeRefreshEnabled
	}

	private fun collectSelectedItems(): Set<Manga> {
		val checkedIds = selectionController?.peekCheckedIds() ?: return emptySet()
		val items = listAdapter?.items ?: return emptySet()
		val result = ArraySet<Manga>(checkedIds.size)
		for (item in items) {
			if (item is MangaListModel && item.id in checkedIds) {
				result.add(item.manga)
			}
		}
		return result
	}

	private inner class SpanSizeLookup : GridLayoutManager.SpanSizeLookup() {

		init {
			isSpanIndexCacheEnabled = true
			isSpanGroupIndexCacheEnabled = true
		}

		override fun getSpanSize(position: Int): Int {
			val total = (viewBinding?.recyclerView?.layoutManager as? GridLayoutManager)?.spanCount ?: return 1
			return when (listAdapter?.getItemViewType(position)) {
				ListItemType.MANGA_GRID.ordinal -> 1
				else -> total
			}
		}

		fun invalidateCache() {
			invalidateSpanGroupIndexCache()
			invalidateSpanIndexCache()
		}
	}
}
