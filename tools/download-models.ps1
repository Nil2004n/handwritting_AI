# Download pinned PaddleOCR PP-OCRv6_small ONNX assets and verify them against models\checksums.sha256.
# Windows counterpart of tools/download-models.sh (same pinned commits, same committed checksums; nothing new is trusted).
# Needs network access ONLY when a file is missing or invalid. Run as your normal user:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\download-models.ps1
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$DetSha = '28fe5895c24fd108c19eb3e8479f4ab385fbfc62'
$RecSha = 'b8f84f0b80c529de40b4fbb3544b84fa7233a513'
$DetPinned = "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/$DetSha"
$RecPinned = "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/$RecSha"
$DetBranch = 'https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main'
$RecBranch = 'https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main'

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$models = Join-Path $root 'models'
$sums = Join-Path $models 'checksums.sha256'
if (-not (Test-Path -LiteralPath $sums)) { throw "models\checksums.sha256 is missing; it is part of the repository and must not be regenerated." }

$want = @{}
foreach ($line in [IO.File]::ReadAllLines($sums)) {
    if ($line -match '^([0-9a-fA-F]{64})\s+\*?(\S.*)$') { $want[$Matches[2].Trim()] = $Matches[1].ToLowerInvariant() }
}

function Test-Valid([string]$name) {
    $p = Join-Path $models $name
    if (-not (Test-Path -LiteralPath $p -PathType Leaf)) { return $false }
    return ((Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant() -eq $want[$name])
}

function Get-One([string]$pinned, [string]$branch, [string]$dest) {
    if (Test-Valid $dest) { Write-Host " keeping existing valid $dest"; return }
    $tmp = Join-Path $models ($dest + '.part')
    if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force }
    try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12 } catch { }
    try { Invoke-WebRequest -UseBasicParsing -Uri $pinned -OutFile $tmp }
    catch { Write-Host " pinned revision failed for $dest, trying branch ref"; Invoke-WebRequest -UseBasicParsing -Uri $branch -OutFile $tmp }
    Move-Item -LiteralPath $tmp -Destination (Join-Path $models $dest) -Force
    Start-Sleep -Seconds 10   # the CDN rate-limits rapid successive requests
}

if (-not (Test-Path -LiteralPath $models)) { New-Item -ItemType Directory -Path $models | Out-Null }
Get-One "$DetPinned/inference.onnx" "$DetBranch/inference.onnx" 'det.onnx'
Get-One "$DetPinned/inference.yml"  "$DetBranch/inference.yml"  'det.yml'
Get-One "$RecPinned/inference.onnx" "$RecBranch/inference.onnx" 'rec.onnx'
Get-One "$RecPinned/inference.yml"  "$RecBranch/inference.yml"  'rec.yml'

$bad = @()
foreach ($n in 'det.onnx', 'det.yml', 'rec.onnx', 'rec.yml') { if (-not (Test-Valid $n)) { $bad += $n } }
if ($bad.Count -gt 0) { throw ("checksum verification FAILED for: " + ($bad -join ', ') + ". Delete those files and run this script again.") }
Write-Host "Models ready in $models (all checksums verified)"
