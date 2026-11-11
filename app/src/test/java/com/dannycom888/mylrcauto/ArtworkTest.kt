package com.dannycom888.mylrcauto

import com.dannycom888.mylrcauto.media.Artwork
import com.dannycom888.mylrcauto.media.ArtworkCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The artwork chain's framework-free parts: which artwork URIs are safe
 * to open locally, how far a cover is downsampled before decoding, and
 * the bounded cover cache. The framework parts — reading a bitmap out of
 * MediaMetadata, opening a `content://` stream — cannot run on the JVM
 * and are covered by the release build plus real-car testing instead.
 */
class ArtworkTest {

    // ---- which URIs are local --------------------------------------------

    @Test
    fun `content uri is local`() {
        assertTrue(Artwork.isLocalUri("content://com.maxmpz.audioplayer/cover/123"))
    }

    @Test
    fun `file uri is local`() {
        assertTrue(Artwork.isLocalUri("file:///storage/emulated/0/Music/cover.jpg"))
    }

    @Test
    fun `https uri is not local`() {
        assertFalse(Artwork.isLocalUri("https://example.com/cover.jpg"))
    }

    @Test
    fun `http uri is not local`() {
        assertFalse(Artwork.isLocalUri("http://example.com/cover.jpg"))
    }

    @Test
    fun `null and blank uris are not local`() {
        assertFalse(Artwork.isLocalUri(null))
        assertFalse(Artwork.isLocalUri(""))
        assertFalse(Artwork.isLocalUri("   "))
    }

    @Test
    fun `bare path is not local`() {
        assertFalse(Artwork.isLocalUri("/storage/emulated/0/Music/cover.jpg"))
    }

    @Test
    fun `scheme match is case-insensitive`() {
        assertTrue(Artwork.isLocalUri("CONTENT://com.maxmpz.audioplayer/cover/1"))
    }

    // ---- downsampling ------------------------------------------------------

    @Test
    fun `small cover is not sampled`() {
        assertEquals(1, Artwork.sampleSize(384, 384))
        assertEquals(1, Artwork.sampleSize(100, 100))
    }

    @Test
    fun `1000px cover samples to 2`() {
        // Decoded at 500px, then scaled to the 384 cap — near the cap
        // instead of a quarter of it.
        assertEquals(2, Artwork.sampleSize(1000, 800))
    }

    @Test
    fun `2048px cover samples to 4`() {
        assertEquals(4, Artwork.sampleSize(2048, 1024))
    }

    @Test
    fun `unknown dimensions are not sampled`() {
        assertEquals(1, Artwork.sampleSize(0, 0))
        assertEquals(1, Artwork.sampleSize(-1, 500))
    }

    // ---- bounded cache -----------------------------------------------------

    @Test
    fun `cache returns what was put`() {
        // Strings stand in for bitmaps: the cache is generic precisely so
        // its eviction policy can run on the JVM (android.graphics.Bitmap
        // cannot be instantiated in a unit test).
        val cache = ArtworkCache<String>(maxEntries = 2)
        cache.put("content://cover/1", "cover-1")
        assertSame("cover-1", cache.get("content://cover/1"))
        assertEquals(1, cache.size)
    }

    @Test
    fun `cache misses return null`() {
        val cache = ArtworkCache<String>()
        assertNull(cache.get("content://cover/1"))
        assertNull(cache.get(null))
    }

    @Test
    fun `cache evicts the oldest entry past the cap`() {
        val cache = ArtworkCache<String>(maxEntries = 2)
        cache.put("content://cover/1", "cover-1")
        cache.put("content://cover/2", "cover-2")
        cache.put("content://cover/3", "cover-3")
        assertEquals(2, cache.size)
        assertNull(cache.get("content://cover/1"))
        assertSame("cover-2", cache.get("content://cover/2"))
        assertSame("cover-3", cache.get("content://cover/3"))
    }

    @Test
    fun `re-putting the same uri does not grow the cache`() {
        val cache = ArtworkCache<String>(maxEntries = 2)
        cache.put("content://cover/1", "cover-1-old")
        cache.put("content://cover/1", "cover-1-new")
        assertEquals(1, cache.size)
        assertSame("cover-1-new", cache.get("content://cover/1"))
    }
}
