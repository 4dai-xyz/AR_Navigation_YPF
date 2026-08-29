param(
    [string]$PythonPath = "",
    [string]$VenvPath = "",
    [string]$PipCacheDir = "F:\pip-cache"
)

$ErrorActionPreference = "Stop"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
if (-not $VenvPath) {
    $VenvPath = Join-Path $RepoRoot ".venv"
}
if (-not $PythonPath) {
    if (Test-Path "F:\Python311\python.exe") {
        $PythonPath = "F:\Python311\python.exe"
    } else {
        $PythonPath = "python"
    }
}

Write-Host "[setup] repo root: $RepoRoot"
Write-Host "[setup] python: $PythonPath"
Write-Host "[setup] venv: $VenvPath"
Write-Host "[setup] pip cache: $PipCacheDir"

New-Item -ItemType Directory -Force $PipCacheDir | Out-Null
if (-not (Test-Path $VenvPath)) {
    & $PythonPath -m venv $VenvPath
}

$VenvPython = Join-Path $VenvPath "Scripts\python.exe"
& $VenvPython -m pip install --cache-dir $PipCacheDir --upgrade pip
& $VenvPython -m pip install --cache-dir $PipCacheDir -e (Join-Path $RepoRoot "cloud")

Write-Host "[setup] done"
Write-Host "[setup] next: .\cloud\tools\run_pc_backend.ps1"
