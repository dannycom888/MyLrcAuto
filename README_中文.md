# My LRC Auto 1.0.6

用户自己的原创 App：只读本地 `.lrc`，跟着 Poweramp 播放，在手机和 Android Auto 车机上显示整首歌词。不联网、不找在线歌词。

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
- 歌词只来自你选的本地文件夹（SAF 选一次，递归扫描），按正在放的音频文件找主干完全相同的 `.lrc`（Poweramp 的配对规则）；多歌手切分 + 歌名匹配做后备；UTF-8 读不了转 GBK
- 启动时预建一次文件索引（1092 个 `.lrc` 也一次扫完），不在播放时现扫
- 手机界面：整首歌词列表，当前行高亮、自动跟随，可点一行快进
- 车机界面：整首歌词显示在车机屏幕上（MapWithContentTemplate + 自己的绘制层），带上一曲/播放暂停/下一曲；全屏时左上小方框显示专辑封面（没有封面的歌显示 M 音符图标）
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
gradle_build :app:testDebugUnitTest   # 47 个测试，0 失败
gradle_build :app:assembleRelease    # 签名用共享 keystore
```

- 包名 `com.dannycom888.mylrcauto`，版本 1.0.6（versionCode 8），minSdk 26
- 签名与 MyLyrics / Melisma 本地版共用同一把 key（`aalyrics-local`）
- 源码：`~/workspace/my-lrc-auto/mylrcauto`，记录：`~/workspace/my-lrc-auto/TASK.md`

## 注意事项

- AGP 9.4：不要加 `org.jetbrains.kotlin.android` 插件（AGP 9 已内置 Kotlin 支持，会报错）；`kotlinOptions` 改成 `kotlin { jvmToolchain(17) }`；Compose BOM 要写成 `implementation(platform(...))` 否则版本解析不到。
- `AppContainer` 是 `MyLrcAutoApp` 的嵌套类，引用要写全 `MyLrcAutoApp.AppContainer`。
- 车机实测需真车验证；APK 不走聊天附件发送（会损坏），走浏览器下载链接或 GitHub Release。
