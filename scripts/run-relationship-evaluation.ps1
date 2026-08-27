param(
    [ValidateSet("A", "B")]
    [string]$Variant = "A",
    [ValidateRange(1, 100)]
    [int]$Runs = 3,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "..\build\relationship-evaluation")
)

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$outputRoot = Join-Path ([System.IO.Path]::GetFullPath($OutputDirectory)) $Variant

for ($run = 1; $run -le $Runs; $run++) {
    $runDirectory = Join-Path $outputRoot "run-$run"
    New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null
    $env:OPENEDEN_EVALUATION_OUTPUT_DIRECTORY = $runDirectory
    & (Join-Path $projectRoot "gradlew.bat") :server:test --tests "io.openeden.server.evaluation.RelationshipLongRunHarnessTest"
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
