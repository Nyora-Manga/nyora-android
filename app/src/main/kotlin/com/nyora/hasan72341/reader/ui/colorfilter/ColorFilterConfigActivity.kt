package com.nyora.hasan72341.reader.ui.colorfilter

import android.content.res.Resources
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.ImageView
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import dagger.hilt.android.AndroidEntryPoint
import com.nyora.hasan72341.R
import com.nyora.hasan72341.core.ui.BaseActivity
import com.nyora.hasan72341.core.util.ext.consumeAllContentInsets
import com.nyora.hasan72341.core.util.ext.observe
import com.nyora.hasan72341.core.util.ext.observeEvent
import com.nyora.hasan72341.core.util.ext.setChecked
import com.nyora.hasan72341.core.util.ext.setValueRounded
import com.nyora.hasan72341.core.util.ext.contentInsets
import com.nyora.hasan72341.core.util.progress.ImageRequestIndicatorListener
import com.nyora.hasan72341.databinding.ActivityColorFilterBinding
import com.nyora.hasan72341.mihon.parsers.model.MangaPage
import com.nyora.hasan72341.mihon.parsers.util.format
import com.nyora.hasan72341.reader.domain.ReaderColorFilter
import javax.inject.Inject

import com.google.android.material.chip.ChipGroup

