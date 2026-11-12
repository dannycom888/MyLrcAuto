package com.dannycom888.mylrcauto.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.dannycom888.mylrcauto.R

/**
 * The Chinese lyrics font: Bauhaus ITC, supplied by the user as a TTF
 * (2026-11-11) and bundled in res/font. It is a Regular (single-weight)
 * font, so bold is synthetic — the platform thickens the outlines when
 * Bold is requested, exactly as it does for any font without a bold
 * variant. The font has Latin glyphs too, but it is only used for
 * Chinese lines: English lyrics keep the default font, unchanged.
 *
 * The family declares the same file for Normal and Bold: a Chinese line
 * is always Bold (see [lyricsFontSpec]), and the Bold entry is what
 * makes the platform synthesize the bold rendering instead of falling
 * back to the Regular weight.
 */
val ChineseLyricsFontFamily: FontFamily = FontFamily(
    Font(R.font.bauhaus_itc, FontWeight.Normal),
    Font(R.font.bauhaus_itc, FontWeight.Bold),
)

/** True when the character is CJK (Chinese/Japanese/Korean ideograph, kana, hangul, or CJK punctuation). */
internal fun Char.isCjk(): Boolean {
    val c = code
    return c in 0x4E00..0x9FFF || // CJK Unified Ideographs
        c in 0x3400..0x4DBF || // CJK Extension A
        c in 0x3040..0x30FF || // Hiragana + Katakana
        c in 0xAC00..0xD7AF || // Hangul syllables
        c in 0x3000..0x303F || // CJK symbols and punctuation
        c in 0xFF00..0xFFEF // Halfwidth and fullwidth forms
}

/** True when the lyric line has any CJK character in it. */
fun isCjkLine(text: String): Boolean = text.any { it.isCjk() }

/** Which font and weight a lyric line draws with. */
data class LyricsFontSpec(
    val fontFamily: FontFamily,
    val fontWeight: FontWeight,
)

/**
 * The font for one lyric line.
 *
 * A line with any CJK character is a Chinese line: the bundled Bauhaus
 * font, always Bold (the current line and the other lines alike — the
 * user asked for Chinese lyrics in this font and bold). A line with no
 * CJK character is an English line: the default font, unchanged, Bold
 * only for the line being sung (the existing behaviour).
 *
 * Pure, so the rule is unit-testable on the JVM: Chinese resolves to
 * the bundled family and Bold, English to the default family.
 */
fun lyricsFontSpec(text: String, isCurrent: Boolean): LyricsFontSpec =
    if (isCjkLine(text)) {
        LyricsFontSpec(ChineseLyricsFontFamily, FontWeight.Bold)
    } else {
        LyricsFontSpec(
            FontFamily.Default,
            if (isCurrent) FontWeight.Bold else FontWeight.Normal,
        )
    }
