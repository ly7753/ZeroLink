# ZeroLink Log Capture Script
# Enhanced version with proper tag filtering

param(
    [int]$Wait = 30
)

$ErrorActionPreference = "Stop"

# Device Serials
$CONTROLLER_SERIAL = "i7fifqzl89ojbirw"
$CONTROLLED_SERIAL = "d228d7ff"

# Package Names
$CONTROLLER_PKG = "com.zero.link.controller"
$CONTROLLED_PKG = "com.zero.link.controlled"

# Log tags to capture
$TAGS = "AndroidRuntime|FATAL EXCEPTION|$CONTROLLER_PKG|$CONTROLLED_PKG|ZeroLink|WebRTC|SocketGateway|Decoder|ScreenStream|Shizuku|MediaCodec|app_process|LanDiscovery|ControllerStore|ServerMain|ShellEnvironment|ControlledStore|isShizuku|ensurePermission|bootstrap|checkSelfPermission"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$logDir = Join-Path $projectRoot "logs"
if (-not (Test-Path $logDir)) {
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
}

# Clean old logs (keep only last 5)
Write-Host "[*] Cleaning old logs..." -ForegroundColor Yellow
$existingLogs = Get-ChildItem -Path $logDir -Filter "*.log" | Sort-Object LastWriteTime -Descending
if ($existingLogs.Count -gt 5) {
    $logsToDelete = $existingLogs | Select-Object -Skip 5
    foreach ($log in $logsToDelete) {
        Remove-Item $log.FullName -Force
        Write-Host "  [DEL] $($log.Name)" -ForegroundColor Gray
    }
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$mergedLog = Join-Path $logDir "merged_$timestamp.log"

function Clear-RootProcess {
    param($serial)
    # Kill old processes and clear WebRTC ports (50990 TCP, 50991 UDP)
    $null = adb -s $serial shell su -c "pkill -f 'zero.link' 2>/dev/null; pkill -f 'WebRTCServer' 2>/dev/null; fuser -k 50990/tcp 2>/dev/null; fuser -k 50991/tcp 2>/dev/null; fuser -k 50991/udp 2>/dev/null" 2>$null
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "ZeroLink Log Capture" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Clear logcat buffer first
Write-Host "[1] Clearing log buffers..." -ForegroundColor Yellow
adb -s $CONTROLLER_SERIAL logcat -c > $null
adb -s $CONTROLLED_SERIAL logcat -c > $null

# Launch apps
Write-Host "[2] Starting apps..." -ForegroundColor Yellow
Clear-RootProcess $CONTROLLER_SERIAL
adb -s $CONTROLLER_SERIAL shell am start -W -n "$CONTROLLER_PKG/.MainActivity"
Clear-RootProcess $CONTROLLED_SERIAL
adb -s $CONTROLLED_SERIAL shell am start -W -n "$CONTROLLED_PKG/.MainActivity"

Write-Host "[3] Capturing for ${Wait}s... (do your test now)" -ForegroundColor Cyan
Write-Host "[*] Waiting..." -ForegroundColor Cyan

# Wait for specified time
$waitSeconds = $Wait
while ($waitSeconds -gt 0) {
    Write-Host "`r[*] Remaining: $waitSeconds seconds..." -NoNewline -ForegroundColor Cyan
    Start-Sleep -Seconds 1
    $waitSeconds--
}
Write-Host ""

# Capture with proper filters
Write-Host "[4] Extracting logs with filters..." -ForegroundColor Yellow

# Get controller log with filters
$controllerRaw = Join-Path $logDir "raw_controller_$timestamp.log"
$controlledRaw = Join-Path $logDir "raw_controlled_$timestamp.log"
adb -s $CONTROLLER_SERIAL logcat -d -v threadtime -b crash,main,system > $controllerRaw 2>$null
adb -s $CONTROLLED_SERIAL logcat -d -v threadtime -b crash,main,system > $controlledRaw 2>$null

# Filter and merge
$pattern = $TAGS -replace '\$CONTROLLER_PKG', $CONTROLLER_PKG -replace '\$CONTROLLED_PKG', $CONTROLLED_PKG

Get-Content $controllerRaw, $controlledRaw | Select-String -Pattern $pattern | Set-Content $mergedLog -Encoding UTF8

# Clean temp files
Remove-Item $controllerRaw, $controlledRaw -Force -ErrorAction SilentlyContinue

# Stop apps
Write-Host "[5] Stopping apps..." -ForegroundColor Yellow
adb -s $CONTROLLER_SERIAL shell am force-stop $CONTROLLER_PKG
adb -s $CONTROLLED_SERIAL shell am force-stop $CONTROLLED_PKG

# Pull server.log
$serverLog = Join-Path $logDir "server_$timestamp.log"
$pullOutput = adb -s $CONTROLLED_SERIAL pull /data/local/tmp/server.log $serverLog 2>&1

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "[OK] Log capture complete!" -ForegroundColor Green
Write-Host "[FILE] Merged: $mergedLog" -ForegroundColor Green
if (Test-Path $serverLog) {
    Write-Host "[FILE] Server: $serverLog" -ForegroundColor Green
}

Write-Host ""
Write-Host "--- Latest 30 lines ---" -ForegroundColor Yellow
Get-Content $mergedLog | Select-Object -Last 30
Write-Host "--- End ---" -ForegroundColor Yellow