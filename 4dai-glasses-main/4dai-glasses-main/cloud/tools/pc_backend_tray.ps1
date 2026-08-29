param(
    [string]$HostAddress = "",
    [int]$Port = 0,
    [string]$VenuePackageRoot = "",
    [string]$RecognitionMode = "",
    [string]$VenvPath = "",
    [int]$RefreshSeconds = 3,
    [switch]$ShowBackendConsole,
    [switch]$ValidateOnly
)

$ErrorActionPreference = "Stop"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$BackendScript = Join-Path $PSScriptRoot "run_pc_backend.ps1"
$LogDir = Join-Path $RepoRoot "cloud\logs"
$PidFile = Join-Path $LogDir "pc_backend_tray.pid"

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
if (-not $RecognitionMode) {
    $RecognitionMode = $env:AI_GLASSES_RECOGNITION_MODE
}
if (-not $RecognitionMode) {
    $RecognitionMode = "scene_classifier"
}
if (-not $VenuePackageRoot) {
    $VenuePackageRoot = $env:AI_GLASSES_VENUE_PACKAGE_ROOT
}
if (-not $VenvPath) {
    $SceneVenvPath = Join-Path $RepoRoot ".venv-ocr"
    if ($RecognitionMode -in @("scene_retrieval", "scene_classifier") -and (Test-Path (Join-Path $SceneVenvPath "Scripts\python.exe"))) {
        $VenvPath = $SceneVenvPath
    } else {
        $VenvPath = Join-Path $RepoRoot ".venv"
    }
}

$LocalBaseUrl = "http://127.0.0.1:$Port"
$HealthUrl = "$LocalBaseUrl/api/v1/health"
$CardsUrl = "$LocalBaseUrl/debug/cards"
$PairingUrl = "$LocalBaseUrl/debug/pairing"
$VisualDebugUrl = "$LocalBaseUrl/debug/visual-locate"
$RecentRequestsUrl = "$LocalBaseUrl/debug/recent-requests"

function Test-UsableLanIp {
    param([string]$IpAddress)

    return $IpAddress -and
        -not $IpAddress.StartsWith("127.") -and
        -not $IpAddress.StartsWith("169.254.")
}

function Get-LanIps {
    $preferredIps = @()
    try {
        $defaultInterfaceIndexes = Get-NetRoute -DestinationPrefix "0.0.0.0/0" -ErrorAction Stop |
            Where-Object { $_.NextHop -and $_.NextHop -ne "0.0.0.0" } |
            Sort-Object RouteMetric |
            Select-Object -ExpandProperty InterfaceIndex -Unique
        foreach ($interfaceIndex in $defaultInterfaceIndexes) {
            $preferredIps += Get-NetIPAddress -AddressFamily IPv4 -InterfaceIndex $interfaceIndex -ErrorAction SilentlyContinue |
                Where-Object { Test-UsableLanIp $_.IPAddress } |
                ForEach-Object { $_.IPAddress }
        }
    } catch {
        $preferredIps = @()
    }
    $preferredIps = @($preferredIps | Sort-Object -Unique)
    if ($preferredIps.Count -gt 0) {
        return $preferredIps
    }

    try {
        $hostname = [System.Net.Dns]::GetHostName()
        return [System.Net.Dns]::GetHostAddresses($hostname) |
            Where-Object {
                $_.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork -and
                (Test-UsableLanIp $_.ToString())
            } |
            ForEach-Object { $_.ToString() } |
            Sort-Object -Unique
    } catch {
        return @()
    }
}

$LanIps = @(Get-LanIps)
$LanIp = $LanIps | Select-Object -First 1
$LanBaseUrl = if ($LanIp) { "http://$LanIp`:$Port" } else { $LocalBaseUrl }
$LanBaseUrls = if ($LanIps.Count -gt 0) { @($LanIps | ForEach-Object { "http://$_`:$Port" }) } else { @($LocalBaseUrl) }

if ($ValidateOnly) {
    [PSCustomObject]@{
        repo_root = "$RepoRoot"
        backend_script = $BackendScript
        host_address = $HostAddress
        port = $Port
        recognition_mode = $RecognitionMode
        venue_package_root = $VenuePackageRoot
        venv_path = $VenvPath
        local_base_url = $LocalBaseUrl
        lan_base_url = $LanBaseUrl
        lan_base_urls = $LanBaseUrls
        pid_file = $PidFile
    } | ConvertTo-Json -Depth 4
    exit 0
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class VisionRouteNativeMethods {
    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool DestroyIcon(IntPtr hIcon);
}
"@

function Convert-UiText {
    param([string]$Base64Text)
    return [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Base64Text))
}

