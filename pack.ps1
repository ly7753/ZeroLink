# ZeroLink 项目源码备份打包脚本
# 用法: .\pack.ps1 [-Output <输出路径>]

param(
    [string]$Output = ".\ZeroLink_Backup_$(Get-Date -Format 'yyyyMMdd_HHmmss').zip"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

# 需要排除的目录和文件
$excludeDirs = @(
    'build',
    '.gradle',
    '.idea',
    '.codebuddy'
)

$excludeFiles = @(
    '*.apk',
    '*.dex',
    '*.iml',
    '.DS_Store',
    'local.properties',
    'out.log'
)

Write-Host "[*] 开始打包 ZeroLink 项目..." -ForegroundColor Cyan

# 获取所有要包含的文件
$files = Get-ChildItem -Recurse -File | Where-Object {
    $file = $_
    $relativePath = $file.FullName.Substring($projectRoot.Length + 1)

    # 排除目录
    foreach ($dir in $excludeDirs) {
        if ($relativePath -like "$dir\*" -or $relativePath -like "*\$dir\*") {
            return $false
        }
    }

    # 排除文件
    foreach ($pattern in $excludeFiles) {
        if ($file.Name -like $pattern) {
            return $false
        }
    }

    return $true
}

# 创建临时目录结构
$tempDir = Join-Path $env:TEMP "ZeroLink-pack-$(Get-Random)"
$zipSource = Join-Path $tempDir "ZeroLink"

try {
    Write-Host "正在收集文件..." -ForegroundColor Yellow
    foreach ($file in $files) {
        $relativePath = $file.FullName.Substring($projectRoot.Length + 1)
        $destPath = Join-Path $zipSource $relativePath
        $destDir = Split-Path -Parent $destPath
        if (!(Test-Path $destDir)) { New-Item -ItemType Directory -Path $destDir -Force | Out-Null }
        Copy-Item $file.FullName -Destination $destPath -Force
    }

    Write-Host "正在压缩..." -ForegroundColor Yellow
    if (Test-Path $Output) { Remove-Item $Output -Force }

    Compress-Archive -Path $zipSource -DestinationPath $Output -CompressionLevel Optimal

    $zipSize = (Get-Item $Output).Length
    $zipSizeMB = [math]::Round($zipSize / 1MB, 2)
    
    Write-Host "----------------------------------------"
    Write-Host "[✅] 打包完成！" -ForegroundColor Green
    Write-Host "[📦] 压缩包位置: $(Get-Location)\$Output"
    Write-Host "[📏] 文件大小: $zipSizeMB MB"
} finally {
    if (Test-Path $tempDir) { Remove-Item $tempDir -Recurse -Force }
}
