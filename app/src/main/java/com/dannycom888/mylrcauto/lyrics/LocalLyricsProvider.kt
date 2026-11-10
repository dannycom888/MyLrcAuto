package com.dannycom888.mylrcauto.lyrics

import android.content.Context
import com.dannycom888.mylrcauto.lyrics.LocalLyricsMatcher.IndexedAudioFile
import com.dannycom888.mylrcauto.lyrics.LocalLyricsMatcher.IndexedLrcFile
import com.dannycom888.mylrcauto.media.TrackInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lyrics provider backed by the user's own `.lrc` files in a folder they pick
 * once in Settings.
 *
 * This is the only lyrics provider in this build: it never contacts the
 * network. When no local file matches the playing track it returns null, and
 * the app shows no lyrics.
 *
 * Matching is audio-anchored first: the playing track's own audio file is
 * found in the folder (its stem must contain the title and one of the
 * artists — or the title alone when the player reports no artist), and the
 * `.lrc` with the identical stem is used — the pairing rule Poweramp uses.
 * When no audio file is found, or the audio file has no same-stem `.lrc`,
 * matching falls back to the `.lrc` file names:
 * `"Artist - Title.lrc"` is preferred, `"Title.lrc"` is the fallback; both
 * are normalized (case, spacing and punctuation are ignored). When the player
 * reports no artist at all, a file whose title part is this title matches
 * too — there is no artist to contradict it. When several files match, the
 * one whose last lyric timestamp is closest to the track duration wins.
 * `.lrc` files are decoded as UTF-8 first and as GBK when that fails.
 */
