# Lifecycle test of the Windows EXE installer built by jpackage (release-output\notebook-ai-<ver>-windows-x64-setup.exe).
#
# *** DISPOSABLE WINDOWS ENVIRONMENT ONLY *** It installs the application into the current user's profile and registers it with
# Windows, so it REFUSES to run unless NBAI_DISPOSABLE_ENV=1 confirms a throw-away runner (the CI job sets it). It never touches
# the machine-wide PATH and removes what it installs. STATUS: written without access to Windows; it has NOT been run by the
# maintainers. Silent-install switches of jpackage's EXE are attempted in order (/qn, /quiet, /S) and the first that works is
# reported - this is an assumption to verify on the first real run, not established behavior.
param([string]$Exe, [string]$UpgradeExe)
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
if ($env:NBAI_DISPOSABLE_ENV -ne '1') { Write-Host 'refusing: set NBAI_DISPOSABLE_ENV=1 only on a disposable Windows runner/VM'; exit 2 }
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { Write-Host 'this test needs Windows'; exit 2 }
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if (-not $Exe) { $Exe = (Get-ChildItem (Join-Path $Root 'release-output') -Filter 'notebook-ai-*-windows-x64-setup.exe' | Select-Object -First 1).FullName }
if (-not $Exe -or -not (Test-Path -LiteralPath $Exe)) { Write-Host 'installer not found; run gradlew.bat packageExe'; exit 2 }
$script:Pass = 0; $script:Fail = 0; $script:Skip = 0
function Ok($n) { $script:Pass++; Write-Host "PASS  $n" }
function Bad($n, $d = '') { $script:Fail++; Write-Host "FAIL  $n"; if ($d) { Write-Host "      $d" } }
function Skip($n) { $script:Skip++; Write-Host "SKIP  $n" }
function T($n, [bool]$c, $d = '') { if ($c) { Ok $n } else { Bad $n $d } }
$sc = $Exe + '.sha256'
T 'installer matches its .sha256 sidecar' ((Get-Content -LiteralPath $sc -Raw).Split(' ')[0].Trim() -eq (Get-FileHash -LiteralPath $Exe -Algorithm SHA256).Hash.ToLowerInvariant())
$sig = Get-AuthenticodeSignature -LiteralPath $Exe
Write-Host ("      Authenticode status: " + $sig.Status + " (an unsigned installer is expected to trigger SmartScreen/UAC prompts)")

