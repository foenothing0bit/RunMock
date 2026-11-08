# 跑步轨迹模拟（RunMock）

给**自研跑步 App** 做轨迹回放的 Android 测试工具：把一条真实 GPX 轨迹按指定配速（或按原始时间轴加速）逐秒注入系统定位，
被测 App 收到的就是一串合法、连续、带速度/方位/高程的定位点，用来验证配速、分段、地图打点、暂停恢复等逻辑。

不 root、不 Xposed，只用 Android 官方的 **mock location provider**（开发者选项里那个「模拟位置信息应用」）。

- 语言 / 构建：Kotlin + Gradle(Kotlin DSL)，minSdk 26，targetSdk 34
- 地图预览：osmdroid + OpenStreetMap，**不需要 Google Maps API Key**
- 定位注入：`LocationManager.addTestProvider` + `setTestProviderLocation`，同时写 GPS 与 NETWORK 两个 provider
- 常驻：前台服务 + 通知栏（暂停 / 继续 / 停止）

> 适用范围：仅用于你自己开发/维护的 App 的功能与回归测试。把模拟轨迹提交给第三方运动平台刷里程、
> 刷排名或套取奖励属于伪造数据，可能构成欺诈并违反平台条款，本项目不为此提供支持。

## 工程结构

```
app/src/main/java/com/example/mockloc/
├── MainActivity.kt          # 导入 GPX、选配速/回放模式、进度轮询、启停
├── MockLocationService.kt   # 前台服务：注册 test provider，按 tick 写入坐标
├── RouteSimulator.kt        # 按配速 / 按时间轴推进，输出位置、方位、速度、高程
├── Route.kt                 # 轨迹模型：累计距离、时间轴、高程剖面、插值
├── GpxParser.kt             # 极简 GPX 解析（trkpt/rtept/wpt，容错优先）
├── Geo.kt                   # 距离、方位角、插值
├── Fmt.kt                   # 配速 / 距离 / 时长格式化
├── MockPermission.kt        # 「模拟位置信息应用」授权检查 + 跳转设置
└── MockState.kt             # 进程内共享状态（含当前路线）
```

## 构建

1. 用 Android Studio（Koala 及以上，JDK 17）直接 `Open` 本目录，等待 Gradle Sync。
2. 或命令行（本机需已装 Gradle 8.7+ 与 Android SDK 34）：

```bash
gradle :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 仓库里只有 `gradle/wrapper/gradle-wrapper.properties`，没有 `gradle-wrapper.jar`。
> 想用 `./gradlew`，先在装了 Gradle 的机器上执行一次 `gradle wrapper`。

## 云端构建（没有电脑时用这条）

仓库自带 `.github/workflows/build-apk.yml`，推到 GitHub 后自动编译出 APK：

1. 新建仓库（私有也行），把本目录内容推上去。**`.github/` 是隐藏目录，Windows 资源管理器默认不显示，别漏了**；
2. 打开仓库 **Actions** 页 → 左侧 **Build APK** → 右侧 **Run workflow**；
3. 等 3–5 分钟，进入这次运行，页面底部 **Artifacts** 里下载 `runmock-debug-apk`；
4. 解压得到 `app-debug.apk`，传到手机安装。

工作流做的事：JDK 17 → Gradle 8.7 → 安装 SDK Platform 34 与 Build-Tools 34.0.0 → `gradle :app:assembleDebug` → 上传 APK。
公开仓库不消耗额度；私有仓库每月 2000 分钟免费额度，这个流程一次约 3–5 分钟。

## 使用步骤

1. 安装并打开 App。
2. **打开开发者选项**：设置 → 关于手机 → 连点「版本号」7 次。
3. 开发者选项 → **选择模拟位置信息应用** → 选中「跑步轨迹模拟」。
   - 没做这一步，`addTestProvider` 会抛 `SecurityException`，界面会红字提示。
4. 点「导入 GPX」选一条轨迹（从运动 App 导出的即可）；没带文件就点「示例路线」跑通链路。
5. 选模式：
   - **按配速跑**：输入 `5:30` 或 `5.5`（分钟/公里），与 GPX 自带时间戳无关，适合稳定复现；
   - **按原始时间**：按 GPX 时间轴回放，用「回放倍速」加速（10 倍速 = 一条 50 分钟的路线 5 分钟跑完）。
6. 点「开始模拟跑步」，App 会申请定位与通知权限，随后开始每秒注入一个定位点。
7. 通知栏可暂停 / 继续 / 停止；也可在 App 内点「停止模拟」。
8. 关闭电池优化（设置 → 应用 → 跑步轨迹模拟 → 电池 → 不受限制），否则长时间后台可能被系统冻结。

## 用它测什么

| 测试点 | 做法 |
| --- | --- |
| 配速 / 实时速度显示 | 按配速跑，设 `4:00`、`6:00` 各跑一段，看被测 App 显示是否一致 |
| 分段（每公里 split） | 用带时间戳的 GPX 按原始时间回放，核对每公里用时 |
| 地图打点与轨迹平滑 | 导入折线明显的轨迹，观察是否出现跳点、漂移 |
| 暂停 / 恢复 | 用通知栏暂停，确认被测 App 是否停止累计距离、恢复后不跳变 |
| 后台与前台服务 | 切后台跑 10 分钟，确认不被系统冻结、数据连续 |
| mock 检测逻辑 | 注入点会带 `Location.isMock() == true`，可验证被测 App 是否正确识别 |
| 高程 / 爬升 | 导入带 `<ele>` 的轨迹，核对爬升统计 |

## 实现要点（改代码时容易踩的坑）

| 坑 | 说明 |
| --- | --- |
| 必须被选中为 mock app | 没有 API 可代劳，只能跳设置页；判定用 `AppOpsManager` 的 `android:mock_location` |
| `Location` 必须带时间戳 | `time` 与 `elapsedRealtimeNanos` 不设置，`setTestProviderLocation` 会抛 `IllegalArgumentException` |
| 顺序：先 add 再 enable | `addTestProvider` 之后必须 `setTestProviderEnabled(true)`，停止时 `removeTestProvider` 收尾 |
| 只写 GPS 可能不够 | 用 `FusedLocationProviderClient` 的 App 有概率读不到，所以 GPS + NETWORK 一起写 |
| Android 14 前台服务 | `foregroundServiceType="location"` + `FOREGROUND_SERVICE_LOCATION` 权限 + 已授予定位权限 |
| 速度按真实时间推进 | 循环里用 `SystemClock.elapsedRealtime()` 算 delta，别假设 `delay(1000)` 精确 |
| 停止服务别用 startForegroundService | 会因 5 秒内没 `startForeground` 触发 `ForegroundServiceDidNotStartInTimeException`，用 `startService` |
| 大 GPX 别塞 Intent | Binder 事务上限约 1 MB；路线放在 `MockState.route`，Intent 只传参数 |

## 已知限制

- 目标 App 可以通过 `Location.isMock` / `isFromMockProvider` 识别模拟位置，本方案**不做隐藏**（测试 mock 检测时反而需要它可识别）。
- 少数定制 ROM 会拦截 test provider 或强制使用自家定位服务，表现为写入了但 App 读不到。
- 只影响 App 层定位，不影响「查找我的设备」等依赖硬件 GNSS 的能力。
- 导入的路线只为下次启动缓存一份（`filesDir/last_route.gpx`）；示例路线不落盘，重启后需重新生成。
