package com.nyora.hasan72341.core.network

import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

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
 * Ported verbatim from `nyora-shared-datadriven`'s `MadaraChapterFix.kt`.
 */
internal object MadaraChapterFix : Interceptor {

	private const val MAX_CACHED_TITLES = 512
	private const val MAX_TITLE_PEEK_BYTES = 2L * 1024L * 1024L

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
	 * Only HTML that actually carries the chapter holder is read, so page images and JSON never get
	 * buffered on the way past.
	 */
	private fun rememberTitleUrl(response: Response): Response {
		if (!response.isSuccessful || response.request.method != "GET") return response
		val contentType = response.body.contentType()
		if (contentType?.type != "text" || contentType.subtype != "html") return response

		val peeked = response.peekBody(MAX_TITLE_PEEK_BYTES).string()
		val mangaId = extractMangaId(peeked) ?: return response
		val key = "${response.request.url.host}|$mangaId"
		synchronized(titleUrls) { titleUrls[key] = response.request.url.toString() }
		return response
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
