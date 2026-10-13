# 定位打卡 · 功能与 UI 完善方案（v1.6.0 → v1.7）

| 项 | 内容 |
| --- | --- |
| 编制日期 | 2026-10-13 |
| 当前版本 | v1.6.0（versionCode 2） |
| 调研范围 | GitHub 同类开源项目 5 个 + 市面成熟软件 7 款 |
| 结论 | 功能缺口 **19 项**、UI 缺口 **12 项**；其中 **P0 四项、P1 七项** |
| 本文性质 | 方案与调整说明（分析 → 差距 → 优先级 → 实现路径 → 验收） |

> 本文只做方案，不含代码改动。确认优先级后即可按批次实施。

---

## 一、同类项目调研

### 1.1 直接对标：牛马时录 workTimeTrace ★

同一技术栈（Kotlin + Compose + Material 3 + Room + DataStore + 高德定位/地图 + 前台服务 + 开机自启）、同一场景（地理围栏自动打卡）、同一地区市场（国产 ROM、无 GMS）。**这是最有价值的参照物。**

它的功能面：

| 能力 | 具体做法 |
| --- | --- |
| 地图选点 | 点选 + 拖动调整，**滑块设半径 50–1000 米**，支持**手动输入经纬度**与**一键取当前位置** |
| 自动打卡 | 进圈记上班、离圈记下班，**离开需连续 2 次定位确认**防漂移 |
| **加班统计** | 工作日超**标准工时**（默认 8h，可调）计加班；周末/法定节假日全天在岗计加班；**内置 2025–2026 国务院节假日与调休表** |
| **月度明细** | 按月看每天上下班时间、在岗时段、加班时长；**记录支持补录 / 编辑 / 删除（单条或整天）** |
| **诊断日志** | **一键导出运行日志**（定位/打卡/服务状态） |
| 状态可视 | 常驻通知实时显示「在岗中 / 等待进入范围」 |
| 多点位 | 多工作地点 |
| **双定位通道** | 高德融合定位失败时**自动降级系统 LocationManager**，其余功能不受影响 |
| 抗噪 | 定位回调**加锁 + 串行化**；**GPS 锚点一致性过滤**（解决魅族网络定位 IP 级跨城漂移） |

**对我们的启示**：它的定位链路健壮性（降级 + 锚点过滤 + 连续确认）明显强于我们；它的记录可编辑与一键导出，正是我们缺的。

### 1.2 其他开源项目

| 项目 | 亮点做法 |
| --- | --- |
| **GeoTrace** | 长按建围栏并**自动解析地址名**；**多点路线规划**（最近优先 + 真实路网 + 每段距离/ETA）；**Visits 历史含进入/离开时间与停留时长**；到点自动推进下一站 |
| **Location Reminder**（Udacity 毕业项目） | 长按地图落点；**「到达即完成」语义**——触发后标记完成并注销围栏，适合一次性提醒 |
| **Location Reminder**（Mapbox 版） | **dwell（停留）检测**；Clean Architecture + Koin；Geofence 数据以 GeoJSON 建模 |
| **Phone Manager** | 定位历史按日期过滤；**行程检测 + 交通方式识别**；Webhook 自动化；**服务健康看门狗 + 自动恢复** |

### 1.3 市面成熟软件

| 产品 | 可借鉴的做法 |
| --- | --- |
| **Samsung Reminder** | 语义化设置「**到达时提醒 / 离开时提醒**」，可直接选**当前位置**；提醒可附**图片**、颜色分类、**可重复的地点提醒** |
| **Tasks.org / TickTick / Todoist / Any.do** | 地点提醒挂在**任务**上（标题/备注/优先级）；到达与离开**双触发**；**可选半径**；Any.do 支持**一条任务挂多个地点** |
| **出勤记录**（App Store） | **日/周/月三级统计**（工作时数、实际工作时数、加班时数）+ **柱状图 + 日历视图**；**补卡可回溯任意日期**；**照片**记录工作现场；**CSV / ZIP 导出**（含照片 + JSON + 说明）；**劳动法休息规则校验并警告** |
| **上班打卡日历** | **首页醒目打卡按钮**；**日历视图**用颜色标记正常/迟到/请假/加班；**备注与补签**；**导出表格**；**后台白名单引导** |
| **打卡习惯 / 打卡清单** | **日历热力图 + 连续天数（streak）+ 完成率**；**备注 / 照片 / 心情 / 评分**；**补卡**；**成就徽章**；**JSON 备份与恢复**；**桌面小组件**（同步 <1s、点击带震动反馈） |
| **自律打卡** | 卡片式任务 + **等宽时间轴**；**番茄钟统计柱状图**（超 4h 标金色）；**手势密码**；**CSV 导出** |

