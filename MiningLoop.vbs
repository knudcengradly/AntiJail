Set objShell = CreateObject("WScript.Shell")
Set objFSO = CreateObject("Scripting.FileSystemObject")

' Get the directory where this script is located
strScriptPath = objFSO.GetParentFolderName(WScript.ScriptFullName)

' Change to the AntiJail directory
objShell.CurrentDirectory = strScriptPath

' Prefer full path to pythonw (works even if PATH not refreshed after install)
strPythonw = objShell.ExpandEnvironmentStrings("%LOCALAPPDATA%") & "\Programs\Python\Python312\pythonw.exe"
If Not objFSO.FileExists(strPythonw) Then
    strPythonw = "pythonw"
End If

' Check if mining_key_loop.py exists
If objFSO.FileExists(strScriptPath & "\mining_key_loop.py") Then
    ' Run mining_key_loop.py silently (0 = hidden window)
    objShell.Run Chr(34) & strPythonw & Chr(34) & " " & Chr(34) & strScriptPath & "\mining_key_loop.py" & Chr(34), 0, False
Else
    ' Show error if file not found
    MsgBox "Error: mining_key_loop.py not found in " & strScriptPath, vbCritical, "Mining Loop Launcher"
End If
