package com.nyora.hasan72341.details.ui

import android.app.assist.AssistContent
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.text.SpannedString
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.viewModels
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import androidx.core.text.method.LinkMovementMethodCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.transition.TransitionManager
import com.nyora.hasan72341.core.ui.anim.SpringPresets
import com.nyora.hasan72341.core.ui.anim.springScale
import com.nyora.hasan72341.core.ui.haptic.HapticHelper
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.request.lifecycle
import coil3.request.transformations
import coil3.size.Precision
import coil3.transform.RoundedCornersTransformation
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import com.nyora.hasan72341.R
import com.nyora.hasan72341.bookmarks.domain.Bookmark
import com.nyora.hasan72341.core.image.CoilMemoryCacheKey
import com.nyora.hasan72341.core.model.FavouriteCategory
import com.nyora.hasan72341.core.model.LocalMangaSource
import com.nyora.hasan72341.core.model.UnknownMangaSource
import com.nyora.hasan72341.core.model.hasRating
import com.nyora.hasan72341.core.model.getSummary
import com.nyora.hasan72341.core.model.getTitle
import com.nyora.hasan72341.core.model.toMangaSource
import com.nyora.hasan72341.core.model.titleResId
import com.nyora.hasan72341.core.nav.ReaderIntent
import com.nyora.hasan72341.core.nav.router
import com.nyora.hasan72341.core.os.AppShortcutManager
import com.nyora.hasan72341.core.parser.favicon.faviconUri
import com.nyora.hasan72341.core.prefs.AppSettings
import com.nyora.hasan72341.core.ui.BaseActivity
import com.nyora.hasan72341.core.ui.BaseListAdapter
import com.nyora.hasan72341.core.ui.dialog.buildAlertDialog
import com.nyora.hasan72341.core.ui.image.FaviconDrawable
import com.nyora.hasan72341.core.ui.image.TextDrawable
import com.nyora.hasan72341.core.ui.image.TextViewTarget
import com.nyora.hasan72341.core.ui.list.OnListItemClickListener
import com.nyora.hasan72341.core.ui.sheet.BottomSheetCollapseCallback
import com.nyora.hasan72341.core.ui.util.FoldSupport
import com.nyora.hasan72341.core.ui.util.MenuInvalidator
import com.nyora.hasan72341.core.ui.util.ReversibleActionObserver
import com.nyora.hasan72341.core.ui.widgets.ChipsView
import com.nyora.hasan72341.core.util.FileSize
import com.nyora.hasan72341.core.util.LocaleUtils
import com.nyora.hasan72341.core.util.ext.consume
import com.nyora.hasan72341.core.util.ext.copyToClipboard
import com.nyora.hasan72341.core.util.ext.drawableStart
import com.nyora.hasan72341.core.util.ext.end
import com.nyora.hasan72341.core.util.ext.enqueueWith
import com.nyora.hasan72341.core.util.ext.getQuantityStringSafe
import com.nyora.hasan72341.core.util.ext.isAnimationsEnabled
import com.nyora.hasan72341.core.util.ext.isTextTruncated
import com.nyora.hasan72341.core.util.ext.joinToStringWithLimit
import com.nyora.hasan72341.core.util.ext.mangaSourceExtra
import com.nyora.hasan72341.core.util.ext.observe
import com.nyora.hasan72341.core.util.ext.observeEvent
import com.nyora.hasan72341.core.util.ext.parentView
import com.nyora.hasan72341.core.util.ext.setTooltipCompat
import com.nyora.hasan72341.core.util.ext.start
import com.nyora.hasan72341.core.util.ext.textAndVisible
import com.nyora.hasan72341.core.util.ext.toUriOrNull
import com.nyora.hasan72341.databinding.ActivityDetailsBinding
import com.nyora.hasan72341.databinding.LayoutDetailsTableBinding
import com.nyora.hasan72341.details.data.MangaDetails
import com.nyora.hasan72341.details.data.ReadingTime
import com.nyora.hasan72341.details.service.MangaPrefetchService
import com.nyora.hasan72341.details.ui.model.ChapterListItem
import com.nyora.hasan72341.details.ui.model.HistoryInfo
import com.nyora.hasan72341.details.ui.scrobbling.ScrobblingItemDecoration
import com.nyora.hasan72341.details.ui.scrobbling.ScrollingInfoAdapter
import com.nyora.hasan72341.download.ui.worker.DownloadStartedObserver
import com.nyora.hasan72341.list.domain.ReadingProgress
import com.nyora.hasan72341.list.ui.adapter.ListItemType
import com.nyora.hasan72341.list.ui.adapter.mangaGridItemAD
import com.nyora.hasan72341.list.ui.model.ListModel
import com.nyora.hasan72341.list.ui.model.MangaListModel
import com.nyora.hasan72341.list.ui.size.StaticItemSizeResolver
import com.nyora.hasan72341.main.ui.owners.BottomSheetOwner
import com.nyora.hasan72341.mihon.parsers.model.ContentRating
import com.nyora.hasan72341.mihon.parsers.model.Manga
import com.nyora.hasan72341.mihon.parsers.model.MangaTag
import com.nyora.hasan72341.mihon.parsers.util.ifNullOrEmpty
import com.nyora.hasan72341.mihon.parsers.util.nullIfEmpty
import com.nyora.hasan72341.mihon.parsers.util.toTitleCase
import com.nyora.hasan72341.scrobbling.common.domain.model.ScrobblingInfo
import javax.inject.Inject
import kotlin.math.roundToInt
import com.google.android.material.R as materialR
import com.nyora.hasan72341.core.util.ext.contentInsetsType

