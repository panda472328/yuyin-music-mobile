# Android 发布文件

公开发布页：[android-v0.1.1](https://github.com/panda472328/yuyin-music-mobile/releases/tag/android-v0.1.1)。

| 内容 | 文件 |
| --- | --- |
| Android 预览版安装包 | `Yuyin-Mobile-0.1.1.apk` |
| APK 校验值与实际构建记录 | `Yuyin-Mobile-0.1.1.apk.sha256`、`Yuyin-Mobile-0.1.1.release.json` |
| 全部附件校验值 | `SHA256SUMS.txt` |
| 发布来源与附件清单 | `release-manifest.json` |
| 项目及第三方完整许可文本 | `Yuyin-Android-0.1.1-Licenses.zip` |

本机公开附件整理到 `artifacts/published/android-v0.1.1/`。它与 PC 的 `release/` 和 Git 仓库独立，原 APK 及版本化构建记录保留原路径；附件、SDK、签名、缓存和 `.qa/` 不进入源码提交。

应用版本为 0.1.1，versionCode 为 2，APK 的实际构建源码为 `0b4a5dde2f7bf018603526a4a3d5c2445968ad2f`。本次复用已验证的签名包；公开标签包含后续的 MIT、开发规范和包元数据，manifest 分别记录 `binaryBuildCommit` 与 `releaseSourceCommit`。旧 `mobile-v` 标签保留历史，不改写。

源码 ZIP / TAR 由 GitHub 根据公开发布标签提供。开发与签名、以后版本的发布规则见 [PROJECT-MANAGEMENT.md](PROJECT-MANAGEMENT.md)，验证范围见 [QA-0.1.1.md](QA-0.1.1.md)。本轮页面标记为预览版；模拟器与受控媒体结果不代表真实 Bilibili 在所有手机的长时间后台表现已通过。
