# 应用更新与正式发布

Android 从本仓库的 `updates/stable.json` 检查正式版本；PC 使用独立仓库和独立清单。提交代码到 GitHub 不等于通知用户，只有正式安装文件公开并成功更新清单，客户端才会发现新版本。

发布有两种入口：推送版本标签让 Actions 构建签名 APK，或先在本机签名、验收，再到 GitHub 上传并正式发布。第二种只使用公开 APK 和证书校验，不需要配置 Android 签名 secrets。

## 用户流程

带更新功能的版本启动后自动检查新版；登录之前和设置页也可以手动检查。发现新版后显示版本和说明，用户选择下载、等待校验，再选择安装。下载可以取消，网络失败可以重试；GitHub Raw／Release 不可达时可以改从发布页手动下载。

Android 普通应用不能静默覆盖安装。安装 APK 时必须经过 Android 系统确认，首次可能需要在系统设置允许余音安装应用。拒绝授权或取消安装不会退出应用、清空歌单或丢失设置。APK 的 SHA-256、大小、包名、版本号、versionCode 和升级签名都必须验证通过。

已经安装的 0.1.2 及更早版本没有应用内更新功能，需要先手动覆盖安装一次带更新功能的正式版。保留当前正式签名，不卸载旧版；此后可以在应用里发现并选择更新。Android 音乐库、偏好和登录会话继续保存在原应用私有数据中。

## Actions 首次配置

使用 **Actions 自动构建签名 APK** 时，在 **yuyin-music-mobile** 仓库的 Settings → Secrets and variables → Actions 配置以下仓库 secrets。维护者从现有正式签名的私下备份中准备，不把内容放进源码、Issue、聊天、日志或发布附件；Agent 不读取、导出或上传本机发布私钥。这些 secrets 仍需维护者配置，本次本机发布不代替此配置。

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 当前正式 keystore／PKCS12 文件的 Base64 文本 |
| `ANDROID_KEY_PASSWORD` | 该 keystore 的密码；当前构建约定 key password 与 store password 相同 |
| `ANDROID_KEY_ALIAS` | 当前正式签名的 key alias |

必须沿用现有正式密钥。工作流在 runner 的临时目录准备签名，向现有 `npm run android:build` 传入 `YUYIN_ANDROID_KEYSTORE`、`YUYIN_ANDROID_KEY_PASSWORD`、`YUYIN_ANDROID_KEY_ALIAS`；不会读取开发者的默认签名配置或生成新发布密钥。构建后用公开证书 SHA-256 `068675cef3bd1e3402408efa3ddf0a26f2827b460945d8780072460cdfc78146` 核对，错误 secrets 或新密钥会导致发布失败。结束时清理临时签名文件。

另外启用 Actions，保留默认分支 `main`，允许 `.github/workflows/release.yml` 的 `GITHUB_TOKEN` 使用 `contents: write` 创建 Release 和更新 `main/updates/stable.json`。有分支保护时由维护者配置发布权限；脚本不绕过保护规则。`GITHUB_TOKEN` 由 GitHub 自动提供。

## 后续发布

1. 在手机版仓库执行版本管理脚本，递增版本和 Android versionCode。例如下一版本：

   ```sh
   node scripts/set-version.mjs 0.1.3 4
   ```

2. 补充 `CHANGELOG.md` 对应版本章节，完成类型、业务、生产构建及原生／界面验收，提交、推送源码。
3. 给发布提交建立并推送 `android-vX.Y.Z` 标签。例如：

   ```sh
   git tag android-v0.1.3
   git push origin android-v0.1.3
   ```

4. Actions 安装 Node 24、JDK 21、SDK 36 和项目要求的 Build-Tools，运行业务、发布保护及 Android 单元测试，构建签名 APK。`verify-android-release.mjs` 核对实际 APK 包名 `com.yuyin.music.mobile`、版本、versionCode 和签名。
5. 发布脚本先草稿上传唯一附件 `Yuyin-Mobile-X.Y.Z.apk`，复核大小和重新下载的 SHA-256，再公开 Release，最后更新 main 的稳定清单。不上传 Debug／测试 APK、校验文件、构建记录或签名附件。
6. 检查工作流、发布页和稳定清单，在独立测试设备验收旧版覆盖升级、系统授权／确认、取消安装以及更新后数据保留。CI 构建和受控测试不能代替真实 Bilibili、手机后台表现或系统安装的验收。