### 1.4 行业共性做法（横向提炼）

把上述项目横着切一刀，成熟产品有 **8 条共识**：

| # | 共识 | 说明 |
| --- | --- | --- |
| **A** | **记录必须能事后修** | 13 个样本里 10 个支持补卡/编辑/删除单条。定位漂移误判、忘打卡是常态，不可修＝数据不可信 |
| **B** | **记录必须能看懂** | 日/周/月统计 + 日历视图 + 连续天数，几乎是标配；纯流水列表已不够 |
| **C** | **数据必须能带走** | CSV / JSON 导出 + 备份恢复。「卸载即全丢」是用户零容忍项 |
| **D** | **提醒必须能分级** | 轻→重：横幅 → 响铃震动 → 全屏 → 持续响铃直到处理；**通知带动作按钮**（立即打卡 / 稍后） |
| **E** | **设置必须能就近** | 到达/离开分别开关、半径可选档位、直接选「当前位置」、手动输入经纬度兜底 |
| **F** | **失效必须能自证** | 诊断日志一键导出 + 自检清单 + 服务看门狗。国产 ROM 保活是刚需场景 |
| **G** | **界面必须自适应 + M3** | 紧凑=底部栏 / 中=导航栏(Rail) / 展开=抽屉；edge-to-edge；48dp 触控目标；用类型尺度而非手挑字号 |
| **H** | **状态不能只靠颜色** | 色盲与阳光下可读性：图标 + 文字 + 颜色**三重编码** |

---

## 二、本项目现状与差距

### 2.1 已经做得好的（保留，不折腾）

- **架构**：单 Activity + Compose + MVVM + Room + DataStore，分层干净、无循环依赖
- **围栏状态机**（OUTSIDE / NEAR / INSIDE / LEFT）+ **动态调频**（远 30s、近 5s）—— 比牛马时录的固定 10 分钟**更省电且更灵敏**，是相对优势
- **双重去重**：状态机层 + `NotificationDeduper`，停在点位内不会反复弹
- **保活链路完整**：前台服务 + `ServiceWatchdog` + 开机自启 + 电池优化白名单引导 + ROM 厂商识别
- **自检 10 项**（含「全屏提醒可用」）+ **定位调试页**（含回调日志、参数实时调节）—— 与牛马时录「诊断日志」同思路，颗粒度更细
- **合规与适配**：隐私弹窗、Android 14 全屏提醒授权（反射兼容 compileSdk 33）、通知渠道版本化
- **测试**：88 用例覆盖状态机、距离、调频、去重、错误映射

### 2.2 功能差距

