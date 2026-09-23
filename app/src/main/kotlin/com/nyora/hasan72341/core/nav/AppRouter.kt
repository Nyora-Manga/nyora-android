package com.nyora.hasan72341.core.nav

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.annotation.CheckResult
import androidx.annotation.UiContext
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.findFragment
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.EntryPointAccessors
import com.nyora.hasan72341.BuildConfig
import com.nyora.hasan72341.R
import com.nyora.hasan72341.alternatives.ui.AlternativesActivity
import com.nyora.hasan72341.backups.ui.backup.BackupDialogFragment
import com.nyora.hasan72341.backups.ui.restore.RestoreDialogFragment
import com.nyora.hasan72341.bookmarks.ui.AllBookmarksActivity
import com.nyora.hasan72341.browser.BrowserActivity
import com.nyora.hasan72341.browser.cloudflare.CloudFlareActivity
import com.nyora.hasan72341.core.exceptions.CloudFlareProtectedException
import com.nyora.hasan72341.core.image.CoilMemoryCacheKey
import com.nyora.hasan72341.core.model.FavouriteCategory
import com.nyora.hasan72341.core.model.MangaSourceInfo
import com.nyora.hasan72341.core.model.appUrl
import com.nyora.hasan72341.core.model.getTitle
import com.nyora.hasan72341.core.model.isBroken
import com.nyora.hasan72341.core.model.isLocal
import com.nyora.hasan72341.core.model.toMangaSource
import com.nyora.hasan72341.core.model.parcelable.ParcelableManga
import com.nyora.hasan72341.core.model.parcelable.ParcelableMangaListFilter
import com.nyora.hasan72341.core.model.parcelable.ParcelableMangaPage
import com.nyora.hasan72341.core.network.CommonHeaders
import com.nyora.hasan72341.core.parser.external.ExternalMangaSource
import com.nyora.hasan72341.core.prefs.AppSettings
import com.nyora.hasan72341.core.prefs.ReaderMode
import com.nyora.hasan72341.core.prefs.TriStateOption
import com.nyora.hasan72341.core.ui.dialog.BigButtonsAlertDialog
import com.nyora.hasan72341.core.ui.dialog.ErrorDetailsDialog
import com.nyora.hasan72341.core.ui.dialog.buildAlertDialog
import com.nyora.hasan72341.core.util.ext.connectivityManager
import com.nyora.hasan72341.core.util.ext.findActivity
import com.nyora.hasan72341.core.util.ext.getThemeDrawable
import com.nyora.hasan72341.core.util.ext.printStackTraceDebug
import com.nyora.hasan72341.core.util.ext.toFileOrNull
import com.nyora.hasan72341.core.util.ext.toUriOrNull
import com.nyora.hasan72341.core.util.ext.withArgs
import com.nyora.hasan72341.details.ui.DetailsActivity
import com.nyora.hasan72341.details.ui.pager.ChaptersPagesSheet
import com.nyora.hasan72341.details.ui.related.RelatedMangaActivity
import com.nyora.hasan72341.details.ui.scrobbling.ScrobblingInfoSheet
import com.nyora.hasan72341.download.ui.dialog.DownloadDialogFragment
import com.nyora.hasan72341.favourites.ui.FavouritesActivity
import com.nyora.hasan72341.favourites.ui.categories.FavouriteCategoriesActivity
import com.nyora.hasan72341.favourites.ui.categories.edit.FavouritesCategoryEditActivity
import com.nyora.hasan72341.favourites.ui.categories.select.FavoriteDialog
import com.nyora.hasan72341.favourites.ui.container.FavouritesContainerViewModel
import com.nyora.hasan72341.filter.ui.FilterCoordinator
import com.nyora.hasan72341.filter.ui.sheet.FilterSheetFragment
import com.nyora.hasan72341.filter.ui.tags.TagsCatalogSheet
import com.nyora.hasan72341.history.ui.HistoryActivity
import com.nyora.hasan72341.image.ui.ImageActivity
import com.nyora.hasan72341.list.ui.config.ListConfigBottomSheet
import com.nyora.hasan72341.list.ui.config.ListConfigSection
import com.nyora.hasan72341.local.ui.ImportDialogFragment
import com.nyora.hasan72341.local.ui.info.LocalInfoDialog
import com.nyora.hasan72341.main.ui.MainActivity
import com.nyora.hasan72341.main.ui.welcome.WelcomeActivity
import com.nyora.hasan72341.reader.ui.colorfilter.ColorFilterConfigActivity
import com.nyora.hasan72341.reader.ui.config.ReaderConfigSheet
import com.nyora.hasan72341.scrobbling.common.domain.model.ScrobblerService
import com.nyora.hasan72341.scrobbling.common.ui.config.ScrobblerConfigActivity
import com.nyora.hasan72341.scrobbling.common.ui.selector.ScrobblingSelectorSheet
import com.nyora.hasan72341.search.domain.SearchKind
import com.nyora.hasan72341.search.ui.MangaListActivity
import com.nyora.hasan72341.search.ui.multi.SearchActivity
import com.nyora.hasan72341.settings.SettingsActivity
import com.nyora.hasan72341.settings.about.AppUpdateActivity
import com.nyora.hasan72341.settings.override.OverrideConfigActivity
import com.nyora.hasan72341.settings.reader.ReaderTapGridConfigActivity
import com.nyora.hasan72341.settings.sources.auth.SourceAuthActivity
import com.nyora.hasan72341.settings.sources.catalog.SourcesCatalogActivity
import com.nyora.hasan72341.settings.storage.MangaDirectorySelectDialog
import com.nyora.hasan72341.settings.storage.directories.MangaDirectoriesActivity
import com.nyora.hasan72341.settings.tracker.categories.TrackerCategoriesConfigSheet
import com.nyora.hasan72341.stats.ui.StatsActivity
import com.nyora.hasan72341.stats.ui.sheet.MangaStatsSheet
import com.nyora.hasan72341.suggestions.ui.SuggestionsActivity
import com.nyora.hasan72341.tracker.ui.updates.UpdatesActivity
import com.nyora.hasan72341.mihon.parsers.model.Manga
import com.nyora.hasan72341.mihon.parsers.model.MangaListFilter
import com.nyora.hasan72341.mihon.parsers.model.MangaPage
import com.nyora.hasan72341.mihon.parsers.model.MangaSource
import com.nyora.hasan72341.mihon.parsers.model.MangaTag
import com.nyora.hasan72341.mihon.parsers.model.SortOrder
import com.nyora.hasan72341.mihon.parsers.util.ellipsize
import com.nyora.hasan72341.mihon.parsers.util.isNullOrEmpty
import com.nyora.hasan72341.mihon.parsers.util.mapToArray
import java.io.File
import androidx.appcompat.R as appcompatR

