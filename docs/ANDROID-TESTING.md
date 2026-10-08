# Android 原生验证

本次测试设备为独立的 Android 9（API 28）x86_64 模拟器 `emulator-5554`，WebView 91。没有登录 Bilibili、导入 PC 登录凭据或读取真实账号数据。

## 重现

先按项目构建说明生成 `app-debug.apk` 和 `app-debug-androidTest.apk`。使用 Android SDK 的 `adb`，将下列命令的设备序号替换为专用测试模拟器；不要对日常使用的手机或已有登录状态的模拟器运行受控媒体测试。

```powershell
adb -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# 插件方法、独立应用 ID、非导出播放服务的原生契约检查
adb -s emulator-5554 shell am instrument -w -r -e class com.yuyin.music.mobile.YuyinNativeContractTest com.yuyin.music.mobile.test/androidx.test.runner.AndroidJUnitRunner

# 必须显式选择独立模拟器；会短暂切换到桌面并关闭/唤醒屏幕
adb -s emulator-5554 shell am instrument -w -r -e class com.yuyin.music.mobile.ControlledPlaybackTest -e yuyinControlledEmulator true com.yuyin.music.mobile.test/androidx.test.runner.AndroidJUnitRunner

# 读取成功测试的结果；此命令适用于可调试 APK
adb -s emulator-5554 shell run-as com.yuyin.music.mobile cat cache/qa-controlled-playback-evidence.json
```

`NativeContract` 有 2 项检查，`ControlledPlayback` 有 1 项包含完整媒体流程的检查。受控测试若未传入 `yuyinControlledEmulator=true`、已有身份 Cookie、已有播放服务或系统低于 API 26，会跳过；跳过不算播放验证通过。

## 实测结果与范围

3 项原生测试全部通过。受控测试把生成的本地 WAV 放入真实 Android WebView 的 HTML5 媒体元素，拦截所有 HTTP 请求，不访问 Bilibili 音视频。它通过正式播放服务的实际状态验证以下行为：

- 旧播放请求失效，暂停后时钟停止，恢复与跳转改变实际媒体位置。
- 外部音频暂时取得焦点时暂停，焦点返回后恢复；暂停与播放结束时释放唤醒锁。
- 原视频视图移到可见容器再移回，保留同一 WebView 与正在播放的媒体。
- 切换到系统桌面后，播放时钟前进 **4.118 秒**；关闭屏幕后前进 **4.389 秒**。
- 播放结束阻止页面自身继续播放，通知栏与 MediaSession 状态可用。
- 音源视图没有 Capacitor 原生桥接；用户歌单与偏好设置在测试前后完全一致。

结果已取回本机 `.qa/native-android9-playback-evidence.json`。它验证受控媒体下的 Android 原生机制；真实 Bilibili 登录、网络播放、特定机型的省电限制仍需单独在手机上验收。

Android WebView 会为 HTML5 播放自行申请音频焦点。播放服务先检查焦点许可，然后释放自己的请求，再启动媒体，避免与同一进程的 Chromium 争抢焦点。服务读取真实媒体的暂停状态与播放时间，同步通知栏、锁屏控制和唤醒锁；用户明确点击暂停后保持暂停。

测试宿主 `QaPlaybackActivity` 只存在于 `src/debug`，测试代码存在于 `src/androidTest`。正式 release APK 不包含这个宿主，也没有用于注入测试媒体的产品接口。测试不启动 React 歌单界面；完成或失败后都会停止测试播放服务并唤醒屏幕。