$Ui = @{
    AppTitle = Convert-UiText "VmlzaW9uUm91dGUgUEMg5ZCO5Y+w"
    StatusChecking = Convert-UiText "54q25oCB77ya5qOA5p+l5LitLi4u"
    StatusPrefix = Convert-UiText "54q25oCB77ya"
    StatusDetail = Convert-UiText "54q25oCB6K+m5oOF"
    StartBackend = Convert-UiText "5ZCv5Yqo5ZCO5Y+w"
    StopBackend = Convert-UiText "5YGc5q2i5ZCO5Y+w"
    RefreshStatus = Convert-UiText "5Yi35paw54q25oCB"
    ShowStatus = Convert-UiText "5pi+56S654q25oCB"
    OpenHealth = Convert-UiText "5omT5byAIEhlYWx0aA=="
    OpenPairing = Convert-UiText "5omT5byA6YWN5a+56aG1"
    OpenDebugCards = Convert-UiText "5omT5byAIERlYnVnIENhcmRz"
    OpenVisualDebug = Convert-UiText "5omT5byA6K+G5Yir6LCD6K+V6aG1"
    OpenRecentRequests = Convert-UiText "5omT5byA5pyA6L+R6K+35rGC"
    CopyLocalBaseUrl = Convert-UiText "5aSN5Yi2IExvY2FsIGJhc2VVcmw="
    CopyLanBaseUrl = Convert-UiText "5aSN5Yi2IExBTiBiYXNlVXJs"
    ExitTray = Convert-UiText "6YCA5Ye65omY55uY"
    Running = Convert-UiText "6L+Q6KGM5Lit"
    Starting = Convert-UiText "5ZCv5Yqo5Lit"
    Stopped = Convert-UiText "5pyq6L+Q6KGM"
    Error = Convert-UiText "5byC5bi4"
    WaitingHealth = Convert-UiText "562J5b6FIGhlYWx0aA=="
    Mode = Convert-UiText "5qih5byP"
    BackendAvailable = Convert-UiText "566X5rOV5Y+v55So"
    Yes = Convert-UiText "5piv"
    No = Convert-UiText "5ZCm"
    AlreadyResponds = Convert-UiText "UEMg5ZCO5Y+w5bey5pyJ5ZON5bqU77ya"
    StartingBackend = Convert-UiText "5q2j5Zyo5ZCv5YqoIFBDIOWQjuWPsO+8mg=="
    NoTrayStartedProcess = Convert-UiText "5rKh5pyJ5om+5Yiw55Sx5omY55uY5ZCv5Yqo55qE5ZCO5Y+w6L+b56iL44CC"
    BackendStopRequested = Convert-UiText "5bey6K+35rGC5YGc5q2iIFBDIOWQjuWPsOOAgg=="
    Copied = Convert-UiText "5bey5aSN5Yi277ya"
    ClipboardUnavailable = Convert-UiText "5Ymq6LS05p2/5LiN5Y+v55So44CC5YaF5a6577ya"
    TrayReady = Convert-UiText "5omY55uY5bey5bCx57uq77yM5Y+z6ZSu5p+l55yL5pON5L2c44CC"
    TooltipRunning = Convert-UiText "VmlzaW9uUm91dGXvvJrov5DooYzkuK0="
    TooltipStarting = Convert-UiText "VmlzaW9uUm91dGXvvJrlkK/liqjkuK0="
    TooltipError = Convert-UiText "VmlzaW9uUm91dGXvvJrlvILluLg="
    TooltipStopped = Convert-UiText "VmlzaW9uUm91dGXvvJrmnKrov5DooYw="
}

[System.Windows.Forms.Application]::EnableVisualStyles()
[System.Windows.Forms.Application]::SetCompatibleTextRenderingDefault($false)
New-Item -ItemType Directory -Force $LogDir | Out-Null

function New-StatusIcon {
    param([System.Drawing.Color]$Color)

    $bitmap = New-Object System.Drawing.Bitmap 16, 16
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([System.Drawing.Color]::Transparent)
    $brush = New-Object System.Drawing.SolidBrush $Color
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::White), 2
    $graphics.FillEllipse($brush, 1, 1, 14, 14)
    $graphics.DrawEllipse($pen, 1, 1, 14, 14)
    $handle = $bitmap.GetHicon()
    $icon = [System.Drawing.Icon]::FromHandle($handle).Clone()
    [VisionRouteNativeMethods]::DestroyIcon($handle) | Out-Null
    $pen.Dispose()
    $brush.Dispose()
    $graphics.Dispose()
    $bitmap.Dispose()
    return $icon
}

$IconRunning = New-StatusIcon ([System.Drawing.Color]::LimeGreen)
$IconStarting = New-StatusIcon ([System.Drawing.Color]::Gold)
$IconStopped = New-StatusIcon ([System.Drawing.Color]::Firebrick)

