package com.nyora.hasan72341.backups.ui.restore

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nyora.hasan72341.R
import com.nyora.hasan72341.backups.data.NyoraRestoreMode
import com.nyora.hasan72341.backups.data.NyoraRestorePlan
import com.nyora.hasan72341.core.nav.router
import com.nyora.hasan72341.core.ui.AlertDialogFragment
import com.nyora.hasan72341.core.util.ext.getDisplayMessage
import com.nyora.hasan72341.core.util.ext.observe
import com.nyora.hasan72341.core.util.ext.observeEvent
import com.nyora.hasan72341.databinding.DialogRestoreBinding
import dagger.hilt.android.AndroidEntryPoint
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.flow.combine

@AndroidEntryPoint
class RestoreDialogFragment : AlertDialogFragment<DialogRestoreBinding>(), View.OnClickListener {
	private val viewModel: RestoreViewModel by viewModels()

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?) =
		DialogRestoreBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: DialogRestoreBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.buttonCancel.setOnClickListener(this)
		binding.buttonRestore.setOnClickListener(this)
		binding.restoreModeGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
			if (!isChecked) return@addOnButtonCheckedListener
			when (checkedId) {
				R.id.button_merge -> viewModel.selectMerge()
				R.id.button_replace -> confirmReplace(group)
			}
		}
		viewModel.onError.observeEvent(viewLifecycleOwner, this::onError)
		combine(viewModel.isLoading, viewModel.plan, viewModel.mode, ::Triple)
			.observe(viewLifecycleOwner, this::render)
	}

	override fun onBuildDialog(builder: MaterialAlertDialogBuilder): MaterialAlertDialogBuilder =
		super.onBuildDialog(builder).setTitle(R.string.restore_backup).setCancelable(false)

	override fun onClick(view: View) {
		when (view.id) {
			R.id.button_cancel -> dismiss()
			R.id.button_restore -> if (startRestoreService()) {
				Toast.makeText(view.context, R.string.backup_restored_background, Toast.LENGTH_SHORT).show()
				router.closeWelcomeSheet()
				dismiss()
			} else {
				Toast.makeText(view.context, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
			}
		}
	}

	private fun confirmReplace(group: com.google.android.material.button.MaterialButtonToggleGroup) {
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.backup_restore_replace)
			.setMessage(R.string.backup_replace_confirmation)
			.setNegativeButton(android.R.string.cancel) { _, _ -> group.check(R.id.button_merge) }
			.setOnCancelListener { group.check(R.id.button_merge) }
			.setPositiveButton(R.string.backup_restore_replace) { _, _ -> viewModel.selectReplaceConfirmed() }
			.show()
	}

	private fun render(value: Triple<Boolean, NyoraRestorePlan?, NyoraRestoreMode>) {
		val (loading, plan, mode) = value
		with(requireViewBinding()) {
			progressBar.isVisible = loading
			buttonRestore.isEnabled = !loading && plan != null
			restoreModeGroup.isEnabled = !loading
			if (!loading) restoreModeGroup.check(if (mode == NyoraRestoreMode.Merge) R.id.button_merge else R.id.button_replace)
			textViewSubtitle.text = plan?.let(::planSummary) ?: getString(if (loading) R.string.processing_ else R.string.loading_)
		}
	}

	private fun planSummary(plan: NyoraRestorePlan): String {
		val additions = plan.sections.values.sumOf { it.additions }
		val updates = plan.sections.values.sumOf { it.updates }
		val deletions = plan.sections.values.sumOf { it.deletions }
		val unchanged = plan.sections.values.sumOf { it.unchanged }
		val counts = getString(R.string.backup_restore_preview, additions, updates, deletions, unchanged)
		val date = Date.from(java.time.Instant.parse(plan.createdAt)).formatBackupDate()
		return listOf(date, counts, plan.warnings.joinToString("\n")).filter { it.isNotBlank() }.joinToString("\n")
	}

	private fun startRestoreService(): Boolean {
		val context = context ?: run {
			viewModel.discardPreparedPayload()
			return false
		}
		return viewModel.handOffPayload { payload -> RestoreService.start(context, payload, viewModel.restoreRequest()) }
	}

	override fun onDismiss(dialog: DialogInterface) {
		viewModel.discardPreparedPayload()
		super.onDismiss(dialog)
	}

	private fun Date.formatBackupDate(): String = getString(
		R.string.backup_date_,
		SimpleDateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(this),
	)

	private fun onError(error: Throwable) {
		MaterialAlertDialogBuilder(context ?: return)
			.setNegativeButton(R.string.close, null)
			.setTitle(R.string.error)
			.setMessage(error.getDisplayMessage(resources))
			.show()
		dismiss()
	}
}
