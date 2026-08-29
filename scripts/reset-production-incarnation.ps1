param(
    [Parameter(Mandatory = $true)][string]$ServiceRoot,
    [Parameter(Mandatory = $true)][string]$ManifestPath,
    [Parameter(Mandatory = $true)][string]$IncarnationId,
    [Parameter(Mandatory = $true)][string]$RequestId,
    [Parameter(Mandatory = $true)][ValidateSet('growth', 'legacy')][string]$PersonaMode,
    [Parameter(Mandatory = $true)][ValidateSet('pre_command', 'true_self', 'awakened')][string]$PersonaStart,
    [Parameter(Mandatory = $true)][string]$MaintenanceToken,
    [Parameter(Mandatory = $true)][ValidateRange(1, 65535)][int]$OpenEdenPort,
    [Parameter(Mandatory = $true)][switch]$ConfirmReset
)

$ErrorActionPreference = 'Stop'

function Resolve-CanonicalPath([string]$Path, [bool]$MustExist) {
    $absolute = [IO.Path]::GetFullPath($Path)
    if ($MustExist) {
        if (-not (Test-Path -LiteralPath $absolute)) { throw "Path does not exist: $absolute" }
        return (Resolve-Path -LiteralPath $absolute).Path
    }
    $missing = [Collections.Generic.Stack[string]]::new()
    $candidate = $absolute
    while (-not (Test-Path -LiteralPath $candidate)) {
        $missing.Push([IO.Path]::GetFileName($candidate))
        $parent = [IO.Path]::GetDirectoryName($candidate)
        if ([string]::IsNullOrWhiteSpace($parent)) { throw "Path has no existing parent: $absolute" }
        $candidate = $parent
    }
    $resolved = (Resolve-Path -LiteralPath $candidate).Path
    while ($missing.Count -gt 0) { $resolved = Join-Path $resolved $missing.Pop() }
    return [IO.Path]::GetFullPath($resolved)
}

function Assert-UnderRoot([string]$Path, [string]$Root, [string]$Label) {
    $prefix = $Root.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $Path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label must be inside the configured OpenEden service root: $Root"
    }
}

function Invoke-OpenEdenMaintenance([string]$Method, [string]$Path, [object]$Body) {
    $headers = @{ Authorization = "Bearer $MaintenanceToken" }
    $uri = "http://127.0.0.1:$OpenEdenPort$Path"
    if ($null -eq $Body) {
        return Invoke-RestMethod -Uri $uri -Method $Method -Headers $headers -TimeoutSec 30
    }
    return Invoke-RestMethod -Uri $uri -Method $Method -Headers $headers -ContentType 'application/json' `
        -Body ($Body | ConvertTo-Json -Depth 8) -TimeoutSec 180
}

if ($OpenEdenPort -eq 8080) { throw 'Port 8080 is reserved and must never be targeted by OpenEden maintenance.' }
if (-not $ConfirmReset.IsPresent) { throw 'Reset requires the explicit -ConfirmReset switch.' }
if ([string]::IsNullOrWhiteSpace($MaintenanceToken)) { throw 'MaintenanceToken must not be blank.' }
if ($RequestId.Trim() -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$') {
    throw 'RequestId must be stable, 8-128 characters, and use only letters, digits, dot, underscore, colon, or hyphen.'
}
if ([string]::IsNullOrWhiteSpace($IncarnationId)) { throw 'IncarnationId must not be blank.' }
if ($PersonaMode -eq 'legacy' -and $PersonaStart -ne 'awakened') {
    throw 'Legacy mode requires the awakened starting point.'
}

$resolvedRoot = Resolve-CanonicalPath $ServiceRoot $true
if (-not (Test-Path -LiteralPath (Join-Path $resolvedRoot 'settings.gradle.kts'))) {
    throw "ServiceRoot is not an OpenEden service checkout: $resolvedRoot"
}
$resolvedManifest = Resolve-CanonicalPath $ManifestPath $true
Assert-UnderRoot $resolvedManifest $resolvedRoot 'ManifestPath'
if ([IO.Path]::GetFileName($resolvedManifest) -cne 'manifest.json') {
    throw 'ManifestPath must resolve to manifest.json.'
}

$readiness = Invoke-OpenEdenMaintenance 'Get' '/api/v1/maintenance/incarnation/readiness' $null
$schemaVersion = [int]$readiness.schemaVersion
if ($schemaVersion -lt 23) { throw "Maintenance requires schema version 23+; server reported $schemaVersion." }
if ([int64]$readiness.activeIncarnationCount -ne 1) { throw 'Maintenance requires exactly one active incarnation.' }
if ($readiness.resetReadiness -notin @('READY', 'RESUME_REQUIRED')) {
    throw "Server reset readiness is $($readiness.resetReadiness)."
}
if ($readiness.activeIncarnationId -cne $IncarnationId) { throw 'IncarnationId is stale.' }

Invoke-OpenEdenMaintenance 'Post' '/api/v1/maintenance/incarnation/reset' @{
    incarnationId = $IncarnationId
    requestId = $RequestId.Trim()
    manifestPath = $resolvedManifest
    confirmed = $true
    personaMode = $PersonaMode
    personaStartSubState = $PersonaStart
}
