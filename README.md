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

---

# 中文

原创 App：只读本地 `.lrc`，跟着 Poweramp 播放，在手机和 Android Auto 车机上显示整首歌词。不联网、不找在线歌词。

本软件由 Muse 协助制作。

## v1.0.17 变化

- **“没有歌词”那句提示彻底删了**：每换一首歌，这句提示都会先闪一下、歌词随后才出来——问题出在这句提示本身，不在查找。App 里任何地方都不再显示这句。大屏上没有歌词可显示时，封面和歌名照常显示、歌词区留空；模板回落界面显示只有歌名和歌手的方框；手机歌词区同样留空。模板构建失败时，现在显示“出了点问题”，不再是原来那句。
- **分屏卡片修好了——按 AALyrics 的顺序**：1.0.13 / 1.0.14 时卡片正常，1.0.15 / 1.0.16 时没了。原因：1.0.15 在把会话设成活跃之前，先同步发布了一次状态，第一次发布就成了会话能否建立的关口。发布一旦抛异常——已回收的封面图无法跨 Binder 传输，而快照里可能正拿着 Poweramp 发布新歌信息时回收掉的那张图——`onCreate` 抛错，服务起不来，会话一直没激活，卡片也就一直没有；启动跟踪器又只在第一次启动尝试后就记成了“已活跃”，再也没重试过。这版按 AALyrics 的顺序、也是 1.0.13 / 1.0.14 的顺序：会话先设成活跃，再用当前快照同步发布一次完整状态（含上一首 / 下一首、不含快退 / 快进），发布包在保护里——失败只记日志、交给后续的渲染循环重试，不再能拖垮服务。已回收的封面图不再放进发布的数据里。启动跟踪器现在换歌时重新发起启动、有歌时每 5 秒重试一次，启动失败一次或服务中途被杀掉都能自己回来。没有歌词的歌也有卡片——封面、歌名、歌手都在，歌词行是空的——卡片两种情况都不会消失。
- **封面更小、更靠下、带发光边**：左上小框下面的封面现在是 180dp（原来 240dp），上沿在小框横向中线下方，和小框之间留有空隙，不再贴着小框底边。封面边沿有一圈柔和的发光，和正在唱的那行歌词发光同色。依然清晰，歌词还是满宽，其余部分黑色。
- **左上小框重新显示 App 名**：小框的标题栏恢复显示“My LRC Auto”，在歌名和歌手上面。1.0.10 时为了收窄车机画的方框把这一栏去掉了。
- **图标不变**（v22）。

## v1.0.16 变化

- **歌词字体可以选了（设置 → 歌词字体）**：两个选项——**楷书**（为本 App 提供的 Bauhaus ITC 字体，中文歌词加粗）和**系统自带**（原来的字体，只有正在唱的那行加粗，和 1.0.15 之前一样）。**默认是楷书**，不换就什么都不变。英文歌词两种选法都是原字体不变。选一次，车机大屏歌词和手机歌词视图同时换。分屏卡片的副标题是车机自己画的，字体改不了。
- **图标不变**（v22）。

## v1.0.15 变化

- **卡片按钮改成上一首 / 下一首——这次是真的修了**：1.0.14 其实已经发布了切歌按钮，但卡片上还是 10 秒按钮。原因：会话在 `onCreate` 里一创建就设成了活跃（`isActive = true`），而第一次发布播放状态是在之后——要等异步的 combine 流第一次发出来。车机在会话刚激活那一刻就读了按钮集合，读到的是空的，于是把卡片按钮锁定成了 10 秒后备方案；之后再发布切歌按钮，车机也不重新推导按钮了。（同一时刻车机大屏底部的媒体条显示的是上一首/暂停/下一首——两处车机界面对同一个会话的按钮不一致。）这版在 `onCreate` 里先用当前快照同步发布一次完整状态（含上一首/下一首、不含快退/快进），**再**把会话设成活跃，之后每次更新都走同一条发布路径。暂停/播放不变。新增测试走真实发布路径，断言发布到会话里的按钮含切歌、不含 10 秒快退/快进。
- **中文歌词字体**：中文歌词现在用为本 App 提供的 Bauhaus ITC 字体，**加粗**；英文歌词保持原字体不变。字体打进 APK 了，所以 APK 现在约 19 MB（原来约 9 MB）。只作用于 App 自己的界面——车机大屏歌词和手机歌词视图；正在唱的那行发光高亮和歌词大小设置照常工作。分屏卡片的副标题是车机自己画的，字体改不了。
- **封面放大一倍**：左上小框下面的封面现在是 240dp（原来 120dp），依然清晰，歌词还是满宽，其余部分黑色。
- **背景只剩全黑**：设置里的背景选项和模糊滑块都删了，死掉的模糊计算代码也清了。大屏背景永远全黑。
- **设置里能看到版本号了**：设置最下面现在显示“版本 1.0.15”（从安装包里读，每版自动更新）。设置列表现在也能滚动了——以前下面的行够不着；行距也收紧了，行与行之间空隙更小。
- **图标不变**（v22）。

