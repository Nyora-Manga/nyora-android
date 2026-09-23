package com.nyora.hasan72341.core.prefs

import android.content.Context
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import androidx.core.content.edit
import com.nyora.hasan72341.core.SourcePatches
import com.nyora.hasan72341.core.db.migrations.upgradedStoredSourceName
import com.nyora.hasan72341.core.parser.datadriven.LEGACY_SOURCE_RENAMES
import com.nyora.hasan72341.core.util.ext.getEnumValue
import com.nyora.hasan72341.core.util.ext.putAll
import com.nyora.hasan72341.core.util.ext.putEnumValue
import com.nyora.hasan72341.core.util.ext.sanitizeHeaderValue
import com.nyora.hasan72341.mihon.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.config.ConfigKey
import com.nyora.hasan72341.mihon.parsers.config.ConfigKey as NativeConfigKey
import com.nyora.hasan72341.mihon.parsers.model.MangaSource
import com.nyora.hasan72341.mihon.parsers.model.SortOrder
import com.nyora.hasan72341.mihon.parsers.util.ifNullOrEmpty
import com.nyora.hasan72341.mihon.parsers.util.nullIfEmpty
import com.nyora.hasan72341.settings.utils.validation.DomainValidator
import java.io.File

class SourceSettings(context: Context, private val source: MangaSource) : MangaSourceConfig {

	private val prefs = context.getSharedPreferences(preferencesName(source.name), Context.MODE_PRIVATE)

	var defaultSortOrder: SortOrder?
		get() = prefs.getEnumValue(KEY_SORT_ORDER, SortOrder::class.java)
		set(value) = prefs.edit { putEnumValue(KEY_SORT_ORDER, value) }

	val isSlowdownEnabled: Boolean
		get() = prefs.getBoolean(KEY_SLOWDOWN, false)

	val isCaptchaNotificationsDisabled: Boolean
		get() = prefs.getBoolean(KEY_NO_CAPTCHA, false)

	@Suppress("UNCHECKED_CAST")
	override fun <T> get(key: ConfigKey<T>): T {
		return when (key) {
			is ConfigKey.UserAgent -> prefs.getString(key.key, key.defaultValue)
				.ifNullOrEmpty { key.defaultValue }
				.sanitizeHeaderValue()

			// For a relocated source whose baked-in domain is dead, SourcePatches.DOMAIN_OVERRIDES
			// supplies the current live domain; it is the effective default (a user-set domain pref
			// still wins). Mirrors the helper's OverrideSourceConfig — without this the override was inert.
			is ConfigKey.Domain -> {
				val fallback = SourcePatches.DOMAIN_OVERRIDES[source.name] ?: key.defaultValue
				prefs.getString(key.key, fallback)
					?.trim()
					?.takeIf { DomainValidator.isValidDomain(it) }
					?: fallback
			}

			is ConfigKey.ShowSuspiciousContent -> prefs.getBoolean(key.key, key.defaultValue)
			is ConfigKey.SplitByTranslations -> prefs.getBoolean(key.key, key.defaultValue)
			is ConfigKey.PreferredImageServer -> prefs.getString(key.key, key.defaultValue)?.nullIfEmpty()
			is ConfigKey.DisableUpdateChecking -> prefs.getBoolean(key.key, key.defaultValue)
			is ConfigKey.InterceptCloudflare -> prefs.getBoolean(key.key, key.defaultValue)
		} as T
	}

	operator fun <T> set(key: ConfigKey<T>, value: T) = prefs.edit {
		when (key) {
			is ConfigKey.Domain -> putString(key.key, value as String?)
			is ConfigKey.ShowSuspiciousContent -> putBoolean(key.key, value as Boolean)
			is ConfigKey.UserAgent -> putString(key.key, (value as String?)?.sanitizeHeaderValue())
			is ConfigKey.SplitByTranslations -> putBoolean(key.key, value as Boolean)
			is ConfigKey.PreferredImageServer -> putString(key.key, value as String? ?: "")
			is ConfigKey.InterceptCloudflare -> putBoolean(key.key, value as Boolean)
			is ConfigKey.DisableUpdateChecking -> {
				// Read-only parser flag; keep it parser-controlled.
			}
		}
	}

	@Suppress("UNCHECKED_CAST")
	@JvmName("getNative")
	operator fun <T> get(key: NativeConfigKey<T>): T {
		return when (key) {
			is NativeConfigKey.UserAgent -> prefs.getString(key.key, key.defaultValue)
				.ifNullOrEmpty { key.defaultValue }
				.sanitizeHeaderValue()

			is NativeConfigKey.Domain -> prefs.getString(key.key, key.defaultValue)
				?.trim()
				?.takeIf { DomainValidator.isValidDomain(it) }
				?: key.defaultValue

			is NativeConfigKey.ShowSuspiciousContent -> prefs.getBoolean(key.key, key.defaultValue)
			is NativeConfigKey.SplitByTranslations -> prefs.getBoolean(key.key, key.defaultValue)
			is NativeConfigKey.PreferredImageServer -> prefs.getString(key.key, key.defaultValue)?.nullIfEmpty()
			is NativeConfigKey.Text -> prefs.getString(key.key, key.defaultValue).ifNullOrEmpty { key.defaultValue }
			is NativeConfigKey.Toggle -> prefs.getBoolean(key.key, key.defaultValue)
			is NativeConfigKey.PreferredLanguage -> prefs.getString(key.key, key.defaultValue) ?: key.defaultValue
		} as T
	}

