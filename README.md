# My LRC Auto 1.0.8

Your own original app: local-only `.lrc` lyrics that follow Poweramp playback, showing the full lyrics on your phone and on the Android Auto head unit. No network, no online lyric lookup.

This software was made with Muse's assistance.

## What's new in 1.0.8

- **Smaller icon**: the note and car are now smaller inside the icon, with comfortable space to the edges (same design otherwise: deep-blue background #051E3C, glowing white M note, white car with round headlights and a smile).
- **Previous-track icon fixed**: on the phone lyrics screen, the previous button is now the exact mirror of the next button (|◀ mirrors ▶|). It used to render as a tall triangle leaning right, sitting too low.

## What's new in 1.0.7

- **Lyrics size** in Settings: a slider from 14 to 32 sp (default 17 sp, the original size). It sizes the lyrics on the phone and on the car together, and the choice is kept across restarts.
- **Keep screen on** switch in Settings: while lyrics are showing, the phone screen stays on; leaving the lyrics screen returns to normal. The choice is kept across restarts.
- **New icon**: deep-blue background (#051E3C), a white M-shaped note and a glowing white car, both centred on the same axis and almost touching; the car has round headlights and a smile.

## What's new in 1.0.6

The top-left box in full-screen now shows the album cover: the cover image on top, song title and artist below; the cover changes with the track, and songs without a cover show the app's M-note icon. Covers come only from what Poweramp itself provides (artwork in the playback info, or a local content:// cover) — nothing is downloaded; the three-line split-screen card, the scrolling lyrics panel and the 30-second lookup timeout from v1.0.5 are unchanged.

## What's new in 1.0.5

The big lyrics panel on the car no longer freezes: like the phone, it refreshes every 0.5 s, and the line being sung is highlighted and auto-scrolled. The split-screen card now shows three lines — previous, current, next — with the current line in the middle; tapping the card still opens the full lyrics, and in full-screen the top-left card shows only the song title and artist, no repeated lyrics. If the car has not found lyrics after 30 seconds, the full-screen lyrics area shows "no lyrics" instead of "looking" forever. The on-disk lyrics index is now written to a temp file and renamed, so the car never reads a half-written cache on re-entry.

## Features

- Follows Poweramp playback (notification access + MediaSession), showing the current track and progress
- Lyrics come only from a folder you pick (SAF, picked once, scanned recursively), matched by the audio file's identical filename stem (Poweramp's pairing rule); multi-artist split + title matching as fallback; UTF-8 with GBK fallback
- The file index is pre-built once at startup (1092 `.lrc` files in one pass), never scanned lazily during playback
- Phone: full lyrics list, current line highlighted and auto-followed, tap a line to seek
- Car: full lyrics on the head unit (MapWithContentTemplate + its own surface), with previous / play-pause / next; in full-screen the top-left box shows the album cover (M-note icon when there is none)
- Chinese UI, with an in-app language switch at the top of Settings (follow system / 中文（简体）/ English), effective immediately
- Lyrics size (14–32 sp) and keep-screen-on can be set in Settings; both persist across restarts
- Icon: deep blue (#051E3C), white M-shaped note above a glowing white car, both centred and almost touching; the car has round headlights and a smile

## Install (the APK is not on the Play Store)

1. In Android Auto developer settings, turn on **Unknown sources**, or the app will not appear in the car list.
2. **You must install the APK with KingInstaller** (it installs as the Play installer, which is what makes Android Auto list the app). Download KingInstaller from its GitHub Releases page: https://github.com/fcaronte/KingInstaller/releases
3. Open the app and grant **notification access** when asked (so it can see what Poweramp is playing).
4. Settings → pick the lyrics folder → pick your Music folder → tap Rescan; it should find 1092 `.lrc` files.
5. Play a song (for example 《一千年以后》); the full lyrics should appear on both the phone and the car.

## Build

```bash
source ~/workspace/aalyrics-local/tools/env.sh
cd ~/workspace/my-lrc-auto/mylrcauto
gradle_build :app:testDebugUnitTest   # 53 tests, 0 failures
gradle_build :app:assembleRelease    # signed with the shared keystore
```

- Package `com.dannycom888.mylrcauto`, version 1.0.8 (versionCode 10), minSdk 26
- Signed with the same key as the local-only MyLyrics / Melisma builds (`aalyrics-local`)
- Source: `~/workspace/my-lrc-auto/mylrcauto`, notes: `~/workspace/my-lrc-auto/TASK.md`

## Notes

- AGP 9.4: do not add the `org.jetbrains.kotlin.android` plugin (AGP 9 has Kotlin support built in and it errors); use `kotlin { jvmToolchain(17) }` instead of `kotlinOptions`; the Compose BOM must be written as `implementation(platform(...))` or versions will not resolve.
- `AppContainer` is a nested class of `MyLrcAutoApp`; refer to it as `MyLrcAutoApp.AppContainer`.
- The car head unit can only be tested in a real car; do not send the APK as a chat attachment (it arrives corrupted) — use a browser download link or a GitHub Release.
