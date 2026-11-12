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
    /** Base lyrics font size in sp (non-current lines; the current line is +5 sp). */
    val lyricsFontSizeSp: Int = SettingsStore.DEFAULT_LYRICS_FONT_SIZE_SP,
    /** Which font Chinese lyrics draw in (Settings → 歌词字体). */
    val lyricsFont: LyricsFontChoice = SettingsStore.DEFAULT_LYRICS_FONT,
    /** Keep the phone screen on while the lyrics screen is showing. */
    val keepScreenOn: Boolean = false,
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
        val fontName = prefs.getString(KEY_LYRICS_FONT, null)
        val lyricsFont = runCatching {
            if (fontName == null) DEFAULT_LYRICS_FONT else LyricsFontChoice.valueOf(fontName)
        }.getOrDefault(DEFAULT_LYRICS_FONT)
        return Settings(
            localLyricsFolderUri = prefs.getString(KEY_FOLDER_URI, null),
            appLanguage = language,
            lyricsFontSizeSp = coerceLyricsFontSizeSp(
                prefs.getInt(KEY_LYRICS_FONT_SIZE_SP, DEFAULT_LYRICS_FONT_SIZE_SP),
            ),
            lyricsFont = lyricsFont,
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false),
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

    /** Out-of-range values (e.g. from an older build) are clamped, never stored raw. */
    fun setLyricsFontSizeSp(sp: Int) {
        val value = coerceLyricsFontSizeSp(sp)
        prefs.edit().putInt(KEY_LYRICS_FONT_SIZE_SP, value).apply()
        _settings.value = current.copy(lyricsFontSizeSp = value)
    }

    fun setKeepScreenOn(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
        _settings.value = current.copy(keepScreenOn = enabled)
    }

    fun setLyricsFont(choice: LyricsFontChoice) {
        prefs.edit().putString(KEY_LYRICS_FONT, choice.name).apply()
        _settings.value = current.copy(lyricsFont = choice)
    }

    companion object {
        const val PREFS_FILE = "mylrcauto-prefs"
        const val KEY_FOLDER_URI = "local_lyrics_folder_uri"
        const val KEY_APP_LANGUAGE = "app_language"
        const val KEY_LYRICS_FONT_SIZE_SP = "lyrics_font_size_sp"
        const val KEY_LYRICS_FONT = "lyrics_font"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"

        /** Lyrics size slider range: 14–32 sp, default 17 sp (the original hardcoded size). */
        const val MIN_LYRICS_FONT_SIZE_SP = 14
        const val MAX_LYRICS_FONT_SIZE_SP = 32
        const val DEFAULT_LYRICS_FONT_SIZE_SP = 17

        /** Default lyrics font: Bauhaus, so adding the choice changes nothing on upgrade. */
        val DEFAULT_LYRICS_FONT: LyricsFontChoice = LyricsFontChoice.BAUHAUS

        fun coerceLyricsFontSizeSp(sp: Int): Int =
            sp.coerceIn(MIN_LYRICS_FONT_SIZE_SP, MAX_LYRICS_FONT_SIZE_SP)
    }
}
