# notebook-ai installer helper for Windows (per-user). Called by install.cmd; can also be run directly:
#   powershell -NoProfile -File tools\install-windows.ps1 [options]
#
# Requires Windows PowerShell 5.1 or PowerShell 7+. Does not change any execution policy (install.cmd uses a
# process-scoped bypass for this single run only; Group Policy, if enforced, still wins).
# Per-user only: never writes outside the chosen application directory and the current user's PATH value.
#
# Exit codes: 0 ok, 1 failure (the previous installation is untouched unless stated), 2 usage error.

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$script:Marker = '.notebook-ai-install'
$script:LauncherMark = 'rem notebook-ai-launcher: owned-by=notebook-ai-installer format=1'
$script:MinJava = 21
$script:PreviousPathAdded = $null
$script:Pkg = $false
$script:RepoDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

function Test-TestMode { return ($env:NBAI_TEST_MODE -eq '1') }

# Joins path components one at a time with the host's separator; components may themselves contain \ or /.
# (PJ with an embedded backslash is only correct on Windows; this keeps the logic testable under pwsh on Linux.)
function PJ {
    $p = [string]$args[0]
    for ($i = 1; $i -lt $args.Count; $i++) {
        foreach ($seg in ([string]$args[$i] -split '[\\/]')) { if ($seg -ne '') { $p = Join-Path $p $seg } }
    }
    return $p
}
function Fail([string]$m) { throw ('NBAI:' + $m) }
function FailUsage([string]$m) { throw ('NBAI-USAGE:' + $m) }
function Say([string]$m) { Write-Host $m }
function SayWarn([string]$m) { Write-Host ('WARNING: ' + $m) }

function Show-Usage {
@'
Usage: install.cmd [install|uninstall|status] [options]

Installs notebook-ai for the CURRENT USER from an already built Windows distribution.
No Administrator rights are needed; nothing is downloaded or built unless --build is given.

Options:
  --app-dir DIR      application directory (default: %LOCALAPPDATA%\Programs\Notebook-AI)
  --dist DIR         staged distribution (default: build\install-win-x64\notebook-ai, or build\install\notebook-ai
                     when built on Windows)
  --models DIR       verified models directory (default: models\ next to install.cmd)
  --add-to-path      add <app-dir>\bin to YOUR user PATH (explicit consent; otherwise you are asked)
  --no-path          do not touch PATH; instructions are printed instead
  --build            opt-in: run gradlew.bat installDist first (uses the network; refused when elevated)
  --uninstall        remove the installation selected by the same options (also: 'uninstall')
  --status           show and verify the installation (also: 'status')
  -h, --help, /?     this text

Preparation commands (run as your normal user; the installer does not run them):
  gradlew.bat installDist      and      powershell -NoProfile -ExecutionPolicy Bypass -File tools\download-models.ps1
  (Windows distribution built on Linux:  ./gradlew installDist -PtargetPlatform=win-x64)

The installed command is  <app-dir>\bin\notebook-ai.cmd . Existing terminals must be reopened after the user
PATH changes. Projects (.nbai) and exports are never touched. Supported: Windows x64 only.
'@ | Write-Host
}

# --------------------------------------------------------------------------------------------------
# pure helpers (unit-tested; see tools/verify-install-windows.ps1)
# --------------------------------------------------------------------------------------------------
function Test-ControlChars([string]$p) { return ($p -match "[\r\n\0]") }

function Test-AbsoluteWinPath([string]$p) {
    if ($p -match '^[A-Za-z]:[\\/]') { return $true }
    if ((Test-TestMode) -and $p.StartsWith('/')) { return $true }   # test seam: POSIX temp dirs under pwsh
    return $false
}