| # | 维度 | 现状 | 行业做法 | 差距 |
| --- | --- | --- | --- | --- |
| F1 | **记录可编辑** | 只有「**一键清空全部**」，且**无二次确认**；`MainViewModel.deleteRecord()` **已实现但未接入任何界面** | 补卡 / 编辑 / 删除单条 + 撤销 | **P0** |
| F2 | **统计** | 无 | 日/周/月汇总 + 柱状图 + 完成率 | **P0** |
| F3 | **日历视图** | 无 | 月历 + 状态色块/热力 | **P0** |
| F4 | **导出与备份** | 无。且 `allowBackup=false`、无云同步 → **卸载/换机数据全丢，无迁移路径** | CSV / JSON 导出 + 备份恢复 | **P0** |
| F5 | 记录筛选与搜索 | 无（只有时间倒序全量） | 按点位/日期/来源筛选 + 搜索 | P1 |
| F6 | 备注 / 照片 | 无 | 文字备注 + 照片/心情 | P1 |
| F7 | 提醒分级 | 通知 + 全屏；开关仅 铃声/震动/全屏 | **通知动作按钮**、稍后提醒、自定义铃声 | P1 |
| F8 | 打卡点详情页 | 点击**直接进编辑**，看不到该点的历史与统计 | 详情页：地图预览 + 统计 + 该点记录 | P1 |
| F9 | 地图总览 | 无（只能逐点进地图页） | 一屏看全部点位 + 当前位置 | P1 |
| F10 | 首页「今天」 | 无（首屏是点位列表） | 醒目状态卡 + 一键打卡 + 今日概览 | P1 |
| F11 | 连续天数 / 目标 | 无 | streak + 每日目标 + 完成率 | P2 |
| F12 | 桌面小组件 / 快捷磁贴 | 无 | Widget + QS Tile | P2 |
| F13 | 漏打卡提醒 | 无 | 检测「应打未打」并提醒 | P2 |
| F14 | **定位降级** | 仅高德单通道，失败即无定位 | 高德 → 系统 LocationManager 自动降级 | P2 |
| F15 | 防漂移 | 有 NEAR 分级；**离开无连续确认** | 离开需连续 2 次确认 | P2 |
| F16 | 深色模式 | 仅跟随系统，**无手动开关** | 三态：跟随 / 浅色 / 深色 | P2 |
| F17 | 分组 / 标签 | 仅有颜色字段 | 分组、标签、置顶 | P3 |
| F18 | 轨迹回放 | 无 | 历史轨迹 | P3 |
| F19 | 多语言 | 仅中文 | i18n | P3 |

### 2.3 UI 差距

| # | 维度 | 现状 | 行业 / M3 规范 | 差距 |
| --- | --- | --- | --- | --- |
| U1 | **自适应布局** | 恒用底部导航栏 | 紧凑=Bar / 中=Rail / 展开=Drawer；平板不得原样搬底部栏 | P1 |
| U2 | **动效** | 几乎无 | 弹簧曲线（非 FastOutSlowIn）、共享元素过渡、列表增删动画、**预测式返回** | P1 |
| U3 | **空状态** | 仅一句文字（`EmptyHint` / `records_empty`） | 插图 + 标题 + 副标题 + 主行动按钮 | P1 |
| U4 | M3 Expressive | 标准 M3 基线 | 形状变形、可变字重图标（Material Symbols）、色调高度 | P1 |
| U5 | 列表信息层级 | 卡片内堆等宽坐标 + 独立「编辑」按钮 + 删除图标，密度低、动作冗余 | 主次分明；动作收敛到侧滑/详情页 | P1 |
| U6 | 可访问性 | 围栏状态**仅靠颜色+文字**，颜色是主要编码 | 图标 + 文字 + 颜色三重编码；TalkBack 描述 | P2 |
| U7 | 动态取色 | 有开关但**默认关闭** | Android 12+ 默认开启 Material You | P2 |
| U8 | 设置页 | 8 段平铺超长单页 | 分组折叠 / 二级页 / 页内搜索 | P2 |
| U9 | 手势交互 | 无（无侧滑、无长按多选） | 侧滑删除/编辑、长按进入多选 | P2 |
| U10 | 加载态 | 无骨架屏，直接空白 | Shimmer 占位 | P3 |
| U11 | 顶栏行为 | 固定 `TopAppBar` | `LargeTopAppBar` + 滚动折叠 | P3 |
| U12 | 底部导航信息量 | 无徽标 | Badge 提示新增记录 / 待打卡 | P3 |

---

## 三、优先级与批次规划

```
批次 A（P0 · 止血与数据资产）   F1 F2 F3 F4            ← 建议立即做
     ↓
批次 B（P1 · 对齐行业基准）     F5–F10  U1–U5
     ↓
批次 C（P2 · 进阶能力）         F11–F16  U6–U9
     ↓
批次 D（P3 · 锦上添花）         F17–F19  U10–U12
```

**为什么 F1–F4 是 P0**：

1. **F1 是已经埋好的雷**：`clearRecords()` 当前**点一下图标就清空全部记录、无任何确认**；而「删除单条」能力早已写好却从未接线。用户一次误触即永久丢失全部数据 —— 这属于必须立刻修的正确性缺陷，而非新功能。
2. **F4 是数据资产风险**：`allowBackup=false`（安全结论，需保留）叠加无导出，等于**应用一旦卸载，用户所有打卡历史归零**，且无任何补救路径。成熟产品无一例外都提供导出。
3. **F2/F3 决定产品是否「有用」**：没有统计与日历，记录就只是一堆流水，用户无法回答「我这个月去了几次」——这是打卡类产品的核心价值交付点。

