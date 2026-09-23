package com.nyora.hasan72341.core.parser

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * MangaFire's current API request signer.
 *
 * The website canonicalizes query fields by their unescaped field name, appends them to the
 * API-relative path, then applies three chained substitution stages. Keeping this isolated from the
 * repository makes every protected endpoint use the same implementation and gives one place to
 * update when MangaFire rotates a build.
 *
 * Ported from `nyora-shared-datadriven`'s `MangaFireVrf.kt`. Base64 goes through okio rather than
 * `java.util.Base64`, which needs API 26.
 */
internal object MangaFireVrf {

	private class Stage(
		val substitution: ByteArray,
		val key: ByteArray,
		val initialPrevious: Int,
	)

	private val stages: List<Stage> by lazy(LazyThreadSafetyMode.PUBLICATION) {
		listOf(
			Stage(
				substitution = decode(
					"yINlmUNho8VYJT+ibTIP+9ESiULpVEtMOoD6U6lRE0R/xwXo/Xp9NrUgC4cw/Lmo33vUyjUE40kUoEWIr/fxfNNcq2s79ShQ5NhNrFnJ4hXPwOu/SuXzIbuTQKGFvfm08E9jvCfqAtoDqvQq3dVWPQFmJjgvkISBeXY3BgANR+yVnjGbcxZ47d6kLNfZPIayTq3/YGySb1KuVZodWp/WGNAO5pfMcpaK53Hhs0allBszaMaxuouOwdxbwgxIw6YunSsXjI05Yi0j9j4eHKfSXR8Ifo/Od+8iamRfCXTyvm7NGRGYdcQ0ywcK/u6RXhrbcCm4t2eCtrDgQVecJGkQ+A==",
				),
				key = decode("0Ec58JOY3uBzJK9m3zqIOpdlF7UFiax9DmA="),
				initialPrevious = 90,
			),
			Stage(
				substitution = decode(
					"IUFltCxD3Oc2cwCgkJffthaOg9cgPUb0LgW6H/VtfcF0kc5F25t+aWj6JH9VOhOaY0rAFdUxlDnl5BLNvwEJvQtP5qcw7vdb/K+chnbwnspSHT8mz5lqwz41TezG0hkO06FTjJZhsyNuFLDpD2ZZxQj/QIRcF90zpmQ7Byu483WsQqUE0C342HL+JXngRB6fRzxRyVTaKu83h7UYTJ0QMt6ixFh6S3F8gqkKwrGTL3jHNBsD45UnifK8+RGtishQV2K3rujLKEkiZxpr2dYcudFW4oFsDKhad3CLBvuyTqsCo4B7mL5IKQ1vXo/MOOvq1I1d8ar9X6Ttu5KF4fZgiA==",
				),
				key = decode("AAdjb1iPY8CiDmq9H34tKTBF8a3oDQ=="),
				initialPrevious = 53,
			),
			Stage(
				substitution = decode(
					"NQHlu1/wVO5EmkwQymF810qqY2xG1k2obcas4Z9mCsPEIFl9pRIjFxbJ7ybMHbBckT5Ton85E0FOeHezbh/mjlEYpmpnlXOS8dgrqeq2KfxImTh1YK9y0PeMNhzA1OQzSY9brYOJq/l2QnE/hwOeZIhPixVSKIUlDb5vLcH6RWKxkIEMuP0bDwIqQ71AJJaEaMJL7A6YtyIwoRT+L5v4aZzodN/0+3nOGsfblFjgxSfPzVDjNFeNl5P26+kEC/8AHgdrpAbt3hHz3HrRN1Y6e+JHgF7ncFWnoF0y3THL1S71WgWGCa6KtSzTCCG58n68nTyj2T3Sshk7utqCtMi/ZQ==",
				),
				key = decode("DELOJgPsVaCcblDtTGMdHzM="),
				initialPrevious = 186,
			),
		).also { configuredStages ->
			check(configuredStages.all { it.substitution.size == 256 && it.key.isNotEmpty() }) {
				"Invalid MangaFire VRF configuration"
			}
		}
	}

	fun token(relativePath: String, params: List<Pair<String, String>> = emptyList()): String {
		require(relativePath.startsWith('/')) { "MangaFire API path must be relative and start with '/'" }
		val canonical = canonicalQuery(params)
		val payload = if (canonical.isEmpty()) relativePath else "$relativePath?$canonical"
		var bytes = payload.toByteArray(Charsets.UTF_8)

		stages.forEach { stage ->
			var previous = stage.initialPrevious
			bytes = ByteArray(bytes.size) { index ->
				val plain = bytes[index].toInt() and 0xff
				val key = stage.key[index % stage.key.size].toInt() and 0xff
				val encrypted = stage.substitution[plain xor key xor previous]
				previous = encrypted.toInt() and 0xff
				encrypted
			}
		}

		// URL-safe and unpadded, as the site's own client emits it.
		return bytes.toByteString().base64Url().trimEnd('=')
	}

	/** Visible for the test that pins this signer against the shared clients' golden tokens. */
	internal fun canonicalQuery(params: List<Pair<String, String>>): String =
		params.withIndex()
			.sortedWith(compareBy<IndexedValue<Pair<String, String>>> { it.value.first }.thenBy { it.index })
			.joinToString("&") { (_, field) -> "${field.first}=${field.second}" }

	private fun decode(value: String): ByteArray =
		requireNotNull(value.decodeBase64()) { "Invalid MangaFire VRF configuration" }.toByteArray()
}
