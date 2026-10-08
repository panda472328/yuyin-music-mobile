# Android 发布文件

0.1.2 发布页：[android-v0.1.2](https://github.com/panda472328/yuyin-music-mobile/releases/tag/android-v0.1.2)。

| 内容 | 文件 |
| --- | --- |
| Android 预览版最终安装包（唯一手动上传附件） | `Yuyin-Mobile-0.1.2.apk` |

0.1.2 / versionCode 3 修复登录成功后未自动返回，以及视频网页因后台隐藏而暂停的问题。安装包沿用此前的正式签名，可覆盖升级并保留应用数据；无需卸载旧版本。

需要 Android 7.0 / API 24 以上，以及 Android System WebView / Chrome 91 以上。本轮是手机预览版；受控 Android 媒体测试的范围见 [QA-0.1.2.md](QA-0.1.2.md)，真实 Bilibili 与具体手机的长时间省电表现仍需单独验收。

## 来源与归档

从已提交的干净手机版源码构建最终签名 APK，脚本将实际构建提交、SHA-256、字节数与版本写入 `artifacts/Yuyin-Mobile-0.1.2.release.json`。最终构建完成后在本文和公开发布说明补齐该次记录；不使用工作树未提交时的预验证包作为最终来源。

签名证书 SHA-256 为 `068675cef3bd1e3402408efa3ddf0a26f2827b460945d8780072460cdfc78146`。应用 ID 为 `com.yuyin.music.mobile`，versionName `0.1.2`，versionCode `3`。

本机公开文件整理到 `artifacts/published/android-v0.1.2/`；原 APK、`.apk.sha256`、版本化 `.release.json` 和本地整理清单保留在 `artifacts/`。GitHub 每个发布页只上传该版本最终 APK；校验、源码来源和证书摘要写在正文。产物、SDK、签名、缓存和 `.qa/` 不进入源码提交。

公开发布标签可以包含最终校验与发布文档提交；APK 的实际构建来源与公开标签提交分别注明。已发布的 0.1.1 与早期 `mobile-v` 标签保留历史，不移动、不覆盖为新版本。

项目和第三方完整许可保留在源码仓库的 [LICENSE](../LICENSE)、[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) 与 [licenses/](../licenses/)，发布说明提供这些文档的链接。

源码 ZIP / TAR 由 GitHub 根据公开发布标签提供。开发、签名和版本管理见 [PROJECT-MANAGEMENT.md](PROJECT-MANAGEMENT.md)。
