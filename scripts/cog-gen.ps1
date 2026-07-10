<#
.SYNOPSIS
  ChunkSmith Cog code-generation driver for one cell.

.DESCRIPTION
  Materialises a per-cell, per-MC-version copy of the shared_minecraft mixin/accessor
  source under <Cell>/gen/, with all version drift resolved by Cog (direct-compile,
  driven by _codegen/compat.py). Steps:

    1. Wipe + recreate <Cell>/gen/ (it is a build artifact, never committed).
    2. Copy shared_minecraft/src/main/java -> <Cell>/gen/ verbatim.
    3. Overwrite the drifting files with the Cog-instrumented copies from _codegen/cog_sources/.
    4. Add/remove the presence-gated files for this MC version:
         - ChunkStorageAccessor  : present <=1.21.10, absent 1.21.11/26
         - MinecraftServerAccess : present 26 only
    5. Run `cog -r -D mcver=<v>` over the Cog files (compat.py on sys.path).
    6. Regenerate <Cell>/src/main/resources/chunksmith.mixins.json's mixins[] + compatibilityLevel
       to match the files actually present for this version.

  The cell's build.gradle.kts srcDirs <Cell>/gen/ (NOT shared_minecraft directly), so it
  compiles the post-Cog output.

.PARAMETER Cell
  Path to the cell dir (e.g. Fabric/1.21.8), relative to the repo root or absolute.

.PARAMETER McVer
  The MC version key (e.g. 1.21.8, 26, 26.3-snapshot-2). Drives compat.py era selection.

.EXAMPLE
  ./cog-gen.ps1 -Cell Fabric/1.21.8 -McVer 1.21.8
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Cell,
    [Parameter(Mandatory = $true)][string]$McVer,
    # Loader for this cell. Only Forge changes behaviour: classic-SRG Forge (the ancient 1.20.1/
    # 1.20.4 line) needs a "refmap" key in chunksmith.mixins.json; every other loader/version omits
    # it (mojmap-native or loom-managed). Auto-detected from the cell path when not given.
    [Parameter(Mandatory = $false)][ValidateSet('Fabric','NeoForge','Forge')][string]$Loader
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent   # cog-gen.ps1 lives in scripts/; repo root is one up
$codegen  = Join-Path $repoRoot '_codegen'
$cogSrc   = Join-Path $codegen 'cog_sources'

# Resolve the cell path (allow relative-to-repo).
if ([System.IO.Path]::IsPathRooted($Cell)) {
    $cellPath = $Cell
} else {
    $cellPath = Join-Path $repoRoot $Cell
}
if (-not (Test-Path $cellPath)) { throw "Cell not found: $cellPath" }

# Resolve the loader (default: infer from the cell path, e.g. "Forge/1.21.4" -> Forge).
if (-not $Loader) {
    $leadSeg = ($Cell -replace '\\', '/').TrimStart('/').Split('/')[0]
    if ($leadSeg -in @('Fabric','NeoForge','Forge')) { $Loader = $leadSeg } else { $Loader = 'Fabric' }
}

$sharedJava = Join-Path $repoRoot 'shared_minecraft/src/main/java'
if (-not (Test-Path $sharedJava)) { throw "shared_minecraft java not found: $sharedJava" }

$genDir     = Join-Path $cellPath 'gen'
$genJava    = Join-Path $genDir 'src/main/java'
$mixinPkg   = 'com/kishku7/chunksmith/mixin'

Write-Host "[cog-gen] cell=$Cell mcver=$McVer"

# --- Step 1: clean gen/ ---
if (Test-Path $genDir) { Remove-Item -Recurse -Force $genDir }
New-Item -ItemType Directory -Force -Path $genJava | Out-Null

# --- Step 2: copy shared_minecraft java verbatim ---
Copy-Item -Recurse -Force (Join-Path $sharedJava '*') $genJava

# --- Step 3: overwrite drifting files with the Cog-instrumented copies ---
# Each entry is: cog_source basename => destination path RELATIVE to gen java root.
# These files carry Cog markers; the plain shared copies are replaced so cog can process them.
$driftMap = [ordered]@{
    'ServerChunkCacheMixin.java'                = 'com/kishku7/chunksmith/mixin/ServerChunkCacheMixin.java'
    'WorldGenRegionMixin.java'                  = 'com/kishku7/chunksmith/mixin/WorldGenRegionMixin.java'
    'StructureStartMixin.java'                  = 'com/kishku7/chunksmith/mixin/StructureStartMixin.java'
    'MinecraftServerMixin.java'                 = 'com/kishku7/chunksmith/mixin/MinecraftServerMixin.java'
    'IOWorkerAccessor.java'                     = 'com/kishku7/chunksmith/mixin/IOWorkerAccessor.java'
    'EntityStorageAccessor.java'                = 'com/kishku7/chunksmith/mixin/EntityStorageAccessor.java'
    'PersistentEntitySectionManagerMixin.java'  = 'com/kishku7/chunksmith/mixin/PersistentEntitySectionManagerMixin.java'
    'BossBarTaskFinishListener.java'            = 'com/kishku7/chunksmith/listeners/bossbar/BossBarTaskFinishListener.java'
    'BossBarTaskUpdateListener.java'            = 'com/kishku7/chunksmith/listeners/bossbar/BossBarTaskUpdateListener.java'
}
foreach ($name in $driftMap.Keys) {
    $src = Join-Path $cogSrc $name
    $dst = Join-Path $genJava $driftMap[$name]
    if (-not (Test-Path $src)) { throw "cog_source missing: $src" }
    Copy-Item -Force $src $dst
}

