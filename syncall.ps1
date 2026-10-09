# Pull telemetry and event logs from the phone.
# Exits early if phone is not on ADB or if another sync is already running.

$ErrorActionPreference = "Stop"

. "$PSScriptRoot\sync_common.ps1"

if (-not (Enter-SyncLock)) {
    exit 2
}

try {
    $adbReport = Get-AdbDeviceReport
    if (-not $adbReport.HasDevice) {
        Show-NoPhoneConnected -Report $adbReport
        exit 1
    }

    Write-Banner -Title "SYNCALL - phone connected" -Color Green
    foreach ($id in $adbReport.Connected) {
        Write-Host "  Device: $id" -ForegroundColor Green
    }
    Write-Host ""

    & "$PSScriptRoot\synclogs.ps1" -SkipLock
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    Write-Host ""
    Write-Banner -Title "SYNCALL SUCCEEDED" -Color Green
    Write-Host "Today's logs: $(Join-Path $PSScriptRoot 'pulled_logs')" -ForegroundColor Green
    Write-Host "Archive: $(Join-Path $PSScriptRoot 'pulled_logs\archive')" -ForegroundColor Green
    Write-Host ""
}
finally {
    Exit-SyncLock
}
