# Android 原生验证

本文记录 0.1.2 的受控原生测试入口与验证边界。历史 0.1.0 的 WAV 后台测试及具体时间保留在 [0.1.0 验证记录](QA.md)；不能直接作为本次修复的通过证据。0.1.2 的实际结果见 [本轮验证记录](QA-0.1.2.md)。

本轮使用从发行模板新建的独立 LDPlayer Android 9（API 28）x86_64 实例 `emulator-5556`，WebView 91.0.4472.114。没有启动、复制或读取原有模拟器的账号、音乐库、设置，也没有导入 PC 登录会话。测试设备编号仅是本轮环境标识，复现时须替换为自己明确选定的独立模拟器。

## 重现

先配置项目要求的 Node、JDK 与 Android SDK，运行 `npm run android:build`。该脚本执行网页检查、Capacitor sync、Android 应用单元测试，并生成 Release、Debug 和 instrumentation APK。原生回归安装下面两份 Debug 包；正式签名包的构建来源、签名和安装验收另行记录。

仅在独立测试设备执行以下命令。已有真实登录状态或日常音乐库的设备不适用于此受控流程，不使用清除数据来绕过跳过条件。

```powershell
adb -s emulator-5556 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# 2 项：插件方法、独立应用 ID 和非导出媒体播放服务
adb -s emulator-5556 shell am instrument -w -r -e class com.yuyin.music.mobile.YuyinNativeContractTest com.yuyin.music.mobile.test/androidx.test.runner.AndroidJUnitRunner

# 2 项：官方 nav 结果校验、受控结果驱动的实际登录 Dialog
adb -s emulator-5556 shell am instrument -w -r -e class com.yuyin.music.mobile.ControlledLoginTest -e yuyinControlledEmulator true com.yuyin.music.mobile.test/androidx.test.runner.AndroidJUnitRunner

# 1 项完整媒体流程：会切到桌面、关闭/唤醒屏幕，并重建/销毁测试 Activity
adb -s emulator-5556 shell am instrument -w -r -e class com.yuyin.music.mobile.ControlledPlaybackTest -e yuyinControlledEmulator true com.yuyin.music.mobile.test/androidx.test.runner.AndroidJUnitRunner

# 仅完整媒体流程成功后写入；读取时确认它来自本轮执行，而非旧文件
adb -s emulator-5556 shell run-as com.yuyin.music.mobile cat cache/qa-controlled-playback-evidence.json
```

共 5 项 instrumentation 测试，必须保留本轮输出并核对失败与跳过。登录 Dialog 流程要求显式 `yuyinControlledEmulator=true` 且没有身份 Cookie；媒体流程还要求没有正在运行的播放服务、系统至少 API 26。条件不满足时相应测试跳过，跳过不能记为通过。登录结果解析测试与 2 项契约测试没有这些媒体流程的跳过条件。

## 登录验证方式

`ControlledLoginTest` 使用虚构 UID 与官方 nav 格式的受控响应，检查 HTTP 状态、`code`、`isLogin`、UID 和用户名。第二项测试在真实 Android Dialog 与 WebView 上调用正式结果处理逻辑，覆盖自动返回、同账号管理窗口保持打开、未登录/验证挑战/网络失败保持打开、旧 Cookie 签名、旧窗口与迟到基准结果，以及未知旧会话受限后确认新的有效会话。

测试通过反射控制插件私有请求状态并交付结果；不注入 Cookie、不建立真实账号、不让虚构身份进入 React。它验证登录结果解析与 Dialog 完成逻辑，不代表 Bilibili 登录网络流程、扫码或验证码已实测通过。

## 播放与生命周期验证方式

测试宿主 `QaPlaybackActivity` 继承正式 `MainActivity`，保留真实 Capacitor Bridge、插件与 Activity 的暂停、停止、恢复和销毁调用。在 Bridge 创建 WebView 前选择 Debug 专用的惰性 HTML 资产，并拦截宿主请求；不会加载 React、账号页面或歌单界面。它验证正式生命周期调用链，同时保持受控数据边界。

`ControlledPlaybackTest` 使用自制 90 秒 H.264 baseline 视频与 AAC 音频的 MP4，在正式音源 WebView 中加载内存数据，并验证实际视频尺寸大于零。这样覆盖真实音视频媒体的隐藏窗口策略，而非仅把 WAV 放在 `<video>` 标签里。夹具包含页面隐藏后主动暂停的监听；测试通过媒体实际位置和暂停状态检查隐藏策略防护。视频的生成方法见 [受控音视频夹具](../android/app/src/androidTest/assets/README.md)。播放结束检查另用本地生成的短 WAV。

本轮完整流程通过以下检查：

