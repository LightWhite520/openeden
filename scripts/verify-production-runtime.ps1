param(
    [int]$StartupTimeoutSeconds = 90,
    [int]$RequestTimeoutSeconds = 120,
    [int]$DiaryTimeoutSeconds = 180,
    [int]$RestartTimeoutSeconds = 90
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$javaHome = 'F:\SDK\JDK21'
$port = $null
$baseUrl = $null
$sessionUser = "production-smoke-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) "openeden-production-$([Guid]::NewGuid().ToString('N'))"
$dbPath = Join-Path $tempRoot 'runtime.db'
$server = $null

function Import-DotEnv {
    $path = Join-Path $root '.env'
    if (-not (Test-Path $path)) { return }
    foreach ($line in Get-Content $path) {
        if ($line -match '^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
            $name = $Matches[1]
            $value = $Matches[2].Trim()
            if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}

function Require-Setting([string]$name) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Required setting $name is missing from .env or the environment."
    }
    if ($name -eq 'OPENEDEN_OPENAI_API_KEY' -and $value -match 'replace-with-your-api-key|your-api-key|^sk-\.\.\.$') {
        throw "A real OPENEDEN_OPENAI_API_KEY is required; the configured value is a placeholder."
    }
}

function Resolve-ModelPath([string]$name, [string]$defaultRelative) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($value)) { $value = $defaultRelative }
    $path = if ([IO.Path]::IsPathRooted($value)) { $value } else { Join-Path $root $value }
    if (-not (Test-Path $path)) { throw "Model path for $name was not found: $path" }
    [Environment]::SetEnvironmentVariable($name, (Resolve-Path $path).Path, 'Process')
}

function Get-FreePort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return ([Net.IPEndPoint]$listener.LocalEndpoint).Port } finally { $listener.Stop() }
}

