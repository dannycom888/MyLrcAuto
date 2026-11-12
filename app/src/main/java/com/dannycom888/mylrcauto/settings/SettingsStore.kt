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
    /** Keep the phone screen on while the lyrics screen is showing. */
    val keepScreenOn: Boolean = false,
    /** The big screen's backdrop: the current cover, blurred, or all black. Default BLACK. */
    val backgroundStyle: BackgroundStyle = BackgroundStyle.BLACK,
    /** How blurred the cover backdrop is, in px at the downscaled blur size. */
    val coverBlurPx: Int = SettingsStore.DEFAULT_COVER_BLUR_PX,
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
            lyricsFontSizeSp = coerceLyricsFontSizeSp(
                prefs.getInt(KEY_LYRICS_FONT_SIZE_SP, DEFAULT_LYRICS_FONT_SIZE_SP),
            ),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false),
            backgroundStyle = BackgroundStyle.fromName(prefs.getString(KEY_BACKGROUND_STYLE, null)),
            coverBlurPx = coerceCoverBlurPx(
                prefs.getInt(KEY_COVER_BLUR_PX, DEFAULT_COVER_BLUR_PX),
            ),
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

    fun setBackgroundStyle(style: BackgroundStyle) {
        prefs.edit().putString(KEY_BACKGROUND_STYLE, style.name).apply()
        _settings.value = current.copy(backgroundStyle = style)
    }

    /** Out-of-range values (e.g. from an older build) are clamped, never stored raw. */
    fun setCoverBlurPx(px: Int) {
        val value = coerceCoverBlurPx(px)
        prefs.edit().putInt(KEY_COVER_BLUR_PX, value).apply()
        _settings.value = current.copy(coverBlurPx = value)
    }

    companion object {
        const val PREFS_FILE = "mylrcauto-prefs"
        const val KEY_FOLDER_URI = "local_lyrics_folder_uri"
        const val KEY_APP_LANGUAGE = "app_language"
        const val KEY_LYRICS_FONT_SIZE_SP = "lyrics_font_size_sp"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_BACKGROUND_STYLE = "background_style"
        const val KEY_COVER_BLUR_PX = "cover_blur_px"

        /** Lyrics size slider range: 14–32 sp, default 17 sp (the original hardcoded size). */
        const val MIN_LYRICS_FONT_SIZE_SP = 14
        const val MAX_LYRICS_FONT_SIZE_SP = 32
        const val DEFAULT_LYRICS_FONT_SIZE_SP = 17

        fun coerceLyricsFontSizeSp(sp: Int): Int =
            sp.coerceIn(MIN_LYRICS_FONT_SIZE_SP, MAX_LYRICS_FONT_SIZE_SP)

        /**
         * Cover-blur slider range: 0–25 px, default 12 px. The radius is
         * in px at the downscaled blur size (the cover is shrunk to ≤ 48px
         * before blurring), so 12 is already a soft wash and 25 is nearly
         * a flat average colour; 0 is just the shrunken cover stretched,
         * which is soft on its own. Default 12 looks like Melisma's car
         * photos: clearly blurred, not sharp.
         */
        const val MIN_COVER_BLUR_PX = 0
        const val MAX_COVER_BLUR_PX = 25
        const val DEFAULT_COVER_BLUR_PX = 12

        fun coerceCoverBlurPx(px: Int): Int =
            px.coerceIn(MIN_COVER_BLUR_PX, MAX_COVER_BLUR_PX)
    }
}
