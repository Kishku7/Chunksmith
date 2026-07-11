# build-forge.ps1 -- build ChunkSmith Forge jars into dist/.
# Forge is pre-26 only (classic FG6; there is NO 26 Forge cell -- FG6 is the ceiling), so every
# Forge/<v> cell is cog-gen'd (-Loader Forge) + built. Lives in scripts/ (run from anywhere).
#
# Usage:
#   pwsh scripts/build-forge.ps1               # build every Forge cell
#   pwsh scripts/build-forge.ps1 1.20.1        # build one cell
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Only)
$ErrorActionPreference = "Stop"
$repo   = Split-Path $PSScriptRoot -Parent
$loader = "Forge"
$root   = Join-Path $repo $loader
$dist   = Join-Path $repo "dist"
$cogGen = Join-Path $PSScriptRoot "cog-gen.ps1"
New-Item -ItemType Directory -Force -Path $dist | Out-Null

# --- single-build guard -------------------------------------------------------------------
# All loader cells share the ONE shared_common subproject, and every cell build runs
# :chunksmith-common:clean. Two builds at once race on shared_common/build -- one wipes the
# classes the other is compiling against, which shows up as a bogus
# "package com.kishku7.chunksmith.util does not exist" in the cell's own sources. Never run two.
$lockFile = Join-Path $repo ".build-lock"
if (Test-Path $lockFile) {
  $owner = (Get-Content $lockFile -Raw -ErrorAction SilentlyContinue).Trim()
  throw "Another ChunkSmith build appears to be running ($owner). Cell builds share shared_common and MUST NOT overlap. If this is stale, delete: $lockFile"
}
Set-Content -Path $lockFile -Value "pid=$PID started=$(Get-Date -Format o)" -Encoding ascii
try {


$cells = Get-ChildItem $root -Directory | Where-Object { $_.Name -ne "26" } | Select-Object -ExpandProperty Name | Sort-Object
if ($Only) {
  $targets = $Only
  foreach ($t in $targets) { if ($cells -notcontains $t) { throw "Unknown Forge cell '$t' (have: $($cells -join ', '))" } }
} else {
  $targets = $cells
}

# Build every requested cell even if one fails (see the note in build-fabric.ps1).
$failed = @()
foreach ($v in $targets) {
  try {
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
  $dest = Join-Path $dist ("chunksmith-{0}+{1}-forge.jar" -f $modver, $v)
  Copy-Item $jar.FullName $dest -Force
  Write-Host "  -> $dest"
  } catch {
    Write-Host "!!! CELL FAILED: Forge/$v -- $($_.Exception.Message)" -ForegroundColor Red
    $failed += $v
  }
}
if ($failed.Count -gt 0) {
  Write-Host ""
  Write-Host "Forge build FINISHED WITH FAILURES ($($failed.Count)): $($failed -join ', ')" -ForegroundColor Red
  Write-Host "Jars for the cells that DID build are in $dist"
  exit 1
}
Write-Host "Forge build complete. Jars in $dist"
} finally {
  Remove-Item $lockFile -Force -ErrorAction SilentlyContinue
}
