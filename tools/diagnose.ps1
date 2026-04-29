# diagnose.ps1 — STT Floater self-check.
#
# Runs locally on the PC. Walks every link in the chain (server, watchdog,
# WSL, tmux, Tailscale, firewall, public-exposure tunnels, optionally the
# phone over adb) and prints a single pass/warn/fail report.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File C:\Users\kevin\Desktop\stt-app\tools\diagnose.ps1
#
# Or from the project root:
#   .\tools\diagnose.ps1
#
# Exit code is the count of FAILs (0 = healthy).

$ErrorActionPreference = 'Continue'

$ScriptDir   = $PSScriptRoot
$Root        = Split-Path -Parent $ScriptDir
$LogPath     = Join-Path $ScriptDir 'logs\server.log'
$LockPath    = Join-Path $ScriptDir 'logs\watchdog.lock'
$Adb         = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$Tailscale   = 'C:\Program Files\Tailscale\tailscale.exe'

$Token = if ($env:STT_TOKEN) { $env:STT_TOKEN } else { 'change-me' }
$Port  = if ($env:STT_PORT)  { $env:STT_PORT }  else { '8080' }

$results = New-Object System.Collections.ArrayList

function Add-Result {
    param([string]$Name, [string]$Status, [string]$Detail = '')
    [void]$results.Add([PSCustomObject]@{
        Name = $Name; Status = $Status; Detail = $Detail
    })
}

function Try-Run {
    param([string]$Name, [scriptblock]$Block)
    try {
        & $Block
    } catch {
        Add-Result $Name 'FAIL' $_.Exception.Message
    }
}

# ---------------------------------------------------------------------------
# 1. Server liveness
# ---------------------------------------------------------------------------

Try-Run 'server: port 8080 listener' {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($conn) {
        $proc = Get-Process -Id $conn[0].OwningProcess -ErrorAction SilentlyContinue
        Add-Result 'server: port 8080 listener' 'OK' "pid=$($conn[0].OwningProcess) ($($proc.ProcessName))"
    } else {
        Add-Result 'server: port 8080 listener' 'FAIL' 'nothing listening — server not running'
    }
}

Try-Run 'server: /health responds' {
    try {
        $r = Invoke-WebRequest "http://localhost:$Port/health" -UseBasicParsing -TimeoutSec 5
        $j = $r.Content | ConvertFrom-Json
        if ($j.ok) {
            $loaded = if ($j.whisper_loaded) { 'whisper loaded' } else { 'WHISPER NOT LOADED' }
            $status = if ($j.whisper_loaded) { 'OK' } else { 'WARN' }
            Add-Result 'server: /health responds' $status $loaded
        } else {
            Add-Result 'server: /health responds' 'WARN' "unexpected body: $($r.Content)"
        }
    } catch {
        Add-Result 'server: /health responds' 'FAIL' $_.Exception.Message
    }
}

Try-Run 'server: watchdog alive' {
    if (Test-Path $LockPath) {
        $pidLine = (Get-Content $LockPath -ErrorAction Stop).Trim()
        $proc    = Get-Process -Id ([int]$pidLine) -ErrorAction SilentlyContinue
        if ($proc) {
            Add-Result 'server: watchdog alive' 'OK' "pid=$pidLine ($($proc.ProcessName))"
        } else {
            Add-Result 'server: watchdog alive' 'WARN' "stale lock (pid $pidLine not running)"
        }
    } else {
        Add-Result 'server: watchdog alive' 'WARN' 'no lock file — watchdog not running (server may be running standalone)'
    }
}

