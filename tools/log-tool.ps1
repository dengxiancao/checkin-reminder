# 职责：抓取本 App 的运行日志并生成可直接发送的文本文件（双击 抓日志.bat 即会调用本脚本）
#
# 为什么用 PowerShell 而不是纯批处理：日志过滤需要中文关键字匹配，
# 批处理在 cmd 下的编码处理极易出错（GBK/UTF-8 混乱），PowerShell 原生 UTF-8 更可靠。

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$Adb = 'C:\Android\Sdk\platform-tools\adb.exe'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$OutDir = Join-Path $ScriptDir 'logs'
$OutFile = Join-Path $OutDir 'checkin-log.txt'
$CrashFile = Join-Path $OutDir 'crash-log.txt'
$FullFile = Join-Path $OutDir 'full-log.txt'

function Write-Line($text = '') { Write-Host $text }
function Write-Head($text) {
    Write-Line ('=' * 60)
    Write-Line "  $text"
    Write-Line ('=' * 60)
}

Write-Head '定位打卡 - 日志抓取工具'
Write-Line

# ---------------- 0. 环境检查 ----------------
if (-not (Test-Path $Adb)) {
    Write-Line "[错误] 找不到 adb: $Adb"
    Write-Line '       请确认 Android SDK 的 platform-tools 已安装。'
    Write-Line
    Read-Host '按回车键退出'
    exit 1
}

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }

# ---------------- 1. 设备检查 ----------------
Write-Line '[1/5] 检查手机连接...'
$devicesRaw = & $Adb devices -l 2>&1
# 只保留真正的设备行（排除表头与 daemon 提示）
$deviceLines = $devicesRaw | Where-Object {
    $_ -match '\s+device(\s|$)' -and $_ -notmatch 'List of devices' -and $_ -notmatch 'daemon'
}

if (-not $deviceLines -or $deviceLines.Count -eq 0) {
    Write-Line
    Write-Line '[错误] 没有检测到已连接的手机。'
    Write-Line
    Write-Line '  请依次确认：'
    Write-Line '    1. 手机已用数据线连到电脑'
    Write-Line '    2. 手机「设置 - 关于手机」连点 7 次版本号，开启开发者选项'
    Write-Line '    3. 开发者选项里打开「USB 调试」'
    Write-Line '    4. 手机弹出「允许 USB 调试吗？」时点「允许」'
    Write-Line '    5. 手机 USB 模式选「传输文件」而不是「仅充电」'
    Write-Line
    Write-Line '  当前 adb 看到的设备列表：'
    $devicesRaw | ForEach-Object { Write-Line "    $_" }
    Write-Line
    Read-Host '按回车键退出'
    exit 1
}

Write-Line '      已连接：'
$deviceLines | ForEach-Object { Write-Line "    $_" }
Write-Line

# ---------------- 2. 清理旧日志 ----------------
Write-Line '[2/5] 清理旧日志...'
& $Adb logcat -c 2>&1 | Out-Null
& $Adb logcat -b crash -c 2>&1 | Out-Null
Write-Line '      完成。'
Write-Line

# ---------------- 3. 等待复现 ----------------
Write-Head '请现在在手机上操作，把问题复现出来！'
Write-Line
Write-Line '  建议依次做这几步（每步之间停 2-3 秒）：'
Write-Line '    1. 打开「定位打卡」App，确认版本号是 1.6.0'
Write-Line '    2. 进「设置 - 定位调试」，点「单次定位」'
Write-Line '    3. 打开某个打卡点，点「定位到当前位置」'
Write-Line '    4. 回设置页点「开始监控」'
Write-Line '    5. 如果闪退了，重新打开 App 继续操作'
Write-Line
Write-Line '  复现完成后，回到这个窗口按回车继续。'
Write-Line ('=' * 60)
Read-Host | Out-Null
Write-Line

# ---------------- 4. 导出日志 ----------------
Write-Line '[3/5] 正在导出日志...'
& $Adb logcat -d -v time 2>&1 | Out-File -FilePath $FullFile -Encoding utf8
& $Adb logcat -b crash -d -v time 2>&1 | Out-File -FilePath $CrashFile -Encoding utf8

# 关键字过滤：本 App 包名、自研 TAG、高德 SDK、崩溃
$pattern = 'checkin|AMap|amap|LocationError|Locator|Geofence|Notifier|Watchdog|AndroidRuntime|FATAL'
$filtered = Get-Content -LiteralPath $FullFile -Encoding utf8 -ErrorAction SilentlyContinue |
    Where-Object { $_ -match $pattern }
$filtered | Out-File -FilePath $OutFile -Encoding utf8
Write-Line '      完成。'
Write-Line

# ---------------- 5. 摘要 ----------------
Write-Line '[4/5] 关键信息摘要'
Write-Line ('-' * 60)

Write-Line '  [已安装版本]'
$pkg = & $Adb shell dumpsys package com.example.checkin 2>&1 |
    Select-String -Pattern 'versionName|versionCode' |
    Where-Object { $_ -notmatch 'disabled|minSdk|targetSdk' } |
    Select-Object -First 2
if ($pkg) { $pkg | ForEach-Object { Write-Line "    $($_.ToString().Trim())" } }
else { Write-Line '    (未安装或读取失败)' }
Write-Line

Write-Line '  [高德 SDK 初始化与隐私状态]'
$initLog = Get-Content -LiteralPath $FullFile -Encoding utf8 -ErrorAction SilentlyContinue |
    Select-String -Pattern '高德 SDK 初始化|隐私已同意' | Select-Object -Last 5
if ($initLog) { $initLog | ForEach-Object { Write-Line "    $($_.ToString().Trim())" } }
else { Write-Line '    (没有找到初始化日志 —— 可能 App 未启动)' }
Write-Line

Write-Line '  [定位相关：成功 / 失败 / 错误码]'
$locLog = Get-Content -LiteralPath $FullFile -Encoding utf8 -ErrorAction SilentlyContinue |
    Select-String -Pattern '定位' | Select-Object -Last 15
if ($locLog) { $locLog | ForEach-Object { Write-Line "    $($_.ToString().Trim())" } }
else { Write-Line '    (没有定位相关日志 —— 说明从未发起定位)' }
Write-Line

Write-Line '  [崩溃堆栈]'
$crashSize = (Get-Item -LiteralPath $CrashFile).Length
$crashTail = Get-Content -LiteralPath $CrashFile -Encoding utf8 -ErrorAction SilentlyContinue |
    Where-Object { $_ -match 'checkin|FATAL|Exception|Cause|at ' } | Select-Object -Last 30
if ($crashTail) {
    Write-Line '    *** 检测到崩溃相关内容 ***'
    $crashTail | ForEach-Object { Write-Line "    $($_.ToString().Trim())" }
} else {
    Write-Line '    (崩溃缓冲区为空 —— 本次未捕获到闪退)'
}
Write-Line ('-' * 60)
Write-Line

# ---------------- 6. 收尾 ----------------
Write-Line '[5/5] 完成'
$outSize = [math]::Round((Get-Item -LiteralPath $OutFile).Length / 1KB, 1)
Write-Head '请把下面这个文件发给我'
Write-Line
Write-Line "    $OutFile    ($outSize KB)"
Write-Line
Write-Line '  如果上面提示「检测到崩溃相关内容」，请把 crash-log.txt 也一起发。'
Write-Line
Write-Line '  发送方式：直接把这个文件拖进聊天窗口，或用记事本打开后复制内容粘贴给我。'
Write-Line

Start-Process explorer.exe $OutDir
Read-Host '按回车键退出'
