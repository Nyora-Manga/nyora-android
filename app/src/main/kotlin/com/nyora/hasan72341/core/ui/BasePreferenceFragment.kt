package com.nyora.hasan72341.core.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.get
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import com.nyora.hasan72341.R
import com.nyora.hasan72341.core.exceptions.resolve.ExceptionResolver
import com.nyora.hasan72341.core.prefs.AppSettings
import com.nyora.hasan72341.core.ui.util.RecyclerViewOwner
import com.nyora.hasan72341.core.util.ext.consumeAllContentInsets
import com.nyora.hasan72341.core.util.ext.container
import com.nyora.hasan72341.core.util.ext.end
import com.nyora.hasan72341.core.util.ext.getThemeColor
import com.nyora.hasan72341.core.util.ext.getThemeDrawable
import com.nyora.hasan72341.core.util.ext.parentView
import com.nyora.hasan72341.core.util.ext.start
import com.nyora.hasan72341.core.util.ext.contentInsets
import com.nyora.hasan72341.settings.SettingsActivity
import javax.inject.Inject
import com.google.android.material.R as materialR

@AndroidEntryPoint
abstract class BasePreferenceFragment(@StringRes private val titleId: Int) :
	PreferenceFragmentCompat(),
	OnApplyWindowInsetsListener,
	RecyclerViewOwner {

	protected lateinit var exceptionResolver: ExceptionResolver
		private set

	@Inject
	lateinit var settings: AppSettings

	override val recyclerView: RecyclerView?
		get() = listView

	override fun onAttach(context: Context) {
		super.onAttach(context)
		val entryPoint = EntryPointAccessors.fromApplication<BaseActivityEntryPoint>(context)
		exceptionResolver = entryPoint.exceptionResolverFactory.create(this)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		ViewCompat.setOnApplyWindowInsetsListener(view, this)
		listView.clipToPadding = false
		setDividerHeight(0)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.contentInsets
		listView.setPaddingRelative(0, 0, 0, barsInsets.bottom)
		return insets.consumeAllContentInsets()
	}

	override fun onResume() {
		super.onResume()
		setTitle(if (titleId != 0) getString(titleId) else null)
		arguments?.getString(SettingsActivity.ARG_PREF_KEY)?.let {
			focusPreference(it)
			arguments?.remove(SettingsActivity.ARG_PREF_KEY)
		}
	}

	protected open fun setTitle(title: CharSequence?) {
		(activity as? SettingsActivity)?.setSectionTitle(title)
	}

	protected fun getWarningIcon(): Drawable? = context?.let { ctx ->
		ContextCompat.getDrawable(ctx, R.drawable.ic_alert_outline)?.also {
			it.setTint(ContextCompat.getColor(ctx, R.color.warning))
		}
	}

	private fun focusPreference(key: String) {
		val pref = findPreference<Preference>(key)
		if (pref == null) {
			scrollToPreference(key)
			return
		}
		scrollToPreference(pref)
		val prefIndex = preferenceScreen.indexOf(key)
		val view = if (prefIndex >= 0) {
			listView.findViewHolderForAdapterPosition(prefIndex)?.itemView ?: return
		} else {
			return
		}
		view.context.getThemeDrawable(materialR.attr.colorTertiaryContainer)?.let {
			view.background = it
		}
	}

	private fun PreferenceScreen.indexOf(key: String): Int {
		for (i in 0 until preferenceCount) {
			if (get(i).key == key) {
				return i
			}
		}
		return -1
	}
}