	@Suppress("UNCHECKED_CAST")
	@JvmName("setNative")
	operator fun <T> set(key: NativeConfigKey<T>, value: T) = prefs.edit {
		when (key) {
			is NativeConfigKey.Domain -> putString(key.key, value as String?)
			is NativeConfigKey.ShowSuspiciousContent -> putBoolean(key.key, value as Boolean)
			is NativeConfigKey.UserAgent -> putString(key.key, (value as String?)?.sanitizeHeaderValue())
			is NativeConfigKey.SplitByTranslations -> putBoolean(key.key, value as Boolean)
			is NativeConfigKey.PreferredImageServer -> putString(key.key, value as String? ?: "")
			is NativeConfigKey.Text -> putString(key.key, value as String?)
			is NativeConfigKey.Toggle -> putBoolean(key.key, value as Boolean)
			is NativeConfigKey.PreferredLanguage -> putString(key.key, value as String?)
		}
	}

	fun subscribe(listener: OnSharedPreferenceChangeListener) {
		prefs.registerOnSharedPreferenceChangeListener(listener)
	}

	fun unsubscribe(listener: OnSharedPreferenceChangeListener) {
		prefs.unregisterOnSharedPreferenceChangeListener(listener)
	}

	companion object {

		const val KEY_DOMAIN = "domain"
		const val KEY_NO_CAPTCHA = "no_captcha"
		const val KEY_SLOWDOWN = "slowdown"
		const val KEY_SORT_ORDER = "sort_order"

		/**
		 * Move the settings of the sources this build renamed into the file it now reads them from.
		 *
		 * Each source keeps its settings in a file named after the source, so a renamed source would
		 * otherwise come up with its defaults and lose a configured mirror domain or user agent. Two
		 * lineages have to move: the spellings database schema 34 renamed, and the `DD_` and `JS_`
		 * files a shipped install still holds. Only a source that has settings and has not been
		 * configured under its new name is moved.
		 */
		fun migrateRenamedPreferenceFiles(context: Context) {
			renamedPreferenceSources(preferenceFileNames(context)).forEach { (oldName, newName) ->
				if (oldName == newName) return@forEach
				val oldPrefs = context.getSharedPreferences(preferencesName(oldName), Context.MODE_PRIVATE)
				val values = oldPrefs.all
				if (values.isEmpty()) return@forEach
				val newPrefs = context.getSharedPreferences(preferencesName(newName), Context.MODE_PRIVATE)
				if (newPrefs.all.isNotEmpty()) return@forEach
				newPrefs.edit { putAll(values) }
				oldPrefs.edit { clear() }
			}
		}

		private fun preferenceFileNames(context: Context): List<String> =
			File(context.applicationInfo.dataDir, PREFERENCES_DIRECTORY).list()?.asList().orEmpty()

		private fun preferencesName(sourceName: String) = sourceName.replace(File.separatorChar, '$')
	}
}

/** Directory the framework keeps every [android.content.SharedPreferences] file of the app in. */
private const val PREFERENCES_DIRECTORY = "shared_prefs"

/** Suffix the framework gives every preference file it writes. */
private const val PREFERENCES_FILE_SUFFIX = ".xml"

/** Source name prefixes the shipped v2.1.6 (`JS_`) and v2.6-v2.7.3 (`DD_`) builds wrote. */
private val SHIPPED_SOURCE_PREFIXES = listOf("DD_", "JS_")

/**
 * The preference files to move, as old source name to new source name: first every shipped `DD_` or
 * `JS_` spelling in [preferenceFileNames], then the spellings database schema 34 renamed.
 *
 * The listing is sorted because two shipped spellings can name the same site (`DD_MANGANATO` and
 * `JS_MANGANATO_GG` both upgrade to `data:manganato`) and only the first of them is moved; the file
 * system does not promise an order, so the migration picks one.
 */
internal fun renamedPreferenceSources(preferenceFileNames: Iterable<String>): List<Pair<String, String>> {
	val shipped = preferenceFileNames.sorted().mapNotNull { fileName ->
		if (!fileName.endsWith(PREFERENCES_FILE_SUFFIX)) return@mapNotNull null
		val sourceName = fileName.removeSuffix(PREFERENCES_FILE_SUFFIX)
		if (SHIPPED_SOURCE_PREFIXES.none(sourceName::startsWith)) return@mapNotNull null
		upgradedStoredSourceName(sourceName)?.let { sourceName to it }
	}
	return shipped + LEGACY_SOURCE_RENAMES.toList()
}