Try-Run 'server: recent crash streak' {
    if (-not (Test-Path $LogPath)) {
        Add-Result 'server: recent crash streak' 'WARN' 'no log file yet'
        return
    }
    # Look at the last 200 lines for fast-crash streak counters.
    $tail = Get-Content $LogPath -Tail 200 -ErrorAction SilentlyContinue
    $lastStreak = ($tail | Select-String -Pattern 'fast-crash streak=(\d+)' -AllMatches |
                   Select-Object -Last 1).Matches.Groups[1].Value
    if (-not $lastStreak) {
        Add-Result 'server: recent crash streak' 'OK' 'no recent restarts logged'
    } elseif ([int]$lastStreak -eq 0) {
        Add-Result 'server: recent crash streak' 'OK' 'streak=0'
    } elseif ([int]$lastStreak -lt 3) {
        Add-Result 'server: recent crash streak' 'WARN' "streak=$lastStreak (some recent crashes)"
    } else {
        Add-Result 'server: recent crash streak' 'FAIL' "streak=$lastStreak (looping — check logs)"
    }
}

# ---------------------------------------------------------------------------
# 2. WSL + tmux
# ---------------------------------------------------------------------------

Try-Run 'wsl: distro available' {
    $out = wsl -l -q 2>&1 | ForEach-Object { ($_ -replace "`0", '').Trim() } | Where-Object { $_ }
    $distro = if ($env:WSL_DISTRO) { $env:WSL_DISTRO } else { 'Ubuntu' }
    if ($out -contains $distro) {
        Add-Result 'wsl: distro available' 'OK' "$distro present"
    } else {
        Add-Result 'wsl: distro available' 'FAIL' "$distro not in: $($out -join ', ')"
    }
}

Try-Run 'tmux: sessions visible' {
    try {
        $r = Invoke-WebRequest "http://localhost:$Port/sessions" -Headers @{ 'X-Token' = $Token } -UseBasicParsing -TimeoutSec 8
        $j = $r.Content | ConvertFrom-Json
        if ($j.ok -and $j.sessions.Count -gt 0) {
            Add-Result 'tmux: sessions visible' 'OK' "found: $($j.sessions -join ', ')"
        } elseif ($j.ok) {
            Add-Result 'tmux: sessions visible' 'WARN' 'no tmux sessions in WSL — start some with claude-sessions-app'
        } else {
            Add-Result 'tmux: sessions visible' 'FAIL' "/sessions error: $($j.error)"
        }
    } catch {
        Add-Result 'tmux: sessions visible' 'FAIL' $_.Exception.Message
    }
}

Try-Run 'tmux: auto-resolve picks a session' {
    try {
        $r = Invoke-WebRequest "http://localhost:$Port/active_session" -Headers @{ 'X-Token' = $Token } -UseBasicParsing -TimeoutSec 8
        $j = $r.Content | ConvertFrom-Json
        if ($j.session) {
            Add-Result 'tmux: auto-resolve picks a session' 'OK' "auto -> $($j.session)"
        } else {
            Add-Result 'tmux: auto-resolve picks a session' 'WARN' 'no most-recently-attached session — auto routing will fall through'
        }
    } catch {
        Add-Result 'tmux: auto-resolve picks a session' 'FAIL' $_.Exception.Message
    }
}

# ---------------------------------------------------------------------------
# 3. Tailscale
# ---------------------------------------------------------------------------

Try-Run 'tailscale: installed' {
    if (Test-Path $Tailscale) {
        Add-Result 'tailscale: installed' 'OK' $Tailscale
    } else {
        Add-Result 'tailscale: installed' 'FAIL' "not at $Tailscale"
        return
    }
}

if (Test-Path $Tailscale) {
    Try-Run 'tailscale: this PC online' {
        $status = & $Tailscale status 2>&1
        $self = $status | Where-Object { $_ -match '^\d+\.\d+\.\d+\.\d+\s+\S+\s+\S+\s+\S+' } | Select-Object -First 1
        if ($self) {
            $ip = ($self -split '\s+')[0]
            Add-Result 'tailscale: this PC online' 'OK' "self=$ip"
        } else {
            Add-Result 'tailscale: this PC online' 'WARN' 'no self entry parsed — may not be authenticated'
        }
    }

    Try-Run 'tailscale: funnel NOT exposing 8080' {
        $f = & $Tailscale funnel status 2>&1
        if ($f -match 'No serve config' -or $f -match 'No funnel config' -or $f -match '^$') {
            Add-Result 'tailscale: funnel NOT exposing 8080' 'OK' 'no funnel configured'
        } elseif ($f -match '8080') {
            Add-Result 'tailscale: funnel NOT exposing 8080' 'FAIL' '8080 is publicly funneled — DISABLE THIS'
        } else {
            Add-Result 'tailscale: funnel NOT exposing 8080' 'WARN' "funnel exists for other ports: $($f -join ' | ')"
        }
    }
}

