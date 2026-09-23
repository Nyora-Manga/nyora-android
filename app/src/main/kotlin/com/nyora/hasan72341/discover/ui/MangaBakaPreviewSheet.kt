package com.nyora.hasan72341.discover.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import dagger.hilt.android.AndroidEntryPoint
import com.nyora.hasan72341.R
import com.nyora.hasan72341.core.nav.router
import com.nyora.hasan72341.core.ui.sheet.BaseAdaptiveSheet
import com.nyora.hasan72341.core.util.ext.consume
import com.nyora.hasan72341.core.util.ext.enqueueWith
import com.nyora.hasan72341.core.util.ext.withArgs
import com.nyora.hasan72341.databinding.SheetMangabakaPreviewBinding
import com.nyora.hasan72341.mihon.parsers.util.runCatchingCancellable
import com.nyora.hasan72341.suggestions.data.MangaBakaMetaRepository
import com.nyora.hasan72341.suggestions.data.MangaBakaSeriesInfo
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Source-less preview: shows a title's synopsis + total chapter count from MangaBaka while the app
 * has no sources installed. Reading is disabled until the user adds a source repo, after which
 * Discover reverts to opening the real source.
 */
@AndroidEntryPoint
class MangaBakaPreviewSheet : BaseAdaptiveSheet<SheetMangabakaPreviewBinding>() {

	@Inject
	lateinit var repository: MangaBakaMetaRepository

	@Inject
	lateinit var coil: ImageLoader

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?) =
		SheetMangabakaPreviewBinding.inflate(inflater, container, false)

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
		requireViewBinding().root.updatePadding(bottom = insets.getInsets(typeMask).bottom)
		return insets.consume(v, typeMask, bottom = true)
	}

	override fun onViewBindingCreated(binding: SheetMangabakaPreviewBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val title = arguments?.getString(ARG_TITLE).orEmpty()
		binding.buttonAddSource.setOnClickListener {
			router.openSourcesCatalog()
			dismiss()
		}
		load(title)
	}

	private fun load(title: String) {
		setLoading()
		lifecycleScope.launch {
			val info = runCatchingCancellable { repository.searchFirst(title) }.getOrNull()
			val binding = viewBinding ?: return@launch
			if (info == null) {
				binding.progress.isVisible = false
				binding.content.isVisible = false
				binding.textError.isVisible = true
				binding.textError.setText(R.string.mangabaka_preview_unavailable)
			} else {
				bind(binding, info)
			}
		}
	}

	private fun setLoading() {
		val binding = viewBinding ?: return
		binding.progress.isVisible = true
		binding.content.isVisible = false
		binding.textError.isVisible = false
	}

	private fun bind(binding: SheetMangabakaPreviewBinding, info: MangaBakaSeriesInfo) {
		binding.progress.isVisible = false
		binding.textError.isVisible = false
		binding.content.isVisible = true

		binding.textTitle.text = info.title
		binding.textMeta.text = listOfNotNull(
			info.type?.replaceFirstChar { it.uppercase() },
			info.status?.replaceFirstChar { it.uppercase() },
			info.year?.toString(),
		).joinToString(" • ").ifEmpty { null }
		binding.textMeta.isVisible = binding.textMeta.text.isNullOrEmpty().not()

		binding.textChapters.isVisible = info.totalChapters != null
		info.totalChapters?.let { binding.textChapters.text = getString(R.string.chapters_count, it) }

		binding.textGenres.text = info.genres.take(5).joinToString(", ")
		binding.textGenres.isVisible = info.genres.isNotEmpty()

		binding.textDescription.text = info.description
		binding.textDescription.isVisible = info.description != null

		ImageRequest.Builder(binding.imageCover.context)
			.data(info.coverUrl)
			.crossfade(true)
			.target(binding.imageCover)
			.enqueueWith(coil)
	}

	companion object {
		private const val ARG_TITLE = "title"

		fun newInstance(title: String) = MangaBakaPreviewSheet().withArgs(1) {
			putString(ARG_TITLE, title)
		}
	}
}
