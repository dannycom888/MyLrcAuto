package com.dannycom888.mylrcauto.media

import android.graphics.Bitmap

/**
 * Album artwork, from the player itself only.
 *
 * A media session publishes artwork two ways: as a bitmap in the metadata
 * ([android.media.MediaMetadata.METADATA_KEY_ALBUM_ART] and friends) or as
 * a URI (METADATA_KEY_ALBUM_ART_URI / METADATA_KEY_ART_URI). This file holds
 * the parts of that chain that do not need framework calls, so they can be
 * unit-tested on the JVM: which URIs are safe to open locally, how far to
 * downsample a large embedded cover, and the bounded cache.
 *
 * Local only: an `http(s)://` artwork URI is never opened — this app has no
 * network permission and never downloads anything. A cover that arrives as
 * a bitmap is scaled down before it is kept: a template's image travels to
 * the car host over Binder, and a full-size cover would blow the
 * transaction limit.
 */
object Artwork {

    /**
     * Longest side of a cover kept for a CarIcon. 384px is about 576 KiB
     * as ARGB_8888 — comfortably inside one Binder transaction with the
     * rest of the template beside it — and plenty for the pane image.
     */
    const val MAX_DIMENSION_PX = 384

    /** Largest number of covers held in memory at once. */
    const val MAX_CACHED_COVERS = 8

    /**
     * True when a published artwork URI can be opened on this device
     * without a network: `content://` (most players) or `file://`.
     * Anything else — `http(s)://`, a bare path, a blank string — is
     * refused. The scheme is read off the string so this stays pure
     * Kotlin, testable without the framework.
     */
    fun isLocalUri(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        val scheme = uri.substringBefore(':').lowercase()
        return scheme == "content" || scheme == "file"
    }

    /**
     * The `inSampleSize` that brings an image of [width] x [height] down
     * so the decoded bitmap is still at least [MAX_DIMENSION_PX] on its
     * longest side — close enough that a final [scaleDown] to the cap is
     * cheap, small enough that the decode itself stays light. Always a
     * power of two (what BitmapFactory wants) and at least 1. 0 or negative
     * dimensions mean "unknown" and are not sampled.
     */
    fun sampleSize(width: Int, height: Int): Int {
        val longest = maxOf(width, height)
        if (longest <= 0) return 1
        var sample = 1
        while (longest / (sample * 2) >= MAX_DIMENSION_PX) sample *= 2
        return sample
    }

    /**
     * Returns [bitmap] scaled so its longest side is at most
     * [MAX_DIMENSION_PX], or the bitmap itself when it already fits.
     * The scaled copy is a new bitmap; the original is untouched.
     */
    fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION_PX || longest <= 0) return bitmap
        val scale = MAX_DIMENSION_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}

/**
 * Covers fetched from artwork URIs, bounded so a long listening session
 * cannot grow memory without limit: the oldest entry goes when the cap is
 * reached. Keyed by URI, so two tracks from the same album share one cover.
 * Generic over the stored value so the eviction policy can be unit-tested
 * on the JVM without an Android bitmap.
 */
class ArtworkCache<V>(private val maxEntries: Int = Artwork.MAX_CACHED_COVERS) {
    private val covers = LinkedHashMap<String, V>()

    fun get(uri: String?): V? = uri?.let { covers[it] }

    fun put(uri: String, value: V) {
        covers[uri] = value
        while (covers.size > maxEntries) {
            covers.keys.firstOrNull()?.let { covers.remove(it) } ?: break
        }
    }

    val size: Int get() = covers.size
}
