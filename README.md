# My LRC Auto 1.0.17

An original app: local-only `.lrc` lyrics that follow Poweramp playback, showing the full lyrics on the phone and on the Android Auto head unit. No network, no online lyric lookup.

This software was made with Muse's assistance.

## What's new in 1.0.17

- **"No lyrics" text deleted entirely**: the reminder flashed briefly on every song switch, before the lyrics appeared — the words themselves were the problem, not the lookup. No state in the app shows that text any more. With no lyrics to show, the big screen keeps the cover and the title with an empty lyrics area, the template fallback shows a neutral title/artist pane, and the phone shows nothing in the lyrics area. A failed template build now shows "Something went wrong" instead of the old text.
- **Dashboard card, fixed — AALyrics' sequence**: the card worked in 1.0.13/1.0.14 and disappeared in 1.0.15/1.0.16. Cause: 1.0.15 published the first state synchronously before setting the session active, which made the first publish a gate on the session itself. If that publish threw — a recycled cover bitmap cannot be parceled across Binder, and the snapshot can hold a bitmap Poweramp recycled when it published new metadata — `onCreate` threw, the service never started, the session never went active, and there was no card at all. The activation tracker also recorded "active" after a single start attempt, so nothing ever retried. This version follows the sequence AALyrics uses and 1.0.13/1.0.14 used: the session goes active first, then a complete state — with skip-to-previous / skip-to-next, no rewind/fast-forward — is published synchronously from the current snapshot, guarded so a failed publish is logged and left to the render loop instead of killing the service. Recycled cover bitmaps are left out of published metadata. The activation tracker now re-issues the start when the track changes and retries every 5 seconds while a track is loaded, so a failed start or a service killed mid-drive comes back. A song with no lyrics still gets a card — cover, title and artist with an empty lyric line — so the card stays visible either way.
- **Cover smaller, lower, glowing**: the cover below the top-left pane is now 180dp (was 240dp). Its top edge sits below the pane's horizontal midline with clear space between the pane and the cover, instead of hugging the pane's bottom edge. A soft glow runs around the cover's edge, in the same colour as the glowing current lyric line. Still sharp, still with the lyrics full width and the rest of the screen black.
- **Top-left pane names the app again**: the pane shows "My LRC Auto" as its title strip, above the song title and artist. The strip was removed in 1.0.10 to narrow the host-drawn box.
- **Icon unchanged** (v22).

## What's new in 1.0.16

- **Lyrics font is now a choice (Settings → Lyrics font)**: two options — **Regular script** (the Bauhaus ITC font provided for this app, Chinese lyrics bold) and **System default** (the original font, bold only for the line being sung, as before v1.0.15). **Default is Regular script**, so nothing changes unless the setting is switched. English lyrics keep the default font either way. The choice applies to the car big-screen lyrics and the phone lyrics view at once; the dashboard card's subtitle is drawn by the car itself, so its font cannot change.
- **Icon unchanged** (v22).

## What's new in 1.0.15

