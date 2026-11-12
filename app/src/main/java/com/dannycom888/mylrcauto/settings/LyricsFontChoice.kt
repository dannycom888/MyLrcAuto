package com.dannycom888.mylrcauto.settings

/**
 * Which font Chinese lyrics draw in, chosen in Settings.
 *
 * - [ORIGINAL]: the font the app used before v1.0.15 — the platform
 *   default, bold only for the line being sung.
 * - [BAUHAUS]: the user's uploaded Bauhaus ITC font (bundled in
 *   res/font), bold for every Chinese line.
 *
 * English lyrics are unaffected by the choice — they always keep the
 * default font. The default is [BAUHAUS], so installing a version
 * that adds this setting changes nothing unless the user switches.
 */
enum class LyricsFontChoice {
    ORIGINAL,
    BAUHAUS,
}
