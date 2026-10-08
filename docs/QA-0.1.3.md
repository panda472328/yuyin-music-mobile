# 0.1.3 应用内更新验证

验证日期：2026-10-08。本轮在独立 Android 仓库验证更新功能源码；验证时安装的 Debug 应用仍为 0.1.2 / versionCode 3，未来版本夹具为 0.1.3 / versionCode 4。夹具位于独立本机构建目录，仅用于设备测试，不是正式发布 APK。正式 0.1.3 签名包的来源、校验和覆盖升级验收需要分别记录。

## 实现范围

- 应用启动和前台运行时检查固定 GitHub 更新清单，运行期间检查间隔为 6 小时；登录前和设置页均可手动检查。
- 发现新版后由用户选择下载。显示下载进度，可取消、重试；下载完成后再由用户选择安装。
- 原生校验清单版本、Android 平台、受限下载地址、大小、SHA-256、APK 包名、版本名、versionCode 和已安装应用的签名。只允许本仓库 Release URL 及必要的 GitHub 发行 CDN 跳转。
- APK 位于应用私有 `cache/updates`，FileProvider 只导出该目录，并只授予安装器读取权限。更新桥接不接收外部 URL 或安装路径，不调用账号接口或传递 Bilibili Cookie。
- 未获得安装许可时打开本应用的系统授权页，返回后可继续。实际安装由 Android 系统确认；重复点击与应用关闭后的迟到请求有原生保护。
- 更新使用独立 IO 执行器，界面以状态序号忽略迟到回应；不改变播放、保存和账号业务。

## 类型、业务与界面

`npm run typecheck`、`npm test` 与 `npm run build` 通过。业务测试 20 项全部通过，无失败、无跳过；新增更新测试覆盖桥接结果边界、版本、受限 URL、大小、说明、状态序号与定期检查条件。

`node scripts/verify-ui.mjs` 完成 13 项隔离界面检查，页面错误为 0。使用虚构账号和曲目，检查登录、搜索、收藏、歌单、历史、歌词、设置、更新提示、用户选择下载、取消后重试、安装许可提示以及迟到状态不会覆盖新提示。320 / 360 像素窄屏和 844×390 横屏无横向溢出，相关安装按钮经过实际点击。截图也进行了视觉核对。

这些界面检查通过受控原生 facade 完成，不代表真实 Bilibili 网络或真实 APK 安装过程。

## Android 构建与设备

`:app:testDebugUnitTest`、`:app:assembleDebug` 和 `:app:assembleDebugAndroidTest` 通过。7 项单元测试全部通过：现有 NativePolicy 4 项与 UpdatePolicy 3 项，覆盖地址、版本、整数和签名集合边界。

设备是从 LDPlayer 发行模板新增的独立 Android 9 / API 28 实例 `Yuyin-Updates-QA-20261008`，本轮编号为 index 2 / `emulator-5558`。没有克隆、启动或读取日常用户的 index 0。此前独立测试实例 index 1 上的正式签名版本没有被卸载、重签或清除数据；新建实例和此前测试实例在本轮结束后均已停止。

## 原生实际结果

8 项 instrumentation 测试全部通过，无失败、无跳过。重现入口与前置条件见 [Android 原生验证](ANDROID-TESTING.md)。

| 检查 | 数量 | 本轮实际结果与范围 |
| --- | --- | --- |
| `ControlledUpdateTest` | 3 | 严格清单解析、受限 FileProvider、完整受控下载与安装流程通过 |
| `YuyinNativeContractTest` | 2 | 更新桥接方法、应用 ID 与非导出媒体服务通过 |
| `ControlledLoginTest` | 2 | 官方 nav 格式与受控结果驱动的实际登录 Dialog 回归通过 |
| `ControlledPlaybackTest` | 1 | 真实受控音视频的后台、熄屏、焦点、视图重挂与 Activity 生命周期回归通过，耗时 30.851 秒 |

更新流程使用另建的未来 Debug APK，以及通过临时合成测试签名生成的错误签名 APK；没有读取正式发布签名。HTTPS URL handler 只存在于 instrumentation 测试代码，向正式 UpdateManager 提供内存清单和 APK 响应。正式 Release 中没有该 handler 或下载注入接口。

通过的更新场景包括本仓库到 GitHub CDN 的跳转、外部跳转拒绝、HTTP 503、哈希损坏、文件截断、不同签名拒绝、取消下载后恢复可下载状态与重试、正确未来 APK 校验、安装前再次校验、未知来源授权 Intent、只读 `content://` 安装 Intent、重复安装请求只启动一次，以及销毁后的迟到请求不会启动安装。

测试实际打开了 Android 系统安装确认页，确认页显示余音和安装操作，随后通过系统返回取消安装。设备证据记录 `systemInstallerShown=true`。这个流程没有按下确认安装，也没有把测试应用升级成未来版本。

## 数据保持的证明范围

界面测试比较了包含虚构收藏、歌单、历史、队列和设置的隔离存储记录；更新操作前后记录和播放调用次数保持一致。原生更新测试拒绝已有音乐库或偏好记录的设备，验证空的应用库与偏好键在流程后仍未被创建。原生媒体回归也检查其受控数据在流程前后保持一致。

因此，本轮证明更新检查、下载、取消和打开安装确认没有修改这些受控记录。它不替代携带非空 Android 音乐库的正式签名覆盖升级验收；实际覆盖安装后的登录、收藏、歌单和偏好保留需要在正式包验收中分别验证。

## 本机证据与限制

本机证据保留在 `.qa/`，不提交源码，也不作为 Release 附件：

- `.qa/mobile-ui/evidence.json` 与相关截图：13 项界面检查和尺寸验证。
- `.qa/native-updates-0.1.3.log`、`.qa/native-updates-0.1.3-evidence.json`：3 项更新检查与系统安装页证据。
- `.qa/native-contract-updates.log`、`.qa/native-login-updates.log`、`.qa/native-playback-updates.log`：本轮原生回归输出。
- `android/app/build/test-results/testDebugUnitTest/`：本轮 7 项单元测试输出，仅在本机构建目录中保留。

这些结果证明受控模拟器上的原生机制和网页交互。真实 GitHub 下载链路、正式签名 APK 覆盖升级、真实 Bilibili 登录与歌曲网络播放、手机厂商省电限制、长时间锁屏及其他 Android / WebView 版本仍需分别验收。应用进程被系统终止后的持续播放不属于本轮保证范围。
