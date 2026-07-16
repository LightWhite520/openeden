param(
    [string]$JdkHome = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'F:\SDK\JDK21' })
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Get-FreeTcpPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return ([Net.IPEndPoint]$listener.LocalEndpoint).Port } finally { $listener.Stop() }
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Invoke-CliByteCase {
    param(
        [string]$Name,
        [Text.Encoding]$Encoding,
        [bool]$InputBom,
        [string]$CliPath,
        [int]$Port
    )

    $tempHome = Join-Path ([IO.Path]::GetTempPath()) ("openeden-cli-unicode-" + [Guid]::NewGuid().ToString('N'))
    $configDir = Join-Path $tempHome '.openeden'
    [IO.Directory]::CreateDirectory($configDir) | Out-Null
    [IO.File]::WriteAllText(
        (Join-Path $configDir 'config.json'),
        "{`"serverUrl`":`"http://127.0.0.1:$Port`",`"userId`":`"local`"}",
        [Text.UTF8Encoding]::new($false)
    )

    try {
        $start = [Diagnostics.ProcessStartInfo]::new()
        $start.FileName = $env:ComSpec
        $start.ArgumentList.Add('/d')
        $start.ArgumentList.Add('/c')
        $start.ArgumentList.Add($CliPath)
        $start.UseShellExecute = $false
        $start.RedirectStandardInput = $true
        $start.RedirectStandardOutput = $true
        $start.RedirectStandardError = $true
        $start.Environment['JAVA_OPTS'] = "-Duser.home=$tempHome"

        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $start
        Assert-True $process.Start() "$Name failed to start"
        $stdout = [IO.MemoryStream]::new()
        $stdoutCopy = $process.StandardOutput.BaseStream.CopyToAsync($stdout)
        $stderrTask = $process.StandardError.ReadToEndAsync()

        $content = $Encoding.GetBytes("你好`r`n/exit`r`n")
        $input = if ($InputBom) { $Encoding.GetPreamble() + $content } else { $content }
        $process.StandardInput.BaseStream.Write($input, 0, $input.Length)
        $process.StandardInput.BaseStream.Close()

        Assert-True $process.WaitForExit(20000) "$Name timed out"
        $null = $stdoutCopy.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        $bytes = $stdout.ToArray()
        $text = $Encoding.GetString($bytes)

        Assert-True ($process.ExitCode -eq 0) "$Name exited $($process.ExitCode): $stderr"
        Assert-True (-not ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)) "$Name emitted a UTF-8 BOM"
        Assert-True ($text.Contains('你好')) "$Name lost Chinese input: $text"
        Assert-True ($text.Contains('回复：你好')) "$Name lost Chinese output: $text"
        Assert-True (-not $text.Contains([char]0xFFFD)) "$Name emitted a replacement character"
    } finally {
        if (Test-Path -LiteralPath $tempHome) { Remove-Item -LiteralPath $tempHome -Recurse -Force }
    }
}

$env:JAVA_HOME = $JdkHome
$env:Path = "$JdkHome\bin;$env:Path"
& "$PSScriptRoot\..\gradlew.bat" :installDist --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { throw 'installDist failed' }

$port = Get-FreeTcpPort
$server = Start-Job -ArgumentList $port -ScriptBlock {
    param($Port)
    $listener = [Net.HttpListener]::new()
    $listener.Prefixes.Add("http://127.0.0.1:$Port/")
    $listener.Start()
    try {
        while ($listener.IsListening) {
            $context = $listener.GetContext()
            $path = $context.Request.Url.AbsolutePath
            $body = if ($path -eq '/health') {
                '{"status":"ready"}'
            } elseif ($path -eq '/api/v1/chat/stream') {
                "event: accepted`ndata: {`"requestId`":`"req_1`"}`n`nevent: response.delta`ndata: {`"text`":`"回复：你好`"}`n`nevent: completed`ndata: {`"requestId`":`"req_1`",`"status`":`"completed`"}`n`n"
            } else {
                '{}'
            }
            $bytes = [Text.Encoding]::UTF8.GetBytes($body)
            $context.Response.StatusCode = 200
            $context.Response.ContentType = if ($path -eq '/api/v1/chat/stream') { 'text/event-stream; charset=UTF-8' } else { 'application/json; charset=UTF-8' }
            $context.Response.ContentLength64 = $bytes.Length
            $context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
            $context.Response.OutputStream.Close()
        }
    } finally {
        $listener.Close()
    }
}

try {
    Start-Sleep -Milliseconds 300
    $cli = (Resolve-Path "$PSScriptRoot\..\build\install\openeden\bin\openeden.bat").Path
    $utf8 = [Text.UTF8Encoding]::new($false)
    Invoke-CliByteCase 'UTF-8 without BOM' $utf8 $false $cli $port
    Invoke-CliByteCase 'UTF-8 with BOM' $utf8 $true $cli $port
    Write-Host 'PASS: CLI redirected Unicode byte contract'
} finally {
    Stop-Job -Job $server -ErrorAction SilentlyContinue
    Remove-Job -Job $server -Force -ErrorAction SilentlyContinue
}
