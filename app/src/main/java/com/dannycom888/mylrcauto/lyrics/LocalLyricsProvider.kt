package com.dannycom888.mylrcauto.lyrics

import android.content.Context
import android.util.Log
import com.dannycom888.mylrcauto.lyrics.LocalLyricsMatcher.IndexedAudioFile
import com.dannycom888.mylrcauto.lyrics.LocalLyricsMatcher.IndexedLrcFile
import com.dannycom888.mylrcauto.media.TrackInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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
    /**
     * The configured folder's URI string, or null when no folder is picked.
     * Drives the disk index cache: a cached index is only served for the
     * folder it was built from.
     */
    private val folderUri: () -> String? = { null },
    /**
     * App-lifetime scope for the background index rebuild after a disk
     * cache is served. Null disables the rebuild (the cache is still
     * served, and the next lookup walks the folder instead).
     */
    private val backgroundScope: CoroutineScope? = null,
) {

    val isConfigured: Boolean get() = hasFolder()

    var noMatchReason: String? = null

    private val _indexVersion = MutableStateFlow(0L)

    /**
     * Bumped every time the served index is REPLACED by a freshly
     * walked one — the cold walk in [ensureIndex] and the background
     * rebuild after a disk cache was served. Serving the disk cache
     * itself does not bump: fetches use that index directly, and it
     * is the walk landing afterwards whose contents they may have
     * missed. The app collects this and re-runs the current track's
     * lookup (see LyricsEngine.refresh): a lookup answered by a
     * stale index — a miss for a file the fresh walk does find —
     * otherwise stands until the track changes, because the engine
     * only fetches on a track change. That is how the "不再犹豫"
     * miss (2026-11-12) survived the user's track switches: both
     * switches were answered by the not-yet-rebuilt index, and once
     * the rebuild landed nothing ever looked the track up again.
     */
    val indexVersion: StateFlow<Long> = _indexVersion.asStateFlow()

    private val indexMutex = Mutex()

    /** True while a background rebuild (after serving a disk cache) is running. */
    private val rebuildInFlight = AtomicBoolean(false)

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
        //    The `.lrc` is only trusted when its title is the track's title:
        //    findAudioFile matches the audio file by substring, so a longer
        //    file ("爱你.mp3") can be picked for a shorter title ("爱"), and
        //    its same-stem `.lrc` would show the wrong song's lyrics. A
        //    title that does not match falls through to the metadata
        //    fallback below (exact keys), which is the stricter check.
        val audioFile = LocalLyricsMatcher.findAudioFile(title, artists, index.audio)
        if (audioFile != null) {
            val lrcEntry = LocalLyricsMatcher.sameStemLrc(audioFile, index.lrc)
            if (lrcEntry != null &&
                LocalLyricsMatcher.lrcTitleMatches(lrcEntry.file.fileName, title)
            ) {
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
        val uri = folderUri()
        // Fresh process, disk cache for this folder: serve it at once so the
        // first lookup resolves immediately, and rebuild it in the
        // background so the cache stays fresh. Stale entries beat an
        // infinite spinner on the car.
        if (uri != null) {
            val diskIndex = withContext(ioDispatcher) { loadCachedIndex(uri) }
            if (diskIndex != null) {
                cachedIndex = diskIndex
                triggerBackgroundRebuild()
                return diskIndex
            }
        }
        return indexMutex.withLock {
            cachedIndex?.let { return it }
            // One walk, not two: the folder is traversed once for `.lrc` and
            // audio files together.
            //
            // A revoked SAF grant (SecurityException) or a vanished folder
            // must not crash the lookup or the rescan either: the index stays
            // empty, the failure is remembered for the UI to report, and the
            // next lookup retries the walk rather than serving a stale answer.
            val index = walkFolder()
            if (index == null) {
                lastIndexError = true
                return@withLock null
            }
            lastIndexError = false
            cachedIndex = index
            _indexVersion.value += 1
            if (uri != null) {
                saveCachedIndex(uri, index)
            }
            index
        }
    }

    /**
     * One SAF walk of the folder, with timing and failure logging. Returns
     * the built index, or null when the folder cannot be read. Never throws
     * except on cancellation.
     *
     * NonCancellable, because a lookup that times out must not discard the
     * build: a big folder outruns a first lookup, but the index is the whole
     * point of the *next* lookup too, so the walk runs to completion even
     * when this fetch is cancelled.
     */
    private suspend fun walkFolder(): LocalIndex? {
        val folderSource = source ?: return null
        val startedAt = System.currentTimeMillis()
        val (lrcFiles, audioFiles) = try {
            withContext(NonCancellable + ioDispatcher) {
                folderSource.listAll()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Lyrics index walk failed", e)
            return null
        }
        val tookMs = System.currentTimeMillis() - startedAt
        if (tookMs > SLOW_WALK_MS) {
            Log.i(
                TAG,
                "Lyrics index walk took ${tookMs}ms: ${lrcFiles.size} .lrc, " +
                    "${audioFiles.size} audio",
            )
        }
        return LocalIndex(
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
    }

    private fun cacheFile(): File = File(context.filesDir, CACHE_FILE_NAME)

    /**
     * Loads the disk-cached index for this folder, or null when there is no
     * cache, it is malformed, or it was built for a different folder.
     * Called on the IO dispatcher; never throws.
     */
    private fun loadCachedIndex(uri: String): LocalIndex? {
        return try {
            val file = cacheFile()
            if (!file.exists()) return null
            val cached = LyricsIndexCache.parse(file.readText()) ?: return null
            if (cached.folderUri != uri) return null
            LocalIndex(
                lrc = cached.lrc.map { fileEntry ->
                    IndexedLrcFile(
                        file = fileEntry,
                        keys = LocalLyricsMatcher.fileKeys(fileEntry.fileName),
                    )
                },
                audio = cached.audio.map { fileEntry ->
                    IndexedAudioFile(
                        file = fileEntry,
                        normalizedStem = LocalLyricsMatcher.fileStem(fileEntry.fileName),
                    )
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring unreadable lyrics index cache", e)
            null
        }
    }

    /** Best-effort save of the index to disk; a failed save never breaks a lookup. */
    private suspend fun saveCachedIndex(uri: String, index: LocalIndex) {
        try {
            val json = LyricsIndexCache.serialize(
                folderUri = uri,
                lrc = index.lrc.map { it.file },
                audio = index.audio.map { it.file },
            )
            withContext(ioDispatcher) {
                // Publish atomically: write to a temp file, then rename it
                // over the cache. A fresh process reading the cache while a
                // rebuild is saving must see the old complete file or the
                // new complete file, never a half-written one — the strict
                // parser rejects a torn file, and that rejection forces a
                // full folder walk on the next launch, which is exactly the
                // cold-start spin this cache exists to avoid. A process
                // killed mid-save (easy on car re-entry churn) leaves the
                // previous good cache in place.
                val tmpFile = File(context.filesDir, "$CACHE_FILE_NAME.tmp")
                tmpFile.writeText(json)
                if (!tmpFile.renameTo(cacheFile())) {
                    // Same directory, so this should not happen; fall back
                    // to a direct write rather than losing the cache.
                    tmpFile.delete()
                    cacheFile().writeText(json)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not save lyrics index cache", e)
        }
    }

    /**
     * Rebuilds the index in the background after a disk-cached one was
     * served, so the cache stays fresh without making the first lookup wait
     * for a walk. Only one rebuild runs at a time, and a rebuild is
     * discarded if the folder changed while it was walking, so it can never
     * clobber a newer folder's index. A failed rebuild only logs: the
     * disk-cached index keeps serving, and the failure is not remembered as
     * an index error.
     */
    private fun triggerBackgroundRebuild() {
        val scope = backgroundScope ?: return
        val rebuildFolderUri = folderUri() ?: return
        if (!rebuildInFlight.compareAndSet(false, true)) return
        scope.launch(ioDispatcher) {
            try {
                val built = indexMutex.withLock { walkFolder() }
                if (built != null && folderUri() == rebuildFolderUri) {
                    cachedIndex = built
                    _indexVersion.value += 1
                    saveCachedIndex(rebuildFolderUri, built)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Background lyrics index rebuild failed", e)
            } finally {
                rebuildInFlight.set(false)
            }
        }
    }

    private suspend fun parseFile(file: LocalLrcFile): LyricsDocument? {
        val bytes = withContext(ioDispatcher) { source?.readBytes(file) } ?: return null
        return LocalLyricsMatcher.parseBytes(bytes, file.fileName)
    }

    companion object {
        /** Log tag for index walk diagnostics. */
        const val TAG = "MyLrcAutoLyrics"

        /** Disk cache of the file index, in the app's internal storage. */
        const val CACHE_FILE_NAME = "lyrics-index-cache.json"

        /** A walk slower than this is logged with its duration and file counts. */
        const val SLOW_WALK_MS = 10_000L

        /** At most this many matching files are read and parsed per lookup. */
        private const val MAX_FILES_TO_PARSE = 32
    }
}
