package com.dannycom888.mylrcauto.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dannycom888.mylrcauto.MyLrcAutoApp
import com.dannycom888.mylrcauto.settings.AppLanguage
import com.dannycom888.mylrcauto.ui.theme.MyLrcAutoTheme
import com.dannycom888.mylrcauto.util.AppLocale

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MyLrcAutoApp).container
        setContent {
            MyLrcAutoTheme {
                // Language change → recreate so the new locale applies.
                // The first value seen after (re)creation is the language
                // this activity was already created with, so it must not
                // trigger another recreate — otherwise this loops forever.
                // `appliedLanguage` resets to null on every recreation, so
                // the fresh first emission is always absorbed, never acted on.
                val settings by container.settings.settings.collectAsStateWithLifecycle()
                var appliedLanguage by remember { mutableStateOf<AppLanguage?>(null) }
                LaunchedEffect(settings.appLanguage) {
                    if (appliedLanguage == null) {
                        appliedLanguage = settings.appLanguage
                    } else if (appliedLanguage != settings.appLanguage) {
                        appliedLanguage = settings.appLanguage
                        recreate()
                    }
                }
                LyricsScreen(container)
            }
        }
    }
}