@AndroidEntryPoint
class ColorFilterConfigActivity :
	BaseActivity<ActivityColorFilterBinding>(),
	Slider.OnChangeListener,
	View.OnClickListener, CompoundButton.OnCheckedChangeListener {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel: ColorFilterConfigViewModel by viewModels()

	private val presetMap = mapOf(
		R.id.chip_none to ReaderColorFilter.Preset.NONE,
		R.id.chip_duotone_sepia to ReaderColorFilter.Preset.DUOTONE_SEPIA,
		R.id.chip_duotone_slate to ReaderColorFilter.Preset.DUOTONE_SLATE,
		R.id.chip_duotone_cyberpunk to ReaderColorFilter.Preset.DUOTONE_CYBERPUNK,
		R.id.chip_duotone_emerald to ReaderColorFilter.Preset.DUOTONE_EMERALD,
		R.id.chip_duotone_terracotta to ReaderColorFilter.Preset.DUOTONE_TERRACOTTA,
		R.id.chip_tritone_vintage to ReaderColorFilter.Preset.TRITONE_VINTAGE,
		R.id.chip_tritone_copper to ReaderColorFilter.Preset.TRITONE_COPPER,
		R.id.chip_tritone_sunset to ReaderColorFilter.Preset.TRITONE_SUNSET,
		R.id.chip_tritone_biolum to ReaderColorFilter.Preset.TRITONE_BIOLUM,
		R.id.chip_tritone_sage to ReaderColorFilter.Preset.TRITONE_SAGE,
		R.id.chip_quad_arcade to ReaderColorFilter.Preset.QUAD_ARCADE,
		R.id.chip_quad_candy to ReaderColorFilter.Preset.QUAD_CANDY,
		R.id.chip_quad_lavender to ReaderColorFilter.Preset.QUAD_LAVENDER,
		R.id.chip_quad_autumn to ReaderColorFilter.Preset.QUAD_AUTUMN,
		R.id.chip_quad_aurora to ReaderColorFilter.Preset.QUAD_AURORA,
		R.id.chip_penta_synthwave to ReaderColorFilter.Preset.PENTA_SYNTHWAVE,
		R.id.chip_penta_rainbow to ReaderColorFilter.Preset.PENTA_RAINBOW,
		R.id.chip_penta_canopy to ReaderColorFilter.Preset.PENTA_CANOPY,
		R.id.chip_penta_cybercity to ReaderColorFilter.Preset.PENTA_CYBERCITY,
		R.id.chip_penta_pastel to ReaderColorFilter.Preset.PENTA_PASTEL,
		R.id.chip_hex_abyss to ReaderColorFilter.Preset.HEX_ABYSS,
		R.id.chip_hex_thermal to ReaderColorFilter.Preset.HEX_THERMAL,
		R.id.chip_hex_sunset to ReaderColorFilter.Preset.HEX_SUNSET,
		R.id.chip_hex_meadow to ReaderColorFilter.Preset.HEX_MEADOW,
		R.id.chip_hex_vaporwave to ReaderColorFilter.Preset.HEX_VAPORWAVE,
		R.id.chip_hepta_nebula to ReaderColorFilter.Preset.HEPTA_NEBULA,
		R.id.chip_hepta_glitch to ReaderColorFilter.Preset.HEPTA_GLITCH,
		R.id.chip_hepta_vintage to ReaderColorFilter.Preset.HEPTA_VINTAGE,
		R.id.chip_hepta_jungle to ReaderColorFilter.Preset.HEPTA_JUNGLE,
		R.id.chip_hepta_twilight to ReaderColorFilter.Preset.HEPTA_TWILIGHT,
		R.id.chip_octa_acid to ReaderColorFilter.Preset.OCTA_ACID,
		R.id.chip_octa_prism to ReaderColorFilter.Preset.OCTA_PRISM,
		R.id.chip_octa_emerald to ReaderColorFilter.Preset.OCTA_EMERALD,
		R.id.chip_octa_sakura to ReaderColorFilter.Preset.OCTA_SAKURA,
		R.id.chip_octa_arctic to ReaderColorFilter.Preset.OCTA_ARCTIC
	)

	private val reversePresetMap = presetMap.entries.associate { it.value to it.key }

	private val chipGroups by lazy {
		listOf(
			viewBinding.chipGroupDuotone,
			viewBinding.chipGroupTritone,
			viewBinding.chipGroupQuadratone,
			viewBinding.chipGroupPentatone,
			viewBinding.chipGroupHexatone,
			viewBinding.chipGroupHeptatone,
			viewBinding.chipGroupOctatone
		)
	}

	private fun setupChipGroupListeners() {
		chipGroups.forEach { group ->
			group.setOnCheckedStateChangeListener { _, checkedIds ->
				val checkedId = checkedIds.firstOrNull()
				if (checkedId != null) {
					chipGroups.forEach { otherGroup ->
						if (otherGroup != group) {
							otherGroup.setOnCheckedStateChangeListener(null)
							otherGroup.clearCheck()
						}
					}
					val preset = presetMap[checkedId] ?: ReaderColorFilter.Preset.NONE
					viewModel.setMultitonePreset(preset)
					setupChipGroupListeners()
				} else {
					val anyChecked = chipGroups.any { it.checkedChipId != View.NO_ID }
					if (!anyChecked) {
						viewModel.setMultitonePreset(ReaderColorFilter.Preset.NONE)
					}
				}
			}
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityColorFilterBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		viewBinding.sliderBrightness.addOnChangeListener(this)
		viewBinding.sliderContrast.addOnChangeListener(this)
		val formatter = PercentLabelFormatter(resources)
		viewBinding.sliderContrast.setLabelFormatter(formatter)
		viewBinding.sliderBrightness.setLabelFormatter(formatter)
		viewBinding.switchInvert.setOnCheckedChangeListener(this)
		viewBinding.switchGrayscale.setOnCheckedChangeListener(this)
		viewBinding.switchBook.setOnCheckedChangeListener(this)
		viewBinding.buttonDone.setOnClickListener(this)
		viewBinding.buttonReset.setOnClickListener(this)

		setupChipGroupListeners()

		onBackPressedDispatcher.addCallback(ColorFilterConfigBackPressedDispatcher(this, viewModel))

		viewModel.colorFilter.observe(this, this::onColorFilterChanged)
		viewModel.isLoading.observe(this, this::onLoadingChanged)
		viewModel.onDismiss.observeEvent(this) {
			finishAfterTransition()
		}
		loadPreview(viewModel.preview)
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat
	): WindowInsetsCompat {
		val barsInsets = insets.contentInsets
		viewBinding.root.setPadding(
			barsInsets.left,
			barsInsets.top,
			barsInsets.right,
			barsInsets.bottom,
		)
		return insets.consumeAllContentInsets()
	}

	override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
		if (fromUser) {
			when (slider.id) {
				R.id.slider_brightness -> viewModel.setBrightness(value)
				R.id.slider_contrast -> viewModel.setContrast(value)
			}
		}
	}

	override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
		when (buttonView.id) {
			R.id.switch_invert -> viewModel.setInversion(isChecked)
			R.id.switch_grayscale -> viewModel.setGrayscale(isChecked)
			R.id.switch_book -> viewModel.setBookEffect(isChecked)
		}
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_done -> showSaveConfirmation()
			R.id.button_reset -> viewModel.reset()
		}
	}

	fun showSaveConfirmation() {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.apply)
			.setMessage(R.string.color_correction_apply_text)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.this_manga) { _, _ ->
				viewModel.save()
			}.setNeutralButton(R.string.globally) { _, _ ->
				viewModel.saveGlobally()
			}.show()
	}

	private fun onColorFilterChanged(readerColorFilter: ReaderColorFilter?) {
		viewBinding.sliderBrightness.setValueRounded(readerColorFilter?.brightness ?: 0f)
		viewBinding.sliderContrast.setValueRounded(readerColorFilter?.contrast ?: 0f)
		viewBinding.switchInvert.setChecked(readerColorFilter?.isInverted == true, false)
		viewBinding.switchGrayscale.setChecked(readerColorFilter?.isGrayscale == true, false)
		viewBinding.switchBook.setChecked(readerColorFilter?.isBookBackground == true, false)

		val targetChipId = reversePresetMap[readerColorFilter?.multitonePreset] ?: R.id.chip_none

		chipGroups.forEach { group ->
			group.setOnCheckedStateChangeListener(null)
			group.clearCheck()
		}

		chipGroups.forEach { group ->
			val hasChip = group.findViewById<View>(targetChipId) != null
			if (hasChip) {
				group.check(targetChipId)
			}
		}

		setupChipGroupListeners()

		viewBinding.imageViewAfter.colorFilter = readerColorFilter?.toColorFilter()
	}

	private fun loadPreview(page: MangaPage) = with(viewBinding.imageViewBefore) {
		addImageRequestListener(
			ImageRequestIndicatorListener(
				listOf(
					viewBinding.progressBefore,
					viewBinding.progressAfter,
				),
			),
		)
		addImageRequestListener(ShadowImageListener(viewBinding.imageViewAfter))
		setImageAsync(page)
	}

	private fun onLoadingChanged(isLoading: Boolean) {
		viewBinding.sliderContrast.isEnabled = !isLoading
		viewBinding.sliderBrightness.isEnabled = !isLoading
		viewBinding.switchInvert.isEnabled = !isLoading
		viewBinding.switchGrayscale.isEnabled = !isLoading
		viewBinding.buttonDone.isEnabled = !isLoading
		
		chipGroups.forEach { group ->
			group.isEnabled = !isLoading
			for (i in 0 until group.childCount) {
				group.getChildAt(i).isEnabled = !isLoading
			}
		}
	}

	private class PercentLabelFormatter(resources: Resources) : LabelFormatter {

		private val pattern = resources.getString(R.string.percent_string_pattern)

		override fun getFormattedValue(value: Float): String {
			val percent = ((value + 1f) * 100).format(0)
			return pattern.format(percent)
		}
	}

	private class ShadowImageListener(
		private val imageView: ImageView
	) : ImageRequest.Listener {

		override fun onError(request: ImageRequest, result: ErrorResult) {
			super.onError(request, result)
			imageView.setImageDrawable(result.image?.asDrawable(imageView.resources))
		}

		override fun onStart(request: ImageRequest) {
			super.onStart(request)
			imageView.setImageDrawable(request.placeholder()?.asDrawable(imageView.resources))
		}

		override fun onSuccess(request: ImageRequest, result: SuccessResult) {
			super.onSuccess(request, result)
			imageView.setImageDrawable(result.image.asDrawable(imageView.resources))
		}
	}
}
