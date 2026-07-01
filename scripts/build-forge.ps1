# build-forge.ps1 -- build ChunkSmith Forge jars into dist/.
# Forge is pre-26 only (classic FG6; there is NO 26 Forge cell -- FG6 is the ceiling), so every
# Forge/<v> cell is cog-gen'd (-Loader Forge) + built. Lives in scripts/ (run from anywhere).
#
# Usage:
#   pwsh scripts/build-forge.ps1               # build every Forge cell
#   pwsh scripts/build-forge.ps1 1.20.1        # build one cell
param([string[]]$Only)
$ErrorActionPreference = "Stop"
$repo   = Split-Path $PSScriptRoot -Parent
$loader = "Forge"
$root   = Join-Path $repo $loader
$dist   = Join-Path $repo "dist"
$cogGen = Join-Path $PSScriptRoot "cog-gen.ps1"
New-Item -ItemType Directory -Force -Path $dist | Out-Null

$cells = Get-ChildItem $root -Directory | Where-Object { $_.Name -ne "26" } | Select-Object -ExpandProperty Name | Sort-Object
if ($Only) {
  $targets = $Only
  foreach ($t in $targets) { if ($cells -notcontains $t) { throw "Unknown Forge cell '$t' (have: $($cells -join ', '))" } }
} else {
  $targets = $cells
}

foreach ($v in $targets) {
  $cellPath = Join-Path $root $v
  $modver = (Select-String -Path (Join-Path $cellPath "gradle.properties") -Pattern '^version=(.+)$').Matches[0].Groups[1].Value
  Write-Host "=== $loader/$v  (modver=$modver) ==="
  & $cogGen -Cell "$loader/$v" -McVer $v -Loader $loader
  if ($LASTEXITCODE -ne 0) { throw "cog-gen FAILED for $loader/$v" }
  Push-Location $cellPath
  & ".\gradlew.bat" clean build --no-daemon
  $rc = $LASTEXITCODE; Pop-Location
  if ($rc -ne 0) { throw "Build FAILED for $loader/$v" }
  $jar = Get-ChildItem (Join-Path $cellPath "build\libs") -Filter "Chunksmith-$loader-*.jar" |
         Where-Object { $_.Name -notmatch 'noshade|slim|sources|dev-shadow' } | Sort-Object LastWriteTime | Select-Object -Last 1
  if (-not $jar) { throw "No built jar for $loader/$v" }
  $dest = Join-Path $dist ("Chunksmith-{0}-{1}+mc{2}.jar" -f $loader, $modver, $v)
  Copy-Item $jar.FullName $dest -Force
  Write-Host "  -> $dest"
}
Write-Host "Forge build complete. Jars in $dist"
