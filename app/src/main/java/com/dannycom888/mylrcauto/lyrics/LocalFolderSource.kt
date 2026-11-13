package com.dannycom888.mylrcauto.lyrics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
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
 * The result of one walk of the picked folder: the indexed `.lrc` and
 * audio files, plus the number of listing rows that could not be
 * classified and so were not indexed.
 *
 * The dropped count exists because the v1.0.22-and-earlier walk (built
 * on `DocumentFile`) lost rows SILENTLY: `TreeDocumentFile.listFiles()`
 * reads only each child's document id up front, and the name and mime
 * type are lazy per-child resolver queries
 * (`DocumentsContractApi19.queryForString`) that return null on ANY
 * failure — empty cursor, NULL column, exception — with only a Log.w.
 * The old `collectFiles` had no else branch, so a child whose mime
 * read defaulted was neither directory nor file and vanished (a
 * directory child lost that way took its whole subtree with it), and
 * a child whose name read defaulted got "" and failed both extension
 * checks. `listFiles()` also swallows mid-iteration query exceptions
 * and returns the partial listing as if it were complete. On the
 * user's phone that is the leading explanation for lookups missing
 * files that provably exist in the tree (traces, 2026-11-12 23:24 /
 * 23:25: clean title/artist, index "ready" — audio found NO, name
 * matches 0). Whether the phone's documents provider actually fails
 * those lazy queries is device behaviour that cannot be proven from
 * here; the walk's fragility was code-confirmed, so the walk no
 * longer depends on it — see [LocalFolderSource].
 */
data class FolderWalk(
    val lrcFiles: List<LocalLrcFile>,
    val audioFiles: List<LocalAudioFile>,
    /** Listing rows that could not be classified and were not indexed. */
    val droppedCount: Int,
)

/** What one row of a folder listing is, decided from that row alone. */
enum class FolderRowKind {
    DIRECTORY,
    LRC_FILE,
    AUDIO_FILE,

    /** A real row the index does not need (not a directory, not a media file). */
    OTHER_FILE,

    /**
     * The row lacks the data needed to classify it — counted as
     * dropped by the walk, never silently ignored.
     */
    UNCLASSIFIABLE,
}

/** The documents-provider mime type that marks a directory row. */
const val FOLDER_DIRECTORY_MIME_TYPE = "vnd.android.document/directory"

/**
 * Classifies one row of a folder listing from that row's OWN values —
 * the name and mime type read together in a single query, as opposed
 * to the old walk's lazy per-child queries whose independent failures
 * silently dropped rows.
 *
 * Policy: a row is a directory exactly when its mime type says so.
 * Everything else is classified by the extension of its name, so a
 * row whose mime read came back null is still indexed when its name
 * carries a known extension. A row with no usable name cannot be
 * classified at all, and neither can an extensionless row with no
 * mime type — that shape is exactly a directory whose mime read
 * failed, so it is reported ([FolderRowKind.UNCLASSIFIABLE]) instead
 * of being waved through as an uninteresting file. Pure, so the
 * policy is unit-testable on the JVM.
 */
fun classifyFolderRow(name: String?, mimeType: String?): FolderRowKind {
    if (mimeType == FOLDER_DIRECTORY_MIME_TYPE) return FolderRowKind.DIRECTORY
    if (name.isNullOrBlank()) return FolderRowKind.UNCLASSIFIABLE
    if (name.endsWith(".lrc", ignoreCase = true)) return FolderRowKind.LRC_FILE
    if (AUDIO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }) {
        return FolderRowKind.AUDIO_FILE
    }
    if (mimeType == null && !name.contains('.')) return FolderRowKind.UNCLASSIFIABLE
    return FolderRowKind.OTHER_FILE
}

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
     * One walk collecting `.lrc` files and audio files together, with
     * the count of rows that could not be classified. Throws when the
     * folder cannot be read at all (a revoked SAF grant or a vanished
     * folder) — the provider catches that and reports it instead of
     * crashing.
     */
    suspend fun listAll(): FolderWalk

    /** Reads one file's raw bytes, or null when it cannot be opened. */
    suspend fun readBytes(file: LocalLrcFile): ByteArray?
}

/**
 * SAF-backed [LocalLyricsSource].
 *
 * The folder URI comes from [SettingsStore], so picking a new folder in
 * Settings takes effect on the next scan.
 *
 * The walk is built on [DocumentsContract] directly, NOT on
 * `DocumentFile`: for each directory it issues ONE query on the
 * child-documents URI asking for document id, display name and mime
 * type together, and classifies each row atomically from that row's
 * own values ([classifyFolderRow]). The previous `DocumentFile` walk
 * resolved name and mime lazily, one extra query per child per
 * property, and any failure in those queries silently dropped the
 * child (a directory child took its whole subtree) — see
 * [FolderWalk]. Rows that still cannot be classified are counted,
 * and a directory that cannot be listed at all fails the walk when
 * it is the picked root (an unreadable folder must surface as an
 * error, not as an empty index) and is counted as dropped otherwise.
 */