function Get-TrackedBackendProcess {
    if (-not (Test-Path $PidFile)) {
        return $null
    }
    try {
        $backendPid = [int](Get-Content $PidFile -ErrorAction Stop | Select-Object -First 1)
        return Get-Process -Id $backendPid -ErrorAction SilentlyContinue
    } catch {
        return $null
    }
}

function Get-ChildProcessIds {
    param([int]$ParentId)

    $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$ParentId" -ErrorAction SilentlyContinue)
    foreach ($child in $children) {
        Get-ChildProcessIds -ParentId ([int]$child.ProcessId)
        [int]$child.ProcessId
    }
}

function Start-Backend {
    $currentStatus = Get-BackendStatus
    if ($currentStatus.State -in @("running", "error")) {
        $notifyIcon.ShowBalloonTip(2000, $Ui.AppTitle, "$($Ui.AlreadyResponds)$LocalBaseUrl", [System.Windows.Forms.ToolTipIcon]::Info)
        return
    }

    $arguments = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", "`"$BackendScript`"",
        "-HostAddress", "`"$HostAddress`"",
        "-Port", "$Port",
        "-RecognitionMode", "`"$RecognitionMode`"",
        "-VenvPath", "`"$VenvPath`""
    )
    if ($VenuePackageRoot) {
        $arguments += @("-VenuePackageRoot", "`"$VenuePackageRoot`"")
    }

    $windowStyle = if ($ShowBackendConsole) { "Normal" } else { "Hidden" }
    $process = Start-Process -FilePath "powershell.exe" -ArgumentList $arguments -WorkingDirectory $RepoRoot -WindowStyle $windowStyle -PassThru
    Set-Content -Path $PidFile -Value $process.Id
    $notifyIcon.Icon = $IconStarting
    $notifyIcon.ShowBalloonTip(2000, $Ui.AppTitle, "$($Ui.StartingBackend)$LocalBaseUrl", [System.Windows.Forms.ToolTipIcon]::Info)
}

function Stop-Backend {
    $process = Get-TrackedBackendProcess
    if (-not $process) {
        Remove-Item -Path $PidFile -ErrorAction SilentlyContinue
        $notifyIcon.ShowBalloonTip(2500, $Ui.AppTitle, $Ui.NoTrayStartedProcess, [System.Windows.Forms.ToolTipIcon]::Warning)
        return
    }

    $childIds = @(Get-ChildProcessIds -ParentId $process.Id)
    foreach ($childId in ($childIds | Sort-Object -Descending)) {
        Stop-Process -Id $childId -Force -ErrorAction SilentlyContinue
    }
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Remove-Item -Path $PidFile -ErrorAction SilentlyContinue
    $notifyIcon.ShowBalloonTip(2000, $Ui.AppTitle, $Ui.BackendStopRequested, [System.Windows.Forms.ToolTipIcon]::Info)
}

function Get-BackendStatus {
    $trackedProcess = Get-TrackedBackendProcess
    try {
        $response = Invoke-RestMethod -Uri $HealthUrl -Method Get -TimeoutSec 1
        $data = if ($response.data) { $response.data } else { $response }
        $backend = $data.algorithm_backend_status
        $backendAvailable = if ($null -ne $backend -and $null -ne $backend.available) { [bool]$backend.available } else { $null }
        $summary = $Ui.Running
        if ($data.recognition_mode) {
            $summary = "$summary | $($Ui.Mode)=$($data.recognition_mode)"
        }
        if ($data.venue_id) {
            $summary = "$summary | $($data.venue_id)"
        }
        if ($null -ne $backendAvailable) {
            $backendAvailableText = if ($backendAvailable) { $Ui.Yes } else { $Ui.No }
            $summary = "$summary | $($Ui.BackendAvailable)=$backendAvailableText"
        }
        return [PSCustomObject]@{
            State = "running"
            Summary = $summary
            Detail = $data
        }
    } catch {
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
            return [PSCustomObject]@{
                State = "error"
                Summary = "$($Ui.Error) | health_http_$statusCode"
                Detail = $null
            }
        }
        if ($trackedProcess) {
            return [PSCustomObject]@{
                State = "starting"
                Summary = "$($Ui.Starting) | $($Ui.WaitingHealth)"
                Detail = $null
            }
        }
        return [PSCustomObject]@{
            State = "stopped"
            Summary = $Ui.Stopped
            Detail = $null
        }
    }
}

function Open-Url {
    param([string]$Url)
    Start-Process $Url
}

function Copy-Text {
    param([string]$Text)
    try {
        Set-Clipboard -Value $Text
        $notifyIcon.ShowBalloonTip(1500, $Ui.AppTitle, "$($Ui.Copied)$Text", [System.Windows.Forms.ToolTipIcon]::Info)
    } catch {
        $notifyIcon.ShowBalloonTip(2500, $Ui.AppTitle, "$($Ui.ClipboardUnavailable)$Text", [System.Windows.Forms.ToolTipIcon]::Warning)
    }
}

