# dist-prune.ps1 -- drop superseded jars from dist/ so it only ever holds the CURRENT mod version.
#
# WHY (2026-09-18): nothing ever removed a previous release from dist/, so it had stacked up
# to 226 jars across TEN versions. That is not merely clutter -- it changes what gets TESTED.
# deploy_profiles.py matches a jar per cell, and with two candidates for the same cell it reports
# `ambiguous (2: chunksmith-4.2.0+26.3-neoforge.jar, chunksmith-4.3.0+26.3-neoforge.jar)` and falls
# back to a FILENAME-VERSION tiebreak. A filename comparison then decides which binary a release is
# gated on. It happened to pick right on 2026-09-18; that is luck, not a guarantee.
#
# DESIGN -- prune by VERSION, never "empty the folder". This is the one point where the obvious
# reading of "clear dist on every build" is WRONG and would break the release pipeline:
# build-stage.ps1 runs the per-loader builders BACK TO BACK, so a builder that blanked dist/ on
# entry would delete the jars the previous builder had just produced in the SAME release run, and
# only the last loader's would survive. Removing only artifacts whose version differs from the
# current one is idempotent and order-independent: every builder can call it on entry, in any
# order, and a finished sweep leaves exactly one version behind.
#
# Ported from m1\minecraft-1.20-26.3\scripts\dist-prune.ps1, which is where this pattern and every
# guard below was paid for. Keep them.
#
# VERSION SOURCE -- the cells' own gradle.properties, which is what the builders read to stamp the
# jar. Never a matrix/manifest file: M1's equivalent was gitignored and unmaintained and still read
# 0.12 while the cells were at 0.16.0, so trusting it would have pruned the CURRENT jars and kept
# the stale ones.
#
#   pwsh scripts\dist-prune.ps1              # prune to the current version
#   pwsh scripts\dist-prune.ps1 -WhatIfOnly  # report what WOULD go, delete nothing
param(
  [switch]$WhatIfOnly
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$dist = Join-Path $root 'dist'
if (-not (Test-Path $dist)) { Write-Host "dist-prune: no dist/ yet, nothing to do."; return }

# --- resolve the current mod version from the cells -------------------------
$props = Get-ChildItem (Join-Path $root 'Fabric'),(Join-Path $root 'Forge'),
                       (Join-Path $root 'NeoForge'),(Join-Path $root 'Plugin') `
           -Filter 'gradle.properties' -Recurse -ErrorAction SilentlyContinue

# @(...) IS LOAD-BEARING, and this is not hypothetical -- it cost M1 37 jars on 2026-08-01.
# Sort-Object -Unique returns a SCALAR STRING when every cell agrees, which is the normal case.
# Indexing a string yields a CHARACTER, so $vers[0] became '4' instead of '4.3.0', every jar then
# mismatched "4", and the whole of dist/ was condemned. .Count is 1 on a bare string too, so the
# disagreement guard below does NOT catch it. The array subexpression is the fix; the shape gate
# further down is the backstop.
$vers = @($props | ForEach-Object {
  $m = Select-String -Path $_.FullName -Pattern '^version\s*=\s*(.+)$'
  if ($m) { $m.Matches.Groups[1].Value.Trim() }
} | Sort-Object -Unique)

if (-not $vers)        { throw "dist-prune: no version found in any cell gradle.properties -- refusing to delete." }
if ($vers.Count -gt 1) {
  # Cells disagree: a bump is half-applied. Pruning now could delete jars that are still wanted.
  Write-Host ("dist-prune: SKIPPED -- cells disagree on version ({0}). Finish the bump, then rerun." -f ($vers -join ', '))
  return
}
$cur = $vers[0]

# Shape gate: whatever we resolved must LOOK like a version before it may authorise deletions.
# A malformed value matches nothing in dist/ and would therefore condemn everything, so refuse
# loudly instead. This is the backstop for the scalar/array trap above.
if ($cur -notmatch '^\d+\.\d+(\.\d+)?') {
  throw "dist-prune: resolved version '$cur' is not a version -- refusing to delete anything."
}

# Emptying dist/ completely is EXPECTED right after a version bump: dist/ still holds only the
# previous release and none of the new one exists yet. So this is a NOTICE, not a veto -- vetoing
# would defeat the point of pruning BEFORE a run. What makes it safe is that $cur is corroborated
# twice: every cell gradle.properties agrees on it, and it passed the shape gate.
$present = @(Get-ChildItem $dist -File | Where-Object { $_.Name -match '^chunksmith-.+?\+' })
$keepers = @($present | Where-Object { $_.Name -match ('^chunksmith-' + [regex]::Escape($cur) + '\+') })
if ($present.Count -gt 0 -and $keepers.Count -eq 0) {
  Write-Host ("dist-prune: dist/ holds {0} artifact(s), none at {1} -- clearing for a fresh {1} run." -f $present.Count,$cur)
}

# --- prune anything that is not the current version -------------------------
# Artifact names are chunksmith-<ver>+<family>[-<loader>].jar, so the version is the text between
# the leading "chunksmith-" and the "+". Anything unparseable is LEFT ALONE rather than guessed at.
$doomed = Get-ChildItem $dist -File | Where-Object {
  $_.Name -match '^chunksmith-(.+?)\+' -and $Matches[1] -ne $cur
}

if (-not $doomed) { Write-Host ("dist-prune: dist/ already clean at {0}." -f $cur); return }

$mb = [math]::Round((($doomed | Measure-Object Length -Sum).Sum / 1mb), 1)
if ($WhatIfOnly) {
  Write-Host ("dist-prune: WOULD remove {0} file(s) ({1} MB), keeping {2}." -f $doomed.Count,$mb,$cur)
  $doomed | Group-Object { if ($_.Name -match '^chunksmith-(.+?)\+') { $Matches[1] } else { '?' } } |
    Sort-Object Name | ForEach-Object { Write-Host ("  {0,-10} {1} file(s)" -f $_.Name,$_.Count) }
} else {
  $doomed | Remove-Item -Force
  Write-Host ("dist-prune: removed {0} superseded file(s) ({1} MB); dist/ now holds {2} only." -f $doomed.Count,$mb,$cur)
}
