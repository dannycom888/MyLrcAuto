package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.lyrics.FolderRowKind
import com.dannycom888.mylrcauto.lyrics.classifyFolderRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The v1.0.23 walk's row classification: one directory listing row
 * is classified from its OWN name + mime type, read together in a
 * single query. The pre-v1.0.23 `DocumentFile` walk resolved those
 * two values through separate lazy queries whose failures silently
 * dropped the row (a dropped directory took its whole subtree);
 * these tests pin the replacement policy, including the rule that a
 * row whose mime read failed is still classified by its name's
 * extension, and that a row which cannot be classified is reported
 * (the walk counts it as dropped) instead of vanishing.
 */
class FolderWalkClassificationTest {

    @Test
    fun directoryMimeMarksADirectory() {
        assertEquals(
            FolderRowKind.DIRECTORY,
            classifyFolderRow("music chinese boy", "vnd.android.document/directory"),
        )
    }

    @Test
    fun directoryMimeWinsOverAFileLikeName() {
        // Trust the mime type: a directory named like a song is
        // still a directory — recursing into it is the whole point.
        assertEquals(
            FolderRowKind.DIRECTORY,
            classifyFolderRow("Beyond - 不再犹豫.mp3", "vnd.android.document/directory"),
        )
    }

    @Test
    fun lrcRowWithMimeIsAnLrcFile() {
        assertEquals(
            FolderRowKind.LRC_FILE,
            classifyFolderRow("Beyond - 不再犹豫.lrc", "application/octet-stream"),
        )
    }

    @Test
    fun audioRowWithMimeIsAnAudioFile() {
        assertEquals(
            FolderRowKind.AUDIO_FILE,
            classifyFolderRow("Beyond - 不再犹豫.mp3", "audio/mpeg"),
        )
        assertEquals(
            FolderRowKind.AUDIO_FILE,
            classifyFolderRow("Song.flac", "audio/flac"),
        )
    }

    @Test
    fun nullMimeWithKnownExtensionClassifiesByExtension() {
        // THE dropped-row shape: the mime read came back null, but
        // the name is right there in the same row — classify by it.
        assertEquals(
            FolderRowKind.LRC_FILE,
            classifyFolderRow("邰正宵 - 九百九十九朵玫瑰.lrc", null),
        )
        assertEquals(
            FolderRowKind.AUDIO_FILE,
            classifyFolderRow("邰正宵 - 九百九十九朵玫瑰.mp3", null),
        )
    }

    @Test
    fun extensionMatchingIsCaseInsensitive() {
        assertEquals(
            FolderRowKind.LRC_FILE,
            classifyFolderRow("Song.LRC", null),
        )
        assertEquals(
            FolderRowKind.AUDIO_FILE,
            classifyFolderRow("Song.MP3", null),
        )
    }

    @Test
    fun nullNameCannotBeClassified() {
        assertEquals(
            FolderRowKind.UNCLASSIFIABLE,
            classifyFolderRow(null, "audio/mpeg"),
        )
        assertEquals(
            FolderRowKind.UNCLASSIFIABLE,
            classifyFolderRow(null, null),
        )
        assertEquals(
            FolderRowKind.UNCLASSIFIABLE,
            classifyFolderRow("  ", "audio/mpeg"),
        )
    }

    @Test
    fun nullNameWithDirectoryMimeIsStillADirectory() {
        // The mime type alone is enough to recurse; the name is
        // never needed for a directory row.
        assertEquals(
            FolderRowKind.DIRECTORY,
            classifyFolderRow(null, "vnd.android.document/directory"),
        )
    }

    @Test
    fun unknownTypesAreOtherFilesNotDrops() {
        // Real rows the index simply does not need: classified,
        // skipped, and NOT counted as dropped.
        assertEquals(
            FolderRowKind.OTHER_FILE,
            classifyFolderRow("cover.jpg", "image/jpeg"),
        )
        assertEquals(
            FolderRowKind.OTHER_FILE,
            classifyFolderRow("notes.txt", "text/plain"),
        )
        assertEquals(
            FolderRowKind.OTHER_FILE,
            classifyFolderRow("playlist.m3u", "audio/x-mpegurl"),
        )
    }

    @Test
    fun extensionlessRowWithMimeIsAnOtherFile() {
        assertEquals(
            FolderRowKind.OTHER_FILE,
            classifyFolderRow("README", "text/plain"),
        )
    }

    @Test
    fun extensionlessRowWithNullMimeIsUnclassifiable() {
        // No extension and no mime: indistinguishable from a
        // directory whose mime read failed — exactly the row the
        // old walk lost (with its subtree). Count it, don't guess.
        assertEquals(
            FolderRowKind.UNCLASSIFIABLE,
            classifyFolderRow("music chinese boy", null),
        )
    }
}
