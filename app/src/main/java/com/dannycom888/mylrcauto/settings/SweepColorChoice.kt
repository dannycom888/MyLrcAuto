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
 */
enum class SweepColorChoice {
    GOLD,
    ORANGE,
    RED,
}