## v1.0.14 变化

- **背景设置（设置 → 背景）**：大屏背景现在可选当前歌曲的封面（模糊，封面）或全黑（全黑）。**默认全黑**。封面样式带一个封面模糊滑块（0–25，默认 12），可以调模糊程度。模糊封面只在换封面时算一次并缓存，不会在播放进度每次跳动时重算，不会卡歌词滚动。没有封面时，封面样式回落到深色背景——不会空白、不会白屏。正在唱的那行发光高亮不变，全黑默认下更显眼。
- **封面移到小框下面**：小封面现在放在左上小框下面，其余部分是黑色——之前说小框下面画不了是错的。车机给 App 的是整块 surface（可见区域左边缘在 surface 宽度约 60% 处，所以 surface 确实延伸到小框左边、包括小框下面），Melisma 的照片也证明车机在小框下面什么都不画。所以歌词旁边的封面列取消了，歌词用满宽，小封面（120dp，清晰）放在小框下面。
- **分屏卡片，启动修复**：1.0.13 已装上、没重启前还是没有卡片，所以 App 容器里后台启动服务那条路确实失败了（手机在口袋里时被系统拦了）。这版在车机 App 自己启动时也启动镜像服务——车机会话开始和主界面创建时都启动，因为那一刻 App 就是前台车机应用，启动服务是允许的。`startService` 抛异常时改用 `startForegroundService`，带一个最小的静默通知。App 容器的快照触发保留。**会话现在在有歌但暂停时也保持活跃**（1.0.13 一暂停就停掉，卡片立刻变回 Poweramp 的）。镜像会话自己不放声音。
- **“找不到歌词”闪一下，没了**：以前模板会在歌词还在找的时候先闪一句“没有歌词”（新进程要扫 1,092 个 .lrc，30 秒超时就把“正在找”降级成“没有歌词”，歌词随后才到）。现在只有查找真正完成、确实没找到时才显示“没有歌词”——还在找的时候显示加载界面。
- **歌词又会滚动了**：大屏歌词列表之前不跟着歌走了。是播放进度冻住了，不是滚动逻辑坏了：500 毫秒的计时状态每次加载歌词都会被重新创建，而计时器还在给旧的那个加数，所以正在唱的那行永远不前进。修好了——计时状态现在只有一个稳定的，列表从一开始就实时跟着正在唱的那行走，不会静止一分钟再猛跳一大段。
- **卡片按钮改成上一首 / 下一首**：分屏卡片以前是快退 10 秒 / 快进 10 秒。Poweramp 支持切歌但没声明这项能力，镜像会话就没发布切歌按钮，车机只好退回 10 秒按钮。镜像现在只要有歌就提供上一首 / 下一首（转交给 Poweramp）；暂停 / 播放不变。
- **图标不变**（v22）。

## v1.0.13 变化

- **分屏卡片，这次是真的修了**：1.0.12 加了镜像媒体会话，但车机只有用户选它的时候才会启动 `MediaBrowserService`——而操作流程是选 Poweramp，再点 App，点 App 打开的是模板界面，永远不会启动浏览器服务，所以服务一直没起来，卡片还是 Poweramp 的。AALyrics 用完全一样的操作就能出卡片，因为它一检测到播放在会话就活了。这版也一样：App 自己在有歌播放时启动镜像服务，停播或没歌时停掉。**不用选媒体来源——1.0.12 那句“在车机媒体选择器里选 My LRC Auto”是错的。**卡片和全屏媒体界面显示一句歌词、进度和播放控制，和 AALyrics 一样；这个会话自己不放声音——按钮都转交给 Poweramp。卡片到底出不出来，只有按这个操作（选 Poweramp，点 My LRC Auto）上车试了才知道。
- **点图标的大屏整首歌词不变**：点 App 图标还是大屏整首歌词，跟着高亮滚动。
- **图标不变**（v22）。

## v1.0.12 变化

