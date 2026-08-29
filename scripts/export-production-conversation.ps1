param(
    [Parameter(Mandatory = $true)][string]$ServiceRoot,
    [Parameter(Mandatory = $true)][string]$ExportDirectory,
    [Parameter(Mandatory = $true)][string]$IncarnationId,
    [Parameter(Mandatory = $true)][string]$MaintenanceToken,
    [Parameter(Mandatory = $true)][ValidateRange(1, 65535)][int]$OpenEdenPort
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
        -Body ($Body | ConvertTo-Json -Depth 8) -TimeoutSec 120
}

if ($OpenEdenPort -eq 8080) { throw 'Port 8080 is reserved and must never be targeted by OpenEden maintenance.' }
if ([string]::IsNullOrWhiteSpace($MaintenanceToken)) { throw 'MaintenanceToken must not be blank.' }
if ([string]::IsNullOrWhiteSpace($IncarnationId)) { throw 'IncarnationId must not be blank.' }

$resolvedRoot = Resolve-CanonicalPath $ServiceRoot $true
if (-not (Test-Path -LiteralPath (Join-Path $resolvedRoot 'settings.gradle.kts'))) {
    throw "ServiceRoot is not an OpenEden service checkout: $resolvedRoot"
}
$resolvedExport = Resolve-CanonicalPath $ExportDirectory $false
Assert-UnderRoot $resolvedExport $resolvedRoot 'ExportDirectory'
if (Test-Path -LiteralPath $resolvedExport) { throw "ExportDirectory already exists: $resolvedExport" }

$readiness = Invoke-OpenEdenMaintenance 'Get' '/api/v1/maintenance/incarnation/readiness' $null
$schemaVersion = [int]$readiness.schemaVersion
if ($schemaVersion -lt 23) { throw "Maintenance requires schema version 23+; server reported $schemaVersion." }
if ([int64]$readiness.activeIncarnationCount -ne 1) { throw 'Maintenance requires exactly one active incarnation.' }
if ($readiness.resetReadiness -ne 'READY') { throw "Server reset readiness is $($readiness.resetReadiness)." }
if ($readiness.activeIncarnationId -cne $IncarnationId) { throw 'IncarnationId is stale.' }

Invoke-OpenEdenMaintenance 'Post' '/api/v1/maintenance/incarnation/export' @{
    incarnationId = $IncarnationId
    targetDirectory = $resolvedExport
}
