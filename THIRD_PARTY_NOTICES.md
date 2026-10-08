# 第三方组件与内容声明

本项目原创代码采用根目录 [MIT 许可证](LICENSE)。以下组件继续适用原有许可和版权归属；MIT 不替代其许可，也不重新许可第三方音乐、封面、歌词或平台内容。

## Android 应用运行时

以下是 Android 0.1.2 的主要运行时组件，与 0.1.1 的依赖版本相同。JavaScript 版本以 `package-lock.json` 为准；Android 直接依赖版本见 `android/variables.gradle`，其传递依赖由 Gradle 解析。

| 组件 | 版本或范围 | 许可与原始文本 | 上游项目 |
| --- | --- | --- | --- |
| React | 19.3.0 | MIT；[原始许可证](licenses/React-LICENSE.txt) | [facebook/react](https://github.com/facebook/react) |
| React DOM | 19.3.0 | MIT；[原始许可证](licenses/ReactDOM-LICENSE.txt) | [facebook/react](https://github.com/facebook/react) |
| scheduler | 0.28.0 | MIT；[原始许可证](licenses/Scheduler-LICENSE.txt) | [React scheduler](https://github.com/facebook/react/tree/main/packages/scheduler) |
| Lucide React | 0.468.0 | ISC；[完整原始许可证及 Feather 归属](licenses/Lucide-LICENSE.txt) | [lucide-icons/lucide](https://github.com/lucide-icons/lucide) |
| Capacitor core | 8.5.3 | MIT；[原始许可证](licenses/CapacitorCore-LICENSE.txt) | [ionic-team/capacitor](https://github.com/ionic-team/capacitor) |
| Capacitor Android | 8.5.3 | MIT；[原始许可证](licenses/CapacitorAndroid-LICENSE.txt) | [Capacitor Android](https://github.com/ionic-team/capacitor/tree/main/android) |
| AndroidX AppCompat、Activity、Core、Fragment、CoordinatorLayout、WebKit、Core SplashScreen 等 AndroidX 库 | 直接与传递依赖 | Apache-2.0；[许可全文](licenses/Apache-2.0.txt)，版权归 Android Open Source Project 及各原贡献者 | [androidx/androidx](https://github.com/androidx/androidx)、[AndroidX 发行说明](https://developer.android.com/jetpack/androidx/versions) |
| Apache Cordova Android framework | 14.0.1 | Apache-2.0；[原始 LICENSE](licenses/Cordova-LICENSE.txt)、[原始 NOTICE](licenses/Cordova-NOTICE.txt) | [apache/cordova-android](https://github.com/apache/cordova-android/tree/rel/14.0.1) |
| Kotlin 标准库及 kotlinx 运行时传递依赖 | 由 AndroidX 等依赖引入；以 Gradle 解析结果为准 | Apache-2.0；[许可全文](licenses/Apache-2.0.txt)，版权归 JetBrains 及原贡献者 | [JetBrains/kotlin](https://github.com/JetBrains/kotlin)、[Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |

React、Lucide 和 Capacitor 的许可文本从本版本实际安装的 npm 依赖包逐字复制，保留 Meta、Lucide / Feather 和 Drifty Co. 的原版权行。Cordova 的 LICENSE 与 NOTICE 取自其官方 `rel/14.0.1` 标签，原样保留。通用 Apache-2.0 全文取自 [Apache Software Foundation](https://www.apache.org/licenses/LICENSE-2.0.txt)，其中的附录示例不替代每个组件自己的版权归属。

Android System WebView 是设备提供的系统组件，APK 不捆绑独立的 Chromium / Electron 运行时；其许可随设备的 WebView 发行版提供。设备系统组件的权利不由本项目的 MIT 授予。

GitHub [发行页](https://github.com/panda472328/yuyin-music-mobile/releases) 仅上传该平台最终 APK，说明中链接源码仓库的 [项目 MIT](LICENSE)、本文件和 [原始许可文本](licenses/)。重新分发 APK 时应一并保留本文件、项目 MIT 和适用的原许可证 / NOTICE。上述表格是主要组件索引，不替代依赖本身的完整声明；新增原生库后需核对其传递依赖并补充原始文本。

## 构建与测试工具

Gradle 8.14.3、Android Gradle Plugin、TypeScript 和 Playwright 使用 Apache-2.0；Vite、tsx 和 Capacitor CLI 使用 MIT。它们用于构建或验证，完整开发工具发行包不随 APK 捆绑。Java 开发工具和 Android SDK 也属于独立工具链，仍适用各自的发行许可。

源码仓库包含 Gradle Wrapper，因此保留 [Gradle 原始 LICENSE](licenses/Gradle-LICENSE.txt) 和 [Gradle 原始 NOTICE](licenses/Gradle-NOTICE.txt)。这两份文本来自实际使用的 Gradle 8.14.3 官方发行包，包含该工具发行版自己的第三方声明；列出其中组件不意味着整个 Gradle 发行版被打入 APK。Wrapper 和 Gradle 的原代码归 Gradle 原贡献者所有，不能将其标为本项目 MIT 代码。[Gradle 上游源码](https://github.com/gradle/gradle/tree/v8.14.3) 与 [Apache-2.0 全文](licenses/Apache-2.0.txt) 可供查阅。

JUnit、AndroidX Test 和 Espresso 用于测试。测试依赖按其自己的许可管理，不将这些测试框架混记为本应用的正式播放功能或统一套用 MIT。复制或重新分发任意工具、测试库或其源码时，仍需保留对应发行包自己的 LICENSE / NOTICE。

## Bilibili、歌词与媒体

- Bilibili 名称、标志、网页、视频、音频、字幕、封面和账号资料属于平台或相应权利人。本项目不是 Bilibili 官方产品，MIT 不授权这些内容或商标。
- 应用通过第三方网页和接口显示公开搜索结果、封面与字幕，没有将第三方歌曲音频文件作为源码资产发布。运行时显示的封面及预览截图中的第三方封面保留原权利人的权利。
- 歌词来自 Bilibili 已有字幕或 [LRCLIB](https://lrclib.net/)；LRCLIB 服务代码的开源许可与歌词文本的权利不同。本项目不以 MIT 重新许可歌曲、歌词或字幕。
- 第三方服务的登录、可用范围和使用规则由各服务管理；使用其内容仍需遵循适用规则和权利要求。

## 更新规则

升级依赖时同时核对 npm 锁文件、Gradle 解析后的正式运行时依赖，以及原 LICENSE / NOTICE；新增组件应补充源码仓库的本文件和 `licenses/`，发布说明同步链接该版本对应的许可文档。不得通过替换原版权行或统一改为 MIT 的方式处理第三方文本。
