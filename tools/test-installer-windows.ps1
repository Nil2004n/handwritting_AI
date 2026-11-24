# Lifecycle test of the Windows EXE installer built by jpackage (release-output\notebook-ai-<ver>-windows-x64-setup.exe).
#
# *** DISPOSABLE WINDOWS ENVIRONMENT ONLY *** It installs into the current user's profile and registers with Windows, so it REFUSES to
# run unless NBAI_DISPOSABLE_ENV=1 confirms a throw-away runner/VM (the CI job sets it). It never touches the machine-wide PATH and
# uninstalls what it installs.
#
# STATUS: written without access to Windows and, until a real runner executes it, UNVERIFIED. The silent-install switches of
# jpackage's EXE are not documented: they are tried in turn (with a timeout each) and the one that works is reported.
# Observations that are not pass/fail (privilege, file-lock behavior, SmartScreen) are printed as OBSERVED lines.
#
#   -Exe          the installer under test (default: release-output\notebook-ai-*-windows-x64-setup.exe, lowest version)
#   -UpgradeExe   an installer with a HIGHER version, for the upgrade tests (the CI job builds one with -PnativeAppVersion)
#   -SkipStandardUser / -StandardUserOnly   run the main lifecycle without / only the non-Administrator install section (CI runs them as
#                 separate steps so a problem in the account-creation section can never hide the main results)
param([string]$Exe, [string]$UpgradeExe, [switch]$StandardUserOnly, [switch]$SkipStandardUser)
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
if ($env:NBAI_DISPOSABLE_ENV -ne '1') { Write-Host 'refusing: set NBAI_DISPOSABLE_ENV=1 only on a disposable Windows runner/VM'; exit 2 }
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { Write-Host 'this test needs Windows'; exit 2 }
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if (-not $Exe) { $Exe = (Get-ChildItem (Join-Path $Root 'release-output') -Filter 'notebook-ai-*-windows-x64-setup.exe' | Sort-Object Name | Select-Object -First 1).FullName }
if (-not $Exe -or -not (Test-Path -LiteralPath $Exe)) { Write-Host 'installer not found; run gradlew.bat packageExe'; exit 2 }
$UpgradeCode = '{6AC77948-495E-4E24-B379-4D90790B06C7}'     # the committed jpackage --win-upgrade-uuid
$script:Pass = 0; $script:Fail = 0; $script:Skip = 0
function Ok($n) { $script:Pass++; Write-Host "PASS  $n" }
function Bad($n, $d = '') { $script:Fail++; Write-Host "FAIL  $n"; if ($d) { Write-Host "      $d" } }
function Skip($n) { $script:Skip++; Write-Host "SKIP  $n" }
function Obs($n) { Write-Host "OBSERVED  $n" }
function T($n, [bool]$c, $d = '') { if ($c) { Ok $n } else { Bad $n $d } }
function Sha($p) { return (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant() }

# Runs a program with a timeout; never hangs the job (a GUI wizard shown by a wrong switch is killed).
function Run-Exe([string]$path, [string]$arguments, [int]$timeoutSec = 600) {
    $p = if ($arguments) { Start-Process -FilePath $path -ArgumentList $arguments -PassThru } else { Start-Process -FilePath $path -PassThru }
    if (-not $p.WaitForExit($timeoutSec * 1000)) { try { $p.Kill() } catch { }; return @{ Exit = -999; TimedOut = $true } }
    return @{ Exit = $p.ExitCode; TimedOut = $false }
}
function Install-Silently([string]$path, [string]$tag) {
    $log = Join-Path $env:TEMP "nbai-install-$tag.log"
    foreach ($a in @("/qn /l*v `"$log`"", '/quiet /norestart', '/S')) {
        $r = Run-Exe $path $a 240
        Obs ("installer switch '$a' -> exit " + $r.Exit + $(if ($r.TimedOut) { ' (timed out and was killed)' } else { '' }))
        if (-not $r.TimedOut -and ($r.Exit -eq 0 -or $r.Exit -eq 3010)) { return $a }
    }
    if (Test-Path $log) { Copy-Item $log (Join-Path $Root "build\ci\msi-$tag.log") -ErrorAction SilentlyContinue }
    return $null
}
function Get-Prop($o, [string]$n) { $p = $o.PSObject.Properties[$n]; if ($p) { return $p.Value } else { return '' } }   # StrictMode-safe optional property
function Get-Registered {
    $keys = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*', 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*', 'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*'
    return @(Get-ItemProperty $keys -ErrorAction SilentlyContinue | Where-Object { $_.PSObject.Properties['DisplayName'] -and $_.DisplayName -like '*notebook-ai*' } | ForEach-Object { [pscustomobject]@{ Name = $_.DisplayName; Version = (Get-Prop $_ 'DisplayVersion'); Key = $_.PSPath; Uninstall = (Get-Prop $_ 'UninstallString') } })
}
function Get-PeSubsystem([string]$file) {      # 2 = Windows GUI (no console window), 3 = console
    $b = [IO.File]::ReadAllBytes($file); $pe = [BitConverter]::ToInt32($b, 0x3C); return [BitConverter]::ToUInt16($b, $pe + 0x5C)
}
# Runs a native program and returns its combined output and exit code. 'Stop' would otherwise turn EXPECTED stderr output
# (e.g. the application's own failure message) into a terminating PowerShell error.
function Invoke-Native([string]$file, [string[]]$arguments = @()) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $o = (& $file @arguments 2>&1 | ForEach-Object { "$_" }) -join "`n"; $c = $LASTEXITCODE } finally { $ErrorActionPreference = $old }
    return @{ Out = [string]$o; Exit = $c }
}
# Runs a command line as ANOTHER (standard) local user through a one-shot scheduled task and returns its exit code and output.
# (Start-Process -Credential failed on the CI runner image; a scheduled task with a password logon works for a non-elevated token.)
function Run-AsUser([string]$user, [string]$plainPw, [string]$cmdline, [int]$timeoutSec = 300) {
    $tag = [guid]::NewGuid().ToString('N').Substring(0, 8)
    $dir = 'C:\Users\Public\nbai-std'; $out = Join-Path $dir "out-$tag.txt"; $rcf = Join-Path $dir "rc-$tag.txt"; $wrapper = Join-Path $dir "run-$tag.cmd"
    [IO.File]::WriteAllText($wrapper, ("@echo off`r`n" + $cmdline + " > `"" + $out + "`" 2>&1`r`necho %ERRORLEVEL% > `"" + $rcf + "`"`r`n"), [Text.Encoding]::ASCII)
    $name = "nbai-std-$tag"
    $action = New-ScheduledTaskAction -Execute 'cmd.exe' -Argument ('/c "' + $wrapper + '"') -WorkingDirectory $dir
    Register-ScheduledTask -TaskName $name -Action $action -User ($env:COMPUTERNAME + '\' + $user) -Password $plainPw -RunLevel Limited | Out-Null
    try {
        Start-ScheduledTask -TaskName $name
        $sw = [Diagnostics.Stopwatch]::StartNew()
        while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) { Start-Sleep -Seconds 3; if ((Test-Path $rcf) -and ((Get-ScheduledTask -TaskName $name).State -ne 'Running')) { break } }
        $rc = if (Test-Path $rcf) { [int]((Get-Content $rcf -Raw).Trim()) } else { -999 }
        return @{ Exit = $rc; Out = $(if (Test-Path $out) { [string](Get-Content $out -Raw) } else { '' }) }
    } finally { Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue }
}
function Strip-Java { $script:old = @{ PATH = $env:PATH; JAVA_HOME = $env:JAVA_HOME }; $env:PATH = (($env:PATH -split ';') | Where-Object { $_ -and -not (Test-Path (Join-Path $_ 'java.exe')) }) -join ';'; Remove-Item Env:\JAVA_HOME -ErrorAction SilentlyContinue }
function Restore-Java { $env:PATH = $script:old.PATH; if ($script:old.JAVA_HOME) { $env:JAVA_HOME = $script:old.JAVA_HOME } }

Write-Host '== 0. environment and installer identity'
$os = Get-CimInstance Win32_OperatingSystem
Obs ("OS: " + $os.Caption + " " + $os.Version + " (build " + $os.BuildNumber + "), PowerShell " + $PSVersionTable.PSVersion)
$elevated = (New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
Obs ("this session is elevated (Administrator): $elevated  - UAC behaviour of the installer therefore cannot be observed on this runner")
$sc = $Exe + '.sha256'
T 'installer matches its .sha256 sidecar' ((Get-Content -LiteralPath $sc -Raw).Split(' ')[0].Trim() -eq (Sha $Exe))
$sig = Get-AuthenticodeSignature -LiteralPath $Exe
Obs ("Authenticode status of the installer: " + $sig.Status + " (SmartScreen is not exercised by a CI runner)")
Obs ("installer: " + (Split-Path -Leaf $Exe) + ", " + (Get-Item $Exe).Length + " bytes, sha256 " + (Sha $Exe))
New-Item -ItemType Directory -Force -Path (Join-Path $Root 'build\ci') | Out-Null
$Docs = Join-Path $env:USERPROFILE 'Documents'; New-Item -ItemType Directory -Force -Path $Docs | Out-Null
[IO.File]::WriteAllText((Join-Path $Docs 'work.nbai'), 'my project')
$Prefs = Join-Path $env:APPDATA 'notebook-ai'; New-Item -ItemType Directory -Force -Path $Prefs | Out-Null
[IO.File]::WriteAllText((Join-Path $Prefs 'prefs.txt'), 'my preferences')
T 'before: no notebook-ai is registered on this machine' (@(Get-Registered).Count -eq 0)

if (-not $SkipStandardUser) {
Write-Host '== 1a. standard-user (NON-Administrator) install: the per-user claim, tested where it can be'
$stdUser = 'nbaitest'
try {
    $pwPlain = [guid]::NewGuid().ToString('N') + 'aA1!'
    $sec = ConvertTo-SecureString $pwPlain -AsPlainText -Force
    if (Get-LocalUser -Name $stdUser -ErrorAction SilentlyContinue) { Remove-LocalUser -Name $stdUser }
    New-LocalUser -Name $stdUser -Password $sec -AccountNeverExpires -PasswordNeverExpires -UserMayNotChangePassword | Out-Null
    # 'Performance Log Users' carries the "log on as a batch job" right a scheduled task needs; it does NOT make the account an administrator.
    Add-LocalGroupMember -Group 'Performance Log Users' -Member $stdUser
    $pub = 'C:\Users\Public\nbai-std'; New-Item -ItemType Directory -Force -Path $pub | Out-Null
    Copy-Item -LiteralPath $Exe -Destination (Join-Path $pub 'setup.exe') -Force
    [void](Invoke-Native 'icacls.exe' @($pub, '/grant', 'Users:(OI)(CI)M'))
    $members = @(Get-LocalGroupMember -Group 'Administrators' | ForEach-Object { $_.Name })
    T 'the test account is a standard user (not a member of Administrators)' (@($members | Where-Object { $_ -like ('*\' + $stdUser) }).Count -eq 0)
    $r = Run-AsUser $stdUser $pwPlain ('"' + (Join-Path $pub 'setup.exe') + '" /qn')
    Obs ("standard-user installer exit code: " + $r.Exit)
    $uApp = 'C:\Users\' + $stdUser + '\AppData\Local\notebook-ai'
    T 'a standard user (no Administrator rights) can install; files go to that user''s %LOCALAPPDATA%' (Test-Path (Join-Path $uApp 'notebook-ai.exe')) ("exit " + $r.Exit + " " + $r.Out)
    T 'the standard-user install created NO machine-wide (HKLM) registration' (@(Get-Registered | Where-Object { $_.Key -like '*HKEY_LOCAL_MACHINE*' }).Count -eq 0) ((Get-Registered) | Out-String)
    $hive = 'NbaiStdHive'; $uCode = ''
    [void](Invoke-Native 'reg.exe' @('load', ('HKU\' + $hive), ('C:\Users\' + $stdUser + '\NTUSER.DAT')))
    try {
        $ents = @(Get-ItemProperty ('Registry::HKEY_USERS\' + $hive + '\Software\Microsoft\Windows\CurrentVersion\Uninstall\*') -ErrorAction SilentlyContinue | Where-Object { $_.PSObject.Properties['DisplayName'] -and $_.DisplayName -like '*notebook-ai*' })
        T 'the product is registered in THAT USER''s own registry hive (per-user install)' ($ents.Count -eq 1) ($ents | Out-String)
        if ($ents.Count -ge 1) { $uCode = [regex]::Match($ents[0].PSChildName, '\{[0-9A-Fa-f-]+\}').Value }
    } finally { [gc]::Collect(); Start-Sleep -Seconds 2; [void](Invoke-Native 'reg.exe' @('unload', ('HKU\' + $hive))) }
    if (Test-Path (Join-Path $uApp 'notebook-ai-cli.exe')) {
        $r2 = Run-AsUser $stdUser $pwPlain ('"' + (Join-Path $uApp 'notebook-ai-cli.exe') + '" --self-check')
        T 'the standard user can run notebook-ai-cli --self-check (bundled runtime)' ($r2.Exit -eq 0 -and $r2.Out.Contains('SELF-CHECK OK')) ("exit " + $r2.Exit)
    }
    if ($uCode) {
        $r3 = Run-AsUser $stdUser $pwPlain ('msiexec.exe /x ' + $uCode + ' /qn')
        T 'the standard user can uninstall' ($r3.Exit -eq 0 -or $r3.Exit -eq 3010) ("exit " + $r3.Exit)
        T 'uninstall removed the per-user application directory' (-not (Test-Path (Join-Path $uApp 'notebook-ai.exe')))
    } else { Skip 'standard-user uninstall not attempted (product code not found in the user hive)' }
} catch { Skip ("standard-user install could NOT be tested on this machine (harness problem, NOT a pass): " + $_.Exception.Message) }
finally {
    try { Get-CimInstance Win32_UserProfile | Where-Object { $_.LocalPath -eq ('C:\Users\' + $stdUser) } | Remove-CimInstance -ErrorAction SilentlyContinue } catch { }
    try { if (Get-LocalUser -Name $stdUser -ErrorAction SilentlyContinue) { Remove-LocalUser -Name $stdUser } } catch { }
    Remove-Item -LiteralPath 'C:\Users\Public\nbai-std' -Recurse -Force -ErrorAction SilentlyContinue
}
T 'machine is clean again after the standard-user section (nothing registered machine-wide)' (@(Get-Registered).Count -eq 0)
}
if ($StandardUserOnly) { Write-Host ''; Write-Host "RESULT: $($script:Pass) passed, $($script:Fail) failed, $($script:Skip) skipped (standard-user section only)"; if ($script:Fail -eq 0) { exit 0 } else { exit 1 } }

Write-Host '== 1. install (per user)'
$sw = Install-Silently $Exe 'first'
T ('silent install succeeded' + $(if ($sw) { " (switch: $sw)" } else { '' })) ($null -ne $sw)
$reg = @(Get-Registered)
T 'registered in the Windows uninstall list exactly once' ($reg.Count -eq 1) ($reg | Out-String)
if ($reg.Count -ge 1) {
    $scope = if ($reg[0].Key -like '*HKEY_LOCAL_MACHINE*') { 'HKLM (machine-wide registration)' } elseif ($reg[0].Key -like '*HKEY_CURRENT_USER*') { 'HKCU (per-user registration)' } else { $reg[0].Key }
    Obs ("registration scope in THIS ELEVATED session: $scope. A per-user installer run by an Administrator can register machine-wide; per-user behaviour for a standard user is tested in section 1a.")
    try { $code = [regex]::Match($reg[0].Key, '\{[0-9A-Fa-f-]+\}').Value; $wiA = New-Object -ComObject WindowsInstaller.Installer; Obs ("Windows Installer AssignmentType for " + $code + ": " + $wiA.ProductInfo($code, 'AssignmentType') + " (0 = per-user, 1 = per-machine)") } catch { Obs "could not read AssignmentType: $($_.Exception.Message)" }
    Obs ("registered name/version: " + $reg[0].Name + " " + $reg[0].Version)
}
$App = Join-Path $env:LOCALAPPDATA 'notebook-ai'
T 'installed under %LOCALAPPDATA%\notebook-ai' (Test-Path (Join-Path $App 'notebook-ai.exe'))
T 'notebook-ai-cli.exe (console launcher) is installed' (Test-Path (Join-Path $App 'notebook-ai-cli.exe'))
T 'the runtime, app, JavaFX, models and legal documents are installed' ((Test-Path (Join-Path $App 'runtime\bin\java.exe')) -and (Test-Path (Join-Path $App 'app\notebook-ai-0.1.0.jar')) -and (Test-Path (Join-Path $App 'fx\javafx-graphics-21.0.7-win.jar')) -and (Test-Path (Join-Path $App 'models\det.onnx')) -and (Test-Path (Join-Path $App 'legal\THIRD-PARTY-NOTICES.txt')))
T 'installed models match models\checksums.sha256' (@((Get-Content (Join-Path $App 'models\checksums.sha256')) | Where-Object { $_ -match '^([0-9a-f]{64})\s+(\S+)$' } | Where-Object { (Sha (Join-Path $App ('models\' + $Matches[2]))) -ne $Matches[1] }).Count -eq 0)
T 'a Start Menu shortcut exists' (@(Get-ChildItem (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs') -Recurse -Filter '*notebook-ai*.lnk' -ErrorAction SilentlyContinue).Count -ge 1)
T 'notebook-ai.exe is a GUI-subsystem program (starts without a console window)' ((Get-PeSubsystem (Join-Path $App 'notebook-ai.exe')) -eq 2)
T 'notebook-ai-cli.exe is a console-subsystem program (usable from CMD)' ((Get-PeSubsystem (Join-Path $App 'notebook-ai-cli.exe')) -eq 3)
Obs ("the machine-wide PATH is not modified by this installer; the console command is: `"" + (Join-Path $App 'notebook-ai-cli.exe') + "`" (no PATH integration is implemented)")

Write-Host '== 2. bundled runtime: no Java on PATH, no JAVA_HOME'
Strip-Java
$wd = Join-Path $env:TEMP ('nbai wdir ' + [char]0x00E9 + [char]0x4E2D + [char]0x6587); New-Item -ItemType Directory -Force -Path $wd | Out-Null
Push-Location -LiteralPath $wd
try {
    T 'no java.exe is resolvable for this test' ($null -eq (Get-Command java.exe -ErrorAction SilentlyContinue))
    $r = Invoke-Native (Join-Path $App 'notebook-ai-cli.exe') @('--self-check'); $out = $r.Out; $rc = $r.Exit
    T 'notebook-ai-cli.exe --self-check exits 0 (cwd with spaces and non-ASCII characters)' ($rc -eq 0 -and $out.Contains('SELF-CHECK OK')) "exit=$rc $out"
    T 'it ran on the bundled runtime' ($out.Contains('Java home: ' + (Join-Path $App 'runtime')))
    T 'models were found from outside the installation directory (the working directory is elsewhere)' ($out.Contains('Checksums: OK'))
    $r = Invoke-Native (Join-Path $App 'notebook-ai-cli.exe') @('--self-check', 'extra-argument'); $out2 = $r.Out; $rc2 = $r.Exit
    T 'extra arguments after --self-check are accepted and the exit status is still 0' ($rc2 -eq 0)
    $env:NOTEBOOK_AI_MODELS = (Join-Path $wd 'no models here')
    $r = Invoke-Native (Join-Path $App 'notebook-ai-cli.exe') @('--self-check'); $out3 = $r.Out; $rc3 = $r.Exit
    T 'an explicit NOTEBOOK_AI_MODELS override wins and the application exit status (1) is returned' ($rc3 -eq 1 -and $out3.Contains('SELF-CHECK FAILED')) "exit=$rc3"
    $mc = Join-Path $wd ('models ' + [char]0x00FC + [char]0x4E2D); Copy-Item -Recurse -LiteralPath (Join-Path $App 'models') -Destination $mc
    $env:NOTEBOOK_AI_MODELS = $mc
    $r = Invoke-Native (Join-Path $App 'notebook-ai-cli.exe') @('--self-check'); $out4 = $r.Out; $rc4 = $r.Exit
    T 'a models override in a directory with spaces and non-ASCII characters works' ($rc4 -eq 0 -and $out4.Contains('SELF-CHECK OK')) "exit=$rc4"
    Remove-Item Env:\NOTEBOOK_AI_MODELS
    # real OCR on a generated image through the bundled runtime (the GUI launcher cannot print, so the CLI class runs on the bundled java)
    Add-Type -AssemblyName System.Drawing
    $bmp = New-Object System.Drawing.Bitmap 1500, 500; $g = [System.Drawing.Graphics]::FromImage($bmp); $g.Clear([System.Drawing.Color]::White)
    $f = New-Object System.Drawing.Font('Arial', 44); $g.DrawString('Hello world 123', $f, [System.Drawing.Brushes]::Black, 60, 50); $g.DrawString('Pack my box 456', $f, [System.Drawing.Brushes]::Black, 60, 220)
    $img = Join-Path $wd 'ocr input.png'; $bmp.Save($img, [System.Drawing.Imaging.ImageFormat]::Png); $g.Dispose(); $bmp.Dispose()
    $r = Invoke-Native (Join-Path $App 'runtime\bin\java.exe') @(('-Dnotebookai.models=' + (Join-Path $App 'models')), '-cp', ((Join-Path $App 'app') + '\*'), 'notebookai.ocr.RecognizeCli', $img); $ocr = $r.Out
    T 'RecognizeCli (real OCR) on a generated image runs on the bundled runtime and reads the text' ($r.Exit -eq 0 -and $ocr.Contains('Hello world 123') -and $ocr.Contains('Pack my box 456')) $ocr
} finally { Pop-Location; Restore-Java }

Write-Host '== 3. GUI launch (what a runner allows; --self-check is NOT a GUI acceptance test)'
Strip-Java
$gui = Start-Process -FilePath (Join-Path $App 'notebook-ai.exe') -PassThru
Start-Sleep -Seconds 25
T 'the GUI launcher is still running after 25 s (did not crash)' (-not $gui.HasExited) ("exit code " + $(if ($gui.HasExited) { $gui.ExitCode } else { 'n/a' }))
if (-not $gui.HasExited) {
    $gui.Refresh(); $title = $gui.MainWindowTitle
    T 'the application window exists and carries the expected title' ($title -like '*notebook-ai*') "title='$title'"
    Obs ("the GUI process stays running for the file-lock test below; window title: '$title'")
}
Restore-Java

Write-Host '== 4. upgrade identity and running-app (file lock) behavior'
if ($UpgradeExe -and (Test-Path -LiteralPath $UpgradeExe)) {
    $before = @(Get-Registered)
    if ($before.Count -ne 1) { Bad 'upgrade tests need exactly one registered installation to start from' ($before | Out-String) }
    $bv = if ($before.Count -ge 1) { $before[0].Version } else { '' }
    $r = Run-Exe $UpgradeExe '/qn' 300
    Obs ("OBSERVED file-lock case: upgrade installer run while notebook-ai.exe was RUNNING -> exit " + $r.Exit + $(if ($r.TimedOut) { ' (timed out)' } else { '' }))
    $mid = @(Get-Registered)
    Obs ("registered after that attempt: " + (($mid | ForEach-Object { $_.Name + ' ' + $_.Version }) -join '; '))
    T 'whatever happened, exactly one notebook-ai remains registered (no duplicate, no orphan)' ($mid.Count -eq 1)
    Strip-Java
    $r = Invoke-Native (Join-Path $App 'notebook-ai-cli.exe') @('--self-check'); $o = $r.Out
    T 'the installation is still usable after the upgrade attempt while running' ($r.Exit -eq 0 -and $o.Contains('SELF-CHECK OK'))
    Restore-Java
    if (-not $gui.HasExited) { Stop-Process -Id $gui.Id -Force -ErrorAction SilentlyContinue; Start-Sleep -Seconds 3 }
    if ($mid.Count -eq 1 -and $mid[0].Version -eq $bv) {
        $r2 = Run-Exe $UpgradeExe '/qn' 300
        Obs ("upgrade attempt #2 with the app closed -> exit " + $r2.Exit)
    }
    $after = @(Get-Registered)
    T 'after the upgrade there is exactly ONE installed notebook-ai (stable upgrade identity)' ($after.Count -eq 1) ($after | Out-String)
    T 'the installed version changed to the higher version' ($after.Count -eq 1 -and $after[0].Version -ne $bv) ("before=" + $bv + " after=" + $(if ($after.Count) { $after[0].Version } else { 'none' }))
    try {
        $wi = New-Object -ComObject WindowsInstaller.Installer
        $related = @($wi.RelatedProducts($UpgradeCode))
        T 'Windows Installer reports exactly one product for the committed UpgradeCode' ($related.Count -eq 1) ("products: " + ($related -join ', '))
    } catch { Skip "could not query WindowsInstaller RelatedProducts: $($_.Exception.Message)" }
    Strip-Java
    $r = Invoke-Native (Join-Path $App 'notebook-ai-cli.exe') @('--self-check'); $o2 = $r.Out
    T 'the upgraded application passes --self-check on the bundled runtime' ($r.Exit -eq 0 -and $o2.Contains('SELF-CHECK OK'))
    Restore-Java
    T 'user project and preferences survived the upgrade' ((Get-Content (Join-Path $Docs 'work.nbai') -Raw) -eq 'my project' -and (Get-Content (Join-Path $Prefs 'prefs.txt') -Raw) -eq 'my preferences')
} else { Skip 'upgrade and file-lock tests need a higher-version installer (-UpgradeExe); none was supplied' ; if (-not $gui.HasExited) { Stop-Process -Id $gui.Id -Force -ErrorAction SilentlyContinue } }

Write-Host '== 5. uninstall'
$un = @(Get-Registered) | Select-Object -First 1
if ($un) {
    if ($un.Uninstall -match '(?i)msiexec') { $guid = [regex]::Match($un.Uninstall, '\{[0-9A-Fa-f-]+\}').Value; $r = Run-Exe 'msiexec.exe' "/x $guid /qn" 300 }
    else { $r = Run-Exe 'cmd.exe' ("/c " + $un.Uninstall + " /quiet") 300 }
    T 'uninstall succeeded' ($r.Exit -eq 0 -or $r.Exit -eq 3010) ("exit " + $r.Exit)
    T 'the application directory is gone' (-not (Test-Path (Join-Path $App 'notebook-ai.exe')))
    T 'removed from the uninstall list' (@(Get-Registered).Count -eq 0)
    T 'the Start Menu shortcut is gone' (@(Get-ChildItem (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs') -Recurse -Filter '*notebook-ai*.lnk' -ErrorAction SilentlyContinue).Count -eq 0)
} else { Bad 'no uninstall registration found' }
T 'the user project in Documents survived uninstall' ((Get-Content (Join-Path $Docs 'work.nbai') -Raw) -eq 'my project')
T 'user preferences (%APPDATA%\notebook-ai) survived uninstall' ((Get-Content (Join-Path $Prefs 'prefs.txt') -Raw) -eq 'my preferences')
Write-Host ''
Write-Host "RESULT: $($script:Pass) passed, $($script:Fail) failed, $($script:Skip) skipped"
if ($script:Fail -eq 0) { Write-Host 'ALL CHECKS PASSED'; exit 0 } else { Write-Host 'SOME CHECKS FAILED'; exit 1 }
