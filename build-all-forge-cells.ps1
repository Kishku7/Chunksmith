# build-all-forge-cells.ps1 -- walk the Forge/<v> cells, cog-gen (-Loader Forge) + gradlew clean build each,
# copy the shaded jar into dist/ with a line-keyed name (Chunksmith-Forge-<modver>+mc<v>.jar).
# There is no 26 Forge cell (26 is Fabric + NeoForge only), so ALL Forge/<v> dirs are walked.
#
# Usage:
#   pwsh build-all-forge-cells.ps1                # build every Forge cell
#   pwsh build-all-forge-cells.ps1 -Only 1.20.1   # build ONE cell (respects the 45s shell cap)
param([string]$Only)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $MyInvocation.MyCommand.Path
$loader = "Forge"
$root = Join-Path $repo $loader
$dist = Join-Path $repo "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null

# every Forge cell (no "26" Forge cell exists).
$cells = Get-ChildItem $root -Directory | Where-Object { $_.Name -ne "26" } | Select-Object -ExpandProperty Name | Sort-Object
if ($Only) {
  if ($cells -notcontains $Only) { throw "Cell '$Only' not found under $root (have: $($cells -join ', '))" }
  $cells = @($Only)
}

foreach ($v in $cells) {
  $cellPath = Join-Path $root $v
  $modver = (Select-String -Path (Join-Path $cellPath "gradle.properties") -Pattern '^version=(.+)$').Matches[0].Groups[1].Value
  Write-Host "=== $loader/$v  (modver=$modver) ==="
  # 1. regenerate the cogged gen/ tree for this cell (-Loader Forge -> refmap key on ancient SRG cells).
  & (Join-Path $repo "cog-gen.ps1") -Cell "$loader/$v" -McVer $v -Loader $loader
  if ($LASTEXITCODE -ne 0) { throw "cog-gen FAILED for $loader/$v (rc=$LASTEXITCODE)" }
  # 2. clean build.
  Push-Location $cellPath
  & ".\gradlew.bat" clean build --no-daemon
  $rc = $LASTEXITCODE
  Pop-Location
  if ($rc -ne 0) { throw "Build FAILED for $loader/$v (rc=$rc)" }
  # 3. copy the shaded jar to dist/ with the line-keyed name.
  $jar = Get-ChildItem (Join-Path $cellPath "build\libs") -Filter "Chunksmith-$loader-*.jar" |
         Where-Object { $_.Name -notmatch 'noshade|slim|sources|dev-shadow' } |
         Sort-Object LastWriteTime | Select-Object -Last 1
  if (-not $jar) { throw "No built jar found for $loader/$v under build\libs" }
  $dest = Join-Path $dist ("Chunksmith-{0}-{1}+mc{2}.jar" -f $loader, $modver, $v)
  Copy-Item $jar.FullName $dest -Force
  Write-Host "  -> $dest"
}
Write-Host "$loader cells complete. Jars in $dist"
