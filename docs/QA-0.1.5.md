# Android 0.1.5 发布验证

日期：2026-10-15。版本 `0.1.5` / versionCode `6`，应用 ID `com.yuyin.music.mobile`。本轮发布 PC 新 UI 的手机实现，保留经典主题、独立登录和数据、手动播放及双源更新。

## 最终文件

| 项目 | 值 |
| --- | --- |
| 唯一 APK | `Yuyin-Mobile-0.1.5.apk` |
| 实际构建与不可变标签提交 | `ce7c3abb613ea9a4ada27d1040ff377a867b76db` |
| 构建记录 | `dirty=false` |
| 字节数 | `5114211` |
| SHA-256 | `eaac052c9fb797889c77bbfc34b9f0a0158768708ffa373b85a65c18c86ae744` |
| 沿用签名证书 SHA-256 | `068675cef3bd1e3402408efa3ddf0a26f2827b460945d8780072460cdfc78146` |
| GitHub 发布时间（UTC） | `2026-10-15T08:38:26Z` |
| GitHub Release / 附件 ID | `413583235` / `643242932` |
| Gitee Release / 附件 ID | `1207438` / `3396897` |

[GitHub 发布页](https://github.com/panda472328/yuyin-music-mobile/releases/tag/android-v0.1.5) 与 [Gitee 发布页](https://gitee.com/openmusic/openmusic-mobile/releases/tag/android-v0.1.5) 各保留一个相同 APK。标签不移到后续文档或更新清单提交，不重新构建或替换公开文件。

## 实际验证

- 正式 `npm run android:build` 完成类型检查、49 项业务测试、生产构建、Capacitor 同步、16 项 Android 单元测试和签名构建；21 项发布保护测试通过。
- `scripts/verify-android-release.mjs` 核对实际 APK 的包名、版本、versionCode 与沿用签名通过；ZIP 检查包含本地像素字体和许可，没有 Debug QA 宿主或签名私钥。
- 新主题、四款独立歌词预设及窄屏 / 横屏检查，以及 5 项实际 Android 原生外观 / 输入测试，见 [本轮 UI 同步记录](QA-UI-SYNC-20261015.md)。这些测试针对同一产品源码，发布提交仅调整版本和更新记录。
- 在专用 Android 9 / API 28、WebView 91 实例，从正式签名 0.1.4 覆盖升级到这份 0.1.5。应用 UID 和首次安装时间不变；虚构收藏 1 首、歌单 1 个、历史 1 条、队列 2 首及偏好设置原始内容逐字节保持一致。启动正常，查看实际 Release 设置页的新歌词预设和字体。没有卸载应用或重置存储。
- GitHub 草稿上传后完整回读，公开后再匿名完整下载，大小和 SHA-256 与本地最终文件一致。[发布事件 Actions 38707109823](https://github.com/panda472328/yuyin-music-mobile/actions/runs/38707109823) 成功独立核对现有 APK 并推进稳定清单。
- Gitee 上传相同字节；官方 `prepare-domestic-update.mjs --prepare` 匿名完整下载国内附件、核对大小和 SHA-256 并回读 Release / 附件身份后生成国内候选。`updates/domestic.json` 使用该候选，GitHub `stable.json` 保留备用路径。

临时日志、截图、构建记录及覆盖升级前后证据在忽略目录 `.qa/release-015/`、`.qa/publication/` 和 `artifacts/`，不提交设备私有数据或安装包。

## 验证边界

本次是独立模拟器上的同签名覆盖升级，不代表各厂商真机或系统安装授权流程均已验收。没有新增真实 Bilibili 账号、网络搜索 / 评论、长时间后台播放或省电策略验收；Android 12+ 悬浮触摸限制保留。Gitee 线上附件由桌面 Node 工具完整校验，未将其描述为本轮 Android 更新器在真机中的完整下载安装测试。安装仍由用户选择并通过 Android 系统确认。
