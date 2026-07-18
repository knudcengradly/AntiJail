# Re-run this after a fresh Windows install to restore AntiJail dependencies.
$ErrorActionPreference = "Stop"
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$PythonRoot = "$env:LOCALAPPDATA\Programs\Python\Python312"
$PythonExe = Join-Path $PythonRoot "python.exe"

if (-not (Test-Path $PythonExe)) {
    Write-Host "Python 3.12 not found. Downloading installer..."
    $installer = Join-Path $env:TEMP "python-3.12.10-amd64.exe"
    Invoke-WebRequest -Uri "https://www.python.org/ftp/python/3.12.10/python-3.12.10-amd64.exe" -OutFile $installer
    Start-Process -FilePath $installer -ArgumentList "/quiet","InstallAllUsers=0","PrependPath=1","Include_test=0","Include_pip=1","Include_launcher=1" -Wait
    Remove-Item $installer -Force -ErrorAction SilentlyContinue
}

& $PythonExe -m pip install --upgrade pip
& $PythonExe -m pip install -r (Join-Path $ProjectDir "requirements.txt")

Write-Host ""
Write-Host "Done. Launch with AntiJail.vbs or:"
Write-Host "  `"$PythonRoot\pythonw.exe`" `"$ProjectDir\AntiJail.py`""