---

## 四、实现方案

### 批次 A

#### A1 · 记录可编辑 / 删除 / 撤销（F1）

**数据层**
- `CheckinRecordDao`：已具备 `deleteById`；补 `@Update suspend fun update(record: CheckinRecord)` 与 `@Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(record): Long`（撤销回填需保留原 id）。
- `CheckinRecordRepository`：补 `update(record)`、`upsert(record)`。

**状态层**
- `MainViewModel`：
  - 把已有 `deleteRecord(record)` 接进界面；
  - 新增「**待撤销缓冲区**」：`private var lastDeleted: CheckinRecord? = null`，删除时暂存，暴露 `undoDelete()`；缓冲区在 Snackbar 消失或下一次删除/进入页面时失效；
  - 新增 `updateRecord(record)`。

**UI**
- `RecordsScreen` 新增参数 `onDelete: (CheckinRecord) -> Unit`、`onEdit: (CheckinRecord) -> Unit`。
- 每条记录用 `SwipeToDismissBox` 侧滑删除（`confirmValueChange` 返回后触发删除）；卡片右侧不再放常驻删除图标。
- 顶栏「清空」补 `AlertDialog` 二次确认，文案明确「共 N 条，清空后不可恢复」。
- Snackbar 带 action：`SnackbarHostState.showSnackbar(message, actionLabel = "撤销")` → 返回 `ActionPerformed` 时执行 `undoDelete()`。
- `MainActivity` 接线：`RecordsScreen(records, onDelete = viewModel::deleteRecord, onEdit = ..., onClear = { pendingClear = true })`。

**验收**
- 侧滑删单条 → Snackbar 出现「撤销」→ 点击后记录回到原位（含原 id、原时间）。
- 点顶栏清空 → 弹确认框显示条数；取消不产生任何写库。
- 单测：`RecordsUndoTest` 覆盖「删除→撤销→数据一致」与「撤销窗口失效」。

---

#### A2 · 统计与日历（F2 / F3）

**设计原则**：聚合逻辑放**纯 Kotlin**（不写进 SQL），原因有二 ——（1）本场景真正的难点是**区间配对与跨日切分**，SQL 表达力不足；（2）纯函数才能被单元测试覆盖。

**新增 `stats` 包**

```kotlin
/** 一天一个打卡点的聚合结果 */
data class DailyPointStat(
    val day: LocalDate,
    val pointId: Long,
    val pointName: String,
    val firstEnterAt: Long?,     // 首次进入
    val lastExitAt: Long?,       // 最后离开
    val dwellMinutes: Int,       // 当日累计在岗时长（多段取并集）
    val enterCount: Int,
    val exitCount: Int,
    val closed: Boolean          // 区间是否已闭合（仍在岗时为 false）
)

/** 变更：某点位当日状态，用于日历色块 */
enum class DayStatus { NO_RECORD, ENTERED_ONLY, COMPLETED, MULTI_SEGMENT }
```

**`StatsCalculator`（`object`，纯函数）**
- 输入 `List<CheckinRecord>` + 时区，输出 `Map<LocalDate, List<DailyPointStat>>`。
- 规则（对齐牛马时录的成熟规则）：
  1. 一次「在岗区间」= 一条 `is_enter=1` 到其后最近一条 `is_enter=0` 的配对；
  2. **跨午夜**按自然日切分归属；
  3. 同日多段区间**取并集**，重叠不重复计算；
  4. **未闭合**区间（仍在岗）实时计入今天（`closed=false`）；
  5. `MANUAL` 记录计入「打卡次数」但不参与区间配对。

**`MonthCalendarModel`**：给定年月 → `List<DayCell>`（含日期、`DayStatus`、点数、是否今天）。