- **Card buttons are previous / next track — for real this time**: 1.0.14 already published skip actions, but the card still showed the 10s buttons. The cause: the session was set active (`isActive = true`) in `onCreate` before the first playback state was ever published — the first publish only happened later, when the async combine flow emitted. The car host read the session's actions right at activation, got an empty set, and locked the card's button layout to the 10s fallback; later publishes never made it re-derive the buttons. (The car screen's own media bar showed prev/next at the same moment, so the two host UIs disagreed about the same session.) This version publishes a full playback state — with skip-to-previous / skip-to-next, no rewind/fast-forward — synchronously from the current snapshot in `onCreate`, **before** setting the session active, and every later update goes through the same single publish path. Pause/play is unchanged. A test now drives the real publish path and asserts the live session's actions include skip and exclude rewind/fast-forward.
- **Chinese lyrics font**: Chinese lines now draw in Bauhaus ITC (the font provided for this app), **bold**; English lines keep the current font, unchanged. The font is bundled in the APK, which is why the APK is now about 19 MB instead of 9 MB. It applies to the app's own surfaces — the car big-screen lyrics and the phone lyrics view — with the glowing current-line highlight and the lyrics-size setting working as before. The dashboard card's subtitle is drawn by the car itself, so its font cannot change.
- **Cover twice as big**: the cover below the top-left pane is now 240dp (was 120dp), still sharp, still with the lyrics full width and the rest of the screen black.
- **Background is always all black**: the background choice and the blur slider are gone from Settings, and the now-dead blur computation code is removed. The big screen's background is always black.
- **Settings shows the version**: the bottom of Settings now shows "Version 1.0.15" (read from the app package, so it updates itself every release). The Settings list is also scrollable now — the rows below the fold were unreachable before — and the rows are tighter, with less empty space between items.
- **Icon unchanged** (v22).

## What's new in 1.0.14

- **Background setting (Settings → 背景)**: the big screen's background is now either the current track's cover, blurred (封面), or all black (全黑) — the choice. **Default is all black**. The 封面 style has a 封面模糊 slider (0–25 px, default 12) to adjust how blurred the cover is. The blurred cover is computed once per cover change and cached, never re-blurred on the playhead tick, so it cannot jank the scrolling. With no cover art, the 封面 style falls back to the dark background — never blank or white. The glowing current-line highlight is unchanged, and the all-black default makes it stand out even more.
- **Cover moved below the pane**: the small cover now sits below the top-left pane box and the rest of the screen is black — the earlier claim that the area below the pane is not drawable was wrong. The host grants the app the full surface (the visible area's left edge sits at about 60% of the surface width, so the surface does extend left of it, below the pane included), and Melisma's photos prove the host draws nothing below the pane. So the cover column beside the lyrics is gone; the lyrics now use the full width, and the cover (120dp, sharp) sits below the pane.
- **Dashboard card, start fix**: 1.0.13 is installed and still showed no card until a phone restart, so the background start from the app container genuinely failed (blocked while the phone sat in the pocket). This version ALSO starts the mirror service from the car app itself — when the car session starts and when the main screen is created — because at that moment the app is the foreground car app and starting the service is allowed. If `startService` throws, it falls back to `startForegroundService` with a minimal silent notification. The app-container snapshot hook is kept. **The session now also stays active while a track is loaded but paused** (1.0.13 stopped it on pause, so the card fell back to Poweramp's the moment playback paused). The mirror never plays audio itself.
- **"No lyrics" flash, gone**: the template used to flash "no lyrics" while the lookup was still running (a fresh process walks 1,092 .lrc files, and the 30s timeout demoted "looking" to "no lyrics" before the lyrics arrived). Now "no lyrics" only appears after the lookup has actually completed and found no match — while it is still looking, the loading pane shows.
- **Lyrics scroll again**: the big-screen list had stopped following the song. The playhead was frozen, not the scroll logic: the 500ms tick state was recreated every time the lyrics loaded, while the ticker kept incrementing the old one, so the current line never advanced. Fixed — the tick is one stable state now, and the list follows the current line in real time from the start instead of sitting still for a minute and then jumping.
- **Card buttons are previous / next track**: the dashboard card used to show 10s rewind / fast-forward buttons. Poweramp supports track skipping but does not advertise it, so the mirror published no skip actions and the car fell back to the 10s buttons. The mirror now always offers previous/next track (forwarded to Poweramp); pause/play is unchanged.
- **Icon unchanged** (v22).

## What's new in 1.0.13

- **Dashboard card, fixed for real this time**: 1.0.12 added the mirror media session, but the car host only starts a `MediaBrowserService` when the user selects it — and the flow here is Poweramp selected, then tap the app, and tapping the app opens the template screen, never the browser service, so the service never started and the card stayed Poweramp's. AALyrics works with exactly that flow because its session is active as soon as playback is detected. This version does the same: the app itself starts the mirror service the moment a track is playing and stops it when playback stops or there is no track. **No picker selection is needed — the 1.0.12 instruction to pick My LRC Auto as the media source was wrong.** The card and the full-screen media view show one lyric line, progress, and transport, the way AALyrics does; the session never plays audio itself — its buttons forward to Poweramp. Only the car test (select Poweramp, tap My LRC Auto) can prove the card appears.
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

- **Lyrics size** in Settings: a slider from 14 to 32 sp (default 17 sp, the original size). It sizes the lyrics on the phone and on the car together, and the choice is kept across restarts.
- **Keep screen on** switch in Settings: while lyrics are showing, the phone screen stays on; leaving the lyrics screen returns to normal. The choice is kept across restarts.
- **New icon**: deep-blue background (#051E3C), a white M-shaped note and a glowing white car, both centred on the same axis and almost touching; the car has round headlights and a smile.

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
- Lyrics come only from a folder picked in Settings (SAF, picked once, scanned recursively), matched by the audio file's identical filename stem (Poweramp's pairing rule); multi-artist split + title matching as fallback; UTF-8 with GBK fallback
- The file index is pre-built once at startup (1092 `.lrc` files in one pass), never scanned lazily during playback
- Phone: full lyrics list, current line highlighted and auto-followed, tap a line to seek
- Car: full lyrics on the head unit (MapWithContentTemplate + its own surface), with previous / play-pause / next; in full-screen the cover sits below the top-left pane (M-note icon when there is none)
- Chinese UI, with an in-app language switch at the top of Settings (follow system / 中文（简体）/ English), effective immediately
- Lyrics size (14–32 sp) and keep-screen-on can be set in Settings; both persist across restarts
- Icon: deep blue (#051E3C), white M-shaped note above a glowing white car, the note slightly left of the car's centre; the car has round headlights and a smile

## Install (the APK is not on the Play Store)

1. In Android Auto developer settings, turn on **Unknown sources**, or the app will not appear in the car list.
2. **Install the APK with KingInstaller** (it installs as the Play installer, which is what makes Android Auto list the app). Download KingInstaller from its GitHub Releases page: https://github.com/fcaronte/KingInstaller/releases
3. Open the app and grant **notification access** when asked (so it can see what Poweramp is playing).
4. Settings → pick the lyrics folder → pick the Music folder → tap Rescan; it should find 1092 `.lrc` files.
5. Play a song (for example 《一千年以后》); the full lyrics should appear on both the phone and the car.

## Build

```bash
source ~/workspace/aalyrics-local/tools/env.sh
cd ~/workspace/my-lrc-auto/mylrcauto
gradle_build :app:testDebugUnitTest   # 135 tests, 0 failures
gradle_build :app:assembleRelease    # signed with the shared keystore
```

- Package `com.dannycom888.mylrcauto`, version 1.0.17 (versionCode 19), minSdk 26
- Signed with the same key as the local-only MyLyrics / Melisma builds (`aalyrics-local`)
- Source: `~/workspace/my-lrc-auto/mylrcauto`, notes: `~/workspace/my-lrc-auto/TASK.md`

## Notes

- AGP 9.4: do not add the `org.jetbrains.kotlin.android` plugin (AGP 9 has Kotlin support built in and it errors); use `kotlin { jvmToolchain(17) }` instead of `kotlinOptions`; the Compose BOM must be written as `implementation(platform(...))` or versions will not resolve.
- `AppContainer` is a nested class of `MyLrcAutoApp`; refer to it as `MyLrcAutoApp.AppContainer`.
- The car head unit can only be tested in a real car; do not send the APK as a chat attachment (it arrives corrupted) — use a browser download link or a GitHub Release.
