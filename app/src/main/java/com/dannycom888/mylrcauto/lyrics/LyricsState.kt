package com.dannycom888.mylrcauto.lyrics

/**
 * What the app knows about the lyrics for the track that is playing.
 *
 * The UI combines this with the media snapshot: the track comes from the
 * player, the words come from here.
 */
sealed interface LyricsState {
    /** Nothing to look up — no track is playing. */
    data object Idle : LyricsState

    /** A track is playing and its lyrics are being looked up. */
    data object Looking : LyricsState

    /** Lyrics found for the playing track. */
    data class Loaded(val document: LyricsDocument) : LyricsState

    /** No lyrics for the playing track; [reason] is a human-readable why. */
    data class None(val reason: String) : LyricsState
}
