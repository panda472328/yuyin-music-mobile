# 文件与版本管理

PC 版与手机版使用独立工作目录和独立 Git 仓库，避免一次提交、清理或打包操作同时改变两个平台。手机项目可以复用经过确认的业务逻辑，但复制后在手机仓库内维护，不引用 PC 目录中的源文件、`node_modules` 或安装产物。

| 项目 | 工作目录 | 仓库标识 | 应用标识 |
| --- | --- | --- | --- |
| PC 版 | `D:\音乐播放器` | `yuyin-music` | `com.yuyin.music` |
| Android 版 | `D:\音乐播放器-手机版` | `yuyin-music-mobile` | `com.yuyin.music.mobile` |

GitHub Desktop 应分别添加上述两个本地仓库，按当前项目切换操作。手机仓库的远程发布状态以实际 Git 远程配置为准；仓库使用私有可见性，不把 PC 仓库覆盖成手机版，也不把手机目录放成 PC 仓库中的嵌套工程。

## 目录约定

- `src/`：React 界面、类型、纯业务逻辑和 Capacitor API 适配。Android 调用集中在 `src/native/`。
- `android/`：可维护的原生源码、资源、清单和 Gradle 配置。修改原生功能时以此目录为准。
- `tests/`：音乐库、歌词、搜索、账号状态等业务回归测试。
- `scripts/`：手机项目自己的构建、版本检查和产物整理脚本。
- `docs/`：验证证据的摘要、项目约定和操作说明。
- `artifacts/`：该手机版的 APK 和相应校验信息。构建时在文件名中区分版本和构建类型。
- `.qa/`：截图、临时测试脚本、下载辅助材料等本机工作文件；不得把这里的模拟接口作为生产行为。

`node_modules/`、`dist/`、Gradle 缓存、`android/**/build/`、本机 SDK 路径、安装产物和验证临时材料均由 `.gitignore` 排除。源码仓库保留 `package-lock.json`，便于重现依赖版本。

## 版本约定

手机版本从 **0.1.0** 开始，独立于 PC 的版本号。`version.json` 为 Android 版本来源；Gradle 从中读取 `versionName`、`versionCode`。用 `node scripts/set-version.mjs 0.1.1 2` 同步更新 `version.json`、`package.json` 和锁文件，Android `versionCode` 每次可安装更新递增。更新记录和产物文件名也须匹配。

每次发布记录具体改变、已完成的验证和已知限制。使用能够说明实际改动的提交信息；给已验证的版本打标签时保留平台标识，例如 `mobile-v0.1.0`。PC 的提交或标签不会自动代表手机版已发布。

跨平台共用逻辑有修复时，分别在对应仓库更新、运行有关检查并提交。两个项目可以采取不同发布节奏；手机发布不得重新打包或替换桌面快捷方式指向的 PC 应用。

## 数据与凭据

Android 音乐库和偏好设置位于该应用的私有存储中，WebView 登录会话由手机独立保存；浏览器开发预览另用独立的本地存储键。没有自动同步 PC 音乐库或 PC 登录会话的功能。

签名密钥、密码、环境变量文件和真实账号凭据不进入 Git。当前 `.gitignore` 排除了 `.jks`、`.keystore`、`.p12`、`signing.properties` 和 `.env` 文件。构建记录可以写签名类型与产物校验值，不记录私钥或 Cookie。

本机稳定的手机版发布签名位于 `%LOCALAPPDATA%\YuyinMobile\signing`，每次构建复用同一签名；需要迁移电脑时应私下备份整个签名目录，不上传到仓库。丢失签名后生成的新安装包不能覆盖原版本。可用 `YUYIN_ANDROID_KEYSTORE`、`YUYIN_ANDROID_KEY_PASSWORD`、`YUYIN_ANDROID_KEY_ALIAS` 指定外部签名。

Windows 中文工作目录通过 `%LOCALAPPDATA%\YuyinMobile\workspace` 的目录联接参与 Gradle 构建，实际文件仍保存在手机版目录，不复制或移动源码。这样避免 JDK 的参数文件编码问题。工具链及缓存保存在独立的 `%LOCALAPPDATA%\YuyinMobile\toolchain`，不进入手机或 PC 仓库。

APK 构建、签名和设备安装的具体结果写入 [验证记录](QA.md)，只记录实际执行过的项目。
