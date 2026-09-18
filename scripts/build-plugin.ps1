# build-plugin.ps1 -- build the three ChunkSmith plugin cells (1.20.x / 1.21.x / 26.x) into dist/.
# Each cell is a STANDALONE gradle build consuming ../../shared_common in place; each ships ONE jar
# named Chunksmith-plugin-<line>.x.jar covering Paper/Spigot/Folia via the runtime Platform facade.
# Lives in scripts/ (run from anywhere).
#
# Usage:
#   pwsh scripts/build-plugin.ps1                 # build all three (serial)
#   pwsh scripts/build-plugin.ps1 -Only 1.20.x    # build a single line (1.20.x | 1.21.x | 26.x)
param([string]$Only)
$ErrorActionPreference = "Stop"
$repo   = Split-Path $PSScriptRoot -Parent
$plugin = Join-Path $repo "Plugin"
$dist   = Join-Path $repo "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
# --- dist hygiene (2026-09-18) ------------------------------------------------------------
# dist/ must only ever hold the CURRENT version. It had reached 226 jars across ten versions, and
# that is not merely clutter: deploy_profiles.py matches a jar per cell, two candidates make the
# match AMBIGUOUS, and it then falls back to a filename-version tiebreak -- so a string comparison
# decides which binary a release is gated on. Pruning is VERSION-SCOPED, never "empty dist/",
# because build-stage.ps1 runs the loader builders back to back and a blanket wipe here would
# delete the jars the previous builder just produced in the same run. See dist-prune.ps1.
& (Join-Path $PSScriptRoot "dist-prune.ps1")

$cells = [ordered]@{
    "1.20.x" = "Chunksmith-plugin-1.20.x.jar"
    "1.21.x" = "Chunksmith-plugin-1.21.x.jar"
    "26.x"   = "Chunksmith-plugin-26.x.jar"
}
if ($Only) {
    if (-not $cells.Contains($Only)) { throw "Unknown cell '$Only' (expected 1.20.x | 1.21.x | 26.x)" }
    $keys = @($Only)
} else {
    $keys = @($cells.Keys)
}

foreach ($line in $keys) {
    $jar     = $cells[$line]
    $cellDir = Join-Path $plugin $line
    Write-Host "=== Plugin $line ==="
    Push-Location $cellDir
    try {
        & ".\gradlew.bat" clean build --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "Plugin $line build FAILED (rc=$LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
    $src = Join-Path $cellDir ("build\libs\{0}" -f $jar)
    if (-not (Test-Path $src)) { throw "Missing jar: $src" }
    $modver = (Select-String -Path (Join-Path $cellDir "gradle.properties") -Pattern '^version=(.+)$').Matches[0].Groups[1].Value
    $destName = "chunksmith-{0}+{1}-plugin.jar" -f $modver, $line
    Copy-Item $src (Join-Path $dist $destName) -Force
    Write-Host "  -> $(Join-Path $dist $destName)"
}
Write-Host "Plugin build complete. Jars in $dist"
