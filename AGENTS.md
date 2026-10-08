# Agent 工作标准

本文件适用于这个仓库中的所有文件。先确认当前工作目录和 Git 仓库是 `yuyin-music-mobile`；PC 版在独立仓库 `yuyin-music`，不要将手机修改提交或打包到 PC 项目。

## 先读与定位

- 产品范围与环境：[README.md](README.md)。
- **所有界面 / 样式改动先读 [docs/UI-GUIDE.md](docs/UI-GUIDE.md)**。
- 原生能力改动先读 [docs/ANDROID-TESTING.md](docs/ANDROID-TESTING.md)。
- 文件、签名、版本和发布：[docs/PROJECT-MANAGEMENT.md](docs/PROJECT-MANAGEMENT.md)。
- 验证结果属于对应历史版本；`docs/QA*.md` 不是新改动的自动证明。

| 要修改的内容 | 起点 |
| --- | --- |
| 配色 / 品牌变量 | `src/theme.css` |
| 字体 / 间距 / 布局 / 组件状态 | `src/styles.css` |
| 文案 / 页面结构 / 点击交互 | `src/App.tsx` |
| 歌单 / 歌词 / 账号 / 搜索业务 | `src/domain/` |
| 原生调用及预览适配 | `src/native/api.ts`、`src/native/contract.ts` |
| Android 窗口、播放与存储 | `android/app/src/main/java/com/yuyin/music/mobile/` |
| 启动页 / 状态栏 / 导航栏主题 | `capacitor.config.ts`、`android/app/src/main/res/values*/` |
| 业务 / 原生 / 界面验证 | `tests/`、`android/app/src/test/`、`android/app/src/androidTest/`、`scripts/verify-ui.mjs` |

## 界面约束

1. 用语义 CSS 变量调整全局配色，用组件规则调整局部布局；同步相关状态和 Android 系统主题，按 UI-GUIDE 的入口逐项检查。
2. 默认保持米白与鼠尾草绿、声波品牌、唱片和深绿歌词高亮。任务要求新风格时按要求完成，同时更新规范中的现行配色与规则。
3. 共享品牌不等于共用布局。手机保留触摸菜单、底部导航和操作弹层；主要触控目标至少 44 像素，考虑安全区、横屏与窄屏。
4. WebView 最低版本 91；保留 `chrome91` 构建目标，使用 `vh` 与明确状态类，不依赖 `dvh`、`:has()` 或未兼容的新 JS API。
5. 跨平台视觉更改在两个仓库分别修改和验证，不引用 PC 的源码、依赖或产物。

## 数据与行为约束

- 不读取、打印、提交或清除真实账号 Cookie、音乐库、偏好和发布签名。验证用独立浏览器上下文、虚构数据或专用模拟器。
- 不用存储重置解决迁移和写入问题；保留损坏的原记录，等待原生保存成功后再更新界面。
- 搜索不自动播放；默认 Bilibili 字幕，可切换 LRCLIB，歌词偏移按歌曲和来源保存。
- 不把 Android 测试宿主或媒体注入接口加入 Release；原生桥接保留域名 / 路径白名单、限量和超时。
- 不将 Bilibili 登录凭据传入 React，也不把 PC 的账号状态复制到手机。

## 验证与版本

代码改动运行 `npm run typecheck`、`npm test` 和 `npm run build`。样式或结构改动运行 `scripts/verify-ui.mjs` 并检查相关截图；原生改动运行应用单元测试，按 ANDROID-TESTING 在独立设备验证相关行为。只改文档时核对事实和链接即可。

完成报告区分网页、受控原生媒体和真实 Bilibili / 手机测试，列出实际结果及未验证范围。不把跳过记为通过，不覆盖历史验证记录来表示新一轮已验证。

版本仅在发布任务中通过 `node scripts/set-version.mjs <version> <versionCode>` 修改，versionCode 递增；同步 CHANGELOG。安装包进入 `artifacts/` 和 GitHub Release，不进源码提交；签名保留在仓库外。提交前检查 diff 与 Git 状态，防止依赖、缓存、账号数据和另一平台文件混入。
