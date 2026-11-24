@echo off
rem Removes the installation made by install.cmd from this package (same options, e.g. --app-dir DIR).
rem Your projects and exports are never touched; a PATH entry is removed only if install.cmd added it.
call "%~dp0install.cmd" --uninstall %*
exit /b %ERRORLEVEL%
