package com.nyora.hasan72341.core.model

import android.content.Context
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.text.inSpans
import com.nyora.hasan72341.R
import com.nyora.hasan72341.core.parser.datadriven.DataDrivenCatalogue
import com.nyora.hasan72341.core.parser.datadriven.LEGACY_SOURCE_RENAMES
import com.nyora.hasan72341.core.parser.datadriven.canonicalDataSourceId
import com.nyora.hasan72341.core.parser.external.ExternalMangaSource
import com.nyora.hasan72341.core.util.ext.getDisplayName
import com.nyora.hasan72341.core.util.ext.toLocale
import com.nyora.hasan72341.core.util.ext.toLocaleOrNull
import com.nyora.hasan72341.js.NyoraJsMangaSource
import com.nyora.hasan72341.mihon.parsers.model.ContentType
import com.nyora.hasan72341.mihon.parsers.model.MangaParserSource
import com.nyora.hasan72341.mihon.parsers.model.MangaSource
import com.nyora.hasan72341.mihon.parsers.model.MangaSourceRef
import com.nyora.hasan72341.mihon.parsers.util.splitTwoParts
import java.util.Locale

data object LocalMangaSource : MangaSource {
	override val name = "LOCAL"
}

data object UnknownMangaSource : MangaSource {
	override val name = "UNKNOWN"
}

data object TestMangaSource : MangaSource {
	override val name = "TEST"
}

fun MangaSource(name: String?): MangaSource {
	when (name ?: return UnknownMangaSource) {
		UnknownMangaSource.name -> return UnknownMangaSource
		LocalMangaSource.name -> return LocalMangaSource
		TestMangaSource.name -> return TestMangaSource
	}
	if (name.startsWith(DataDrivenMangaSource.PREFIX)) {
		val catalogue = DataDrivenCatalogue.instance
		if (catalogue != null) {
			// Rows persisted under a renamed or retired spelling still have to reach their row, or
			// their history, favourites and downloads read as an unknown source.
			val id = name.removePrefix(DataDrivenMangaSource.PREFIX)
			val canonical = DataDrivenMangaSource.PREFIX + canonicalDataSourceId(id)
			LEGACY_SOURCE_RENAMES[name]?.let { renamed -> catalogue.find(renamed)?.let { return it } }
			catalogue.find(canonical)?.let { return it }
		}
		// An unknown data source is still a data source: keep its identity so a catalogue refresh
		// can resolve it later instead of orphaning the rows that reference it.
		return AnonymousMangaSource(name)
	}
	if (name.startsWith("MIHON_") || name.startsWith("mihon:") || name.all(Char::isDigit)) {
		return UnknownMangaSource
	}
	if (name.startsWith("content:")) {
		val parts = name.substringAfter(':').splitTwoParts('/') ?: return UnknownMangaSource
		return ExternalMangaSource(packageName = parts.first, authority = parts.second)
	}
	if (name.startsWith("JS_")) {
		return AnonymousMangaSource(name)
	}
	MangaParserSource.entries.forEach {
		if (it.name == name) return it
	}
	return UnknownMangaSource
}

private data class AnonymousMangaSource(override val name: String) : MangaSource

fun Collection<String>.toMangaSources() = map(::MangaSource)

fun ContentType.isHentai(): Boolean = this == ContentType.HENTAI_MANGA ||
	this == ContentType.HENTAI_NOVEL ||
	this == ContentType.HENTAI_VIDEO

fun MangaSource.isNsfw(): Boolean = when (val source = unwrap()) {
	is MangaSourceInfo -> source.mangaSource.isNsfw()
	is MangaParserSource -> source.contentType.toNyoraContentType().isHentai()
	// The catalogue marks adult rows explicitly; several of them carry a non-hentai content type.
	is DataDrivenMangaSource -> source.nsfw || source.contentType.isHentai()
	is NyoraJsMangaSource -> source.contentType.isHentai()
	is com.nyora.hasan72341.mihon.parsers.model.ContentSource -> source.contentType.isHentai()
	else -> false
}

@get:StringRes
val ContentType.titleResId
	get() = when (this) {
		ContentType.MANGA -> R.string.content_type_manga
		ContentType.HENTAI_MANGA,
		ContentType.HENTAI_NOVEL,
		ContentType.HENTAI_VIDEO -> R.string.content_type_hentai
		ContentType.COMICS -> R.string.content_type_comics
		ContentType.OTHER,
		ContentType.VIDEO -> R.string.content_type_other
		ContentType.MANHWA -> R.string.content_type_manhwa
		ContentType.MANHUA -> R.string.content_type_manhua
		ContentType.NOVEL -> R.string.content_type_novel
		ContentType.ONE_SHOT -> R.string.content_type_one_shot
		ContentType.DOUJINSHI -> R.string.content_type_doujinshi
		ContentType.IMAGE_SET -> R.string.content_type_image_set
		ContentType.ARTIST_CG -> R.string.content_type_artist_cg
		ContentType.GAME_CG -> R.string.content_type_game_cg
	}

