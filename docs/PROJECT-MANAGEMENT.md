# 文件与版本管理

PC 版与手机版使用独立工作目录和独立 Git 仓库，两个仓库可放在本机任意两个目录中。手机项目可以复用经过确认的业务逻辑，但复制后在手机仓库内维护，不引用 PC 目录中的源文件、`node_modules` 或安装产物。

| 项目 | 仓库 | 应用标识 | 当前版本 |
| --- | --- | --- | --- |
| PC 版 | [yuyin-music](https://github.com/panda472328/yuyin-music) | `com.yuyin.music` | `0.4.10` |
| Android 版 | [yuyin-music-mobile](https://github.com/panda472328/yuyin-music-mobile) | `com.yuyin.music.mobile` | `0.1.3` / versionCode `4` |

GitHub Desktop 应分别添加上述两个本地仓库，执行操作前确认当前项目。不把 PC 仓库覆盖成手机版，也不把手机目录放成 PC 仓库中的嵌套工程。共同视觉规范的变更在两个仓库分别修改、验证和提交。

## 目录约定

- `src/`：React 界面、类型、纯业务逻辑和 Capacitor API 适配。Android 调用集中在 `src/native/`。
- `android/`：可维护的原生源码、资源、清单和 Gradle 配置。修改原生功能时以此目录为准。
- `tests/`：音乐库、歌词、搜索、账号状态等业务回归测试。
- `scripts/`：手机项目自己的构建、版本检查和产物整理脚本。
- `docs/`：验证证据的摘要、项目约定和操作说明。
- `artifacts/`：该手机版的 APK 和相应校验信息。构建时在文件名中区分版本和构建类型。
- `.qa/`：截图、临时测试脚本、下载辅助材料等本机工作文件；不得把这里的模拟接口作为生产行为。

`node_modules/`、`dist/`、Gradle 缓存、`android/**/build/`、本机 SDK 路径、生成的网页资源、安装产物和验证临时材料均由 `.gitignore` 排除。源码仓库保留 `package-lock.json` 和 Gradle Wrapper，依赖安装使用 `npm ci`。

## 版本约定

手机版本从 **0.1.0** 开始，独立于 PC 的版本号；当前为 **0.1.3** / versionCode **4**。`version.json` 为 Android 版本来源；Gradle 从中读取 `versionName`、`versionCode`。下一版可用 `node scripts/set-version.mjs 0.1.4 5` 同步更新 `version.json`、`package.json` 和锁文件，Android `versionCode` 每次可安装更新递增。更新记录和产物文件名也须匹配。

每次发布记录具体改变、已完成的验证和已知限制。使用能够说明实际改动的提交信息；公开版本标签保留平台标识，例如 `android-v0.1.1`。早期 `mobile-v0.1.0`、`mobile-v0.1.1` 标签作为历史验证记录保留，不移动到开源文档提交。PC 的提交或标签不会自动代表手机版已发布。

跨平台共用逻辑有修复时，分别在对应仓库更新、运行有关检查并提交。两个项目可以采取不同发布节奏；手机发布不得重新打包或替换桌面快捷方式指向的 PC 应用。

## 发布约定

自动发布流程和 `updates/stable.json` 维护见 [UPDATES.md](UPDATES.md)。推送 `android-vX.Y.Z` 标签可触发 Actions，沿用 secrets 中的正式签名构建、核对实际 APK 并公开唯一附件后才推进稳定通道；手工正式发布已有 APK 也会触发校验和清单推进，不需要签名 secrets。不能通过替换同版本文件发布更新。

1. 完成与改动有关的类型、业务、界面或原生检查，在验证记录中区分实际执行、跳过和未验证的内容。
2. 从已提交的干净源码构建签名 Release APK，核对版本、应用 ID、签名和 SHA-256，保留脚本生成的构建记录。
3. 更新 `CHANGELOG.md` 和对应版本的验证文档，给发布提交打 `android-v<版本号>` 标签并推送。
4. 在 [GitHub Releases](https://github.com/panda472328/yuyin-music-mobile/releases) 仅上传一个最终 APK 附件，在发布说明中写出 SHA-256、实际 APK 构建提交、公开源码标签提交、签名证书摘要、系统要求、改动与验证限制；提供仓库中 `LICENSE`、`THIRD_PARTY_NOTICES.md` 和 `licenses/` 的链接。

构建记录中的 `commit` 指向 APK 的实际源码来源。后续仅增加许可证、文档与项目元数据的提交可以作为公开发布标签，但必须明确这个差别，不能把旧包描述成由新业务代码构建。0.1.1 APK 来源为 `0b4a5dde2f7bf018603526a4a3d5c2445968ad2f`，`android-v0.1.1` 对应开源整理；0.1.2 是修复登录和后台播放后的新构建，沿用原签名，具体来源以该版本构建记录及发布说明为准。

本地安装包使用 `Yuyin-Mobile-<版本号>.apk` 及对应 `.apk.sha256` 和 `.release.json`。`artifacts/release.json` 仅表示本机最近一次构建；校验文件、构建记录和本地整理清单保留在 `artifacts/`。发布页的手动上传附件只保留该版本最终 APK，许可文本保留在源码仓库。已发布标签和安装包保留用于追溯，不覆盖为另一个版本的内容。

## 数据与凭据

Android 音乐库和偏好设置位于该应用的私有存储中，WebView 登录会话由手机独立保存；浏览器开发预览另用独立的本地存储键。没有自动同步 PC 音乐库或 PC 登录会话的功能。

签名密钥、密码、环境变量文件和真实账号凭据不进入 Git。当前 `.gitignore` 排除了 `.jks`、`.keystore`、`.p12`、`signing.properties` 和 `.env` 文件。构建记录可以写签名类型与产物校验值，不记录私钥或 Cookie。

默认稳定签名位于本机用户数据目录 `YuyinMobile/signing`，Windows 对应 `%LOCALAPPDATA%\YuyinMobile\signing`，每次构建复用同一签名；需要迁移电脑时应私下备份整个签名目录，不上传到仓库。丢失签名后生成的新安装包不能覆盖原版本。可用 `YUYIN_ANDROID_KEYSTORE`、`YUYIN_ANDROID_KEY_PASSWORD`、`YUYIN_ANDROID_KEY_ALIAS` 指定外部签名。

Windows 中文工作目录通过用户数据目录 `YuyinMobile/workspace` 的目录联接参与 Gradle 构建，实际文件仍保存在手机版目录，不复制或移动源码。这样避免 JDK 的参数文件编码问题。工具链及缓存保存在独立的 `YuyinMobile/toolchain`，不进入手机或 PC 仓库。开发者配置自己的 JDK / SDK，不需要历史验证机器的绝对路径。

APK 构建、签名和设备安装的具体结果按版本记录：[0.1.3](QA-0.1.3.md)、[0.1.2](QA-0.1.2.md)、[0.1.1](QA-0.1.1.md)、[0.1.0](QA.md)，只记录实际执行过的项目。每份 APK 同时保留对应的 `.sha256` 与 `.release.json`；`artifacts/release.json` 为最近一次构建记录。

历史记录中的路径、机器和临时证据保持原意，不充当新一轮验证结果。
