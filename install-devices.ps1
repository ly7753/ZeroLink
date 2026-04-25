# ZeroLink Build and Install Script
# Controller APK -> i7fifqzl89ojbirw
# Controlled APK -> d228d7ff

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

# Device Serials
$CONTROLLER_SERIAL = "i7fifqzl89ojbirw"
$CONTROLLED_SERIAL = "d228d7ff"

Write-Host "[*] Starting ZeroLink build..." -ForegroundColor Cyan
Write-Host "----------------------------------------"

# Build both APKs
$gradleResult = & .\gradlew.bat :app-controller:assembleRelease :app-controlled:assembleRelease --parallel --no-daemon

if ($LASTEXITCODE -ne 0) {
    Write-Host "[X] Build failed!" -ForegroundColor Red
    Write-Host $gradleResult
    exit 1
}

Write-Host "[OK] Build successful!" -ForegroundColor Green
Write-Host "----------------------------------------"

# Find APK files
$controllerApk = Get-ChildItem -Path "app-controller\build\outputs\apk\debug\*.apk" | Select-Object -First 1
$controlledApk = Get-ChildItem -Path "app-controlled\build\outputs\apk\debug\*.apk" | Select-Object -First 1

if (-not $controllerApk) {
    Write-Host "[X] Controller APK not found" -ForegroundColor Red
    exit 1
}

if (-not $controlledApk) {
    Write-Host "[X] Controlled APK not found" -ForegroundColor Red
    exit 1
}

Write-Host "[PACK] Build outputs:" -ForegroundColor Yellow
Write-Host "  Controller: $($controllerApk.Name)"
Write-Host "  Controlled: $($controlledApk.Name)"
Write-Host "----------------------------------------"

# Install Controller to i7fifqzl89ojbirw
# Use -d for downgrade, -r for replace, -t for allow test apk
Write-Host "[*] Installing controller to $CONTROLLER_SERIAL ..." -ForegroundColor Cyan
$installController = adb -s $CONTROLLER_SERIAL install -r -d -t "$($controllerApk.FullName)"

if ($LASTEXITCODE -eq 0) {
    Write-Host "[OK] Controller installed!" -ForegroundColor Green
} else {
    Write-Host "[X] Controller install failed: $installController" -ForegroundColor Red
}

# Install Controlled to d228d7ff
Write-Host "[*] Installing controlled to $CONTROLLED_SERIAL ..." -ForegroundColor Cyan
$installControlled = adb -s $CONTROLLED_SERIAL install -r -d -t "$($controlledApk.FullName)"

if ($LASTEXITCODE -eq 0) {
    Write-Host "[OK] Controlled installed!" -ForegroundColor Green
} else {
    Write-Host "[X] Controlled install failed: $installControlled" -ForegroundColor Red
}

Write-Host "----------------------------------------"
Write-Host "[*] Installation complete!" -ForegroundColor Cyan