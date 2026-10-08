# Android 发布文件

公开发布页：[android-v0.1.1](https://github.com/panda472328/yuyin-music-mobile/releases/tag/android-v0.1.1)。

| 内容 | 文件 |
| --- | --- |
| Android 预览版最终安装包（唯一手动上传附件） | `Yuyin-Mobile-0.1.1.apk` |

本机发布文件整理到 `artifacts/published/android-v0.1.1/`。它与 PC 的 `release/` 和 Git 仓库独立，原 APK、`.apk.sha256`、版本化 `.release.json` 及本地整理清单保留在 `artifacts/` 归档。GitHub 发布页只上传最终 APK；校验、来源和证书信息写入页面说明。产物、SDK、签名、缓存和 `.qa/` 不进入源码提交。

应用版本为 0.1.1，versionCode 为 2。本次复用已验证的签名包；公开标签包含后续的 MIT、开发规范和包元数据，APK 构建来源与公开源码标签提交分别在发布说明中标明。旧 `mobile-v` 标签保留历史，不改写。

发布说明中的二进制信息：

| 项目 | 值 |
| --- | --- |
| APK 实际构建提交 | `0b4a5dde2f7bf018603526a4a3d5c2445968ad2f` |
| APK SHA-256 | `4867c134f16138f28c372c406cd92192a036277c7698f5d676154de53ffa5184` |
| 签名证书 SHA-256 | `068675cef3bd1e3402408efa3ddf0a26f2827b460945d8780072460cdfc78146` |
| 应用 ID 与版本 | `com.yuyin.music.mobile`，versionName `0.1.1`，versionCode `2` |

项目和第三方完整许可保留在源码仓库的 [LICENSE](../LICENSE)、[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) 与 [licenses/](../licenses/)，发布说明提供这些文档的链接。

源码 ZIP / TAR 由 GitHub 根据公开发布标签提供。开发与签名、以后版本的发布规则见 [PROJECT-MANAGEMENT.md](PROJECT-MANAGEMENT.md)，验证范围见 [QA-0.1.1.md](QA-0.1.1.md)。本轮页面标记为预览版；模拟器与受控媒体结果不代表真实 Bilibili 在所有手机的长时间后台表现已通过。