# ---------------------------------------------------------------------------
# 4. Public-exposure surface
# ---------------------------------------------------------------------------

Try-Run 'firewall: profiles enabled' {
    $profiles = Get-NetFirewallProfile -ErrorAction SilentlyContinue
    $disabled = $profiles | Where-Object { -not $_.Enabled }
    if ($disabled) {
        Add-Result 'firewall: profiles enabled' 'WARN' "disabled: $($disabled.Name -join ', ')"
    } else {
        Add-Result 'firewall: profiles enabled' 'OK' 'all profiles enabled'
    }
}

Try-Run 'public-exposure tunnels NOT running' {
    $risky = @('ngrok', 'cloudflared', 'devtunnel', 'frpc', 'bore', 'localtunnel', 'pinggy')
    $found = foreach ($name in $risky) {
        $p = Get-Process -Name $name -ErrorAction SilentlyContinue
        if ($p) { "$name(pid=$($p.Id -join ','))" }
    }
    if ($found) {
        Add-Result 'public-exposure tunnels NOT running' 'WARN' ($found -join ', ')
    } else {
        Add-Result 'public-exposure tunnels NOT running' 'OK' 'none of: ngrok, cloudflared, devtunnel, frpc, bore, localtunnel, pinggy'
    }
}

# ---------------------------------------------------------------------------
# 5. Configuration sanity
# ---------------------------------------------------------------------------

Try-Run 'config: STT_TOKEN not default' {
    if ($Token -eq 'change-me') {
        Add-Result 'config: STT_TOKEN not default' 'WARN' 'using default token "change-me" — fine for personal Tailnet, weak otherwise'
    } else {
        Add-Result 'config: STT_TOKEN not default' 'OK' 'token is customized'
    }
}

Try-Run 'config: rotation key staged' {
    $keyPath = if ($env:STT_ROTATION_KEY) { $env:STT_ROTATION_KEY } else { Join-Path $ScriptDir 'ssh_key' }
    if (Test-Path $keyPath) {
        Add-Result 'config: rotation key staged' 'OK' "$keyPath exists"
    } else {
        Add-Result 'config: rotation key staged' 'WARN' "$keyPath missing — /keyfile rotation flow won't work until you generate a key"
    }
}

# ---------------------------------------------------------------------------
# 6. Phone (only if adb is installed and a device is connected)
# ---------------------------------------------------------------------------

