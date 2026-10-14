# Android 国内更新验证（源码，未发布）

日期：2026-10-14。基于手机版 `024e585` 添加 Gitee / GitHub 双源更新；仅修改手机版独立仓库，PC 未改动。版本仍为 0.1.3 / versionCode 4，未改稳定清单、未打发行标签、未替换正式 APK。此前功能移植验证保留在 [QA-PC-SYNC.md](QA-PC-SYNC.md)，不能将核心移植描述为所有 PC 功能已完成。

## 改动和验证

- 原生读取两端清单，校验 Gitee Contents 封装、规范 Base64 与 UTF-8、附件身份和移动端版本；同版本一致优先国内，较新版本优先，同版本冲突阻止下载，同会话保留已确认的新版。
- Gitee 固定附件 API 仅允许一次受限 Foruda 跳转；失败清理临时文件并以相同版本 / 摘要回退 GitHub。两端保留 APK 大小、SHA-256、应用 ID、versionName / versionCode 和当前安装签名校验；取消不启动备用下载。
- 前端更新状态接受经过原生校验的国内地址，拒绝 PC 仓库、任意 CDN 初始地址、附加令牌、错误附件 ID / 名称及混用来源。
- 准备工具匿名完整下载 Gitee APK，必须与公开 GitHub stable 的正式字节完全一致，再回读 Release / 附件 / 通道身份，生成候选；不上传附件或推进通道。

| 检查 | 实际结果 |
| --- | --- |
| `npm run typecheck`、`npm test`、`npm run build` | 通过；44 项业务测试，0 失败 / 跳过 |
| `node --test scripts/release.test.mjs scripts/prepare-domestic-update.test.mjs` | 21 项通过，0 失败 / 跳过 |
| Capacitor `sync android` | 通过 |
| `:app:testDebugUnitTest` | 13 项通过：评论策略 4、原生策略 4、更新策略 5；0 失败 / 跳过 |
| `:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:assembleRelease` | 通过；Release 为未签名构建，未访问正式签名 |
| `ControlledDomesticUpdateTest` | 3 项通过，0 失败 / 跳过；13.357 秒 |

原生执行使用此前专门新建的 `OpenMusic-Sync-QA-20261014` / index 4 / `emulator-5562`，Android 9。只在这个没有账号、音乐库和偏好的测试实例覆盖安装 Debug / instrumentation；不使用日常设备或原有模拟器。未来夹具是隔离 Gradle 配置和输出目录生成的 Debug `1.0.0` / versionCode 100，同一测试证书，仅推送到 `/data/local/tmp/yuyin-update-domestic-future.apk`，没有修改仓库版本或使用正式发布密钥。

3 项原生测试分别检查 Contents 解析、双源选择和真实 `UpdateManager` 下载流程。网络只由 instrumentation APK 的 HTTPS URL handler 返回内存响应，Release 没有注入开关。完整流程覆盖官方 API → Foruda 下载与实际 APK 校验、国内 HTTP 错误 / 文件损坏 / 截断 / 外部跳转 / 第二次 CDN 跳转后的 GitHub 备用下载、单源失效与非法 Contents、同版本冲突后无法下载、镜像落后保留新版、显式取消不下载备用文件，以及国内清单阻塞时 12 秒预算结束后返回可用 GitHub 清单。检测到的请求均未带 Cookie / Authorization；测试前后库与偏好保持不存在，仅清理该测试的缓存 APK。

日志位于本机 `.qa/native-domestic-updates.txt`，隔离夹具 `.qa/domestic-update-future-debug.apk`；均不进入提交或发行附件。原生测试下载并校验 Debug APK，不调用系统安装或覆盖升级。

## 公开路径与限制

2026-10-14 匿名读取手机版 Gitee `releases?per_page=5` 和固定国内 Contents API，均为 HTTP 200 + 空数组，当时没有正式 APK / 国内清单。未生成或提交占位 `updates/domestic.json`，未将旧正式 APK / 新 Debug 包发布到 Gitee。

本轮只证明受控 Android 更新逻辑与构建通过；尚未验证本项目公开 Gitee APK 的完整下载、正式签名覆盖升级、真实手机或非空个人音乐库升级保留。新源码也不改变已安装 0.1.3 的 GitHub 更新路径。下一正式发布必须按 [UPDATES.md](UPDATES.md) 上传同一正式 APK、匿名完整校验国内附件，再推进国内清单，才能认定国内更新通道已启用。