class LocalLyricsProvider(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** True once the user has picked a folder; drives [isConfigured]. */
    private val hasFolder: () -> Boolean = { true },
) {

    val isConfigured: Boolean get() = hasFolder()

    var noMatchReason: String? = null

    private val indexMutex = Mutex()

    @Volatile
    private var source: LocalLyricsSource? = null

    @Volatile
    private var cachedIndex: LocalIndex? = null

    /**
     * The last index build failed — the folder is configured but could not be
     * read (a revoked SAF grant throws, or the folder is gone). Remembered so
     * the UI can say so instead of showing nothing.
     */
    @Volatile
    private var lastIndexError = false

    /** What trying to (re)build the file index came back with. */
    sealed interface IndexResult {
        /** No folder picked yet. */
        data object NoFolder : IndexResult

        /** The folder is configured but cannot be opened — pick it again. */
        data class Unreadable(val reason: String) : IndexResult

        /** The folder read fine, with this many `.lrc` files in it. */
        data class Ready(val lrcFileCount: Int) : IndexResult
    }

    /** Attaches (or replaces) the folder source. Re-attaching invalidates the cached index. */
    fun attach(source: LocalLyricsSource?) {
        this.source = source
        invalidate()
    }

    /** Drops the cached file index so the next search rescans the folder. */
    fun invalidate() {
        cachedIndex = null
        lastIndexError = false
    }

    /**
     * Rebuilds the file index immediately (used by the Settings rescan action)
     * and says what came of it — including zero files and an unreadable
     * folder, which a silent rebuild would leave invisible.
     */
    suspend fun refreshIndex(): IndexResult {
        invalidate()
        return indexStatus()
    }

    /** What the folder looks like right now, building the index if it isn't built yet. */
    suspend fun indexStatus(): IndexResult {
        if (!isConfigured) return IndexResult.NoFolder
        if (lastIndexError) return IndexResult.Unreadable(
            "Lyrics folder can't be read — pick it again in Settings",
        )
        val index = ensureIndex() ?: return IndexResult.Unreadable(
            "Lyrics folder can't be read — pick it again in Settings",
        )
        return IndexResult.Ready(index.lrc.size)
    }

    /** Number of `.lrc` files in the current index, or null when no index has been built yet. */
    suspend fun indexedFileCount(): Int? = ensureIndex()?.lrc?.size

    /** Lyrics for the playing track, or null when no local file matches it. */
    suspend fun fetch(track: TrackInfo): LyricsDocument? {
        val artists = listOf(track.artist).filter { it.isNotBlank() }
        return fetch(track.title, artists, track.durationMs)
    }

    /** Lyrics for a track described by title, artists and duration, or null when no local file matches it. */
    suspend fun fetch(title: String, artists: List<String>, durationMs: Long): LyricsDocument? {
        noMatchReason = null
        val index = ensureIndex()
        if (index == null) {
            noMatchReason = if (!isConfigured) {
                "No lyrics folder picked yet"
            } else {
                "Lyrics folder can't be read — pick it again in Settings"
            }
            return null
        }

        // 1. Audio-anchored: the playing track's own audio file, then the
        //    `.lrc` with the identical stem (the pairing rule Poweramp uses).
        val audioFile = LocalLyricsMatcher.findAudioFile(title, artists, index.audio)
        if (audioFile != null) {
            val lrcEntry = LocalLyricsMatcher.sameStemLrc(audioFile, index.lrc)
            if (lrcEntry != null) {
                parseFile(lrcEntry.file)?.let { return it }
            }
        }

        // 2. Fallback: metadata matching against `.lrc` file names.
        val matched = LocalLyricsMatcher.match(title, artists, index.lrc)
        if (matched.isEmpty()) {
            noMatchReason = "No .lrc file in the folder matches this track"
            return null
        }

        val parsed = mutableListOf<Pair<IndexedLrcFile, LyricsDocument>>()
        for (entry in matched.take(MAX_FILES_TO_PARSE)) {
            parseFile(entry.file)?.let { parsed += entry to it }
        }
        if (parsed.isEmpty()) {
            noMatchReason = "Matched .lrc files could not be read"
            return null
        }

        val winner = LocalLyricsMatcher.pickWinner(
            title = title,
            artists = artists,
            durationMs = durationMs,
            parsed = parsed.map { (entry, document) ->
                entry to document.lines.maxOfOrNull { it.timeMs }
            },
        ) ?: return null
        return parsed.first { it.first == winner }.second
    }

    /** The cached folder index: indexed `.lrc` files plus indexed audio files. */
    private data class LocalIndex(
        val lrc: List<IndexedLrcFile>,
        val audio: List<IndexedAudioFile>,
    )

    private suspend fun ensureIndex(): LocalIndex? {
        cachedIndex?.let { return it }
        val folderSource = source ?: return null
        return indexMutex.withLock {
            cachedIndex?.let { return it }
            // One walk, not two: the folder is traversed once for `.lrc` and
            // audio files together.
            //
            // NonCancellable, because a lookup that times out must not discard
            // the build: a big folder outruns a first lookup, but the index is
            // the whole point of the *next* lookup too, so the walk runs to
            // completion even when this fetch is cancelled.
            //
            // A revoked SAF grant (SecurityException) or a vanished folder
            // must not crash the lookup or the rescan either: the index stays
            // empty, the failure is remembered for the UI to report, and the
            // next lookup retries the walk rather than serving a stale answer.
            val (lrcFiles, audioFiles) = try {
                withContext(NonCancellable + ioDispatcher) {
                    folderSource.listAll()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastIndexError = true
                return@withLock null
            }
            lastIndexError = false
            val index = LocalIndex(
                lrc = lrcFiles.map { file ->
                    IndexedLrcFile(
                        file = file,
                        keys = LocalLyricsMatcher.fileKeys(file.fileName),
                    )
                },
                audio = audioFiles.map { file ->
                    IndexedAudioFile(
                        file = file,
                        normalizedStem = LocalLyricsMatcher.fileStem(file.fileName),
                    )
                },
            )
            cachedIndex = index
            index
        }
    }

    private suspend fun parseFile(file: LocalLrcFile): LyricsDocument? {
        val bytes = withContext(ioDispatcher) { source?.readBytes(file) } ?: return null
        return LocalLyricsMatcher.parseBytes(bytes, file.fileName)
    }

    companion object {
        /** At most this many matching files are read and parsed per lookup. */
        private const val MAX_FILES_TO_PARSE = 8
    }
}
