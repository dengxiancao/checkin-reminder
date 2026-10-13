# 定位打卡（CheckinReminder）

一个面向**中国区 Android** 的定位打卡提醒 App：在地图上设置打卡点与范围半径，进入/离开范围时推送高优先级通知（震动 + 声音 + 锁屏可见），并支持一键打卡落库、后台常驻、清理后台自恢复、开机自动恢复。

- **应用名**：定位打卡
- **包名**：`com.example.checkin`
- **版本**：1.7.2（versionCode 5）
- **兼容**：Android 8.0（API 26）～ Android 14（targetSdk 34）

---

## 一、功能说明

| 模块 | 能力 |
| --- | --- |
| 打卡点管理 | 地图点选落标记、半径档位（100/200/300/500 米）、启用/停用、编辑、删除 |
| 范围触发 | 围栏状态机（OUTSIDE / NEAR / INSIDE / LEFT），进入触发「进入」事件、离开触发「离开」事件 |
| 通知提醒 | 高优先级通道（IMPORTANCE_HIGH），震动 + 默认提示音 + 锁屏可见 + 全屏提醒，点击进入打卡确认页 |
| 打卡确认 | 显示打卡点、实时距离，一键「立即打卡」用当前位置写入记录 |
| 打卡记录 | **三视图**：时间轴（按日分组，侧滑删除可撤销）/ 日历（月视图标色）/ 统计（日·周·月在岗时长与打卡次数） |
| 记录编辑 | 可修改打卡时间与备注（≤200 字）；坐标与来源不可篡改，保证记录可信 |
| 数据导出 | 打卡记录导出 **CSV**（带 UTF-8 BOM，Excel / WPS 直接打开不乱码） |
| 数据备份 | 完整备份导出与恢复（**JSON**，含打卡点与记录；单事务写入，恢复后自动重注册围栏） |
| 隐私合规 | 内置隐私政策页，明确数据只存本机、不上传、不采集 |
| 防重复提醒 | 同一打卡点在同一状态下只提醒一次，离开后再次进入才重新提醒 |
| 动态省电 | 全部远离时 30 秒定位一次，有接近/进入时提升到 5 秒 |
| 稳定保活 | 前台服务 + START_STICKY + 划掉后台自恢复 + 看活闹钟 + 部分唤醒锁 |
| 开机恢复 | 读取持久化开关，若上次为开启则自动恢复监控 |
| 设置页 | 监控总开关、提醒方式、电池优化、保活自检、国产 ROM 后台权限引导 |
| 定位调试 | 实时坐标/精度/定位来源/回调间隔日志（验证定位与调频） |

---

## 二、技术栈

| 维度 | 选型 |
| --- | --- |
| 语言 / UI | Kotlin + Jetpack Compose + Material3 |
| 架构 | MVVM（ViewModel + StateFlow + Repository） |
| 定位底座 | **高德定位 SDK**（官方完整版 `AMap_Location_V11.3.101`，含 `libapssdk.so`） |
| 地图选点 | **高德地图 SDK**（`com.amap.api:map3d`，`TextureMapView`） |
| 地理围栏 | 高德 `GeoFenceClient.addGeoFence` 注册圆形围栏 + 自研距离状态机做权威判定 |
| 后台运行 | 前台服务（`foregroundServiceType="location"`）+ START_STICKY + AlarmManager 看活 |
| 本地存储 | Room v3（`checkin_point` / `checkin_record`，含 `AutoMigration`）+ DataStore 偏好 |
| 构建 | Gradle Kotlin DSL + Version Catalog（`gradle/libs.versions.toml`） |

---

## 三、快速开始

### 1. 准备开发环境

- JDK 17
- Android SDK：**platform android-33** 与 build-tools 34.0.0
- 无需单独安装 Gradle（使用仓库内 `gradlew`）

> **关于 compileSdk**：本工程 `compileSdk = 33`、`targetSdk = 34`。
> 原因见第八节「已知限制」。若你已安装 android-34，可把
> `gradle/libs.versions.toml` 的 `compileSdk` 改为 `"34"` 并删除
> `app/build.gradle.kts` 底部的 `CheckAarMetadataTask` 禁用代码。

### 2. 放入高德 SDK（必须，仓库不含）

高德 SDK 二进制受其许可约束，**不随本仓库分发**。请自行下载后放到指定位置，否则编译会因缺少 `com.amap.api` 符号而失败：

