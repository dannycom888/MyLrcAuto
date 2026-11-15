package com.dannycom888.mylrcauto.settings

/**
 * The karaoke sweep colour, chosen in Settings （扫色颜色）.
 *
 * The sweep is the golden overlay that fills the characters already
 * sung on the current line of an enhanced LRC (see LyricsView).
 * [GOLD] is the colour the sweep has had since it was introduced in
 * v1.0.33, and it is the default, so adding this choice in v1.0.35
 * changes nothing unless the user picks another colour. The exact
 * hues live with the rendering, in `sweepColorFor` — this enum only
 * names the choice, the same way [LyricsFontChoice] names the font.
 *
 * v1.0.36 swapped the other two hues: the v1.0.35 ORANGE and RED
 * read as harsh against the cyan glow, so their slots are now a
 * light green and a light pink. Stored names from v1.0.35 migrate
 * by slot — see [fromStoredName].
 */
enum class SweepColorChoice {
    GOLD,
    LIGHT_GREEN,
    LIGHT_PINK,
    ;

    companion object {
        /**
         * The choice a stored prefs value names. Current names map
         * to themselves; the retired v1.0.35 names migrate by slot
         * (ORANGE → [LIGHT_GREEN], RED → [LIGHT_PINK]), so an install
         * that picked orange or red keeps a picked colour, in the
         * softer hue that replaced it. Null (never stored) and any
         * unrecognised value fall back to [GOLD], the default.
         */
        fun fromStoredName(name: String?): SweepColorChoice = when (name) {
            "GOLD" -> GOLD
            "LIGHT_GREEN" -> LIGHT_GREEN
            "LIGHT_PINK" -> LIGHT_PINK
            "ORANGE" -> LIGHT_GREEN
            "RED" -> LIGHT_PINK
            else -> GOLD
        }
    }
}
