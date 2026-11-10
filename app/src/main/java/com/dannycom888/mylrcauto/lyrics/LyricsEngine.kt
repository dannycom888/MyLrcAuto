package com.dannycom888.mylrcauto.lyrics

import com.dannycom888.mylrcauto.media.TrackInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Turns the playing track into lyrics.
 *
 * One lookup at a time: [setTrack] cancels any fetch still in flight, so a
 * track change never lets the previous song's lyrics land on the new one.
 * The actual lookup lives in [LocalLyricsProvider]; this class only owns the
 * [LyricsState] the UI reads.
 */
class LyricsEngine(
    private val scope: CoroutineScope,
    private val provider: LocalLyricsProvider,
) {

    private val _state = MutableStateFlow<LyricsState>(LyricsState.Idle)
    val state: StateFlow<LyricsState> = _state.asStateFlow()

    private var fetchJob: Job? = null

    fun setTrack(track: TrackInfo?) {
        fetchJob?.cancel()
        fetchJob = null
        if (track == null || track.isEmpty) {
            _state.value = LyricsState.Idle
            return
        }
        _state.value = LyricsState.Looking
        fetchJob = scope.launch {
            val document = try {
                provider.fetch(track)
            } catch (e: CancellationException) {
                // A newer track cancelled this fetch: never report it, and
                // never swallow the cancellation itself.
                throw e
            } catch (e: Exception) {
                null
            }
            _state.value = if (document != null) {
                LyricsState.Loaded(document)
            } else {
                LyricsState.None(provider.noMatchReason ?: GENERIC_NO_MATCH_REASON)
            }
        }
    }

    private companion object {
        const val GENERIC_NO_MATCH_REASON = "No lyrics found for this track."
    }
}