- 旧播放请求失效；暂停、恢复和跳转改变真实媒体状态，暂停与结束时释放唤醒锁。
- 页面隐藏策略不自行暂停当前音源；源视频控件的主动暂停仍然生效。
- 切到系统桌面与熄屏时，真实媒体时钟继续前进；后台 MediaSession 的手动暂停与恢复生效。
- 外部临时音频焦点在前台与后台暂停媒体，焦点返回后恢复，不能在焦点仍被占用时强制复播。
- 原视频视图移到可见容器再移回，Activity 重建及完全销毁后仍保留同一个服务音源。
- 迟到的旧源视频弹窗关闭回调不会移走新弹窗的音源；停止播放后私有窗口、虚拟显示和帧队列均释放。
- 通知栏与 MediaSession 状态有效；播放结束阻止页面自行播放下一条。
- 远程音源没有 Capacitor 原生桥接；测试前后的音乐库与偏好 JSON 保持一致。

媒体测试拦截全部 HTTP 请求，不访问或下载 Bilibili 音视频。默认音源挂在由播放服务持有的 64×64 私有 `VirtualDisplay` 与 `Presentation` 窗口中，不挂主 Activity 的隐藏容器；主界面退出不会使默认音源脱离窗口。显示原视频时把同一个 WebView 临时移到实际弹窗，返回或退出该 Activity 时移回私有窗口。旧弹窗的迟到关闭回调必须匹配自己的容器，不能移走新弹窗里的音源。

虚拟显示只设 `OWN_CONTENT_ONLY` 与 `PRESENTATION`，只容纳本应用的页面，不镜像手机屏幕；不申请悬浮窗或屏幕捕获权限。`ImageReader` 的 64×64 输出帧收到后立即关闭，仅用于排空队列，不读取或保存像素。停止/销毁服务时依次移除并销毁音源、关闭私有窗口、释放虚拟显示及帧队列；创建中途失败清理已分配资源，源渲染进程退出时释放并重建宿主。本轮测试确认停止后显示失效、Surface 失效、宿主资源引用清空。

`SourceWebView` 的窗口可见性防护只作用于服务音源；脚本防护限定在 HTTPS、`www.bilibili.com`、当前选定 BV 号和第一分 P，不将该策略套用到本地界面或登录页面。它防止页面因隐藏而主动暂停，不通过反复调用播放覆盖用户暂停或外部音频焦点。

Android WebView 为 HTML5 媒体自行申请音频焦点。服务仅在新源文档首次开始且媒体尚未播放时检查许可，再立即释放自己的请求；页面已经自行开始播放时跳过原生预检。同一文档暂停后恢复不重新争抢原生焦点，避免 Chromium 收到延迟的焦点丢失事件而再次暂停。切歌、错误后重新加载及音源 WebView 重建都会重置首次预检状态；首次播放前暂停也不会绕过预检。服务继续读取实际媒体状态，同步通知、锁屏控制和唤醒锁。

## 证据与范围

2026-10-08 在上述独立模拟器完成 0.1.2 的全部 5 项原生检查，无失败、无跳过。2 项契约、2 项受控登录和 1 项完整媒体流程的日志分别为本机 `.qa/native-contract-0.1.2.log`、`.qa/native-login-0.1.2.log`、`.qa/native-playback-0.1.2.log`；媒体流程耗时 31.007 秒。

取回的 `.qa/native-android9-playback-0.1.2-evidence.json` 记录实际媒体时钟推进：

| 场景 | 本轮媒体时钟推进 |
| --- | --- |
| 切到系统桌面 | 3.963657 秒 |
| 关闭屏幕 | 4.291253 秒 |
| Activity 完全销毁后，服务仍运行 | 3.205213 秒 |

暂停/恢复/跳转、前后台临时焦点中断、用户主动暂停、源视图重挂、Activity 重建、通知/MediaSession、播放结束、私有宿主资源释放与音乐库/偏好不变均通过。正式签名 APK 的构建提交、签名和覆盖安装结果另见 [0.1.2 验证记录](QA-0.1.2.md)。本机日志、截图与设备证据位于 `.qa/`，不进入源码提交。

Debug 宿主与惰性资产只存在于 `src/debug`；测试代码与自制视频只存在于 `src/androidTest`。正式 Release APK 不包含这些宿主、夹具或媒体注入入口。媒体流程完成或失败后都会停止测试播放服务并唤醒屏幕。

受控原生成功仅证明该模拟器上的原生机制。真实 Bilibili 登录、网络歌曲播放、手机长时间锁屏、省电条件与不同 Android/WebView 版本仍需分别验收。Activity 销毁后服务仍运行，也不等于应用进程被系统终止后还能持续播放。