class AppRouter private constructor(
    private val activity: FragmentActivity?,
    private val fragment: Fragment?,
) {

    constructor(activity: FragmentActivity) : this(activity, null)

    constructor(fragment: Fragment) : this(null, fragment)

    private val settings: AppSettings by lazy {
        EntryPointAccessors.fromApplication<AppRouterEntryPoint>(checkNotNull(contextOrNull())).settings
    }

    /** Activities **/

    fun openList(source: MangaSource, filter: MangaListFilter?, sortOrder: SortOrder?) {
        startActivity(listIntent(contextOrNull() ?: return, source, filter, sortOrder))
    }

    fun openList(tag: MangaTag) = openSearch(tag.title, SearchKind.TAG)

    fun openSearch(query: String, kind: SearchKind = SearchKind.SIMPLE) {
        startActivity(
            Intent(contextOrNull() ?: return, SearchActivity::class.java)
                .putExtra(KEY_QUERY, query)
                .putExtra(KEY_KIND, kind),
        )
    }

    fun openSearch(source: MangaSource, query: String) = openList(source, MangaListFilter(query = query), null)

    fun openDetails(manga: Manga) {
        startActivity(detailsIntent(contextOrNull() ?: return, manga))
    }

    fun openDetails(mangaId: String) {
        startActivity(detailsIntent(contextOrNull() ?: return, mangaId))
    }

    fun openDetails(link: Uri) {
        startActivity(
            Intent(contextOrNull() ?: return, DetailsActivity::class.java)
                .setData(link),
        )
    }

    fun openReader(manga: Manga, anchor: View? = null) {
        openReader(
            ReaderIntent.Builder(contextOrNull() ?: return)
                .manga(manga)
                .build(),
            anchor,
        )
    }

    fun openReader(intent: ReaderIntent, anchor: View? = null) {
        val activityIntent = intent.intent
        if (settings.isReaderMultiTaskEnabled && activityIntent.data != null) {
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }
        startActivity(activityIntent, anchor?.let { view -> scaleUpActivityOptionsOf(view) })
    }

    fun openAlternatives(manga: Manga) {
        startActivity(
            Intent(contextOrNull() ?: return, AlternativesActivity::class.java)
                .putExtra(KEY_MANGA, ParcelableManga(manga)),
        )
    }

    fun openRelated(manga: Manga) {
        startActivity(
            Intent(contextOrNull(), RelatedMangaActivity::class.java)
                .putExtra(KEY_MANGA, ParcelableManga(manga)),
        )
    }

    fun openImage(url: String, source: MangaSource?, anchor: View? = null, preview: CoilMemoryCacheKey? = null) {
        startActivity(
            Intent(contextOrNull(), ImageActivity::class.java)
                .setData(url.toUri())
                .putExtra(KEY_SOURCE, source?.name)
                .putExtra(KEY_PREVIEW, preview),
            anchor?.let { scaleUpActivityOptionsOf(it) },
        )
    }

    fun openBookmarks() = startActivity(AllBookmarksActivity::class.java)

    fun openAppUpdate() = startActivity(AppUpdateActivity::class.java)

    fun openSuggestions() {
        startActivity(suggestionsIntent(contextOrNull() ?: return))
    }

    fun openSourcesCatalog() = startActivity(SourcesCatalogActivity::class.java)

    fun openDownloads() {
        startActivity(
            Intent(contextOrNull() ?: return, MainActivity::class.java)
                .putExtra(KEY_TAB, R.id.nav_favorites)
                .putExtra(KEY_ID, FavouritesContainerViewModel.ID_DOWNLOADS)
        )
    }

    fun openDirectoriesSettings() = startActivity(MangaDirectoriesActivity::class.java)

    fun openBrowser(url: String, source: MangaSource?, title: String?) {
        startActivity(browserIntent(contextOrNull() ?: return, url, source, title))
    }

    fun openBrowser(manga: Manga) = openBrowser(
        url = manga.publicUrl,
        source = manga.source.toMangaSource(),
        title = manga.title,
    )

    fun openColorFilterConfig(manga: Manga, page: MangaPage) {
        startActivity(
            Intent(contextOrNull(), ColorFilterConfigActivity::class.java)
                .putExtra(KEY_MANGA, ParcelableManga(manga))
                .putExtra(KEY_PAGES, ParcelableMangaPage(page)),
        )
    }

    fun openHistory() = startActivity(HistoryActivity::class.java)

    fun openFavorites() = startActivity(FavouritesActivity::class.java)

    fun openFavorites(category: FavouriteCategory) {
        startActivity(
            Intent(contextOrNull() ?: return, FavouritesActivity::class.java)
                .putExtra(KEY_ID, category.id)
                .putExtra(KEY_TITLE, category.title),
        )
    }

    fun openFavoriteCategories() = startActivity(FavouriteCategoriesActivity::class.java)

    fun openFavoriteCategoryEdit(categoryId: Long) {
        startActivity(
            Intent(contextOrNull() ?: return, FavouritesCategoryEditActivity::class.java)
                .putExtra(KEY_ID, categoryId),
        )
    }

    fun openFavoriteCategoryCreate() = openFavoriteCategoryEdit(FavouritesCategoryEditActivity.NO_ID)

    fun openMangaUpdates() {
        startActivity(mangaUpdatesIntent(contextOrNull() ?: return))
    }

    fun openMangaOverrideConfig(manga: Manga) {
        val intent = overrideEditIntent(contextOrNull() ?: return, manga)
        startActivity(intent)
    }

    fun openSettings() = startActivity(SettingsActivity::class.java)

    fun openReaderSettings() {
        startActivity(readerSettingsIntent(contextOrNull() ?: return))
    }

    fun openProxySettings() {
        startActivity(proxySettingsIntent(contextOrNull() ?: return))
    }

    fun openDownloadsSetting() {
        startActivity(downloadsSettingsIntent(contextOrNull() ?: return))
    }

    fun openSourceSettings(source: MangaSource) {
        startActivity(sourceSettingsIntent(contextOrNull() ?: return, source))
    }

    fun openSuggestionsSettings() {
        startActivity(suggestionsSettingsIntent(contextOrNull() ?: return))
    }

    fun openSourcesSettings() {
        startActivity(sourcesSettingsIntent(contextOrNull() ?: return))
    }

    fun openDiscordSettings() {
        startActivity(discordSettingsIntent(contextOrNull() ?: return))
    }

    fun openReaderTapGridSettings() = startActivity(ReaderTapGridConfigActivity::class.java)

    fun openScrobblerSettings(scrobbler: ScrobblerService) {
        startActivity(
            Intent(contextOrNull() ?: return, ScrobblerConfigActivity::class.java)
                .putExtra(KEY_ID, scrobbler.id),
        )
    }

    fun openSourceAuth(source: MangaSource) {
        startActivity(sourceAuthIntent(contextOrNull() ?: return, source))
    }

    fun openManageSources() {
        startActivity(
            manageSourcesIntent(contextOrNull() ?: return),
        )
    }

    fun openStatistic() = startActivity(StatsActivity::class.java)

    @CheckResult
    fun openExternalBrowser(url: String, chooserTitle: CharSequence? = null): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = url.toUriOrNull() ?: return false
        return startActivitySafe(
            if (!chooserTitle.isNullOrEmpty()) {
                Intent.createChooser(intent, chooserTitle)
            } else {
                intent
            },
        )
    }

    /** Dialogs **/

    fun showDownloadDialog(manga: Manga, snackbarHost: View?) = showDownloadDialog(setOf(manga), snackbarHost)

    fun showDownloadDialog(manga: Collection<Manga>, snackbarHost: View?) {
        if (manga.isEmpty()) {
            return
        }
        val fm = getFragmentManager() ?: return
        if (snackbarHost != null) {
            getLifecycleOwner()?.let { lifecycleOwner ->
                DownloadDialogFragment.registerCallback(fm, lifecycleOwner, snackbarHost)
            }
        } else {
            DownloadDialogFragment.unregisterCallback(fm)
        }
        DownloadDialogFragment().withArgs(1) {
            putParcelableArray(KEY_MANGA, manga.mapToArray { ParcelableManga(it, withDescription = false) })
        }.showDistinct()
    }

    fun showLocalInfoDialog(manga: Manga) {
        LocalInfoDialog().withArgs(1) {
            putParcelable(KEY_MANGA, ParcelableManga(manga))
        }.showDistinct()
    }

    fun showDirectorySelectDialog() {
        MangaDirectorySelectDialog().showDistinct()
    }

    fun showFavoriteDialog(manga: Manga) = showFavoriteDialog(setOf(manga))

    fun showFavoriteDialog(manga: Collection<Manga>) {
        if (manga.isEmpty()) {
            return
        }
        FavoriteDialog().withArgs(1) {
            putParcelableArrayList(
                KEY_MANGA_LIST,
                manga.mapTo(ArrayList(manga.size)) { ParcelableManga(it, withDescription = false) },
            )
        }.showDistinct()
    }

    fun showTagDialog(tag: MangaTag) {
        buildAlertDialog(contextOrNull() ?: return) {
            setIcon(R.drawable.ic_tag)
            setTitle(tag.title)
            setItems(
                arrayOf(
                    context.getString(R.string.search_everywhere),
                ),
            ) { _, which ->
                when (which) {
                    0 -> openSearch(tag.title, SearchKind.TAG)
                }
            }
            setNegativeButton(R.string.close, null)
            setCancelable(true)
        }.show()
    }

    fun showAuthorDialog(author: String, source: MangaSource) {
        buildAlertDialog(contextOrNull() ?: return) {
            setIcon(R.drawable.ic_user)
            setTitle(author)
            setItems(
                arrayOf(
                    context.getString(R.string.search_on_s, source.getTitle(context)),
                    context.getString(R.string.search_everywhere),
                ),
            ) { _, which ->
                when (which) {
                    0 -> openList(source, MangaListFilter(author = author), null)
                    1 -> openSearch(author, SearchKind.AUTHOR)
                }
            }
            setNegativeButton(R.string.close, null)
            setCancelable(true)
        }.show()
    }

    fun showShareDialog(manga: Manga) {
        if (manga.isBroken) {
            return
        }
        if (manga.isLocal) {
            manga.url.toUri().toFileOrNull()?.let {
                shareFile(it)
            }
            return
        }
        buildAlertDialog(contextOrNull() ?: return) {
            setIcon(context.getThemeDrawable(appcompatR.attr.actionModeShareDrawable))
            setTitle(R.string.share)
            setItems(
                arrayOf(
                    context.getString(R.string.link_to_manga_in_app),
                    context.getString(R.string.link_to_manga_on_s, manga.source.getTitle(context)),
                ),
            ) { _, which ->
                val link = when (which) {
                    0 -> manga.appUrl.toString()
                    1 -> manga.publicUrl
                    else -> return@setItems
                }
                shareLink(link, manga.title)
            }
            setNegativeButton(android.R.string.cancel, null)
            setCancelable(true)
        }.show()
    }

    fun showErrorDialog(error: Throwable, url: String? = null) {
        ErrorDetailsDialog().withArgs(2) {
            putSerializable(KEY_ERROR, error)
            putString(KEY_URL, url)
        }.show()
    }

    fun showBackupRestoreDialog(fileUri: Uri) {
        RestoreDialogFragment().withArgs(1) {
            putString(KEY_FILE, fileUri.toString())
        }.show()
    }

    fun createBackup(destination: Uri) {
        BackupDialogFragment().withArgs(1) {
            putParcelable(KEY_DATA, destination)
        }.showDistinct()
    }

    fun showImportDialog() {
        ImportDialogFragment().showDistinct()
    }

    fun showMangaBakaPreview(title: String) {
        com.nyora.hasan72341.discover.ui.MangaBakaPreviewSheet.newInstance(title).showDistinct()
    }

    fun showFilterSheet(): Boolean = if (isFilterSupported()) {
        FilterSheetFragment().showDistinct()
    } else {
        false
    }

    fun showTagsCatalogSheet(excludeMode: Boolean) {
        if (!isFilterSupported()) {
            return
        }
        TagsCatalogSheet().withArgs(1) {
            putBoolean(KEY_EXCLUDE, excludeMode)
        }.showDistinct()
    }

    fun showListConfigSheet(section: ListConfigSection) {
        ListConfigBottomSheet().withArgs(1) {
            putParcelable(KEY_LIST_SECTION, section)
        }.showDistinct()
    }

    fun showStatisticSheet(manga: Manga) {
        MangaStatsSheet().withArgs(1) {
            putParcelable(KEY_MANGA, ParcelableManga(manga))
        }.showDistinct()
    }

    fun showReaderConfigSheet(mode: ReaderMode) {
        ReaderConfigSheet().withArgs(1) {
            putInt(KEY_READER_MODE, mode.id)
        }.showDistinct()
    }

    fun showWelcomeSheet() {
        val context = contextOrNull() ?: return
        val intent = Intent(context, WelcomeActivity::class.java)
        startActivity(intent)
    }

    fun showChapterPagesSheet() {
        ChaptersPagesSheet().showDistinct()
    }

    fun showChapterPagesSheet(defaultTab: Int) {
        ChaptersPagesSheet().withArgs(1) {
            putInt(KEY_TAB, defaultTab)
        }.showDistinct()
    }

    fun showScrobblingSelectorSheet(manga: Manga, scrobblerService: ScrobblerService?) {
        ScrobblingSelectorSheet().withArgs(2) {
            putParcelable(KEY_MANGA, ParcelableManga(manga))
            if (scrobblerService != null) {
                putInt(KEY_ID, scrobblerService.id)
            }
        }.show()
    }

    fun showScrobblingInfoSheet(index: Int) {
        ScrobblingInfoSheet().withArgs(1) {
            putInt(KEY_INDEX, index)
        }.showDistinct()
    }

    fun showTrackerCategoriesConfigSheet() {
        TrackerCategoriesConfigSheet().showDistinct()
    }

    fun askForDownloadOverMeteredNetwork(onConfirmed: (allow: Boolean) -> Unit) {
        val context = contextOrNull() ?: return
        when (settings.allowDownloadOnMeteredNetwork) {
            TriStateOption.ENABLED -> onConfirmed(true)
            TriStateOption.DISABLED -> onConfirmed(false)
            TriStateOption.ASK -> {
                if (!context.connectivityManager.isActiveNetworkMetered) {
                    onConfirmed(true)
                    return
                }
                val listener = DialogInterface.OnClickListener { _, which ->
                    when (which) {
                        DialogInterface.BUTTON_POSITIVE -> {
                            settings.allowDownloadOnMeteredNetwork = TriStateOption.ENABLED
                            onConfirmed(true)
                        }

                        DialogInterface.BUTTON_NEUTRAL -> {
                            onConfirmed(true)
                        }

                        DialogInterface.BUTTON_NEGATIVE -> {
                            settings.allowDownloadOnMeteredNetwork = TriStateOption.DISABLED
                            onConfirmed(false)
                        }
                    }
                }
                BigButtonsAlertDialog.Builder(context)
                    .setIcon(R.drawable.ic_network_cellular)
                    .setTitle(R.string.download_cellular_confirm)
                    .setPositiveButton(R.string.allow_always, listener)
                    .setNeutralButton(R.string.allow_once, listener)
                    .setNegativeButton(R.string.dont_allow, listener)
                    .create()
                    .show()
            }
        }
    }

    /** Public utils **/

    fun isFilterSupported(): Boolean = when {
        fragment != null -> FilterCoordinator.find(fragment) != null
        activity != null -> activity is FilterCoordinator.Owner
        else -> false
    }

    fun isChapterPagesSheetShown(): Boolean {
        val sheet = getFragmentManager()?.findFragmentByTag(fragmentTag<ChaptersPagesSheet>()) as? ChaptersPagesSheet
        return sheet?.dialog?.isShowing == true
    }

    fun closeWelcomeSheet(): Boolean {
        val currentActivity = activity
        if (currentActivity is WelcomeActivity) {
            currentActivity.finish()
            return true
        }
        return false
    }

    /** Private utils **/

    private fun startActivity(intent: Intent, options: Bundle? = null) {
        fragment?.also {
            if (it.host != null) {
                it.startActivity(intent, options)
            }
        } ?: activity?.startActivity(intent, options)
    }

    private fun startActivitySafe(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private fun startActivity(activityClass: Class<out Activity>) {
        startActivity(Intent(contextOrNull() ?: return, activityClass))
    }

    private fun getFragmentManager(): FragmentManager? = runCatching {
        fragment?.childFragmentManager ?: activity?.supportFragmentManager
    }.onFailure { exception ->
        exception.printStackTraceDebug("AppRouter::getFragmentManager")
    }.getOrNull()

    private fun shareLink(link: String, title: String) {
        val context = contextOrNull() ?: return
        ShareCompat.IntentBuilder(context)
            .setText(link)
            .setType(TYPE_TEXT)
            .setChooserTitle(context.getString(R.string.share_s, title.ellipsize(12)))
            .startChooser()
    }

    private fun shareFile(file: File) { // TODO directory sharing support
        val context = contextOrNull() ?: return
        val intentBuilder = ShareCompat.IntentBuilder(context)
            .setType(TYPE_CBZ)
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
        intentBuilder.addStream(uri)
        intentBuilder.setChooserTitle(context.getString(R.string.share_s, file.name))
        intentBuilder.startChooser()
    }

    @UiContext
    private fun contextOrNull(): Context? = activity ?: fragment?.context

    private fun getLifecycleOwner(): LifecycleOwner? = activity ?: fragment?.viewLifecycleOwner

    private fun DialogFragment.showDistinct(): Boolean {
        val fm = this@AppRouter.getFragmentManager() ?: return false
        val tag = javaClass.fragmentTag()
        val existing = fm.findFragmentByTag(tag) as? DialogFragment?
        if (existing != null && existing.isVisible && existing.arguments == this.arguments) {
            return false
        }
        show(fm, tag)
        return true
    }

    private fun DialogFragment.show() {
        show(
            this@AppRouter.getFragmentManager() ?: return,
            javaClass.fragmentTag(),
        )
    }

    private fun Fragment.findFragmentByTagRecursive(fragmentTag: String): Fragment? {
        childFragmentManager.findFragmentByTag(fragmentTag)?.let {
            return it
        }
        val parent = parentFragment
        return if (parent != null) {
            parent.findFragmentByTagRecursive(fragmentTag)
        } else {
            parentFragmentManager.findFragmentByTag(fragmentTag)
        }
    }

    companion object {

        fun from(view: View): AppRouter? = runCatching {
            AppRouter(view.findFragment())
        }.getOrElse {
            (view.context.findActivity() as? FragmentActivity)?.let(::AppRouter)
        }

        fun detailsIntent(context: Context, manga: Manga) = Intent(context, DetailsActivity::class.java)
            .putExtra(KEY_MANGA, ParcelableManga(manga))
            .setData(shortMangaUrl(manga.id))

        fun detailsIntent(context: Context, mangaId: String) = Intent(context, DetailsActivity::class.java)
            .putExtra(KEY_ID, mangaId)
            .setData(shortMangaUrl(mangaId))

        fun listIntent(context: Context, source: MangaSource, filter: MangaListFilter?, sortOrder: SortOrder?): Intent =
            Intent(context, MangaListActivity::class.java)
                .setAction(ACTION_MANGA_EXPLORE)
                .putExtra(KEY_SOURCE, source.name)
                .apply {
                    if (filter != null && !filter.isEmpty()) {
                        putExtra(KEY_FILTER, ParcelableMangaListFilter(filter))
                    }
                    if (sortOrder != null) {
                        putExtra(KEY_SORT_ORDER, sortOrder)
                    }
                }

        fun cloudFlareResolveIntent(context: Context, exception: CloudFlareProtectedException): Intent =
            Intent(context, CloudFlareActivity::class.java).apply {
                data = exception.url.toUri()
                putExtra(KEY_SOURCE, exception.source.name)
                exception.headers[CommonHeaders.USER_AGENT]?.let {
                    putExtra(KEY_USER_AGENT, it)
                }
            }

        fun browserIntent(
            context: Context,
            url: String,
            source: MangaSource?,
            title: String?
        ): Intent = Intent(context, BrowserActivity::class.java)
            .setData(url.toUri())
            .putExtra(KEY_TITLE, title)
            .putExtra(KEY_SOURCE, source?.name)

        fun suggestionsIntent(context: Context) = Intent(context, SuggestionsActivity::class.java)

        fun homeIntent(context: Context) = Intent(context, MainActivity::class.java)

        fun mangaUpdatesIntent(context: Context) = Intent(context, UpdatesActivity::class.java)

        fun readerSettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_READER)

        fun suggestionsSettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_SUGGESTIONS)

        fun trackerSettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_TRACKER)

        fun periodicBackupSettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_PERIODIC_BACKUP)

        fun discordSettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_MANAGE_DISCORD)

        fun proxySettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_PROXY)

        fun historySettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_HISTORY)

        fun sourcesSettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_SOURCES)

        fun manageSourcesIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_MANAGE_SOURCES)

        fun downloadsSettingsIntent(context: Context) =
            Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_MANAGE_DOWNLOADS)

        fun sourceSettingsIntent(context: Context, source: MangaSource): Intent = when (source) {
            is MangaSourceInfo -> sourceSettingsIntent(context, source.mangaSource)
            is ExternalMangaSource -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", source.packageName, null))

            else -> Intent(context, SettingsActivity::class.java)
                .setAction(ACTION_SOURCE)
                .putExtra(KEY_SOURCE, source.name)
        }

        fun sourceAuthIntent(context: Context, source: MangaSource): Intent {
            return Intent(context, SourceAuthActivity::class.java)
                .putExtra(KEY_SOURCE, source.name)
        }

        fun overrideEditIntent(context: Context, manga: Manga): Intent =
            Intent(context, OverrideConfigActivity::class.java)
                .putExtra(KEY_MANGA, ParcelableManga(manga, withDescription = false))

        fun isShareSupported(manga: Manga): Boolean = when {
            manga.isBroken -> false
            manga.isLocal -> manga.url.toUri().toFileOrNull() != null
            else -> true
        }

        fun shortMangaUrl(mangaId: String): Uri = Uri.Builder()
            .scheme("nyora")
            .authority("manga")
            .appendQueryParameter("id", mangaId)
            .build()

        const val KEY_DATA = "data"
        const val KEY_ENTRIES = "entries"
        const val KEY_ERROR = "error"
        const val KEY_EXCLUDE = "exclude"
        const val KEY_FILE = "file"
        const val KEY_FILTER = "filter"
        const val KEY_ID = "id"
        const val KEY_INDEX = "index"
        const val KEY_IS_BOTTOMTAB = "is_btab"
        const val KEY_KIND = "kind"
        const val KEY_LIST_SECTION = "list_section"
        const val KEY_MANGA = "manga"
        const val KEY_MANGA_LIST = "manga_list"
        const val KEY_PAGES = "pages"
        const val KEY_PREVIEW = "preview"
        const val KEY_QUERY = "query"
        const val KEY_READER_MODE = "reader_mode"
        const val KEY_SORT_ORDER = "sort_order"
        const val KEY_SOURCE = "source"
        const val KEY_TAB = "tab"
        const val KEY_TITLE = "title"
        const val KEY_URL = "url"
        const val KEY_USER_AGENT = "user_agent"

        const val ACTION_HISTORY = "${BuildConfig.APPLICATION_ID}.action.MANAGE_HISTORY"
        const val ACTION_MANAGE_DOWNLOADS = "${BuildConfig.APPLICATION_ID}.action.MANAGE_DOWNLOADS"
        const val ACTION_MANAGE_SOURCES = "${BuildConfig.APPLICATION_ID}.action.MANAGE_SOURCES_LIST"
        const val ACTION_MANGA_EXPLORE = "${BuildConfig.APPLICATION_ID}.action.EXPLORE_MANGA"
        const val ACTION_PROXY = "${BuildConfig.APPLICATION_ID}.action.MANAGE_PROXY"
        const val ACTION_READER = "${BuildConfig.APPLICATION_ID}.action.MANAGE_READER_SETTINGS"
        const val ACTION_SOURCE = "${BuildConfig.APPLICATION_ID}.action.MANAGE_SOURCE_SETTINGS"
        const val ACTION_SOURCES = "${BuildConfig.APPLICATION_ID}.action.MANAGE_SOURCES"
        const val ACTION_MANAGE_DISCORD = "${BuildConfig.APPLICATION_ID}.action.MANAGE_DISCORD"
        const val ACTION_SUGGESTIONS = "${BuildConfig.APPLICATION_ID}.action.MANAGE_SUGGESTIONS"
        const val ACTION_TRACKER = "${BuildConfig.APPLICATION_ID}.action.MANAGE_TRACKER"
        const val ACTION_PERIODIC_BACKUP = "${BuildConfig.APPLICATION_ID}.action.MANAGE_PERIODIC_BACKUP"

        private const val TYPE_TEXT = "text/plain"
        private const val TYPE_IMAGE = "image/*"
        private const val TYPE_CBZ = "application/x-cbz"

        private fun Class<out Fragment>.fragmentTag() = name // TODO

        private inline fun <reified F : Fragment> fragmentTag() = F::class.java.fragmentTag()
    }
}
