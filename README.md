# 余音手机版

余音的独立 Android 项目，当前版本为 **0.1.1**。界面由 React 构建，Capacitor 连接 Android 原生能力；音源来自 Bilibili 官方视频页面。PC 版继续位于 `D:\音乐播放器`，手机版源码、依赖和安装产物位于 `D:\音乐播放器-手机版`，两者分别管理 Git 历史。0.1.1 按 PC 版统一了米白与鼠尾草绿主题、品牌、唱片元素和歌词高亮，保留适合触屏的布局；详见 [界面规范](docs/UI-GUIDE.md)。

当前 **0.1.1 签名 Release APK** 已完成构建，通过 TypeScript 检查、11 项业务测试、11 项界面检查及 4 项 Android 单元测试；正式包已在 Android 9 模拟器同签名覆盖安装并启动，详见 [0.1.1 验证记录](docs/QA-0.1.1.md)。初版的受控后台播放、3 项原生测试和强制结束后存储恢复结果保留在 [0.1.0 验证记录](docs/QA.md)。真实 Bilibili 登录、实播与后台表现需要在用户手机上验收。

## 已实现的功能

- Bilibili 官方登录窗口、进入应用后的登录验证、真实账号头像与可点击的账号菜单。
- 使用 Bilibili 官方综合排序搜索，保留源站返回的顺序。搜索和翻页始终等待用户选择歌曲，**不会自动播放第一条结果**。
- 原视频页面播放、暂停、继续、进度定位、音量、播放队列、上一首和下一首，以及列表、单曲和随机播放模式。
- 收藏、歌单创建与编辑、播放历史；导入当前 Bilibili 账号的收藏夹为本地歌单，再次导入可合并新歌曲。
- **默认读取 Bilibili 已有字幕**，标注 AI 或 UP 主来源；可切换 LRCLIB 搜索歌词。没有字幕的视频会显示缺失状态，不会伪造歌词。
- 跟随实际播放时间显示歌词；按歌曲和歌词来源分别保存同步偏移，支持点选正在听到的一句进行校准。
- Android 前台播放服务、媒体通知和锁屏媒体控制已接入。原生层在播放前检查音频焦点，HTML5 音频的持续焦点管理由 Chromium / WebView 负责；后台表现以设备验证结果为准。

收藏、歌单、历史、队列、音量、播放模式、歌词来源和歌词校准值保存在 Android 应用私有存储中。界面在收到保存成功的确认后更新；保存失败会提示原因，损坏的原记录会保留。Bilibili 登录信息保留在手机自己的 WebView 会话中，不导入 PC 登录凭据。

## 开发和预览

在手机版目录中执行：

```powershell
npm ci
npm run dev
npm run typecheck
npm test
npm run build
```

浏览器预览可以查看界面并保存本地音乐库；Bilibili 登录和音源播放需要运行 Android APK。网页预览的数据使用独立存储键，与 Android 和 PC 的数据分开。

### 界面验证

需要本机已安装 Google Chrome。先在手机版目录中执行 `npm run dev`，保留开发服务运行；再打开另一个终端执行：

```powershell
node scripts/verify-ui.mjs
```

脚本默认访问 `http://127.0.0.1:5173`，可通过环境变量 `MOBILE_UI_URL` 指定开发服务地址。它使用独立浏览器上下文和纯虚构的歌曲、账号、歌词，通过测试中的请求拦截临时替换原生接口；生产代码没有模拟开关，也不读写真实账号或音乐库。11 项检查覆盖登录入口、手动播放、收藏和歌单持久化、歌词校准、队列切歌、保存失败、账号菜单与 320 / 360 像素窄屏及横屏适配。结果与截图写入 `.qa/mobile-ui/`，不纳入 Git。这些检查用于界面验证，真实 Bilibili 与锁屏播放仍需在 Android 设备上测试。

Android 同步及构建入口：

```powershell
npm run android:sync
npm run android:build
```

构建需要可用的 Node.js、**完整 JDK 21、Android SDK Platform 36、Build-Tools 35.0.0 与 36.0.0，以及 Platform-Tools**。设备需要 **Android System WebView / Chrome 91 或以上**。通过 `JAVA_HOME` 指定 JDK，通过 `ANDROID_HOME` 指定 SDK；本机工具链的实际版本与安装结果记录在 [验证记录](docs/QA.md)。

构建脚本生成签名 Release APK：`artifacts/Yuyin-Mobile-0.1.1.apk`，并生成相邻的 `.sha256` 文件和 `Yuyin-Mobile-0.1.1.release.json`。`artifacts/release.json` 指向最近一次构建记录。后续版本沿用 `Yuyin-Mobile-<版本号>.apk` 文件名。核对实际产物时以该次构建生成的记录为准。旧版本 APK 及版本记录继续保留，手机使用同一发布签名进行覆盖升级。

首次构建会在 `%LOCALAPPDATA%\YuyinMobile\signing` 保存固定的 Release 签名密钥和配置，后续升级复用同一签名。请备份整个签名目录；密钥和配置不进入 Git，也不随 APK 分发。

更新手机版本使用项目自己的脚本，同时递增 Android versionCode：

```powershell
node scripts/set-version.mjs 0.1.2 3
npm run android:build
```

该脚本同步 `package.json`、`package-lock.json` 和 `version.json`，构建时据此设置 Android 版本及产物文件名。更新后同步修改 `CHANGELOG.md`。

## 项目管理

| 内容 | 位置 |
| --- | --- |
| 手机 UI、业务与平台适配 | `src/` |
| Android 原生工程和播放服务 | `android/` |
| 业务回归测试 | `tests/` |
| 构建与版本脚本 | `scripts/` |
| 安装产物 | `artifacts/` |
| 验证记录、管理约定 | `docs/` |
| 本机临时验证材料 | `.qa/`，不纳入 Git |

手机版已同步到独立的私有仓库：[panda472328/yuyin-music-mobile](https://github.com/panda472328/yuyin-music-mobile)。详见 [更新记录](CHANGELOG.md)、[当前版本验证记录](docs/QA-0.1.1.md) 和 [文件与版本管理](docs/PROJECT-MANAGEMENT.md)。手机项目独立提交；公开发布需按用户要求另行处理。
