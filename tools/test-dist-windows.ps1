# End-to-end test of the WINDOWS release archive (release-output/notebook-ai-<ver>-windows-x64.zip).
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\test-dist-windows.ps1 [path\to\archive.zip]     (make test-dist on Windows)
#   pwsh -NoProfile -File tools/test-dist-windows.ps1                                                          (Linux: partial, see below)
#
# Isolation: private temporary directory, file-backed stand-in for the user PATH (NBAI_TEST_PATH_FILE; the registry is never
# touched), fake LOCALAPPDATA. No network, no elevation.
#
# HONESTY NOTE: when run on Linux this verifies the archive CONTENT and the packaged-mode PowerShell installer logic
# (integrity check, platform check, staged install, upgrade, uninstall, tamper refusal). It does NOT execute cmd.exe, the
# generated run-notebook-ai.cmd / notebook-ai.cmd, the registry PATH write, or the Windows application. Those checks are in the
# [WINDOWS-ONLY] section and are SKIPPED elsewhere; the repository's CI workflow runs them on a real Windows runner (not yet run).

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$IsWin = ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT)
$PSExe = (Get-Process -Id $PID).Path
$Zip = $args | Select-Object -First 1
if (-not $Zip) { $Zip = (Get-ChildItem (Join-Path $Root 'release-output') -Filter 'notebook-ai-*-windows-x64.zip' -ErrorAction SilentlyContinue | Select-Object -First 1).FullName }
if (-not $Zip -or -not (Test-Path -LiteralPath $Zip)) { Write-Host 'archive not found; run: make dist-windows package-windows (or ./gradlew packageWindows)'; exit 2 }
$Top = [IO.Path]::GetFileNameWithoutExtension($Zip)
$T = Join-Path ([IO.Path]::GetTempPath()) ('nbai-wdtest-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $T | Out-Null
$script:Pass = 0; $script:Fail = 0; $script:Skip = 0
function Ok([string]$n) { $script:Pass++; Write-Host ('PASS  ' + $n) }
function Bad([string]$n, [string]$d = '') { $script:Fail++; Write-Host ('FAIL  ' + $n); if ($d) { Write-Host ('      ' + $d) } }
function Skip([string]$n) { $script:Skip++; Write-Host ('SKIP  ' + $n) }
function T([string]$n, [bool]$c, [string]$d = '') { if ($c) { Ok $n } else { Bad $n $d } }
function Sha([string]$p) { return (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant() }
function Extract([string]$dest) { if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Recurse -Force }; New-Item -ItemType Directory -Path $dest | Out-Null; Expand-Archive -LiteralPath $Zip -DestinationPath $dest -Force; return (Join-Path $dest $Top) }

Write-Host '== 1. archive'
$sc = Join-Path (Split-Path -Parent $Zip) ((Split-Path -Leaf $Zip) + '.sha256')
T 'archive matches its .sha256 sidecar' ((Get-Content -LiteralPath $sc -Raw).Split(' ')[0].Trim() -eq (Sha $Zip))
Add-Type -AssemblyName System.IO.Compression.FileSystem
$za = [IO.Compression.ZipFile]::OpenRead($Zip)
try {
    $names = @($za.Entries | ForEach-Object { $_.FullName })
    T 'single top-level directory' (@($names | ForEach-Object { $_.Split('/')[0] } | Sort-Object -Unique).Count -eq 1 -and $names[0].StartsWith($Top + '/'))
    T 'no unsafe entry names' (@($names | Where-Object { $_.StartsWith('/') -or $_.Contains('..') -or $_.Contains('\') }).Count -eq 0)
    T 'nothing forbidden inside (.git, src, tests, build, private data)' (@($names | Where-Object { $_ -match '/(\.git|src|tests?|build|cand|eval-local|build-old-rootowned)/' }).Count -eq 0)
    T 'no Linux payload in the Windows archive' (@($names | Where-Object { $_ -match '(-linux\.jar$|\.so$|install\.sh$)' }).Count -eq 0)
} finally { $za.Dispose() }

Write-Host '== 2. extract into a path with spaces; static payload checks'
$X = Extract (Join-Path $T 'ex tract ed')
T 'extracted' (Test-Path -LiteralPath $X)
foreach ($f in 'install.cmd', 'uninstall.cmd', 'run-notebook-ai.cmd', 'README.txt', 'LICENSE', 'THIRD-PARTY-NOTICES.txt', 'distribution.json', 'SHA256SUMS', 'app\bin\notebook-ai.bat', 'app\PLATFORM', 'app\models\det.onnx', 'app\models\rec.onnx', 'scripts\install-windows.ps1', 'scripts\launcher-body.cmd', 'licenses\Apache-2.0.txt', 'licenses\openjfx\LICENSE') {
    if (-not (Test-Path -LiteralPath (Join-Path $X $f))) { Bad ('missing ' + $f) }
}
Ok 'required files present (see FAIL lines above if any)'
$bad = 0; $listed = 0
foreach ($l in [IO.File]::ReadAllLines((Join-Path $X 'SHA256SUMS'))) { if ($l -match '^([0-9a-f]{64})  (.+)$') { $listed++; $p = Join-Path $X ($Matches[2].Replace('/', [IO.Path]::DirectorySeparatorChar)); if (-not (Test-Path -LiteralPath $p) -or (Sha $p) -ne $Matches[1]) { $bad++ } } }
$nfiles = @(Get-ChildItem -LiteralPath $X -Recurse -File | Where-Object { $_.Name -ne 'SHA256SUMS' }).Count
T 'SHA256SUMS validates every shipped file and lists exactly the shipped files' ($bad -eq 0 -and $listed -eq $nfiles) "bad=$bad listed=$listed files=$nfiles"
$m = Get-Content -LiteralPath (Join-Path $X 'distribution.json') -Raw | ConvertFrom-Json
T 'distribution.json: target windows/x64, Java 21, runtime not bundled, version' ($m.targetPlatform -eq 'win-x64' -and $m.target.os -eq 'windows' -and $m.target.arch -eq 'x64' -and $m.requiredJava -eq 21 -and $m.javaRuntimeBundled -eq $false -and $m.application.version -eq '0.1.0')
T 'distribution.json: source commit recorded with an honest dirty flag; licensing gaps declared' (($m.source.commit.Length -ge 7) -and ($m.source.dirty -is [bool]) -and (@($m.licensingGaps).Count -ge 1))
$libs = @(Get-ChildItem -LiteralPath (Join-Path $X 'app\lib') -File | ForEach-Object { $_.Name })
T 'distribution.json libraries equal app\lib, with matching hashes' ((@($m.libraries | ForEach-Object { $_.file } | Sort-Object) -join ',') -eq (($libs | Sort-Object) -join ',') -and @($m.libraries | Where-Object { (Sha (Join-Path $X ('app\lib\' + $_.file))) -ne $_.sha256 }).Count -eq 0)
T 'only Windows JavaFX jars (base, graphics, controls); no Linux jars' (@($libs | Where-Object { $_ -like 'javafx-*' -and $_ -notlike '*-win.jar' }).Count -eq 0 -and @($libs | Where-Object { $_ -like 'javafx-*-win.jar' }).Count -eq 3)
$zo = [IO.Compression.ZipFile]::OpenRead((Join-Path $X 'app\lib\onnxruntime-1.22.0.jar'))
try { $nat = @($zo.Entries | ForEach-Object { $_.FullName } | Where-Object { $_ -match '^ai/onnxruntime/native/([^/]+)/' } | ForEach-Object { ($_ -split '/')[3] } | Sort-Object -Unique) } finally { $zo.Dispose() }
T 'ONNX Runtime jar contains only the win-x64 natives' (($nat -join ',') -eq 'win-x64') ($nat -join ',')
$modelsOk = $true; foreach ($mf in $m.models.files) { if ((Sha (Join-Path $X ('app\models\' + $mf.name))) -ne $mf.sha256) { $modelsOk = $false } }
T 'bundled models match distribution.json and models\checksums.sha256 is shipped' ($modelsOk -and $m.models.bundled -eq $true -and (Test-Path (Join-Path $X 'app\models\checksums.sha256')))
$crlfOk = $true; foreach ($f in 'install.cmd', 'uninstall.cmd', 'run-notebook-ai.cmd', 'app\bin\notebook-ai.bat', 'scripts\launcher-body.cmd') { $raw = [IO.File]::ReadAllText((Join-Path $X $f)); if (($raw -replace "`r`n", '') -match "[`r`n]" -or -not $raw.Contains("`r`n")) { $crlfOk = $false } }
T 'batch files use CRLF line endings only' $crlfOk
$start = [IO.File]::ReadAllText((Join-Path $X 'app\bin\notebook-ai.bat'))
T 'start script: JavaFX win jars on --module-path, none on the classpath' ($start -match 'MODULE_PATH=.*javafx-graphics-21\.0\.7-win\.jar' -and $start.Contains('--add-modules javafx.controls') -and $start -notmatch 'CLASSPATH=[^\r\n]*javafx-')
$devPaths = @($Root, $env:HOME, $env:USERPROFILE) | Where-Object { $_ -and $_.Length -gt 4 }
$leak = @(); foreach ($f in Get-ChildItem -LiteralPath $X -Recurse -File | Where-Object { $_.Extension -in '.cmd', '.bat', '.ps1', '.json', '.txt' -and $_.FullName -notmatch '[\\/]licenses[\\/]' -and $_.Name -ne 'install-windows.ps1' }) { $txt = [IO.File]::ReadAllText($f.FullName); foreach ($d in $devPaths) { if ($txt.Contains($d)) { $leak += $f.Name } } }
T 'no absolute developer-machine paths embedded in scripts, config or docs' ($leak.Count -eq 0) ($leak -join ', ')
T 'README states the Java prerequisite and that Windows is not yet execution-tested' ((Get-Content -LiteralPath (Join-Path $X 'README.txt') -Raw) -match 'Java 21' -and (Get-Content -LiteralPath (Join-Path $X 'README.txt') -Raw) -match 'NOT yet been\s+\r?\n?\*\*\* executed|NOT yet been')

Write-Host '== 3. packaged-mode installer logic (helper run as a child process in test mode; NOT cmd.exe)'
$Home1 = Join-Path $T 'home'; New-Item -ItemType Directory -Path $Home1 | Out-Null
$PathFile = Join-Path $T 'userpath.txt'; [IO.File]::WriteAllText($PathFile, 'C:\Tools;%USERPROFILE%\bin')
$Jdk = Join-Path $T 'jdk21'
if (-not $IsWin) { New-Item -ItemType Directory -Path (Join-Path $Jdk 'bin') -Force | Out-Null; [IO.File]::WriteAllText((Join-Path $Jdk 'bin/java'), "#!/bin/sh`necho 'openjdk version `"21.0.1`" 2026-01-01' >&2`n"); & chmod 755 (Join-Path $Jdk 'bin/java') }
function Inst([string]$pkg, [string[]]$a, [hashtable]$extra = @{}) {
    $vars = @{ NBAI_TEST_MODE = '1'; NBAI_TEST_PLATFORM = 'win-x64'; NBAI_TEST_PATH_FILE = $PathFile; NBAI_TEST_ELEVATED = '0'
               LOCALAPPDATA = (Join-Path $Home1 'AppData\Local'); USERPROFILE = $Home1; SystemRoot = 'C:\Windows' }
    if (-not $IsWin) { $vars['JAVA_HOME'] = $Jdk }
    foreach ($k in $extra.Keys) { $vars[$k] = $extra[$k] }
    $saved = @{}; foreach ($k in $vars.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k); [Environment]::SetEnvironmentVariable($k, [string]$vars[$k]) }
    $helper = Join-Path $pkg 'scripts\install-windows.ps1'
    if (-not (Test-Path -LiteralPath $helper)) { $helper = Join-Path $pkg 'tools\install-windows.ps1' }   # e.g. $Root when no package is left
    try { $out = & $PSExe -NoProfile -File $helper @a 2>&1 | ForEach-Object { "$_" } | Out-String; return @{ Out = $out; Rc = $LASTEXITCODE } }
    finally { foreach ($k in $vars.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k]) } }
}
$App = Join-Path $Home1 'AppData\Local\Programs\Notebook-AI'
$r = Inst $X @('--add-to-path'); T 'install from the extracted package (no source, Gradle or network)' ($r.Rc -eq 0) $r.Out
T 'installed layout: dist\, models\, bin\notebook-ai.cmd, legal\ with licenses, marker, manifest' ((Test-Path (Join-Path $App 'dist\bin\notebook-ai.bat')) -and (Test-Path (Join-Path $App 'models\det.onnx')) -and (Test-Path (Join-Path $App 'bin\notebook-ai.cmd')) -and (Test-Path (Join-Path $App 'legal\THIRD-PARTY-NOTICES.txt')) -and (Test-Path (Join-Path $App 'legal\licenses\openjfx\LICENSE')) -and (Test-Path (Join-Path $App '.notebook-ai-install')) -and (Test-Path (Join-Path $App 'MANIFEST.sha256')))
T 'the package''s models folder was not duplicated into dist\' (-not (Test-Path (Join-Path $App 'dist\models')))
$pf = [IO.File]::ReadAllText($PathFile)
T 'user PATH gained exactly the app bin dir; existing entries preserved' ($pf -eq ('C:\Tools;%USERPROFILE%\bin;' + (Join-Path $App 'bin')))
$r = Inst $X @('--status'); T 'installed payload verifies against MANIFEST.sha256' ($r.Rc -eq 0 -and $r.Out.Contains('Integrity             : OK'))
$wrapper = [IO.File]::ReadAllText((Join-Path $App 'bin\notebook-ai.cmd'))
T 'installed wrapper = shared launcher body (marker, no extracted-package path)' ($wrapper.Contains('owned-by=notebook-ai-installer') -and -not $wrapper.Contains($X) -and $wrapper.Contains('call "%NBAI_START%" %*'))
$src = @{}; foreach ($f in Get-ChildItem -LiteralPath (Join-Path $X 'app\lib') -File) { $src[$f.Name] = Sha $f.FullName }
$same = $true; foreach ($k in $src.Keys) { if ((Sha (Join-Path $App ('dist\lib\' + $k))) -ne $src[$k]) { $same = $false } }
T 'installed libraries are byte-identical to the package''s app\lib' $same
Remove-Item -LiteralPath $X -Recurse -Force
T 'the installation is complete after the extracted package was deleted' ((Test-Path (Join-Path $App 'dist\bin\notebook-ai.bat')) -and (Inst $Root @('--status')).Rc -eq 0)
$X2 = Extract (Join-Path $T 'ex2')
New-Item -ItemType Directory -Path (Join-Path $Home1 'Documents') -Force | Out-Null; [IO.File]::WriteAllText((Join-Path $Home1 'Documents\work.nbai'), 'project')
$r = Inst $X2 @('--add-to-path'); T 'upgrade from a fresh extraction succeeds' ($r.Rc -eq 0 -and $r.Out.Contains('upgraded an existing installation')) $r.Out
$r = Inst $X2 @('--uninstall'); T 'uninstall succeeds' ($r.Rc -eq 0) $r.Out
T 'app removed, our PATH entry removed, other entries and projects kept' ((-not (Test-Path -LiteralPath $App)) -and ([IO.File]::ReadAllText($PathFile) -eq 'C:\Tools;%USERPROFILE%\bin') -and (Test-Path (Join-Path $Home1 'Documents\work.nbai')))

Write-Host '== 4. damaged / unsuitable packages are refused before anything is installed'
$X3 = Extract (Join-Path $T 'ex3'); Add-Content -LiteralPath (Join-Path $X3 'app\lib\xmpcore-6.1.11.jar') 'x'
$r = Inst $X3 @(); T 'a modified file is detected by SHA256SUMS; nothing installed' ($r.Rc -eq 1 -and $r.Out.Contains('SHA256SUMS integrity check') -and -not (Test-Path -LiteralPath $App))
$X4 = Extract (Join-Path $T 'ex4'); Remove-Item (Join-Path $X4 'SHA256SUMS'); $r = Inst $X4 @(); T 'missing SHA256SUMS refused' ($r.Rc -eq 1 -and $r.Out.Contains('SHA256SUMS is missing'))
$X5 = Extract (Join-Path $T 'ex5'); $r = Inst $X5 @('--build'); T '--build refused in a packaged distribution' ($r.Rc -eq 1 -and $r.Out.Contains('not available in a packaged distribution'))
$r = Inst $X5 @() @{ NBAI_TEST_PLATFORM = 'linux-x64' }; T 'a Windows package is refused on a machine of another platform' ($r.Rc -eq 1 -and $r.Out.Contains('is for'))
$r = Inst $X5 @('--app-dir', (Join-Path $X5 'inside')); T 'installing into the extracted package itself is refused' ($r.Rc -eq 1)

Write-Host '== 5. [WINDOWS-ONLY] real cmd.exe execution: direct run, install.cmd, command resolution'
if ($IsWin) {
    $XW = Extract (Join-Path $T 'exw'); $Run = Join-Path $T 'elsewhere dir'; New-Item -ItemType Directory -Path $Run | Out-Null
    Push-Location -LiteralPath $Run
    try {
        $o = & cmd.exe /c ('"' + (Join-Path $XW 'run-notebook-ai.cmd') + '" --self-check') 2>&1 | Out-String
        T 'run-notebook-ai.cmd --self-check works from another directory (JavaFX modules, ONNX natives, models)' ($LASTEXITCODE -eq 0 -and $o.Contains('SELF-CHECK OK')) $o
        $env:NOTEBOOK_AI_MODELS = (Join-Path $T 'no-such-models')
        $o = & cmd.exe /c ('"' + (Join-Path $XW 'run-notebook-ai.cmd') + '" --self-check') 2>&1 | Out-String
        T 'missing models: actionable message and non-zero exit' ($LASTEXITCODE -ne 0 -and $o.Contains('OCR model files were not found'))
        Remove-Item Env:\NOTEBOOK_AI_MODELS
        $env:NBAI_TEST_MODE = '1'; $env:NBAI_TEST_PATH_FILE = (Join-Path $T 'wpath.txt'); $env:NBAI_TEST_PLATFORM = 'win-x64'
        $WA = Join-Path $T 'win app'
        $o = & cmd.exe /c ('"' + (Join-Path $XW 'install.cmd') + '" --app-dir "' + $WA + '" --no-path') 2>&1 | Out-String
        T 'install.cmd installs from the extracted package' ($LASTEXITCODE -eq 0 -and (Test-Path (Join-Path $WA 'bin\notebook-ai.cmd'))) $o
        Remove-Item Env:\NBAI_TEST_MODE, Env:\NBAI_TEST_PATH_FILE, Env:\NBAI_TEST_PLATFORM
        Remove-Item -LiteralPath $XW -Recurse -Force
        $o = & cmd.exe /c ('"' + (Join-Path $WA 'bin\notebook-ai.cmd') + '" --self-check') 2>&1 | Out-String
        T 'installed notebook-ai.cmd works after the extracted package was deleted' ($LASTEXITCODE -eq 0 -and $o.Contains('SELF-CHECK OK')) $o
        $o = & cmd.exe /c ('set "PATH=' + (Join-Path $WA 'bin') + ';%PATH%" && where notebook-ai') 2>&1 | Out-String
        T 'command resolution: where notebook-ai finds the installed wrapper' ($o.Contains('notebook-ai.cmd')) $o
        $o = & cmd.exe /c ('"' + (Join-Path $WA 'bin\notebook-ai.cmd') + '" --self-check x') 2>&1 | Out-String
        T 'arguments are forwarded to the application' ($o -ne '')
    } finally { Pop-Location }
} else {
    Skip 'run-notebook-ai.cmd --self-check from another directory (needs Windows + Java 21)'
    Skip 'install.cmd from the extracted package; installed notebook-ai.cmd after deleting the package (needs Windows)'
    Skip 'command resolution via where.exe / a new Command Prompt (needs Windows)'
    Skip 'registry-backed user PATH change and uninstall PATH removal (needs Windows)'
    Skip 'the application itself on Windows (needs Windows)'
}

Remove-Item -LiteralPath $T -Recurse -Force -ErrorAction SilentlyContinue
Write-Host ''
Write-Host ('RESULT: ' + $script:Pass + ' passed, ' + $script:Fail + ' failed, ' + $script:Skip + ' skipped')
if ($script:Fail -eq 0) { Write-Host 'ALL CHECKS PASSED' } else { Write-Host 'SOME CHECKS FAILED' }
if ($script:Fail -eq 0) { exit 0 } else { exit 1 }
