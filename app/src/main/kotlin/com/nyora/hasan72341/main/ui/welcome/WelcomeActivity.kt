package com.nyora.hasan72341.main.ui.welcome

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import com.nyora.hasan72341.R
import com.nyora.hasan72341.core.model.titleResId
import com.nyora.hasan72341.core.nav.router
import com.nyora.hasan72341.core.ui.BaseActivity
import com.nyora.hasan72341.core.ui.widgets.ChipsView
import com.nyora.hasan72341.core.util.ext.contentInsetsType
import com.nyora.hasan72341.core.util.ext.getDisplayName
import com.nyora.hasan72341.core.util.ext.observe
import com.nyora.hasan72341.core.util.ext.tryLaunch
import com.nyora.hasan72341.databinding.ActivityWelcomeBinding
import com.nyora.hasan72341.filter.ui.model.FilterProperty
import com.nyora.hasan72341.mihon.parsers.model.ContentType
import com.nyora.hasan72341.sync.supabase.SupabaseSync
import com.nyora.hasan72341.sync.supabase.SupabaseSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class WelcomeActivity : BaseActivity<ActivityWelcomeBinding>(), ChipsView.OnChipClickListener, View.OnClickListener,
	ActivityResultCallback<Uri?> {

	@Inject
	lateinit var supabaseSync: SupabaseSync

	private val viewModel by viewModels<WelcomeViewModel>()

	private val backupSelectCall = registerForActivityResult(
		ActivityResultContracts.OpenDocument(),
		this,
	)

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val systemBars = insets.getInsets(contentInsetsType)
		v.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
		return insets
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityWelcomeBinding.inflate(layoutInflater))
		
		viewBinding.chipsLocales.onChipClickListener = this
		viewBinding.chipsType.onChipClickListener = this
		viewBinding.buttonBackup.setOnClickListener(this)
		viewBinding.buttonSignIn.setOnClickListener(this)
		viewBinding.buttonRegister.setOnClickListener(this)
		viewBinding.buttonGuest.setOnClickListener(this)
		viewBinding.buttonDirectories.setOnClickListener(this)
		viewBinding.buttonFinishSetup.setOnClickListener(this)

		viewModel.locales.observe(this, ::onLocalesChanged)
		viewModel.types.observe(this, ::onTypesChanged)

		onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				if (viewBinding.viewFlipper.displayedChild == 1) {
					viewBinding.viewFlipper.displayedChild = 0
					viewBinding.headerTitle.setText(R.string.welcome)
				} else {
					finishAffinity() // Exit app if back is pressed on step 1
				}
			}
		})
	}

	override fun onChipClick(chip: Chip, data: Any?) {
		when (data) {
			is ContentType -> viewModel.setTypeChecked(data, !chip.isChecked)
			is Locale -> viewModel.setLocaleChecked(data, !chip.isChecked)
		}
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_backup -> {
				if (!backupSelectCall.tryLaunch(arrayOf("*/*"))) {
					Snackbar.make(
						v, R.string.operation_not_supported, Snackbar.LENGTH_SHORT,
					).show()
				}
			}

			R.id.button_sign_in -> doAuth(register = false)

			R.id.button_register -> doAuth(register = true)

			R.id.button_guest -> {
				viewBinding.viewFlipper.displayedChild = 1 // Go to step 2
				viewBinding.headerTitle.setText(R.string.welcome_sources_title)
			}

			R.id.button_directories -> {
				router.openDirectoriesSettings()
			}

			R.id.button_finish_setup -> {
				finish() // Done with onboarding
			}
		}
	}

	private fun setAuthBusy(busy: Boolean) {
		viewBinding.buttonSignIn.isEnabled = !busy
		viewBinding.buttonRegister.isEnabled = !busy
		viewBinding.buttonGuest.isEnabled = !busy
		viewBinding.buttonBackup.isEnabled = !busy
		viewBinding.layoutLoading.visibility = if (busy) View.VISIBLE else View.GONE
	}

	private fun doAuth(register: Boolean) {
		val email = viewBinding.editEmail.text?.toString()?.trim().orEmpty()
		val password = viewBinding.editPassword.text?.toString().orEmpty()
		if (email.isEmpty() || password.isEmpty()) {
			Snackbar.make(viewBinding.root, R.string.enter_email_and_password, Snackbar.LENGTH_SHORT).show()
			return
		}
		setAuthBusy(true)
		lifecycleScope.launch(Dispatchers.IO) {
			val ok = if (register) supabaseSync.register(email, password) else supabaseSync.signIn(email, password)
			if (ok) {
				try {
					supabaseSync.syncNow()
				} catch (e: Exception) {
					android.util.Log.e("WelcomeActivity", "Initial sync failed", e)
				}
			}
			withContext(Dispatchers.Main) {
				setAuthBusy(false)
				if (ok) {
					SupabaseSyncWorker.schedulePeriodic(this@WelcomeActivity)
					Snackbar.make(viewBinding.root, R.string.signed_in, Snackbar.LENGTH_SHORT).show()
					viewBinding.viewFlipper.displayedChild = 1 // Go to step 2
					viewBinding.headerTitle.setText(R.string.welcome_sources_title)
				} else {
					Snackbar.make(viewBinding.root, R.string.sign_in_failed, Snackbar.LENGTH_LONG).show()
				}
			}
		}
	}

	override fun onActivityResult(result: Uri?) {
		if (result != null) {
			router.showBackupRestoreDialog(result)
		}
	}

	private fun onLocalesChanged(value: FilterProperty<Locale>) {
		val chips = viewBinding.chipsLocales
		chips.setChips(
			value.availableItems.map {
				ChipsView.ChipModel(
					title = it.getDisplayName(this),
					isChecked = it in value.selectedItems,
					data = it,
				)
			},
		)
	}

	private fun onTypesChanged(value: FilterProperty<ContentType>) {
		val chips = viewBinding.chipsType
		chips.setChips(
			value.availableItems.map {
				ChipsView.ChipModel(
					title = getString(it.titleResId),
					isChecked = it in value.selectedItems,
					data = it,
				)
			},
		)
	}
}