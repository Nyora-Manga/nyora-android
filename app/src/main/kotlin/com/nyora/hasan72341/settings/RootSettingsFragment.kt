package com.nyora.hasan72341.settings

import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import com.nyora.hasan72341.BuildConfig
import com.nyora.hasan72341.R
import com.nyora.hasan72341.core.prefs.AppSettings
import com.nyora.hasan72341.core.ui.BasePreferenceFragment
import com.nyora.hasan72341.core.ui.util.windowSizeClasses
import com.nyora.hasan72341.core.util.ext.addMenuProvider
import com.nyora.hasan72341.core.util.ext.getQuantityStringSafe
import com.nyora.hasan72341.core.util.ext.observe
import com.nyora.hasan72341.settings.search.SettingsSearchMenuProvider
import com.nyora.hasan72341.settings.search.SettingsSearchViewModel

@AndroidEntryPoint
class RootSettingsFragment : BasePreferenceFragment(0) {

	private val viewModel: RootSettingsViewModel by viewModels()
	private val activityViewModel: SettingsSearchViewModel by activityViewModels()

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_root)
		bindPreferenceSummary("appearance", R.string.theme, R.string.list_mode, R.string.language)
		bindPreferenceSummary("reader", R.string.read_mode, R.string.scale_mode, R.string.switch_pages)
		bindPreferenceSummary("network", R.string.storage_usage, R.string.proxy, R.string.prefetch_content)
		bindPreferenceSummary("userdata", R.string.create_or_restore_backup, R.string.periodic_backups)
		bindPreferenceSummary("downloads", R.string.manga_save_location, R.string.downloads_wifi_only)
		bindPreferenceSummary("tracker", R.string.track_sources, R.string.notifications_settings)
		bindPreferenceSummary("services", R.string.suggestions, R.string.related_manga, R.string.tracking)
		findPreference<Preference>("about")?.summary = "Version ${BuildConfig.VERSION_NAME}"
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		findPreference<Preference>(AppSettings.KEY_REMOTE_SOURCES)?.let { pref ->
			val total = viewModel.totalSourcesCount
			viewModel.enabledSourcesCount.observe(viewLifecycleOwner) {
				pref.summary = if (it >= 0) {
					getString(R.string.enabled_d_of_d, it, total)
				} else {
					resources.getQuantityStringSafe(R.plurals.items, total, total)
				}
			}
		}
		addMenuProvider(SettingsSearchMenuProvider(activityViewModel))
	}

	override fun setTitle(title: CharSequence?) {
		if (!windowSizeClasses.isAtLeastMediumWidth) {
			super.setTitle(title)
		}
	}

	private fun bindPreferenceSummary(key: String, @StringRes vararg items: Int) {
		findPreference<Preference>(key)?.summary = items.joinToString { getString(it) }
	}
}
