param(
    [string]$HostAddress = "",
    [int]$Port = 0,
    [string]$VenuePackageRoot = "",
    [string]$RecognitionMode = "",
    [string]$VenvPath = ""
)

$ErrorActionPreference = "Stop"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $RepoRoot

if (-not $HostAddress) {
    $HostAddress = $env:AI_GLASSES_PC_BACKEND_HOST
}
if (-not $HostAddress) {
    $HostAddress = "0.0.0.0"
}
if ($Port -le 0) {
    if ($env:AI_GLASSES_PC_BACKEND_PORT) {
        $Port = [int]$env:AI_GLASSES_PC_BACKEND_PORT
    } else {
        $Port = 8000
    }
}
if (-not $VenuePackageRoot) {
    $VenuePackageRoot = $env:AI_GLASSES_VENUE_PACKAGE_ROOT
}
if (-not $VenuePackageRoot) {
    $VenuePackageRoot = Join-Path $RepoRoot "cloud\data\exhibition_demo"
}
if (-not $RecognitionMode) {
    $RecognitionMode = $env:AI_GLASSES_RECOGNITION_MODE
}
if (-not $RecognitionMode) {
    $DefaultClassifier = Join-Path $RepoRoot "cloud\tmp_scene_recognition_probe\yjdd_hd_booth_classifier_mobilenet_v3_small_v3_gpu_hard_partial\booth_classifier_mobilenet_v3_small_v3_gpu_hard_partial.pt"
    if (-not (Test-Path $DefaultClassifier)) {
        $DefaultClassifier = Join-Path $RepoRoot "cloud\tmp_scene_recognition_probe\yjdd_hd_booth_classifier_mobilenet_v3_small_v2_aug\booth_classifier_mobilenet_v3_small_v2_aug.pt"
    }
    if (-not (Test-Path $DefaultClassifier)) {
        $DefaultClassifier = Join-Path $RepoRoot "cloud\tmp_scene_recognition_probe\yjdd_hd_booth_classifier_mobilenet_v3_small\booth_classifier_mobilenet_v3_small.pt"
    }
    if (Test-Path $DefaultClassifier) {
        $RecognitionMode = "scene_classifier"
    } else {
        $DefaultSceneIndex = Join-Path $RepoRoot "cloud\tmp_scene_recognition_probe\yjdd_hd_scene_retrieval_fusion_resnet50_clip_dinov2_exclude2s\scene_retrieval_fusion_resnet50_clip_vitb32_dinov2_index.npz"
        if (-not (Test-Path $DefaultSceneIndex)) {
            $DefaultSceneIndex = Join-Path $RepoRoot "cloud\tmp_scene_recognition_probe\yjdd_hd_scene_retrieval_hybrid_v1_exclude2s\scene_retrieval_hybrid_v1_index.npz"
        }
        if (Test-Path $DefaultSceneIndex) {
        $RecognitionMode = "scene_retrieval"
        } else {
            $RecognitionMode = "template"
        }
    }
}
if (-not $VenvPath) {
    $SceneVenvPath = Join-Path $RepoRoot ".venv-ocr"
    if ($RecognitionMode -in @("scene_retrieval", "scene_classifier") -and (Test-Path (Join-Path $SceneVenvPath "Scripts\python.exe"))) {
        $VenvPath = $SceneVenvPath
    } else {
        $VenvPath = Join-Path $RepoRoot ".venv"
    }
}

function Test-PythonCudaAvailable {
    param([string]$PythonPath)
    if (-not (Test-Path $PythonPath)) {
        return $false
    }
    try {
        $CudaCheck = & $PythonPath -c "import torch; print('1' if torch.cuda.is_available() else '0')" 2>$null
        return (($CudaCheck | Select-Object -Last 1) -eq "1")
    } catch {
        return $false
    }
}

