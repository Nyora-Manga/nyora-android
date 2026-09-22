package com.nyora.hasan72341.stats.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.CompoundButton
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.AsyncListDiffer
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import dagger.hilt.android.AndroidEntryPoint
import com.nyora.hasan72341.R
import com.nyora.hasan72341.core.model.FavouriteCategory
import com.nyora.hasan72341.core.nav.router
import com.nyora.hasan72341.core.ui.BaseActivity
import com.nyora.hasan72341.core.ui.BaseListAdapter
import com.nyora.hasan72341.core.ui.dialog.buildAlertDialog
import com.nyora.hasan72341.core.ui.list.OnListItemClickListener
import com.nyora.hasan72341.core.ui.util.ReversibleActionObserver
import com.nyora.hasan72341.core.util.NyoraColors
import com.nyora.hasan72341.core.util.ext.contentInsetsType
import com.nyora.hasan72341.core.util.ext.end
import com.nyora.hasan72341.core.util.ext.observe
import com.nyora.hasan72341.core.util.ext.observeEvent
import com.nyora.hasan72341.core.util.ext.setTextAndVisible
import com.nyora.hasan72341.core.util.ext.showOrHide
import com.nyora.hasan72341.core.util.ext.start
import com.nyora.hasan72341.databinding.ActivityStatsBinding
import com.nyora.hasan72341.databinding.ItemEmptyStateBinding
import com.nyora.hasan72341.list.ui.adapter.ListItemType
import com.nyora.hasan72341.mihon.parsers.model.Manga
import com.nyora.hasan72341.stats.domain.StatsPeriod
import com.nyora.hasan72341.stats.domain.StatsRecord
import com.nyora.hasan72341.stats.ui.views.PieChartView

