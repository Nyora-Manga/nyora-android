package com.nyora.hasan72341.sync.supabase

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.nyora.hasan72341.core.prefs.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupabaseConfig @Inject constructor(
	@ApplicationContext private val context: Context
) {
	companion object {
		const val INITIAL_SYNC_TIMESTAMP = "1970-01-01T00:00:00Z"
	}

	private val prefs: SharedPreferences = SecurePreferences.open(context, "supabase_secure_prefs")

	var url: String = ""
	var anonKey: String = ""
	var accessToken: String = ""
	var refreshToken: String = ""
	var userId: String = ""
	var lastSyncTimestamp: String = INITIAL_SYNC_TIMESTAMP
	var firstLoginHandled: Boolean = false

	val isConfigured get() = url.isNotBlank() && anonKey.isNotBlank()
	val isAuthenticated get() = accessToken.isNotBlank() && userId.isNotBlank()

	init {
		load()
	}

	fun load() {
		url = prefs.getString("url", "") ?: ""
		anonKey = prefs.getString("anon_key", "") ?: ""
		accessToken = prefs.getString("access_token", "") ?: ""
		refreshToken = prefs.getString("refresh_token", "") ?: ""
		userId = prefs.getString("user_id", "") ?: ""
		lastSyncTimestamp = prefs.getString("last_sync_timestamp", INITIAL_SYNC_TIMESTAMP)
			?: INITIAL_SYNC_TIMESTAMP
		firstLoginHandled = prefs.getBoolean("first_login_handled", false)
	}

	fun configure(url: String, anonKey: String) {
		this.url = url
		this.anonKey = anonKey
		prefs.edit {
			putString("url", url)
			putString("anon_key", anonKey)
		}
	}

	fun saveTokens() {
		prefs.edit {
			putString("access_token", accessToken)
			putString("refresh_token", refreshToken)
			putString("user_id", userId)
			putString("last_sync_timestamp", lastSyncTimestamp)
			putBoolean("first_login_handled", firstLoginHandled)
		}
	}

	fun resetSyncCursor() {
		lastSyncTimestamp = INITIAL_SYNC_TIMESTAMP
		prefs.edit {
			putString("last_sync_timestamp", INITIAL_SYNC_TIMESTAMP)
		}
	}

	fun clearTokens() {
		accessToken = ""
		refreshToken = ""
		userId = ""
		lastSyncTimestamp = INITIAL_SYNC_TIMESTAMP
		firstLoginHandled = false
		prefs.edit {
			remove("access_token")
			remove("refresh_token")
			remove("user_id")
			remove("last_sync_timestamp")
			remove("first_login_handled")
		}
	}

	fun parseUserIdFromJwt(token: String): String = runCatching {
		val payload = token.split(".")[1]
		val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
		val decoded = String(java.util.Base64.getUrlDecoder().decode(padded))
		Regex(""""sub"\s*:\s*"([^"]+)""").find(decoded)?.groupValues?.get(1) ?: ""
	}.getOrDefault("")

	val email: String get() = runCatching {
		if (accessToken.isBlank()) return@runCatching ""
		val payload = accessToken.split(".")[1]
		val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
		val decoded = String(java.util.Base64.getUrlDecoder().decode(padded))
		Regex(""""email"\s*:\s*"([^"]+)""").find(decoded)?.groupValues?.get(1) ?: ""
	}.getOrDefault("")
}