- **分屏卡片，像 AALyrics 那样**：分屏那格是车机的媒体卡片，不是模板——分屏槽（左边地图）显示的是当前媒体会话，模板界面永远进不去，这就是 1.0.9–1.0.11 再怎么修模板、三行小卡片也出不来的原因。这版加了一个镜像 Poweramp 的媒体会话：发布封面、歌名、歌手和正在唱的那句歌词（下一句做第二行），所以分屏卡片和全屏媒体界面会显示一句歌词、进度和播放控制，和 AALyrics 一样。这个会话自己不放声音——播放/暂停/切歌按钮都转交给 Poweramp。（1.0.12 那句“在车机媒体选择器里选 My LRC Auto”是错的——见 1.0.13，App 自己在 Poweramp 播放时启动会话。）卡片到底出不出来，只有车上试了才知道。
- **点图标的大屏整首歌词不变**：点 App 图标还是大屏整首歌词，跟着高亮滚动。
- **图标不变**（v22）。

## v1.0.11 变化

- 分屏三行小卡片再修一次：装了 1.0.10 后，全屏是正常的（歌词出来、正常播放，“没有歌词”只是在大屏出来前闪一下），但分屏那格还是不出三行小卡片。改了两处：（1）界面现在直接盯着正在放的歌、歌词结果和权限看，一变就刷新——以前只靠前台定时，分屏那格的画面可能一直停在旧的；（2）界面自己的前后台状态也成了选模板的条件——被盖住的界面就算 surface 还活着、还很宽，也回落到三行小卡片，宽度和可见区域这两项抓不住“车机不收 surface”的情况。还有一个未知：如果这台车分屏时还让界面保持前台，这个条件就分不出来——车上试了才知道。
- 图标 v22：车脸的笑嘴加粗了（约 2 倍）、往上移了、离眼睛更近了，也像 M 音符一样发光了。其他不变（v21 的构图、深蓝底 #051E3C）。

- 分屏三行小卡片修好了：车机在分屏那格一直显示全屏布局，从不切到三行小卡片。现在 surface 一来一走就立刻刷新界面（不用等下一句歌词），还会看 surface 有多宽——活着但不到 600dp 宽的 surface 就是分屏那格，显示三行小卡片（上一句、正在唱的、下一句）。可见区域为空时也回落到小卡片。
- 歌词更宽了：全屏歌词左边那列封面改窄了（140dp 封面、172dp 一列，原来是 180dp / 212dp），歌词列表多出约 40dp 宽。全屏左上小方框不再带“My LRC Auto”标题——想让车机画的那个框窄一点，因为框的位置和宽度是车机系统定的，App 移不动它。把封面放小框下面也查过了，这台车上做不到：小框下面的区域是车机留给模板的，不是 App 的画面——可见区域从小框右边才开始，所以封面只能留在歌词左边的那一列。没有加任何模糊：封面还是原样清晰显示。
- 图标不变（v21）。

## v1.0.9 变化

- 第二首歌不再卡住不动：切歌时新歌现在从 0 开始，不再继承上一首快播完的位置——以前第二首歌高亮会停在最后一句、列表也不滚动；换歌时列表也回到顶部。
- 封面改到歌词左边：这台车机不显示全屏标题框里的封面图，所以封面现在放在全屏歌词左边的那一列（方形封面，下面是歌名和歌手），歌词列表保持整屏高度。没有封面的歌显示 App 的 M 音符图标。
- 防崩溃加固：坏的专辑封面图不再能把车机 App 拖垮；万一车机界面构建失败，App 现在显示一句提示，而不是弹出“出现意外错误”。
- 图标：M 音符往左挪了一点（其他不变）。

## v1.0.8 变化

- 图标改小了：音符和汽车在图标里缩小了一圈，离边沿留了足够空隙（其他不变：深蓝底 #051E3C、发光白 M 音符、圆眼睛笑嘴的白车）。
- 修了上一首图标：手机歌词界面底部的上一首按钮，现在是下一首的精确镜像（|◀ 对 ▶|）。以前它画出来是个往右歪的高三角、位置还偏低。

- 设置里新增“歌词大小”：滑块调 14–32 sp，默认 17 sp（就是原来的大小），手机和车机上的歌词一起变，重启后保留。
- 设置里新增“保持屏幕常亮”开关：打开后看歌词时屏幕不自动熄灭，离开歌词界面就恢复正常，重启后保留。
- 换了新图标：深蓝底（#051E3C），白色 M 形音符和发光白车都对准中线、几乎贴在一起，车有两个圆眼睛和一张笑嘴。

## v1.0.6 变化

全屏时左上的小方框现在显示专辑封面：封面大图在上面，歌名和歌手在下面；切歌时封面跟着换，没有封面的歌显示 App 的 M 音符图标。封面只取 Poweramp 自己提供的（播放信息里的图，或本地 content:// 封面），不联网下载；分屏三行小卡片、歌词面板滚动、30 秒查找超时这些 v1.0.5 的内容都不变。

## v1.0.5 变化

