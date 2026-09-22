package com.nyora.hasan72341.core.prefs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Encrypted preferences that never take the process down. Keystore failures are common on
 * older devices (corrupt keysets after a restore, OEM keystore bugs); one reset is attempted,
 * after which a plain preferences file with the same name is used.
 */
object SecurePreferences {

	fun open(context: Context, name: String): SharedPreferences {
		encrypted(context, name)?.let { return it }
		deleteKeyset(context, name)
		encrypted(context, name)?.let { return it }
		Log.w(TAG, "Encrypted preferences unavailable, using plain preferences for $name")
		return context.getSharedPreferences("${name}_plain", Context.MODE_PRIVATE)
	}

	private fun encrypted(context: Context, name: String): SharedPreferences? = try {
		val masterKey = MasterKey.Builder(context)
			.setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
			.build()
		EncryptedSharedPreferences.create(
			context,
			name,
			masterKey,
			EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
			EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
		)
	} catch (e: Throwable) {
		Log.e(TAG, "Cannot open encrypted preferences $name", e)
		null
	}

	private fun deleteKeyset(context: Context, name: String) {
		runCatching {
			context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
			File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").delete()
		}
	}

	private const val TAG = "SecurePreferences"
}
