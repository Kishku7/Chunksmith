# Chunksmith Changelog

## [Unreleased]

## [3.14.0] - 2026-08-26

### Added

- **The LOD backchannel port is now yours to choose (`lodBackchannelPort`).** It was derived from
  the game port and nothing else -- always `serverPort + 1`, with no way to change it. That is fine
  on a box you own and impossible on a managed host that will not hand you the port next door
  (mod_support #19). The new key takes an explicit port; `0` keeps the old derived behaviour and
  remains the default, so an existing server is unaffected and nothing needs to be set.

- **`/cs set lodBackchannelPort <0|1024-65535>` -- and it takes effect immediately.** No restart.
  The backchannel stops, rebinds on the new port, and every connected client is told the new port
  and handed a fresh download credential in the same pass. Clients never had a port setting to
  begin with (the server has always advertised it on connect), so changing it costs a player
  nothing -- they do not need to know it happened.

- **`/cs status` -- one command that says what Chunksmith is doing.** Pre-gen state and the LOD
  backchannel together, because those are the two halves of the mod and the two questions operators
  actually arrive with: "is it generating?" and "why are my players getting no LOD?". The second
  answer previously lived in `/cslod status`, which does not appear in `/cs help`, so it was
  effectively hidden from the people who needed it.

### Fixed

- **Setting the backchannel to the game's own port silently disabled it, and stuck.** The bind
  refused the port -- correctly, and said so -- but the value had already been written to the config
  and the command answered "lodBackchannelPort is now 25565". The result was an operator told the
  setting had worked, a backchannel that was off, and a config that kept it off through every
  restart until somebody thought to look. It is refused now at the moment it is typed, nothing is
  saved, and the command says it was rejected.

- **A paused single-player pre-gen made no progress at all, and said nothing about it.** Since
  3.3.0. Pausing is how people run a big pre-gen -- the menu is open, nothing else is competing,
  the generator has the machine -- so this took the mod's best case and turned it into its worst
  one, with no error to explain it (mod_support #17).

  3.3.0 introduced a ticket safe point: all chunk-ticket work goes onto a queue that is drained
  once per tick from `MinecraftServer.tickServer`. That fixed a real crash, and on a dedicated
  server it fires every tick without exception. But `IntegratedServer.tickServer` calls
  `tickPaused()` and **returns without calling its superclass** whenever the game is paused, so on
  single-player the drain simply never ran. Ticket work piled up, no chunk was ever given a ticket,
  and the generator -- which runs on its own thread -- kept looping happily over work the server
  would never accept. Nothing threw, so nothing was logged.

  The paused tick now drains the queue before it runs housekeeping. This is safe precisely because
  the server is paused: the safe point exists to keep ticket changes out of the chunk-tick walk,
  and that walk does not happen while paused.

- **Resuming a pre-gen over ground you already generated is dramatically faster.** Chunksmith
  keeps an in-memory map of what it has generated, but that map starts empty every time the game
  starts -- so on the run where it matters most, re-running a selection after a restart, it knew
  nothing and asked the chunk system about every single chunk, one asynchronous round-trip at a
  time, purely to be told the chunk was already there. A region file's header describes 1024 chunks
  at once, so that question is now answered by reading a handful of files before the run begins:
  **5929 existing chunks identified in 183 ms where the old path spent about seven seconds**, and
  14898 in 301 ms. The saving scales with the selection, so a large resumed pre-gen that used to
  spend minutes deciding it had nothing to do now starts almost immediately.

  Only chunks recorded as fully generated count; anything else is generated as normal. If a region
  file cannot be read, those chunks are simply checked the old way, so the worst case of this change
  is the behaviour that was there before.

### Changed

- **The progress line no longer makes a healthy run look like a failing one.** Resuming a
  pre-gen reported a rate in the thousands of chunks per second that then fell steadily to a few
  dozen, and an ETA that grew to match. Nothing was slowing down: already-generated chunks counted
  toward the percentage but not toward the rate, so the figure started from a number that meant
  nothing and spent the next minute converging on the truth. Watching it, the only reasonable
  conclusion was that the mod was grinding to a halt -- which is what somebody reported
  (mod_support #17).

  The rate is now averaged over at least five seconds, so it climbs to the real figure instead of
  falling from an imaginary one, and the progress line says how many chunks were already there
  versus how many this run actually generated:

      Task finished for minecraft:overworld. Processed: 5929 chunks (100.00%),
      Total time: 0:00:00 -- 5929 already generated (skipped), 0 newly generated

- **A backchannel that cannot bind now says so at WARN, naming the port.** It was logged at INFO,
  worded as though nothing much had happened, and the mod quietly fell back to the slower in-band
  channel. That is the line an operator needed to see and did not: "Chunksmith is installed on both
  sides and no LOD ever arrives" reads like a broken mod, not like a closed port. The message now
  names the port that failed and says what to do about it.

- **The Bukkit/Paper plugin now says plainly that it cannot send LOD to players.** It writes a
  CSLOD store and has no channel to serve it -- that is by design, and it has always been true, but
  the startup line said "no renderer feed on this platform yet", which an operator reads as a
  remark about the server rather than as "your players will receive nothing". Somebody ran
  Chunksmith on their server and their client, got no LOD, and had to open an issue to find out
  that no combination of mods could have worked (mod_support #18). The message now names the
  consequence, names the build that does support it, and says outright that no client mod is a
  workaround. The store is still written and is still worth having: it becomes servable the moment
  that server moves to the mod build.

- **`/cs status` reports the effective backchannel port**, including whether it was derived or set,
  so the answer to "what port should I be opening?" comes from the server rather than from
  arithmetic.

## [3.13.0] - 2026-08-21

The first release since 3.4.1. Versions 3.5.0 through 3.12.0 were built and tested in-house but
never published, so everything they contain arrives here -- this entry covers the whole line. The
short version: a pre-gen could hold the entire world open until the server died, and that is fixed;
throughput on a multi-core server is up substantially; and the mod now tells you what it is doing
instead of leaving you to infer it from tick times.

### Fixed

- **A pre-gen could hold the entire world open, and the server would die to the tick watchdog.**
  This is the headline fix. A held chunk does not cost one chunk: its ticket sits at FULL level and
  vanilla's distance manager propagates that level outward one ring at a time, so a single held
  ticket keeps a whole neighbourhood resident. Measured on a live 1.21.11 server, **20 held tickets
  held 3,507 resident chunks** -- roughly 25 resident chunks per held ticket at pre-gen clustering.
  3.4.1 had no cap on the settle frontier at all, which is how a real server reached **75,045
  resident chunks**, pinned its heap at 107% of an 8 GB `-Xmx`, and collapsed to 5 chunks per second
  with a 42-hour ETA. The frontier is now bounded by `pregenSettleMaxHeld` (default 256), and memory
  is bounded directly by `throttleMaxHeapPercent` rather than by any proxy.

- **The unload backlog outlived the task that created it.** Housekeeping was armed by a running
  task, so when a run paused or finished, whatever backlog remained was handed to vanilla's budgeted
  pass -- which does almost nothing once the tick is already over budget, and the tick was over
  budget precisely because of the retained chunks. The backlog was then permanent until a restart.
  Housekeeping is now armed while a BACKLOG exists, not while a task exists.

- **Three separate mechanisms were suppressing their own recovery.** Releases are driven by new
  chunks arriving, so gating dispatch also stopped the releases that would have opened the gate; a
  gate holding dispatch was given the small unload budget, so nothing unloaded and it never
  reopened; and the settle frontier froze at its cap during a hold while holding the very tickets
  that blocked recovery. All three now release, drain and re-arm from the tick instead.

- **`/cs debug` answered "An unexpected error occurred trying to execute that command".** The
  residency snapshot contained a literal percent sign and the message path runs through
  `String.format`.

- **`/cs continue` reported "Task already started!" while the previous run was still draining**,
  leaving the operator with a stopped pre-gen and a message saying the opposite. It now says what is
  actually happening.

- **The ticket diagnostics were compiled into the shared mixin but written against 1.21.11-and-newer
  shapes**, so this whole line of work could only build for 1.21.11. They are now gated on the
  presence of those shapes and every supported version has a jar again.

- **A 26-line drift:** `ChunkPos` became a record and lost its packed-long constructor.

- **Config range-clamp warnings went nowhere.** `GsonConfig` and `TaskScheduler` logged through
  `java.util.logging`, which no loader routes into the game log, so a clamped value was silently
  corrected with the explanation discarded. Both now use slf4j like the rest of the mod.

### Added

- **`dispatchMaxConcurrent` -- how many chunk requests stay in flight, and the single biggest
  throughput lever in the mod.** It was previously fixed at 50 and reachable only through an
  undocumented `chunksmith.maxWorkingCount` system property. Nothing was saturated at that cap: CPU
  sat at 255 of 800 percent, workers at ~24 percent, the server thread at ~10. Measured on an
  8-core dedicated server: **50 -> 31.6 chunks/sec, 200 -> 43.9, 600 -> 42.4.** So 200 is the knee,
  worth **+39 percent**, and beyond it the remaining ceiling is vanilla promoting about 2.2 chunks
  per tick at 20 tps. The default now scales with the machine -- `min(400, max(50, cores * 25))` --
  with a floor of 50 so no server gets slower than it was. Live-settable with `/cs set`.

- **A pre-gen now pauses when the server cannot sustain it, and resumes when it can.**
  `autoPauseOnOverload` (default true) and `autoPauseGraceSeconds` (default 120). A gated run on an
  overloaded server does not stop, it stutters -- measured at 60 chunks in two minutes, which is
  indistinguishable from a hang, keeps the server under load throughout, and produces nothing. Both
  directions require the condition to hold continuously for the grace period. A human `/cs pause`
  outranks it in both directions and is never auto-resumed.

- **`throttleMaxHeapPercent` (default 85) bounds the run by measuring memory.** Three earlier
  releases tried to bound a pre-gen with a proxy -- queued writes, LOD queue depth, resident chunks,
  chunks added since the run started -- and each was wrong on a live server in a different way. A
  chunk is worth wildly different amounts of heap depending on what came with it, so no chunk count
  means the same thing on two worlds.

- **The mod now reports what it is doing.** `/cs debug` prints a chunk-residency snapshot, a ticket
  ledger, and an unload breakdown with a plain-English verdict; a drain logs one line when it starts
  and one when it ends, naming the outcome and how many chunks it freed, at WARNING level when
  chunks are left behind. Previously the only way to read residency at all was to set a low cap,
  start a run, and read the backpressure line -- which perturbs the thing being measured.

### Changed

- **`throttleMaxAddedChunks` now defaults to 0 (off).** On a live server it closed at 22,000 chunks
  with the heap at 40 percent and stuttered a healthy run down to 60 chunks per two minutes. A
  pre-gen's resident set is its FULL chunks plus the mandatory worldgen context ring around each of
  them, and that frontier's perimeter legitimately grows with the radius. It remains available as an
  expert knob.

- **The tick-budget baseline now requires 15 consecutive idle ticks**, and probes every 60 seconds
  instead of 120. A one- or two-tick gap between chunks is not idle -- it is still paying for the
  chunk that just landed -- and sampling those taught the baseline the mod's own aftermath, observed
  as a baseline reading 49ms and then 116.8ms with no change in load.

- **The LOD startup message no longer claims LOD generation costs ~16 percent of pre-gen speed.**
  It does not: measured over matched windows, **36.1 chunks/sec with LOD on against 34.2 with it
  off**. The message now reports the measured cost as none.

- The README documents nine config keys that previously had no documentation anywhere.

### Removed

- **The stale-ticket purge, and its `purgeStaleVanillaTickets` and `staleTicketThreshold` keys.** A
  controlled A/B put residency at 16,800 with it on and 16,820 with it off -- a 0.1 percent
  difference -- while it evicted 10,765 tickets. It recovered nothing and was removed rather than
  kept as reassurance.

## [3.7.1] - 2026-08-20

### Fixed

- **Auto-pause could not see the situation it exists for.** 3.7.0 triggered only when one of
  Chunksmith's own throttle gates was holding dispatch. On a live server with the chunk gate off
  (its default) and the heap below its threshold, nothing of ours ever closed while the server
  logged **twelve "Can't keep up" warnings and generation fell to 5 chunks per second** -- and
  auto-pause sat idle through all of it.

  The condition is now "the server cannot sustain this run", which is either of our gates holding
  **or** the tick running past twice the target the throttle steers to. Load that has nothing to do
  with Chunksmith still means a pre-gen should not be adding to it. Twice the target is well clear of
  a healthy pre-gen and well short of a server that is merely busy.

## [3.7.0] - 2026-08-20

### Added

- **A pre-gen now stops when the server cannot sustain it, and starts again when it can.** On an
  overloaded server a gated run does not stop -- it stutters, because the never-wedge valve lets
  through about a second of work every grace period. Measured on a live server: **60 chunks in two
  minutes**. That is indistinguishable from a hang to anyone watching, it keeps the server under load
  throughout, and it produces nothing. Chunksmith now pauses, says why, and resumes by itself once
  the server has been healthy for the grace period.

  `autoPauseOnOverload` (default **true**) and `autoPauseGraceSeconds` (default 120, range 10-3600),
  both settable from `/cs set` and both editable in the config, live -- turn it off and a run pushes
  on regardless.

  Both directions require the condition to hold CONTINUOUSLY for the grace period, and any moment to
  the contrary resets the clock: pausing on the first bad second would stop a run for a passing
  autosave, and resuming on the first good second would restart it into the same wall. "Healthy"
  means the tick keeping up AND the heap having real headroom, because either alone recovers before
  the other.

  **A human `/cs pause` outranks it in both directions** -- it is never auto-resumed, and it clears
  any outstanding auto-pause so a later recovery cannot restart a run somebody deliberately stopped.

## [3.6.1] - 2026-08-20

### Fixed

- **The diagnostic's own level thresholds were hand-written and wrong.** It classified a chunk as
  droppable at level 44, but `ChunkLevel.MAX_LEVEL` is `33 + RADIUS_AROUND_FULL_CHUNK` -- 41 on this
  version -- so two whole levels of genuinely-droppable chunks were being reported as loaded, and the
  "droppable" figure it printed was noise. It now reads `ChunkLevel.byStatus(FULL)` and
  `ChunkLevel.isLoaded` directly, which is both correct here and correct on every other MC version
  instead of only the one the numbers were guessed for.

  With the buckets right, the picture inverts: **droppable = 0**. Every resident chunk is either FULL
  or the worldgen context ring that a FULL chunk requires. Nothing is sitting around waiting to be
  unloaded, and vanilla's unload path is healthy.

### Note on what the resident count means

A pre-gen's resident set is its FULL chunks plus the mandatory context ring around each of them, and
the frontier's perimeter grows with the radius being generated. On a radius-7500 square that reaches
tens of thousands of chunks legitimately. It is not a leak, which is why `throttleMaxAddedChunks`
defaults to 0 and memory is bounded by `throttleMaxHeapPercent` instead. What must stay bounded is
the number of FULL chunks held at once -- the dispatch limit and `pregenSettleMaxHeld` -- and that is
what 3.4.1 got wrong by holding an uncapped settle frontier.

## [3.6.0] - 2026-08-20

**This is the release that actually fixes the unbounded chunk retention reported on 2026-08-19.**
Everything between 3.5.0 and 3.5.9 was built against theories that measurement later disproved; they
are left in the changelog as written because each one is a real, if secondary, improvement, and
because the wrong turns are worth reading.

### Fixed

- **A pre-gen could hold the entire world open. The cap that was supposed to stop it was counted in
  the wrong unit.**

  A held chunk does not cost one chunk. Its ticket sits at FULL level, and vanilla's distance manager
  propagates that level outward one ring at a time, so a single held ticket keeps a whole
  neighbourhood resident with it. Measured on a live 1.21.11 server: **20 held tickets -> 3,507
  resident chunks; ~400 held -> 10,167 resident** -- roughly 25 resident chunks per held ticket at
  pre-gen clustering.

  `pregenSettleMaxHeld` counts TICKETS. Its 3.5.0 default of 8192 therefore authorised on the order
  of **two hundred thousand** resident chunks, and 3.4.1 -- which had no cap at all, because the
  setting did not exist yet -- let the window hold the entire un-closed frontier. That is how a live
  server reached **75,045 resident chunks** and died to the tick watchdog.

  The default is now **256** (about 6,000 resident chunks on that measurement, which an 8 GB heap
  carries comfortably), the minimum is 16, and the setting now documents the multiplier everywhere it
  appears. It is a memory setting, and it now says so.

- Nothing was wrong with ticket removal, the unload pass, level propagation, or the settle window's
  release logic. Each was investigated in turn and each was innocent; the counts that proved it are
  the ones `/cs debug` now prints.

### How it was finally found, since the method is the transferable part

Six theories were reasoned out and shipped against before anything was counted, and all six were
wrong. What ended it was measuring the thing itself rather than a proxy: first Chunksmith's own
ticket ledger (20 outstanding against 3,507 resident -- so not ours), then a tally of every ticket on
every resident chunk by type (**138 tickets holding 3,507 chunks** -- so not a leak at all, but a
multiplier). A count that cannot be read two ways beats any amount of inference from tick times.

## [3.5.5] - 2026-08-20

### Added

- **Chunksmith can now say WHY a drain is not freeing chunks.** A drain ran its full ten-minute
  ceiling and freed 30 chunks out of 22,067, with the pregen paused, and nothing in the mod could
  distinguish the two possible causes: chunks not ELIGIBLE to unload (their tickets are still held)
  versus eligible work not getting done. Three releases were spent making the unload pass faster on
  the assumption it was the second. Reading `ChunkMap.processUnloads` shows it cannot be: its
  `toDrop` loop consults no budget at all, and its `unloadQueue` drain runs
  `while (unloadQueue.size() - 2000 > 0 || haveTime())`, so vanilla drains that queue down to 2000
  entries even when `haveTime` is false.

  `/cs debug` now reports `visible`, `toDrop`, `unloadQueue`, `pendingUnloads` and `hasTickets`,
  with a plain-English verdict, and the drain start/finish lines carry the same numbers. `toDrop`
  is the one that matters: zero, while chunks are resident, means nothing is eligible and the
  problem is tickets. All five read identically on every supported version (1.20.1 through 26.3),
  so this needs no version handling.

### Changed

- **`throttleMaxAddedChunks` now defaults to 0 (off).** On a live server it closed at 22,000 chunks
  while the heap sat at 40 percent, and stuttered a healthy run down to 60 chunks per two minutes.
  A chunk count cannot be tuned to mean the same thing on two worlds. Memory is governed by
  `throttleMaxHeapPercent` and load by tick health; this remains available as an expert knob.

## [3.5.4] - 2026-08-20

Three fixes for things the first live test of the 3.5.2/3.5.3 gates exposed within ten minutes.

### Fixed

- **The drain's own log lines never appeared.** `ChunkResidency` logged through
  `java.util.logging`, which the loaders do not route into the game log, so 3.5.3's drain lifecycle
  reporting ran and went nowhere. Switched to slf4j, which every other logging class in the mod
  already uses. (`GsonConfig` and `TaskScheduler` still use JUL and are equally invisible -- notably
  `TaskScheduler` logs uncaught generation-task exceptions that way.)

- **A gate holding dispatch got the SMALL unload budget, so nothing unloaded and the gate never
  reopened.** The larger idle budget was conditional on a post-run drain only. But a mid-run hold is
  the same situation -- nobody is playing, nothing is generating, and unloading is the only thing
  that can end it. Measured: a residency hold ran for the full 120-second never-wedge window and the
  resident count went UP by 196 instead of falling, so the run resumed straight into the gate again
  and stuttered. The full budget now applies whenever nobody is online and generation is stopped by
  one of our own gates, and housekeeping stays armed for the same window.

- **The settle frontier froze at its cap during a hold and blocked its own recovery.** A held chunk
  is released once its neighbours exist, and neighbours only arrive from new dispatches -- so with
  dispatch gated the frontier can never complete, and it sits at `pregenSettleMaxHeld` holding
  tickets that prevent the unloading the gate is waiting for. Entering a hold now hands every held
  ticket back, without retiring the window: when the gate opens, the next arrivals build a fresh
  frontier. This is the third distinct form of the same bug -- a mechanism suppressing the recovery
  it is waiting on -- and the general lesson is that anything driven by "new work arriving" must not
  be load-bearing on a path that stops new work arriving.

## [3.5.3] - 2026-08-20

### Fixed

- **`/cs debug` answered "An unexpected error occurred trying to execute that command".** The new
  residency snapshot contained a literal percent sign, and `Sender.sendMessagePrefixed` runs its
  message through `String.format`, which read it as a format specifier and threw. The snapshot no
  longer contains one, and a test now asserts that and then formats the string to prove it. The
  original tests did not catch this because they exercised `describe()` in isolation -- the bug lived
  in the seam between it and the sender, which is where this kind of bug always lives.

## [3.5.2] - 2026-08-20

Instrumentation, plus the throttle change the instrumentation immediately justified: this release
stops trying to bound a pregen by counting chunks and starts measuring the heap.

3.5.1 fixed the orphaned unload backlog and was, on a real server, largely right: no tick overruns,
the heap down from 107% of an 8 GB Xmx to a third of it, and no restart needed. But an idle server
still did not return all the way to its pre-run tick cost, and there was **no way to tell whether the
drain was still working, had succeeded, or had quietly given up** -- only to infer it from tick times.
A signal nobody can read is a signal that cannot be debugged, which is how the 3.5.0 defect survived
review in the first place.

### Added

- **The drain now says what it is doing.** One line when a run's drain starts (chunks resident, and
  how many of them this run added) and one when it ends, naming the outcome -- back to where the run
  started, stopped falling because the rest is pinned by something that is not ours, or the
  ten-minute ceiling -- with how many chunks were freed, how many remain above the starting point,
  and how long it took. Two lines per run. It is logged as a WARNING rather than information when
  chunks are left behind, because that is the case an operator actually needs to see, and it is
  exactly the case that could not be distinguished from success before.

- **`/cs debug` prints a chunk-residency snapshot** on every invocation, whichever way the toggle
  went: resident count, the run's baseline, how many this run added, whether a drain is in progress,
  and how the last one ended. Until now the only way to read residency was to set a low
  `throttleMaxAddedChunks`, start a run and read the backpressure line -- which perturbs the thing
  being measured and cannot be done at all on an idle server.

### Fixed

- **Bound the run by MEASURING MEMORY instead of counting chunks.** Three releases have tried to
  bound a pregen with a proxy -- queued writes, LOD queue depth, resident chunks, chunks added since
  the run started -- and each was wrong on a live server in a different way. An absolute chunk cap
  fired on chunks that were never ours. A delta cap did not fire at all while the heap filled to
  107% of `-Xmx`, because the run had been resumed on an already-loaded server. A chunk is worth
  wildly different amounts of heap depending on the entities and block entities that came with it,
  so no chunk count means the same thing on two worlds. New `throttleMaxHeapPercent` (default 85,
  0 disables) stops dispatch when the heap stays above the threshold for several consecutive
  samples, and resumes only once there is 15 points of headroom again. Confirmation over several
  samples is what stops ordinary uncollected garbage from tripping it. The chunk counters remain,
  for the cases they are genuinely good at.

- **A drain could be convicted of stalling when it was never given a budget to work with.** The
  unload floor is 2 ms while players are online and 10 ms when the server is empty; the
  "no progress for 30 seconds" give-up made no distinction, so a drain running at 2 ms next to a
  player loading chunks was almost guaranteed to be declared stuck. Measured: a drain gave up while
  one player was online, that player logged off, and the server then sat at **71.5 ms per tick with
  the heap at 107%** and did not recover until it was restarted. The no-progress clock now only
  advances while the drain is actually being given the full budget, so a give-up can only ever mean
  "we tried properly and it would not move".

- **A drain was a one-shot that could be lost for ever.** Nothing re-armed it when the thing
  blocking it went away. The moment the last player leaves, an outstanding drain is resumed.

- **`/cs debug on` and `/cs debug off` were rejected by the command parser.** The command has always
  accepted an explicit on/off argument, but only the bare toggle was registered in the loaders'
  command trees, so anything after `debug` failed to parse. Registered on all three loaders.

## [3.5.1] - 2026-08-20

3.5.0's residency work was right about the problem and wrong in two ways that only a live server
could show. Both were found within an hour of deploying it, on the same world that produced the
original report. 3.5.0 was never published to Modrinth; the config key renamed below therefore
exists on exactly one server, which has been updated by hand.

### Fixed

- **A finished pregen orphaned its own unload backlog, and the server never recovered without a
  restart.** Measured on a 1.21.11 dedicated server: **39,064 chunks still resident nineteen minutes
  after the pregen was paused, with no players online** -- 51.4 ms per tick (P95 59.3), a stall of
  almost exactly 2000 ms every ~65 seconds, and the heap pinned at 8.7 GB of an 8 GB `-Xmx`. After a
  restart the same server measured 0.2 ms per tick and 792 MB. A 19.5-hour idle log from the same
  build contains zero "Can't keep up", so it is running a pregen that causes this, not the build
  sitting there.

  The cause was 3.5.0's own change. It armed chunk-system housekeeping every tick *while a task was
  active*; when the task ended, that stopped, and the remaining backlog was left to vanilla's
  `ChunkMap.tick(haveTime)` -- which does approximately nothing once the tick is over budget, which
  it is precisely BECAUSE of the retained chunks. The 2 ms unload floor added in 3.5.0 could not
  help, because on an idle server nothing armed the hook that would have spent it.
  - `ChunkResidency` now publishes every tick, running or not, and carries a DRAIN state: ending a
    task declares a debt that keeps the unload pass armed until residency is actually back to where
    the run started. The drain ends on success, or when the count has not moved for 30 s (the
    remainder is pinned by players or another mod and no further pass will shift it), or after ten
    minutes flat. All three exits are unit-tested against an injected clock.
  - The unload floor is now 2 ms normally and **10 ms when nobody is online and a drain is
    outstanding**. 2 ms is tuned for "do not disturb a live server", which is the wrong constraint
    for an empty one. Still bounded and still self-limiting, so it cannot become the unbounded pin
    that 3.2.0 fixed.

- **The residency gate measured the wrong thing and closed on chunks that were not ours.**
  `throttleMaxLoadedChunks` capped the ABSOLUTE resident count, so on a server already sitting near
  the cap it tripped immediately and stayed tripped, stuttering a run at the never-wedge interval to
  roughly 0.9 chunks/sec -- worse than the runaway it was added to prevent. Renamed to
  **`throttleMaxAddedChunks`** and measured as a DELTA against residency captured when the run
  starts. What a pregen can be held responsible for is what it added; everything already there
  belongs to the server. The backpressure message now reports both numbers.

- **The settle window could only release when new work arrived, so holding dispatch stopped it
  draining.** `ChunkSettleWindow.releaseDue()` had exactly one production caller -- inside
  `offer()`. With the residency gate holding dispatch there are no arrivals, so the frontier could
  not shrink, so residency could not fall, so the gate stayed shut: it suppressed its own recovery.
  Live windows are now registered with `ChunkSettleSupport` and pumped once per server tick, so a
  release depends on time passing rather than on more work being dispatched. Drained windows drop
  themselves rather than requiring the adapter to remember to deregister.

### Changed

- `throttleMaxLoadedChunks` is now `throttleMaxAddedChunks` (default 20000, 0 disables). Its meaning
  changed, so the key changed with it rather than silently redefining what an operator had already
  set.

## [3.5.0] - 2026-08-19

### Fixed

- **A pregen could drive the server to 75,045 resident chunks and then keep going, until the
  watchdog killed it.** Observed live on a 1.21.11 Fabric server: a run at 6.95% was holding about
  ten times the chunks its sweep frontier could account for, the I/O throttle was pinned at its
  floor (`1/50`), throughput had fallen to ~5 chunks/sec and the estimate had grown past 42 hours.
  The crash itself came from elsewhere -- an unrelated datapack tick function walking 11,613
  entities -- but a server in this state has no margin left for anything else to go wrong, and
  nothing in Chunksmith could see it happening.

  Three separate mechanisms, each individually defensible, formed a loop that only tightened:
  - **The throttle measured everything except what mattered.** Tick time, per-chunk latency, the
    write queue and the LOD sink all measure the rate work goes IN. Nothing measured what had piled
    up and not gone OUT, so the resident chunk count was invisible to every signal the mod had.
  - **A server that has fallen behind stops unloading entirely.** 3.2.0 fixed a 60-minute CPU pin
    (mod_support #11) by handing vanilla's own `haveTime` budget to the unload pass instead of a
    hardcoded "unlimited". That was correct, and it created the opposite failure: once the tick is
    over budget, `haveTime` is false for the whole tick, so the unload pass does nothing at all.
    More resident chunks then cost more to tick, and the server falls further behind.
  - **Backing off made it worse.** A settle window's ticket releases are driven by new arrivals, and
    housekeeping was only armed by ticket mutations. Cutting dispatch to 1 therefore also cut the
    rate at which chunks were handed back -- the throttle was throttling the cure.

  What changed:
  - Chunk residency is now a first-class throttle signal (`ChunkResidency`, published once per tick
    from the server thread). Past `throttleMaxLoadedChunks` (new, default 20000, 0 disables)
    dispatch stops entirely until the server has unloaded back to half of it -- the same hard gate
    the write-queue backlog already had. A run can never wedge on it: if residency stays over the
    cap for two minutes the gate opens anyway and says so, because a Chunksmith that silently
    stopped working is worse than a slow one.
  - The unload pass now gets a guaranteed floor of 2 ms per tick (`haveTime` OR 2 ms, whichever is
    greater), shared across dimensions rather than per-dimension. A healthy server behaves exactly
    as it did in 3.2.0 -- `haveTime` is true and the floor is never consulted. A starved one drains
    its backlog steadily instead of not at all. 2 ms of a 50 ms tick cannot pin a core, which is the
    failure 3.2.0 was fixing.
  - Chunk-system housekeeping is armed every tick while a run is active, not only when a ticket
    moves.

- **The settle window could hold chunks for an entire run, and on a resumed world usually did.**
  Its rule -- release a chunk once all eight neighbours exist -- bounds the frontier only while
  every held chunk eventually gets its ninth neighbour. Chunks the run SKIPS are never offered, so
  a chunk bordering already-generated ground or the edge of the shape never completes and was held
  until the task finished. That makes the leak worst on exactly the common case: re-running a
  pregen over a world that is already partly done. `pregenSettleMaxHeld` (new, default 8192,
  0 = unbounded) caps the frontier and releases the oldest held chunk when it is exceeded -- age
  being the evidence that a neighbourhood is not coming. Evictions are counted separately from
  ordinary releases, because a run with many of them is a run worth looking at.

### Added

- **Chunksmith now says so when a dedicated server is carrying an LOD renderer it does not need.**
  One warning at startup naming what it found (Distant Horizons, voxy, or both), why it is not
  needed -- Chunksmith builds its own LOD data and serves it to each player's client, which injects
  it into the renderer THEY have -- and when keeping it is still the right call (serving vanilla DH
  clients that do not have Chunksmith). It is advice, not enforcement: nothing is disabled, nothing
  is declared incompatible, and Distant Horizons is never something Chunksmith `breaks` -- it is a
  renderer we feed. Nothing is said on an integrated server, which is a client and does need one.
  Wired on all three mod loaders.

- New settings, both reachable from `/cs set` like every other config key:
  `throttleMaxLoadedChunks` and `pregenSettleMaxHeld`.

## [3.4.1] - 2026-08-18

### Fixed

- **LOD stopped being injected after the first disconnect, for the rest of the game session.**
  Join a server, leave it, join again -- and from then on every LOD injection ended instantly with
  `the player left <dimension> while its LOD data was still being injected`, in every dimension, no
  matter where the player actually was. The regions downloaded correctly and were thrown away at the
  last step. Restarting Minecraft was the only way out, and only until the next disconnect.
  Introduced in 3.3.0; 3.3.0 and 3.4.0 are both affected.
  - 3.3.0's fix for mod_support #16 gave the injector a `stopRequested` flag, set on disconnect and
    cleared by an `arm()` call. Only the IN-BAND FALLBACK path called `arm()`. The HTTP backchannel
    -- the path used whenever the server has its port open, which is almost always -- never did, so
    the flag stayed true forever after the first disconnect.
  - The flag is now a per-session generation counter. An injection reads the current generation when
    it starts, so a stop can only end the work it was aimed at and no call site has to remember to
    arm anything. `LodInjector.arm()` is gone rather than fixed: it was a pairing that could be got
    wrong, and it was.
  - The abort message now names which of the two conditions fired. It used to announce a dimension
    change for both, so the session-ended case reported a portal the player had never walked through
    -- which is most of why this took a full session to spot.
  - Everything the mod does that is not client-side LOD injection was unaffected: pregeneration,
    the store, and the server's serving of it all behaved correctly throughout.

## [3.4.0] - 2026-08-13

### Fixed

- **A player with Chunksmith but no LOD renderer could not reach their own client settings.**
  3.3.0 added `/cslod set`, which is a server command that relays to the player's client over the
  `chunksmith:lod` channel -- and the server only relays to a client it has actually heard from.
  The client never said hello unless voxy or Distant Horizons was installed, so for everyone else
  the command answered with a refusal, and the two settings in `config/chunksmith-lod.properties`
  were reachable only by editing the file and restarting. That is precisely the situation the house
  rule exists to end, and the refusal text said so out loud rather than fixing it.
  - The client now sends its hello with **no renderer installed** as well. The wire format has
    always carried `hasVoxy`/`hasDh` and the server has always modelled both-false, so this needed
    no protocol change and older servers answer it exactly as they always did.
  - It stays an INTRODUCTION and nothing more: with no renderer the client never asks for an index,
    a summary or a region, and never enters the empty-store retry loop. The server answers with an
    empty hello, mints no token, scans no store, records no radius, and does not add the player to
    the store watch -- so nothing is sent that nobody could draw.
  - The server records the greeting (which is what `/cslod set` tests) and logs it once per player
    per session, naming the no-renderer case, rather than the previous silence.
  - The refusal text lost its "or you have no LOD renderer" half, because that is no longer a cause
    of it, and pointing a player at a config file for a problem they no longer have is worse than
    saying nothing.
  - Loading the client config at hello is now load-bearing rather than incidental: it is what tells
    the config where its file lives, and without it a `/cslod set` from a no-renderer client would
    have changed the value in memory and written no file at all.

- **On Minecraft 26, Chunksmith's chunk-system housekeeping never actually ran.** Every tick
  Chunksmith gives the chunk system a second nudge: push through the chunk tickets a pre-gen has
  just handed back so the finished chunks are free to go, run a time-budgeted unload pass so they
  actually leave memory, flush pending block changes, and tick the entity manager. On 1.20.x and
  1.21.x that nudge is attached to the END of the server tick, and it fires every tick. On the 26
  line it had been attached to a point the server tick only reaches while it is PAUSING because
  nobody is online -- a path that returns immediately, before the rest of the tick even runs. Two
  things guarantee that path is never taken during a pre-gen: `pause-when-empty-seconds=0` skips
  the check outright, and Chunksmith's own keep-awake deliberately resets the empty-server counter
  every tick so a running pre-gen is never paused. The hook was installed, was valid, and fired
  exactly zero times on every 26 server since the 26 line existed -- so on 26 the whole unload
  side of a pre-gen was left entirely to vanilla's single pass per tick. It now attaches to the
  end of the server tick on 26 too, the same place as every other Minecraft version. Confirmed by
  reading the compiled server (`javap -c`) on 26.1, 26.1.2, 26.2 and 26.3, and proven by recording
  the running server on 26.1.2: the hook is called zero times in 3.3.0 and once per tick in 3.4.0.

## [3.3.0] - 2026-08-12

### Added

- **`/cslod set` -- the LOD client's own settings are now settable in-game.** 3.2.4 brought every
  setting in `config/chunksmith/config.json` under `/cs set`, but the LOD client keeps its two
  settings somewhere else entirely -- `config/chunksmith-lod.properties`, on the CLIENT -- and those
  stayed file-only. A setting you can only change by editing a file and restarting is not a setting
  on a running game, and that applies on the client exactly as it does on the server.
  - `/cslod set` lists both settings with the value in force, `/cslod set <name>` shows one, and
    `/cslod set <name> <value>` changes it, applies it immediately and writes it to the file.
  - `sync-interval-seconds` is clamped ON WRITE to the 30s floor, so the file can never hold a
    number the client would refuse to honour; the command reports what was actually stored.
  - `reinject-on-join` can now be turned on, joined with, and turned back off without ever opening
    the config folder -- which is the entire point of a one-shot recovery switch.
  - The settings are held in a registry (`CsLodClientSettings`) and a coverage test fails BY NAME if
    a key in `CsLodClientConfig` is not reachable from the command, the same guard `/cs set` got.

### Fixed

- **Cancelling a pre-generation could crash the server when C2ME is installed** (mod_support #16).
  The settle window introduced in 3.2.4 holds a chunk ticket until a chunk's neighbours have caught
  up, then hands it back. Handing them ALL back -- at the end of a run, and far more abruptly on a
  cancel -- was done from Chunksmith's own worker thread, and a chunk ticket may only be touched on
  the server thread. Vanilla tolerated it. C2ME, which moves that machinery onto its own concurrent
  scheduler, did not: the ticket graph was corrupted mid-flight and the server came down with
  `ArrayIndexOutOfBoundsException`, then failed a second time while saving worlds on the way out.
  The release now runs on the server thread, like every other ticket operation beside it.
  Reproduced on demand before the fix and confirmed gone after it, with a test that was shown to
  FAIL on the previous release first -- an untested fix for a crash is a guess.
- **The LOD injector kept running after the world had gone.** On a disconnect or a cancel it would
  carry on handing regions to your renderer -- observed still logging progress 45 seconds after the
  server had stopped. It is now told to stop, and stops at the next region.

### Deprecated

- **Folia is deprecated.** The plugin still declares `folia-supported` and still runs on Folia in
  this release, so nothing breaks today -- but Folia is no longer a tested platform and support
  will be REMOVED in a future release. Chunksmith's Folia test cells could not be kept working, and
  a platform we cannot test is a coverage claim nobody can verify; saying so is better than quietly
  shipping it untested. Paper remains fully supported and tested. If you run Chunksmith on Folia and
  want that to continue, say so on the issue tracker.

## [3.2.4] - 2026-08-11

### Added

- **`/cs set` -- every setting is now readable and writable in-game.** A setting you can only change
  by editing a file and restarting is not much use on a running server. Only two of the thirteen
  settings had a command; the rest, including the three new settle keys, could not be changed on a
  running server at all.
  - `/cs set` lists every setting with the value in force, `/cs set <name>` shows one, and
    `/cs set <name> <value>` changes it and saves it immediately.
  - Values with a legal range are clamped **as they are written**, not only as they are read, so the
    file can never hold a number the mod would refuse to honour. The command reports the value that
    was actually stored rather than echoing what you typed -- ask for a settle radius of 40 and it
    tells you it is 16.
  - A value that cannot be understood at all -- a word where a number belongs, a language that is
    not shipped -- is refused, rather than quietly becoming a default.
  - `/cs silent` and `/cs quiet` still work and are unchanged. Both now PERSIST, which they did not
    before: they used to change the running value and forget it at the next restart.
  - On Paper/Folia the three `pregenSettle*` settings report that they do not apply rather than
    accepting a value that is ignored. Bukkit does not manage chunk tickets, so there is no window
    to hold open.

### Fixed
- **Other mods can build on freshly pregenerated land again.** A pregen added a chunk ticket,
  generated the chunk and dropped the ticket the instant the future completed. For pure terrain that
  is exactly right and it is what keeps a run's memory flat -- but a mod that reacts to "a new chunk
  appeared" and does its work on a later server tick found the chunk, and everything around it,
  already unloaded. It could not build, so it deferred, and it kept deferring for the whole run.
  Reported against Millenaire (mod_support #14), whose villages simply never appeared in
  pregenerated land: measured on a NeoForge 1.21.1 pregen, **309 spawn attempts and 309 deferrals,
  zero villages**.
  - A chunk's ticket is now held until **all eight of its neighbours have also been generated**,
    plus a short delay. What is held is therefore the sweep frontier and nothing else -- it is
    bounded by the shape of the pattern rather than by a guessed number, and it shrinks to nothing
    as the run finishes.
  - The rule is deliberately about chunks, not about any particular mod. Nothing in Chunksmith knows
    Millenaire's name; anything that builds on newly generated chunks benefits, and anything that
    does not is unaffected.
  - **On by default.** `pregenSettle: false` in `config/chunksmith/config.json` restores the old behaviour
    exactly -- release inline, allocate nothing -- which is the right setting for a pure terrain
    pregen with no such mods installed, where holding the frontier costs memory and a little
    throughput and buys nothing. `pregenSettleDelayTicks` (default 40, i.e. two seconds) tunes how
    long a chunk lingers after its neighbourhood closes, and `pregenSettleRadius` (default 7 chunks,
    maximum 16) sets how much ground the trailing sweep loads together -- sized to the largest
    footprint a mod is likely to want at once.
  - Note for upgrades: an existing config is never rewritten, so these keys will not appear in a
    config written by an earlier version. They take their defaults regardless; delete
    `config/chunksmith/config.json` and restart to get a fresh one that lists them.
- **LOD data is no longer re-injected into the renderer on every single world join.** Which
  regions had already been handed to voxy / Distant Horizons was remembered only in memory, and
  that memory was thrown away on disconnect -- so every join re-decoded and re-pushed the entire
  in-range store, whether or not the renderer already had every bit of it. On a large pregenerated
  world that is minutes of CPU on a background thread at every single join, for terrain that was
  already drawn. Reported by Maker261 (mod_support #15) on a two-core machine, where it is
  impossible to miss.
  - The claim set is now written to disk as `.injected`, one per dimension, next to the region
    files it describes -- the same `x,z=token` line format and the same atomic `.part`+move
    discipline the download manifest already uses. A join now starts from what the last session
    actually injected instead of from nothing.
  - A region whose token has MOVED is still re-injected. That is the whole point of tracking the
    token rather than the coordinates, and it is what keeps a pregen that is still growing the
    region under the player's feet from freezing at whatever it was when they first joined.
  - The file records WHICH renderers it was written for. Install Distant Horizons alongside an
    existing voxy setup and the epoch no longer matches, so everything is injected once into the
    renderer that has never seen it, rather than being skipped forever.
  - `reinject-on-join` (new, in `config/chunksmith-lod.properties`, default `false`) forces a full
    re-injection for one session. It is the escape hatch for the case the epoch cannot see: a
    player who deletes or resets their renderer's own database still holds a `.injected` that
    honestly describes what we sent, and we would otherwise believe them. Deleting the `.injected`
    files does the same thing.

## [3.2.3] - 2026-08-04

### Added
- **Pregeneration now verifies its own work.** A chunk counted as "generated" was only ever a
  claim: the counter is incremented when the load future completes, and a future can complete
  having done nothing at all -- which is exactly how 3.2.2's C2ME bug went unnoticed for two
  releases while every counter and log line reported success. At the end of a run Chunksmith now
  takes a spread of up to 32 chunks it says it generated and asks the world whether they are
  actually on disk. If any are missing it says so loudly, with the count and example coordinates,
  instead of reporting a clean finish. Costs at most 32 status reads, once, per run.
  - Deliberately quiet when it cannot get an answer (busy or shutting-down server): only a
    definite "not on disk" is ever reported. A check that cries wolf gets ignored.
- The end-of-run drain now happens for every task rather than only LOD-enabled ones, so a task no
  longer declares itself finished while its own dispatches are still outstanding. Previously a
  non-LOD run could under-report by up to the dispatch limit.

### Changed
- **MC 26.3 rebuilt onto `26.3-snapshot-7`** (was snapshot-6), Fabric API `0.156.2+26.3`,
  `pack_format` **95** (was 94). Every 26.3 snapshot so far has bumped the resource pack format by
  exactly one, so each build is snapshot-exclusive by design -- `3.2.2+26.3` remains available and
  listed for snapshot-6.

## [3.2.2] - 2026-08-04

### Fixed
- **Pregeneration silently generated nothing when C2ME was installed (Fabric, all versions).**
  Reported as mod_support #13 on Minecraft 1.21.1 Fabric: Chunksmith reported thousands of chunks
  generated, in effectively zero seconds, and no terrain was written. Every Fabric build from 3.2.0
  onward was affected on every Minecraft version, not only 1.21.1.

  Root cause: 3.2.0 propagated the C2ME ticket-race guard fleet-wide, which correctly skips the
  forced `runDistanceManagerUpdates()` call when C2ME is present. But that call is also what builds
  the `ChunkHolder` for the chunk ticket Chunksmith had just added, and the follow-up
  `getChunkFutureMainThread(x, z, FULL, create)` was still being passed `create = false`. With no
  holder and no permission to create one, it returned an immediately-completed FAILED `ChunkResult`
  ("unloaded chunk"). A failed `ChunkResult` is not an exception, so the completion callback saw a
  null throwable, released the ticket, and counted the chunk as finished. The result was a pregen
  that reported complete success at impossible speed while doing no work.

  Fix: when either C2ME or Moonrise is present, `getChunkFutureMainThread` is now allowed to create
  the holder itself (`create = true`). The forced distance-manager call stays skipped under C2ME, so
  the 3.1.5 ticket-race crash fix is preserved. Moonrise already used this exact mechanism for the
  same underlying reason.

  Verified by disk state rather than by Chunksmith's own progress counter: a fixed 4225-chunk
  selection now writes 4225 chunks at `minecraft:full` into the region files with C2ME installed,
  matching the no-C2ME control exactly. Before the fix the same selection wrote 49.

## [3.2.1] - 2026-08-03

### Added
- **Voxy LOD support on Fabric 1.21.1 and NeoForge 1.21.1**, via the `m3t4f1v3` fork of voxy
  (github.com/m3t4f1v3/voxy, `multiversion` branch). Upstream voxy has never published a build
  for either of these cells (Fabric 1.20.1/1.21.1, or any NeoForge line), so Chunksmith's voxy
  adapter previously compiled out entirely on both. The fork's NeoForge cell is a genuine native
  NeoForge port (its own mojmap-compiled entrypoints, not a Sinytra Connector repackage), which is
  what makes it compatible with Chunksmith's own mojmap-native NeoForge build -- the same
  Connector-repackage forks that were rejected before remain unusable, only this one fork's direct
  compile target is viable. `_codegen/compat.py`'s `has_voxy()` now recognizes both cells; the
  `_real` voxy adapter (`CsLodVoxyInjector`, `VoxyLodSink`, `VoxyTarget`, `VoxyRadius`) is compiled
  in on both, and both were verified end-to-end on the LOD functional render gate (server pregen,
  client join, region fetch + inject, EYEBALL-confirmed terrain render) before release.

### Scope
- **Only two jars change in this release**: `chunksmith-3.2.1+1.21.1.jar` (Fabric) and
  `chunksmith-3.2.1+1.21.1-neoforge.jar` (NeoForge). Every other cell is unchanged and remains at
  `3.2.0` -- there is nothing in this release for them to receive.

## [3.2.0] - 2026-08-03

### Added
- **Universal LOD generation on the Bukkit/Paper/Folia plugin.** The plugin line previously
  shipped with LOD generation hardcoded off; it now generates LOD data by default, matching
  the Fabric/Forge/NeoForge behavior, using the same pregen-time hook the mod side already
  used. No configuration needed -- `lod-enabled: false` still turns it off for admins who
  want pregen without LOD.

### Fixed
- **Housekeeping-hook stall under a large chunk-unload backlog (issue #11).** The shared
  per-tick housekeeping mixin (every Fabric/Forge/NeoForge cell) called
  `chunkMap.invokeTick(() -> true)`, discarding vanilla's real per-tick time budget
  (`haveTime`, already passed in and unused) and letting the unload loop run with no time
  limit every tick. A backlog of 13k+ chunks pinned the server thread for over 50 minutes
  CPU time on one reported world. Fixed by passing the real `haveTime` through -- shared
  plumbing, so every cell picks up the fix automatically. Load-tested 62.5 minutes
  continuous on the exact reported configuration (144,433 chunks processed, zero stalls)
  and boot-verified across the rest of the matrix.
- **Plugin: `IncompatibleClassChangeError` extracting LOD biome data on some Paper builds.**
  Bukkit's `Biome` type changed from a class to an interface across Paper API generations
  within the same Minecraft version line; a plugin jar compiled against one shape could
  throw at runtime against a server built against the other. Fixed by resolving the biome
  through the stable `org.bukkit.Keyed` interface instead of the concrete `Biome` type, and
  by making LOD extraction failures fail loud (logged) instead of silently doing nothing, so
  a bug in this class can't hide again.
- **C2ME compatibility guard was only wired into one Fabric cell.** A ticket-race workaround
  for running alongside the C2ME concurrency mod (added for Fabric 1.21.11 in 3.1.5) is now
  applied on every Fabric/Forge/NeoForge cell. No behavior change where C2ME is absent --
  Forge and NeoForge never set the detection flag that arms it.

### Changed
- **Version numbering reconciled fleet-wide.** Every cell (Fabric, Forge, NeoForge, and the
  plugin, across every supported Minecraft version) now reports `3.2.0`. Previously the
  plugin tracked `3.1`, most mod cells tracked `3.1.1`, and two cells (Fabric 26 and Fabric
  1.21.11) had drifted ahead to `3.1.4`/`3.1.5` on earlier fixes that never got a version
  bump recorded here.

## [3.1.5] - 2026-07-29

### Fixed
- **Fabric 1.21.11: C2ME chunk-ticket race that could crash the server** (`FabricWorld.getChunkAtAsync`).
  Every chunk request forced an immediate, synchronous `runDistanceManagerUpdates()` call right after
  adding a ticket, instead of letting the distance manager process tickets on its own once-per-tick
  cadence. Vanilla only ever runs that update once per tick from one place, so ticket-map mutation and
  iteration stay serialized; forcing it here re-entered that code far more often than vanilla ever
  would. C2ME rewrites the chunk ticket/distance manager onto its own concurrent scheduler, and this
  out-of-cadence forcing could trigger a race there -- observed as a server crash, NPE in
  `Long2ByteOpenHashMap` iteration ("this.wrapped" null) while ticking chunk tickets, reproduced on the
  "Zion" world after starting a Chunksmith pregen with C2ME installed.
- Chunksmith now detects C2ME at init (`PlatformCompat.ENABLE_C2ME_TICKET_COMPAT`, mod id `c2me`) and,
  when present, skips the forced call -- the newly added ticket is picked up by C2ME's own scheduler on
  its next pass instead of racing it. No behavior change when C2ME is absent. Scoped to the Fabric
  1.21.11 platform-adapter variant (`compat_platform.py` "a2e3c49d5235"); the detection flag itself is
  shared plumbing (harmless no-op on every other cell until wired into their variants too).

## [3.1.4] - 2026-07-28

### Changed
- **D16: the platform adapters are single-sourced.** The 8 per-cell adapter classes
  (`Fabric`/`NeoForge` x `Player`/`Sender`/`Server`/`World`) were the LAST hand-copied master in
  the mod -- **104 files across the 26 build cells**. They now live in
  `_codegen/compat_platform.py` and are materialised into each cell's `gen/` by `cog-gen.ps1`
  (new Step 3d), exactly as `Border` was in Step 3c. **Every cell's `src/main/java` is now empty:
  100% of Chunksmith's Java comes from the one cog source of truth.**
- The adapters carry real MC-era drift, so they are stored as WHOLE-FILE era variants plus a
  measured cell->variant map, which is the house pattern for this (cf. bank-vault's `compat_*.py`).
  FabricWorld had 9 distinct bodies across 10 cells and NeoForgeWorld 11 across 16; on 26 the
  `*World` classes are a structural fork (different interfaces implemented, extra fields, an extra
  ticket block), not a rename set, so inline predicates would have been the wrong shape.
- Exactly ONE genuine loader difference exists in the whole set -- `ChunksmithForge` vs
  `ChunksmithNeoForge` in `NeoForgeServer`. Everything else is version drift.

### Added
- `scripts/verify-platform.py` -- the D16 hash oracle. Default mode checks what
  `compat_platform.py` materialises against each cell's generated `gen/` tree; `--against-git <ref>`
  checks it against the pre-migration committed copies. Non-zero exit on any mismatch, so it can
  gate a build.

### Verified
- **Hash oracle, twice: 104/104 byte-identical** (line endings normalised -- git rewrites those on
  checkout anyway) against the pre-migration copies at the previous commit, and again against the
  freshly generated `gen/` trees after all 26 cells were regenerated.
- **All 26 cells rebuilt: 26 OK, 0 failures, 0 javac warnings.**

### Note
- An analysis pass flagged the missing `LodSupport.offer` hook in 18 of 26 cells as unintended
  drift, citing same-version Forge/NeoForge pairs that differ. **It is not drift.** Those 8 cells
  are exactly `compat.has_lod()` -- verified by running the predicate across the cell list against
  the hook's real locations, 8/8. Forge's only Distant Horizons line is 1.20.1, so Forge/1.21.1
  correctly has no hook. Recorded so the next pass does not "fix" the LOD curation into cells that
  cannot use it.

## [3.1.3] - 2026-07-28

### Changed
- **Fabric 26.3 cell moved to MC 26.3-snapshot-6** (from snapshot-5): fabric-api
  `0.155.3+26.3` -> `0.156.1+26.3`, `pack_format` `93` -> `94`, and the exclusive
  snapshot pin `depends.minecraft` `26.3-alpha.5` -> `26.3-alpha.6` (Fabric normalizes
  `26.3-snapshot-N` to `26.3-alpha.N`). The snapshot-5 build stays listed on Modrinth;
  this jar does not claim it.

### Notes
- **No source change required.** Snapshot-6 is a large MC release -- it rewrote the
  worldgen noise stack (`NormalNoise.NoiseParameters` deleted, `Registries.NOISE`
  retyped, `DensityFunction` gained an abstract `domainAxes()`, `ImprovedNoise` /
  `PerlinSimplexNoise` removed) and reworked the terrain render path
  (`ChunkSectionLayer.pipeline(boolean)`, `LevelRenderer.prepareChunkRenders` gained a
  flag, `DynamicUniforms` -> `DynamicGpuData`). Chunksmith references NONE of those
  symbols: it drives pregeneration through the server-facing chunk APIs only, so the
  overhaul does not reach it (verified by scanning all 346 source files).
- The 26.3 LOD functional gate remains untestable: Distant Horizons still ships no
  26.3 build upstream.

## [3.1.2] - 2026-07-21

Adds Minecraft 26.3-snapshot-5 (Fabric). The 26.3 Fabric jar is rebuilt against 26.3-snapshot-5 (Fabric API 0.155.3+26.3, pack_format 93). The mod loads and renders in-world on the snapshot's reworked GPU/shader ("renderpearl") pipeline with no source changes -- the LOD mixins and the Distant Horizons / voxy adapters apply cleanly. Verified in-world on the headless client harness. The 26.3 Fabric jar's dependency is pinned to 26.3-snapshot-5 exclusively, per the per-snapshot compatibility policy for the rendering line.

## [3.1] - 2026-07-16

Stable plugin release (Paper / Spigot / Folia) of the 3.1 line. The Bukkit plugin is a server-side pre-generator only -- it has no LOD/distant-terrain code (there is no plugin-side renderer), so it is functionally unchanged from the 3.1.0 betas. Rebuilt at 3.1 for the stable line across the 1.20.x, 1.21.x, and 26.x families.

## [3.1.1] - 2026-07-16

Stable release. This promotes the entire 3.1.0-beta line to a stable build -- no issues were reported on the betas -- and adds Minecraft 26.3-snapshot-4 (Fabric). All 29 mod jars (Fabric, NeoForge, Forge across the 1.20.x / 1.21.x / 26.x families) are rebuilt at 3.1.1.

Everything from the 3.1.0 betas is now stable:

- One jar does everything: Chunksmith-Client is merged in, so a single mod handles both server-side chunk pre-generation and the multiplayer distant-terrain (LOD) delivery -- no separate client mod to install. (3.1.0-beta-1)
- Fixed a main-thread memory blowup when serving LOD index requests, and added periodic client/server checksum sync. (3.1.0-beta-4)
- Fixed cross-dimension LOD leakage and delivery to players who joined before pre-generation had data. (3.1.0-beta-2 / beta-3)
- Forge 1.20.1: the LOD network channel is optional again, so a client without Chunksmith can join a Chunksmith Forge server. (3.1.0-beta-5)

### New in 3.1.1

- Minecraft 26.3-snapshot-4 support (Fabric); resource pack_format 92.

## [3.1.0-beta-5] - 2026-07-14

**On Forge 1.20.1, a player who did not have Chunksmith could no longer join a server that did.** The mod
is meant to be optional on the client -- it is a server-side pre-generator, and the client half only adds
distant terrain for players who want it. But since 3.x the server registered its `chunksmith:lod` network
channel as *required*, so Forge's login handshake refused any client that lacked it: the player was kicked
at join with "Connection closed - mismatched mod channel list". This build makes the channel optional
again, so a vanilla-Forge client joins a Chunksmith Forge server exactly as it did on 2.2.3. The server is
unchanged for players who do have Chunksmith -- the channel simply is not negotiated to those who do not.

This was a regression from the 3.x LOD channel and it affected **Forge only**. NeoForge was already correct
(its channel was marked optional during the 3.1.0-beta-1 client merge) and Fabric never forced the client
(its play channels are inherently permissive). Only the Forge cell was missed.

Only the **Forge 1.20.1** jar changed. Every other cell is byte-for-byte identical to 3.1.0-beta-4 and is
unaffected.

### Fixed

- **Forge 1.20.1: the LOD network channel is now optional.** Both accepted-version predicates on the
  `chunksmith:lod` `SimpleChannel` are wrapped in `NetworkRegistry.acceptMissingOr(...)`, which accepts the
  "channel absent" sentinel the FML login handshake sends for a client that does not have the channel. A
  bare version-equality predicate rejected that sentinel, which marked the channel required and made the
  server refuse the client. Mirrors the NeoForge cell's `.optional()`.

## [3.1.0-beta-4] - 2026-07-13

**Every time a player asked "what LOD terrain is near me?", the server read every region file in the store
and hashed its contents -- on the main server thread.** On a 340-region, 850 MB store that is **205 MB of
memory allocated per request**, several times a second, in blocks big enough that the garbage collector has
to handle each one as a special case. A live server ran out of memory doing it, and then took **67 minutes**
to shut down. It is fixed: the answer is now built from each file's timestamp and size, off the main thread,
and the same request allocates **18 KB** -- **11,808x less** -- with **zero** of those collector pauses where
there used to be 38.

**Also new: your client now notices new terrain on its own.** Every few minutes it and the server compare a
single small checksum; if they differ, the client pulls only what changed. You no longer have to relog, or
walk somewhere, to see terrain a running pre-generation has just finished.

> ### Read this before you update
>
> **The LOD network protocol changed (v1 -> v2). Your server and every player's client must BOTH be on
> `3.1.0-beta-4` or later.** A mismatched pair will not exchange LOD data -- both sides refuse it, both say
> so in the log, and nothing else breaks (no crash, no hang, no stuck downloads) -- but the distant terrain
> will simply not be there. There is no way around this: the number the two sides compare to decide "do I
> already have this region?" is exactly the thing that had to change, and an old client asking the new
> question would re-download your entire LOD store every five seconds, forever. We would have traded a memory
> problem for a bandwidth one.
>
> **Update the server and the clients together.** On the first join after updating, each client re-downloads
> the regions in its view radius once -- it has no record of what it can vouch for yet -- and then never
> again.

### Fixed

- **The LOD index no longer reads the store to build itself, and no longer runs on the server thread.** To
  tell a client which regions it should have, the server used to open every region file near that player,
  read all of it, and CRC32 the bytes -- 205 MB of allocation per request on a real store, 74-173 ms of the
  tick each time, and because the buffers are multi-megabyte the JVM allocates them out of a special
  "humongous" path that forces a collection. Over five minutes of a player walking around, that was **38
  garbage-collection pauses, all of them attributed to exactly this**. It is why one server climbed to 100%
  memory during a pre-generation and could not save its worlds afterwards. The freshness marker for a region
  is now derived from its **modification time and size** -- which is all the question ever needed, because
  the question is only ever "is this the same file I already sent you?" -- and the scan runs on a background
  thread, one outstanding scan per player, so the tick does no I/O at all. Same request: **18 KB, under a
  millisecond, zero humongous allocations, zero GC pauses.**
- **The client was doing the same thing to its own store, and nobody ever noticed.** On every index it read
  and hashed every region file it had, for the same reason. A server dies loudly and gets reported; a client
  with a big heap just stutters. It now records what the server told it about each region in a small manifest
  file beside the store, so the "do I already have this?" check is a lookup and one file-size stat.
- **The index can no longer be unbounded in size.** It was capped at 4,096 regions -- but a region can be
  7 MB, so that cap permitted a ~28 GB answer. It is now capped in **bytes** (2 GiB), and the scan is sorted
  nearest-first, so if a store is bigger than the cap what a client loses is the furthest terrain, and it
  gets that as it walks toward it.
- **A region that GREW is no longer downloaded and then thrown away.** The client remembered which regions it
  had drawn by their coordinates alone -- which answers "have I ever drawn this?", not "have I drawn *this
  version* of it?". A pre-generation does not only create new regions; it keeps growing the ones under you,
  for hours. The client would notice the change, fetch the bigger file, hand it to the renderer -- and the
  renderer's own bookkeeping would recognise the coordinates, drop the new data on the floor, and report
  success. You would have watched the far ring of terrain fill in while the ground under you stayed frozen at
  whatever it was when you joined. Regions are now remembered by coordinate *and* version.
- **A client on a too-old server is no longer left staring at an empty horizon in silence.** If you update
  your client before the server updates, the old server refuses your hello and answers nothing at all -- so
  the client has nothing to check a version against, and it used to note the silence only at debug level.
  You got no terrain and no explanation. It now says so plainly, once, in the log: no LOD data is on offer
  here, either because this server does not run Chunksmith (perfectly normal) or because it runs a version
  older than `3.1.0-beta-4`, and if you expected terrain then both ends need to be on the same version. It
  cannot tell those two cases apart -- an old server tells it nothing to tell them apart *with* -- so it
  names both instead of guessing. One line, once, and never silence.

### Added

- **Periodic checksum sync -- new terrain arrives while you stand still.** Every 300 seconds by default, the
  client asks the server for a one-line summary of the LOD regions in its view (a count and a single folded
  checksum) and compares it with its own. If they match, that is the end of it. If they do not, it pulls the
  index and fetches **only the difference**. No relog. No walking. It costs **22 bytes out and 34 bytes back**
  -- about 0.19 bytes per second per player -- and on the server it is roughly 86 syscalls and **not one byte
  of file content read**. One mechanism covers all three of "the server generated more", "a region I hold
  changed", and "I lost regions off my disk" (delete some and the next poll brings back exactly those).
- **`sync-interval-seconds` in `config/chunksmith-lod.properties`** (client-side; the file is written with
  defaults and comments on first run). Default **300**. **Values below 30 are clamped to 30** -- a config
  value is a suggestion, and a one-second poll must not become a denial of service against a server that is
  already busy pre-generating. No settings screen yet; that is `3.2`.

### Changed

- **`CsLodProtocol.VERSION` 1 -> 2.** Deliberate and unavoidable; see the notice above. The wire *layout* is
  unchanged -- what changed is the *meaning* of the index's freshness field (a CRC32 of the file's contents
  became an opaque timestamp+size token). Both ends check the version, both refuse a mismatch, and both name
  it in the log rather than failing quietly.
- **Moving a world between machines, or restoring it from a backup, now re-sends the LOD regions once.**
  Copying files changes their modification times without changing their contents, so every freshness token
  moves and connected clients re-fetch what is in their radius. That is the honest price of the fix, it is
  bounded, and it is once. The alternative failure -- a marker that says "unchanged" about a region that did
  change -- leaves a player looking at terrain that no longer exists with no mechanism that could ever
  correct it. A redundant download is a bandwidth bill; a stale region that is trusted is a bug you cannot
  see.

### Notes

- **This release rebuilds the eleven jars that carry the LOD feature** (Fabric 1.20.1 / 1.21.1 / 1.21.11 /
  26.1 / 26.2 / 26.3, NeoForge 1.21.1 / 1.21.11 / 26.1 / 26.2, Forge 1.20.1). The Bukkit/Paper/Folia plugin
  jars and the other mod jars have no LOD code path at all, so they are unchanged and remain at
  `3.1.0-beta-3` -- there is nothing in this release for them to receive.
- Everything above was measured on a real 340-region / 853 MB store with a real Distant Horizons client
  connected and a pre-generation running: server heap 18% of an 8 GB maximum, zero full collections, zero
  "Can't keep up" tick warnings, and a clean 22-second shutdown in the middle of the pre-generation -- the
  exact situation that previously hung for over an hour.

## [3.1.0-beta-3] - 2026-07-13

**The Overworld was showing up in the Nether. It is not any more.** If you went through a Nether portal on a
Chunksmith server, the distant terrain you saw there was not the Nether -- it was the Overworld's, pushed
into the Nether's sky. Grass, oceans and beaches, floating over the lava. Everything reported success while
it happened. This release fixes it, on both sides.

### Fixed

- **Distant terrain from one dimension no longer appears in another.** The client picked which dimension's
  LOD data to download from the FIRST dimension the server happened to list when you joined -- on any normal
  server, the Overworld -- and then never revisited that choice for the rest of the session. Walk through a
  portal and it kept downloading the Overworld's terrain and handing it to your LOD renderer, which drew it
  around you in the Nether. Neither Distant Horizons nor voxy checks that the terrain it is given belongs to
  the world you are in; they accepted it, saved it, and drew it. The client now tracks the dimension you are
  actually standing in, re-asks the server the moment you change dimension, and **refuses to inject any
  record that does not belong to the level in front of it.** Terrain from another dimension is never a
  substitute for this one's, and it is no longer treated as one.
- **The Nether's own LOD data is no longer silently skipped.** A second, independent bug, which would have
  kept the Nether empty even after the fix above. The client remembers which regions it has already drawn so
  a travel refresh does not re-push terrain you can already see -- but it remembered them by region
  coordinates ALONE. Region (0,0) is a different place in every dimension, so the moment the Overworld's
  (0,0) had been drawn, the Nether's (0,0) counted as "already done" and was dropped on the floor, for the
  whole session, without a word. Regions are now remembered per dimension, which is the only way a region
  coordinate means anything.
- **The server no longer serves an index for a dimension you are not in.** The list of regions near you is
  filtered by your renderer's range measured from YOUR position -- and a position only means something in a
  particular world. The server now answers with the dimension you are actually standing in, whatever was
  asked for, and says so in the log when the two differ. **This alone stops an already-installed
  3.1.0-beta-2 client from putting Overworld terrain in your Nether sky**, since the client files and draws
  the data under the dimension the server names.
- **Changing dimension mid-download no longer wastes the transfer.** A download in flight when you step
  through a portal is now cancelled -- on both ends -- instead of continuing to spend your connection on a
  world you have left, and the regions it had not reached are re-fetched for the dimension you are in.

### Notes

- The wire protocol is UNCHANGED (`CsLodProtocol.VERSION` is still 1). A beta-2 client and a beta-3 server
  interoperate, and the server-side correction above means such a client is partly fixed without updating --
  but only partly (it will still skip a region whose coordinates it drew in another dimension), so please
  update.
- Single-dimension play -- the overwhelmingly normal case, an Overworld-only pregen and a player who never
  leaves it -- behaves exactly as before.

## [3.1.0-beta-2] - 2026-07-13

**If you were already on the server when the pre-generation started, you now get the LOD data anyway.**
Until this build you did not, and nothing you could do in-game would fix it -- only leaving and re-joining.
Since a pre-generation takes hours, and people stay on the server while it runs, that was the normal case.

### Fixed

- **Joining before the server has any LOD data no longer costs you the whole session.** The client asked the
  server once, on join. If the store was empty -- which it always is until someone runs a pre-generation --
  the client logged one line and stood down for good: it never asked again, and the travel refresh never
  armed either, so no amount of playing or exploring brought the data in. Meanwhile the operator would start
  a pre-generation, the store would fill up over the next few hours, and every player already connected
  stayed blind to it until they thought to re-log. Now the client keeps asking (after 15s, 30s, a minute,
  then every two minutes -- a few bytes each time), **and** the server volunteers the news the moment its
  store has something to serve, so in practice the terrain simply appears. No re-log, no command, nothing to
  configure. The log says what is happening in both directions: *"the server has no pre-generated LOD data
  yet ... you do NOT need to re-log"*, and later *"the server NOW has LOD data for [...] -- fetching it"*.
- **The server no longer claims to have LOD data for a dimension it cannot serve a single region of.** A
  pre-generation creates its folder the moment it starts and only writes into it some time later, and the
  server was advertising that empty folder as data -- and issuing a download credential to go with it, which
  is how an operator could see one live token and zero files served. A dimension now counts when there is
  actually something in it.
- **A long session no longer quietly loses the fast download path.** Download credentials expire after ten
  minutes; a session lasts hours. Travelling far enough to pull in new terrain an hour after joining used to
  present an expired one, fail every fetch, and drop to the slow path for the rest of the session without
  ever saying so. The client now renews it before it can go stale.
- **The server no longer hands out a region it is still writing.** Pre-generation keeps each region file open
  and appends to it as chunks complete, so a copy taken mid-write is short: the client got part of a region
  and an error in its log. It always recovered on the next fetch, so this was invisible until now -- but now
  that players are told the moment the store comes to life, the very first region they ask for is one the
  server is in the middle of writing. A region is offered once the generator has finished with it.

### Notes

- **No protocol change.** The wire format and the protocol version are untouched. A 3.1.0-beta-2 client and
  a 3.1.0-beta-1 server work together, and so do a 3.1.0-beta-1 client and a 3.1.0-beta-2 server -- in fact
  an old client talking to a new server picks up a late pre-generation too, because the server's notice is
  simply its ordinary hello, sent again.

## [3.1.0-beta-1] - 2026-07-13

**One mod does everything now.** Chunksmith-Client -- the separate client mod that multiplayer LOD used to
require -- has been merged into Chunksmith and is discontinued. There is nothing else to install.

- **Singleplayer:** just Chunksmith. (Unchanged; this already worked.)
- **Multiplayer:** Chunksmith on the server *and* on the client. Same jar, both sides.
- **A server that only wants pre-generation:** just Chunksmith, exactly as before. Nothing new loads.

### Added

- **Multiplayer LOD is now built in.** Joining a Chunksmith server that has pre-generated LOD data, your
  client downloads it -- over the server's HTTP backchannel at network speed, or down the game connection
  if that port is not reachable -- and feeds it to whichever LOD renderer you have (Distant Horizons, or
  voxy on the cells where voxy exists). You see the whole pre-generated world at distance without ever
  having walked it. Everything the standalone client did, it still does: the store is the cache, so a
  re-join re-downloads nothing; the fetch repeats as you travel, so walking toward new terrain brings it in.

### Fixed

- **Installing Chunksmith and Chunksmith-Client together no longer crashes the game.** Both mods registered
  the same `chunksmith:lod` channel, and the second one to start died with
  `Packet type [id=chunksmith:lod] is already registered!` -- a hard crash on startup, with no explanation,
  for anyone who ran their own server and also joined someone else's. There is now exactly one registration
  of that channel, in one mod, so the collision cannot happen. If you still have the old client mod
  installed, the loader will tell you to remove it instead of crashing.

### Changed

- Chunksmith is now listed as **client-optional and server-optional**. It is genuinely both: an operator
  needs it server-side, a player joining that server needs it client-side, and a singleplayer user needs
  only the one jar.
- **Chunksmith-Client is discontinued.** Its existing builds keep working against this release -- the CSLOD
  wire format is unchanged and the protocol version is still **1**, so a 3.1 client talks to a 3.0.0-beta-4
  server and a 3.1 server serves a 1.0-beta-3 client. Nothing you have installed breaks. But there is no
  reason to keep it: remove it and Chunksmith does the job alone.

Bukkit/Paper/Folia are unaffected -- LOD is a Fabric/NeoForge/Forge feature and the plugin never carried it.

## [3.0.0-beta-4] - 2026-07-12

Security release. A full audit of the CSLOD network path found two flaws, both fixed here. Upgrading is
recommended for anyone running Chunksmith with LOD generation enabled, and especially on a server that
accepts connections from players you do not control.

### Security

- **A dimension name that arrived over the network was used to build a file path without being checked.**
  The CSLOD store turns a dimension id into a directory, and the value coming from the peer was trusted as
  written, so a malformed one could refer to a location outside the store. Every place that turns a
  dimension into a path now runs it through one shared validator that accepts only a well-formed dimension
  id and rejects everything else.
- **The CSLOD packet decoders allocated buffers from counts and lengths supplied by the sender, without
  bounding them.** A very small hostile packet could therefore ask the receiver to reserve an enormous
  amount of memory. Every count and length is now checked against a derived ceiling before anything is
  allocated, at the canonical source (`shared_common`), so the server and the client share one set of
  limits.

The wire format is **unchanged** and the CSLOD protocol version is still **1** -- a 3.0.0-beta-4 server and
an older Chunksmith-Client (or the reverse) still talk to each other.

### Added

- `CsLodBoundsTest` -- unit coverage for the new decode-time bounds.

## [3.0.0-beta-3] - 2026-07-12

Re-run a pregen and it fills in the missing LODs. Generate a world first and turn LOD on later, and the
LODs are no longer stranded -- you do not have to regenerate anything, and you do not have to reprocess
what is already done.

### Changed

- **The CSLOD store is now part of the chunk-skip decision.** When LOD generation is active, Chunksmith
  checks the store as well as the world, per chunk:
  - no chunk -> generate it (the LOD is built on the way past, as before);
  - **chunk on disk but no LOD -> load the chunk (no worldgen) and build the LOD from it**;
  - chunk *and* LOD both present -> skip entirely; no load, no write.

  Previously an already-generated chunk was skipped and never loaded, so the LOD hook never saw it: a
  world pregenerated before LOD was switched on could never get its LODs without regenerating from
  scratch or setting `forceLoadExistingChunks`. Now a plain re-run of the same selection fills the holes.
- **Only the holes are filled.** Deleting part of the CSLOD store and re-running rebuilds exactly the
  missing records and leaves the rest untouched -- the store is not rewritten wholesale.
- `forceLoadExistingChunks: true` is unchanged, and keeps its meaning as the explicit override: reprocess
  every chunk in the selection regardless, even where a LOD already exists.
- **With LOD off, nothing changes.** The skip behaviour is exactly what it has always been.

### Added

- The pregen now reports what it actually did: `generated`, `LOD-only (built from existing chunks)`, and
  `skipped (chunk + LOD present)`, plus the measured cost of the store check.
- `/cslod status` reports the store's **record count**, so it can be compared against the chunk count.

### Performance

- The presence check reads each region file's 8 KB header **once** and holds a 1024-bit bitmap, so a whole
  region's presence costs one sequential read. No records are decoded, and no file is re-opened or
  re-stat-ed per chunk.

## [3.0.0-beta-2] - 2026-07-12

LOD generation turns itself on. Install Chunksmith next to Distant Horizons or Voxy, pregenerate, and
the LODs are there -- no config file to find first.

### Changed

- **`lodEnabled` is now a TRISTATE, `auto` by default** (it was a boolean defaulting to `false`).
  - `auto` -- Chunksmith decides. LOD generation is **ON** when an LOD renderer is present in the JVM
    (Distant Horizons, Voxy, or a Voxy fork), **ON** on a dedicated server, and off otherwise. A
    dedicated server runs no renderer of its own, but its CSLOD store is exactly what Chunksmith-Client
    downloads, so the store is what it is *for*.
  - `true` / `false` -- an explicit operator decision, and it is **never** overridden. `lodEnabled: false`
    keeps LOD off even with Distant Horizons installed.
  - An existing config that already says `"lodEnabled": true` or `"lodEnabled": false` keeps working and
    keeps meaning exactly what it said. Nothing is rewritten behind you.
- **The decision is logged, once, at server start** -- which way it went and why
  (`LOD generation auto-enabled -- detected distanthorizons ...`, or `no LOD renderer detected; LOD
  generation off`). `/cslod status` reports it too. A default nobody can see is a default nobody uses.
- Renderer detection covers Distant Horizons (`distanthorizons`), Voxy (`voxy`) and the Voxy forks -- five
  of the six known forks keep the upstream `voxy` id; `neovoxy` is detected as well. A fork under an id we
  have never seen simply does not trip the auto-on, and `lodEnabled: true` still forces it.

## [3.0.0-beta-1] - 2026-07-12

The LOD feature leaves the 26.x-Fabric prototype and ships on every MC line where a player actually
has something to draw it with.

### Added

- **LOD generation.** Chunksmith can now emit level-of-detail data while it pregenerates, in its own
  neutral format (CSLOD): full block states, per-voxel biomes, and separate sky/block light carried even
  for air. ~5.8 KB per chunk, ~16% slower pregen, zero native dependencies. Off by default (`lodEnabled`).
- **Distant Horizons support -- on every LOD version, singleplayer included.** Chunksmith registers as
  DH's world-generator override and serves it straight from the CSLOD store, so DH's LODs appear for
  pregenerated area without DH generating anything (opt-in: `lodDhOverride`). `/cslod dhpush` replays an
  existing store into DH on demand -- so a world pregenerated long before DH was installed gets its LODs
  after the fact, with no regeneration. This ships on **all eight LOD cells**: Fabric 1.20.1 / 1.21.1 /
  1.21.11 / 26.x, NeoForge 1.21.1 / 1.21.11 / 26.x, and Forge 1.20.1 -- DH publishes a build for every one
  of them. We use DH's PUBLIC API only; no mixin into DH.
- **Voxy support -- where voxy actually exists.** LODs are fed to voxy live during pregen, and an existing
  store can be replayed into voxy at any time with `/cslod inject`. Ships on **Fabric 1.21.11 and Fabric
  26.x**, and only there: voxy is Fabric-only and upstream has never published a 1.20.1 or a 1.21.1 build
  on any loader. On every other cell the voxy seam is simply not compiled in -- Chunksmith never claims a
  renderer it cannot feed.
- **Singleplayer gets LODs with no client mod at all.** In singleplayer the integrated server runs inside
  the client JVM, so Chunksmith hands the player's own DH (and voxy, where it exists) their data
  DIRECTLY -- no Chunksmith-Client, no network. That used to work only on Fabric 26.x; it now works on
  every version and loader where a renderer exists.
- **The LOD server ports to the versions people actually play.** The whole server side -- the CSLOD store,
  the HTTP backchannel (game port + 1, zero config), the authenticated in-band handshake and tokens, the
  in-band fallback transfer, the worldgen hook, and `/cslod` -- now ships on **Fabric 1.20.1, 1.21.1,
  1.21.11 and 26.x**, **NeoForge 1.21.1, 1.21.11 and 26.x**, and **Forge 1.20.1** -- the versions people
  actually run. The remaining versions (1.20.4, 1.20.6, 1.21.4, 1.21.5, 1.21.8, 1.21.10) can carry the
  feature and will get it -- they are simply not in this release.
- The wire protocol is IDENTICAL on every one of them. The format is the disk format is the wire format,
  and it lives in one place -- so any Chunksmith-Client talks to any Chunksmith server.
- `/cslod status` and `/cslod token <player>` on every LOD cell; `/cslod dhpush` on every LOD cell;
  `/cslod inject` on the two cells where voxy exists. `/cslod status` reports only the renderers a cell can
  actually feed -- it says "voxy: no build for this loader/MC" rather than pretending voxy is merely absent.

### Changed

- **Declared conflicts.** Chunksmith's LOD cells now hard-conflict with the other mods that stream LOD
  data into a client's renderer -- `lss` (LOD Server Support), `voxyserver` (Voxy Server), and `lodserver`
  (our own predecessor). Two uncoordinated writers into one LOD database means duplicated downloads and a
  real risk of racing voxy's database-local id allocation. Distant Horizons is deliberately NOT a
  conflict: it is a renderer we feed. (Forge 1.20.1's `mods.toml` has no incompatible-dependency type, so
  there the clash is reported loudly in the log instead.)

### Fixed

- **`pack.mcmeta` on 1.21.10 and 1.21.11.** From MC 1.21.9 the pack-metadata codec validates a mod jar
  TWICE -- once as a resource pack, once as a data pack -- and each pack type has its own threshold above
  which `min_format`/`max_format` become mandatory (64 for resources, 81 for data). The resource format on
  those versions (69 and 75) lands between the two, so no single `pack.mcmeta` can satisfy both, and the
  client logged `Couldn't load chunksmith pack metadata ... missing mandatory fields min_format and
  max_format`. The Fabric and NeoForge cells now ship no `pack.mcmeta` at all (both loaders synthesise
  correct per-type metadata when a mod jar omits it); the Forge cells, where the file is mandatory, use the
  exact-range form with the data-pack format, the only value above both thresholds.
- Build scripts silently ignored all but the first target (`build-fabric.ps1 1.21.8 26.1` built only
  1.21.8), aborted the whole matrix on the first failing cell, and could corrupt each other when run
  concurrently (all cells share `shared_common`). All three fixed; a build lock now prevents overlap.
- **In-band LOD requests no longer trust the client's numbers.** A region count arriving off the wire was
  used to pre-size a list and to slurp every requested region file into memory on the server thread: a
  large but perfectly legitimate request meant hundreds of megabytes and a multi-second stall, and a
  hostile one was a single packet away from an out-of-memory kill. Requests are now bounded, and the
  transfer streams each region a slice at a time off disk instead of buffering it. The client's declared
  LOD radius is clamped as well.
- The NeoForge 26.x manifest declared its Minecraft and NeoForge version requirements under the mod id
  `chunky` (a leftover from the fork's ancestry), so the loader never applied them.
- LOD store logging went to `System.out` instead of the mod's logger.


## [2.2.3] - 2026-07-10

Fixes a hard crash at startup that could hit large modpacks. Chunksmith's five optional worldgen/entity diagnostic mixins are now best-effort (`require = 0`), so another mod that removes or overrides one of their target methods can no longer take the game down at boot. Reported on NeoForge 1.21.1 in a ~400-mod pack.

Chunksmith's core behavior is unchanged - the functional mixins (keep-awake, chunk housekeeping, entity-retention, client housekeeping) stay hard-required, so a genuine problem there still fails loudly.

## 2.2.1 (2026-07-05) -- metadata + build hygiene bugfix

- Issue-tracker URL: every mod manifest now points to the mod_support hub
  (github.com/Kishku7/mod_support/issues), single-sourced + audit-checked via
  scripts/_metadata.py (previously the mod's own disabled Issues tab).
- pack.mcmeta: the 1.21.10 / 1.21.11 cells (Fabric/Forge/NeoForge) now ship the
  supported_formats range form required for pack_format > 64 (were plain int,
  which caused the client to skip the mod resource pack on 1.21.9+).
- Removed the dead oss.sonatype.org snapshots repo and the unused Architectury
  maven repo from all cell build scripts.
- Internal docs folder renamed .docs -> docs (gitignored). README build-script
  references corrected to scripts/build-*.ps1.

## 2.1.3

### Minecraft 26.3-snapshot-2 support

Adds Fabric support for 26.3-snapshot-2. This build covers BOTH 26.3-snapshot-1 and 26.3-snapshot-2,
so snapshot-1 users should upgrade. It is a pure dependency bump: no worldgen or mixin logic changed
(verified against the 26.3-snapshot-1 -> snapshot-2 decompiled source diff -- the snapshot-2 worldgen
refactor does not touch any Chunksmith injection point).

### Packaging + build hygiene

- Every jar now ships a correct per-version `pack.mcmeta` (added the missing Fabric resource metadata;
  corrected the NeoForge `pack_format`).
- The mod and plugin now compile clean under `-Xlint:all` with zero warnings. These are behavior-
  preserving changes only (final classes, explicit numeric casts, a non-deprecated permission API call,
  and justified suppressions for intentional cross-version Bukkit/Paper API use).

## 2.1.2

### Fixed: worldgen entities (mobs, item frames, armor stands, etc.) could fail to save

During large pre-generation runs, entities that spawn as brand-new chunks are generated could,
in some cases, fail to be saved -- in practice, "mobs that just don't persist."

**What was happening.** When Minecraft unloads a freshly generated chunk's entities, it normally
first reads any already-saved entities for that chunk so they can be merged before the new data is
written. Chunksmith skips that read when a chunk has no saved entity data -- a real optimization that
keeps memory and disk under control during big pre-gens. The problem was *how* it decided "is there
saved entity data?": that check ran off the storage thread, against a cache that was never
refreshed, and it ignored writes that were still queued in memory but not yet flushed to disk. So a
chunk that was stored, unloaded, and then re-loaded before its write reached disk could have its
just-saved entities overwritten -- and lost.

**The fix.** The "is there saved entity data?" check now runs on the chunk-storage system's own
thread -- the single thread that owns both the in-memory write queue and the region files -- and it
checks the queued writes AND the on-disk data. It can no longer race the writer, read a half-written
file header, or trust a stale cache. If any saved data exists, or anything is uncertain, Chunksmith
performs the full, safe read-and-merge exactly as vanilla would. Nothing is ever skipped when data
might exist, so no entity can be lost.

This fix is applied across every supported Minecraft version and loader.

### Also in this release

- **Unified version.** All Minecraft lines (1.20.x, 1.21.x, 26.x) and all loaders (Fabric, Forge,
  NeoForge, and the Bukkit/Paper plugin) are now on a single version, 2.1.2, so every supported
  version carries the same set of fixes.
- **26.x loader metadata.** Minecraft version ranges are now closed (a 26.1 build targets 26.1.x
  only, a 26.2 build targets 26.2.x only, and so on) instead of open-ended, so a build can no longer
  claim to support a Minecraft line it was not built and tested against.

---

Earlier releases were published on Modrinth only; this is the first in-repo changelog entry.