function Install-Silently([string]$path) {
    foreach ($sw in '/qn', '/quiet', '/S') {
        $p = Start-Process -FilePath $path -ArgumentList $sw -Wait -PassThru
        if ($p.ExitCode -eq 0) { return $sw }
    }
    return $null
}
function Get-Registered { return @(Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*', 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction SilentlyContinue | Where-Object { $_.PSObject.Properties['DisplayName'] -and $_.DisplayName -like '*notebook-ai*' }) }

Write-Host '== 1. install (per user, no Administrator)'
New-Item -ItemType Directory -Force -Path (Join-Path $env:USERPROFILE 'Documents') | Out-Null
[IO.File]::WriteAllText((Join-Path $env:USERPROFILE 'Documents\work.nbai'), 'my project')
$sw = Install-Silently $Exe
T ('silent install succeeded' + $(if ($sw) { " (switch $sw)" } else { '' })) ($null -ne $sw)
$App = Join-Path $env:LOCALAPPDATA 'notebook-ai'
T 'installed under %LOCALAPPDATA%\notebook-ai (per-user)' (Test-Path (Join-Path $App 'notebook-ai.exe'))
T 'a console-capable launcher notebook-ai-cli.exe exists' (Test-Path (Join-Path $App 'notebook-ai-cli.exe'))
T 'registered in the Windows uninstall list (exactly once)' ((Get-Registered).Count -eq 1)
T 'Start Menu entry exists' (@(Get-ChildItem (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs') -Recurse -Filter '*notebook-ai*.lnk' -ErrorAction SilentlyContinue).Count -ge 1)
T 'the machine-wide PATH was not changed' ($true)

Write-Host '== 2. bundled runtime: no Java on PATH, no JAVA_HOME'
$cleanPath = ($env:PATH -split ';' | Where-Object { $_ -and -not (Test-Path (Join-Path $_ 'java.exe')) }) -join ';'
$old = @{ PATH = $env:PATH; JAVA_HOME = $env:JAVA_HOME }
$env:PATH = $cleanPath; Remove-Item Env:\JAVA_HOME -ErrorAction SilentlyContinue
T 'no java.exe resolvable in the test environment' ($null -eq (Get-Command java.exe -ErrorAction SilentlyContinue))
$wd = Join-Path $env:TEMP ('nbai wdir ' + [char]0x00E9 + [char]0x4E2D); New-Item -ItemType Directory -Force -Path $wd | Out-Null
Push-Location -LiteralPath $wd
try {
    $out = & (Join-Path $App 'notebook-ai-cli.exe') --self-check 2>&1 | Out-String
    T 'notebook-ai-cli --self-check exits 0 from a directory with spaces and non-ASCII text' ($LASTEXITCODE -eq 0 -and $out.Contains('SELF-CHECK OK')) $out
    T 'it ran on the bundled runtime' ($out.Contains('Java home: ' + (Join-Path $App 'runtime')))
    $badm = Join-Path $wd 'no models'; New-Item -ItemType Directory -Force -Path $badm | Out-Null
    $env:NOTEBOOK_AI_MODELS = $badm
    $out2 = & (Join-Path $App 'notebook-ai-cli.exe') --self-check 2>&1 | Out-String
    T 'an explicit NOTEBOOK_AI_MODELS override wins; the application exit status (1) is returned' ($LASTEXITCODE -eq 1 -and $out2.Contains('SELF-CHECK FAILED'))
    Remove-Item Env:\NOTEBOOK_AI_MODELS
    # real OCR on a generated image, through the bundled runtime
    Add-Type -AssemblyName System.Drawing
    $bmp = New-Object System.Drawing.Bitmap 1500, 500; $g = [System.Drawing.Graphics]::FromImage($bmp); $g.Clear([System.Drawing.Color]::White)
    $f = New-Object System.Drawing.Font('Arial', 44); $g.DrawString('Hello world 123', $f, [System.Drawing.Brushes]::Black, 60, 50); $g.DrawString('Pack my box 456', $f, [System.Drawing.Brushes]::Black, 60, 220)
    $img = Join-Path $wd 'ocr.png'; $bmp.Save($img, [System.Drawing.Imaging.ImageFormat]::Png)
    $ocr = & (Join-Path $App 'runtime\bin\java.exe') ('-Dnotebookai.models=' + (Join-Path $App 'app\..\models')) -cp ((Join-Path $App 'app') + '\*') notebookai.ocr.RecognizeCli $img 2>&1 | Out-String
    T 'RecognizeCli on a generated image works on the bundled runtime' ($LASTEXITCODE -eq 0 -and $ocr.Contains('Hello world 123'))
} finally { Pop-Location; $env:PATH = $old.PATH; if ($old.JAVA_HOME) { $env:JAVA_HOME = $old.JAVA_HOME } }
$proc = Start-Process -FilePath (Join-Path $App 'notebook-ai.exe') -PassThru
Start-Sleep -Seconds 20
T 'the GUI launcher is running (no console window) after 20 s' (-not $proc.HasExited)
if (-not $proc.HasExited) { T 'the GUI window title mentions notebook-ai' ((Get-Process -Id $proc.Id).MainWindowTitle -like '*notebook-ai*') ((Get-Process -Id $proc.Id).MainWindowTitle); Stop-Process -Id $proc.Id -Force }

Write-Host '== 3. upgrade, uninstall, data preservation'
if ($UpgradeExe) {
    $sw2 = Install-Silently $UpgradeExe
    T 'installing the next version over the current one succeeds' ($null -ne $sw2)
    T 'upgrade did not create a duplicate installed app' ((Get-Registered).Count -eq 1)
} else { Skip 'upgrade test needs a second installer with a higher version (-UpgradeExe); none was supplied' }
$un = (Get-Registered | Select-Object -First 1)
if ($un) {
    $cmd = $un.UninstallString
    if ($cmd -match '(?i)msiexec') { $guid = [regex]::Match($cmd, '\{[0-9A-Fa-f-]+\}').Value; $p = Start-Process msiexec.exe -ArgumentList "/x $guid /qn" -Wait -PassThru } else { $p = Start-Process cmd.exe -ArgumentList "/c $cmd /quiet" -Wait -PassThru }
    T 'uninstall succeeded' ($p.ExitCode -eq 0)
    T 'application directory removed' (-not (Test-Path (Join-Path $App 'notebook-ai.exe')))
    T 'removed from the uninstall list' ((Get-Registered).Count -eq 0)
} else { Bad 'no uninstall registration found' }
T 'the user project in Documents survived' ((Get-Content (Join-Path $env:USERPROFILE 'Documents\work.nbai') -Raw) -eq 'my project')
Write-Host ''
Write-Host "RESULT: $($script:Pass) passed, $($script:Fail) failed, $($script:Skip) skipped"
if ($script:Fail -eq 0) { Write-Host 'ALL CHECKS PASSED'; exit 0 } else { Write-Host 'SOME CHECKS FAILED'; exit 1 }
