package com.nyora.hasan72341.settings.about

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nyora.hasan72341.BuildConfig
import com.nyora.hasan72341.R
import com.nyora.hasan72341.core.parser.datadriven.CatalogueRefreshWorker
import com.nyora.hasan72341.core.parser.datadriven.DataDrivenCatalogue
import com.nyora.hasan72341.core.ui.BasePreferenceFragment
import javax.inject.Inject

@AndroidEntryPoint
class AboutSettingsFragment : BasePreferenceFragment(R.string.about) {

	@Inject
	lateinit var catalogue: DataDrivenCatalogue

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_about)
		findPreference<Preference>("about_app_info")?.summary = "v${BuildConfig.VERSION_NAME}"
		lifecycleScope.launch { updateCatalogueSummary() }
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean = when (preference.key) {
		KEY_CATALOGUE -> {
			refreshCatalogue()
			true
		}

		else -> super.onPreferenceTreeClick(preference)
	}

	private suspend fun updateCatalogueSummary() {
		// Reading the catalogue can still be the call that parses it, which the main thread must not do.
		val (count, revision) = withContext(Dispatchers.IO) {
			catalogue.sources.size to catalogue.revision
		}
		findPreference<Preference>(KEY_CATALOGUE)?.summary =
			getString(R.string.source_catalogue_summary, count, revision)
	}

	private fun refreshCatalogue() {
		viewLifecycleOwner.lifecycleScope.launch {
			val pref = findPreference<Preference>(KEY_CATALOGUE)
			pref?.isEnabled = false
			pref?.summary = getString(R.string.checking_for_updates)

			val result = CatalogueRefreshWorker.runNow(requireContext())
			updateCatalogueSummary()
			val message = result.fold(
				onSuccess = { getString(R.string.catalogue_updated, it) },
				onFailure = { getString(R.string.error_checking_updates) },
			)
			Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG).show()
			pref?.isEnabled = true
		}
	}

	private companion object {

		const val KEY_CATALOGUE = "about_parser_info"
	}
}
