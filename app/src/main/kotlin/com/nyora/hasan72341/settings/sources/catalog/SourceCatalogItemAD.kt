package com.nyora.hasan72341.settings.sources.catalog

import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import com.nyora.hasan72341.R
import com.nyora.hasan72341.core.model.getSummary
import com.nyora.hasan72341.core.model.getTitle
import com.nyora.hasan72341.core.model.isBroken
import com.nyora.hasan72341.core.ui.image.FaviconDrawable
import com.nyora.hasan72341.core.ui.list.OnListItemClickListener
import com.nyora.hasan72341.core.util.ext.drawableStart
import com.nyora.hasan72341.core.util.ext.getThemeDimensionPixelOffset
import com.nyora.hasan72341.core.util.ext.setTextAndVisible
import com.nyora.hasan72341.databinding.ItemEmptyHintBinding
import com.nyora.hasan72341.databinding.ItemSourceCatalogBinding
import com.nyora.hasan72341.list.ui.model.ListModel
import androidx.appcompat.R as appcompatR

fun sourceCatalogItemSourceAD(
	listener: OnListItemClickListener<SourceCatalogItem.Source>
) = adapterDelegateViewBinding<SourceCatalogItem.Source, ListModel, ItemSourceCatalogBinding>(
	{ layoutInflater, parent ->
		ItemSourceCatalogBinding.inflate(layoutInflater, parent, false)
	},
) {

	binding.imageViewAdd.setOnClickListener { v ->
		listener.onItemLongClick(item, v)
	}
	binding.root.setOnClickListener { v ->
		listener.onItemClick(item, v)
	}
	val basePadding = context.getThemeDimensionPixelOffset(
		appcompatR.attr.listPreferredItemPaddingEnd,
		binding.root.paddingStart,
	)
	binding.root.updatePaddingRelative(
		end = (basePadding - context.resources.getDimensionPixelOffset(R.dimen.margin_small)).coerceAtLeast(0),
	)

	fun bindToggle() {
		val isEnabled = item.isEnabled
		binding.imageViewAdd.setImageResource(
			if (isEnabled) R.drawable.ic_remove else R.drawable.ic_add,
		)
		val label = context.getString(if (isEnabled) R.string.remove else R.string.add)
		binding.imageViewAdd.contentDescription = label
		TooltipCompat.setTooltipText(binding.imageViewAdd, label)
	}

	bind { payloads ->
		if (payloads.contains(SourceCatalogItem.PAYLOAD_ENABLED_CHANGED)) {
			bindToggle()
			return@bind
		}
		binding.textViewTitle.text = item.source.getTitle(context)
		binding.textViewDescription.text = item.source.getSummary(context)
		binding.textViewDescription.drawableStart = if (item.source.isBroken) {
			ContextCompat.getDrawable(context, R.drawable.ic_off_small)
		} else {
			null
		}
		FaviconDrawable(context, R.style.FaviconDrawable_Small, item.source.name)
		binding.imageViewIcon.setImageAsync(item.source)
		bindToggle()
	}
}

fun sourceCatalogItemHintAD() = adapterDelegateViewBinding<SourceCatalogItem.Hint, ListModel, ItemEmptyHintBinding>(
	{ inflater, parent -> ItemEmptyHintBinding.inflate(inflater, parent, false) },
) {

	binding.buttonRetry.isVisible = false

	bind {
		binding.icon.setImageAsync(item.icon)
		binding.textPrimary.setText(item.title)
		binding.textSecondary.setTextAndVisible(item.text)
	}
}
