# build-all-plugin.ps1 -- build the Bukkit/Paper+Folia Plugin cell and copy the single shipped
# plugin jar into dist/ as chunksmith-<modver>-plugin.jar. The Plugin has NO cog-gen / mixins;
# it is a self-standing gradle multi-project (its own settings.gradle.kts + gradlew).
# One jar spans the whole MC range (api-version 1.21); it does NOT follow the per-version model.
#
# Usage:
#   pwsh build-all-plugin.ps1
# (The -Only param is accepted for call-site uniformity with the cell walkers; it is ignored.)
param([string]$Only)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $MyInvocation.MyCommand.Path
$plugin = Join-Path $repo "Plugin"
$dist = Join-Path $repo "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null

$modver = (Select-String -Path (Join-Path $plugin "gradle.properties") -Pattern '^version=(.+)$').Matches[0].Groups[1].Value
Write-Host "=== Plugin  (modver=$modver) ==="
Push-Location $plugin
& ".\gradlew.bat" clean build --no-daemon
$rc = $LASTEXITCODE
Pop-Location
if ($rc -ne 0) { throw "Plugin build FAILED (rc=$rc)" }

# The shipped jar is the shaded bukkit output: chunksmith-<modver>-plugin.jar.
$jar = Get-ChildItem (Join-Path $plugin "bukkit\build\libs") -Filter "*-plugin.jar" |
       Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $jar) { throw "No plugin jar found under Plugin\bukkit\build\libs" }
$dest = Join-Path $dist ("chunksmith-{0}-plugin.jar" -f $modver)
Copy-Item $jar.FullName $dest -Force
Write-Host "  -> $dest"
Write-Host "Plugin complete. Jar in $dist"