function Wait-Http([string]$uri, [int]$timeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    do {
        try { return Invoke-RestMethod -Uri $uri -Method Get -TimeoutSec 5 } catch { Start-Sleep -Seconds 1 }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $uri"
}

function Invoke-Chat([string]$text) {
    $body = @{ userId = $sessionUser; text = $text } | ConvertTo-Json
    return Invoke-RestMethod -Uri "$baseUrl/api/v1/chat" -Method Post -ContentType 'application/json' -Body $body -TimeoutSec $RequestTimeoutSeconds
}

function Invoke-Sql([string]$query) {
    $result = & sqlite3 -cmd '.timeout 5000' $dbPath $query
    if ($LASTEXITCODE -ne 0) { throw "sqlite3 query failed" }
    return ($result -join "`n").Trim()
}

function Start-Server([int]$timeoutSeconds = $StartupTimeoutSeconds) {
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = Join-Path $root 'gradlew.bat'
    $psi.Arguments = ':server:run --no-daemon'
    $psi.WorkingDirectory = $root
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.Environment['JAVA_HOME'] = $javaHome
    $psi.Environment['OPENEDEN_SERVER_PORT'] = [string]$port
    $psi.Environment['OPENEDEN_RUNTIME_DB_PATH'] = $dbPath
    $psi.Environment['OPENEDEN_MODEL_BACKEND'] = 'djl'
    $psi.Environment['OPENEDEN_DIARY_DELTA_THRESHOLD'] = '0.01'
    $psi.Environment['OPENEDEN_DIARY_SCAN_INTERVAL_SECONDS'] = '1'
    $psi.Environment['OPENEDEN_DIARY_MAX_RAW_MEMORIES'] = '32'
    $script:server = [Diagnostics.Process]::new()
    $server.StartInfo = $psi
    $null = $server.Start()
    $null = $server.BeginOutputReadLine()
    $null = $server.BeginErrorReadLine()
    Wait-Http "$baseUrl/health" $timeoutSeconds | Out-Null
}

function Stop-Server {
    if ($null -ne $script:server -and -not $server.HasExited) {
        $server.CloseMainWindow()
        if (-not $server.WaitForExit(15000)) {
            & taskkill.exe /PID $server.Id /T /F 2>$null | Out-Null
            $server.WaitForExit(10000)
        }
        if (-not $server.HasExited) {
            & taskkill.exe /PID $server.Id /T /F 2>$null | Out-Null
        }
    }
    if ($null -ne $port) {
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        do {
            $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
            if ($null -eq $listener) { break }
            Start-Sleep -Milliseconds 250
        } while ([DateTime]::UtcNow -lt $deadline)
    }
    $script:server = $null
}

try {
    if (-not (Test-Path (Join-Path $javaHome 'bin\java.exe'))) { throw "JDK21 not found at $javaHome" }
    if (-not (Get-Command sqlite3 -ErrorAction SilentlyContinue)) { throw 'sqlite3 is required for production verification.' }
    Import-DotEnv
    $port = Get-FreePort
    $baseUrl = "http://127.0.0.1:$port"
    Require-Setting 'OPENEDEN_OPENAI_API_KEY'
    Resolve-ModelPath 'OPENEDEN_DJL_VQVAE_MODEL_PATH' 'data/models/djl/vqvae'
    Resolve-ModelPath 'OPENEDEN_DJL_TEXT_MODEL_PATH' 'data/models/djl/text'
    Resolve-ModelPath 'OPENEDEN_DJL_EMOTIONAL_MODEL_PATH' 'data/models/djl/emotional'
    Resolve-ModelPath 'OPENEDEN_DJL_AFFECT_MODEL_PATH' 'data/models/djl/affect'
    New-Item -ItemType Directory -Path $tempRoot | Out-Null

    Start-Server
    1..3 | ForEach-Object { Invoke-Chat "production diary smoke turn $_" | Out-Null }
    $deadline = [DateTime]::UtcNow.AddSeconds($DiaryTimeoutSeconds)
    do {
        Start-Sleep -Seconds 1
        $done = Invoke-Sql "SELECT COUNT(*) FROM diary_tasks WHERE session_id='CLI:$sessionUser' AND status='DONE';"
    } while ($done -lt 1 -and [DateTime]::UtcNow -lt $deadline)
    if ($done -lt 1) { throw 'Diary task did not complete before timeout.' }
    $beforeEvolution = [int64](Invoke-Sql "SELECT evolution_index FROM session_state WHERE session_id='CLI:$sessionUser';")
    $beforeVector = Invoke-Sql "SELECT vector_json FROM session_state WHERE session_id='CLI:$sessionUser';"
    $beforeRaw = [int](Invoke-Sql "SELECT COUNT(*) FROM memory_entries WHERE session_id='CLI:$sessionUser' AND kind='RAW';")
    $narrative = [int](Invoke-Sql "SELECT COUNT(*) FROM memory_entries WHERE session_id='CLI:$sessionUser' AND kind='NARRATIVE';")
    $checkpoint = Invoke-Sql "SELECT COUNT(*) FROM diary_checkpoints WHERE session_id='CLI:$sessionUser';"
    if ($beforeRaw -lt 3 -or $narrative -lt 1 -or $checkpoint -ne '1') { throw 'Initial persistence checks failed.' }
    $stateBeforeRestart = Invoke-RestMethod "$baseUrl/api/v1/state?userId=$sessionUser"
    Stop-Server

    Start-Server -timeoutSeconds $RestartTimeoutSeconds
    $stateAfterRestart = Invoke-RestMethod "$baseUrl/api/v1/state?userId=$sessionUser"
    $afterEvolution = [int64](Invoke-Sql "SELECT evolution_index FROM session_state WHERE session_id='CLI:$sessionUser';")
    if ($afterEvolution -ne $beforeEvolution) { throw 'Evolution index changed across restart.' }
    if ((Invoke-Sql "SELECT vector_json FROM session_state WHERE session_id='CLI:$sessionUser';") -ne $beforeVector) { throw 'Vector changed across restart.' }
    if ($stateAfterRestart.omega -ne $stateBeforeRestart.omega) { throw 'Omega changed across restart.' }
    if ([int](Invoke-Sql "SELECT COUNT(*) FROM diary_tasks WHERE session_id='CLI:$sessionUser' AND status='RUNNING';") -ne 0) { throw 'A Diary task remained RUNNING after restart.' }
    Invoke-Chat 'production diary post-restart turn' | Out-Null
    $finalEvolution = [int64](Invoke-Sql "SELECT evolution_index FROM session_state WHERE session_id='CLI:$sessionUser';")
    if ($finalEvolution -le $afterEvolution) { throw 'Post-restart chat did not advance evolution index.' }
    Write-Host "Production runtime verification passed (raw=$beforeRaw, narrative=$narrative, evolution=$finalEvolution)."
}
finally {
    Stop-Server
    if (Test-Path $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}
