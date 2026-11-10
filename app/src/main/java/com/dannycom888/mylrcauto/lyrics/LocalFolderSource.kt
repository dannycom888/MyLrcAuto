package com.dannycom888.mylrcauto.lyrics

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.dannycom888.mylrcauto.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One `.lrc` file found under the configured local lyrics folder. */
data class LocalLrcFile(
    /** String form of the file's content URI; kept as a String so the matcher stays JVM-testable. */
    val uriString: String,
    val fileName: String,
)

/** One audio file found under the configured local lyrics folder. */
data class LocalAudioFile(
    /** String form of the file's content URI; kept as a String so the matcher stays JVM-testable. */
    val uriString: String,
    val fileName: String,
)

/** Audio file extensions indexed alongside `.lrc` files for audio-anchored matching. */
private val AUDIO_EXTENSIONS = setOf(
    ".mp3", ".flac", ".wav", ".aac", ".m4a", ".m4b", ".ogg", ".oga", ".opus",
    ".wma", ".ape", ".aiff", ".aif", ".aifc", ".dff", ".dsf", ".mka", ".tak",
    ".tta", ".wv", ".mpc", ".mp2", ".3gp",
)

/**
 * Access to the user's chosen local lyrics folder.
 *
 * The provider only depends on this interface; the Android implementation
 * below reads through the Storage Access Framework, so the folder only needs
 * to have been picked once and the grant persisted.
 */
interface LocalLyricsSource {
    /** True once the user has picked a folder. */
    fun isConfigured(): Boolean

    /**
     * One walk collecting `.lrc` files and audio files together. Empty when
     * no folder is set. Throws when the folder cannot be read (a revoked SAF
     * grant or a vanished folder) — the provider catches that and reports it
     * instead of crashing.
     */
    suspend fun listAll(): Pair<List<LocalLrcFile>, List<LocalAudioFile>>

    /** Reads one file's raw bytes, or null when it cannot be opened. */
    suspend fun readBytes(file: LocalLrcFile): ByteArray?
}

/**
 * SAF-backed [LocalLyricsSource].
 *
 * The folder URI comes from [SettingsStore], so picking a new folder in
 * Settings takes effect on the next scan.
 */
class LocalFolderSource(
    context: Context,
    private val settings: SettingsStore,
) : LocalLyricsSource {

    private val appContext = context.applicationContext

    override fun isConfigured(): Boolean =
        settings.current.localLyricsFolderUri != null

    /** One recursive walk collecting `.lrc` files and audio files together. */
    override suspend fun listAll(): Pair<List<LocalLrcFile>, List<LocalAudioFile>> =
        withContext(Dispatchers.IO) {
            collectFiles()
        }

    private fun collectFiles(): Pair<List<LocalLrcFile>, List<LocalAudioFile>> {
        val rawUri = settings.current.localLyricsFolderUri
            ?: return emptyList<LocalLrcFile>() to emptyList()
        val root = DocumentFile.fromTreeUri(appContext, Uri.parse(rawUri))
            ?: return emptyList<LocalLrcFile>() to emptyList()
        val lrc = mutableListOf<LocalLrcFile>()
        val audio = mutableListOf<LocalAudioFile>()
        collectFiles(root, lrc, audio)
        return lrc to audio
    }

    private fun collectFiles(
        directory: DocumentFile,
        lrcOut: MutableList<LocalLrcFile>,
        audioOut: MutableList<LocalAudioFile>,
    ) {
        for (child in directory.listFiles()) {
            when {
                child.isDirectory -> collectFiles(child, lrcOut, audioOut)
                child.isFile -> {
                    val name = child.name.orEmpty()
                    when {
                        name.endsWith(".lrc", ignoreCase = true) -> {
                            lrcOut += LocalLrcFile(
                                uriString = child.uri.toString(),
                                fileName = name,
                            )
                        }
                        AUDIO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) } -> {
                            audioOut += LocalAudioFile(
                                uriString = child.uri.toString(),
                                fileName = name,
                            )
                        }
                    }
                }
            }
        }
    }

    override suspend fun readBytes(file: LocalLrcFile): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                appContext.contentResolver
                    .openInputStream(Uri.parse(file.uriString))
                    ?.use { input -> input.readBytes() }
            }.getOrNull()
        }
}