if (Test-Path $Adb) {
    $devices = (& $Adb devices 2>&1) | Where-Object { $_ -match "`tdevice$" }
    if ($devices) {
        $count = ($devices | Measure-Object).Count
        Add-Result 'adb: device connected' 'OK' "$count device(s) attached"

        Try-Run 'phone: app installed' {
            $pkg = & $Adb shell "pm list packages com.stt.floater" 2>&1
            if ($pkg -match 'com.stt.floater') {
                Add-Result 'phone: app installed' 'OK' 'com.stt.floater present'
            } else {
                Add-Result 'phone: app installed' 'FAIL' 'not installed on connected device'
            }
        }

        Try-Run 'phone: prefs URL + token match server' {
            # adb shell returns an array of lines; flatten so -match sees the
            # whole document and $matches gets populated correctly.
            $xml = (& $Adb shell "run-as com.stt.floater cat /data/data/com.stt.floater/shared_prefs/stt.xml" 2>&1) -join "`n"

            $phoneUrl = ''
            if ($xml -match '<string name="url">([^<]*)</string>') {
                $phoneUrl = $matches[1].Trim()
            }
            $phoneToken = ''
            if ($xml -match '<string name="token">([^<]*)</string>') {
                $phoneToken = $matches[1].Trim()
            }

            $issues = @()
            if (-not $phoneUrl)   { $issues += 'phone has no server URL' }
            if (-not $phoneToken) { $issues += 'phone has no token' }
            if ($phoneToken -and $phoneToken -ne $Token) {
                $shortPhone = if ($phoneToken.Length -ge 4) { $phoneToken.Substring(0,4) + '…' } else { '…' }
                $issues += "tokens differ (phone='$shortPhone' vs server)"
            }

            if ($issues) {
                Add-Result 'phone: prefs URL + token match server' 'WARN' ($issues -join '; ')
            } else {
                Add-Result 'phone: prefs URL + token match server' 'OK' "url=$phoneUrl tokens match"
            }
        }

        Try-Run 'phone: can reach server over Tailnet' {
            $xml = (& $Adb shell "run-as com.stt.floater cat /data/data/com.stt.floater/shared_prefs/stt.xml" 2>&1) -join "`n"
            if ($xml -match '<string name="url">([^<]*)</string>') {
                $u = $matches[1].Trim()
                if (-not $u) {
                    Add-Result 'phone: can reach server over Tailnet' 'WARN' 'phone has no URL configured'
                    return
                }
                if ($u -notmatch '^https?://') { $u = "http://$u" }
                $r = (& $Adb shell "curl -sS -m 5 $u/health" 2>&1) -join ' '
                if ($r -match '"ok":\s*true') {
                    Add-Result 'phone: can reach server over Tailnet' 'OK' "$u/health -> ok"
                } else {
                    Add-Result 'phone: can reach server over Tailnet' 'FAIL' "$u/health -> $r"
                }
            } else {
                Add-Result 'phone: can reach server over Tailnet' 'WARN' 'phone has no URL configured'
            }
        }

        Try-Run 'phone: bubble service running' {
            $ps = & $Adb shell "pidof com.stt.floater" 2>&1
            if ($ps -match '\d+') {
                Add-Result 'phone: bubble service running' 'OK' "pid=$ps"
            } else {
                Add-Result 'phone: bubble service running' 'WARN' 'app process not running — open the app and tap Start'
            }
        }

        Try-Run 'phone: accessibility service status' {
            $en = (& $Adb shell "settings get secure enabled_accessibility_services" 2>&1).Trim()
            if ($en -match 'com\.stt\.floater') {
                Add-Result 'phone: accessibility service status' 'OK' 'enabled'
            } else {
                Add-Result 'phone: accessibility service status' 'WARN' 'disabled (only matters if you use clipboard auto-paste-Enter)'
            }
        }
    } else {
        Add-Result 'adb: device connected' 'WARN' 'no devices attached — phone checks skipped'
    }
} else {
    Add-Result 'adb: installed' 'WARN' 'adb not at expected Android SDK path — phone checks skipped'
}

# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------

Write-Host ''
Write-Host '== STT Floater self-diagnose =='
Write-Host ''
foreach ($r in $results) {
    $tag = "[{0}]" -f $r.Status
    $color = switch ($r.Status) {
        'OK'   { 'Green' }
        'WARN' { 'Yellow' }
        'FAIL' { 'Red' }
        default { 'Gray' }
    }
    $line = "{0,-6} {1,-44} {2}" -f $tag, $r.Name, $r.Detail
    Write-Host $line -ForegroundColor $color
}
Write-Host ''

$ok    = ($results | Where-Object { $_.Status -eq 'OK' }).Count
$warn  = ($results | Where-Object { $_.Status -eq 'WARN' }).Count
$fail  = ($results | Where-Object { $_.Status -eq 'FAIL' }).Count
$summaryColor = 'Green'
if ($fail) { $summaryColor = 'Red' } elseif ($warn) { $summaryColor = 'Yellow' }
Write-Host ("Summary: {0} OK, {1} warn, {2} fail" -f $ok, $warn, $fail) -ForegroundColor $summaryColor
Write-Host ''
if ($fail) {
    Write-Host 'See server log:' -ForegroundColor DarkGray
    Write-Host "  Get-Content `"$LogPath`" -Tail 30 -Wait" -ForegroundColor DarkGray
}

exit $fail
