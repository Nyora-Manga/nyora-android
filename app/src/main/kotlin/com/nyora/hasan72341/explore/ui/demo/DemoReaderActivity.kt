package com.nyora.hasan72341.explore.ui.demo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.card.MaterialCardView
import dagger.hilt.android.AndroidEntryPoint
import com.nyora.hasan72341.core.ui.BaseActivity
import com.nyora.hasan72341.core.util.ext.contentInsets
import com.nyora.hasan72341.databinding.ActivityDemoReaderBinding

@AndroidEntryPoint
class DemoReaderActivity : BaseActivity<ActivityDemoReaderBinding>() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityDemoReaderBinding.inflate(layoutInflater))
		viewBinding.toolbar.setNavigationOnClickListener { dispatchNavigateUp() }

		val index = intent.getIntExtra(EXTRA_INDEX, 0)
		val entry = DEMO_ENTRIES.getOrElse(index) { DEMO_ENTRIES.first() }
		viewBinding.toolbar.title = entry.title
		bindPages(entry.pages)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.contentInsets
		viewBinding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
			topMargin = barsInsets.top
		}
		viewBinding.scrollView.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			bottom = barsInsets.bottom,
		)
		return insets
	}

	private fun bindPages(pages: List<DemoPage>) {
		val container = viewBinding.containerPages
		container.removeAllViews()
		val density = resources.displayMetrics.density
		val pad = (16 * density).toInt()
		val gap = (12 * density).toInt()
		for (page in pages) {
			val card = MaterialCardView(this).apply {
				layoutParams = LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT,
				).apply {
					topMargin = gap
				}
				radius = 20 * density
				cardElevation = 0f
			}
			val column = LinearLayout(this).apply {
				orientation = LinearLayout.VERTICAL
				setPadding(pad, pad, pad, pad)
			}
			val heading = TextView(this).apply {
				text = page.heading
				setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
			}
			val body = TextView(this).apply {
				text = page.body
				setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
			}
			val bodyParams = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
			).apply { topMargin = (8 * density).toInt() }
			column.addView(heading)
			column.addView(body, bodyParams)
			card.addView(column)
			container.addView(card)
		}
	}

	companion object {

		private const val EXTRA_INDEX = "demo_index"

		fun newIntent(context: Context, index: Int): Intent {
			return Intent(context, DemoReaderActivity::class.java)
				.putExtra(EXTRA_INDEX, index)
		}

		private data class DemoPage(val heading: String, val body: String)

		private data class DemoEntry(val title: String, val pages: List<DemoPage>)

		private val DEMO_ENTRIES = listOf(
			DemoEntry(
				title = "Welcome to Nyora",
				pages = listOf(
					DemoPage(
						heading = "Welcome",
						body = "Nyora is a clean, fast reader built for one thing: getting out of your way so you can read.",
					),
					DemoPage(
						heading = "One quiet shelf",
						body = "Favourites, history, bookmarks and downloads in one place — synced across your devices when you sign in.",
					),
					DemoPage(
						heading = "No ads. Ever.",
						body = "Just your library and the page in front of you.",
					),
				),
			),
			DemoEntry(
				title = "Reader Features",
				pages = listOf(
					DemoPage(
						heading = "Reading modes",
						body = "Read paged, right-to-left, or as a continuous webtoon scroll.",
					),
					DemoPage(
						heading = "Gestures & keys",
						body = "Tap the side zones to turn pages; long-press to peek; pinch to zoom.",
					),
					DemoPage(
						heading = "Translate",
						body = "Turn on translation to read foreign pages in your language, in place.",
					),
				),
			),
			DemoEntry(
				title = "Add Your Sources",
				pages = listOf(
					DemoPage(
						heading = "Bring your own sources",
						body = "Nyora ships without sources of its own; you add them from a source repository you trust.",
					),
					DemoPage(
						heading = "How",
						body = "Your sources appear here in Explore once added.",
					),
					DemoPage(
						heading = "That's it",
						body = "Once sources are added, this demo steps aside and your library takes over.",
					),
				),
			),
		)
	}
}
