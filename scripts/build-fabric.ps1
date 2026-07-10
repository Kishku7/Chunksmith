# build-fabric.ps1 -- build ChunkSmith Fabric jars into dist/.
# Covers BOTH the pre-26 per-version cells (Fabric/<v>, cog-gen + gradlew) AND the unified 26 line
# (Fabric/26, -P matrix + PACK_FORMAT range-form). Lives in scripts/ (run from anywhere).
#
# Usage:
#   pwsh scripts/build-fabric.ps1                 # build EVERYTHING (all pre-26 cells + all 26.X)
#   pwsh scripts/build-fabric.ps1 1.21.8          # build one pre-26 cell
#   pwsh scripts/build-fabric.ps1 26.2            # build one 26.X target
#   pwsh scripts/build-fabric.ps1 1.21.8 26.1     # build a specific set
param([string[]]$Only)
$ErrorActionPreference = "Stop"
$repo   = Split-Path $PSScriptRoot -Parent
$loader = "Fabric"
$root   = Join-Path $repo $loader
$dist   = Join-Path $repo "dist"
$cogGen = Join-Path $PSScriptRoot "cog-gen.ps1"
New-Item -ItemType Directory -Force -Path $dist | Out-Null

# 26-line matrix (unified Fabric/26 cell; -P + PACK_FORMAT). pack_format per Memory/knowledge/pack-formats.md.
# 26.3 pinned to snapshot-3 EXCLUSIVELY (2026-07-07): dep uses the Fabric-normalized alpha form; no lower-26.3 compat.
$m26 = [ordered]@{
  "26.1" = @{ mc = "26.1.2";          api = "0.150.0+26.1.2"; dep = ">=26.1- <26.2"; packFormat = "84" }
  "26.2" = @{ mc = "26.2";            api = "0.152.1+26.2";   dep = ">=26.2- <26.3"; packFormat = "88" }
  "26.3" = @{ mc = "26.3-snapshot-3"; api = "0.154.3+26.3";   dep = "26.3-alpha.3"; packFormat = "91" }
}
# pre-26 cells = Fabric/<v> dirs except the unified "26".
$preCells = Get-ChildItem $root -Directory | Where-Object { $_.Name -ne "26" } | Select-Object -ExpandProperty Name | Sort-Object

if ($Only) {
  $targets = $Only
  foreach ($t in $targets) {
    if (-not $m26.Contains($t) -and $preCells -notcontains $t) {
      throw "Unknown Fabric target '$t' (pre-26 cells: $($preCells -join ', ') ; 26 line: $($m26.Keys -join ', '))"
    }
  }
} else {
  $targets = @($preCells) + @($m26.Keys)
}

function Build-PreCell($v) {
  $cellPath = Join-Path $root $v
  $modver = (Select-String -Path (Join-Path $cellPath "gradle.properties") -Pattern '^version=(.+)$').Matches[0].Groups[1].Value
  Write-Host "=== $loader/$v  (pre-26 cell, modver=$modver) ==="
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

function Build-26($v) {
  $cell = Join-Path $root "26"
  $mm = $m26[$v]
  $modver = (Select-String -Path (Join-Path $cell "gradle.properties") -Pattern '^version=(.+)$').Matches[0].Groups[1].Value
  Write-Host "=== $loader/26 -> $v  (mc=$($mm.mc)  api=$($mm.api)  pack_format=$($mm.packFormat)) ==="
  & $cogGen -Cell "$loader/26" -McVer "26" -Loader $loader
  if ($LASTEXITCODE -ne 0) { throw "cog-gen FAILED for $loader/26 -> $v" }
  $env:MC_DEP = $mm.dep
  $env:PACK_FORMAT = $mm.packFormat
  Push-Location $cell
  & ".\gradlew.bat" clean build "-PmcVersion=$($mm.mc)" "-PfabricApiVersion=$($mm.api)" --no-daemon
  $rc = $LASTEXITCODE; Pop-Location
  if ($rc -ne 0) { throw "Build FAILED for $v" }
  $jar = Get-ChildItem (Join-Path $cell "build\libs") -Filter "Chunksmith-Fabric-*.jar" |
         Where-Object { $_.Name -notmatch 'noshade|sources' } | Sort-Object LastWriteTime | Select-Object -Last 1
  $dest = Join-Path $dist ("Chunksmith-Fabric-{0}+mc{1}.jar" -f $modver, $v)
  Copy-Item $jar.FullName $dest -Force
  Write-Host "  -> $dest"
}

foreach ($t in $targets) {
  if ($m26.Contains($t)) { Build-26 $t } else { Build-PreCell $t }
}
Write-Host "Fabric build complete. Jars in $dist"
