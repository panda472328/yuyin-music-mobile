# Android 发布文件

## 0.1.3 应用内更新

正式更新通道：[android-v0.1.3](https://github.com/panda472328/yuyin-music-mobile/releases/tag/android-v0.1.3)，唯一安装文件为 `Yuyin-Mobile-0.1.3.apk`，versionCode 4。旧版先手动覆盖安装这一版本，之后可以自动检查新版并选择更新。安装继续需要 Android 系统确认，沿用原签名、应用 ID 和数据目录。

本轮源码提交使用 `[skip ci]` 标记，先在本机验证和签名构建后手工发布，避免标签构建另一个同版本安装文件。正式发布事件会校验已有 APK 并推进清单，不需要把发布密钥上传 GitHub。实际验证与限制见 [QA-0.1.3.md](QA-0.1.3.md)，校验值和构建来源写在发布说明中。

## 0.1.2（历史）

后续正式标签发布、Actions 签名 secrets 配置和应用内更新流程见 [UPDATES.md](UPDATES.md)。下列内容保留历史 0.1.2 的实际来源与验证结果，不表示新功能已经发布到该安装包。

0.1.2 发布页：[android-v0.1.2](https://github.com/panda472328/yuyin-music-mobile/releases/tag/android-v0.1.2)。

| 内容 | 文件 |
| --- | --- |
| Android 预览版最终安装包（唯一手动上传附件） | `Yuyin-Mobile-0.1.2.apk` |

0.1.2 / versionCode 3 修复登录成功后未自动返回，以及视频网页因后台隐藏而暂停的问题。安装包沿用此前的正式签名，可覆盖升级并保留应用数据；无需卸载旧版本。

需要 Android 7.0 / API 24 以上，以及 Android System WebView / Chrome 91 以上。本轮是手机预览版；受控 Android 媒体测试的范围见 [QA-0.1.2.md](QA-0.1.2.md)，真实 Bilibili 与具体手机的长时间省电表现仍需单独验收。

## 来源与归档

最终签名 APK 从已提交的干净手机版源码构建，记录位于 `artifacts/Yuyin-Mobile-0.1.2.release.json`，`dirty` 为 `false`。公开标签另包含最终校验与验证文档提交，不改变 APK 的实际源码来源。

| 项目 | 值 |
| --- | --- |
| APK 实际构建提交 | `e3d6f41743ae8ec58a2964200ec2071888b92794` |
| APK SHA-256 | `e6e1149714087941fdf6039347ab51425e71a1ce0a54a6511ef26e0984730d81` |
| APK 字节数 | `3309135` |
| 构建时间（UTC） | `2026-10-08T12:34:25.258Z` |
| 签名校验 | APK v2 通过，证书与此前正式版本相同 |

Android 9 专用模拟器已验证从正式 0.1.1 覆盖安装到 0.1.2，首次安装时间保持不变，MainActivity 启动与未登录引导显示正常。正式包没有 Debug 宿主、测试媒体、悬浮窗或录屏权限。

签名证书 SHA-256 为 `068675cef3bd1e3402408efa3ddf0a26f2827b460945d8780072460cdfc78146`。应用 ID 为 `com.yuyin.music.mobile`，versionName `0.1.2`，versionCode `3`。

本机公开文件整理到 `artifacts/published/android-v0.1.2/`；原 APK、`.apk.sha256`、版本化 `.release.json` 和本地整理清单保留在 `artifacts/`。GitHub 每个发布页只上传该版本最终 APK；校验、源码来源和证书摘要写在正文。产物、SDK、签名、缓存和 `.qa/` 不进入源码提交。

公开发布标签可以包含最终校验与发布文档提交；APK 的实际构建来源与公开标签提交分别注明。已发布的 0.1.1 与早期 `mobile-v` 标签保留历史，不移动、不覆盖为新版本。

项目和第三方完整许可保留在源码仓库的 [LICENSE](../LICENSE)、[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) 与 [licenses/](../licenses/)，发布说明提供这些文档的链接。

源码 ZIP / TAR 由 GitHub 根据公开发布标签提供。开发、签名和版本管理见 [PROJECT-MANAGEMENT.md](PROJECT-MANAGEMENT.md)。
