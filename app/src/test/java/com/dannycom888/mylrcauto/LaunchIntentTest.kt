package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.car.describeLaunchIntent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The item-7 diagnostic's description of a launch intent, as pure
 * string formatting — the session extracts the strings from the
 * `Intent` and this is what goes in the log, so the follow-up can
 * see whether an icon tap and a card tap ever arrive with
 * different intents.
 */
class LaunchIntentTest {

    @Test
    fun launcherIntentDescribesActionAndCategory() {
        assertEquals(
            "launch intent: action=android.intent.action.MAIN " +
                "categories=[android.intent.category.LAUNCHER] extras=[]",
            describeLaunchIntent(
                action = "android.intent.action.MAIN",
                categories = setOf("android.intent.category.LAUNCHER"),
                extrasKeys = emptySet(),
            ),
        )
    }

    @Test
    fun markerExtrasAreListedSorted() {
        // A session-activity marker (the follow-up's candidate
        // distinguisher) would show up here as an extras key.
        assertEquals(
            "launch intent: action=com.dannycom888.mylrcauto.OPEN_LYRICS " +
                "categories=[] extras=[from_media_card]",
            describeLaunchIntent(
                action = "com.dannycom888.mylrcauto.OPEN_LYRICS",
                categories = null,
                extrasKeys = setOf("from_media_card"),
            ),
        )
    }

    @Test
    fun nullsDescribeAsNoneAndEmpty() {
        assertEquals(
            "launch intent: action=(none) categories=[] extras=[]",
            describeLaunchIntent(action = null, categories = null, extrasKeys = null),
        )
    }
}