# --- Step 3b: loader entrypoint, single-sourced via cog (D15). Chunksmith<Loader>.java is emitted
# from ONE cog_source per loader (drift resolved by compat.py) into gen/, replacing the hand-copied
# per-cell src/main/java entrypoint (deleted 2026-07-10). ---
$entrypointName = "Chunksmith$Loader.java"
$entrypointSrc  = Join-Path $cogSrc $entrypointName
$entrypointDst  = Join-Path $genJava "com/kishku7/chunksmith/$entrypointName"
if (-not (Test-Path $entrypointSrc)) { throw "cog_source entrypoint missing: $entrypointSrc" }
Copy-Item -Force $entrypointSrc $entrypointDst

# --- Step 4: presence-gated files (query compat.py so the rule lives in ONE place) ---
Push-Location $codegen
try {
    $hasChunkStorage = (& python -c "import compat,sys; sys.stdout.write('1' if compat.has_chunk_storage_accessor('$McVer') else '0')")
    $hasSimpleRegionStorage = (& python -c "import compat,sys; sys.stdout.write('1' if compat.has_simple_region_storage_accessor('$McVer') else '0')")
    $hasMcServerAcc  = (& python -c "import compat,sys; sys.stdout.write('1' if compat.has_minecraft_server_access('$McVer') else '0')")
    $hangingClass    = (& python -c "import compat,sys; sys.stdout.write(compat.hanging_entity_class('$McVer'))")
    # JAVA_17 for 1.20.x and 26+ (as before). Also JAVA_17 for classic-Forge cells at MC 1.21/1.21.1:
    # Forge 51.0.0 (MC 1.21) bundles Mixin 0.8.5 which does not recognise JAVA_21 and hard-crashes at
    # launch ("compatibility level JAVA_21 which is not recognised"). Level = mixin language features,
    # not bytecode version, so JAVA_17 is safe for our plain-code mixins there.
    $forgeOldMixin = if ($Loader -eq 'Forge') { 'True' } else { 'False' }
    $compatLevel     = (& python -c "import compat,sys; v=compat._parse('$McVer'); sys.stdout.write('JAVA_17' if (v[0]>=26 or v[0]==1 and v[1]<=20 or ($forgeOldMixin and v[:3]<(1,21,2))) else 'JAVA_21')")
    $forgeNeedsRefmap = (& python -c "import compat,sys; sys.stdout.write('1' if compat.forge_needs_refmap('$McVer') else '0')")
} finally {
    Pop-Location
}

$chunkStorageDst = Join-Path $genJava (Join-Path $mixinPkg 'ChunkStorageAccessor.java')
if ($hasChunkStorage -eq '1') {
    # ChunkStorageAccessor is NOT in shared_minecraft (26-shaped); inject it for older versions.
    Copy-Item -Force (Join-Path $cogSrc 'ChunkStorageAccessor.java') $chunkStorageDst
    Write-Host "[cog-gen] + ChunkStorageAccessor (present on $McVer)"
} else {
    if (Test-Path $chunkStorageDst) { Remove-Item -Force $chunkStorageDst }
    Write-Host "[cog-gen] - ChunkStorageAccessor (absent on $McVer)"
}

$mcServerAccDst = Join-Path $genJava (Join-Path $mixinPkg 'MinecraftServerAccess.java')
if ($hasMcServerAcc -eq '1') {
    Write-Host "[cog-gen] + MinecraftServerAccess (present on $McVer)"
    # already copied from shared_minecraft; leave it.
} else {
    if (Test-Path $mcServerAccDst) { Remove-Item -Force $mcServerAccDst }
    Write-Host "[cog-gen] - MinecraftServerAccess (absent on $McVer)"
}

