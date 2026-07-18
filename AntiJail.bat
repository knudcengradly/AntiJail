@echo off
cd /d "%~dp0"
if not exist "AntiJail.py" (
    echo Error: AntiJail.py not found in current directory
    echo Current directory: %CD%
    echo Please make sure this batch file is in the same folder as AntiJail.py
    pause
    exit /b 1
)
pythonw AntiJail.py 