package com.nyora.hasan72341.settings.override

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.filterNotNull
import com.nyora.hasan72341.R
import com.nyora.hasan72341.core.ui.BaseActivity
import com.nyora.hasan72341.core.ui.model.MangaOverride
import com.nyora.hasan72341.core.util.ext.consumeAll
import com.nyora.hasan72341.core.util.ext.getDisplayMessage
import com.nyora.hasan72341.core.util.ext.observe
import com.nyora.hasan72341.core.util.ext.observeEvent
import com.nyora.hasan72341.core.util.ext.tryLaunch
import com.nyora.hasan72341.databinding.ActivityOverrideEditBinding
import com.nyora.hasan72341.mihon.parsers.model.Manga
import com.nyora.hasan72341.mihon.parsers.util.ifNullOrEmpty
import com.nyora.hasan72341.picker.ui.PageImagePickContract
import com.google.android.material.R as materialR
import com.nyora.hasan72341.core.util.ext.contentInsetsType

@AndroidEntryPoint
class OverrideConfigActivity : BaseActivity<ActivityOverrideEditBinding>(), View.OnClickListener,
	ActivityResultCallback<Uri?> {

	private val viewModel: OverrideConfigViewModel by viewModels()

	private val pickCoverFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument(), this)
	private val pickPageLauncher = registerForActivityResult(PageImagePickContract(), this)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityOverrideEditBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		viewBinding.buttonDone.setOnClickListener(this)
		viewBinding.buttonPickFile.setOnClickListener(this)
		viewBinding.buttonPickPage.setOnClickListener(this)
		viewBinding.buttonResetCover.setOnClickListener(this)
		viewBinding.layoutName.setEndIconOnClickListener(this)
		viewModel.data.filterNotNull().observe(this, ::onDataChanged)
		viewModel.onSaved.observeEvent(this) { onDataSaved() }
		viewModel.isLoading.observe(this, ::onLoadingStateChanged)
		viewModel.onError.observeEvent(this, ::onError)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = contentInsetsType
		val barsInsets = insets.getInsets(typeMask)
		viewBinding.root.setPadding(
			barsInsets.left,
			barsInsets.top,
			barsInsets.right,
			barsInsets.bottom,
		)
		return insets.consumeAll(typeMask)
	}

	override fun onActivityResult(result: Uri?) {
		if (result != null) {
			if (result.host?.startsWith(packageName) != true) {
				contentResolver.takePersistableUriPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION)
			}
			viewModel.updateCover(result.toString())
		}
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_done -> viewModel.save(
				title = viewBinding.editName.text?.toString()?.trim(),
			)

			materialR.id.text_input_end_icon -> viewBinding.editName.text?.clear()

			R.id.button_reset_cover -> viewModel.updateCover(null)
			R.id.button_pick_file -> {
				if (!pickCoverFileLauncher.tryLaunch(arrayOf("image/*"))) {
					Snackbar.make(
						viewBinding.imageViewCover,
						R.string.operation_not_supported,
						Snackbar.LENGTH_SHORT,
					).show()
				}
			}

			R.id.button_pick_page -> {
				val manga = viewModel.data.value?.first
				pickPageLauncher.launch(manga)
			}
		}
	}

	private fun onDataChanged(data: Pair<Manga, MangaOverride>) {
		val (manga, override) = data
		viewBinding.imageViewCover.setImageAsync(override.coverUrl.ifNullOrEmpty { manga.coverUrl }, manga)
		viewBinding.layoutName.placeholderText = manga.title
		if (viewBinding.editName.tag == null) {
			viewBinding.editName.setText(override.title)
			viewBinding.editName.tag = override.title
		}
		viewBinding.buttonResetCover.isEnabled = !override.coverUrl.isNullOrEmpty()
	}

	private fun onError(e: Throwable) {
		viewBinding.textViewError.text = e.getDisplayMessage(resources)
		viewBinding.textViewError.isVisible = true
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		viewBinding.buttonDone.isEnabled = !isLoading
		viewBinding.editName.isEnabled = !isLoading
		if (isLoading) {
			viewBinding.textViewError.isVisible = false
		}
	}

	private fun onDataSaved() {
		setResult(RESULT_OK)
		finish()
	}
}
