package com.dannycom888.mylrcauto.car

import com.dannycom888.mylrcauto.lyrics.LyricsDocument
import com.dannycom888.mylrcauto.lyrics.LyricsState

/**
 * What a car screen should say, right now.
 *
 * Deliberately a value rather than a template: everything about which words
 * belong on a car screen is decided here, in a pure function of the lyrics
 * state and the playhead, and the screen only turns the answer into rows.
 * That is what makes this testable at all — the alternative is a template
 * builder that can only be judged by plugging a phone into a car.
 *
 * This is the *plain* car screen: two lines, never more, enforced by there
 * being nowhere in the type to put a third. It is what a car gets when the
 * host will not hand the app a surface to draw on. See [CarLyricsContent]
 * for the full version.
 *
 * Note on the name: androidx.car.app 1.7.0 has no library `Glance` type to
 * extend — there is no glance screen in the Car App Library — so, as in the
 * Melisma reference, `CarGlance` is the app's own decision value, not a
 * library class.
 */
sealed interface CarGlance {

    /** No player is running, or the one that is has nothing to say. */
    data object Silent : CarGlance

    /** Something is playing and the lyrics are still being looked up. */
    data object Looking : CarGlance

    /** Nobody has these words. */
    data object None : CarGlance

    /** There are words but no timings, so there is no "now" to show. */
    data object Untimed : CarGlance

    /** Notification access has not been granted, which cannot be fixed from the car. */
    data object NoPermission : CarGlance

    /**
     * The words being sung, and the ones coming.
     *
     * @param line null during an instrumental passage or an intro — there is
     *   nothing being sung, and inventing something to fill the row would be
     *   a lie the driver has to read to discount.
     * @param next the next line with words in it, or null at the end of the song.
     * @param instrumental true when the song is between vocal lines.
     */
    data class Now(
        val line: String?,
        val next: String?,
        val instrumental: Boolean,
    ) : CarGlance

    companion object {

        /**
         * @param positionMs the playhead, from the media snapshot — the car
         *   screen has to agree with the phone about where the song is.
         */
        fun of(
            state: LyricsState,
            hasTrack: Boolean,
            permissionGranted: Boolean,
            positionMs: Long,
        ): CarGlance = when {
            !permissionGranted -> NoPermission
            !hasTrack -> Silent
            else -> when (state) {
                LyricsState.Idle -> Silent
                LyricsState.Looking -> Looking
                is LyricsState.None -> None
                is LyricsState.Loaded -> state.document.glance(positionMs)
            }
        }

        /**
         * The next line of the lyrics, or null when there is nothing to show
         * — no track, no permission, still looking, no lyrics, or the last
         * line is already playing.
         */
        fun nextLine(
            state: LyricsState,
            hasTrack: Boolean,
            permissionGranted: Boolean,
            positionMs: Long,
        ): String? = (of(state, hasTrack, permissionGranted, positionMs) as? Now)?.next

        private fun LyricsDocument.glance(positionMs: Long): CarGlance {
            if (!hasTimings) return Untimed

            val activeIndex = indexAt(positionMs.coerceAtLeast(0L))
            val active = lines.getOrNull(activeIndex)
            val line = active?.text?.takeIf { it.isNotBlank() }

            return Now(
                line = line,
                next = lines.drop((activeIndex + 1).coerceAtLeast(0))
                    .firstOrNull { it.text.isNotBlank() }?.text,
                // Before the first line, or on a blank line, nobody is singing.
                instrumental = line == null,
            )
        }
    }
}
