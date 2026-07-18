@echo off
title Create AntiJail Desktop Shortcut

echo Creating AntiJail desktop shortcut...

REM Get the current directory (where AntiJail files are located)
set "CURRENT_DIR=%~dp0"

REM Set desktop path
set "DESKTOP=%USERPROFILE%\Desktop"

REM Create a VBScript to generate the shortcut
echo Set oWS = WScript.CreateObject("WScript.Shell") > "%TEMP%\CreateShortcut.vbs"
echo sLinkFile = "%DESKTOP%\AntiJail.lnk" >> "%TEMP%\CreateShortcut.vbs"
echo Set oLink = oWS.CreateShortcut(sLinkFile) >> "%TEMP%\CreateShortcut.vbs"
echo oLink.TargetPath = "%CURRENT_DIR%AntiJail.vbs" >> "%TEMP%\CreateShortcut.vbs"
echo oLink.WorkingDirectory = "%CURRENT_DIR%" >> "%TEMP%\CreateShortcut.vbs"
echo oLink.Description = "AntiJail - Multi-Emulator Antijail Macro" >> "%TEMP%\CreateShortcut.vbs"
echo oLink.IconLocation = "%CURRENT_DIR%nojail.ico" >> "%TEMP%\CreateShortcut.vbs"
echo oLink.Save >> "%TEMP%\CreateShortcut.vbs"

REM Run the VBScript to create the shortcut
cscript //nologo "%TEMP%\CreateShortcut.vbs"

REM Clean up
del "%TEMP%\CreateShortcut.vbs"

echo.
echo SUCCESS: AntiJail shortcut created on desktop with custom icon!
echo.
echo You can now double-click the "AntiJail" shortcut on your desktop
echo to launch the application from anywhere.
echo.
pause 