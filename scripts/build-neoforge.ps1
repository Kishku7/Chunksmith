# build-neoforge.ps1 -- build ChunkSmith NeoForge jars into dist/.
# Covers BOTH the pre-26 per-version cells (NeoForge/<v>, cog-gen + gradlew) AND the unified 26 line
# (NeoForge/26, -P matrix + PACK_FORMAT range-form). MC 26.3 has no NeoForge yet. Lives in scripts/.
#
# Usage:
#   pwsh scripts/build-neoforge.ps1               # build EVERYTHING (all pre-26 cells + 26.1/26.2)
#   pwsh scripts/build-neoforge.ps1 1.21.8        # build one pre-26 cell
#   pwsh scripts/build-neoforge.ps1 26.2          # build one 26.X target
param([string[]]$Only)
$ErrorActionPreference = "Stop"
$repo   = Split-Path $PSScriptRoot -Parent
$loader = "NeoForge"
$root   = Join-Path $repo $loader
$dist   = Join-Path $repo "dist"
$cogGen = Join-Path $PSScriptRoot "cog-gen.ps1"
New-Item -ItemType Directory -Force -Path $dist | Out-Null

# 26-line matrix (unified NeoForge/26 cell; -P + PACK_FORMAT). pack_format per Memory/knowledge/pack-formats.md.
$m26 = [ordered]@{
  "26.1" = @{ nf = "26.1.0.15-beta"; nfRange = "[26.1.0.0-beta,)"; mcRange = "[26.1,26.2)"; packFormat = "84" }
  "26.2" = @{ nf = "26.2.0.1-beta";  nfRange = "[26.2.0-alpha,)"; mcRange = "[26.2,26.3)"; packFormat = "88" }
}
$preCells = Get-ChildItem $root -Directory | Where-Object { $_.Name -ne "26" } | Select-Object -ExpandProperty Name | Sort-Object

if ($Only) {
  $targets = $Only
  foreach ($t in $targets) {
    if (-not $m26.Contains($t) -and $preCells -notcontains $t) {
      throw "Unknown NeoForge target '$t' (pre-26 cells: $($preCells -join ', ') ; 26 line: $($m26.Keys -join ', '))"
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
  Write-Host "=== $loader/26 -> $v  (neoforge=$($mm.nf)  pack_format=$($mm.packFormat)) ==="
  & $cogGen -Cell "$loader/26" -McVer "26" -Loader $loader
  if ($LASTEXITCODE -ne 0) { throw "cog-gen FAILED for $loader/26 -> $v" }
  $env:NEOFORGE_RANGE = $mm.nfRange
  $env:MC_RANGE = $mm.mcRange
  $env:PACK_FORMAT = $mm.packFormat
  Push-Location $cell
  & ".\gradlew.bat" clean build "-PneoforgeVersion=$($mm.nf)" --no-daemon
  $rc = $LASTEXITCODE; Pop-Location
  if ($rc -ne 0) { throw "Build FAILED for $v" }
  $jar = Get-ChildItem (Join-Path $cell "build\libs") -Filter "Chunksmith-NeoForge-*.jar" |
         Where-Object { $_.Name -notmatch 'slim|sources' } | Sort-Object LastWriteTime | Select-Object -Last 1
  $dest = Join-Path $dist ("Chunksmith-NeoForge-{0}+mc{1}.jar" -f $modver, $v)
  Copy-Item $jar.FullName $dest -Force
  Write-Host "  -> $dest"
}

foreach ($t in $targets) {
  if ($m26.Contains($t)) { Build-26 $t } else { Build-PreCell $t }
}
Write-Host "NeoForge build complete. Jars in $dist"
