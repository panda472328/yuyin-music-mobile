# 更新记录

## 0.1.1 — 2026-10-08

- 按 PC 版 0.4.9 统一为米白与鼠尾草绿主题，统一字体、品牌、按钮、卡片、菜单、搜索框和播放控件。
- 首页与登录页使用同款黑胶唱片元素，首页文案与 PC 版一致。
- 歌词当前句使用淡绿背景、深绿粗体和左侧圆点，统一歌词来源与校准控件。
- 保留手机底部导航和触屏操作，处理 320 / 360 像素窄屏及横屏布局。
- 将启动页、原生窗口及系统栏背景统一为米白；Android 7 导航栏使用品牌绿保证图标可见。移动端布局状态使用明确的类名，页面高度使用 `vh`，兼容 WebView 91。
- Android versionCode 递增为 2，沿用原发布签名，生成独立的 `Yuyin-Mobile-0.1.1.apk`，保留 0.1.0 产物。
- 通过 11 项业务测试、11 项界面检查及 4 项 Android 单元测试；签名校验与 Android 9 正式包覆盖安装、启动通过。记录见 `docs/QA-0.1.1.md`。

## 0.1.0 — 2026-10-08

建立独立的余音 Android 项目，使用 `com.yuyin.music.mobile` 应用标识，与 PC 版分开目录、依赖、产物及 Git 历史。

- 新增适合手机的发现、音乐库、正在播放与歌词、设置页面，以及触屏账号菜单。
- 接入 Bilibili 官方登录与账号验证、官方综合排序搜索；搜索不自动播放。
- 实现收藏、歌单、播放历史、持久化播放队列及 Bilibili 收藏夹导入。
- 实现默认 Bilibili 字幕、可选 LRCLIB 歌词、按实际进度高亮，以及按歌曲和来源保存的歌词校准。
- 增加 Android 原视频 WebView 播放服务、媒体通知、锁屏媒体会话和播放状态通知；原生层进行播放前音频焦点检查，持续 HTML5 音频焦点管理由 Chromium / WebView 负责。
- 增加原生会话变化检测、网络接口范围限制、来源页面导航检查，以及保存成功确认；会话凭据留在 Android 原生层。
- 通过 TypeScript 检查、11 项业务测试和 11 项隔离界面检查。
- 通过 4 项 Android 单元测试、3 项 Android 9 模拟器原生测试，验证受控媒体的后台、熄屏及音频中断恢复；原生音乐库和设置通过强制结束应用后的存储检查。
- 实际构建出 0.1.0 签名 Release APK，产物按 `artifacts/Yuyin-Mobile-<版本号>.apk` 命名，附带校验文件和构建记录。
- 增加 `scripts/set-version.mjs` 统一更新手机版本与递增的 Android versionCode；固定 Release 签名保存在 `%LOCALAPPDATA%\YuyinMobile\signing`，便于后续覆盖升级。
- 源码同步到独立私有仓库 [panda472328/yuyin-music-mobile](https://github.com/panda472328/yuyin-music-mobile)。

构建要求为 JDK 21、SDK Platform 36、Build-Tools 35.0.0 / 36.0.0 和 Platform-Tools；设备 WebView / Chrome 至少为 91。最终签名包已重建并通过安装启动检查，构建与媒体控制测试结果记录在 `docs/QA.md`。真实 Bilibili 登录、播放、锁屏续播及长时间后台播放需要用户手机验收，本版本作为手机初版进行测试。
