package com.nyora.hasan72341.settings.about.changelog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import com.nyora.hasan72341.R
import com.nyora.hasan72341.core.exceptions.resolve.DialogErrorObserver
import com.nyora.hasan72341.core.ui.BaseFragment
import com.nyora.hasan72341.core.util.ext.consumeAll
import com.nyora.hasan72341.core.util.ext.container
import com.nyora.hasan72341.core.util.ext.contentInsetsType
import com.nyora.hasan72341.core.util.ext.end
import com.nyora.hasan72341.core.util.ext.observe
import com.nyora.hasan72341.core.util.ext.observeEvent
import com.nyora.hasan72341.core.util.ext.showOrHide
import com.nyora.hasan72341.core.util.ext.start
import com.nyora.hasan72341.databinding.FragmentChangelogBinding

@AndroidEntryPoint
class ChangelogFragment : BaseFragment<FragmentChangelogBinding>() {

	private val viewModel: ChangelogViewModel by viewModels()

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?
	) = FragmentChangelogBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentChangelogBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val markwon = Markwon.create(binding.root.context)
		viewModel.isLoading.observe(viewLifecycleOwner) {
			binding.progressBar.showOrHide(it)
		}
		viewModel.onError.observeEvent(viewLifecycleOwner, DialogErrorObserver(binding.root, this))
		viewModel.changelog.filterNotNull()
			.map { markwon.toMarkdown(it) }
			.flowOn(Dispatchers.IO)
			.observe(viewLifecycleOwner) {
				markwon.setParsedMarkdown(binding.textViewContent, it)
			}
	}

	override fun onResume() {
		super.onResume()
		activity?.setTitle(R.string.changelog)
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat
	): WindowInsetsCompat {
		val typeMask = contentInsetsType
		val barsInsets = insets.getInsets(typeMask)
		val isTablet = !resources.getBoolean(R.bool.is_tablet)
		val isMaster = container?.id == R.id.container_master
		val basePadding = resources.getDimensionPixelOffset(R.dimen.screen_padding)
		requireViewBinding().textViewContent.setPaddingRelative(
			basePadding + if (isTablet && !isMaster) 0 else barsInsets.start(v),
			basePadding,
			basePadding + if (isTablet && isMaster) 0 else barsInsets.end(v),
			basePadding + barsInsets.bottom,
		)
		return insets.consumeAll(typeMask)
	}
}
