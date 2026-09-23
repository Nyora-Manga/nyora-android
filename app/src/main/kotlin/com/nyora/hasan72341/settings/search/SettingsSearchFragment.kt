package com.nyora.hasan72341.settings.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.AsyncListDiffer.ListListener
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import com.nyora.hasan72341.core.ui.BaseFragment
import com.nyora.hasan72341.core.ui.BaseListAdapter
import com.nyora.hasan72341.core.ui.list.OnListItemClickListener
import com.nyora.hasan72341.core.util.ext.consumeAll
import com.nyora.hasan72341.core.util.ext.contentInsetsType
import com.nyora.hasan72341.core.util.ext.observe
import com.nyora.hasan72341.databinding.FragmentSearchSuggestionBinding
import com.nyora.hasan72341.list.ui.adapter.ListItemType

@AndroidEntryPoint
class SettingsSearchFragment : BaseFragment<FragmentSearchSuggestionBinding>(),
	OnListItemClickListener<SettingsItem>,
	ListListener<SettingsItem> {

	private val viewModel: SettingsSearchViewModel by activityViewModels()

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentSearchSuggestionBinding {
		return FragmentSearchSuggestionBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: FragmentSearchSuggestionBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val adapter = BaseListAdapter<SettingsItem>()
			.addDelegate(ListItemType.NAV_ITEM, settingsItemAD(this))
		adapter.addListListener(this)
		binding.root.adapter = adapter
		binding.root.setHasFixedSize(true)
		viewModel.content.observe(viewLifecycleOwner, adapter)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val type = WindowInsetsCompat.Type.ime() or contentInsetsType
		val barsInsets = insets.getInsets(type)
		v.setPadding(
			barsInsets.left,
			0,
			barsInsets.right,
			barsInsets.bottom,
		)
		return insets.consumeAll(type)
	}

	override fun onItemClick(item: SettingsItem, view: View) = viewModel.navigateToPreference(item)

	override fun onCurrentListChanged(
		previousList: List<SettingsItem?>,
		currentList: List<SettingsItem?>
	) {
		if (currentList.size != previousList.size) {
			(viewBinding?.root?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
		}
	}
}