function Show-StatusBalloon {
    $status = Get-BackendStatus
    $message = "$($status.Summary)`nLocal: $LocalBaseUrl`nLAN: $($LanBaseUrls -join ', ')"
    $icon = if ($status.State -eq "running") { [System.Windows.Forms.ToolTipIcon]::Info } else { [System.Windows.Forms.ToolTipIcon]::Warning }
    $notifyIcon.ShowBalloonTip(3500, $Ui.StatusDetail, $message, $icon)
}

$notifyIcon = New-Object System.Windows.Forms.NotifyIcon
$notifyIcon.Text = $Ui.AppTitle
$notifyIcon.Icon = $IconStopped
$notifyIcon.Visible = $true

$menu = New-Object System.Windows.Forms.ContextMenuStrip
$statusItem = $menu.Items.Add($Ui.StatusChecking)
$statusItem.Enabled = $false
$menu.Items.Add("-") | Out-Null
$startItem = $menu.Items.Add($Ui.StartBackend)
$stopItem = $menu.Items.Add($Ui.StopBackend)
$refreshItem = $menu.Items.Add($Ui.RefreshStatus)
$showStatusItem = $menu.Items.Add($Ui.ShowStatus)
$menu.Items.Add("-") | Out-Null
$openHealthItem = $menu.Items.Add($Ui.OpenHealth)
$openPairingItem = $menu.Items.Add($Ui.OpenPairing)
$openCardsItem = $menu.Items.Add($Ui.OpenDebugCards)
$openVisualItem = $menu.Items.Add($Ui.OpenVisualDebug)
$openRecentItem = $menu.Items.Add($Ui.OpenRecentRequests)
$menu.Items.Add("-") | Out-Null
$copyLocalItem = $menu.Items.Add($Ui.CopyLocalBaseUrl)
$copyLanItem = $menu.Items.Add($Ui.CopyLanBaseUrl)
$menu.Items.Add("-") | Out-Null
$exitItem = $menu.Items.Add($Ui.ExitTray)

$notifyIcon.ContextMenuStrip = $menu

function Update-Status {
    $status = Get-BackendStatus
    $statusItem.Text = "$($Ui.StatusPrefix)$($status.Summary)"
    if ($status.State -eq "running") {
        $notifyIcon.Icon = $IconRunning
        $notifyIcon.Text = $Ui.TooltipRunning
        $startItem.Enabled = $false
        $stopItem.Enabled = $true
    } elseif ($status.State -eq "starting") {
        $notifyIcon.Icon = $IconStarting
        $notifyIcon.Text = $Ui.TooltipStarting
        $startItem.Enabled = $false
        $stopItem.Enabled = $true
    } elseif ($status.State -eq "error") {
        $notifyIcon.Icon = $IconStopped
        $notifyIcon.Text = $Ui.TooltipError
        $startItem.Enabled = $false
        $stopItem.Enabled = [bool](Get-TrackedBackendProcess)
    } else {
        $notifyIcon.Icon = $IconStopped
        $notifyIcon.Text = $Ui.TooltipStopped
        $startItem.Enabled = $true
        $stopItem.Enabled = $false
    }
}

$startItem.add_Click({ Start-Backend; Start-Sleep -Milliseconds 500; Update-Status })
$stopItem.add_Click({ Stop-Backend; Start-Sleep -Milliseconds 500; Update-Status })
$refreshItem.add_Click({ Update-Status; Show-StatusBalloon })
$showStatusItem.add_Click({ Show-StatusBalloon })
$openHealthItem.add_Click({ Open-Url $HealthUrl })
$openPairingItem.add_Click({ Open-Url $PairingUrl })
$openCardsItem.add_Click({ Open-Url $CardsUrl })
$openVisualItem.add_Click({ Open-Url $VisualDebugUrl })
$openRecentItem.add_Click({ Open-Url $RecentRequestsUrl })
$copyLocalItem.add_Click({ Copy-Text $LocalBaseUrl })
$copyLanItem.add_Click({ Copy-Text ($LanBaseUrls -join [Environment]::NewLine) })
$exitItem.add_Click({
    $timer.Stop()
    $notifyIcon.Visible = $false
    $notifyIcon.Dispose()
    [System.Windows.Forms.Application]::Exit()
})
$notifyIcon.add_DoubleClick({ Open-Url $VisualDebugUrl })

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = [Math]::Max(1, $RefreshSeconds) * 1000
$timer.add_Tick({ Update-Status })

Update-Status
$timer.Start()
$notifyIcon.ShowBalloonTip(2000, $Ui.AppTitle, $Ui.TrayReady, [System.Windows.Forms.ToolTipIcon]::Info)
[System.Windows.Forms.Application]::Run()

$IconRunning.Dispose()
$IconStarting.Dispose()
$IconStopped.Dispose()
