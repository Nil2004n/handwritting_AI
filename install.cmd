@echo off
rem notebook-ai installer entry point for Windows (per-user, no Administrator rights needed).
rem Usage: install.cmd [--help] [--add-to-path] [--no-path] [--app-dir DIR] [--uninstall] [--status] ...
rem The installed launcher (notebook-ai.cmd) adds the JVM flags that Java 23+/24+ need to start without warnings (see packaging\launcher-body.cmd).
rem Windows has no old-layout installation to upgrade and no sudo: this install is always per-user and never needs Administrator rights.
rem Make is NOT required. This file only locates tools\install-windows.ps1 next to itself and runs it;
rem it preserves the helper's exit code. Keep this file ASCII.
setlocal DisableDelayedExpansion
set "NBAI_HERE=%~dp0"
set "NBAI_PS1=%NBAI_HERE%scripts\install-windows.ps1"
if not exist "%NBAI_PS1%" set "NBAI_PS1=%NBAI_HERE%tools\install-windows.ps1"
if not exist "%NBAI_PS1%" goto :nohelper
set "NBAI_PSEXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if exist "%NBAI_PSEXE%" goto :run
set "NBAI_PSEXE="
for %%P in (pwsh.exe) do set "NBAI_PSEXE=%%~$PATH:P"
if not defined NBAI_PSEXE goto :nops
:run
rem -ExecutionPolicy Bypass applies to THIS process only; the machine/user execution policy is not changed.
"%NBAI_PSEXE%" -NoProfile -ExecutionPolicy Bypass -File "%NBAI_PS1%" %*
set "NBAI_RC=%ERRORLEVEL%"
endlocal & exit /b %NBAI_RC%
:nohelper
echo install.cmd: cannot find scripts\install-windows.ps1 (or tools\install-windows.ps1) next to install.cmd 1>&2
endlocal & exit /b 1
:nops
echo install.cmd: PowerShell was not found (expected Windows PowerShell 5.1 or pwsh). 1>&2
endlocal & exit /b 1