@AndroidEntryPoint
class DetailsActivity :
	BaseActivity<ActivityDetailsBinding>(),
	View.OnClickListener,
	View.OnLayoutChangeListener,
	ViewTreeObserver.OnDrawListener,
	ChipsView.OnChipClickListener,
	OnListItemClickListener<Bookmark>,
	SwipeRefreshLayout.OnRefreshListener,
	AuthorSpan.OnAuthorClickListener,
	BottomSheetOwner {

	@Inject
	lateinit var shortcutManager: AppShortcutManager

	@Inject
	lateinit var coil: ImageLoader

	@Inject
	lateinit var settings: AppSettings

	private val viewModel: DetailsViewModel by viewModels()
	private lateinit var menuProvider: DetailsMenuProvider
	private lateinit var infoBinding: LayoutDetailsTableBinding
	private val foldSupport = FoldSupport(this)
	private var hingeBounds: Rect? = null

	override val bottomSheet: View?
		get() = viewBinding.containerBottomSheet

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityDetailsBinding.inflate(layoutInflater))
		infoBinding = LayoutDetailsTableBinding.bind(viewBinding.root)
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		supportActionBar?.setDisplayShowTitleEnabled(false)
		viewBinding.chipFavorite.setOnClickListener(this)
		infoBinding.textViewLocal.setOnClickListener(this)
		infoBinding.textViewSource.setOnClickListener(this)
		viewBinding.imageViewCover.setOnClickListener(this)
		viewBinding.textViewTitle.setOnClickListener(this)
		viewBinding.buttonDescriptionMore.setOnClickListener(this)
		viewBinding.buttonScrobblingMore.setOnClickListener(this)
		viewBinding.buttonRelatedMore.setOnClickListener(this)
		viewBinding.textViewDescription.addOnLayoutChangeListener(this)
		viewBinding.swipeRefreshLayout.setOnRefreshListener(this)
		viewBinding.textViewDescription.viewTreeObserver.addOnDrawListener(this)
		infoBinding.textViewAuthor.movementMethod = LinkMovementMethodCompat.getInstance()
		viewBinding.textViewDescription.movementMethod = LinkMovementMethodCompat.getInstance()
		viewBinding.chipsTags.onChipClickListener = this
		TitleScrollCoordinator(viewBinding.textViewTitle).attach(viewBinding.scrollView)
		viewBinding.scrollView.setOnScrollChangeListener(
			ParallaxScrollListener(
				blurBackground = viewBinding.imageViewBlurBackground,
				toolbar = null, // toolbar fading handled by AppBarLayout
				coverImage = viewBinding.imageViewCover,
			),
		)
		if (settings.isDescriptionExpanded) {
			viewBinding.textViewDescription.maxLines = Int.MAX_VALUE - 1
		}
		viewBinding.containerBottomSheet?.let { sheet ->
			sheet.setOnClickListener(this)
			sheet.addOnLayoutChangeListener(this)
			onBackPressedDispatcher.addCallback(BottomSheetCollapseCallback(sheet))
			BottomSheetBehavior.from(sheet).addBottomSheetCallback(
				DetailsBottomSheetCallback(viewBinding.swipeRefreshLayout, checkNotNull(viewBinding.navbarDim)),
			)
		}

		if (viewBinding.cardChapters != null) {
			foldSupport.start()
			foldSupport.fold.observe(this) { applyFoldPosture() }
			viewBinding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
				applyFoldPosture()
			}
		}

		val appRouter = router
		viewModel.mangaDetails.filterNotNull().observe(this, ::onMangaUpdated)
		viewModel.coverUrl.observe(this, ::loadCover)
		viewModel.onMangaRemoved.observeEvent(this, ::onMangaRemoved)
		viewModel.onError
			.filterNot { appRouter.isChapterPagesSheetShown() }
			.observeEvent(this, DetailsErrorObserver(this, viewModel, exceptionResolver))
		viewModel.onActionDone
			.filterNot { appRouter.isChapterPagesSheetShown() }
			.observeEvent(this, ReversibleActionObserver(viewBinding.scrollView))
		combine(viewModel.historyInfo, viewModel.isLoading, ::Pair).observe(this) {
			onHistoryChanged(it.first, it.second)
		}
		viewModel.isLoading.observe(this, ::onLoadingStateChanged)
		viewModel.scrobblingInfo.observe(this, ::onScrobblingInfoChanged)
		viewModel.localSize.observe(this, ::onLocalSizeChanged)
		viewModel.relatedManga.observe(this, ::onRelatedMangaChanged)
		viewModel.favouriteCategories.observe(this, ::onFavoritesChanged)
		val menuInvalidator = MenuInvalidator(this)
		viewModel.isStatsAvailable.observe(this, menuInvalidator)
		viewModel.remoteManga.observe(this, menuInvalidator)
		viewModel.tags.observe(this, ::onTagsChanged)
		viewModel.chapters.observe(this, PrefetchObserver(this))
		viewModel.onDownloadStarted
			.filterNot { appRouter.isChapterPagesSheetShown() }
			.observeEvent(this, DownloadStartedObserver(viewBinding.scrollView))
		menuProvider = DetailsMenuProvider(
			activity = this,
			viewModel = viewModel,
			snackbarHost = viewBinding.scrollView,
			appShortcutManager = shortcutManager,
		)
		addMenuProvider(menuProvider)
	}

	override fun onProvideAssistContent(outContent: AssistContent) {
		super.onProvideAssistContent(outContent)
		viewModel.getMangaOrNull()?.publicUrl?.toUriOrNull()?.let { outContent.webUri = it }
	}

	override fun isNsfwContent(): Flow<Boolean> = viewModel.manga.map { it?.contentRating == ContentRating.ADULT }

	override fun onClick(v: View) {
		when (v.id) {
			R.id.textView_source -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.openList(manga.source.toMangaSource(), null, null)
			}

			R.id.textView_local -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.showLocalInfoDialog(manga)
			}

			R.id.chip_favorite -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.showFavoriteDialog(manga)
			}

			R.id.imageView_cover -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.openImage(
					url = viewModel.coverUrl.value ?: return,
					source = manga.source.toMangaSource(),
					preview = CoilMemoryCacheKey.from(viewBinding.imageViewCover),
					anchor = v,
				)
			}

			R.id.button_description_more -> {
				val tv = viewBinding.textViewDescription
                val defaultMaxLines = resources.getInteger(R.integer.details_description_lines)

                if (tv.context.isAnimationsEnabled) {
					tv.parentView?.let {
						TransitionManager.beginDelayedTransition(it)
					}
				}
				if (tv.maxLines in 1 until Integer.MAX_VALUE) {
					tv.maxLines = Integer.MAX_VALUE
				} else {
					tv.maxLines = defaultMaxLines
				}

                val btn = viewBinding.buttonDescriptionMore
                if (tv.maxLines == defaultMaxLines) {
                    btn.setText(R.string.more)
                } else {
                    btn.setText(R.string.collapse)
                }
			}

			R.id.button_scrobbling_more -> {
				router.showScrobblingSelectorSheet(
					manga = viewModel.getMangaOrNull() ?: return,
					scrobblerService = viewModel.scrobblingInfo.value.firstOrNull()?.scrobbler,
				)
			}

			R.id.button_related_more -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.openRelated(manga)
			}

			R.id.textView_title -> {
				val title = viewModel.getMangaOrNull()?.title?.nullIfEmpty() ?: return
				buildAlertDialog(this) {
					setMessage(title)
					setNegativeButton(R.string.close, null)
					setPositiveButton(androidx.preference.R.string.copy) { _, _ ->
						copyToClipboard(getString(R.string.content_type_manga), title)
					}
				}.show()
			}
		}
	}

	override fun onAuthorClick(author: String) {
		router.showAuthorDialog(author, viewModel.getMangaOrNull()?.source?.toMangaSource() ?: return)
	}

	override fun onChipClick(chip: Chip, data: Any?) {
		val tag = data as? MangaTag ?: return
		router.showTagDialog(tag)
	}

	override fun onItemClick(item: Bookmark, view: View) {
		router.openReader(ReaderIntent.Builder(view.context).bookmark(item).incognito().build())
		Toast.makeText(view.context, R.string.incognito_mode, Toast.LENGTH_SHORT).show()
	}

	override fun onRefresh() {
		viewModel.reload()
	}

	override fun onDraw() {
		viewBinding.run {
			buttonDescriptionMore.isVisible = textViewDescription.maxLines == Int.MAX_VALUE ||
				textViewDescription.isTextTruncated
		}
	}

	override fun onLayoutChange(
		v: View?,
		left: Int,
		top: Int,
		right: Int,
		bottom: Int,
		oldLeft: Int,
		oldTop: Int,
		oldRight: Int,
		oldBottom: Int
	) {
		with(viewBinding) {
			containerBottomSheet?.let { sheet ->
				val peekHeight = BottomSheetBehavior.from(sheet).peekHeight
				if (scrollView.paddingBottom != peekHeight) {
					scrollView.updatePadding(bottom = peekHeight)
				}
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = contentInsetsType
		val barsInsets = insets.getInsets(typeMask)
		if (viewBinding.cardChapters != null) {
			// landscape
			viewBinding.cardChapters?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
				topMargin = barsInsets.top + resources.getDimensionPixelOffset(R.dimen.grid_spacing_outer)
				marginEnd = barsInsets.end(v) + resources.getDimensionPixelOffset(R.dimen.side_card_offset)
				bottomMargin = barsInsets.bottom + resources.getDimensionPixelOffset(R.dimen.side_card_offset)
			}
			viewBinding.scrollView.updatePaddingRelative(
				bottom = barsInsets.bottom,
				start = barsInsets.start(v),
			)
			viewBinding.appbar.updatePaddingRelative(
				start = barsInsets.start(v),
			)
			return insets.consume(v, typeMask, bottom = true, end = true)
		} else {
			viewBinding.navbarDim?.updateLayoutParams {
				height = barsInsets.bottom
			}
			return insets
		}
	}

	/**
	 * In book posture the chapters card starts at the far edge of the hinge, so the two panes land
	 * on the two halves of the screen instead of crossing the fold.
	 */
	private fun applyFoldPosture() {
		if (viewBinding.cardChapters == null || viewBinding.guidelineHinge == null) {
			return
		}
		val root = viewBinding.root as? ConstraintLayout ?: return
		val hinge = if (foldSupport.isBook) foldSupport.hingeBoundsIn(root) else null
		if (hinge == hingeBounds) {
			return
		}
		hingeBounds = hinge
		val constraints = ConstraintSet()
		constraints.clone(root)
		if (hinge == null) {
			constraints.connect(R.id.card_chapters, ConstraintSet.START, R.id.appbar, ConstraintSet.END)
		} else {
			if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
				constraints.setGuidelineEnd(R.id.guideline_hinge, (root.width - hinge.left).coerceAtLeast(0))
			} else {
				constraints.setGuidelineBegin(R.id.guideline_hinge, hinge.right.coerceAtLeast(0))
			}
			constraints.connect(R.id.card_chapters, ConstraintSet.START, R.id.guideline_hinge, ConstraintSet.END)
		}
		constraints.applyTo(root)
	}

	private fun onFavoritesChanged(categories: Set<FavouriteCategory>) {
		val chip = viewBinding.chipFavorite
		val wasFavorited = chip.tag as? Boolean ?: false
		val isFavorited = categories.isNotEmpty()
		chip.setChipIconResource(if (categories.isEmpty()) R.drawable.ic_heart_outline else R.drawable.ic_heart)
		chip.text = if (categories.isEmpty()) {
			getString(R.string.add_to_favourites)
		} else {
			categories.joinToStringWithLimit(this, FAV_LABEL_LIMIT) { it.title }
		}
		// Animate and haptic on state change
		if (isFavorited != wasFavorited) {
			chip.tag = isFavorited
			if (isFavorited) {
				HapticHelper.confirm(chip)
				chip.springScale(1.15f, SpringPresets.BOUNCY) {
					chip.springScale(1.0f, SpringPresets.BOUNCY)
				}
			}
		} else {
			chip.tag = isFavorited
		}
	}

	private fun onLocalSizeChanged(size: Long) {
		if (size == 0L) {
			infoBinding.textViewLocal.isVisible = false
			infoBinding.textViewLocalLabel.isVisible = false
		} else {
			infoBinding.textViewLocal.text = FileSize.BYTES.format(this, size)
			infoBinding.textViewLocal.isVisible = true
			infoBinding.textViewLocalLabel.isVisible = true
		}
	}

	private fun onRelatedMangaChanged(related: List<MangaListModel>) {
		if (related.isEmpty()) {
			viewBinding.groupRelated.isVisible = false
			return
		}
		val rv = viewBinding.recyclerViewRelated

		@Suppress("UNCHECKED_CAST")
		val adapter = (rv.adapter as? BaseListAdapter<ListModel>) ?: BaseListAdapter<ListModel>()
			.addDelegate(
				ListItemType.MANGA_GRID,
				mangaGridItemAD(
					sizeResolver = StaticItemSizeResolver(resources.getDimensionPixelSize(R.dimen.smaller_grid_width)),
				) { item, view ->
					router.openDetails(item.toMangaWithOverride())
				},
			).also { rv.adapter = it }
		adapter.items = related
		viewBinding.groupRelated.isVisible = true
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		viewBinding.swipeRefreshLayout.isRefreshing = isLoading
	}

	private fun onScrobblingInfoChanged(scrobblings: List<ScrobblingInfo>) {
		var adapter = viewBinding.recyclerViewScrobbling.adapter as? ScrollingInfoAdapter
		viewBinding.groupScrobbling.isGone = scrobblings.isEmpty()
		if (adapter != null) {
			adapter.items = scrobblings
		} else {
			adapter = ScrollingInfoAdapter(router)
			adapter.items = scrobblings
			viewBinding.recyclerViewScrobbling.adapter = adapter
			viewBinding.recyclerViewScrobbling.addItemDecoration(ScrobblingItemDecoration())
		}
	}

	private fun onMangaUpdated(details: MangaDetails) {
		val manga = details.toManga()
		with(viewBinding) {
			textViewTitle.text = manga.title
			textViewSubtitle.textAndVisible = manga.altTitles.joinToString("\n")
			textViewNsfw16.isVisible = manga.contentRating == ContentRating.SUGGESTIVE
			textViewNsfw18.isVisible = manga.contentRating == ContentRating.ADULT
			textViewDescription.text = details.description.ifNullOrEmpty { getString(R.string.no_description) }
		}
		with(infoBinding) {
			val translation = details.getLocale()
			infoBinding.textViewTranslation.textAndVisible = translation?.getDisplayLanguage(translation)
				?.toTitleCase(translation)
			infoBinding.textViewTranslation.drawableStart = translation?.let {
				LocaleUtils.getEmojiFlag(it)
			}?.let {
				TextDrawable.compound(infoBinding.textViewTranslation, it)
			}
			infoBinding.textViewTranslationLabel.isVisible = infoBinding.textViewTranslation.isVisible
			textViewAuthor.textAndVisible = manga.getAuthorsString()
			textViewAuthorLabel.isVisible = textViewAuthor.isVisible
			if (manga.hasRating) {
				textViewRatingValue.text = String.format("%.1f", manga.rating * 5)
				textViewRatingValue.isVisible = true
				textViewRatingLabel.isVisible = true
			} else {
				textViewRatingValue.isVisible = false
				textViewRatingLabel.isVisible = false
			}
			manga.state?.let { state ->
				textViewState.textAndVisible = resources.getString(state.titleResId)
				textViewStateLabel.isVisible = textViewState.isVisible
			} ?: run {
				textViewState.isVisible = false
				textViewStateLabel.isVisible = false
			}

			if (manga.source == LocalMangaSource || manga.source == UnknownMangaSource) {
				textViewSource.isVisible = false
				textViewSourceLabel.isVisible = false
			} else {
				textViewSource.textAndVisible = manga.source.getTitle(this@DetailsActivity)
				textViewSource.setTooltipCompat(manga.source.toMangaSource().getSummary(this@DetailsActivity))
				textViewSourceLabel.isVisible = textViewSource.isVisible == true
			}
			val faviconPlaceholderFactory = FaviconDrawable.Factory(R.style.FaviconDrawable_Chip)
			ImageRequest.Builder(this@DetailsActivity)
				.data(manga.source.toMangaSource().faviconUri())
				.lifecycle(this@DetailsActivity)
				.crossfade(false)
				.precision(Precision.EXACT)
				.size(resources.getDimensionPixelSize(materialR.dimen.m3_chip_icon_size))
				.target(TextViewTarget(textViewSource, Gravity.START))
				.placeholder(faviconPlaceholderFactory)
				.error(faviconPlaceholderFactory)
				.fallback(faviconPlaceholderFactory)
				.mangaSourceExtra(manga.source.toMangaSource())
				.transformations(RoundedCornersTransformation(resources.getDimension(R.dimen.chip_icon_corner)))
				.allowRgb565(true)
				.enqueueWith(coil)
		}
		title = manga.title
		invalidateOptionsMenu()
	}

	private fun onMangaRemoved(manga: Manga) {
		Toast.makeText(
			this,
			getString(R.string._s_deleted_from_local_storage, manga.title),
			Toast.LENGTH_SHORT,
		).show()
		finishAfterTransition()
	}

	private fun onHistoryChanged(info: HistoryInfo, isLoading: Boolean) = with(infoBinding) {
		textViewChapters.text = when {
			isLoading -> getString(R.string.loading_)
			info.currentChapter >= 0 -> getString(
				R.string.chapter_d_of_d,
				info.currentChapter + 1,
				info.totalChapters,
			).withEstimatedTime(info.estimatedTime)

			info.totalChapters == 0 -> getString(R.string.no_chapters)
			info.totalChapters == -1 -> getString(R.string.error_occurred)
			else -> resources.getQuantityStringSafe(R.plurals.chapters, info.totalChapters, info.totalChapters)
				.withEstimatedTime(info.estimatedTime)
		}
		textViewProgress.textAndVisible = if (info.percent <= 0f) {
			null
		} else {
			val displayPercent = if (ReadingProgress.isCompleted(info.percent)) 100 else (info.percent * 100f).toInt()
			getString(R.string.percent_string_pattern, displayPercent.toString())
		}

		progress.setProgressCompat(
			(progress.max * info.percent.coerceIn(0f, 1f)).roundToInt(),
			true,
		)
		textViewProgressLabel.isVisible = info.history != null
		textViewProgress.isVisible = info.history != null
		progress.isVisible = info.history != null
	}

	private fun onTagsChanged(tags: Collection<ChipsView.ChipModel>) {
		viewBinding.chipsTags.isVisible = tags.isNotEmpty()
		viewBinding.chipsTags.setChips(tags)
	}

	private fun loadCover(imageUrl: String?) {
		viewBinding.imageViewCover.setImageAsync(imageUrl, viewModel.getMangaOrNull())
        viewBinding.imageViewBlurBackground?.setImageAsync(imageUrl, viewModel.getMangaOrNull())
	}

	private fun String.withEstimatedTime(time: ReadingTime?): String {
		if (time == null) {
			return this
		}
		val timeFormatted = time.formatShort(resources)
		return getString(R.string.chapters_time_pattern, this, timeFormatted)
	}

	private fun Manga.getAuthorsString(): SpannedString? {
		if (authors.isEmpty()) {
			return null
		}
		return buildSpannedString {
			authors.forEach { a ->
				if (a.isNotEmpty()) {
					if (isNotEmpty()) {
						append(", ")
					}
					inSpans(AuthorSpan(this@DetailsActivity)) {
						append(a)
					}
				}
			}
		}.nullIfEmpty()
	}

	private class PrefetchObserver(
		private val context: Context,
	) : FlowCollector<List<ChapterListItem>?> {

		private var isCalled = false

		override suspend fun emit(value: List<ChapterListItem>?) {
			if (value.isNullOrEmpty()) {
				return
			}
			if (!isCalled) {
				isCalled = true
				val item = value.find { it.isCurrent } ?: value.first()
				MangaPrefetchService.prefetchPages(context, item.chapter)
			}
		}
	}

	companion object {

		private const val FAV_LABEL_LIMIT = 16
	}
}
