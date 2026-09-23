package com.nyora.hasan72341.core.network

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8

/**
 * Recovers a Madara chapter list when the site's `manga_get_chapters` admin-AJAX action stops
 * answering.
 *
 * Madara ships two endpoints for the same chapter list: the global `/wp-admin/admin-ajax.php`
 * action, and a per-title `<manga-url>ajax/chapters/`. A source picks between them, and the ones
 * that use the AJAX action increasingly answer it with 400 — WordPress hardening, a security
 * plugin, or the theme dropping the handler. Because a 4xx is fatal to the details request, the
 * whole title throws and cannot be opened at all, even though its chapters are one URL away.
 *
 * So the swap happens here: when the AJAX action fails, the request is re-issued against the
 * per-title endpoint and the result handed back in its place. The source's own chapter parsing —
 * selectors, dates, per-source overrides — runs untouched on it.
 *
 * The retry is reactive on purpose. Trying the per-title endpoint first would break the many sites
 * where admin-AJAX is the only one implemented, so nothing changes for a site whose AJAX action
 * still works.
 *
 * The AJAX payload carries only the numeric WordPress post id, so title documents are tapped on
 * their way through to remember `data-id` -> the url they were served from. Taking the url from the
 * *response* matters: it is the one that survived redirects, and only the canonical slug's
 * `ajax/chapters/` answers (`/manga/solo-leveling/` 404s where `/manga/solo-leveling-arise/`
 * returns 200).
 *
 * Ported from `nyora-shared-datadriven`'s `MadaraChapterFix.kt`. The recovery itself is unchanged;
 * what the phone adds is the gating in [rememberTitleUrl], because here the interceptor runs on the
 * shared client of a device with a few hundred MiB of heap rather than on a server.
 */
internal object MadaraChapterFix : Interceptor {

	private const val MAX_CACHED_TITLES = 512

	/**
	 * How far into a document the chapter holder is looked for. A Madara title page loads its
	 * chapter list over AJAX — that is the whole reason this interceptor exists — so the document
	 * itself is small; a bigger one is not a title page worth buffering on a phone.
	 */
	private const val MAX_TITLE_PEEK_BYTES = 512L * 1024L

	/** Bytes decoded either side of the marker — enough to cover the `<div …>` tag holding it. */
	private const val HOLDER_WINDOW_BYTES = 2048L

	private val HOLDER_MARKER = "manga-chapters-holder".encodeUtf8()

	/** The marker also appears in scripts referencing the holder, so a few occurrences are tried. */
	private const val MAX_MARKER_ATTEMPTS = 4

	/** `/manga/<slug>/` is two; a language or region prefix makes three. Past that it is not one. */
	private const val MAX_TITLE_PATH_SEGMENTS = 4

	/**
	 * `chapter-12`, `ch_3`, `episode-4-5` — the reader's pages, never a title document. Deliberately
	 * anchored at both ends: a wasted scan is cheap, while mistaking a title for a chapter would cost
	 * it the recovery, so `ch4rl0tte` and `chapter-1-the-beginning` are left in.
	 */
	private val CHAPTER_SLUG =
		Regex("""(chapter|chap|ch|episode|ep)[-_]?\d[\d._-]*""", RegexOption.IGNORE_CASE)

	// "<host>|<data-id>" -> the url the title document was served from
	private val titleUrls = object : LinkedHashMap<String, String>(64, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) =
			size > MAX_CACHED_TITLES
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val mangaId = chapterListMangaId(request)
		if (mangaId == null) {
			return rememberTitleUrl(chain.proceed(request))
		}

		val response = chain.proceed(request)
		if (response.isSuccessful) return response

		val titleUrl = cachedTitleUrl(response.request.url.host, mangaId) ?: return response

		// OkHttp allows only one response open per call, so the failed one has to be drained before
		// the retry goes out. Keep its bytes so it can still be handed back verbatim — with its
		// original status — if the retry fails too.
		val contentType = response.body.contentType()
		val failedBody = response.body.bytes()

		val retried = chain.proceed(
			request.newBuilder()
				.url(titleUrl.trimEnd('/') + "/ajax/chapters/")
				.header("Referer", titleUrl)
				.header("X-Requested-With", "XMLHttpRequest")
				.post(FormBody.Builder().build())
				.build(),
		)
		if (retried.isSuccessful) return retried

