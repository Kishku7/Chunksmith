# build-all-plugin.ps1 -- build the three ChunkSmith plugin cells (1.20.x / 1.21.x / 26.x) and copy
# the three shipped jars into dist/. Each cell is a STANDALONE gradle build (its own gradlew +
# settings.gradle.kts + gradle.properties, mirroring the mod cells) that pulls the MC-agnostic core
# from ../../shared_common in place. Jars are named Chunksmith-plugin-<line>.x.jar; each covers
# Paper / Spigot / Folia for its MC line (the runtime Platform facade selects the flavour).
#
# Lives at the mod repo root (alongside build-all-fabric.ps1 etc.). Usage:
#   pwsh build-all-plugin.ps1                 # build all three (serial)
#   pwsh build-all-plugin.ps1 -Only 1.20.x    # build a single line (1.20.x | 1.21.x | 26.x)
param([string]$Only)
$ErrorActionPreference = "Stop"
$repo   = Split-Path -Parent $MyInvocation.MyCommand.Path
$plugin = Join-Path $repo "Plugin"
$dist   = Join-Path $repo "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null

# cell folder -> shipped jar name
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
    Copy-Item $src (Join-Path $dist $jar) -Force
    Write-Host "  -> $(Join-Path $dist $jar)"
}
Write-Host "Plugin build complete. Jars in $dist"