$env:AI_GLASSES_SERVICE_MODE = "pc_backend"
$env:AI_GLASSES_PC_BACKEND_HOST = $HostAddress
$env:AI_GLASSES_PC_BACKEND_PORT = "$Port"
$env:AI_GLASSES_VENUE_PACKAGE_ROOT = $VenuePackageRoot
$env:AI_GLASSES_RECOGNITION_MODE = $RecognitionMode
if ($RecognitionMode -eq "scene_retrieval") {
    $PythonPath = Join-Path $VenvPath "Scripts\python.exe"
    $CudaAvailable = Test-PythonCudaAvailable -PythonPath $PythonPath
    if (-not $env:TORCH_HOME) {
        $env:TORCH_HOME = "F:\hz\codex\models\torch"
    }
    if (-not $env:HF_HOME) {
        $env:HF_HOME = "F:\hz\codex\models\huggingface"
    }
    if (-not $env:OPENCLIP_CACHE_DIR) {
        $env:OPENCLIP_CACHE_DIR = "F:\hz\codex\models\open_clip"
    }
    if (-not $env:AI_GLASSES_SCENE_RETRIEVAL_DEVICE) {
        $env:AI_GLASSES_SCENE_RETRIEVAL_DEVICE = "auto"
    }
    $FusionIndexRoot = Join-Path $RepoRoot "cloud\tmp_scene_recognition_probe\yjdd_hd_scene_retrieval_fusion_resnet50_clip_dinov2_exclude2s"
    $ResnetIndexRoot = Join-Path $RepoRoot "cloud\tmp_scene_recognition_probe\yjdd_hd_scene_retrieval_resnet50_exclude2s"
    $HybridIndexRoot = Join-Path $RepoRoot "cloud\tmp_scene_recognition_probe\yjdd_hd_scene_retrieval_hybrid_v1_exclude2s"
    $SceneIndexRoot = $HybridIndexRoot
    $DefaultSceneFeature = "hybrid_v1"
    if ($CudaAvailable -and (Test-Path (Join-Path $FusionIndexRoot "scene_retrieval_fusion_resnet50_clip_vitb32_dinov2_index.npz"))) {
        $SceneIndexRoot = $FusionIndexRoot
        $DefaultSceneFeature = "fusion_resnet50_clip_vitb32_dinov2"
    } elseif ($CudaAvailable -and (Test-Path (Join-Path $ResnetIndexRoot "scene_retrieval_torchvision_resnet50_index.npz"))) {
        $SceneIndexRoot = $ResnetIndexRoot
        $DefaultSceneFeature = "torchvision_resnet50"
    }
    if (-not $env:AI_GLASSES_SCENE_RETRIEVAL_INDEX_PATH) {
        if ($DefaultSceneFeature -eq "fusion_resnet50_clip_vitb32_dinov2") {
            $env:AI_GLASSES_SCENE_RETRIEVAL_INDEX_PATH = Join-Path $SceneIndexRoot "scene_retrieval_fusion_resnet50_clip_vitb32_dinov2_index.npz"
        } elseif ($DefaultSceneFeature -eq "torchvision_resnet50") {
            $env:AI_GLASSES_SCENE_RETRIEVAL_INDEX_PATH = Join-Path $SceneIndexRoot "scene_retrieval_torchvision_resnet50_index.npz"
        } else {
            $env:AI_GLASSES_SCENE_RETRIEVAL_INDEX_PATH = Join-Path $SceneIndexRoot "scene_retrieval_hybrid_v1_index.npz"
        }
    }
    if (-not $env:AI_GLASSES_SCENE_RETRIEVAL_METADATA_PATH) {
        if ($DefaultSceneFeature -eq "fusion_resnet50_clip_vitb32_dinov2") {
            $env:AI_GLASSES_SCENE_RETRIEVAL_METADATA_PATH = Join-Path $SceneIndexRoot "scene_retrieval_fusion_resnet50_clip_vitb32_dinov2_metadata.jsonl"
        } elseif ($DefaultSceneFeature -eq "torchvision_resnet50") {
            $env:AI_GLASSES_SCENE_RETRIEVAL_METADATA_PATH = Join-Path $SceneIndexRoot "scene_retrieval_torchvision_resnet50_metadata.jsonl"
        } else {
            $env:AI_GLASSES_SCENE_RETRIEVAL_METADATA_PATH = Join-Path $SceneIndexRoot "scene_retrieval_hybrid_v1_metadata.jsonl"
        }
    }
    if (-not $env:AI_GLASSES_SCENE_RETRIEVAL_BOOTH_COORDINATES_PATH) {
        $env:AI_GLASSES_SCENE_RETRIEVAL_BOOTH_COORDINATES_PATH = Join-Path $RepoRoot "cloud\data\exhibition_demo\localization\booth_coordinates.json"
    }
    if (-not $env:AI_GLASSES_SCENE_RETRIEVAL_FEATURE_EXTRACTOR) {
        $env:AI_GLASSES_SCENE_RETRIEVAL_FEATURE_EXTRACTOR = $DefaultSceneFeature
    }
    if (-not $env:AI_GLASSES_SCENE_RETRIEVAL_MIN_SCORE) {
        $env:AI_GLASSES_SCENE_RETRIEVAL_MIN_SCORE = if ($DefaultSceneFeature -eq "hybrid_v1") { "0.35" } else { "0.82" }
    }
    if (-not $env:AI_GLASSES_SCENE_RETRIEVAL_OK_SCORE) {
        $env:AI_GLASSES_SCENE_RETRIEVAL_OK_SCORE = if ($DefaultSceneFeature -eq "hybrid_v1") { "0.55" } else { "0.9" }
    }
    if (-not $env:AI_GLASSES_SCENE_RETRIEVAL_TIMEOUT_MS) {
        $env:AI_GLASSES_SCENE_RETRIEVAL_TIMEOUT_MS = if ($DefaultSceneFeature -eq "hybrid_v1") { "15000" } else { "60000" }
    }
}
if ($RecognitionMode -eq "scene_classifier") {
    if (-not $env:AI_GLASSES_SCENE_CLASSIFIER_CHECKPOINT_PATH) {
        $DefaultClassifier = Join-Path $RepoRoot "cloud\tmp_scene_recognition_probe\yjdd_hd_booth_classifier_mobilenet_v3_small_v3_gpu_hard_partial\booth_classifier_mobilenet_v3_small_v3_gpu_hard_partial.pt"
        if (-not (Test-Path $DefaultClassifier)) {
            $DefaultClassifier = Join-Path $RepoRoot "cloud\tmp_scene_recognition_probe\yjdd_hd_booth_classifier_mobilenet_v3_small_v2_aug\booth_classifier_mobilenet_v3_small_v2_aug.pt"
        }
        if (-not (Test-Path $DefaultClassifier)) {
            $DefaultClassifier = Join-Path $RepoRoot "cloud\tmp_scene_recognition_probe\yjdd_hd_booth_classifier_mobilenet_v3_small\booth_classifier_mobilenet_v3_small.pt"
        }
        $env:AI_GLASSES_SCENE_CLASSIFIER_CHECKPOINT_PATH = $DefaultClassifier
    }
    if (-not $env:AI_GLASSES_SCENE_CLASSIFIER_DEVICE) {
        $env:AI_GLASSES_SCENE_CLASSIFIER_DEVICE = "auto"
    }
    if (-not $env:AI_GLASSES_SCENE_RETRIEVAL_BOOTH_COORDINATES_PATH) {
        $env:AI_GLASSES_SCENE_RETRIEVAL_BOOTH_COORDINATES_PATH = Join-Path $RepoRoot "cloud\data\exhibition_demo\localization\booth_coordinates.json"
    }
    if (-not $env:AI_GLASSES_SCENE_CLASSIFIER_MIN_CONFIDENCE) {
        $env:AI_GLASSES_SCENE_CLASSIFIER_MIN_CONFIDENCE = "0.20"
    }
    if (-not $env:AI_GLASSES_SCENE_CLASSIFIER_OK_CONFIDENCE) {
        $env:AI_GLASSES_SCENE_CLASSIFIER_OK_CONFIDENCE = "0.50"
    }
}

