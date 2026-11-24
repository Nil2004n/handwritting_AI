rem notebook-ai launcher body (cmd.exe). SINGLE SOURCE for the installed notebook-ai.cmd and run-notebook-ai.cmd.
rem The including script sets, before this text: NBAI_START (start script), NBAI_MODELS (default models dir),
rem NBAI_HINT (what to tell the user when files are missing), and has already run "setlocal DisableDelayedExpansion".
if not exist "%NBAI_START%" goto :missing
if defined JAVA_HOME goto :homejava
set "NBAI_JAVA="
for %%J in (java.exe) do set "NBAI_JAVA=%%~$PATH:J"
if not defined NBAI_JAVA goto :nojava
goto :checkver
:homejava
set "NBAI_JAVA=%JAVA_HOME:"=%\bin\java.exe"
if not exist "%NBAI_JAVA%" goto :badhome
:checkver
set "NBAI_JVF=%TEMP%\notebook-ai-jv-%RANDOM%%RANDOM%.txt"
"%NBAI_JAVA%" -version >"%NBAI_JVF%" 2>&1
set "NBAI_JV="
for /f "tokens=3" %%V in ('findstr /i /c:" version " "%NBAI_JVF%"') do set "NBAI_JV=%%~V"
del "%NBAI_JVF%" >nul 2>&1
set "NBAI_MAJ="
if defined NBAI_JV for /f "delims=.-_+ tokens=1" %%M in ("%NBAI_JV%") do set "NBAI_MAJ=%%M"
if not defined NBAI_MAJ goto :launch
set /a NBAI_N=%NBAI_MAJ% 2>nul
if %NBAI_N% GTR 0 if %NBAI_N% LSS 21 goto :oldjava
:launch
rem Newer JDKs warn on every start about things this application legitimately does: JDK 23+ about sun.misc.Unsafe (JavaFX) and JDK 24+ about restricted native
rem access (JavaFX and ONNX Runtime load native libraries). These flags state that this is intended. They are added ONLY for JDKs that know them (a JDK 21 rejects
rem --sun-misc-unsafe-memory-access); JAVA_OPTS set by the user is kept.
set "NBAI_EXTRA="
if defined NBAI_N if %NBAI_N% GEQ 23 set "NBAI_EXTRA=--sun-misc-unsafe-memory-access=allow"
if defined NBAI_N if %NBAI_N% GEQ 24 set "NBAI_EXTRA=%NBAI_EXTRA% --enable-native-access=javafx.graphics,ALL-UNNAMED"
if defined NBAI_EXTRA set "JAVA_OPTS=%JAVA_OPTS% %NBAI_EXTRA%"
if not defined NOTEBOOK_AI_MODELS set "NOTEBOOK_AI_MODELS=%NBAI_MODELS%"
if not exist "%NOTEBOOK_AI_MODELS%\det.onnx" goto :nomodels
call "%NBAI_START%" %*
set "NBAI_RC=%ERRORLEVEL%"
endlocal & exit /b %NBAI_RC%
:missing
echo notebook-ai: the application files are missing or damaged ("%NBAI_START%"); %NBAI_HINT% 1>&2
endlocal & exit /b 1
:nojava
echo notebook-ai: Java 21 or newer was not found. Install a Java 21+ runtime or set JAVA_HOME. 1>&2
endlocal & exit /b 1
:badhome
echo notebook-ai: JAVA_HOME is set but "%NBAI_JAVA%" does not exist. 1>&2
endlocal & exit /b 1
:oldjava
echo notebook-ai: Java %NBAI_N% was found at "%NBAI_JAVA%"; Java 21 or newer is required (set JAVA_HOME to a newer JDK or JRE). 1>&2
endlocal & exit /b 1
:nomodels
echo notebook-ai: OCR model files were not found in "%NOTEBOOK_AI_MODELS%"; %NBAI_HINT% 1>&2
endlocal & exit /b 1
