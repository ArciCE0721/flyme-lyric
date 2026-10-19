# FlymeLyric

在 **魅族 Flyme** 状态栏里显示音乐播放器的滚动歌词（目前适配 Apple Music）。

## 功能

- 读取 Apple Music 的播放状态（MediaSession），把当前歌词推送到 Flyme 状态栏
- 自动匹配最佳歌词版本，避免翻唱 / 现场版错配
- 状态栏歌词前可显示 Apple Music 音符图标（可在界面开关）
- 界面为液态玻璃风格：专辑封面模糊背景 + 缓慢流动的光晕动效
- 功耗优化：播放时 700ms 轮询，空闲时退避到 5s，无唤醒锁
- 支持深色 / 浅色模式

## 原理

魅族的状态栏歌词没有公开 API，本项目走的是通知的私有扩展：

```java
// 常驻显示 ticker，直到下一次更新
flags |= 0x1000000;   // FLAG_ALWAYS_SHOW_TICKER
flags |= 0x2000000;   // FLAG_ONLY_UPDATE_TICKER

// 歌词左侧图标（必须是本应用的资源 id）
extras.putInt("ticker_icon", R.drawable.am_note);
extras.putBoolean("ticker_icon_switch", true);
```

通知使用 Apple Music 的 `MediaSession` token，因此歌词会合并进系统媒体卡片，不会多出一条通知。

## 环境要求

- Android 8.0（API 26）及以上，`minSdk 28`
- 仅 **魅族 / Flyme** 系统生效（依赖魅族私有 flag，其他 ROM 不会在状态栏显示）
- 需要安装 Apple Music，并授予本应用**通知使用权**

## 编译

```bash
git clone https://github.com/ArciCE0721/flyme-lyric.git
cd flyme-lyric
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 使用

1. 安装 APK 并打开
2. 点「开启通知使用权」，授予 FlymeLyric 通知读取权限
3. 允许通知权限
4. 在 Flyme 的「手机管家」里把本应用加入自启动 / 后台运行白名单
5. 打开 Apple Music 播放歌曲

## 发布新版本

检查更新读取的是仓库根目录的 `version.json`，**发版时必须同步修改**，否则用户收不到更新提示。

1. 改 `app/build.gradle.kts`：`versionCode` +1，`versionName` 改成新版本号
2. 改根目录 `version.json` 的 `version`，与 `versionName` 保持一致
3. 构建：

   ```bash
   ./gradlew assembleDebug
   ```

4. 提交并推送
5. 在 GitHub 建同版本号的 Release，上传 `app/build/outputs/apk/debug/app-debug.apk`

> `version.json` 通过 jsDelivr 分发，推送后可能有几分钟缓存延迟，属正常现象。

## 项目结构

```
app/src/main/java/com/flymelyric/app/
├── MainActivity.java          # 主界面（玻璃卡片、封面模糊、动效）
├── LyricListenerService.java  # 通知监听 + 状态栏歌词推送
├── LyricsFetcher.java         # 歌词获取与匹配
├── Ttml.java                  # TTML 解析
├── Lrc.java                   # LRC 解析
├── LyricDoc.java              # 歌词接口
├── AuroraView.java            # 背景光晕动效
└── State.java                 # 播放状态
```

## License

本项目采用 [PolyForm Noncommercial 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0) 许可：

- **允许**：个人使用、学习研究、修改代码、Fork、非商业性分发
- **禁止**：任何形式的商业用途（售卖、集成进商业产品、付费服务、商业推广等）
- **衍生作品**：修改后的版本必须保留同样的许可，并注明原作者
- **商标**：许可不授予 Apple、魅族 等商标的使用权

如需商业授权，请联系作者。

> 说明：由于限制了商业用途，这属于「源码可用（source-available）」，并非 OSI 定义的开源许可。

## 免责声明

- 本项目仅用于个人学习与自用，与 Apple、魅族均无关联
- Apple Music 的名称、图标等素材版权归 Apple Inc. 所有
- 歌词内容版权归各自版权方所有，本项目仅做展示
- 使用本软件产生的任何后果由使用者自行承担

## 素材说明

- 应用图标中的文字使用 **Noto Sans SC**（SIL Open Font License 1.1），可自由用于图标渲染
- 应用图标与状态栏图标均为**通用八分音符**，音符图形来自 [Material Icons](https://fonts.google.com/icons)（Apache-2.0），不包含任何音乐服务的品牌 Logo
- 「Apple Music」是 Apple Inc. 的商标，本项目与 Apple 无任何关联，仅用于说明兼容的播放器
- 歌词内容版权归各自版权方所有，本项目仅做展示
