package com.nyora.hasan72341.discover.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import com.nyora.hasan72341.R
import com.nyora.hasan72341.core.nav.router
import com.nyora.hasan72341.core.ui.BaseFragment
import com.nyora.hasan72341.core.ui.util.RecyclerViewOwner
import com.nyora.hasan72341.core.util.ext.consumeAllContentInsets
import com.nyora.hasan72341.core.util.ext.observe
import com.nyora.hasan72341.core.util.ext.contentInsets
import com.nyora.hasan72341.databinding.FragmentDiscoverBinding
import com.nyora.hasan72341.discover.ui.adapter.DiscoverAdapter
import com.nyora.hasan72341.list.ui.adapter.ListHeaderClickListener
import com.nyora.hasan72341.list.ui.adapter.ListStateHolderListener
import com.nyora.hasan72341.list.ui.adapter.TypedListSpacingDecoration
import com.nyora.hasan72341.list.ui.model.ListHeader
import com.nyora.hasan72341.mihon.parsers.model.MangaListFilter
import com.nyora.hasan72341.mihon.parsers.model.SortOrder

@AndroidEntryPoint
class DiscoverFragment :
	BaseFragment<FragmentDiscoverBinding>(),
	RecyclerViewOwner,
	ListHeaderClickListener,
	ListStateHolderListener {

	private val viewModel by viewModels<DiscoverViewModel>()
	private var discoverAdapter: DiscoverAdapter? = null

	override val recyclerView: RecyclerView?
		get() = viewBinding?.recyclerView

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentDiscoverBinding {
		return FragmentDiscoverBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: FragmentDiscoverBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		discoverAdapter = DiscoverAdapter(
			cardClick = { card ->
				when {
					card.manga != null -> router.openDetails(card.manga)
					// No source installed yet: show the MangaBaka overview instead of a dead search.
					!viewModel.hasSources -> router.showMangaBakaPreview(card.searchQuery ?: card.title)
					else -> card.searchQuery?.let(router::openSearch)
				}
			},
			heroClick = { hero ->
				if (viewModel.hasSources) {
					router.openSearch(hero.searchQuery)
				} else {
					router.showMangaBakaPreview(hero.searchQuery)
				}
			},
			headerClickListener = this,
			stateHolderListener = this,
			retry = { viewModel.retryNetworkRails() },
		)
		with(binding.recyclerView) {
			adapter = discoverAdapter
			layoutManager = LinearLayoutManager(context)
			setHasFixedSize(true)
			addItemDecoration(TypedListSpacingDecoration(context, false))
		}
		viewModel.content.observe(viewLifecycleOwner, checkNotNull(discoverAdapter))
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.contentInsets
		val basePadding = v.resources.getDimensionPixelOffset(R.dimen.list_spacing_normal)
		viewBinding?.recyclerView?.setPadding(
			/* left = */ barsInsets.left + basePadding,
			/* top = */ basePadding,
			/* right = */ barsInsets.right + basePadding,
			/* bottom = */ barsInsets.bottom + basePadding,
		)
		return insets.consumeAllContentInsets()
	}

	override fun onListHeaderClick(item: ListHeader, view: View) {
		when (item.payload as? String) {
			DiscoverViewModel.RAIL_CONTINUE_READING -> router.openHistory()
			DiscoverViewModel.RAIL_UPDATES -> router.openMangaUpdates()
			DiscoverViewModel.RAIL_TRENDING,
			DiscoverViewModel.RAIL_MANHWA,
			DiscoverViewModel.RAIL_MANHUA,
			DiscoverViewModel.RAIL_MANGA,
			-> router.openSearch(item.getText(requireContext())?.toString().orEmpty())

			DiscoverViewModel.RAIL_GENRE_ACTION -> router.openSearch(getString(R.string.genre_action))
			DiscoverViewModel.RAIL_GENRE_ROMANCE -> router.openSearch(getString(R.string.genre_romance))
			DiscoverViewModel.RAIL_GENRE_FANTASY -> router.openSearch(getString(R.string.genre_fantasy))
			DiscoverViewModel.RAIL_POPULAR_SOURCE -> viewModel.lastPopularSource
				?.let { router.openList(it, MangaListFilter.EMPTY, SortOrder.POPULARITY) }
				?: router.openSearch(item.getText(requireContext())?.toString().orEmpty())
		}
	}

	override fun onRetryClick(error: Throwable) {
		viewModel.retryNetworkRails()
	}

	override fun onEmptyActionClick() = Unit

	override fun onDestroyView() {
		super.onDestroyView()
		discoverAdapter = null
	}
}
