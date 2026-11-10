package com.dannycom888.mylrcauto.util

import android.content.Context
import android.content.res.Configuration
import com.dannycom888.mylrcauto.settings.AppLanguage
import com.dannycom888.mylrcauto.settings.SettingsStore
import java.util.Locale

/**
 * The app's own menu language, chosen in Settings.
 *
 * Android 13 added a per-app language setting in the system; older Android
 * has none, so the app does it itself.
 *
 * How it works, and why not AppCompat: `AppCompatDelegate.setApplicationLocales`
 * only takes effect through `AppCompatActivity`, and this app's activity is a
 * plain `ComponentActivity` on a Compose theme. The classic alternative needs
 * no dependency: wrap the base context in `attachBaseContext` with a
 * [Configuration] carrying the chosen locale, so every resource the activity
 * (or service) reads resolves in that language. It is applied in
 * [com.dannycom888.mylrcauto.MyLrcAutoApp],
 * [com.dannycom888.mylrcauto.ui.MainActivity] and
 * [com.dannycom888.mylrcauto.car.CarAppService] — the Application wrap alone
 * is not enough, because the system hands each activity and service its own
 * base context with the device configuration rather than the Application's.
 *
 * Changing the choice takes effect by recreating the activity; the process
 * stays alive, so playback tracking is not interrupted. A car screen already
 * connected when the choice changes picks it up on its next connection.
 */
object AppLocale {

    /** The [Locale] a choice resolves to, or null for "follow the system". */
    fun localeFor(choice: AppLanguage): Locale? = when (choice) {
        AppLanguage.SYSTEM -> null
        AppLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
        AppLanguage.ENGLISH -> Locale.ENGLISH
    }

    /**
     * Reads the stored choice straight from SharedPreferences.
     *
     * `attachBaseContext` runs before `onCreate`, so the settings store does
     * not exist yet at the moment this is needed. An unknown stored value
     * falls back to the system language.
     */
    fun readChoice(context: Context): AppLanguage {
        val name = context
            .getSharedPreferences(SettingsStore.PREFS_FILE, Context.MODE_PRIVATE)
            .getString(SettingsStore.KEY_APP_LANGUAGE, null) ?: return AppLanguage.SYSTEM
        return runCatching { AppLanguage.valueOf(name) }.getOrDefault(AppLanguage.SYSTEM)
    }

    /**
     * A context whose resources resolve in the chosen language.
     *
     * Returns the base context untouched for "follow the system". Also sets
     * the default locale, so things that format outside resources (for
     * example a translated language name) still use the chosen language.
     */
    fun wrap(base: Context): Context {
        val locale = localeFor(readChoice(base)) ?: return base
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