**UI 改造（`RecordsScreen`）**
- 顶部加 `SingleChoiceSegmentedButtonRow`：**时间轴 | 日历 | 统计**（默认时间轴，选择用 `rememberSaveable` 保持）。
- **日历**：自绘 `LazyVerticalGrid(columns = 7)`，格子内为圆角色块 —— `COMPLETED`=primary 填充、`MULTI_SEGMENT`=primaryContainer、`ENTERED_ONLY`=tertiary、`NO_RECORD`=surfaceVariant；点击某天 → 下方联动该日明细列表。
- **统计**：`Column` + `SegmentedButton`（日/周/月）+ 指标卡（本月打卡天数、累计在岗、日均、最长连续天数）+ **自绘 `Canvas` 柱状图**（不引入第三方图表库，避免新依赖与体积增长）。
- **时间轴**：现有 `LazyColumn` 改为**按日期分组**（sticky header `stickyHeader`），日期标题右侧显示当日汇总。

**验收**
- `StatsCalculatorTest` 覆盖：正常配对、跨午夜切分、同日多段并集、未闭合区间、孤立 EXIT 无 ENTER。
- 手动构造跨月数据，日历色块与统计数字自洽。

---

#### A3 · 数据导出与备份（F4）

**导出 CSV**
- 入口：设置页「数据」分区 → 「导出打卡记录（CSV）」。
- 实现：`ActivityResultContracts.CreateDocument("text/csv")`（SAF，minSdk 26 全兼容，无需存储权限）。
- 列：`日期时间, 打卡点, 来源, 纬度, 经度, 距离(米), 进出方向`。
- **必须写 UTF-8 BOM**（`\uFEFF`），否则 Excel/WPS 打开中文乱码 —— 这是最常见的低级坑。
- 文件名：`打卡记录_yyyyMMdd_HHmm.csv`。

**备份 / 恢复 JSON**
- `BackupManager`（用 `org.json`，**零新增依赖**）：
  ```json
  {
    "schemaVersion": 1,
    "exportedAt": 1750000000000,
    "points": [ ... ],
    "records": [ ... ],
    "prefs": { "notifySound": true, "notifyVibrate": true, "notifyFullScreen": true }
  }
  ```
- 恢复：**在单个 Room 事务内** `deleteAll()` + `insertAll()`，失败整体回滚，避免半量数据。
- 恢复前弹确认框，明确「将覆盖当前全部数据」。

**说明**：`allowBackup=false` 保持不变（P1 安全结论：避免打卡位置隐私被系统备份外泄）；用**用户显式导出**替代系统自动备份，兼顾隐私与可迁移性。

**验收**
- 导出文件用 Excel 与 WPS 打开，中文不乱码、列对齐正确。
- 单测：`BackupCodecTest` 覆盖序列化→反序列化往返一致、`schemaVersion` 不匹配时拒绝恢复、损坏 JSON 抛可读错误。

---

### 批次 B

#### B1 · 通知动作与提醒分级（F7）
- `Notifier` 用 `NotificationCompat.Builder.addAction()`：
  - 进入通知 → 「**立即打卡**」（`PendingIntent` 直达 `CheckinConfirmActivity`，带 `autoCheckIn=true`）、「**稍后提醒**」（15 分钟后重发，`AlarmManager`）。
  - 离开通知 → 「记录离开」/「忽略本次」。
- 新增可选「**持续提醒**」：`setOngoing(true)` + 循环铃声，直到用户处理；默认**关闭**（避免打扰与耗电）。
- 自定义铃声：`RingtoneManager.ACTION_RINGTONE_PICKER` → Uri 存 DataStore。
- 落点：`Notifier.kt`、`PreferencesStore`（新增 `soundUri` 字段）、`SettingsScreen` 提醒方式分区。

#### B2 · 打卡点详情页 + 地图总览（F8 / F9）
- 抽出可复用组件 `CheckinMapView`（从 `PointEditScreen` 的地图封装里提取，含 marker/circle/相机控制与生命周期释放），供编辑页与详情页共用。
- 新页面 `PointDetailScreen`：顶部小地图预览 → 该点统计卡（本月打卡次数、平均停留）→ 该点记录列表。
- DAO 新增 `@Query("SELECT * FROM checkin_record WHERE pointId = :pointId ORDER BY time DESC") observeByPointId(...)`（**过滤下推到 SQL**，不在应用层过滤）。
- 导航调整：列表项单击 → 详情页；详情页内「编辑」→ 编辑页。
- 新增「地图总览」入口：一屏渲染全部点位圆 + 当前位置，点击圆跳详情。