# SimpleRegionStorage landed at 1.20.5; on ancient (1.20.1/1.20.4) the class does not exist, so
# SimpleRegionStorageAccessor cannot compile -> drop it there. Present transitional and newer.
$simpleRegionStorageDst = Join-Path $genJava (Join-Path $mixinPkg 'SimpleRegionStorageAccessor.java')
if ($hasSimpleRegionStorage -eq '1') {
    Write-Host "[cog-gen] + SimpleRegionStorageAccessor (present on $McVer)"
    # already copied from shared_minecraft; leave it.
} else {
    if (Test-Path $simpleRegionStorageDst) { Remove-Item -Force $simpleRegionStorageDst }
    Write-Host "[cog-gen] - SimpleRegionStorageAccessor (absent on $McVer)"
}

# --- Hanging / BlockAttached presence swap (invalid-position log suppressor). ---
# shared_minecraft carries the 1.21.*+ shape (BlockAttachedEntityMixin, @Mixin BlockAttachedEntity).
# On the 1.20.* line the target class is HangingEntity, so swap in the HangingEntityMixin cog_source
# (same @Redirect body) and drop BlockAttachedEntityMixin. Both are Cog-processed (no markers today,
# but running cog is harmless and keeps them uniform if markers are added later).
$blockAttachedDst = Join-Path $genJava (Join-Path $mixinPkg 'BlockAttachedEntityMixin.java')
$hangingDst       = Join-Path $genJava (Join-Path $mixinPkg 'HangingEntityMixin.java')
if ($hangingClass -eq 'HangingEntity') {
    if (Test-Path $blockAttachedDst) { Remove-Item -Force $blockAttachedDst }
    Copy-Item -Force (Join-Path $cogSrc 'HangingEntityMixin.java') $hangingDst
    Write-Host "[cog-gen] + HangingEntityMixin / - BlockAttachedEntityMixin (1.20.* target on $McVer)"
} else {
    if (Test-Path $hangingDst) { Remove-Item -Force $hangingDst }
    # BlockAttachedEntityMixin already present from shared_minecraft; leave it.
    Write-Host "[cog-gen] + BlockAttachedEntityMixin (1.21.*+ target on $McVer)"
}

# --- Step 5: run Cog over the drift files (compat.py on PYTHONPATH via the -D define) ---
# Cog is invoked from _codegen so `import compat` resolves. -r rewrites in place.
$cogTargets = @()
foreach ($name in $driftMap.Keys) {
    $cogTargets += (Join-Path $genJava $driftMap[$name])
}
$cogTargets += $entrypointDst
Push-Location $codegen
try {
    $env:PYTHONPATH = $codegen
    & cog -r -D "mcver=$McVer" @cogTargets
    if ($LASTEXITCODE -ne 0) { throw "cog failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}

# --- Step 6: rebuild chunksmith.mixins.json to match present files ---
$mixinsJsonPath = Join-Path $cellPath 'src/main/resources/chunksmith.mixins.json'
if (-not (Test-Path $mixinsJsonPath)) { throw "mixins json not found: $mixinsJsonPath" }

# Enumerate mixin classes actually present in gen/ (server-side mixin dir, excluding client/).
$presentMixins = Get-ChildItem -File (Join-Path $genJava $mixinPkg) |
    Where-Object { $_.Extension -eq '.java' } |
    ForEach-Object { $_.BaseName } |
    Sort-Object

$mixinsArray = ($presentMixins | ForEach-Object { "    `"$_`"" }) -join ",`n"

# Classic-SRG Forge (ancient 1.20.1/1.20.4) needs a refmap key so the SpongePowered mixin loader
# can resolve mojmap<->srg target names at runtime; every other loader/version omits it (a
# stray refmap on a mojmap-native runtime would MISRESOLVE targets). forgeNeedsRefmap is only 1
# when -Loader Forge AND compat.forge_needs_refmap(mcver) is true.
$refmapLine = ''
if ($Loader -eq 'Forge' -and $forgeNeedsRefmap -eq '1') {
    $refmapLine = "  `"refmap`": `"chunksmith.refmap.json`",`n"
    Write-Host "[cog-gen] + refmap key (classic-SRG Forge on $McVer)"
} elseif ($Loader -eq 'Forge') {
    Write-Host "[cog-gen] - refmap key (mojmap-native Forge on $McVer)"
}

$jsonText = @"
{
  "required": true,
  "minVersion": "0.8",
  "package": "com.kishku7.chunksmith.mixin",
  "compatibilityLevel": "$compatLevel",
$($refmapLine)  "mixins": [
$mixinsArray
  ],
  "client": [
    "client.IntegratedServerMixin"
  ],
  "injectors": {
    "defaultRequire": 1
  }
}
"@
# ASCII, LF line endings.
$jsonText = $jsonText -replace "`r`n", "`n"
[System.IO.File]::WriteAllText($mixinsJsonPath, $jsonText, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "[cog-gen] wrote $($presentMixins.Count) server mixins + client -> chunksmith.mixins.json (compat=$compatLevel)"
Write-Host "[cog-gen] done: $genJava"
