package com.nyora.hasan72341.backups.data

object NyoraSourceIdentity {
	/** Runtime and portable writes accept canonical identities only. */
	fun canonicalize(stored: String): String? =
		runCatching { NyoraBackupIdentity.requireRemoteSourceId(stored) }.getOrNull()
}