只推送 main 或提交说明文档不会触发发布。标签与三个版本来源不一致、版本回退、versionCode 不递增、旧标签重用或同版本不同文件都会被拒绝。PC 的标签和安装文件不能通过手机版流程。

## 从 GitHub 页面发布已有 APK

本机最终 APK 已完成版本、签名及设备验收后，在同版本 `android-vX.Y.Z` 标签下创建草稿，只上传 `Yuyin-Mobile-X.Y.Z.apk`，再点击 Publish release，不能选择 prerelease。标签源码须包含这套工作流及发布脚本，package／lock／version.json 必须一致。

发布事件自动执行独立 `advance-existing-release` job：检出发布标签，下载唯一 APK，使用 JDK 21 和 Android Build-Tools 36 核对实际包名、versionName、versionCode 和现有公开签名证书；再验证公开附件、大小、SHA-256，并推进 main 的清单。该 job 不构建 APK，不读取任何 keystore，不需要 Android 签名 secrets，只需要 Actions 内置的仓库写入 token。

为避免本机和 CI 产物同时占用同一标签，手工发布的源码提交可使用 GitHub 标准 `[skip ci]` 标记，跳过 tag push 构建。`release.published` job 不受该标记影响。本次首次更新桥接版使用已在本机验收的最终 APK 和此标记；未来要让 tag 自动签名构建，维护者再配置前述 secrets 并使用普通发布提交。

由 Actions 的 `GITHUB_TOKEN` 发布时，GitHub 不会递归触发发布事件；原 tag 构建的脚本自行更新清单。人工网页／本机发布由发布事件推进。手工与事件流程同时写同版本相同文件时可以幂等完成；不同文件仍被拒绝，公开版本不被覆盖。

## 本地预览和故障恢复

不连接 GitHub、不读取签名，用已有 APK 预览清单：

```sh
node --test scripts/release.test.mjs
node scripts/release.mjs --validate-version --tag android-v0.1.2
node scripts/release.mjs --dry-run --tag android-v0.1.2 --artifact artifacts/Yuyin-Mobile-0.1.2.apk --output .qa/update-manifest-preview.json
```

替换成实际待发布版本。默认说明来自 CHANGELOG；`--notes <文本文件>` 可以指定审核后的说明，`--current-manifest <文件>` 仅供 dry run 使用。预览不会覆盖稳定通道。

若 Release 已公开、清单更新因权限或网络失败，用户暂不会发现新版本。修复原因后，使用该版本标签源码及原始已发布 APK 重试，不用不同重新构建的文件覆盖同版本附件。正式模式需要 `gh` 和写入权限 token，并显式指定：

```sh
node scripts/verify-android-release.mjs
node scripts/release.mjs --publish --tag android-vX.Y.Z --artifact artifacts/Yuyin-Mobile-X.Y.Z.apk
```

正式发布默认由 Actions 执行；手工重试前也需核对实际 APK 的版本和签名。对同版本相同附件，脚本允许安全重试；并发更新采用 GitHub 文件 SHA 校验，旧流程不能把稳定通道退回旧版。

## 更新清单

```json
{
  "schemaVersion": 1,
  "platform": "android",
  "version": "0.1.2",
  "versionCode": 3,
  "artifact": {
    "url": "https://github.com/panda472328/yuyin-music-mobile/releases/download/android-v0.1.2/Yuyin-Mobile-0.1.2.apk",
    "sha256": "e6e1149714087941fdf6039347ab51425e71a1ce0a54a6511ef26e0984730d81",
    "size": 3309135
  },
  "releaseNotesUrl": "https://github.com/panda472328/yuyin-music-mobile/releases/tag/android-v0.1.2",
  "notes": "同版本更新说明",
  "publishedAt": "2026-10-08T12:42:25.000Z"
}
```

固定本仓库同版本 URL，版本每段最多六位且不含前导零，versionCode 为 1–2,100,000,000 的整数，说明最多 8000 字，APK 最多 200,000,000 字节。SHA-256 为小写 64 位十六进制，时间为 UTC ISO 格式。初始清单描述已公开 0.1.2，不重新发布该包，也不会让相同版本误报更新。

更新组件入口是 `src/components/Updates.tsx`；原生桥接不接受前端传入任意下载 URL 或本地路径。界面和状态约定见 [UI-GUIDE.md](UI-GUIDE.md)，发布签名与文件归档见 [PROJECT-MANAGEMENT.md](PROJECT-MANAGEMENT.md)。
