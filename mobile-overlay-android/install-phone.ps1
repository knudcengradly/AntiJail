param(
    [string]$Serial = "",
    [switch]$SkipBuild,
    [switch]$ListOnly,
    [switch]$Release
)

$ErrorActionPreference = "Stop"

function Write-Step($msg) {
    Write-Host "`n==> $msg" -ForegroundColor Cyan
}

function Get-AndroidDevices {
    $lines = (& adb devices) | Select-Object -Skip 1
    $devices = @()
    foreach ($line in $lines) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed)) { continue }
        if ($trimmed -notmatch "\s+device$") { continue }

        $serial = ($trimmed -split "\s+")[0]
        $devices += $serial
    }
    return $devices
}

function Resolve-TargetDevice {
    param([string[]]$Devices, [string]$RequestedSerial)

    if ($RequestedSerial) {
        if ($Devices -notcontains $RequestedSerial) {
            throw "Requested serial '$RequestedSerial' is not connected as an Android device."
        }
        return $RequestedSerial
    }

    if ($Devices.Count -eq 0) {
        throw "No Android device detected. Start LDPlayer or connect a device with USB debugging enabled."
    }

    Write-Host "Available Android devices:"
    for ($i = 0; $i -lt $Devices.Count; $i++) {
        Write-Host "[$($i + 1)] $($Devices[$i])"
    }
    $choice = Read-Host "Choose device number"
    $index = [int]$choice - 1
    if ($index -lt 0 -or $index -ge $Devices.Count) {
        throw "Invalid selection."
    }
    return $Devices[$index]
}

try {
    Write-Step "Checking adb devices"
    $androidDevices = Get-AndroidDevices

    if ($ListOnly) {
        if ($androidDevices.Count -eq 0) {
            Write-Host "No Android devices found."
        } else {
            Write-Host "Android devices:"
            $androidDevices | ForEach-Object { Write-Host "- $_" }
        }
        exit 0
    }

    $target = Resolve-TargetDevice -Devices $androidDevices -RequestedSerial $Serial
    Write-Host "Target device: $target" -ForegroundColor Green

    if (-not $SkipBuild) {
        if ($Release) {
            Write-Step "Building release APK"
            & .\gradlew assembleRelease
        } else {
            Write-Step "Building debug APK"
            & .\gradlew assembleDebug
        }
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed." }
    } else {
        Write-Step "Skipping build (using existing APK)"
    }

    $subPath = if ($Release) { "release" } else { "debug" }
    $apkPath = Join-Path $PSScriptRoot "app\build\outputs\apk\$subPath\AntiJail.apk"
    if (-not (Test-Path $apkPath)) {
        throw "APK not found at: $apkPath"
    }

    Write-Step "Installing APK"
    & adb -s $target install -r "$apkPath"
    if ($LASTEXITCODE -ne 0) { throw "adb install failed." }

    Write-Step "Done"
    Write-Host "Installed to $target successfully." -ForegroundColor Green
}
catch {
    Write-Host "`nERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
