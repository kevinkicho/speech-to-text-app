# server-watchdog.ps1 — supervises python server.py.
# Restarts the process if it exits for any reason. Captures stdout+stderr to
# a rolling log file in tools/logs/. Triggered by STT Server.lnk in Startup.
#
# To debug, tail the log:
#   Get-Content "C:\Users\kevin\Desktop\stt-app\tools\logs\server.log" -Wait

$ErrorActionPreference = 'Continue'

$ScriptDir = $PSScriptRoot
$Root      = Split-Path -Parent $ScriptDir
$LogDir    = Join-Path $ScriptDir 'logs'
$LogPath   = Join-Path $LogDir 'server.log'
$LockPath  = Join-Path $LogDir 'watchdog.lock'

$MaxLogBytes  = 10MB
$KeepRotated  = 5
$RestartSleep = 5  # seconds between crash and restart
$MinUptime    = 10 # seconds; faster crash loops back off harder

New-Item -ItemType Directory -Path $LogDir -Force | Out-Null

function Write-Log {
    param([string]$Line)
    "$(Get-Date -Format 'o') $Line" | Out-File -FilePath $LogPath -Append -Encoding utf8
}

function Rotate-Log {
    if (-not (Test-Path $LogPath)) { return }
    if ((Get-Item $LogPath).Length -lt $MaxLogBytes) { return }
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $rotated = Join-Path $LogDir "server-$stamp.log"
    Move-Item -Path $LogPath -Destination $rotated -Force
    # Keep only $KeepRotated most-recent rotated logs.
    Get-ChildItem -Path $LogDir -Filter 'server-*.log' |
        Sort-Object LastWriteTime -Descending |
        Select-Object -Skip $KeepRotated |
        Remove-Item -Force -ErrorAction SilentlyContinue
}

# Single-instance guard. If another watchdog is alive, exit cleanly.
if (Test-Path $LockPath) {
    try {
        $pidExisting = [int](Get-Content $LockPath -ErrorAction Stop)
        if (Get-Process -Id $pidExisting -ErrorAction SilentlyContinue) {
            Write-Log "[watchdog] another watchdog is running (pid=$pidExisting); exiting"
            exit 0
        }
    } catch { }
}
$PID | Out-File -FilePath $LockPath -Encoding ascii -Force

try {
    $VenvPython = Join-Path $Root '.venv\Scripts\python.exe'
    if (-not (Test-Path $VenvPython)) {
        Write-Log "[watchdog] no venv at $VenvPython — run start.bat once interactively to create it"
        exit 1
    }

    if (-not $env:STT_TOKEN) { $env:STT_TOKEN = 'change-me' }
    if (-not $env:STT_PORT)  { $env:STT_PORT  = '8080' }

    Write-Log "[watchdog] starting; root=$Root python=$VenvPython port=$env:STT_PORT"

    $consecutiveFastCrashes = 0
    while ($true) {
        Rotate-Log
        $started = Get-Date
        Write-Log '[watchdog] launching python server.py'

        # Run via cmd.exe so byte-stream redirection preserves python's UTF-8
        # stdout. PowerShell's `*>>` re-encodes to UTF-16, which makes the log
        # unreadable when intermixed with Write-Log's UTF-8 lines.
        $serverPy = Join-Path $Root 'server.py'
        $cmdLine  = "`"$VenvPython`" `"$serverPy`" >> `"$LogPath`" 2>&1"
        & cmd.exe /c $cmdLine
        $code = $LASTEXITCODE

        $uptimeSec = ((Get-Date) - $started).TotalSeconds
        Write-Log ("[watchdog] python exited code={0} after {1:N1}s" -f $code, $uptimeSec)

        if ($uptimeSec -lt $MinUptime) {
            $consecutiveFastCrashes++
        } else {
            $consecutiveFastCrashes = 0
        }
        # Exponential backoff capped at 5 minutes for crash-loops.
        $sleep = [Math]::Min(300, $RestartSleep * [Math]::Pow(2, $consecutiveFastCrashes))
        Write-Log ("[watchdog] restart in {0:N0}s (fast-crash streak={1})" -f $sleep, $consecutiveFastCrashes)
        Start-Sleep -Seconds $sleep
    }
} finally {
    Remove-Item -Path $LockPath -Force -ErrorAction SilentlyContinue
}
