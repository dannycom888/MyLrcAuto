# My LRC Auto 1.0

用户自己的原创 App：只读本地 `.lrc`，跟着 Poweramp 播放，在手机和 Android Auto 车机上显示整首歌词。不联网、不找在线歌词。

## 功能

- 跟随 Poweramp 播放（通知访问权限 + MediaSession），显示当前歌、播放进度
- 歌词只来自你选的本地文件夹（SAF 选一次，递归扫描），按正在放的音频文件找主干完全相同的 `.lrc`（Poweramp 的配对规则）；多歌手切分 + 歌名匹配做后备；UTF-8 读不了转 GBK
- 启动时预建一次文件索引（1092 个 `.lrc` 也一次扫完），不在播放时现扫
- 手机界面：整首歌词列表，当前行高亮、自动跟随，可点一行快进
- 车机界面：整首歌词显示在车机屏幕上（MapWithContentTemplate + 自己的绘制层），带上一曲/播放暂停/下一曲
- 中文界面，设置顶部可切换语言（跟随系统 / 中文（简体）/ English），立即生效
- 图标：深蓝底车头 + 前面一个白色 M 形音符

## 安装（APK 不在 Play 商店）

1. 先在 Android Auto 开发者设置里开 Unknown sources，否则车机列表不显示。
2. 用 KingInstaller 安装 APK（以 Play 安装器身份装，Android Auto 才能认出来）。
3. 打开 App，按提示开“通知访问权限”（用来看 Poweramp 在放什么）。
4. 设置 → 选歌词文件夹 → 选 Music 文件夹 → 点 Rescan，应显示 1092 个 `.lrc`。
5. 放一首歌（比如《一千年以后》），手机和车机上都应出整首歌词。

## 构建

```bash
source ~/workspace/aalyrics-local/tools/env.sh
cd ~/workspace/my-lrc-auto/mylrcauto
gradle_build :app:testDebugUnitTest   # 23 个测试，0 失败
gradle_build :app:assembleRelease    # 签名用共享 keystore
```

- 包名 `com.dannycom888.mylrcauto`，版本 1.0（versionCode 2），minSdk 26
- 签名与 MyLyrics / Melisma 本地版共用同一把 key（`aalyrics-local`）
- 源码：`~/workspace/my-lrc-auto/mylrcauto`，记录：`~/workspace/my-lrc-auto/TASK.md`

## 注意事项

- AGP 9.4：不要加 `org.jetbrains.kotlin.android` 插件（AGP 9 已内置 Kotlin 支持，会报错）；`kotlinOptions` 改成 `kotlin { jvmToolchain(17) }`；Compose BOM 要写成 `implementation(platform(...))` 否则版本解析不到。
- `AppContainer` 是 `MyLrcAutoApp` 的嵌套类，引用要写全 `MyLrcAutoApp.AppContainer`。
- 车机实测需真车验证；APK 不走聊天附件发送（会损坏），走浏览器下载链接或 GitHub Release。
