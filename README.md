# 余音手机版

余音的独立 Android 音乐播放器，当前版本为 **0.1.3**。界面由 React 构建，Capacitor 连接 Android 原生能力；音源来自 Bilibili 官方视频页面。手机版与 [PC 版](https://github.com/panda472328/yuyin-music) 使用两个独立仓库，各自管理源码、依赖、版本和安装包。0.1.1 统一的米白与鼠尾草绿主题、品牌、唱片元素和歌词高亮继续保留，布局适合触屏；详见 [界面规范](docs/UI-GUIDE.md)。

**0.1.3** 增加自动检查新版、用户选择下载与 Android 系统安装确认，继续保留 0.1.2 的登录返回与后台播放修复。类型检查、20 项业务测试、13 项隔离界面检查、7 项原生单元测试和 8 项受控设备测试通过；验证范围见 [0.1.3 验证记录](docs/QA-0.1.3.md)。历史结果保留在 [0.1.2](docs/QA-0.1.2.md)、[0.1.1](docs/QA-0.1.1.md) 和 [0.1.0](docs/QA.md)。真实 Bilibili 登录、实播及不同手机的长时间省电表现需要单独验收。

## 下载和安装

从 [GitHub Releases](https://github.com/panda472328/yuyin-music-mobile/releases) 下载 APK，当前版本为 [android-v0.1.3](https://github.com/panda472328/yuyin-music-mobile/releases/tag/android-v0.1.3)。该平台仅上传一个安装附件：`Yuyin-Mobile-0.1.3.apk`；SHA-256、实际 APK 构建提交和签名证书摘要写在发布说明中。0.1.3 / versionCode 4 沿用此前的官方签名，可直接覆盖升级。历史标签保留，不移动到新业务代码。

需要 **Android 7.0 / API 24 以上**，以及 **Android System WebView / Chrome 91 以上**。在手机打开 APK 按系统提示安装，进入后先登录 Bilibili。官方版本使用固定签名，可覆盖升级并保留应用数据。手机版暂未提供系统悬浮歌词。

0.1.3 起支持应用内版本检查、下载和系统安装确认；0.1.2 及更早版本需先手动覆盖安装一次 0.1.3。之后可自动发现并选择更新，Android 安装仍需系统确认。GitHub 手工正式发布联动无需签名 secrets；自动构建的配置见 [更新与发布](docs/UPDATES.md)。普通代码提交不会通知客户端。

## 已实现的功能

- Bilibili 官方登录窗口、验证成功后自动返回、进入应用后的登录验证、真实账号头像与可点击的账号菜单。
- 使用 Bilibili 官方综合排序搜索，保留源站返回的顺序。搜索和翻页始终等待用户选择歌曲，**不会自动播放第一条结果**。
- 原视频页面播放、暂停、继续、进度定位、音量、播放队列、上一首和下一首，以及列表、单曲和随机播放模式。
- 收藏、歌单创建与编辑、播放历史；导入当前 Bilibili 账号的收藏夹为本地歌单，再次导入可合并新歌曲。
- **默认读取 Bilibili 已有字幕**，标注 AI 或 UP 主来源；可切换 LRCLIB 搜索歌词。没有字幕的视频会显示缺失状态，不会伪造歌词。
- 跟随实际播放时间显示歌词；按歌曲和歌词来源分别保存同步偏移，支持点选正在听到的一句进行校准。
- Android 前台播放服务、媒体通知和锁屏媒体控制已接入。原生层只在新音源文档首次尚未播放时预检音频焦点，同文档恢复和持续焦点管理由 Chromium / WebView 负责；后台表现以设备验证结果为准。

收藏、歌单、历史、队列、音量、播放模式、歌词来源和歌词校准值保存在 Android 应用私有存储中。界面在收到保存成功的确认后更新；保存失败会提示原因，损坏的原记录会保留。Bilibili 登录信息保留在手机自己的 WebView 会话中，不导入 PC 登录凭据。

## 开发和预览

建议使用 **Node.js 24**。克隆手机版仓库后，通过锁文件安装依赖：

```powershell
git clone https://github.com/panda472328/yuyin-music-mobile.git
cd yuyin-music-mobile
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

脚本默认访问 `http://127.0.0.1:5173`，可通过环境变量 `MOBILE_UI_URL` 指定开发服务地址。它使用独立浏览器上下文和纯虚构的歌曲、账号、歌词，通过测试中的请求拦截临时替换原生接口；生产代码没有模拟开关，也不读写真实账号或音乐库。12 项检查覆盖登录入口、无会话事件时的登录返回、手动播放、收藏和歌单持久化、歌词校准、队列切歌、保存失败、账号菜单与 320 / 360 像素窄屏及横屏适配。结果与截图写入 `.qa/mobile-ui/`，不纳入 Git。这些检查用于界面验证，真实 Bilibili 与锁屏播放仍需在 Android 设备上测试。

Android 同步及构建入口：

```powershell
npm run android:sync
npm run android:build
```

构建需要 Node.js 24、**完整 JDK 21、Android SDK Platform 36、Build-Tools 35.0.0 与 36.0.0，以及 Platform-Tools**。工程使用 Gradle Wrapper 8.14.3 和 Android Gradle Plugin 8.13.0。设备需要 **Android System WebView / Chrome 91 或以上**。通过 `JAVA_HOME` 指定 JDK，通过 `ANDROID_HOME` 指定 SDK；历史工具链和验证结果见 [验证记录](docs/QA.md)，其中的本机路径不是开发必需路径。

`npm run android:build` 包含类型检查、业务测试、生产构建、Capacitor sync、Android 应用单元测试，并生成 Release、Debug 与 instrumentation 测试 APK。设备测试的执行方法见 [Android 原生验证](docs/ANDROID-TESTING.md)。

构建脚本生成签名 Release APK：`artifacts/Yuyin-Mobile-0.1.3.apk`，并在本地生成相邻的 `.sha256` 文件和 `Yuyin-Mobile-0.1.3.release.json`。`artifacts/release.json` 指向最近一次构建记录。这些校验与来源文件保留在本地归档，GitHub 发布页只上传最终 APK，并将相关摘要写入发布说明。后续版本沿用 `Yuyin-Mobile-<版本号>.apk` 文件名。核对实际产物时以该次构建生成的记录为准。旧版本 APK 及版本记录继续保留，手机使用同一发布签名进行覆盖升级。

首次构建会在本机用户数据目录 `YuyinMobile/signing` 保存固定的 Release 签名密钥和配置，Windows 对应 `%LOCALAPPDATA%\YuyinMobile\signing`，后续升级复用同一签名。请私下备份整个签名目录；密钥和配置不进入 Git，也不随 APK 分发。也可通过 `YUYIN_ANDROID_KEYSTORE`、`YUYIN_ANDROID_KEY_PASSWORD`、`YUYIN_ANDROID_KEY_ALIAS` 指定自己的签名。自行生成的签名与官方签名不同，不能覆盖升级官方安装包。

更新手机版本使用项目自己的脚本，同时递增 Android versionCode：

```powershell
node scripts/set-version.mjs 0.1.3 4
npm run android:build
```

该脚本同步 `package.json`、`package-lock.json` 和 `version.json`，构建时据此设置 Android 版本及产物文件名。更新后同步修改 `CHANGELOG.md`。

## 项目管理

| 内容 | 位置 |
| --- | --- |
| 页面、交互和状态协调 | `src/App.tsx` |
| 品牌配色与手机布局 | `src/theme.css`、`src/styles.css` |
| 音乐库、歌词、账号和搜索业务 | `src/domain/` |
| Capacitor 接口及网页预览适配 | `src/native/` |
| Android 插件、播放服务、资源与清单 | `android/app/src/main/` |
| 业务、原生单元和设备测试 | `tests/`、`android/app/src/test/`、`android/app/src/androidTest/` |
| 构建与版本脚本 | `scripts/` |
| 安装产物 | `artifacts/` |
| 验证记录、管理约定 | `docs/` |
| 本机临时验证材料 | `.qa/`，不纳入 Git |

源码仓库：[panda472328/yuyin-music-mobile](https://github.com/panda472328/yuyin-music-mobile)。贡献前阅读 [CONTRIBUTING.md](CONTRIBUTING.md)；修改样式参照 [UI-GUIDE.md](docs/UI-GUIDE.md)，Agent 工作入口为 [AGENTS.md](AGENTS.md)。详见 [更新记录](CHANGELOG.md)、[当前版本验证记录](docs/QA-0.1.3.md) 和 [文件与版本管理](docs/PROJECT-MANAGEMENT.md)。

安装文件与版本来源见 [发布说明](docs/RELEASE.md)。项目源码采用 [MIT 许可证](LICENSE)，第三方许可及内容归属见 [第三方声明](THIRD_PARTY_NOTICES.md) 和仓库中的 [原始许可文本](licenses/)。音视频、歌词及第三方依赖保留各自的权利和许可证。
