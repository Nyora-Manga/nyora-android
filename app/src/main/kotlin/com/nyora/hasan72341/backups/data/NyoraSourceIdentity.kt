package com.nyora.hasan72341.backups.data

import java.util.Locale

object NyoraSourceIdentity {
	fun canonicalize(stored: String): String? = when {
		stored.startsWith("data:") -> stored.takeIf {
			runCatching { NyoraBackupIdentity.requireRemoteSourceId(it) }.isSuccess
		}
		stored.startsWith("JS_") -> "data:" + stored.removePrefix("JS_")
			.lowercase(Locale.ROOT)
			.replace('_', '-')
		stored.startsWith("MIHON_") || stored.startsWith("mihon:") || stored.all(Char::isDigit) -> null
		else -> null
	}
}
