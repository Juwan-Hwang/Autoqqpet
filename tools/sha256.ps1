# 生成发布用 SHA-256 校验和（Release 说明里必须附上，防二次打包冒充）
# 用法：  pwsh -File tools/sha256.ps1 [-Apk <路径>]
param(
    [string]$Apk = "app/build/outputs/apk/debug/app-debug.apk"
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if (-not (Test-Path $Apk)) {
    Write-Host "找不到 APK：$Apk （先执行 assembleDebug 或传入 -Apk 指向 release 包）"
    exit 1
}

$item = Get-Item $Apk
$hash = (Get-FileHash -Algorithm SHA256 -Path $item.FullName).Hash.ToLower()
$mb = [math]::Round($item.Length / 1MB, 2)

Write-Host ""
Write-Host "文件      : $($item.Name)"
Write-Host "大小      : $mb MB"
Write-Host "SHA-256   : $hash"
Write-Host ""
Write-Host "可直接粘到 Release 说明："
Write-Host ""
Write-Host "``````"
Write-Host "SHA-256: $hash"
Write-Host "大小: $mb MB"
Write-Host "校验: certutil -hashfile $($item.Name) SHA256   (Windows)"
Write-Host "        shasum -a 256 $($item.Name)             (macOS/Linux)"
Write-Host "``````"
Write-Host ""