		retried.close()
		return response.newBuilder().body(failedBody.toResponseBody(contentType)).build()
	}

	/** The `manga` field of an `action=manga_get_chapters` admin-AJAX post, if that is what this is. */
	private fun chapterListMangaId(request: Request): String? {
		if (request.method != "POST" || !request.url.encodedPath.endsWith("/wp-admin/admin-ajax.php")) {
			return null
		}
		return mangaIdOf(request.body as? FormBody)
	}

	/**
	 * Caches `data-id` -> url for any Madara title document passing through.
	 *
	 * Only HTML that actually carries the chapter holder is remembered, so page images and JSON
	 * never get buffered on the way past. This sits on the one client every source shares, so the
	 * rest of the traffic has to stay cheap too: a request that cannot be a title permalink is not
	 * looked at, an over-long document is not buffered, and the documents that are scanned are
	 * searched as bytes — only the few KiB around the marker is ever decoded, instead of building a
	 * whole second copy of the page as a String beside the one Jsoup is about to build.
	 */
	private fun rememberTitleUrl(response: Response): Response {
		val request = response.request
		if (!response.isSuccessful || request.method != "GET" || !request.url.couldBeTitlePermalink()) {
			return response
		}
		val contentType = response.body.contentType()
		if (contentType?.type != "text" || contentType.subtype != "html") return response
		// -1 when the length is not declared (chunked or compressed); only a declared over-long
		// body is skipped outright.
		if (response.body.contentLength() > MAX_TITLE_PEEK_BYTES) return response

		// A broken body surfaces on the caller's own read of it; scanning must not be what raises it.
		val mangaId = runCatching { response.body.source().peekMangaId() }.getOrNull() ?: return response
		val key = "${request.url.host}|$mangaId"
		synchronized(titleUrls) { titleUrls[key] = request.url.toString() }
		return response
	}

	/**
	 * Madara serves a title as a plain permalink (`/manga/<slug>/`). What this rules out — anything
	 * with a query (`?s=` search, `?m_orderby=` listings), numbered listing pages and the reader's
	 * chapter pages — is the bulk of a session's HTML, and a global search fans exactly those out
	 * across every source at once.
	 *
	 * A title whose own slug reads like `chapter-1` loses the recovery; nothing else does.
	 */
	private fun HttpUrl.couldBeTitlePermalink(): Boolean {
		if (query != null) return false
		val segments = pathSegments.filter { it.isNotEmpty() }
		if (segments.isEmpty() || segments.size > MAX_TITLE_PATH_SEGMENTS) return false
		val slug = segments.last()
		// A title slug always carries letters; the `2` of `/manga/page/2/` does not.
		return slug.any { it.isLetter() } && !CHAPTER_SLUG.matches(slug)
	}

	/**
	 * Byte-level scan for the chapter holder, bounded by [MAX_TITLE_PEEK_BYTES]. Peeking leaves the
	 * body itself untouched for the caller.
	 */
	private fun BufferedSource.peekMangaId(): String? {
		val peek = peek()
		var searchFrom = 0L
		repeat(MAX_MARKER_ATTEMPTS) {
			// Stops at the marker instead of reading to the bound, so a title page costs only the
			// bytes up to its holder.
			val marker = peek.indexOf(HOLDER_MARKER, searchFrom, MAX_TITLE_PEEK_BYTES)
			if (marker < 0L) return null
			peek.request(marker + HOLDER_WINDOW_BYTES)
			val buffered = peek.buffer
			val from = (marker - HOLDER_WINDOW_BYTES).coerceAtLeast(0L)
			val to = (marker + HOLDER_WINDOW_BYTES).coerceAtMost(buffered.size)
			val window = Buffer()
			buffered.copyTo(window, from, to - from)
			// A hit in a script that merely names the holder yields no id; the tag itself does.
			extractMangaId(window.readUtf8())?.let { return it }
			searchFrom = marker + 1L
		}
		return null
	}

	private fun cachedTitleUrl(host: String, mangaId: String): String? =
		synchronized(titleUrls) { titleUrls["$host|$mangaId"] }
}

/** Pulls the `manga` field out of a `action=manga_get_chapters&manga=<id>` form. */
internal fun mangaIdOf(body: FormBody?): String? {
	if (body == null) return null
	var isChapterAction = false
	var mangaId: String? = null
	for (i in 0 until body.size) {
		when (body.name(i)) {
			"action" -> isChapterAction = body.value(i) == "manga_get_chapters"
			"manga" -> mangaId = body.value(i).takeIf { it.isNotBlank() }
		}
	}
	return if (isChapterAction) mangaId else null
}

/** Reads `data-id` off `<div id="manga-chapters-holder" data-id="47353">` in a title document. */
internal fun extractMangaId(html: String): String? {
	val holder = CHAPTERS_HOLDER.find(html)?.value ?: return null
	return DATA_ID.find(holder)?.groupValues?.get(1)
}

private val CHAPTERS_HOLDER =
	Regex("""<div[^>]*\bid=["']manga-chapters-holder["'][^>]*>""", RegexOption.IGNORE_CASE)
private val DATA_ID = Regex("""\bdata-id=["'](\d+)["']""", RegexOption.IGNORE_CASE)