class LocalFolderSource(
    context: Context,
    private val settings: SettingsStore,
) : LocalLyricsSource {

    private val appContext = context.applicationContext

    override fun isConfigured(): Boolean =
        settings.current.localLyricsFolderUri != null

    /** One recursive walk collecting `.lrc` files and audio files together. */
    override suspend fun listAll(): FolderWalk =
        withContext(Dispatchers.IO) {
            collectFiles()
        }

    private fun collectFiles(): FolderWalk {
        val rawUri = settings.current.localLyricsFolderUri
            ?: return FolderWalk(emptyList(), emptyList(), droppedCount = 0)
        val treeUri = Uri.parse(rawUri)
        val accumulator = WalkAccumulator()
        walkChildren(
            treeUri = treeUri,
            documentId = DocumentsContract.getTreeDocumentId(treeUri),
            accumulator = accumulator,
            isRoot = true,
        )
        return FolderWalk(
            lrcFiles = accumulator.lrc,
            audioFiles = accumulator.audio,
            droppedCount = accumulator.dropped,
        )
    }

    /** Mutable sink for one walk: the indexed files plus the dropped-row count. */
    private class WalkAccumulator {
        val lrc = mutableListOf<LocalLrcFile>()
        val audio = mutableListOf<LocalAudioFile>()
        var dropped = 0
    }

    /**
     * Lists one directory with a single child-documents query and
     * recurses into subdirectories. A [SecurityException] anywhere
     * means the grant itself is broken, so it propagates and fails
     * the whole walk (the provider reports the folder as unreadable)
     * rather than producing a quietly partial index.
     */
    private fun walkChildren(
        treeUri: Uri,
        documentId: String,
        accumulator: WalkAccumulator,
        isRoot: Boolean,
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val cursor = try {
            appContext.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            if (isRoot) {
                throw IllegalStateException("Cannot list the picked lyrics folder", e)
            }
            Log.w(TAG, "Cannot list folder $documentId; its subtree is skipped", e)
            accumulator.dropped += 1
            return
        }
        if (cursor == null) {
            if (isRoot) {
                throw IllegalStateException("Cannot list the picked lyrics folder")
            }
            Log.w(TAG, "Listing folder $documentId returned no cursor; subtree skipped")
            accumulator.dropped += 1
            return
        }
        cursor.use { rows ->
            val idColumn = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = rows.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (true) {
                val hasNext = try {
                    rows.moveToNext()
                } catch (e: SecurityException) {
                    throw e
                } catch (e: Exception) {
                    // A listing that dies mid-iteration must not pose
                    // as complete (the old walk's failure mode): the
                    // rows already read stay, the break is counted.
                    Log.w(TAG, "Listing folder $documentId stopped mid-iteration", e)
                    accumulator.dropped += 1
                    break
                }
                if (!hasNext) break
                val childId = readColumn(rows, idColumn)
                if (childId == null) {
                    accumulator.dropped += 1
                    continue
                }
                val name = readColumn(rows, nameColumn)
                val mimeType = readColumn(rows, mimeColumn)
                when (classifyFolderRow(name, mimeType)) {
                    FolderRowKind.DIRECTORY ->
                        walkChildren(treeUri, childId, accumulator, isRoot = false)
                    FolderRowKind.LRC_FILE -> accumulator.lrc += LocalLrcFile(
                        uriString = documentUri(treeUri, childId),
                        // Classified by its extension, so the name is present.
                        fileName = name!!,
                    )
                    FolderRowKind.AUDIO_FILE -> accumulator.audio += LocalAudioFile(
                        uriString = documentUri(treeUri, childId),
                        fileName = name!!,
                    )
                    FolderRowKind.OTHER_FILE -> Unit
                    FolderRowKind.UNCLASSIFIABLE -> accumulator.dropped += 1
                }
            }
        }
    }

    /** One column of the current row, or null when the column is absent, NULL, or unreadable. */
    private fun readColumn(cursor: android.database.Cursor, column: Int): String? {
        if (column < 0) return null
        return try {
            if (cursor.isNull(column)) null else cursor.getString(column)
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** The content URI of one document inside the picked tree — the form [readBytes] opens. */
    private fun documentUri(treeUri: Uri, documentId: String): String =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString()

    override suspend fun readBytes(file: LocalLrcFile): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                appContext.contentResolver
                    .openInputStream(Uri.parse(file.uriString))
                    ?.use { input -> input.readBytes() }
            }.getOrNull()
        }

    private companion object {
        /** Same tag as the provider's walk logging, so one logcat filter shows both. */
        const val TAG = "MyLrcAutoLyrics"
    }
}