@AndroidEntryPoint
class StatsActivity : BaseActivity<ActivityStatsBinding>(),
	OnListItemClickListener<Manga>,
	PieChartView.OnSegmentClickListener,
	AsyncListDiffer.ListListener<StatsRecord>,
	ViewStub.OnInflateListener,
	View.OnClickListener,
	CompoundButton.OnCheckedChangeListener {

	private val viewModel: StatsViewModel by viewModels()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityStatsBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		val adapter = BaseListAdapter<StatsRecord>()
			.addDelegate(ListItemType.FEED, statsAD(this))
			.addListListener(this)
		viewBinding.recyclerView.adapter = adapter
		viewBinding.chart.onSegmentClickListener = this
		viewBinding.stubEmpty.setOnInflateListener(this)
		viewBinding.chipPeriod.setOnClickListener(this)

		viewModel.isLoading.observe(this) {
			viewBinding.progressBar.showOrHide(it)
		}
		viewModel.period.observe(this) {
			viewBinding.chipPeriod.setText(it.titleResId)
		}
		viewModel.favoriteCategories.observe(this, ::createCategoriesChips)
		viewModel.onActionDone.observeEvent(this, ReversibleActionObserver(viewBinding.recyclerView))
		viewModel.readingStats.observe(this) {
			val sum = it.sumOf { it.duration }
			viewBinding.chart.setData(
				it.map { v ->
					PieChartView.Segment(
						value = (v.duration / 1000).toInt(),
						label = v.manga?.title ?: getString(R.string.other_manga),
						percent = (v.duration.toDouble() / sum).toFloat(),
					color = NyoraColors.ofManga(this, v.manga),
						tag = v.manga,
					)
				},
			)
			adapter.emit(it)
		}
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat
	): WindowInsetsCompat {
		val bars = insets.getInsets(contentInsetsType)
		val isTablet = viewBinding.guidelineCenter != null
		viewBinding.appbar.updatePaddingRelative(
			start = bars.start(v),
			top = bars.top,
			end = if (isTablet) 0 else bars.end(v),
		)
		val badgePadding = resources.getDimensionPixelOffset(R.dimen.list_spacing_large)
		viewBinding.scrollViewChips.updatePaddingRelative(
			start = badgePadding + if (isTablet) 0 else bars.start(v),
			end = badgePadding + bars.end(v),
			top = if (isTablet) bars.top else 0,
		)
		viewBinding.recyclerView.updatePaddingRelative(
			start = if (isTablet) 0 else bars.start(v),
			end = bars.end(v),
			bottom = bars.bottom,
		)
		viewBinding.chart.updateLayoutParams<ViewGroup.MarginLayoutParams> {
			val baseMargin = topMargin
			bottomMargin = if (isTablet) baseMargin + bars.bottom else baseMargin
			marginStart = baseMargin + bars.start(v)
			marginEnd = if (isTablet) baseMargin else baseMargin + bars.end(v)
		}
		return WindowInsetsCompat.Builder(insets)
			.setInsets(contentInsetsType, Insets.NONE)
			.build()
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.chip_period -> showPeriodSelector()
		}
	}

	override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
		val category = buttonView.tag as? FavouriteCategory ?: return
		viewModel.setCategoryChecked(category, isChecked)
	}

	override fun onItemClick(item: Manga, view: View) {
		router.showStatisticSheet(item)
	}

	override fun onSegmentClick(view: PieChartView, segment: PieChartView.Segment) {
		val manga = segment.tag as? Manga ?: return
		onItemClick(manga, view)
	}

	override fun onCreateOptionsMenu(menu: Menu?): Boolean {
		menuInflater.inflate(R.menu.opt_stats, menu)
		return super.onCreateOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_clear -> {
				showClearConfirmDialog()
				true
			}

			else -> super.onOptionsItemSelected(item)
		}
	}

	override fun onCurrentListChanged(previousList: MutableList<StatsRecord>, currentList: MutableList<StatsRecord>) {
		val isEmpty = currentList.isEmpty()
		with(viewBinding) {
			chart.isGone = isEmpty
			recyclerView.isGone = isEmpty
			stubEmpty.isVisible = isEmpty
		}
	}

	override fun onInflate(stub: ViewStub?, inflated: View) {
		val stubBinding = ItemEmptyStateBinding.bind(inflated)
		stubBinding.icon.setImageAsync(R.drawable.ic_empty_history)
		stubBinding.textPrimary.setText(R.string.text_empty_holder_primary)
		stubBinding.textSecondary.setTextAndVisible(R.string.empty_stats_text)
		stubBinding.buttonRetry.isVisible = false
	}

	private fun createCategoriesChips(categories: List<FavouriteCategory>) {
		val container = viewBinding.layoutChips
		if (container.childCount > 1) {
			// avoid duplication
			return
		}
		val checkedIds = viewModel.selectedCategories.value
		for (category in categories) {
			val chip = Chip(this)
			val drawable = ChipDrawable.createFromAttributes(this, null, 0, R.style.Widget_Nyora_Chip_Filter)
			chip.setChipDrawable(drawable)
			chip.text = category.title
			chip.tag = category
			chip.isChecked = category.id in checkedIds
			chip.setOnCheckedChangeListener(this)
			container.addView(chip)
		}
	}

	private fun showClearConfirmDialog() {
		buildAlertDialog(this, isCentered = true) {
			setMessage(R.string.clear_stats_confirm)
			setTitle(R.string.clear_stats)
			setIcon(R.drawable.ic_delete_all)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.clear) { _, _ -> viewModel.clearStats() }
		}.show()
	}

	private fun showPeriodSelector() {
		val menu = PopupMenu(this, viewBinding.chipPeriod)
		val selected = viewModel.period.value
		for ((i, branch) in StatsPeriod.entries.withIndex()) {
			val item = menu.menu.add(R.id.group_period, Menu.NONE, i, branch.titleResId)
			item.isCheckable = true
			item.isChecked = selected.ordinal == i
		}
		menu.menu.setGroupCheckable(R.id.group_period, true, true)

		menu.setOnMenuItemClickListener {
			StatsPeriod.entries.getOrNull(it.order)?.also {
				viewModel.period.value = it
			} != null
		}
		menu.show()
	}
}
