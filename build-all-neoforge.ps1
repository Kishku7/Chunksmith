# build-all-neoforge.ps1 -- build the unified NeoForge source for every supported 26.x target.
# Usage: pwsh build-all-neoforge.ps1 [26.1 26.2]   (no args = all). MC 26.3 has no NeoForge yet.
param([string[]]$Versions)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $MyInvocation.MyCommand.Path
$nf   = Join-Path $repo "NeoForge\26"
$dist = Join-Path $repo "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null

# packFormat is the per-26.X resource pack_format (26.1=84, 26.2=88 per Memory/knowledge/pack-formats.md).
# Each emitted jar must carry its own value: NeoForge's strict datapack parser hard-fails a plain int
# > the running version's own format ("newer than 81 ... missing min_format/max_format"), so 26.1 MUST
# ship 84 (not 88), matching the running version's format exactly.
$matrix = [ordered]@{
  "26.1" = @{ nf = "26.1.0.15-beta"; nfRange = "[26.1.0.0-beta,)"; mcRange = "[26.1,26.2)"; packFormat = "84" }
  "26.2" = @{ nf = "26.2.0.1-beta";  nfRange = "[26.2.0-alpha,)"; mcRange = "[26.2,26.3)"; packFormat = "88" }
}
if (-not $Versions -or $Versions.Count -eq 0) { $Versions = @($matrix.Keys) }
$modver = (Select-String -Path (Join-Path $nf "gradle.properties") -Pattern '^version=(.+)$').Matches[0].Groups[1].Value

foreach ($v in $Versions) {
  $m = $matrix[$v]; if (-not $m) { throw "Unknown NeoForge version '$v'" }
  Write-Host "=== NeoForge build for $v  (neoforge=$($m.nf)  pack_format=$($m.packFormat)) ==="
  $env:NEOFORGE_RANGE = $m.nfRange
  $env:MC_RANGE = $m.mcRange
  $env:PACK_FORMAT = $m.packFormat
  Push-Location $nf
  & ".\gradlew.bat" clean build "-PneoforgeVersion=$($m.nf)" --no-daemon
  $rc = $LASTEXITCODE
  Pop-Location
  if ($rc -ne 0) { throw "NeoForge build FAILED for $v (rc=$rc)" }
  $jar = Get-ChildItem (Join-Path $nf "build\libs") -Filter "Chunksmith-NeoForge-*.jar" |
         Where-Object { $_.Name -notmatch 'slim|sources' } | Sort-Object LastWriteTime | Select-Object -Last 1
  $dest = Join-Path $dist ("Chunksmith-NeoForge-{0}+mc{1}.jar" -f $modver, $v)
  Copy-Item $jar.FullName $dest -Force
  Write-Host "  -> $dest"
}
Write-Host "All NeoForge builds complete. Jars in $dist"