function Get-NormalizedPath([string]$p) {
    $n = $p.Replace('/', '\').Trim()
    while ($n.Length -gt 3 -and $n.EndsWith('\')) { $n = $n.Substring(0, $n.Length - 1) }
    return $n.ToLowerInvariant()
}

function Test-HasDotSegments([string]$p) {
    foreach ($seg in ($p -split '[\\/]')) { if ($seg -eq '.' -or $seg -eq '..') { return $true } }
    return $false
}

# Returns $null when the target is acceptable, otherwise a human-readable reason.
function Get-UnsafeTargetReason([string]$path, [string]$repoDir, [hashtable]$known) {
    if (Test-ControlChars $path) { return 'it contains a line break or NUL character' }
    if (-not (Test-AbsoluteWinPath $path)) { return 'it is not an absolute path with a drive letter (UNC and relative paths are refused)' }
    if (Test-HasDotSegments $path) { return "it contains '.' or '..' segments" }
    if ($path -match '[<>"|?*]') { return 'it contains characters that are not valid in Windows paths' }
    $n = Get-NormalizedPath $path
    if ($n -match '^[a-z]:\\?$') { return 'it is a drive root' }
    foreach ($k in $known.Keys) {
        $kn = Get-NormalizedPath $known[$k]
        if ($kn -eq $n) { return "it is the $k directory itself" }
        if ($kn.StartsWith($n + '\')) { return "it is a parent of the $k directory" }
    }
    $rn = Get-NormalizedPath $repoDir
    if ($n -eq $rn -or $n.StartsWith($rn + '\')) { return 'it is inside the source repository' }
    if ($rn.StartsWith($n + '\')) { return 'it is a parent of the source repository' }
    return $null
}

function Split-PathList([string]$raw) { if ([string]::IsNullOrEmpty($raw)) { return @() } else { return @($raw.Split(';')) } }

function Get-EntryKey([string]$e) {
    $x = [Environment]::ExpandEnvironmentVariables($e.Trim())
    return (Get-NormalizedPath $x)
}

function Test-PathListHasEntry([string]$raw, [string]$entry) {
    $want = Get-EntryKey $entry
    foreach ($s in (Split-PathList $raw)) { if ($s.Trim() -ne '' -and (Get-EntryKey $s) -eq $want) { return $true } }
    return $false
}

# Returns @{ Raw = <new value>; Changed = <bool> }. Existing entries are kept byte-for-byte; no expansion.
function Add-PathEntry([string]$raw, [string]$entry) {
    if ($raw.Contains('"')) { Fail 'your user PATH contains a double quote, which this installer will not rewrite. Add the directory manually.' }
    if ($entry.Contains(';')) { Fail 'the directory contains a semicolon and cannot be placed on PATH safely.' }
    if (Test-PathListHasEntry $raw $entry) { return @{ Raw = $raw; Changed = $false } }
    if ([string]::IsNullOrWhiteSpace($raw)) { return @{ Raw = $entry; Changed = $true } }
    if ($raw.EndsWith(';')) { return @{ Raw = ($raw + $entry); Changed = $true } }
    return @{ Raw = ($raw + ';' + $entry); Changed = $true }
}

function Remove-PathEntry([string]$raw, [string]$entry) {
    if ($raw.Contains('"')) { Fail 'your user PATH contains a double quote, which this installer will not rewrite. Remove the entry manually.' }
    $want = Get-EntryKey $entry
    $keep = New-Object System.Collections.Generic.List[string]
    $removed = $false
    foreach ($s in (Split-PathList $raw)) {
        if ($s.Trim() -ne '' -and (Get-EntryKey $s) -eq $want) { $removed = $true } else { $keep.Add($s) }
    }
    return @{ Raw = ($keep -join ';'); Changed = $removed }
}

function Get-JavaMajorFromText([string]$text) {
    if ($text -match 'version "(\d+)') { return [int]$Matches[1] }
    return $null
}

# --------------------------------------------------------------------------------------------------
# user PATH store (registry, preserving unexpanded %VARS% and the value kind; file-backed in test mode)
# --------------------------------------------------------------------------------------------------
function Get-UserPathRaw {
    if ((Test-TestMode) -and $env:NBAI_TEST_PATH_FILE) {
        if (Test-Path -LiteralPath $env:NBAI_TEST_PATH_FILE) { return ([IO.File]::ReadAllText($env:NBAI_TEST_PATH_FILE)).TrimEnd("`r", "`n") }
        return ''
    }
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment', $false)
    if ($null -eq $key) { return '' }
    try { return [string]$key.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames) }
    finally { $key.Close() }
}

function Set-UserPathRaw([string]$value) {
    if ((Test-TestMode) -and $env:NBAI_TEST_PATH_FILE) { [IO.File]::WriteAllText($env:NBAI_TEST_PATH_FILE, $value); return }
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment', $true)
    if ($null -eq $key) { Fail 'cannot open HKCU\Environment for writing' }
    try {
        $kind = [Microsoft.Win32.RegistryValueKind]::ExpandString
        try { $kind = $key.GetValueKind('Path') } catch { }
        $key.SetValue('Path', $value, $kind)
    } finally { $key.Close() }
    try {
        Add-Type -Namespace NbAi -Name Native -MemberDefinition '[DllImport("user32.dll", SetLastError=true, CharSet=CharSet.Auto)] public static extern IntPtr SendMessageTimeout(IntPtr hWnd, uint Msg, UIntPtr wParam, string lParam, uint fuFlags, uint uTimeout, out UIntPtr lpdwResult);' -ErrorAction Stop
        $res = [UIntPtr]::Zero
        [void][NbAi.Native]::SendMessageTimeout([IntPtr]0xffff, 0x1A, [UIntPtr]::Zero, 'Environment', 2, 5000, [ref]$res)
    } catch { SayWarn 'PATH was saved but running programs could not be notified; reopen your terminal.' }
}

# --------------------------------------------------------------------------------------------------
# environment checks
# --------------------------------------------------------------------------------------------------
function Get-HostPlatform {
    if ((Test-TestMode) -and $env:NBAI_TEST_PLATFORM) { return $env:NBAI_TEST_PLATFORM }
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { Fail 'this installer is for Windows. On Linux use install.sh.' }
    $arch = $env:PROCESSOR_ARCHITEW6432
    if ([string]::IsNullOrEmpty($arch)) { $arch = $env:PROCESSOR_ARCHITECTURE }
    if ($arch -ne 'AMD64') { Fail ("unsupported architecture '" + $arch + "'. Only Windows x64 is supported; ARM64 and 32-bit are not interchangeable.") }
    return 'win-x64'
}

function Test-Elevated {
    if (Test-TestMode) { return ($env:NBAI_TEST_ELEVATED -eq '1') }
    try {
        $id = [Security.Principal.WindowsIdentity]::GetCurrent()
        return (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    } catch { return $false }
}

function Find-Java {
    if ($env:JAVA_HOME) {
        $j = PJ $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path -LiteralPath $j) { return $j }
        $j2 = PJ $env:JAVA_HOME 'bin/java'
        if ((Test-TestMode) -and (Test-Path -LiteralPath $j2)) { return $j2 }
        Fail ("JAVA_HOME is set to '" + $env:JAVA_HOME + "' but no java executable was found in its bin directory. Fix or unset JAVA_HOME.")
    }
    $c = Get-Command java.exe -ErrorAction SilentlyContinue
    if (-not $c -and (Test-TestMode)) { $c = Get-Command java -ErrorAction SilentlyContinue }
    if ($c) { return $c.Source }
    return $null
}

function Get-JavaMajor([string]$javaExe) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $txt = (& $javaExe -version 2>&1 | ForEach-Object { "$_" }) -join "`n" } finally { $ErrorActionPreference = $old }
    return (Get-JavaMajorFromText $txt)
}

function Test-ModelsValid([string]$dir) {
    foreach ($f in 'det.onnx', 'det.yml', 'rec.onnx', 'rec.yml', 'checksums.sha256') {
        if (-not (Test-Path -LiteralPath (PJ $dir $f) -PathType Leaf)) {
            if ($script:Pkg) { Fail ("model file '" + (PJ $dir $f) + "' is missing: this extracted distribution is incomplete; extract the archive again.") }
            Fail ("model file '" + (PJ $dir $f) + "' is missing. Prepare the models first (network needed once, as your normal user):`n         powershell -NoProfile -ExecutionPolicy Bypass -File tools\download-models.ps1")
        }
    }
    $want = @{}
    foreach ($line in [IO.File]::ReadAllLines((PJ $dir 'checksums.sha256'))) {
        if ($line -match '^([0-9a-fA-F]{64})\s+\*?(\S.*)$') { $want[$Matches[2].Trim()] = $Matches[1].ToLowerInvariant() }
    }
    foreach ($f in 'det.onnx', 'det.yml', 'rec.onnx', 'rec.yml') {
        if (-not $want.ContainsKey($f)) { Fail ("checksums.sha256 has no entry for " + $f) }
        $got = (Get-FileHash -LiteralPath (PJ $dir $f) -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($got -ne $want[$f]) { Fail ("model checksum mismatch for " + $f + " in '" + $dir + "'. Re-download with tools\download-models.ps1") }
    }
}

function Test-DistValid([string]$dist, [string]$platform) {
    if ($script:Pkg -and -not (Test-Path -LiteralPath $dist -PathType Container)) { Fail ("'" + $dist + "' is missing: this extracted distribution is incomplete; extract the archive again.") }
    if (-not (Test-Path -LiteralPath $dist -PathType Container)) {
        Fail ("no staged distribution at '" + $dist + "'. Build it first as your normal user (NOT elevated):`n         gradlew.bat installDist      (or on Linux: ./gradlew installDist -PtargetPlatform=win-x64)`n         or:  install.cmd --build")
    }
    if (-not (Test-Path -LiteralPath (PJ $dist 'bin\notebook-ai.bat') -PathType Leaf)) { Fail ("'" + $dist + "' is not a complete distribution (bin\notebook-ai.bat missing).") }
    $pf = PJ $dist 'PLATFORM'
    if (-not (Test-Path -LiteralPath $pf -PathType Leaf)) { Fail ("'" + $dist + "' has no PLATFORM file; rebuild with this version of the build.") }
    $dplat = $null
    foreach ($l in [IO.File]::ReadAllLines($pf)) { if ($l -match '^platform=(.+)$') { $dplat = $Matches[1].Trim() } }
    if ($dplat -ne $platform) { Fail ("the staged distribution is for '" + $dplat + "', but this machine needs '" + $platform + "'. A Linux distribution cannot be installed on Windows. Rebuild with -PtargetPlatform=win-x64.") }
    $jars = @(Get-ChildItem -LiteralPath (PJ $dist 'lib') -Filter 'notebook-ai-*.jar' -File -ErrorAction SilentlyContinue)
    if ($jars.Count -ne 1) { Fail ("'" + $dist + "\lib' must contain exactly one notebook-ai-*.jar (found " + $jars.Count + ").") }
    if (@(Get-ChildItem -LiteralPath (PJ $dist 'lib') -Filter 'javafx-graphics-*-win.jar' -File -ErrorAction SilentlyContinue).Count -lt 1) { Fail 'no Windows JavaFX jars (javafx-graphics-*-win.jar) in the distribution.' }
    if (@(Get-ChildItem -LiteralPath (PJ $dist 'lib') -Filter 'javafx-*-linux.jar' -File -ErrorAction SilentlyContinue).Count -gt 0) { Fail 'the distribution contains Linux JavaFX jars; it is not a Windows build.' }
    if (@(Get-ChildItem -LiteralPath (PJ $dist 'lib') -Filter 'onnxruntime-*.jar' -File -ErrorAction SilentlyContinue).Count -lt 1) { Fail 'the ONNX Runtime jar is missing from the distribution.' }
}

# --------------------------------------------------------------------------------------------------
# tree hashing / copying
# --------------------------------------------------------------------------------------------------
function Get-TreeHashes([string]$root, [string[]]$only = @()) {
    $r = (Resolve-Path -LiteralPath $root).ProviderPath.TrimEnd('\', '/')
    $h = @{}
    foreach ($f in (Get-ChildItem -LiteralPath $r -Recurse -File -Force)) {
        $rel = $f.FullName.Substring($r.Length + 1).Replace('\', '/')
        if ($only.Count -gt 0) {
            $top = $rel.Split('/')[0]
            if ($only -notcontains $top) { continue }
        }
        $h[$rel] = (Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    return $h
}

# Release distribution: every shipped file must match SHA256SUMS before anything is installed.
function Test-PackageIntegrity([string]$root) {
    $sums = PJ $root 'SHA256SUMS'
    if (-not (Test-Path -LiteralPath $sums -PathType Leaf)) { Fail 'SHA256SUMS is missing from the distribution; extract the archive again.' }
    $bad = 0
    foreach ($l in [IO.File]::ReadAllLines($sums)) {
        if ($l -match '^([0-9a-fA-F]{64})  (.+)$') {
            $p = PJ $root $Matches[2]
            if (-not (Test-Path -LiteralPath $p -PathType Leaf) -or (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Matches[1].ToLowerInvariant()) { $bad++ }
        }
    }
    if ($bad -gt 0) { Fail ('the extracted distribution failed its SHA256SUMS integrity check (' + $bad + ' file(s) differ or are missing); extract the archive again (verify the archive against its .sha256 file first).') }
}

function Test-SameHashes($a, $b) {
    if ($a.Count -ne $b.Count) { return $false }
    foreach ($k in $a.Keys) { if (-not $b.ContainsKey($k) -or $a[$k] -ne $b[$k]) { return $false } }
    return $true
}

function Get-LauncherFragmentPath {
    foreach ($rel in 'scripts\launcher-body.cmd', 'packaging\launcher-body.cmd') {
        $p = PJ $script:RepoDir $rel
        if (Test-Path -LiteralPath $p -PathType Leaf) { return $p }
    }
    Fail 'the shared launcher template (scripts\launcher-body.cmd) is missing next to the installer; extract the distribution again.'
}

# The installed command = a small header (paths) + the SINGLE-SOURCE launcher body shared with run-notebook-ai.cmd.
function New-WrapperText {
    $head = @(
        '@echo off',
        $script:LauncherMark,
        'rem Generated by install.cmd. Do not edit; reinstall to regenerate. Runs the installed copy only.',
        'setlocal DisableDelayedExpansion',
        'for %%I in ("%~dp0..") do set "NBAI_APP=%%~fI"',
        'set "NBAI_START=%NBAI_APP%\dist\bin\notebook-ai.bat"',
        'set "NBAI_MODELS=%NBAI_APP%\models"',
        'set "NBAI_HINT=reinstall it with install.cmd"'
    )
    $body = @([IO.File]::ReadAllText((Get-LauncherFragmentPath)) -split "\r?\n" | Where-Object { $_ -ne '' })
    return ((($head + $body) -join "`r`n") + "`r`n")
}

function Read-OwnedState([string]$app) {
    $f = PJ $app 'path-state.txt'
    if (Test-Path -LiteralPath $f) { foreach ($l in [IO.File]::ReadAllLines($f)) { if ($l -match '^path-entry-added=(.+)$') { return $Matches[1] } } }
    return $null
}

# --------------------------------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------------------------------
function Parse-Options([string[]]$a) {
    $o = @{ Mode = 'install'; AppDir = $null; Dist = $null; Models = $null; Build = $false; AddPath = $false; NoPath = $false; Help = $false }
    $i = 0
    while ($i -lt $a.Count) {
        $x = $a[$i]
        if ($x -match '^(install|uninstall|status)$') { $o.Mode = $x }
        elseif ($x -eq '--uninstall') { $o.Mode = 'uninstall' }
        elseif ($x -eq '--status') { $o.Mode = 'status' }
        elseif ($x -eq '--build') { $o.Build = $true }
        elseif ($x -eq '--add-to-path') { $o.AddPath = $true }
        elseif ($x -eq '--no-path') { $o.NoPath = $true }
        elseif ($x -match '^(-h|--help|help|/\?|-\?)$') { $o.Help = $true }
        elseif ($x -match '^--(app-dir|dist|models)=(.*)$') {
            $key = @{ 'app-dir' = 'AppDir'; 'dist' = 'Dist'; 'models' = 'Models' }[$Matches[1]]
            $o[$key] = $Matches[2]
        }
        elseif ($x -match '^--(app-dir|dist|models)$') {
            $key = @{ 'app-dir' = 'AppDir'; 'dist' = 'Dist'; 'models' = 'Models' }[$Matches[1]]
            if ($i + 1 -ge $a.Count) { FailUsage ("option " + $x + " needs a value (see --help)") }
            $i++
            $o[$key] = $a[$i]
        }
        else { FailUsage ("unknown option or argument: " + $x + " (see --help)") }
        $i++
    }
    if ($o.AddPath -and $o.NoPath) { FailUsage '--add-to-path and --no-path cannot be combined' }
    return $o
}

function Resolve-Targets($o) {
    $localAppData = $env:LOCALAPPDATA
    $app = $o.AppDir
    if (-not $app) {
        if (-not $localAppData) { Fail 'LOCALAPPDATA is not set; pass --app-dir explicitly.' }
        $app = PJ $localAppData 'Programs\Notebook-AI'
    }
    if ($app.Length -gt 3) { $app = $app.TrimEnd('\', '/') }
    $known = @{}
    if ($env:USERPROFILE) {
        $known['user profile'] = $env:USERPROFILE
        foreach ($sub in 'Desktop', 'Downloads', 'Documents', 'Pictures', 'AppData', 'AppData\Local', 'AppData\Roaming') { $known[$sub] = (PJ $env:USERPROFILE $sub) }
    }
    if ($localAppData) { $known['LocalAppData'] = $localAppData; $known['Programs'] = (PJ $localAppData 'Programs') }
    foreach ($v in 'SystemRoot', 'ProgramFiles', 'ProgramData', 'ProgramFiles(x86)') { $val = [Environment]::GetEnvironmentVariable($v); if ($val) { $known[$v] = $val } }
    $why = Get-UnsafeTargetReason $app $script:RepoDir $known
    if ($why) { Fail ("refusing '" + $app + "' as the application directory: " + $why + '.') }
    return $app
}

function Invoke-Status([string]$app) {
    Say ("Application directory : " + $app)
    Say ("Command               : " + (PJ $app 'bin\notebook-ai.cmd'))
    if (Test-Path -LiteralPath (PJ $app $script:Marker)) {
        Say 'Installation          : present (owned by notebook-ai installer)'
        $inf = PJ $app 'INSTALL.info'
        if (Test-Path -LiteralPath $inf) { foreach ($l in [IO.File]::ReadAllLines($inf)) { Say ('  ' + $l) } }
        $mf = PJ $app 'MANIFEST.sha256'
        if (Test-Path -LiteralPath $mf) {
            $bad = 0
            foreach ($l in [IO.File]::ReadAllLines($mf)) {
                if ($l -match '^([0-9a-f]{64})  (.+)$') {
                    $p = PJ $app $Matches[2]
                    if (-not (Test-Path -LiteralPath $p -PathType Leaf) -or (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Matches[1]) { $bad++ }
                }
            }
            if ($bad -eq 0) { Say 'Integrity             : OK (every installed file matches the recorded SHA-256)' }
            else { Say ('Integrity             : FAILED (' + $bad + ' file(s) changed or missing; reinstall)'); return 1 }
        }
    } elseif (Test-Path -LiteralPath $app) { Say 'Installation          : directory exists but is not a notebook-ai installation' }
    else { Say 'Installation          : not installed' }
    return 0
}

function Invoke-Uninstall([string]$app) {
    $bin = PJ $app 'bin'
    if (Test-Path -LiteralPath (PJ $app $script:Marker)) {
        $added = Read-OwnedState $app
        try { Remove-Item -LiteralPath $app -Recurse -Force } catch { Fail ("could not remove '" + $app + "'. Close Notebook-AI (and any terminal running it) and retry. " + $_.Exception.Message) }
        Say ('Removed application directory: ' + $app)
        if ($added) {
            $raw = Get-UserPathRaw
            if (Test-PathListHasEntry $raw $added) {
                $r = Remove-PathEntry $raw $added
                if ($r.Changed) { Set-UserPathRaw $r.Raw; Say ('Removed ' + $added + ' from your user PATH (it was added by this installer).') }
            }
        } elseif (Test-PathListHasEntry (Get-UserPathRaw) $bin) {
            Say ('Your user PATH still lists ' + $bin + ' (it was not added by this installer, so it was left alone).')
        }
    } elseif (Test-Path -LiteralPath $app) { SayWarn ("'" + $app + "' exists but is not a notebook-ai installation; left untouched.") }
    else { Say ('No application directory at ' + $app) }
    Say "Retained: your projects (.nbai files), exports and evaluation data. Parent directories (Programs, AppData) are never removed."
    return 0
}

function Invoke-Install($o, [string]$app) {
    $plat = Get-HostPlatform
    if (Test-Elevated) { Fail 'refusing to run elevated (Administrator). This is a per-user install; run it from a normal Command Prompt. Gradle is never run elevated.' }
    # Packaged mode: this script sits inside an extracted release distribution (distribution.json + app\).
    $script:Pkg = ((Test-Path -LiteralPath (PJ $script:RepoDir 'distribution.json')) -and (Test-Path -LiteralPath (PJ $script:RepoDir 'app\bin\notebook-ai.bat')))
    if ($script:Pkg -and $o.Build) { Fail '--build is not available in a packaged distribution (it contains no source code).' }
    if ($script:Pkg) {
        Test-PackageIntegrity $script:RepoDir
        $dj = $null
        try { $dj = (Get-Content -LiteralPath (PJ $script:RepoDir 'distribution.json') -Raw | ConvertFrom-Json).targetPlatform } catch { Fail 'distribution.json cannot be read; extract the archive again.' }
        if ($dj -ne $plat) { Fail ("this distribution is for '" + $dj + "', but this machine needs '" + $plat + "'. Use the matching distribution.") }
    }
    $dist = $o.Dist
    if (-not $dist -and $script:Pkg) { $dist = PJ $script:RepoDir 'app' }
    if (-not $dist) {
        $d1 = PJ $script:RepoDir 'build\install\notebook-ai'
        $d2 = PJ $script:RepoDir 'build\install-win-x64\notebook-ai'
        if (Test-Path -LiteralPath (PJ $d1 'PLATFORM')) { $dist = $d1 } else { $dist = $d2 }
    }
    $models = $o.Models
    if (-not $models -and $script:Pkg) { $models = PJ $script:RepoDir 'app\models' }
    if (-not $models) { $models = PJ $script:RepoDir 'models' }
    foreach ($p in $dist, $models) { if (Test-ControlChars $p) { Fail 'a path contains a line break and cannot be handled safely' } }

    if ($o.Build) {
        $g = PJ $script:RepoDir 'gradlew.bat'
        if (-not (Test-Path -LiteralPath $g)) { Fail 'gradlew.bat not found next to install.cmd' }
        Say 'Building (runs Gradle; uses the network to fetch dependencies on first use)...'
        Push-Location -LiteralPath $script:RepoDir
        try { & $g installDist; if ($LASTEXITCODE -ne 0) { Fail 'the build failed' } } finally { Pop-Location }
        if (-not $o.Dist) { $dist = PJ $script:RepoDir 'build\install\notebook-ai' }
    }

    # prerequisites: nothing is modified until all of these pass
    Test-DistValid $dist $plat
    Test-ModelsValid $models
    $java = Find-Java
    if (-not $java) { Fail ("no Java found. Install a Java " + $script:MinJava + " or newer runtime (JRE or JDK) or set JAVA_HOME.") }
    $jmaj = Get-JavaMajor $java
    if ($null -eq $jmaj) { Fail ("could not determine the version of '" + $java + "'") }
    if ($jmaj -lt $script:MinJava) { Fail ("Java " + $jmaj + " found at '" + $java + "'; Java " + $script:MinJava + " or newer is required.") }

    $exists = Test-Path -LiteralPath $app
    if ($exists) {
        if (-not (Test-Path -LiteralPath $app -PathType Container)) { Fail ("'" + $app + "' exists and is not a directory") }
        $item = Get-Item -LiteralPath $app -Force
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { Fail ("'" + $app + "' is a link or junction; refusing (choose the real directory)") }
        $owned = Test-Path -LiteralPath (PJ $app $script:Marker)
        $empty = (@(Get-ChildItem -LiteralPath $app -Force)).Count -eq 0
        if (-not $owned -and -not $empty) { Fail ("'" + $app + "' exists and is not a notebook-ai installation (no ownership marker). Refusing to modify it; choose another --app-dir.") }
    }
    $parent = Split-Path -Parent $app
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }

    $stamp = [string]$PID
    $tmp = PJ $parent ('.notebook-ai.installing.' + $stamp)
    $prev = PJ $parent ('.notebook-ai.previous.' + $stamp)
    if ((Test-Path -LiteralPath $tmp) -or (Test-Path -LiteralPath $prev)) { Fail 'temporary path collision; retry' }
    $swapped = $false
    try {
        New-Item -ItemType Directory -Path $tmp | Out-Null
        New-Item -ItemType File -Path (PJ $tmp $script:Marker) | Out-Null
        New-Item -ItemType Directory -Path (PJ $tmp 'dist') | Out-Null
        foreach ($e in 'bin', 'lib', 'PLATFORM') { Copy-Item -LiteralPath (PJ $dist $e) -Destination (PJ $tmp ('dist\' + $e)) -Recurse }
        New-Item -ItemType Directory -Path (PJ $tmp 'models') | Out-Null
        foreach ($f in 'det.onnx', 'det.yml', 'rec.onnx', 'rec.yml', 'checksums.sha256') { Copy-Item -LiteralPath (PJ $models $f) -Destination (PJ $tmp ('models\' + $f)) }
        New-Item -ItemType Directory -Path (PJ $tmp 'bin') | Out-Null
        [IO.File]::WriteAllText((PJ $tmp 'bin\notebook-ai.cmd'), (New-WrapperText), [Text.Encoding]::ASCII)

        # identity: every copied file must equal its source (SHA-256)
        $srcD = Get-TreeHashes $dist @('bin', 'lib', 'PLATFORM')
        $cpyD = Get-TreeHashes (PJ $tmp 'dist')
        if (-not (Test-SameHashes $srcD $cpyD)) { Fail 'the copied distribution does not match the source; nothing was replaced' }
        $srcM = @{}
        foreach ($f in 'det.onnx', 'det.yml', 'rec.onnx', 'rec.yml', 'checksums.sha256') { $srcM[$f] = (Get-FileHash -LiteralPath (PJ $models $f) -Algorithm SHA256).Hash.ToLowerInvariant() }
        $cpyM = Get-TreeHashes (PJ $tmp 'models')
        if (-not (Test-SameHashes $srcM $cpyM)) { Fail 'the copied models do not match the source; nothing was replaced' }
        # license documents (release distribution: LICENSE, THIRD-PARTY-NOTICES.txt, licenses\; source checkout: LICENSE only)
        $legalDir = PJ $tmp 'legal'
        New-Item -ItemType Directory -Path $legalDir | Out-Null
        foreach ($f in 'LICENSE', 'THIRD-PARTY-NOTICES.txt') { $sp = PJ $script:RepoDir $f; if (Test-Path -LiteralPath $sp -PathType Leaf) { Copy-Item -LiteralPath $sp -Destination (PJ $legalDir $f) } }
        $lsrc = PJ $script:RepoDir 'licenses'
        if (Test-Path -LiteralPath $lsrc -PathType Container) { Copy-Item -LiteralPath $lsrc -Destination (PJ $legalDir 'licenses') -Recurse }
        if (@(Get-ChildItem -LiteralPath $legalDir -Force).Count -eq 0) { Remove-Item -LiteralPath $legalDir -Force; $cpyL = @{} } else { $cpyL = Get-TreeHashes $legalDir }
        $mf = New-Object System.Collections.Generic.List[string]
        foreach ($k in ($cpyD.Keys | Sort-Object)) { $mf.Add($cpyD[$k] + '  dist/' + $k) }
        foreach ($k in ($cpyM.Keys | Sort-Object)) { $mf.Add($cpyM[$k] + '  models/' + $k) }
        foreach ($k in ($cpyL.Keys | Sort-Object)) { $mf.Add($cpyL[$k] + '  legal/' + $k) }
        [IO.File]::WriteAllLines((PJ $tmp 'MANIFEST.sha256'), $mf.ToArray())
        $mainJar = @(Get-ChildItem -LiteralPath (PJ $tmp 'dist\lib') -Filter 'notebook-ai-*.jar')[0]
        $info = @('app=notebook-ai')
        foreach ($l in [IO.File]::ReadAllLines((PJ $dist 'PLATFORM'))) { if ($l -match '^(version|platform)=') { $info += $l } }
        $info += ('main-jar-sha256=' + $cpyD[('lib/' + $mainJar.Name)])
        $info += ('installed-utc=' + [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))
        $info += ('java-at-install=' + $jmaj + ' (' + $java + ')')
        $info += ('command=' + (PJ $app 'bin\notebook-ai.cmd'))
        [IO.File]::WriteAllLines((PJ $tmp 'INSTALL.info'), $info)

        if ((Test-TestMode) -and $env:NBAI_TEST_FAIL_AT -eq 'stage') { Fail 'simulated failure after staging (test seam)' }
        # swap, keeping the previous installation until the new one is in place
        if ($exists) {
            try { Move-Item -LiteralPath $app -Destination $prev }
            catch { Fail ("cannot replace '" + $app + "': a file in it is probably in use. Close Notebook-AI and any terminal running it, then retry. The existing installation is unchanged. (" + $_.Exception.Message + ")") }
        }
        try {
            if ((Test-TestMode) -and $env:NBAI_TEST_FAIL_AT -eq 'swap') { throw 'simulated failure while moving the new installation into place (test seam)' }
            Move-Item -LiteralPath $tmp -Destination $app; $swapped = $true
        }
        catch {
            if ($exists -and -not (Test-Path -LiteralPath $app)) { Move-Item -LiteralPath $prev -Destination $app }
            Fail ("could not put the new installation in place; the previous installation was restored. (" + $_.Exception.Message + ")")
        }
        if ($exists -and (Test-Path -LiteralPath $prev) -and (Test-Path -LiteralPath (PJ $prev $script:Marker))) {
            try { Remove-Item -LiteralPath $prev -Recurse -Force } catch { SayWarn ("could not delete the old copy '" + $prev + "'; it is safe to delete manually.") }
        }
    } finally {
        if (-not $swapped -and (Test-Path -LiteralPath $tmp) -and (Test-Path -LiteralPath (PJ $tmp $script:Marker))) { Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue }
    }

    $binDir = PJ $app 'bin'
    $wrapper = PJ $binDir 'notebook-ai.cmd'
    Say ''
    Say 'notebook-ai installed.'
    Say ('  application : ' + $app)
    Say ('  command     : ' + $wrapper)
    Say ('  platform    : ' + $plat + '   java at install time: ' + $jmaj)
    if ($exists) { Say '  upgraded an existing installation (the old files were replaced as a whole)' }
    Say '  verify      : install.cmd --status'

    # ---- PATH (explicit consent only) ----
    $raw = Get-UserPathRaw
    $already = Test-PathListHasEntry $raw $binDir
    $doAdd = $false
    if ($already) { Say ('  PATH        : ' + $binDir + ' is already on your user PATH') }
    elseif ($o.NoPath) { Say '  PATH        : not modified (--no-path)' }
    elseif ($o.AddPath) { $doAdd = $true }
    elseif (-not (Test-TestMode) -and [Environment]::UserInteractive -and -not [Console]::IsInputRedirected) {
        $ans = Read-Host ('Add ' + $binDir + ' to your user PATH so you can type notebook-ai? [y/N]')
        if ($ans -match '^(y|yes)$') { $doAdd = $true }
    }
    $addedByUs = $false
    if ($doAdd) {
        $r = Add-PathEntry $raw $binDir
        if ($r.Changed) {
            if ($r.Raw.Length -gt 2047) { SayWarn 'your user PATH is longer than 2047 characters; some older programs may truncate it.' }
            Set-UserPathRaw $r.Raw
            $addedByUs = $true
            Say ('  PATH        : added ' + $binDir + ' to your user PATH. Open a NEW Command Prompt for it to take effect (existing windows keep the old PATH).')
        }
    }
    if ($addedByUs) { [IO.File]::WriteAllText((PJ $app 'path-state.txt'), ('path-entry-added=' + $binDir + "`r`n")) }
    elseif ($already -and $script:PreviousPathAdded -and ((Get-EntryKey $script:PreviousPathAdded) -eq (Get-EntryKey $binDir))) {
        [IO.File]::WriteAllText((PJ $app 'path-state.txt'), ('path-entry-added=' + $binDir + "`r`n"))
    }
    if (-not $already -and -not $addedByUs) {
        Say ''
        Say 'NOTE: typing notebook-ai will not work until the command directory is on your PATH. Either:'
        Say ('      run it by full path:   "' + $wrapper + '"')
        Say ('      or add it yourself:    Settings > System > About > Advanced system settings > Environment Variables > User variable "Path" > Edit > New: ' + $binDir)
        Say '      or re-run:             install.cmd --add-to-path'
    }

    # ---- shadowing ----
    if (-not (Test-TestMode)) {
        $hits = @(& where.exe notebook-ai 2>$null)
        if ($hits.Count -gt 0 -and ((Get-EntryKey $hits[0]) -ne (Get-EntryKey $wrapper))) {
            SayWarn ("'notebook-ai' currently resolves to " + $hits[0] + ", not the command just installed.")
            SayWarn ('  Run this installation explicitly with: "' + $wrapper + '"  (the other command was not modified)')
        }
    }
    Say ''
    Say 'Models are bundled in the application directory (no network is used at run time).'
    Say 'Uninstall: install.cmd --uninstall   (your projects and exports are never touched)'
    return 0
}

function Invoke-Main([string[]]$argv) {
    $o = Parse-Options $argv
    if ($o.Help) { Show-Usage; return 0 }
    $app = Resolve-Targets $o
    switch ($o.Mode) {
        'status' { return (Invoke-Status $app) }
        'uninstall' { return (Invoke-Uninstall $app) }
        default {
            $script:PreviousPathAdded = $null
            if (Test-Path -LiteralPath (PJ $app $script:Marker)) { $script:PreviousPathAdded = Read-OwnedState $app }
            return (Invoke-Install $o $app)
        }
    }
}

# Dot-sourcing (tests) loads the functions only.
if ($MyInvocation.InvocationName -eq '.') { return }

try {
    $rc = Invoke-Main @($args)
    exit ([int]$rc)
} catch {
    $m = $_.Exception.Message
    if ($m.StartsWith('NBAI-USAGE:')) { [Console]::Error.WriteLine('install: ' + $m.Substring(11)); exit 2 }
    if ($m.StartsWith('NBAI:')) { [Console]::Error.WriteLine('install: error: ' + $m.Substring(5)); exit 1 }
    [Console]::Error.WriteLine('install: unexpected error: ' + $m)
    exit 1
}
