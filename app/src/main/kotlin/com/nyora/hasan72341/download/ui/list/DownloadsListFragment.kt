package com.nyora.hasan72341.download.ui.list

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ActionMode
import androidx.core.view.MenuProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import com.nyora.hasan72341.R
import com.nyora.hasan72341.core.nav.router
import com.nyora.hasan72341.core.ui.BaseActivity
import com.nyora.hasan72341.core.ui.BaseFragment
import com.nyora.hasan72341.core.ui.list.ListSelectionController
import com.nyora.hasan72341.core.ui.list.RecyclerScrollKeeper
import com.nyora.hasan72341.core.ui.util.MenuInvalidator
import com.nyora.hasan72341.core.ui.util.RecyclerViewOwner
import com.nyora.hasan72341.core.ui.util.ReversibleActionObserver
import com.nyora.hasan72341.core.util.ext.addMenuProvider
import com.nyora.hasan72341.core.util.ext.observe
import com.nyora.hasan72341.core.util.ext.observeEvent
import com.nyora.hasan72341.core.util.ext.contentInsets
import com.nyora.hasan72341.databinding.FragmentListBinding
import com.nyora.hasan72341.download.ui.worker.DownloadWorker
import com.nyora.hasan72341.list.ui.adapter.TypedListSpacingDecoration
import javax.inject.Inject

@AndroidEntryPoint
class DownloadsListFragment : BaseFragment<FragmentListBinding>(),
	DownloadItemListener,
	ListSelectionController.Callback,
	RecyclerViewOwner {

	@Inject
	lateinit var coil: ImageLoader

	@Inject
	lateinit var scheduler: DownloadWorker.Scheduler

	private val viewModel by viewModels<DownloadsViewModel>()
	private lateinit var selectionController: ListSelectionController

	override val recyclerView: androidx.recyclerview.widget.RecyclerView?
		get() = viewBinding?.recyclerView

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?
	) = FragmentListBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val downloadsAdapter = DownloadsAdapter(viewLifecycleOwner, this)
		val decoration = TypedListSpacingDecoration(requireContext(), false)
		selectionController = ListSelectionController(
			appCompatDelegate = (requireActivity() as BaseActivity<*>).delegate,
			decoration = DownloadsSelectionDecoration(requireContext()),
			registryOwner = this,
			callback = this,
		)
		with(binding.recyclerView) {
			setHasFixedSize(true)
			addItemDecoration(decoration)
			adapter = downloadsAdapter
			selectionController.attachToRecyclerView(this)
			RecyclerScrollKeeper(this).attach()
		}
		addMenuProvider(DownloadsMenuProvider(requireActivity(), viewModel))
		viewModel.items.observe(viewLifecycleOwner, downloadsAdapter)
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.recyclerView))
		val menuInvalidator = MenuInvalidator(requireActivity())
		viewModel.hasActiveWorks.observe(viewLifecycleOwner, menuInvalidator)
		viewModel.hasPausedWorks.observe(viewLifecycleOwner, menuInvalidator)
		viewModel.hasCancellableWorks.observe(viewLifecycleOwner, menuInvalidator)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.contentInsets
		viewBinding?.recyclerView?.updatePadding(
			left = bars.left,
			right = bars.right,
			bottom = bars.bottom,
		)
		return insets
	}

	override fun onItemClick(item: DownloadItemModel, view: View) {
		if (selectionController.onItemClick(item.id.mostSignificantBits.toString())) {
			return
		}
		router.openDetails(item.manga ?: return)
	}

	override fun onItemLongClick(item: DownloadItemModel, view: View): Boolean {
		return selectionController.onItemLongClick(view, item.id.mostSignificantBits.toString())
	}

	override fun onItemContextClick(item: DownloadItemModel, view: View): Boolean {
		return selectionController.onItemContextClick(view, item.id.mostSignificantBits.toString())
	}

	override fun onExpandClick(item: DownloadItemModel) {
		if (!selectionController.onItemClick(item.id.mostSignificantBits.toString())) {
			viewModel.expandCollapse(item)
		}
	}

	override fun onCancelClick(item: DownloadItemModel) {
		viewModel.cancel(item.id)
	}

	override fun onPauseClick(item: DownloadItemModel) {
		scheduler.pause(item.id)
	}

	override fun onResumeClick(item: DownloadItemModel) {
		scheduler.resume(item.id)
	}

	override fun onSkipClick(item: DownloadItemModel) {
		scheduler.skip(item.id)
	}

	override fun onSkipAllClick(item: DownloadItemModel) {
		scheduler.skipAll(item.id)
	}

	override fun onSelectionChanged(controller: ListSelectionController, count: Int) {
		viewBinding?.recyclerView?.invalidateItemDecorations()
	}

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu
	): Boolean {
		menuInflater.inflate(R.menu.mode_downloads, menu)
		return true
	}

	override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_resume -> {
				viewModel.resume(controller.snapshot())
				mode?.finish()
				true
			}

			R.id.action_pause -> {
				viewModel.pause(controller.snapshot())
				mode?.finish()
				true
			}

			R.id.action_cancel -> {
				viewModel.cancel(controller.snapshot())
				mode?.finish()
				true
			}

			R.id.action_remove -> {
				viewModel.remove(controller.snapshot())
				mode?.finish()
				true
			}

			R.id.action_select_all -> {
				controller.addAll(viewModel.allIds())
				true
			}

			else -> false
		}
	}

	override fun onPrepareActionMode(controller: ListSelectionController, mode: ActionMode?, menu: Menu): Boolean {
		val snapshot = viewModel.snapshot(controller.peekCheckedIds())
		var canPause = true
		var canResume = true
		var canCancel = true
		var canRemove = true
		for (item in snapshot) {
			canPause = canPause and item.canPause
			canResume = canResume and item.canResume
			canCancel = canCancel and !item.workState.isFinished
			canRemove = canRemove and item.workState.isFinished
		}
		menu.findItem(R.id.action_pause)?.isVisible = canPause
		menu.findItem(R.id.action_resume)?.isVisible = canResume
		menu.findItem(R.id.action_cancel)?.isVisible = canCancel
		menu.findItem(R.id.action_remove)?.isVisible = canRemove
		return true
	}
}