$Hostname = [System.Net.Dns]::GetHostName()
$LanIps = @()
try {
    $LanIps = [System.Net.Dns]::GetHostAddresses($Hostname) |
        Where-Object {
            $_.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork -and
            -not [System.Net.IPAddress]::IsLoopback($_) -and
            -not $_.ToString().StartsWith("169.254.")
        } |
        ForEach-Object { $_.ToString() } |
        Sort-Object -Unique
} catch {
    $LanIps = @()
}

Write-Host "========== VisionRoute PC Backend =========="
Write-Host "Hostname: $Hostname"
Write-Host "Host bind: $HostAddress"
Write-Host "Port: $Port"
Write-Host "Service mode: $env:AI_GLASSES_SERVICE_MODE"
Write-Host "Recognition mode: $RecognitionMode"
Write-Host "Venue package root: $VenuePackageRoot"
Write-Host "Venv path: $VenvPath"
if ($RecognitionMode -eq "scene_retrieval") {
    Write-Host "Scene retrieval index: $env:AI_GLASSES_SCENE_RETRIEVAL_INDEX_PATH"
    Write-Host "Scene retrieval metadata: $env:AI_GLASSES_SCENE_RETRIEVAL_METADATA_PATH"
    Write-Host "Scene retrieval booths: $env:AI_GLASSES_SCENE_RETRIEVAL_BOOTH_COORDINATES_PATH"
    Write-Host "Scene retrieval feature: $env:AI_GLASSES_SCENE_RETRIEVAL_FEATURE_EXTRACTOR"
    Write-Host "Scene retrieval device: $env:AI_GLASSES_SCENE_RETRIEVAL_DEVICE"
}
if ($RecognitionMode -eq "scene_classifier") {
    Write-Host "Scene classifier checkpoint: $env:AI_GLASSES_SCENE_CLASSIFIER_CHECKPOINT_PATH"
    Write-Host "Scene classifier device: $env:AI_GLASSES_SCENE_CLASSIFIER_DEVICE"
    Write-Host "Scene classifier booths: $env:AI_GLASSES_SCENE_RETRIEVAL_BOOTH_COORDINATES_PATH"
}
Write-Host "Local URL: http://127.0.0.1:$Port"
Write-Host "Local Health URL: http://127.0.0.1:$Port/api/v1/health"
Write-Host "visual-locate URL: http://127.0.0.1:$Port/api/v1/localization/visual-locate"
Write-Host "pairing URL: http://127.0.0.1:$Port/debug/pairing"
Write-Host "debug cards URL: http://127.0.0.1:$Port/debug/cards"
Write-Host "visual debug URL: http://127.0.0.1:$Port/debug/visual-locate"
if ($LanIps.Count -eq 0) {
    Write-Host "LAN IPv4: not detected"
    Write-Host "Troubleshooting: confirm PC is connected to Wi-Fi, then run ipconfig and use the active Wi-Fi IPv4 address."
} else {
    foreach ($Ip in $LanIps) {
        Write-Host "LAN URL: http://$Ip`:$Port"
        Write-Host "LAN Health URL: http://$Ip`:$Port/api/v1/health"
        Write-Host "LAN visual-locate URL: http://$Ip`:$Port/api/v1/localization/visual-locate"
        Write-Host "LAN pairing URL: http://$Ip`:$Port/debug/pairing"
        Write-Host "LAN debug cards URL: http://$Ip`:$Port/debug/cards"
        Write-Host "LAN visual debug URL: http://$Ip`:$Port/debug/visual-locate"
    }
}
Write-Host "Android App baseUrl: http://<PC-LAN-IP>:$Port"
Write-Host "Do not use 10.0.2.2 for a real phone; it is only for Android emulator."
Write-Host "============================================"

$VenvPython = Join-Path $VenvPath "Scripts\python.exe"
if (Test-Path $VenvPython) {
    & $VenvPython -m uvicorn cloud.app.main:app --host $HostAddress --port $Port
} else {
    python -m uvicorn cloud.app.main:app --host $HostAddress --port $Port
}
