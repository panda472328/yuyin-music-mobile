# PC 0.5.3 功能同步验证（未发布）

日期：2026-10-14。手机版基于 `77eae2a` 增加 PC 0.5.3 中适合 Android 的功能，参考 PC `a3ab747`；仅修改独立 `yuyin-music-mobile` 仓库，PC 工作区保持干净。此次未递增 0.1.3 / versionCode 4、未更新稳定清单、未替换已发布 APK。

本文保留该次功能移植的验证范围。随后追加的 Gitee 更新器代码与测试见 [国内更新验证](QA-DOMESTIC-UPDATES.md)，下文“未加入 Gitee”是该次功能移植的历史状态；两次源码均未进入正式 APK。

## 同步范围

- 展示品牌 open音乐，保留应用 ID、存储键和兼容产物名。
- 经典、Bilibili、跟随系统明暗、像素主题；旧设置采用经典 / 歌名默认，保留歌词来源和校准值。
- 歌名 / 视频双模式、官方联想、优先音乐 UP 主非 DJ 单曲、连续列表与加载更多；模式切换保留输入和拒绝迟到回应，搜索不自动播放。
- 游客入口，原视频评论读取 / 排序 / 回复分页，以及验证账号后的点赞、评论和回复。
- MV 复用现有音源；新选曲归零、异常跳转保护、快速显示 / 返回的迟到回调保护。

默认音乐 UP 主列表沿用 PC 的公开采样配置，包含音乐私藏馆。排序依据标题、标签、时长和播放量，未检测音频是否无损。Gitee、CDN、断点续传或增量更新没有加入手机版；Android 仍使用原有独立 GitHub 通道。

## 实际验证

| 检查 | 本轮结果 |
| --- | --- |
| `npm run typecheck`、`npm run build` / `android:sync` | 通过；WebView 构建目标仍为 Chrome 91 |
| `npm test` | 43 项通过，无失败、无跳过 |
| `scripts/verify-ui.mjs` | 13 组通过，独立浏览器上下文与虚构响应 |
| `scripts/verify-pc-sync-ui.mjs` | 5 组通过，四主题 / 系统明暗、游客只读、双模式 / IME / 旧回应、保存失败、显式评论与 MV |
| Android 应用单元测试 | 11 项通过：CommentPolicy 4、NativePolicy 4、UpdatePolicy 3 |
| Debug、androidTest、未签名 Release 构建 | 通过；没有使用或读取正式签名密钥 |
| 新独立模拟器 instrumentation | 11 项通过，无失败、无跳过，完整运行 36.158 秒 |

界面覆盖 320×740、360×800 和 844×390；检查经典、Bilibili、系统浅色 / 深色、像素设置页及游客 / 登录评论页截图，页面无横向溢出。原有音乐库、歌单、历史、歌词校准、更新确认和窄屏 / 横屏回归保留。

本轮从模板新建 LDPlayer 实例 `OpenMusic-Sync-QA-20261014`（索引 4，`emulator-5562`），未启动或读取原有实例，不导入真实账号或 PC 会话。原生测试由 2 项契约、5 项内存评论、1 项系统栏主题、2 项受控登录、1 项完整媒体流程组成。评论 POST 是内存受控传输，没有向源站发表评论或点赞。

媒体使用测试自制 H.264 + AAC 视频与 WAV，并拦截网络；实际 Android WebView 的选曲恢复归零、异常跳转纠正、暂停 / 恢复 / 定位、音频焦点、MV 显示 / 返回、后台媒体控制、Activity 重建 / 销毁、播放结束和私有窗口资源释放通过。音乐库与偏好在前后保持一致。

| 媒体场景 | 实际时钟推进 |
| --- | --- |
| 切到系统桌面 | 3.918005 秒 |
| 关闭屏幕 | 4.203626 秒 |
| Activity 销毁后 | 3.419084 秒 |

首次媒体回归发现快速显示 / 隐藏 MV 时迟到的显示回调重新加上全屏样式。修复后重新构建并完整重跑上述 11 项，通过；失败记录没有作为通过证据。

## 本机材料与限制

界面证据在 `.qa/mobile-ui/`、`.qa/pc-sync-ui/`；原生结果在 `.qa/native-sync-build.txt`、`.qa/native-sync-instrumentation.txt`、`.qa/native-sync-playback-evidence.json`。这些文件和 APK 由 Git 忽略，不提交。

本机测试包归档为 `artifacts/testing/pc-sync-20261014/OpenMusic-Mobile-sync-debug.apk`，相邻 `.apk.sha256` 记录校验值。APK 核对应用 ID 为 `com.yuyin.music.mobile`、versionName `0.1.3`、versionCode `4`、最低 API 24、应用展示名 open音乐。

本机测试 APK 是 Debug 签名，版本仍为 0.1.3，不属于正式升级包，不能覆盖官方签名的已安装应用。Release 构建是未签名验证产物，不上传发布页。新源码功能必须经过独立发布、版本号递增和稳定清单推进后，用户才会收到更新。

真实 Bilibili 登录 / 搜索 / 音源 / 评论网络、真实公开写入、正式签名覆盖升级以及各型号手机的长时间锁屏 / 省电表现，本轮未验收。受控通过不代表这些场景已通过。Android 暂无系统悬浮歌词，不把 Windows 桌面歌词当成已同步功能。
