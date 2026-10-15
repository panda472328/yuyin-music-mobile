# Android 0.1.4 发布验证

本轮从干净提交 `2a4e32edbf3479ec876310f8bdafe65177ed8e36` 构建正式 Android 0.1.4 / versionCode 5，沿用既有应用 ID、数据目录和签名。PC 仓库未修改。

## 正式产物

| 项目 | 值 |
| --- | --- |
| 唯一安装文件 | `Yuyin-Mobile-0.1.4.apk` |
| 应用 ID | `com.yuyin.music.mobile` |
| 字节数 | `3371835` |
| SHA-256 | `dcb0b43bc208450a5172663fa24c6c3852d288196aaa28a5c4c38f69eaf50f8c` |
| 构建时间（UTC） | `2026-10-15T02:36:27.335Z` |
| 构建源码与公开标签提交 | `2a4e32edbf3479ec876310f8bdafe65177ed8e36` |
| 正式标签 | `android-v0.1.4` |
| 升级签名证书 SHA-256 | `068675cef3bd1e3402408efa3ddf0a26f2827b460945d8780072460cdfc78146` |
| 干净构建记录 | `artifacts/Yuyin-Mobile-0.1.4.release.json`，`dirty=false` |

最终 APK 的应用 ID、versionName / versionCode 和旧版公开证书通过 `verify-android-release.mjs`。检查最终 APK：不含 Debug QA 页面或 `QaPlaybackActivity`，包含重播时停用旧文档的修复。没有将测试 APK 作为正式包。

## 检查范围

- 发布构建重新执行类型检查、48 项业务测试、生产网页构建（chrome91）、Capacitor 同步、16 项 Android 应用单元测试以及 Release / Debug / androidTest 构建，均通过。
- 发布工具与国内候选工具 21 项测试通过，无失败或跳过。
- 发布前功能验证包含 15 项完整原生回归和 24 组隔离界面检查，详见 [播放回归](QA-PLAYBACK-20261015.md) 与 [完整功能对照](QA-FULL-PARITY.md)。这两项属于版本递增之前的相同业务源码验证，本轮没有重新声称执行。
- 真实游客 Bilibili 播放、重播归零、短时后台与熄屏记录保留在播放回归文档。源站约一分钟后可能主动暂停并要求登录，不能计作长时间熄屏播放成功。

## 正式签名覆盖升级

新建独立 `OpenMusic-Release-014-QA-20261015`，index 6 / `emulator-5566`，Android 9。确认全新实例未安装播放器后，安装正式 0.1.3；仅向这个测试实例写入虚构音乐库、歌单和偏好，再通过 `adb install -r` 覆盖安装最终正式 0.1.4。

升级后 UID 和首次安装时间不变，MainActivity 正常启动；收藏 1 首、歌单 1 个 / 2 首、历史 1 条、队列 2 首、音量 0.36、随机播放、LRCLIB 来源和 1.25 秒校准均保留。原生音乐库与偏好 JSON 在升级及首次启动后逐字相同。游客入口可以进入正式 React 主界面。

该记录证明受控测试库的正式同签名覆盖和原生存储保留，没有登录真实账号，也不代表已验收真实账号收藏夹或所有真机省电行为。证据位于 `.qa/publication/upgrade-014/`，不纳入 Git。

## 更新与发布

GitHub 正式发布时间为 `2026-10-15T02:45:45Z`，[正式发布页](https://github.com/panda472328/yuyin-music-mobile/releases/tag/android-v0.1.4) 只有一个 APK；公开下载经匿名 Range 请求完整重组后，大小和 SHA-256 与本机正式包一致。GitHub 附件摘要与草稿完整回读也一致；`updates/stable.json` 已推进 0.1.4 / 5 并匿名回读核对。

[Gitee 正式发布页](https://gitee.com/openmusic/openmusic-mobile/releases/tag/android-v0.1.4) 的 Release ID 为 `1206583`，唯一附件 ID 为 `3393722`。运行 `prepare-domestic-update.mjs`，从固定官方附件 API 匿名完整下载同一份 APK，大小 / SHA-256 与 GitHub 正式包一致；下载后再回读 Release、附件身份和当前通道，确认未改变后生成候选，再复核并保存为 `updates/domestic.json`。没有写入占位 ID 或保存 CDN 临时签名。

两个通道使用相同的版本、versionCode、字节数和 SHA-256，保留各自固定的官方地址。每个发布页只保留一个最终 APK。本轮正式签名升级通过 ADB 完成；线上国内附件通过匿名下载工具验证，原生双源选择、下载与安装权限的受控结果见既有 [国内验证](QA-DOMESTIC-UPDATES.md)。这不等同于在正式 APK 内完成了 Gitee 下载与系统安装全流程，亦不确认所有手机网络环境可达。

`2026-10-15T02:49:19Z` 再次匿名回读：GitHub latest 为 `android-v0.1.4`，`stable.json` 和 Gitee Contents API 中的 `domestic.json` 均为 0.1.4 / 5，文件摘要、大小、说明和发布时间一致；Gitee 清单与本地候选逐项一致，两个发布页各有一个正确附件。[本版发布事件 Actions](https://github.com/panda472328/yuyin-music-mobile/actions/runs/38672701778) 已完成并成功，复核原始正式 APK 后维护稳定通道。

0.1.3 仅含 GitHub 更新器，需先经 GitHub 或手动覆盖安装本版；0.1.2 及更早需手动安装。本版才包含 Gitee 优先、GitHub 备用的双源更新逻辑。APK 安装仍需用户在 Android 系统中确认，不做静默安装或增量更新。

真实账号长时间播放、真实评论写操作、Android 12+ 悬浮歌词触摸穿透和不同真机的省电策略仍未验证。应用进程被系统终止后不能保证继续播放。
