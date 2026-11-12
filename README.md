# My LRC Auto 1.0.13

Your own original app: local-only `.lrc` lyrics that follow Poweramp playback, showing the full lyrics on your phone and on the Android Auto head unit. No network, no online lyric lookup.

This software was made with Muse's assistance.

## What's new in 1.0.13

- **Dashboard card, fixed for real this time**: 1.0.12 added the mirror media session, but the car host only starts a `MediaBrowserService` when the user selects it — and the user's flow is Poweramp selected, then tap the app, and tapping the app opens the template screen, never the browser service, so the service never started and the card stayed Poweramp's. AALyrics works with exactly that flow because its session is active as soon as playback is detected. This version does the same: the app itself starts the mirror service the moment a track is playing and stops it when playback stops or there is no track. **No picker selection is needed — the 1.0.12 instruction to pick My LRC Auto as the media source was wrong, and the user was right to call it out.** The card and the full-screen media view show one lyric line, progress, and transport, the way AALyrics does; the session never plays audio itself — its buttons forward to Poweramp. Only the car test (select Poweramp, tap My LRC Auto) can prove the card appears.
- **App-icon full lyrics list unchanged**: opening the app icon still shows the whole song, scrolling with the highlight, on the big screen.
- **Icon unchanged** (v22).

## What's new in 1.0.12

- **Dashboard card, like AALyrics**: the split-screen card is the car's media card, not a template — the dashboard slot (map on the left) shows the active media session, and a template screen can never appear there, which is why the three-line card never showed in 1.0.9–1.0.11 despite the template fixes. This version adds a media session that mirrors Poweramp: it publishes the cover, title, artist, and the current lyric line (next line as the second line), so the dashboard card and the full-screen media view show one lyric line, progress, and transport, the way AALyrics does. The session never plays audio itself — its play/pause/skip buttons forward to Poweramp. (The 1.0.12 instruction to pick My LRC Auto as the media source was wrong — see 1.0.13, which starts the session itself when Poweramp plays.) Only the car test can prove the card appears.
- **App-icon full lyrics list unchanged**: opening the app icon still shows the whole song, scrolling with the highlight, on the big screen.
- **Icon unchanged** (v22).

## What's new in 1.0.11

- **Split-screen three-line card, second attempt**: with 1.0.10 installed, full-screen works fine (lyrics show, plays normally — the "no lyrics" message is only a brief flash before the big screen appears), but the three-line card still never appears in the split-screen slot. Two changes: (1) the screen now also watches the playing track, the lyrics result and the permission flag directly, and refreshes the moment any of them changes — the foreground-only timer could leave the slot showing a stale template; (2) the screen's own lifecycle is now a template-choice input — a covered screen falls back to the three-line card even when the host kept the surface live and wide, which the width and visible-area checks cannot catch. One unknown remains: if this car keeps the screen in the foreground even in split-screen, the lifecycle input cannot tell the slot apart — the car test will tell.
- **Icon v22**: the car's smile is bolder (about 2× thicker), moved up closer to the eyes, and now glows like the M-note. Everything else is unchanged (v21 composition, deep-blue background #051E3C).

- **Split-screen three-line card, fixed**: the car kept showing the full-screen layout in the dashboard slot and never swapped to the three-line card. The screen now invalidates the moment the surface comes or goes (instead of waiting for the next lyric line), and it checks the granted surface width — a live surface narrower than 600dp is a split-screen slot, so the three-line card (previous / current / next) shows there. An empty visible area also falls back to the card.
- **More room for the lyrics**: the cover column on the left of the full-screen lyrics is narrower (140dp cover in a 172dp column, down from 180dp / 212dp), so the lyrics list gets ~40dp more width. The top-left box in full-screen no longer carries the "My LRC Auto" title strip — an attempt to narrow the box the car draws, since its position and width are decided by the car system and the app cannot move it. Putting the cover below that box was checked and is not possible on this car: the area under the box is host-reserved template space, not the app's surface — the visible area starts to the right of the box, so the cover stays in the column beside the lyrics. No blur anywhere: the cover is drawn sharp, as before.
- **Icon unchanged** (v21).

## What's new in 1.0.9

- **Second song no longer freezes**: when a track changes, the new song now starts from 0 instead of inheriting the old song's near-end position — before, the highlight stayed stuck on the last line and the list never scrolled on the second song. The list also starts back at the top on a new song.
- **Cover moved to the left of the lyrics**: this car's system ignores the cover image in the full-screen title pane, so the cover now sits in a column on the left of the full-screen lyrics (square cover with title and artist underneath), and the lyrics list keeps its full height. Songs without a cover show the app's M-note icon.
- **Crash hardening**: a bad album-cover bitmap can no longer take the car app down, and if building the car screen ever fails, the app now shows a short message instead of the "unexpected error" dialog.
- **Icon**: the M-note moved slightly left (same design otherwise).

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
- Car: full lyrics on the head unit (MapWithContentTemplate + its own surface), with previous / play-pause / next; in full-screen the cover sits in a column on the left of the lyrics list (M-note icon when there is none)
- Chinese UI, with an in-app language switch at the top of Settings (follow system / 中文（简体）/ English), effective immediately
- Lyrics size (14–32 sp) and keep-screen-on can be set in Settings; both persist across restarts
- Icon: deep blue (#051E3C), white M-shaped note above a glowing white car, the note slightly left of the car's centre; the car has round headlights and a smile

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
gradle_build :app:testDebugUnitTest   # 89 tests, 0 failures
gradle_build :app:assembleRelease    # signed with the shared keystore
```

- Package `com.dannycom888.mylrcauto`, version 1.0.13 (versionCode 15), minSdk 26
- Signed with the same key as the local-only MyLyrics / Melisma builds (`aalyrics-local`)
- Source: `~/workspace/my-lrc-auto/mylrcauto`, notes: `~/workspace/my-lrc-auto/TASK.md`

## Notes

- AGP 9.4: do not add the `org.jetbrains.kotlin.android` plugin (AGP 9 has Kotlin support built in and it errors); use `kotlin { jvmToolchain(17) }` instead of `kotlinOptions`; the Compose BOM must be written as `implementation(platform(...))` or versions will not resolve.
- `AppContainer` is a nested class of `MyLrcAutoApp`; refer to it as `MyLrcAutoApp.AppContainer`.
- The car head unit can only be tested in a real car; do not send the APK as a chat attachment (it arrives corrupted) — use a browser download link or a GitHub Release.