#### B3 · 首页「今天」状态卡（F10）
- 在打卡点页首屏（`MonitoringBar` 位置）升级为**状态卡**：
  - 大状态：`监控中 · 3 个点位生效` / `未开启监控`；
  - 实时：已进入的点位名 + 当前距离（`fenceStates` 已有数据）；
  - 快捷动作：`立即打卡`（取最近点位）、`开始/停止监控`；
  - 今日概览：今日打卡次数、最近一次时间。

#### B4 · 记录筛选与搜索（F5）
- 筛选条：点位 chips（多选）、日期范围、来源。
- 搜索：`pointName LIKE '%' || :q || '%'` **下推到 SQL**；新增 `observeFiltered(pointId?, from, to, q)`。
- 排序：时间倒序 / 正序切换。
- 落点：`CheckinRecordDao`、`CheckinRecordRepository`、`RecordsScreen`。

#### B5 · 自适应布局与 M3 Expressive（U1 / U2 / U4）
- 引入 `androidx.compose.material3:material3-adaptive-navigation-suite`，用 **`NavigationSuiteScaffold`** 替换手写 `NavigationBar`，自动按窗口宽度切换 Bar / Rail / Drawer。
- 把导航目的地抽成 `TopLevelDestination` 枚举（图标 + 标题 + 路由），使**导航状态与 UI 容器解耦** —— 这是 M3 明确要求的做法（同一份目的地定义在 Bar 与 Rail 间复用，不重复）。
- 动效：改用 M3E 弹簧曲线；列表卡片 → 详情页使用 `SharedTransitionLayout` 共享元素过渡；接入 `PredictiveBackHandler` 支持预测式返回。
- 图标改用 **Material Symbols 可变字重**，按状态调整 fill/weight，替代当前的 filled-only。
- 触控目标统一校验 ≥ 48×48dp、间距 ≥ 8dp。

#### B6 · UI 细节打磨（U3 / U5）
- 抽 `EmptyState` 组件：图标 + 标题 + 副标题 + 主按钮（如「还没有打卡点」→「添加第一个打卡点」）。
- 列表卡片信息层级重构：名称（titleMedium）→ 半径 + 启用状态（bodySmall）→ 围栏状态（图标 + 文字 + 颜色）；**坐标移出列表**，只留在详情页。
- 移除列表卡片内的「编辑」文字按钮（改侧滑或进详情），降低动作冗余。

---

### 批次 C（P2，摘要）

| 项 | 方案要点 |
| --- | --- |
| F14 定位降级 | 抽 `LocationEngine` 接口，`AMapLocationEngine` 与 `SystemLocationEngine`（LocationManager）双实现；高德连续失败 N 次或返回 auth 失败时自动切换，并在自检页明示当前通道 |
| F15 离开连续确认 | `FenceStateMachine` 增加可配置 `exitConfirmCount`（默认 1，建议档 2），状态需连续 N 次判定为外才产出 EXIT |
| F16 深色模式三态 | `PreferencesStore` 加 `themeMode`（SYSTEM/LIGHT/DARK），`CheckinTheme` 读取 |
| F12 小组件 + 磁贴 | Glance 实现桌面 Widget（显示状态 + 一键打卡）；`TileService` 提供快捷开关 |
| F13 漏打卡提醒 | 每日固定时刻检查「已启用点位当日是否有记录」，无则发提醒；用 `WorkManager` 周期任务 |
| F11 连续天数 | 基于 `StatsCalculator` 计算 streak 与完成率，展示在首页状态卡 |
| U6 可访问性 | 围栏状态改为「图标 + 文字 + 颜色」三重编码；为等宽坐标与色块补 `contentDescription` |
| U7 动态取色 | Android 12+ 默认开启，设置页提供关闭项 |
| U8 设置页 | 拆为「提醒 / 保活 / 数据 / 关于」四个二级页，主页只留监控开关与状态 |
| U9 手势 | 侧滑删除/编辑、长按多选批量操作 |

---

### 批次 D（P3，摘要）

分组与标签（`CheckinPoint` 加 `groupId`，新增 `point_group` 表）、历史轨迹回放、多语言（`values-en` 起步）、骨架屏、`LargeTopAppBar` 滚动折叠、底部导航 Badge。

---

## 五、调整说明

