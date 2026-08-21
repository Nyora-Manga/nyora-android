package com.nyora.hasan72341.js

import com.nyora.hasan72341.mihon.parsers.model.ContentSource
import com.nyora.hasan72341.mihon.parsers.model.ContentType
import com.nyora.hasan72341.mihon.parsers.model.MangaSource
import java.util.Locale

/**
 * A JS-bundle parser exposed as a first-class [MangaSource] (mirrors `MihonMangaSource`).
 *
 * [jsId] is the raw id the bundle's `NyoraParsers.getParser(id)` expects (e.g. "DANKE");
 * [name] is namespaced "JS_<id>" so it can't collide with native `MangaParserSource` names
 * and is recognised by the repository factory + source rehydration.
 */
data class NyoraJsMangaSource(
    val jsId: String,
    val title: String,
    override val locale: String,
    val domain: String,
    val nsfw: Boolean,
) : MangaSource, ContentSource {
	val portableName: String
		get() = "data:" + jsId.lowercase(Locale.ROOT).replace('_', '-')

    override val name: String get() = "JS_$jsId"

    override val contentType: ContentType
        get() = if (nsfw) ContentType.HENTAI_MANGA else ContentType.MANGA
}