车机大歌词面板不再卡住不动：跟手机一样每 0.5 秒刷新一次，正在唱的那行会高亮并自动滚动；分屏小卡片现在显示三行——上一句、正在唱的、下一句，正在唱的在中间，点卡片进全屏还是整首歌词，全屏时左上小卡片只剩歌名和歌手、不再重复显示歌词；车机上如果 30 秒还没找到歌词，全屏歌词那里也显示“这首歌没有歌词”，不再一直显示“正在查找”；歌词索引存盘改成先写临时文件再替换，车机重进时不会读到写了一半的缓存。

## v1.0.4 变化

车机不再一直转圈：歌词索引现在会存到手机里，下次打开（包括车机）直接用缓存，不用再等整首歌文件夹扫完，后台会悄悄重建一次保持新鲜；车机上如果 30 秒还没找到歌词，显示“这首歌没有歌词”，不再一直转圈；歌词滚动加了保护，不会再因为滚动越界让车机界面出错。

## v1.0.3 变化

当前播放的歌词行现在显示在列表的第三行，上面留两行歌词做上下文（开头不足两行时从顶行开始）。手机和车机都一样。

## v1.0.2 修复

手机上顶栏名字、设置齿轮、歌名、状态标题、播放按钮全都看不见（黑底黑字）：界面缺了一层 Surface，文字和图标默认用了黑色。手机和车机都已补上，车机歌词颜色也一并修了。

## v1.0.1 修复

开通知权限后回到 App 一直卡在“请开启通知权限”不动：现在回 App 自动重查权限；权限已开但系统没绑上监听服务时强制重绑；服务晚绑上也会接着开始跟播。

## 功能

- 跟随 Poweramp 播放（通知访问权限 + MediaSession），显示当前歌、播放进度
- 歌词只来自选定的本地文件夹（SAF 选一次，递归扫描），按正在放的音频文件找主干完全相同的 `.lrc`（Poweramp 的配对规则）；多歌手切分 + 歌名匹配做后备；UTF-8 读不了转 GBK
- 启动时预建一次文件索引（1092 个 `.lrc` 也一次扫完），不在播放时现扫
- 手机界面：整首歌词列表，当前行高亮、自动跟随，可点一行快进
- 车机界面：整首歌词显示在车机屏幕上（MapWithContentTemplate + 自己的绘制层），带上一曲/播放暂停/下一曲；全屏时封面在左上小框下面（没有封面的歌显示 M 音符图标）
- 中文界面，设置顶部可切换语言（跟随系统 / 中文（简体）/ English），立即生效
- 设置里可调歌词大小（14–32 sp）和保持屏幕常亮，两个设置都保存、重启保留
- 图标：深蓝底（#051E3C），白色 M 形音符在上、发光白车在下，音符略偏左、车在中线，两者几乎贴在一起，车有圆眼睛和笑嘴；图形缩小了一圈，离边沿留了空隙

## 安装（APK 不在 Play 商店）

1. 先在 Android Auto 开发者设置里开 Unknown sources，否则车机列表不显示。
2. 必须用 KingInstaller 安装 APK（以 Play 安装器身份装，Android Auto 才能认出来）。KingInstaller 从它的 GitHub Releases 页下载：https://github.com/fcaronte/KingInstaller/releases
3. 打开 App，按提示开“通知访问权限”（用来看 Poweramp 在放什么）。
4. 设置 → 选歌词文件夹 → 选 Music 文件夹 → 点 Rescan，应显示 1092 个 `.lrc`。
5. 放一首歌（比如《一千年以后》），手机和车机上都应出整首歌词。

## 构建

```bash
source ~/workspace/aalyrics-local/tools/env.sh
cd ~/workspace/my-lrc-auto/mylrcauto
gradle_build :app:testDebugUnitTest   # 135 个测试，0 失败
gradle_build :app:assembleRelease    # 签名用共享 keystore
```

- 包名 `com.dannycom888.mylrcauto`，版本 1.0.17（versionCode 19），minSdk 26
- 签名与 MyLyrics / Melisma 本地版共用同一把 key（`aalyrics-local`）
- 源码：`~/workspace/my-lrc-auto/mylrcauto`，记录：`~/workspace/my-lrc-auto/TASK.md`

## 注意事项

- AGP 9.4：不要加 `org.jetbrains.kotlin.android` 插件（AGP 9 已内置 Kotlin 支持，会报错）；`kotlinOptions` 改成 `kotlin { jvmToolchain(17) }`；Compose BOM 要写成 `implementation(platform(...))` 否则版本解析不到。
- `AppContainer` 是 `MyLrcAutoApp` 的嵌套类，引用要写全 `MyLrcAutoApp.AppContainer`。
- 车机实测需真车验证；APK 不走聊天附件发送（会损坏），走浏览器下载链接或 GitHub Release。