### 5.1 依赖变更清单

| 依赖 | 用途 | 批次 | 备注 |
| --- | --- | --- | --- |
| `material3-adaptive-navigation-suite` | 自适应导航 | B | 官方组件，体积小 |
| `foundation` 的 `SwipeToDismissBox` | 侧滑删除 | A | 已在 Compose BOM 内，无需新增 |
| `androidx.glance:glance-appwidget` | 桌面小组件 | C | 仅 C 批次 |
| `androidx.work:work-runtime-ktx` | 漏打卡检测 | C | 仅 C 批次 |

**图表库不引入**：柱状图/热力块用 Compose `Canvas` 自绘即可，避免为此新增第三方依赖（体积与供应链风险不划算）。JSON 用 `org.json`（Android 内置），亦不新增依赖。

### 5.2 数据模型与迁移策略

| 变更 | 表/字段 | 迁移方式 |
| --- | --- | --- |
| A1 | 无表结构变更（仅新增 DAO 方法） | 无需迁移 |
| B2 | 无表结构变更（新增按 pointId 查询） | 无需迁移 |
| F6 备注 | `checkin_record.note: String?` | `@AutoMigration(from=当前, to=当前+1)` |
| F11 分组 | 新表 `point_group` + `checkin_point.groupId` | 同上 |

**注意**：当前数据库为 `fallbackToDestructiveMigrationOnDowngrade()`（v1.6.0 已修正为仅降级时销毁），升级路径必须用 `@AutoMigration` 显式声明，**绝不可**回退到 `fallbackToDestructiveMigration()`。

### 5.3 测试策略

| 层 | 覆盖对象 | 形态 |
| --- | --- | --- |
| 纯逻辑（新增重点） | `StatsCalculator`、`MonthCalendarModel`、`BackupCodec`、`ExportCsvWriter` | JVM 单测，无 Android 依赖 |
| 已有 | `FenceStateMachine`、`GeoUtils`、`LocationIntervalScheduler`、`NotificationDeduper`、`LocationError` | JVM 单测，保持全绿 |
| DAO | 新增查询与 upsert | Room in-memory（需引入 `room-testing` + Robolectric，或用 `androidTest`） |
| 真机 | 通知动作、侧滑、日历点击、导出去向 | `adb` + `uiautomator` 脚本化回归 |

### 5.4 明确不做 / 暂缓

| 项 | 原因 |
| --- | --- |
| 云同步 / 多端 | 与「数据只存本机」的隐私定位冲突；导出+备份已覆盖迁移需求 |
| 账号体系 / 团队考勤 | 超出个人提醒工具定位，且引入合规成本 |
| 路线规划 / 导航（GeoTrace 模式） | 与「围栏提醒」核心场景偏离 |
| 轨迹回放 | 收益有限，耗电与存储成本高，留待 D 批次评估 |
| 广告 / 会员 | 无商业化诉求 |

### 5.5 建议实施顺序

```
A1（当日可完成，先止血）
  → A2 + A3（数据价值交付）
  → B1 + B6（提醒与 UI 细节，感知最强）
  → B2 + B3 + B4（页面结构升级）
  → B5（自适应与动效，改动面最大，放最后）
  → C / D
```

**B5 单独放最后**的原因：它要改导航骨架，与 B2/B3/B4 的新页面同时进行会互相干扰，且回归面最大，适合在功能稳定后一次性收口。

---

## 六、一页速览

| 批次 | 内容 | 用户感知 |
| --- | --- | --- |
| **A** | 删记录有确认与撤销、月度统计、日历视图、CSV/JSON 导出备份 | 「数据丢不了了，也看得懂了」 |
| **B** | 通知一键打卡、点位详情、首页今天卡、筛选搜索、自适应布局与动效 | 「用起来像个成熟产品了」 |
| **C** | 定位降级、防漂移、深色三态、小组件、漏打卡提醒、无障碍 | 「更可靠、更能融入日常」 |
| **D** | 分组标签、轨迹、多语言、骨架屏、滚动折叠、徽标 | 「细节讲究」 |

> 下一步：确认批次 A 的范围后即开始实施。批次 A 四项均为**低风险、可单测、不动数据库结构**的改动，可在不破坏现有 88 个用例的前提下增量交付。
