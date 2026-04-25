# Shizuku 激活脚本
# 支持用户选择设备并激活Shizuku服务

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "       Shizuku 激活工具" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 检查adb是否可用
Write-Host "[*] 检查 adb 连接..." -ForegroundColor Yellow
$adbDevices = adb devices
if ($LASTEXITCODE -ne 0) {
    Write-Host "[!] adb 不可用，请确保已安装adb并配置环境变量" -ForegroundColor Red
    exit 1
}

# 获取设备列表
Write-Host "[*] 获取已连接设备..." -ForegroundColor Yellow
$devices = @()
$deviceLines = $adbDevices -split "`n" | Where-Object { $_ -match "^\w+\s+(device|unauthorized|offline)" }

foreach ($line in $deviceLines) {
    $parts = $line -split "\s+"
    if ($parts.Count -ge 2) {
        $devices += @{
            Serial = $parts[0]
            Status = $parts[1]
        }
    }
}

if ($devices.Count -eq 0) {
    Write-Host "[!] 未找到任何已连接的设备" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "已发现 $($devices.Count) 个设备：" -ForegroundColor Green
for ($i = 0; $i -lt $devices.Count; $i++) {
    $color = if ($devices[$i].Status -eq "device") { "Green" } else { "Yellow" }
    Write-Host "  [$($i + 1). $($devices[$i].Serial) - $($devices[$i].Status)" -ForegroundColor $color
}

Write-Host ""
$selection = Read-Host "请选择要激活Shizuku的设备 (输入数字 1-$($devices.Count))"

# 验证选择
try {
    $index = [int]$selection - 1
    if ($index -lt 0 -or $index -ge $devices.Count) {
        Write-Host "[!] 无效的选择" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "[!] 请输入有效的数字" -ForegroundColor Red
    exit 1
}

$selectedDevice = $devices[$index]
Write-Host ""
Write-Host "[*] 已选择设备：$($selectedDevice.Serial) ($($selectedDevice.Status))" -ForegroundColor Cyan

# 检查设备状态
if ($selectedDevice.Status -ne "device") {
    Write-Host "[!] 设备状态异常，请确保设备已授权USB调试" -ForegroundColor Red
    exit 1
}

# 检查Shizuku应用是否安装
Write-Host "[*] 检查Shizuku应用..." -ForegroundColor Yellow
$shizukuInstalled = adb -s $selectedDevice.Serial shell pm list packages | Select-String -Pattern "moe.shizuku.privileged.api"
if (-not $shizukuInstalled) {
    Write-Host "[!] Shizuku应用未安装，请先安装Shizuku应用" -ForegroundColor Red
    exit 1
}
Write-Host "[✓] Shizuku应用已安装" -ForegroundColor Green

# 检查启动脚本路径
Write-Host "[*] 查找Shizuku启动脚本..." -ForegroundColor Yellow
$scriptPaths = @(
    "/storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh",
    "/sdcard/Android/data/moe.shizuku.privileged.api/files/start.sh",
    "/sdcard/Android/data/moe.shizuku.privileged.api/start.sh"
)

$foundScript = $null
foreach ($path in $scriptPaths) {
    $testResult = adb -s $selectedDevice.Serial shell "ls '$path' 2>/dev/null"
    if ($LASTEXITCODE -eq 0 -and $testResult -like "*start.sh*") {
        $foundScript = $path
        break
    }
}

if (-not $foundScript) {
    Write-Host "[!] 未找到Shizuku启动脚本，请先在Shizuku应用中选择'通过adb启动'选项" -ForegroundColor Red
    Write-Host "[提示：" -ForegroundColor Yellow
    Write-Host "  1. 打开Shizuku应用" -ForegroundColor Yellow
    Write-Host "  2. 选择'通过adb启动'" -ForegroundColor Yellow
    Write-Host "  3. 点击'启动'按钮" -ForegroundColor Yellow
    Write-Host "  4. 再次运行此脚本" -ForegroundColor Yellow
    exit 1
}

Write-Host "[✓] 找到启动脚本：$foundScript" -ForegroundColor Green

# 执行激活
Write-Host ""
Write-Host "[*] 正在激活Shizuku..." -ForegroundColor Cyan
Write-Host "[*] 执行命令：adb -s $($selectedDevice.Serial) shell sh '$foundScript'" -ForegroundColor Gray

$activationResult = adb -s $selectedDevice.Serial shell sh "'$foundScript'"
Write-Host $activationResult

# 等待几秒让服务启动
Write-Host ""
Write-Host "[*] 等待Shizuku服务启动..." -ForegroundColor Yellow
Start-Sleep -Seconds 3

# 验证激活
Write-Host "[*] 验证Shizuku服务..." -ForegroundColor Yellow
$shizukuProcess = adb -s $selectedDevice.Serial shell ps -A | Select-String -Pattern "shizuku"
if ($shizukuProcess) {
    Write-Host "[✓] Shizuku服务已成功激活！" -ForegroundColor Green
    Write-Host ""
    Write-Host "进程信息：" -ForegroundColor Cyan
    Write-Host $shizukuProcess
} else {
    Write-Host "[!] 可能激活失败，请检查Shizuku应用" -ForegroundColor Yellow
    Write-Host "[提示] 请尝试在Shizuku应用中手动启动" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "操作完成" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan