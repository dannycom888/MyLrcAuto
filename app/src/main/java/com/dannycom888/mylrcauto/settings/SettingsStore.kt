package com.dannycom888.mylrcauto.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the app remembers across launches. */
data class Settings(
    /** String form of the picked music folder's tree URI, or null when not picked yet. */
    val localLyricsFolderUri: String? = null,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
)

/**
 * SharedPreferences-backed settings.
 *
 * Plain SharedPreferences rather than DataStore: two values, read on the main
 * thread at startup, and [AppLocale] has to read the language choice before
 * any store exists.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    private fun read(): Settings {
        val languageName = prefs.getString(KEY_APP_LANGUAGE, null)
        val language = runCatching {
            if (languageName == null) AppLanguage.SYSTEM else AppLanguage.valueOf(languageName)
        }.getOrDefault(AppLanguage.SYSTEM)
        return Settings(
            localLyricsFolderUri = prefs.getString(KEY_FOLDER_URI, null),
            appLanguage = language,
        )
    }

    fun setFolderUri(uri: String?) {
        prefs.edit().putString(KEY_FOLDER_URI, uri).apply()
        _settings.value = current.copy(localLyricsFolderUri = uri)
    }

    fun setAppLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_APP_LANGUAGE, language.name).apply()
        _settings.value = current.copy(appLanguage = language)
    }

    companion object {
        const val PREFS_FILE = "mylrcauto-prefs"
        const val KEY_FOLDER_URI = "local_lyrics_folder_uri"
        const val KEY_APP_LANGUAGE = "app_language"
    }
}
