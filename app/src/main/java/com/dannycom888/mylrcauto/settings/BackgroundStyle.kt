package com.dannycom888.mylrcauto.settings

/**
 * The big screen's backdrop, on the car's own surface.
 *
 * COVER draws the current track's cover, blurred, behind the lyrics;
 * BLACK is all black. The user asked for both (2026-11-11: "封面只"
 * first, then "也可以选全黑，我喜欢") and likes all-black, so BLACK
 * is the default. Living / artist / solid-colour styles and Spotify
 * Canvas (Melisma's other options) do not apply here: this app is
 * local-only, has no artist images and no Spotify.
 */
enum class BackgroundStyle {
    COVER,
    BLACK;

    companion object {
        /** Unknown or stored names from an older build fall back to BLACK, never crash. */
        fun fromName(name: String?): BackgroundStyle =
            runCatching { if (name == null) BLACK else valueOf(name) }.getOrDefault(BLACK)
    }
}
