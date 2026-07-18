# Copy snapshot PNGs into AntiJail/images for template detection.
# Sources (in order):
#   1. LDPlayer shared folder on PC: XuanZhi9\Pictures  (emulator /mnt/shared/Pictures)
#   2. adb pull from emulator app storage (fallback when shared-folder write fails)
$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$localImages = Join-Path $projectRoot "images"
$ldplayerShared = "C:\Users\Gradly De Villa\Documents\XuanZhi9\Pictures"

New-Item -ItemType Directory -Force -Path $localImages | Out-Null

$copiedFromPc = 0
$copiedFromAdb = 0

if (Test-Path $ldplayerShared) {
    Get-ChildItem -Path $ldplayerShared -Filter "snapshot_*.png" -File | ForEach-Object {
        Copy-Item -Path $_.FullName -Destination (Join-Path $localImages $_.Name) -Force
        Write-Host "Copied from PC shared folder: $($_.Name)"
        $copiedFromPc++
    }
} else {
    Write-Host "LDPlayer shared folder not found: $ldplayerShared"
}

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (Test-Path $adb) {
    $remotePaths = @(
        "/storage/emulated/0/Android/data/com.antijail.overlay/files/images/",
        "/sdcard/AntiJail/images/",
        "/storage/emulated/0/AntiJail/images/"
    )
    $pullRoot = Join-Path $env:TEMP "antijail-snapshot-pull"
    Remove-Item -Path $pullRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $pullRoot | Out-Null

    foreach ($remote in $remotePaths) {
        Write-Host "Pulling $remote ..."
        try {
            & $adb pull $remote $pullRoot 2>$null
            if ($LASTEXITCODE -ne 0) {
                Write-Host "  (skipped - path not on device)"
            }
        } catch {
            Write-Host "  (skipped - adb pull failed)"
        }
    }

    Get-ChildItem -Path $pullRoot -Recurse -Filter "snapshot_*.png" -File | ForEach-Object {
        Copy-Item -Path $_.FullName -Destination (Join-Path $localImages $_.Name) -Force
        Write-Host "Copied from emulator: $($_.Name)"
        $copiedFromAdb++
    }
    Remove-Item -Path $pullRoot -Recurse -Force -ErrorAction SilentlyContinue
} else {
    Write-Host "adb not found - skipping emulator pull"
}

Write-Host ""
Write-Host "Done. $copiedFromPc from LDPlayer Pictures, $copiedFromAdb from emulator via adb."
Write-Host "Templates folder: $localImages"
