#Requires -Version 5.1
<#
.SYNOPSIS
    Decode ATRI .ks.scn (PSB) scenario files to readable JSON, locally.

.DESCRIPTION
    Downloads FreeMote (PsbDecompile) if needed, then decompiles every
    private_corpus/atri_game_scripts_vol1/*.scn into private_corpus/atri_decoded/.

    The .scn files use the raw PSB format (magic 'PSB\0'), so no key is required.

    SAFE TO COMMIT: this script. NOT SAFE TO COMMIT: anything it produces.
    All output goes under private_corpus/, which is gitignored. See README.md.

.NOTES
    Requires .NET Framework 4.8 (Windows built-in). Run from any directory;
    paths are resolved relative to the repo root inferred from this script.
#>
[CmdletBinding()]
param(
    # Override the FreeMote release asset URL to pin a version or bypass the API lookup.
    [string]$FreeMoteUrl,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

# repo root = two levels up from tools/psb-decode/
$RepoRoot   = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$SrcDir     = Join-Path $RepoRoot 'private_corpus\atri_game_scripts_vol1'
$OutDir     = Join-Path $RepoRoot 'private_corpus\atri_decoded'
$ToolDir    = Join-Path $OutDir '_freemote'           # kept inside gitignored area
$Decompiler = Join-Path $ToolDir 'PsbDecompile.exe'

if (-not (Test-Path $SrcDir)) {
    throw "Source scenario dir not found: $SrcDir`nExpected the GARbro-extracted vol1 .scn files there."
}
New-Item -ItemType Directory -Force -Path $OutDir, $ToolDir | Out-Null

# --- 1. Ensure FreeMote PsbDecompile is available -------------------------------
if ($Force -or -not (Test-Path $Decompiler)) {
    $zipPath = Join-Path $ToolDir 'freemote.zip'
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

    # Resolve the latest toolkit asset via the GitHub API unless an explicit URL was given.
    # Asset names look like 'Ulysses-FreeMoteToolkit-vX.Y.Z.zip' and the tag lags the asset,
    # so a hardcoded URL is brittle — query for it.
    if (-not $FreeMoteUrl) {
        Write-Host "[1/3] Resolving latest FreeMote toolkit asset..."
        try {
            $rel = Invoke-RestMethod -Uri 'https://api.github.com/repos/UlyssesWu/FreeMote/releases/latest' `
                -Headers @{ 'User-Agent' = 'openeden-psb-decode' } -UseBasicParsing
            $asset = $rel.assets | Where-Object { $_.name -like '*FreeMoteToolkit*.zip' } | Select-Object -First 1
            if (-not $asset) { $asset = $rel.assets | Where-Object { $_.name -like '*.zip' } | Select-Object -First 1 }
            if (-not $asset) { throw "no .zip asset on release $($rel.tag_name)" }
            $FreeMoteUrl = $asset.browser_download_url
        } catch {
            throw "Could not resolve FreeMote release via GitHub API ($_).`n" +
                  "Pass -FreeMoteUrl <zip url> explicitly, or download PsbDecompile.exe from`n" +
                  "  https://github.com/UlyssesWu/FreeMote/releases  into  $ToolDir"
        }
    }

    Write-Host "[1/3] Downloading FreeMote <- $FreeMoteUrl"
    try {
        Invoke-WebRequest -Uri $FreeMoteUrl -OutFile $zipPath -UseBasicParsing
    } catch {
        throw "Failed to download FreeMote from $FreeMoteUrl`n" +
              "Download PsbDecompile.exe manually from " +
              "https://github.com/UlyssesWu/FreeMote/releases and place it in:`n  $ToolDir`n($_)"
    }
    Write-Host "      Extracting..."
    Expand-Archive -Path $zipPath -DestinationPath $ToolDir -Force
    Remove-Item $zipPath -Force

    if (-not (Test-Path $Decompiler)) {
        # Some release zips nest binaries in a subfolder; locate the exe.
        $found = Get-ChildItem -Path $ToolDir -Recurse -Filter 'PsbDecompile.exe' | Select-Object -First 1
        if ($found) { $Decompiler = $found.FullName }
        else { throw "PsbDecompile.exe not found after extraction under $ToolDir" }
    }
} else {
    Write-Host "[1/3] FreeMote already present: $Decompiler"
}

# Windows may flag downloaded DLLs; unblock so plugins load.
Get-ChildItem -Path (Split-Path $Decompiler) -Recurse -Include *.dll,*.exe -ErrorAction SilentlyContinue |
    Unblock-File -ErrorAction SilentlyContinue

# --- 2. Decode every .scn -------------------------------------------------------
$scn = Get-ChildItem -Path $SrcDir -Filter '*.scn' | Sort-Object Name
Write-Host "[2/3] Decoding $($scn.Count) .scn files -> $OutDir"

$ok = 0; $skip = 0; $fail = 0
foreach ($f in $scn) {
    # PsbDecompile writes <name>.json next to the input; we redirect via -o.
    $expected = Join-Path $OutDir ($f.BaseName + '.json')
    if (-not $Force -and (Test-Path $expected)) {
        $skip++
        continue
    }
    & $Decompiler $f.FullName -o $OutDir 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0 -or (Test-Path $expected)) { $ok++ }
    else { Write-Warning "  decode failed: $($f.Name)"; $fail++ }
}

# --- 3. Report ------------------------------------------------------------------
Write-Host "[3/3] Done. decoded=$ok skipped=$skip failed=$fail"
Write-Host ""
Write-Host "Output (gitignored): $OutDir"
Write-Host "Reminder: decoded text is local research only. Commit distilled rules"
Write-Host "and statistics only - never raw scenario text. See tools/psb-decode/README.md."