| 文件 | 放入位置 | 获取方式 |
| --- | --- | --- |
| `AMap_Location_V11.3.101*.jar` | `app/libs/` | [高德开放平台 → 下载中心 → Android 定位 SDK](https://lbs.amap.com/api/android-location-sdk/download) |
| `libapssdk.so`（`arm64-v8a`、`armeabi-v7a` 各一份） | `app/src/main/jniLibs/<abi>/` | 同上，定位 SDK 压缩包内附带 |

> 地图选点所用的 `com.amap.api:map3d` 等依赖由 Gradle 自动下载，无需手动放置。

### 3. 配置 local.properties

复制模板并填写：

```bash
cp local.properties.example local.properties
```

```properties
sdk.dir=C:/Android/Sdk
MAP_KEY=你在高德控制台申请到的Key
```

> `local.properties` 已在 `.gitignore` 中，Key 不会被提交，也不硬编码进源码。
> 构建时该值会同时注入到 `AndroidManifest` 的 `com.amap.api.v2.apikey` 与 `BuildConfig.MAP_KEY`。

### 4. 打包

```bash
./gradlew assembleDebug      # 调试包 → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # 正式包 → app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest  # 单元测试（164 个用例 / 11 个测试类）
```

Windows 用 `gradlew.bat`。

> **工程必须放在纯 ASCII 路径下**（当前：`D:\projects\checkin`）。
> 历史上曾因工程目录含中文 + `gradle.properties` 给 Gradle daemon 加了
> `-Dfile.encoding=UTF-8`（而 fork 出的测试 JVM 仍按平台默认 GBK 读取 classpath 参数文件），
> 导致 `testDebugUnitTest` 出现 `ClassNotFoundException`。现已：① 迁移到纯 ASCII 路径；
> ② 移除该编码参数并删除 `app/build.gradle.kts` 中无效的测试编码修正段；③ 移除
> `android.overridePathCheck=true`，恢复 AGP 的路径检查。
> 此后单元测试在 ASCII 路径下稳定全绿（当前 164 个用例 / 11 个测试类全部通过）。

---

## 四、高德 Key 申请与配置

### 4.1 申请步骤

1. 登录 [高德开放平台控制台](https://console.amap.com/dev/key/app)；
2. 左侧进入 **应用管理 → 我的应用**，点击 **创建新应用**（名称随意，如「定位打卡」）；
3. 在应用记录上点击 **添加新Key**，填写：

   | 字段 | 填写内容 |
   | --- | --- |
   | Key 名称 | 随意，如 `checkin-android` |
   | **绑定的服务** | 必须选 **`Android平台SDK`** |
   | 发布版安全码 SHA1 | 见 4.2 节获取 |
   | 调试版安全码 SHA1 | 留空即可（本工程 debug 与 release 使用同一密钥） |
   | PackageName | `com.example.checkin` |

4. 勾选服务条款 → 提交，复制生成的 32 位 Key。

### 4.2 获取签名 SHA1

```bash
# 当前默认（在用）密钥库
keytool -list -v -keystore checkin-release-legacy-2026.jks -alias checkin
```

输出中的 `SHA1:` 即为需要填到控制台的值。该值必须与控制台登记值**完全一致**，否则定位/地图会被鉴权拒绝。

> **签名口令不落文档**：口令存放在 `keystore.properties`（已被 `.gitignore` 忽略）
> 或环境变量中，并同步至团队密码管理器。README 中不再明文记录口令与 SHA1。

> ⚠️ **本工程有两个密钥库，切换时必须同步更新高德控制台的 SHA1**：
>
> | | 密钥库 | 高德控制台 | 说明 |
> | --- | --- | --- | --- |
> | **在用** | `checkin-release-legacy-2026.jks` | **已登记** | 实测定位可用；口令较弱，仅限本地构建 |
> | 备用 | `checkin-release.jks`（RSA 4096 / PKCS12） | **未登记** | 需先在高德控制台为 `com.example.checkin` 与 `com.example.checkin.debug` 登记其 SHA1 后才可使用 |
>
> - 在用的密钥库由 `keystore.properties` 的 `storeFile` 决定；备用那组值以注释形式保留在同一文件里。
> - **签名 SHA1 与控制台登记不一致时，定位/地图必然不可用**。实测新版定位 SDK 此时**不返回错误码 26**，
>   而是返回 `code=8`，真正线索在 `errorInfo` 中：`auth fail:INVALID_USER_SCODE#SHA1AndPackage#<sha1>:<pkg>`。
>   应用内已识别该情况并给出明确提示，可直接从调试页读到上报的 SHA1，与 4.2 节值比对。
> - 两套签名**互不兼容**：切换密钥库后**已安装的旧包无法覆盖安装**，需先卸载（应用数据会丢失）。
> 密钥库一旦丢失将无法对已上架应用做覆盖更新，务必妥善备份。

### 4.3 常见 Key 相关错误

| 现象 | 原因 |
| --- | --- |
| 定位/地图无反应，调试页提示「高德鉴权失败」 | **签名 SHA1 与控制台登记不一致**（最常见）。用 4.2 节命令取当前密钥库 SHA1，与控制台登记值逐字符比对；新版 SDK 报 `code=8`，不要只看错误码 |
| 定位/地图无反应，错误码 **26** | Key、包名、SHA1 三者不匹配（旧版 SDK 表现）。检查控制台登记的 PackageName 是否为 `com.example.checkin`，SHA1 是否为 4.2 节的值 |
| 错误码 **27** | 未同意隐私政策。首次启动 App 必须点「同意并继续」 |
| 地图白屏、定位无结果 | Key 未填写（`MAP_KEY=` 为空）或未绑定 `Android平台SDK` 服务 |
| 换签名后失效 | 更换密钥库后 SHA1 变化，需在控制台**补登新的 SHA1**（见 4.2 节提示） |

---

## 五、国产 ROM 后台权限开启说明

国产系统会主动清理后台，**必须手动开启以下权限**，否则锁屏或清理后台后收不到提醒。
App 内提供 **设置 → 后台权限设置引导**，含图文步骤与直达按钮。

### 5.1 通用步骤（所有机型都适用）

1. **允许自启动**：设置 → 应用管理 → 定位打卡 → 允许「自启动 / 开机启动」
2. **电池不优化**：设置 → 电池 → 关闭对本应用的「智能省电 / 后台冻结」
3. **允许后台运行**：设置 → 应用管理 → 定位打卡 → 后台运行 → 允许
4. **锁定后台任务**：打开最近任务界面，下拉本应用卡片（或点锁图标）锁定
5. 若系统弹出「本应用耗电异常」，选择**允许继续运行**

### 5.2 各机型路径

| 机型 | 自启动 | 电池 / 后台 |
| --- | --- | --- |
| **小米 / 红米（MIUI、HyperOS）** | 设置 → 应用设置 → 应用管理 → 定位打卡 → 自启动 | 同页 → 省电策略 → **无限制** |
| **华为（EMUI / HarmonyOS）** | 设置 → 应用 → 应用启动管理 → 定位打卡 → 关闭「自动管理」，手动打开自启动/关联启动/后台活动 | 设置 → 电池 → 更多电池设置 → 忽略电池优化 |
| **荣耀（MagicOS）** | 同上（应用启动管理） | 同上 |
| **OPPO / 一加 / realme（ColorOS）** | 设置 → 应用管理 → 定位打卡 → 允许自启动 | 设置 → 电池 → 应用耗电管理 → 允许后台运行；关闭「睡眠待机优化」 |
| **vivo / iQOO（OriginOS）** | 设置 → 应用与权限 → 权限管理 → 自启动 | 设置 → 电池 → 后台高耗电 → 允许 |
| **魅族（Flyme）** | 手机管家 → 权限管理 → 自启动 | 设置 → 电量管理 → 应用耗电管理 → 允许后台运行 |
| **三星（One UI）** | — | 设置 → 电池和设备维护 → 电池 → 后台使用限制 → 移出「休眠应用」；应用 → 定位打卡 → 电池 → **不受限制** |

### 5.3 验证是否生效

1. 回到 **设置 → 开始自检**，确认「电池优化」与「前台服务」两项显示**通过**；
2. 锁屏等待 5 分钟，观察是否仍能收到进入/离开提醒；
3. 从最近任务划掉 App，等待 1 分钟，下拉通知栏确认常驻通知「正在监控 N 个打卡点」仍在。

### 5.4 能力边界（如实说明）

- **厂商「自启动」开关没有任何公开 API**，App 无法读取或代你开启。自检中该项标记为「无法自动检测」，需你手动确认——这是刻意不做假。
- **厂商设置页跳转不保证精准**：这些页面是私有的，Activity 名称随 ROM 版本变化。App 会尽力直达，失败时降级到应用信息页，因此请以文字路径为准。
- **「强行停止」后无法自恢复**：用户在系统设置里 force-stop 应用后，任何广播与闹钟都不会送达。这是 Android 的安全边界，没有任何 App 能绕过。国产 ROM 的「一键清理」若等同 force-stop 亦然。

---

## 六、工程结构

### 6.1 仓库顶层

```
checkin-reminder/
├── README.md                    # 本文件
├── .gitignore                   # 忽略签名 / Key / 构建产物 / 高德 SDK 二进制
├── build.gradle.kts             # 根构建脚本
├── settings.gradle.kts
├── gradle.properties
├── gradlew  /  gradlew.bat
├── gradle/wrapper/              # Gradle Wrapper
├── local.properties.example     # 高德 Key 模板（真值不入库）
├── keystore.properties.example  # 签名配置模板（真值不入库）
├── app/                         # 主模块（详见 6.2）
├── docs/                        # 工程文档（审计 / 改进方案 / 商业化路线 / 抓日志指南）
├── tools/                       # 抓日志脚本（抓日志.bat + log-tool.ps1）
└── apk/                         # 对外分发的正式构建产物
```

### 6.2 app 模块源码

```
app/src/main/java/com/example/checkin/
├── CheckinApp.kt                     # Application：通知渠道 + 高德 SDK 隐私合规与 Key + 启动自恢复
├── data/
│   ├── local/
│   │   ├── CheckinDatabase.kt        # Room 数据库（v3，含 2→3 AutoMigration）
│   │   ├── Converters.kt             # CheckInSource 枚举转换器
│   │   ├── entity/
│   │   │   ├── CheckinPoint.kt       # 打卡点表 checkin_point
│   │   │   └── CheckinRecord.kt      # 打卡记录表 checkin_record + CheckInSource
│   │   └── dao/
│   │       ├── CheckinPointDao.kt    # 打卡点增删改查、按启用状态查询
│   │       └── CheckinRecordDao.kt   # 记录插入、按时间倒序、待确认记录
│   └── repository/                   # CheckinPointRepository / CheckinRecordRepository / BackupRepository / ServiceLocator
├── stats/                            # 纯 Kotlin 统计内核（零 Android 依赖，可单测）
│   ├── StatsCalculator.kt            # 在岗时长：区间配对 + 跨零点切分 + 同日并集
│   ├── StatsModels.kt                # 日统计 / 日状态 / 日历单元格 / 区间汇总
│   └── MonthCalendarModel.kt         # 月历网格构建（可配置周起始日）
├── backup/                           # 导入导出编解码（纯 Kotlin，可单测）
│   ├── CsvExporter.kt                # CSV 导出（UTF-8 BOM + RFC 4180 转义）
│   ├── BackupCodec.kt                # 备份快照编解码与版本校验
│   └── Json.kt                       # 零依赖 JSON 解析 / 生成
├── geofence/
│   ├── AMapLocationEngine.kt         # 高德定位客户端封装（主线程约束、动态调频）
│   ├── AMapGeoFenceRegistrar.kt      # 高德圆形围栏注册
│   ├── FenceStateMachine.kt          # ★ 围栏状态机（OUTSIDE/NEAR/INSIDE/LEFT）
│   ├── FenceMonitor.kt               # 进程级状态共享，供 UI 订阅
│   ├── GeofenceManager.kt            # 围栏总控：判定 → 落库 → 通知 → 调频
│   ├── LocationIntervalScheduler.kt  # 动态省电调度（30s / 5s，含迟滞）
│   ├── GeoUtils.kt                   # Haversine 距离计算（纯 Kotlin）
│   └── BootCompletedReceiver.kt      # 开机 / 应用更新后恢复监控
├── notify/
│   ├── Notifier.kt                   # 通知渠道 + 进入/离开/打卡成功三类通知
│   ├── NotificationHelper.kt         # 前台服务常驻通知
│   └── NotificationDeduper.kt        # ★ 防重复提醒去重器
├── service/
│   ├── CheckinForegroundService.kt   # 前台服务（START_STICKY + 唤醒锁 + 看活续期）
│   └── WatchdogReceiver.kt           # 看活广播：检查并拉起服务
├── ui/
│   ├── MainActivity.kt               # 底部三 Tab 容器
│   ├── MainViewModel.kt              # 主页状态（StateFlow）
│   ├── point/                        # 新增/编辑打卡点（内嵌高德地图）
│   ├── records/                      # 打卡记录页（时间轴 / 日历 / 统计 + 编辑 / 清空 / 导出）
│   ├── confirm/                      # 打卡确认页（通知点击入口）
│   ├── debug/                        # 定位调试页
│   ├── legal/                        # 隐私政策页
│   ├── permission/                   # Compose 权限申请与拒绝引导
│   ├── screen/                       # 打卡点列表页 / 设置页 / 后台引导页
│   └── theme/                        # Material3 主题
└── util/
    ├── Locator.kt                    # 定位工具类（单次 / 连续 / 停止）
    ├── LocationError.kt              # 高德错误码 → 可读中文
    ├── LocationSource.kt             # locationType → gps/network/cache
    ├── BatteryOptimizationHelper.kt  # 电池优化状态与厂商设置页跳转
    ├── RomUtils.kt                   # 国产 ROM 识别
    ├── SelfCheckRunner.kt            # 保活自检
    ├── ServiceWatchdog.kt            # 看活闹钟调度
    ├── PermissionUtils.kt            # 权限状态与拒绝原因
    └── PreferencesStore.kt           # DataStore（监控开关 / 提醒方式 / 隐私同意）
```

---

## 七、自查清单

### 7.1 权限清单（AndroidManifest）

| 权限 | 用途 |
| --- | --- |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 定位 |
| `ACCESS_BACKGROUND_LOCATION` | 后台定位（Android 10+，需用户手动选「始终允许」） |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` | 前台定位服务（后者 Android 14 必需） |
| `RECEIVE_BOOT_COMPLETED` | 开机恢复监控 |
| `POST_NOTIFICATIONS` | 通知（Android 13+ 运行时申请） |
| `VIBRATE` | 提醒震动 |
| `WAKE_LOCK` | 锁屏后维持 CPU，保证定位回调不中断 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 引导用户设为「不优化」 |
| `SCHEDULE_EXACT_ALARM` | 看活闹钟更准时（未授权自动降级） |
| `INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE` | 高德网络/辅助定位 |
| `ACCESS_LOCATION_EXTRA_COMMANDS` | 高德 A-GPS 辅助 |

### 7.2 组件声明

| 组件 | 声明要点 |
| --- | --- |
| `CheckinForegroundService` | `foregroundServiceType="location"`、`stopWithTask="false"`、`exported="false"` |
| `com.amap.api.location.APSService` | 高德定位服务，需显式声明且带 `foregroundServiceType="location"` |
| `BootCompletedReceiver` | 监听 `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `QUICKBOOT_POWERON` / `MY_PACKAGE_REPLACED`，`directBootAware="true"` |
| `WatchdogReceiver` | 看活广播，`exported="false"` |
| `CheckinConfirmActivity` | 通知点击入口，`launchMode="singleTop"`，代码中启用 `setShowWhenLocked` |
| `AddEditPointActivity` | 内嵌地图，需硬件加速 |

### 7.3 通知通道

| 通道 id | 重要性 | 用途 |
| --- | --- | --- |
| `NOTIFICATION_CHANNEL_CHECKIN` | **IMPORTANCE_HIGH** | 进入/离开提醒、打卡成功（震动 + 声音 + 锁屏可见） |
| `channel_service_v1` | IMPORTANCE_LOW | 前台服务常驻通知（静默） |

> 通道重要性创建后不可修改，如需调整必须更换通道 id。

### 7.4 Room 迁移

- 当前 `version = 3`，导出 schema 位于 `app/schemas/…/1.json`、`2.json`、`3.json`；
- v1 → v2：表/字段重命名（`check_point` → `checkin_point`、`radius` → `radiusMeters` 等）；
- v2 → v3：新增可空列 `checkin_record.note`，使用 `@AutoMigration(from = 2, to = 3)`，
  已在真机验证升级不丢数据；降级路径为 `fallbackToDestructiveMigrationOnDowngrade()`；
- ⚠️ **新增字段务必走 `AutoMigration` 或显式 `Migration`**，否则会破坏用户已有数据。

### 7.5 内存泄漏自查

| 检查项 | 结论 |
| --- | --- |
| `bindService` / `ServiceConnection` | 未使用（全程 `startForegroundService`，无绑定泄漏） |
| 动态注册广播 | 未使用（全部清单静态注册，无 unregister 遗漏） |
| 定位监听器 | `setLocationListener` 在 `stop()` 中成对 `unRegisterLocationListener` + `onDestroy()` |
| 高德围栏客户端 | `AMapGeoFenceRegistrar.release()` 中 `setGeoFenceListener(null)` 并置空引用 |
| 唤醒锁 | `acquire(1 小时超时)` + `onDestroy`/`ACTION_STOP` 中 `release()` 兜底 |
| ViewModel 持有的 Locator | `CheckinConfirmViewModel` / `DebugViewModel` 均在 `onCleared()` 调用 `stopLocation()` |
| 工作协程作用域 | `GeofenceManager` 在 `stop()` 取消、`start()` 重建（避免作用域取消后无法再启动） |
| Context 引用 | 统一使用 `applicationContext`，无 Activity 泄漏 |

---

## 八、已知限制（如实说明）

1. **compileSdk = 33**：本机 SDK 仅安装 android-33，且 `dl.google.com` 不可达无法下载 android-34。
   由此 XML 主题的 material 库固定在 1.9.0（1.11.0 起引用 API 34 资源），
   并关闭了 AAR 元数据校验任务。升级路径见第三节。
2. **仅支持 ARM 架构**：高德定位原生库只提供 `arm64-v8a` 与 `armeabi-v7a`，
   **x86 模拟器无法定位**，请用真机测试。
3. **工程与单元测试均需在纯 ASCII 路径下运行**（当前：`D:\projects\checkin`），原因见第三节。
4. **厂商后台限制无法完全规避**：见 5.4 节的能力边界。
5. **真机验证范围**：已在 **Xiaomi 11（Android 13 / arm64-v8a）** 上完成安装升级、
   定位、围栏进入 / 离开、通知、记录三视图、备份导出与恢复等回归；华为 / OPPO / vivo
   等机型的后台保活表现仍需自行确认（各机型策略差异见第五节）。
6. **`compileSdk = 33` 的替代路径**：若你已安装 android-34，可把 `gradle/libs.versions.toml`
   的 `compileSdk` 改为 `"34"`，并删除 `app/build.gradle.kts` 底部的 `CheckAarMetadataTask` 禁用代码。

---

## 九、工程文档

| 文档 | 内容 |
| --- | --- |
| [`docs/代码审计报告-v1.6.0.md`](docs/代码审计报告-v1.6.0.md) | 全量静态审计：架构、缺陷、安全、性能、可维护性，含风险等级与修复优先级 |
| [`docs/代码审计修复说明-v1.6.0.md`](docs/代码审计修复说明-v1.6.0.md) | 上述问题的逐项修复记录与真机验证结论 |
| [`docs/功能与UI改进方案-v1.7.md`](docs/功能与UI改进方案-v1.7.md) | 对标同类开源项目与商业软件的改进点与优先级 |
| [`docs/商业化实施路线-v1.7.md`](docs/商业化实施路线-v1.7.md) | 核心功能范围、差异化亮点、非功能要求与迭代路线 |
| [`docs/如何抓日志.md`](docs/如何抓日志.md) | 反馈「定位无反应 / 闪退」时的日志抓取指引 |

> 抓日志脚本在 [`tools/`](tools/)：双击 `抓日志.bat`（会调用同目录的 `log-tool.ps1`），
> 输出在该目录下的 `logs/`（已被 `.gitignore` 忽略）。

---

## 十、安全与合规说明

- **不入库的敏感内容**（已在 `.gitignore` 排除，仓库内只提供 `.example` 模板）：
  签名密钥 `*.jks`、签名口令 `keystore.properties`、高德 Key `local.properties`。
- **需自行下载**：高德 SDK 二进制（`app/libs/*.jar`、`app/src/main/jniLibs/**/*.so`），见第 3 节第 2 步。
- **数据留在本机**：打卡点与记录（坐标、时间、备注）仅存于设备本地 Room 数据库，
  App 不做任何数据上传，不含统计埋点；详见 App 内「设置 → 隐私政策」。
- **正式分发前**：请更换为自有的强口令密钥库，并在高德控制台登记其 SHA1（步骤见 4.2 节）。

