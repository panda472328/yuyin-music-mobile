# 参与贡献

这个仓库只维护余音 Android 版；PC 版在 [yuyin-music](https://github.com/panda472328/yuyin-music)。问题、修改和发布记录按平台分别管理。项目源码采用 [MIT 许可证](LICENSE)。

## 报告问题

请说明应用版本、Android 版本、WebView / Chrome 版本、机型和重现步骤。播放或歌词问题可以附视频 BV 号、歌词来源、偏移设置与实际现象。界面问题附屏幕方向、分辨率及遮盖个人信息后的截图。

不要在 Issue、日志、提交或附件中提供 Cookie、账号凭据、签名密码和真实音乐库导出。用虚构数据重现保存问题；需要测试播放时先选择独立模拟器。

## 开发环境

按 [README](README.md) 安装 Node.js 24 和锁定依赖，运行 `npm ci`、`npm run dev`。Android 构建还需完整 JDK 21、SDK Platform 36、Build-Tools 35.0.0 / 36.0.0 和 Platform-Tools，设置 `JAVA_HOME`、`ANDROID_HOME`。工程使用自己的 Gradle Wrapper，不提交开发者的 `local.properties`、缓存或依赖目录。

从 `main` 创建描述改动的分支，一次贡献围绕一个具体问题。样式改动先读 [UI-GUIDE.md](docs/UI-GUIDE.md)；原生改动先读 [ANDROID-TESTING.md](docs/ANDROID-TESTING.md)。Agent 入口和源码定位见 [AGENTS.md](AGENTS.md)。

## 代码约定

- 配色在 `src/theme.css`，布局和组件样式在 `src/styles.css`，交互在 `src/App.tsx`。复用现有语义变量，避免为相同视觉用途再定义一套颜色。
- 纯业务逻辑放 `src/domain/`，原生调用经 `src/native/` 契约，不在组件中新增无约束的网络桥接或凭据传递。
- 兼容 WebView 91，保持 Vite 的 `chrome91` 目标；不引入 `.at()`、`dvh`、`:has()` 等未经兼容处理的新 API。
- 保护现有音乐库、偏好和登录会话。保存界面等待落盘成功，失败提示可理解；不要通过重置存储掩盖问题。
- 搜索和翻页等待用户选择歌曲，不自动播放第一条；歌词默认读取 Bilibili 已有字幕。
- 原生播放状态以实际媒体为准，保留独立音源 WebView 与生产 / 测试宿主边界。

## 验证

常规代码改动运行：

```sh
npm run typecheck
npm test
npm run build
```

界面改动保留开发服务，在另一终端运行 `node scripts/verify-ui.mjs`，再检查截图中的实际显示。覆盖登录、首页、搜索、歌单、歌词、设置、菜单、弹层及 320 / 360 像素窄屏和横屏，确认触屏按钮、滚动和底部播放控件可用。不能仅以网页构建成功认定手机界面合格。

原生改动运行 `npm run android:build` 完成应用单元测试和 APK 构建，并根据 [Android 原生验证](docs/ANDROID-TESTING.md) 在独立测试设备执行相关 instrumentation。受控本地媒体通过不代表真实 Bilibili 播放通过；设备测试跳过也不能记为通过。

仅文档改动核对链接、命令和事实，不需要重复完整构建。新增测试覆盖实际行为或风险，避免为低影响的样式调整添加与实现重复的断言。

## 提交和 Pull Request

描述具体问题、修改后的行为、验证命令与结果，以及尚未验证的设备范围。样式修改附相关截图，涉及状态、存储或歌词时说明如何保留兼容。对两个平台共同的修复分别提交，不建立跨仓库运行依赖。

发布版本由 `scripts/set-version.mjs` 更新，Android versionCode 必须递增；同步维护 `CHANGELOG.md` 和验证文档。普通贡献不需要自行改版本号。发布标签、签名和附件约定见 [PROJECT-MANAGEMENT.md](docs/PROJECT-MANAGEMENT.md)。自行构建使用自己的签名，发布密钥不存放在仓库内。

自动发布和应用内更新见 [UPDATES.md](docs/UPDATES.md)。`android-vX.Y.Z` 标签触发 Actions，APK 签名／版本和唯一附件复核通过、Release 公开后才更新 `updates/stable.json`；普通 main 提交不通知客户端。改发布工具时运行 `node --test scripts/release.test.mjs` 和对应 APK 的 `--dry-run`，不得读取本机发布私钥。
