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
import com.nyora.hasan72341.sync.supabase.SupabaseGoogleAuthHelper
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

	private val googleSignInCall = registerForActivityResult(
		ActivityResultContracts.StartActivityForResult(),
	) { result ->
		onGoogleSignInResult(result.data)
	}

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
		viewBinding.buttonGoogleSignIn.setOnClickListener(this)
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

			R.id.button_google_sign_in -> {
				googleSignInCall.launch(SupabaseGoogleAuthHelper.createIntent(this))
			}

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

	private fun onGoogleSignInResult(data: Intent?) {
		when (val signInResult = SupabaseGoogleAuthHelper.handleResult(data)) {
			is SupabaseGoogleAuthHelper.GoogleSignInResult.Success -> {
				viewBinding.buttonGoogleSignIn.isEnabled = false
				viewBinding.buttonGuest.isEnabled = false
				viewBinding.buttonBackup.isEnabled = false
				viewBinding.layoutLoading.visibility = View.VISIBLE
				lifecycleScope.launch(Dispatchers.IO) {
					val ok = supabaseSync.signInWithGoogle(signInResult.idToken)
					if (ok) {
						try {
							supabaseSync.syncNow()
						} catch (e: Exception) {
							android.util.Log.e("WelcomeActivity", "Initial sync failed", e)
						}
					}
					withContext(Dispatchers.Main) {
						viewBinding.buttonGoogleSignIn.isEnabled = true
						viewBinding.buttonGuest.isEnabled = true
						viewBinding.buttonBackup.isEnabled = true
						viewBinding.layoutLoading.visibility = View.GONE
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
			is SupabaseGoogleAuthHelper.GoogleSignInResult.Error -> {
				Snackbar.make(viewBinding.root, signInResult.message, Snackbar.LENGTH_LONG).show()
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