# Windows installer test suite (tools/install-windows.ps1, install.cmd, the generated notebook-ai.cmd wrapper).
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify-install-windows.ps1
#   pwsh -NoProfile -File tools/verify-install-windows.ps1        (also runs on Linux: see below)
#
# Isolation: every scenario uses a private temporary directory and a FILE-BACKED stand-in for the user PATH
# (NBAI_TEST_PATH_FILE, honoured only when NBAI_TEST_MODE=1). The real registry, real PATH and real install
# locations are never read or written. No network, no Gradle, no elevation.
#
# HONESTY NOTE: sections marked [WINDOWS-ONLY] execute notebook-ai.cmd / install.cmd with cmd.exe and are reported
# as SKIP on other hosts. When this script runs under PowerShell on Linux it verifies the PowerShell logic
# (validation, PATH algebra, staging, upgrade, rollback, uninstall, option parsing) but NOT cmd.exe behaviour,
# registry writes or Windows file locking. Only a run on real Windows (e.g. the repository's CI job) covers those.

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Installer = Join-Path $Root 'tools\install-windows.ps1'
$IsWin = ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT)
$PSExe = (Get-Process -Id $PID).Path
$T = Join-Path ([IO.Path]::GetTempPath()) ('nbai-wtest-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $T | Out-Null

$script:Pass = 0; $script:Fail = 0; $script:Skip = 0
function Ok([string]$n) { $script:Pass++; Write-Host ('PASS  ' + $n) }
function Bad([string]$n, [string]$d = '') { $script:Fail++; Write-Host ('FAIL  ' + $n); if ($d) { Write-Host ('      ' + $d) } }
function Skip([string]$n) { $script:Skip++; Write-Host ('SKIP  ' + $n) }
function T([string]$n, [bool]$c, [string]$d = '') { if ($c) { Ok $n } else { Bad $n $d } }

# Load the pure functions (dot-sourcing does not run the installer).
. $Installer

# ----------------------------------------------------------------------------------------------------------
Write-Host '== A. path validation (pure)'
$known = @{ 'user profile' = 'C:\Users\u'; 'Desktop' = 'C:\Users\u\Desktop'; 'Downloads' = 'C:\Users\u\Downloads'
            'LocalAppData' = 'C:\Users\u\AppData\Local'; 'Programs' = 'C:\Users\u\AppData\Local\Programs'
            'SystemRoot' = 'C:\Windows'; 'ProgramFiles' = 'C:\Program Files' }
$repo = 'C:\src\notebook ai'
function Why($p) { return (Get-UnsafeTargetReason $p $repo $known) }
$uni = 'C:\Users\Zo' + [char]0x00EB + '\Apps\N' + [char]0x00F6 + 'tebook'
foreach ($good in @('C:\Users\u\AppData\Local\Programs\Notebook-AI', 'D:\Apps\My App\Notebook-AI', $uni, 'e:/lower/case/ok')) {
    T ("accepts '$good'") ($null -eq (Why $good)) (Why $good)
}
$refuse = [ordered]@{
    'drive root C:\' = 'C:\'; 'drive root C:' = 'C:'; 'relative path' = 'apps\nbai'; 'bare name' = 'Notebook-AI'; 'UNC path' = '\\server\share\nbai'
    'user profile' = 'C:\Users\u'; 'Desktop' = 'C:\Users\u\Desktop'; 'Downloads (trailing slash)' = 'C:\Users\u\Downloads\'
    'LocalAppData' = 'C:\Users\u\AppData\Local'; 'Programs directory' = 'C:\Users\u\AppData\Local\Programs'; 'parent of profile (C:\Users)' = 'C:\Users'
    'Windows directory' = 'C:\Windows'; 'Program Files' = 'C:\Program Files'; 'dot-dot segment' = 'C:\Users\u\x\..\y'; 'dot segment' = 'C:\Users\u\.\y'
    'repository itself' = 'C:\src\notebook ai'; 'inside repository' = 'C:\src\notebook ai\build\app'; 'parent of repository' = 'C:\src'
    'invalid characters' = 'C:\Users\u\a|b'; 'line break' = "C:\Users\u\a`nb"; 'case-insensitive profile' = 'c:\USERS\U'
}
foreach ($k in $refuse.Keys) { T ("refuses $k") ($null -ne (Why $refuse[$k])) ("got: " + (Why $refuse[$k])) }

# ----------------------------------------------------------------------------------------------------------
Write-Host '== B. user PATH handling (pure; the registry is never touched)'
$env:NBAI_UT_BIN = 'C:\Apps\Notebook AI\bin'
$e = 'C:\Users\u\AppData\Local\Programs\Notebook-AI\bin'
$r = Add-PathEntry '' $e; T 'add to an empty PATH' ($r.Changed -and $r.Raw -eq $e)
$orig = 'C:\Tools;%USERPROFILE%\bin;C:\Program Files\Git\cmd;%SystemRoot%\system32'
$r = Add-PathEntry $orig $e; T 'append keeps every existing entry byte for byte, unexpanded' ($r.Changed -and $r.Raw -eq ($orig + ';' + $e))
T 'the %VARS% in existing entries were not expanded' ($r.Raw.Contains('%USERPROFILE%') -and $r.Raw.Contains('%SystemRoot%'))
$r2 = Add-PathEntry $r.Raw $e; T 'adding twice does not duplicate' (-not $r2.Changed -and $r2.Raw -eq $r.Raw)
$r = Add-PathEntry ('C:\x;' + $e.ToUpperInvariant() + '\') $e; T 'duplicate detection ignores case and a trailing backslash' (-not $r.Changed)
$r = Add-PathEntry 'C:\x;%NBAI_UT_BIN%' 'C:\Apps\Notebook AI\bin'; T 'duplicate detection expands %VARS% for comparison only' (-not $r.Changed -and $r.Raw -eq 'C:\x;%NBAI_UT_BIN%')
$r = Add-PathEntry 'C:\a;' $e; T 'trailing semicolon is not doubled' ($r.Raw -eq ('C:\a;' + $e))
$r = Add-PathEntry 'C:\a;;C:\b' $e; T 'existing empty segments are preserved' ($r.Raw -eq ('C:\a;;C:\b;' + $e))
$long = (1..300 | ForEach-Object { 'C:\dir' + $_ }) -join ';'
$r = Add-PathEntry $long $e; T 'a very long PATH is extended without truncation' ($r.Raw.StartsWith($long) -and $r.Raw.EndsWith($e))
$threw = $false; try { Add-PathEntry 'C:\a;"C:\b;c"' $e | Out-Null } catch { $threw = $true }; T 'a PATH containing a double quote is refused, not rewritten' $threw
$threw = $false; try { Add-PathEntry 'C:\a' 'C:\semi;colon' | Out-Null } catch { $threw = $true }; T 'a directory containing a semicolon is refused' $threw
$r = Add-PathEntry 'C:\a' 'C:\Apps\My App\bin'; T 'spaces in the entry are kept as is (no quotes added)' ($r.Raw -eq 'C:\a;C:\Apps\My App\bin')
$full = $orig + ';' + $e
$r = Remove-PathEntry $full $e; T 'remove: only the matching entry goes, the others stay verbatim and in order' ($r.Changed -and $r.Raw -eq $orig)
$r = Remove-PathEntry $orig $e; T 'remove: absent entry is a no-op' (-not $r.Changed -and $r.Raw -eq $orig)
$r = Remove-PathEntry ('C:\a;' + $e.ToLowerInvariant() + '\;C:\b') $e; T 'remove: case/trailing-slash variants match' ($r.Changed -and $r.Raw -eq 'C:\a;C:\b')
$r = Remove-PathEntry ($e + ';' + $e) $e; T 'remove: every duplicate is removed' ($r.Changed -and $r.Raw -eq '')
Remove-Item Env:\NBAI_UT_BIN

# ----------------------------------------------------------------------------------------------------------
Write-Host '== C. Java version parsing and generated wrapper (text inspection)'
T 'java 21.0.7' ((Get-JavaMajorFromText 'openjdk version "21.0.7" 2025-04-15') -eq 21)
T 'java 25' ((Get-JavaMajorFromText 'openjdk version "25" 2026-03-17') -eq 25)
T 'java 17.0.9' ((Get-JavaMajorFromText 'java version "17.0.9" 2023-10-17 LTS') -eq 17)
T 'java 1.8 reports 1 (too old)' ((Get-JavaMajorFromText 'java version "1.8.0_392"') -eq 1)
T 'unparsable output gives null' ($null -eq (Get-JavaMajorFromText 'command not found'))
$w = New-WrapperText
T '[text] wrapper uses CRLF line endings only' (($w -replace "`r`n", '') -notmatch "[`r`n]")
$wl = $w -split "`r`n"
T '[text] wrapper line 2 is the ownership marker' ($wl[1] -eq 'rem notebook-ai-launcher: owned-by=notebook-ai-installer format=1')
T '[text] wrapper is ASCII only' ($w -notmatch '[^\x00-\x7F]')
T '[text] wrapper forwards all arguments and returns the exit code' ($w.Contains('call "%NBAI_START%" %*') -and $w.Contains('endlocal & exit /b %NBAI_RC%'))
T '[text] wrapper locates the app relative to itself and defaults the models dir' ($w.Contains('"%~dp0.."') -and $w.Contains('NBAI_START=%NBAI_APP%\dist\bin\notebook-ai.bat') -and $w.Contains('NBAI_MODELS=%NBAI_APP%\models') -and $w.Contains('NOTEBOOK_AI_MODELS=%NBAI_MODELS%'))
T '[text] wrapper bakes in no absolute install or repository path' ($w -notmatch '[A-Za-z]:\\(Users|src|build)')
T '[text] wrapper checks Java >= 21 and fails open when the version cannot be parsed' ($w.Contains('LSS 21') -and $w.Contains('if not defined NBAI_MAJ goto :launch'))

# ----------------------------------------------------------------------------------------------------------
# fixtures + isolated runs of the real helper script as a child process (test mode, file-backed PATH store)
function New-Fixture([string]$dir, [string]$tag = 'v1', [string]$plat = 'win-x64') {
    if (Test-Path -LiteralPath $dir) { Remove-Item -LiteralPath $dir -Recurse -Force }
    New-Item -ItemType Directory -Path (Join-Path $dir 'dist\bin'), (Join-Path $dir 'dist\lib'), (Join-Path $dir 'models') -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $dir 'dist\PLATFORM'), ("platform=$plat`napp=notebook-ai`nversion=0.0.0-$tag`n"))
    $stub = @('@echo off', 'if not defined NBAI_STUB_EXIT set NBAI_STUB_EXIT=0', 'echo STUB models=%NOTEBOOK_AI_MODELS%', ':loop', 'if "%~1"=="" goto done', 'echo ARG[%~1]', 'shift', 'goto loop', ':done', 'exit /b %NBAI_STUB_EXIT%')
    [IO.File]::WriteAllText((Join-Path $dir 'dist\bin\notebook-ai.bat'), (($stub -join "`r`n") + "`r`n"))
    [IO.File]::WriteAllText((Join-Path $dir 'dist\lib\notebook-ai-0.0.0.jar'), "main-$tag")
    [IO.File]::WriteAllText((Join-Path $dir 'dist\lib\onnxruntime-1.22.0.jar'), 'ort')
    $jfx = 'win'; if ($plat -eq 'linux-x64') { $jfx = 'linux' }
    [IO.File]::WriteAllText((Join-Path $dir ('dist\lib\javafx-graphics-21.0.7-' + $jfx + '.jar')), 'jfx')
    if ($tag -eq 'v1') { [IO.File]::WriteAllText((Join-Path $dir 'dist\lib\stale-only-in-v1.jar'), 'stale') }
    if ($tag -eq 'v2') { [IO.File]::WriteAllText((Join-Path $dir 'dist\lib\new-in-v2.jar'), 'fresh') }
    $sums = @()
    foreach ($f in 'det.onnx', 'det.yml', 'rec.onnx', 'rec.yml') {
        $p = Join-Path $dir ('models\' + $f); [IO.File]::WriteAllText($p, "model-$f")
        $sums += ((Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $f)
    }
    [IO.File]::WriteAllLines((Join-Path $dir 'models\checksums.sha256'), $sums)
}
function New-FakeJava([string]$dir, [int]$major) {
    New-Item -ItemType Directory -Path (Join-Path $dir 'bin') -Force | Out-Null
    $p = Join-Path $dir 'bin/java'
    [IO.File]::WriteAllText($p, ("#!/bin/sh`necho 'openjdk version `"$major.0.1`" 2026-01-01' >&2`n"))
    & chmod 755 $p
}

$Home1 = Join-Path $T 'home'; New-Item -ItemType Directory -Path $Home1 | Out-Null
$PathFile = Join-Path $T 'userpath.txt'
$Jdk21 = Join-Path $T 'jdk21'; $Jdk17 = Join-Path $T 'jdk17'
if (-not $IsWin) { New-FakeJava $Jdk21 21; New-FakeJava $Jdk17 17 }

function Invoke-Inst([string[]]$a, [hashtable]$extra = @{}) {
    $vars = @{ NBAI_TEST_MODE = '1'; NBAI_TEST_PLATFORM = 'win-x64'; NBAI_TEST_PATH_FILE = $PathFile; LOCALAPPDATA = (PJ $Home1 'AppData\Local')
               USERPROFILE = $Home1; SystemRoot = 'C:\Windows'; ProgramFiles = 'C:\Program Files'; NBAI_TEST_ELEVATED = '0' }
    if (-not $IsWin) { $vars['JAVA_HOME'] = $Jdk21 }
    foreach ($k in $extra.Keys) { $vars[$k] = $extra[$k] }
    $saved = @{}
    foreach ($k in $vars.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k); if ($null -eq $vars[$k]) { [Environment]::SetEnvironmentVariable($k, $null) } else { [Environment]::SetEnvironmentVariable($k, [string]$vars[$k]) } }
    try {
        $out = & $PSExe -NoProfile -File $Installer @a 2>&1 | ForEach-Object { "$_" } | Out-String
        return @{ Out = $out; Rc = $LASTEXITCODE }
    } finally { foreach ($k in $vars.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k]) } }
}
function Snap([string]$d) { if (Test-Path -LiteralPath $d) { return ((Get-ChildItem -LiteralPath $d -Recurse -Force | ForEach-Object { $_.FullName.Substring($d.Length) } | Sort-Object) -join '|') } else { return '' } }
function FileText([string]$p) { return [IO.File]::ReadAllText($p) }

$Fx = Join-Path $T 'fx1'; New-Fixture $Fx 'v1'
$Fx2 = Join-Path $T 'fx2'; New-Fixture $Fx2 'v2'
$App = PJ $Home1 'AppData\Local\Programs\Notebook-AI'
$common = @('--dist', (Join-Path $Fx 'dist'), '--models', (Join-Path $Fx 'models'))

Write-Host '== D. usage and option parsing'
$r = Invoke-Inst @('--help'); T '--help exits 0 and documents --add-to-path' ($r.Rc -eq 0 -and $r.Out.Contains('--add-to-path'))
$r = Invoke-Inst @('/?'); T '/? is accepted as help' ($r.Rc -eq 0 -and $r.Out.Contains('Usage:'))
$r = Invoke-Inst @('--bogus'); T 'unknown option exits 2' ($r.Rc -eq 2)
$r = Invoke-Inst @('--app-dir'); T 'missing option value exits 2' ($r.Rc -eq 2)
$r = Invoke-Inst @('--add-to-path', '--no-path'); T 'conflicting PATH options exit 2' ($r.Rc -eq 2)

Write-Host '== E. fresh install (default location under LOCALAPPDATA), PATH consent'
[IO.File]::WriteAllText($PathFile, 'C:\Tools;%USERPROFILE%\bin')
$r = Invoke-Inst ($common)
T 'install succeeds without --add-to-path' ($r.Rc -eq 0) $r.Out
T 'default app dir is <LOCALAPPDATA>\Programs\Notebook-AI' (Test-Path -LiteralPath (Join-Path $App '.notebook-ai-install'))
T 'layout: dist\ models\ bin\notebook-ai.cmd INSTALL.info MANIFEST.sha256' ((Test-Path (Join-Path $App 'dist/bin/notebook-ai.bat')) -and (Test-Path (Join-Path $App 'models/det.onnx')) -and (Test-Path (Join-Path $App 'bin/notebook-ai.cmd')) -and (Test-Path (Join-Path $App 'INSTALL.info')) -and (Test-Path (Join-Path $App 'MANIFEST.sha256')))
T 'installed wrapper equals the generated, marker-bearing wrapper' ((FileText (Join-Path $App 'bin/notebook-ai.cmd')) -eq (New-WrapperText))
T 'PATH was NOT modified without consent' ((FileText $PathFile) -eq 'C:\Tools;%USERPROFILE%\bin')
T 'manual instructions printed when PATH is declined' ($r.Out.Contains('NOTE: typing notebook-ai will not work') -and $r.Out.Contains('notebook-ai.cmd'))
$r = Invoke-Inst @('--status'); T '--status verifies integrity' ($r.Rc -eq 0 -and $r.Out.Contains('Integrity             : OK'))
$r = Invoke-Inst ($common + @('--add-to-path')); T 're-run with --add-to-path succeeds (upgrade in place)' ($r.Rc -eq 0) $r.Out
$bin = PJ $App 'bin'
$p = FileText $PathFile
T 'PATH gained exactly the app bin dir, existing entries preserved verbatim' ($p -eq ('C:\Tools;%USERPROFILE%\bin;' + $bin)) $p
T 'the open-a-new-terminal notice is printed' ($r.Out.Contains('Open a NEW Command Prompt'))
$r = Invoke-Inst ($common + @('--add-to-path')); T 'a further run does not duplicate the PATH entry' ((FileText $PathFile) -eq $p -and $r.Out.Contains('already on your user PATH'))
T 'ownership of the PATH entry is recorded' ((FileText (Join-Path $App 'path-state.txt')).Contains($bin))

Write-Host '== F. upgrade: whole-app replacement, stale files removed, PATH state kept'
$infoBefore = FileText (Join-Path $App 'INSTALL.info')
T 'v1 contains the library that v2 drops' (Test-Path (Join-Path $App 'dist/lib/stale-only-in-v1.jar'))
New-Item -ItemType Directory -Path (PJ $Home1 'AppData\Local\Programs\OtherApp') -Force | Out-Null; [IO.File]::WriteAllText((PJ $Home1 'AppData\Local\Programs\OtherApp\keep.txt'), 'keep')
$r = Invoke-Inst @('--dist', (Join-Path $Fx2 'dist'), '--models', (Join-Path $Fx2 'models')); T 'upgrade to v2 succeeds' ($r.Rc -eq 0) $r.Out
T 'stale library gone, new library present' ((-not (Test-Path (Join-Path $App 'dist/lib/stale-only-in-v1.jar'))) -and (Test-Path (Join-Path $App 'dist/lib/new-in-v2.jar')))
T 'INSTALL.info reflects the new build' ($infoBefore -ne (FileText (Join-Path $App 'INSTALL.info')))
T 'no installer temp/previous directories left' (@(Get-ChildItem -LiteralPath (Split-Path -Parent $App) -Force | Where-Object { $_.Name -like '.notebook-ai.*' }).Count -eq 0)
T 'a sibling application is untouched' (Test-Path (PJ $Home1 'AppData\Local\Programs\OtherApp\keep.txt'))
T 'PATH ownership state survives the upgrade' ((Test-Path (Join-Path $App 'path-state.txt')) -and (FileText (Join-Path $App 'path-state.txt')).Contains($bin))

Write-Host '== G. refusals leave the existing installation untouched'
$snap = Snap $App
$r = Invoke-Inst @('--dist', (Join-Path $T 'nope'), '--models', (Join-Path $Fx 'models')); T 'missing dist: exit 1 with the normal-user build command' ($r.Rc -eq 1 -and $r.Out.Contains('gradlew.bat installDist'))
$r = Invoke-Inst @('--dist', (Join-Path $Fx 'dist'), '--models', (Join-Path $T 'nomodels')); T 'missing models: exit 1 with the preparation command' ($r.Rc -eq 1 -and $r.Out.Contains('download-models.ps1'))
Copy-Item -LiteralPath (Join-Path $Fx 'models') -Destination (Join-Path $T 'badmodels') -Recurse; Add-Content -LiteralPath (Join-Path $T 'badmodels/det.onnx') 'tampered'
$r = Invoke-Inst @('--dist', (Join-Path $Fx 'dist'), '--models', (Join-Path $T 'badmodels')); T 'tampered model refused (the committed checksums are enforced)' ($r.Rc -eq 1 -and $r.Out.Contains('checksum mismatch'))
$FxL = Join-Path $T 'fxlinux'; New-Fixture $FxL 'v1' 'linux-x64'
$r = Invoke-Inst @('--dist', (Join-Path $FxL 'dist'), '--models', (Join-Path $FxL 'models')); T 'a Linux distribution is rejected on Windows' ($r.Rc -eq 1 -and $r.Out.Contains('A Linux distribution cannot be installed on Windows'))
$FxM = Join-Path $T 'fxmix'; New-Fixture $FxM 'v1'; [IO.File]::WriteAllText((Join-Path $FxM 'dist/lib/javafx-base-21.0.7-linux.jar'), 'x')
$r = Invoke-Inst @('--dist', (Join-Path $FxM 'dist'), '--models', (Join-Path $FxM 'models')); T 'mixed-platform JavaFX jars rejected' ($r.Rc -eq 1 -and $r.Out.Contains('Linux JavaFX jars'))
$r = Invoke-Inst $common @{ NBAI_TEST_ELEVATED = '1' }; T 'an elevated (Administrator) shell is refused' ($r.Rc -eq 1 -and $r.Out.Contains('elevated'))
$r = Invoke-Inst (@('--app-dir', 'relative\path') + $common); T 'relative app dir refused' ($r.Rc -eq 1)
$r = Invoke-Inst (@('--app-dir', 'C:\Windows') + $common); T 'C:\Windows itself refused' ($r.Rc -eq 1 -and $r.Out.Contains('refusing'))
$r = Invoke-Inst (@('--app-dir', $Home1) + $common); T 'the user profile directory refused' ($r.Rc -eq 1)
$r = Invoke-Inst (@('--app-dir', $Root) + $common); T 'the source repository refused as app dir' ($r.Rc -eq 1)
$Occupied = Join-Path $T 'occupied'; New-Item -ItemType Directory -Path $Occupied | Out-Null; [IO.File]::WriteAllText((Join-Path $Occupied 'precious.txt'), 'precious')
$r = Invoke-Inst (@('--app-dir', $Occupied) + $common); T 'a non-empty directory without the ownership marker is refused and untouched' ($r.Rc -eq 1 -and $r.Out.Contains('no ownership marker') -and (FileText (Join-Path $Occupied 'precious.txt')) -eq 'precious')
if (-not $IsWin) {
    $r = Invoke-Inst $common @{ JAVA_HOME = $Jdk17 }; T 'Java 17 refused (Java 21+ required)' ($r.Rc -eq 1 -and $r.Out.Contains('Java 17 found'))
    $r = Invoke-Inst $common @{ JAVA_HOME = (Join-Path $T 'no-such-jdk') }; T 'invalid JAVA_HOME refused' ($r.Rc -eq 1 -and $r.Out.Contains('JAVA_HOME'))
    $r = Invoke-Inst $common @{ JAVA_HOME = $null; PATH = '/nonexistent-dir' }; T 'no Java at all refused' ($r.Rc -eq 1 -and $r.Out.Contains('no Java found'))
    $r = Invoke-Inst $common @{ NBAI_TEST_PLATFORM = $null }; T 'running the Windows installer on Linux is refused (not silently accepted)' ($r.Rc -eq 1 -and $r.Out.Contains('for Windows'))
} else { Skip 'fake-Java scenarios use a unix shell script (covered on Linux hosts)' }
T 'every refusal left the installation byte-for-byte unchanged' ($snap -eq (Snap $App))

Write-Host '== H. failure paths keep the previous installation usable (test seams)'
foreach ($seam in 'stage', 'swap') {
    $infoNow = FileText (Join-Path $App 'INSTALL.info')
    $r = Invoke-Inst @('--dist', (Join-Path $Fx 'dist'), '--models', (Join-Path $Fx 'models')) @{ NBAI_TEST_FAIL_AT = $seam }
    T ("failure at '$seam': exit 1") ($r.Rc -eq 1) $r.Out
    T ("failure at '$seam': previous installation intact (same INSTALL.info, new-in-v2.jar still there)") (((FileText (Join-Path $App 'INSTALL.info')) -eq $infoNow) -and (Test-Path (Join-Path $App 'dist/lib/new-in-v2.jar')))
    T ("failure at '$seam': no temp or previous directories left") (@(Get-ChildItem -LiteralPath (Split-Path -Parent $App) -Force | Where-Object { $_.Name -like '.notebook-ai.*' }).Count -eq 0)
    $r = Invoke-Inst @('--status'); T ("failure at '$seam': --status still OK") ($r.Rc -eq 0)
}

Write-Host '== I. uninstall touches only what the installer owns'
New-Item -ItemType Directory -Path (Join-Path $Home1 'Documents') -Force | Out-Null; [IO.File]::WriteAllText((Join-Path $Home1 'Documents/work.nbai'), 'project'); [IO.File]::WriteAllText((Join-Path $Home1 'Documents/export.txt'), 'text')
$r = Invoke-Inst @('--uninstall'); T 'uninstall succeeds' ($r.Rc -eq 0) $r.Out
T 'the app directory is gone' (-not (Test-Path -LiteralPath $App))
T 'the PATH entry added by the installer was removed, other entries preserved verbatim' ((FileText $PathFile) -eq 'C:\Tools;%USERPROFILE%\bin') (FileText $PathFile)
T 'projects, exports and sibling apps retained; Programs dir not removed' ((Test-Path (Join-Path $Home1 'Documents/work.nbai')) -and (Test-Path (Join-Path $Home1 'Documents/export.txt')) -and (Test-Path (PJ $Home1 'AppData\Local\Programs\OtherApp\keep.txt')) -and (Test-Path (PJ $Home1 'AppData\Local\Programs')))
T 'uninstall explains what is retained' ($r.Out.Contains('Retained: your projects'))
$r = Invoke-Inst @('--uninstall'); T 'uninstalling twice is harmless' ($r.Rc -eq 0 -and $r.Out.Contains('No application directory'))
$r = Invoke-Inst @('--app-dir', $Occupied, '--uninstall'); T 'uninstall never removes a directory without the ownership marker' ($r.Rc -eq 0 -and (Test-Path (Join-Path $Occupied 'precious.txt')))
# an entry the user added themselves is not removed
[IO.File]::WriteAllText($PathFile, ('C:\Tools;' + $App + '\bin'))
$r = Invoke-Inst ($common + @('--no-path')); T 'install when the dir is already on PATH (not added by us)' ($r.Rc -eq 0 -and $r.Out.Contains('already on your user PATH'))
T 'no ownership state is recorded for a user-added entry' (-not (Test-Path (Join-Path $App 'path-state.txt')))
$r = Invoke-Inst @('--uninstall'); T 'uninstall leaves a user-added PATH entry alone and says so' ($r.Rc -eq 0 -and (FileText $PathFile) -eq ('C:\Tools;' + $App + '\bin') -and $r.Out.Contains('left alone'))

Write-Host '== J. paths with spaces and non-ASCII characters'
$Odd = Join-Path $T ('Z' + [char]0x00F6 + 'e Apps/My App')
$r = Invoke-Inst (@('--app-dir', $Odd) + $common + @('--no-path')); T 'install into a path with spaces and a non-ASCII character' ($r.Rc -eq 0) $r.Out
T 'files present there and verifiable' ((Test-Path (Join-Path $Odd 'bin/notebook-ai.cmd')) -and ((Invoke-Inst @('--app-dir', $Odd, '--status')).Rc -eq 0))
$r = Invoke-Inst @('--app-dir', $Odd, '--uninstall'); T 'uninstall from that path removes only the app directory' ($r.Rc -eq 0 -and -not (Test-Path -LiteralPath $Odd) -and (Test-Path -LiteralPath (Split-Path -Parent $Odd)))

Write-Host '== K. [WINDOWS-ONLY] real cmd.exe execution of install.cmd and notebook-ai.cmd'
if ($IsWin) {
    $WApp = Join-Path $T 'win app'; $WRun = Join-Path $T 'elsewhere dir'; New-Item -ItemType Directory -Path $WRun | Out-Null
    $out = & cmd.exe /c ('"' + (Join-Path $Root 'install.cmd') + '" --help') 2>&1 | Out-String
    T 'install.cmd --help runs through cmd.exe' ($LASTEXITCODE -eq 0 -and $out.Contains('Usage:'))
    $out = & cmd.exe /c ('"' + (Join-Path $Root 'install.cmd') + '" --bogus') 2>&1 | Out-String
    T 'install.cmd preserves the helper exit code (2)' ($LASTEXITCODE -eq 2)
    $env:NBAI_TEST_MODE = '1'; $env:NBAI_TEST_PATH_FILE = $PathFile; $env:NBAI_TEST_PLATFORM = 'win-x64'
    $out = & cmd.exe /c ('"' + (Join-Path $Root 'install.cmd') + '" --app-dir "' + $WApp + '" --dist "' + (Join-Path $Fx 'dist') + '" --models "' + (Join-Path $Fx 'models') + '" --no-path') 2>&1 | Out-String
    T 'install.cmd installs the fixture' ($LASTEXITCODE -eq 0 -and (Test-Path (Join-Path $WApp 'bin\notebook-ai.cmd'))) $out
    Remove-Item Env:\NBAI_TEST_MODE, Env:\NBAI_TEST_PATH_FILE, Env:\NBAI_TEST_PLATFORM
    Push-Location -LiteralPath $WRun
    try {
        $env:NBAI_STUB_EXIT = '7'
        $out = & cmd.exe /c ('"' + (Join-Path $WApp 'bin\notebook-ai.cmd') + '" "a b" c ""') 2>&1 | Out-String
        T 'wrapper runs from another directory' ($out.Contains('STUB models=' + (Join-Path $WApp 'models')))
        T 'wrapper forwards arguments' ($out.Contains('ARG[a b]') -and $out.Contains('ARG[c]'))
        T 'wrapper preserves the exit status (7)' ($LASTEXITCODE -eq 7)
        Remove-Item Env:\NBAI_STUB_EXIT
    } finally { Pop-Location }
} else { Skip 'cmd.exe execution of install.cmd / notebook-ai.cmd (needs Windows)'; Skip 'wrapper launch from another directory, argument forwarding, exit status (needs Windows)'; Skip 'registry-backed user PATH update and WM_SETTINGCHANGE broadcast (needs Windows)'; Skip 'real file-lock behaviour during upgrade (needs Windows)' }

Remove-Item -LiteralPath $T -Recurse -Force -ErrorAction SilentlyContinue
Write-Host ''
Write-Host ("RESULT: " + $script:Pass + ' passed, ' + $script:Fail + ' failed, ' + $script:Skip + ' skipped')
if ($script:Fail -eq 0) { Write-Host 'ALL CHECKS PASSED' } else { Write-Host 'SOME CHECKS FAILED' }
if ($script:Fail -eq 0) { exit 0 } else { exit 1 }
