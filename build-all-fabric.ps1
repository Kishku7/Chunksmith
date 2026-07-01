# build-all-fabric.ps1 -- build the unified Fabric source for every 26.x target.
# Usage: pwsh build-all-fabric.ps1 [26.1 26.2 26.3]   (no args = all)
param([string[]]$Versions)
$ErrorActionPreference = "Stop"
$repo   = Split-Path -Parent $MyInvocation.MyCommand.Path
$fabric = Join-Path $repo "Fabric\26"
$dist   = Join-Path $repo "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null

$matrix = [ordered]@{
  "26.1" = @{ mc = "26.1.2";          api = "0.150.0+26.1.2"; dep = ">=26.1- <26.2" }
  "26.2" = @{ mc = "26.2";            api = "0.152.1+26.2";   dep = ">=26.2- <26.3" }
  "26.3" = @{ mc = "26.3-snapshot-2"; api = "0.153.2+26.3";   dep = ">=26.3- <26.4" }
}
if (-not $Versions -or $Versions.Count -eq 0) { $Versions = @($matrix.Keys) }

$modver = (Select-String -Path (Join-Path $fabric "gradle.properties") -Pattern '^version=(.+)$').Matches[0].Groups[1].Value

foreach ($v in $Versions) {
  $m = $matrix[$v]
  if (-not $m) { throw "Unknown version '$v'" }
  Write-Host "=== Fabric build for $v  (mc=$($m.mc)  api=$($m.api)) ==="
  $env:MC_DEP = $m.dep
  Push-Location $fabric
  & ".\gradlew.bat" clean build "-PmcVersion=$($m.mc)" "-PfabricApiVersion=$($m.api)" --no-daemon
  $rc = $LASTEXITCODE
  Pop-Location
  if ($rc -ne 0) { throw "Build FAILED for $v (rc=$rc)" }
  $jar = Get-ChildItem (Join-Path $fabric "build\libs") -Filter "Chunksmith-Fabric-*.jar" |
         Where-Object { $_.Name -notmatch 'noshade|sources' } | Sort-Object LastWriteTime | Select-Object -Last 1
  $dest = Join-Path $dist ("Chunksmith-Fabric-{0}+mc{1}.jar" -f $modver, $v)
  Copy-Item $jar.FullName $dest -Force
  Write-Host "  -> $dest"
}
Write-Host "All Fabric builds complete. Jars in $dist"