tailrec fun MangaSource.unwrap(): MangaSource = if (this is MangaSourceInfo) {
	mangaSource.unwrap()
} else {
	this
}

fun MangaSource.getLocale(): Locale? = when (val source = unwrap()) {
	is MangaParserSource -> source.locale.toLocaleOrNull()
	is com.nyora.hasan72341.mihon.parsers.model.ContentSource -> source.locale.takeIf { it.isNotEmpty() }?.toLocaleOrNull()
	else -> null
}

fun MangaSource.getContentTypeOrNull(): ContentType? = when (val source = unwrap()) {
	is MangaParserSource -> source.contentType.toNyoraContentType()
	is com.nyora.hasan72341.mihon.parsers.model.ContentSource -> source.contentType
	else -> null
}

fun MangaSource.getSummary(context: Context): String? = when (val source = unwrap()) {
	is MangaParserSource -> {
		val type = context.getString(source.contentType.toNyoraContentType().titleResId)
		val locale = source.locale.toLocale().getDisplayName(context)
		context.getString(R.string.source_summary_pattern, type, locale)
	}

	is ExternalMangaSource -> context.getString(R.string.external_source)

	is NyoraJsMangaSource,
	is com.nyora.hasan72341.mihon.parsers.model.ContentSource -> {
		val contentType = source.contentType
		val type = context.getString(contentType.titleResId)
		val locale = source.locale.toLocaleOrNull()?.getDisplayName(context) ?: source.locale
		context.getString(R.string.source_summary_pattern, type, locale)
	}

	else -> null
}

fun MangaSource.getTitle(context: Context): String = when (val source = unwrap()) {
	is MangaParserSource -> source.title
	LocalMangaSource -> context.getString(R.string.local_storage)
	TestMangaSource -> context.getString(R.string.test_parser)
	is ExternalMangaSource -> source.resolveName(context)
	is DataDrivenMangaSource -> source.title
	is NyoraJsMangaSource -> source.title
	else -> when {
		source.name.startsWith("JS_") -> source.name.removePrefix("JS_").replace('_', ' ')
		else -> context.getString(R.string.unknown)
	}
}

fun MangaSourceRef.toMangaSource(): MangaSource = MangaSource(name)

fun MangaSourceRef.getTitle(context: Context): String = toMangaSource().getTitle(context)

fun String?.toMangaSourceRef(): MangaSourceRef = when (this) {
	null, "", UnknownMangaSource.name -> MangaSourceRef.Unknown
	LocalMangaSource.name -> MangaSourceRef.Local
	else -> when {
		startsWith("data:") -> runCatching { MangaSourceRef.Data(this) }.getOrDefault(MangaSourceRef.Unknown)
		startsWith("MIHON_") || startsWith("mihon:") || all(Char::isDigit) -> MangaSourceRef.Unknown
		startsWith("JS_") -> MangaSourceRef.Script(this)
		else -> MangaSourceRef.Parser(this)
	}
}

val MangaSource.isBroken: Boolean
	get() = (this as? MangaParserSource)?.isBroken == true

fun SpannableStringBuilder.appendIcon(textView: TextView, @DrawableRes resId: Int): SpannableStringBuilder {
	val icon = ContextCompat.getDrawable(textView.context, resId) ?: return this
	icon.setTintList(textView.textColors)
	val size = textView.lineHeight
	icon.setBounds(0, 0, size, size)
	val alignment = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
		ImageSpan.ALIGN_CENTER
	} else {
		ImageSpan.ALIGN_BOTTOM
	}
	return inSpans(ImageSpan(icon, alignment)) { append(' ') }
}

private fun org.koitharu.kotatsu.parsers.model.ContentType.toNyoraContentType(): ContentType = when (this) {
	org.koitharu.kotatsu.parsers.model.ContentType.MANGA -> ContentType.MANGA
	org.koitharu.kotatsu.parsers.model.ContentType.MANHWA -> ContentType.MANHWA
	org.koitharu.kotatsu.parsers.model.ContentType.MANHUA -> ContentType.MANHUA
	org.koitharu.kotatsu.parsers.model.ContentType.HENTAI -> ContentType.HENTAI_MANGA
	org.koitharu.kotatsu.parsers.model.ContentType.COMICS -> ContentType.COMICS
	org.koitharu.kotatsu.parsers.model.ContentType.NOVEL -> ContentType.NOVEL
	org.koitharu.kotatsu.parsers.model.ContentType.ONE_SHOT -> ContentType.ONE_SHOT
	org.koitharu.kotatsu.parsers.model.ContentType.DOUJINSHI -> ContentType.DOUJINSHI
	org.koitharu.kotatsu.parsers.model.ContentType.IMAGE_SET -> ContentType.IMAGE_SET
	org.koitharu.kotatsu.parsers.model.ContentType.ARTIST_CG -> ContentType.ARTIST_CG
	org.koitharu.kotatsu.parsers.model.ContentType.GAME_CG -> ContentType.GAME_CG
	org.koitharu.kotatsu.parsers.model.ContentType.OTHER -> ContentType.OTHER
}
