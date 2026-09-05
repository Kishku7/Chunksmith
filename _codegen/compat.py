"""ChunkSmith cross-version "era brain" for Cog code generation.

Given an MC version string (mcver), this module returns the correct per-era code
fragments for each mixin/accessor drift point in the drift matrix. Cog source
files import this module and call
its helpers inside //[[[cog ... //]]] blocks so ONE cog_sources file can be
direct-compiled correctly for every MC version.

Rationale for direct-compile (Cog) rather than a reflection facade: pre-26 Fabric
runs on the INTERMEDIARY runtime, so reflection-by-mojmap-name misses there. Cog
emits the correct mojmap symbol as real source, which loom then remaps to
intermediary at build time. Cog therefore works on every loader + every runtime.

ASCII-only. Plain Python, no third-party deps beyond the stdlib.

The four eras (Axis A = IOWorker executor primitive, Axis B = storage layering):
  1. ancient       1.20.1, 1.20.4          (direct worker + mailbox + Either + ChunkStorage + net.minecraft.Util)
  2. transitional  1.20.6, 1.21.1..1.21.3  (SRS + mailbox + ChunkResult + ChunkStorage + net.minecraft.Util)
  3. modern_pre11  1.21.4, 1.21.8, 1.21.10 (SRS + executor + ChunkResult + ChunkStorage + net.minecraft.util? NO: net.minecraft.Util)
  4. modern_11plus 1.21.11, 26             (SRS + executor + ChunkResult + SimpleRegionStorage(ChunkMap) + net.minecraft.util.Util)

Only modern_pre11 (1.21.8) and modern_11plus (26) are fully
implemented; ancient/transitional are STUBBED (raise NotImplementedError) so the
structure is ready to slot in later without reshaping the callers.
"""

import sys


# ---------------------------------------------------------------------------
# Version parsing + era classification
# ---------------------------------------------------------------------------

def _parse(mcver):
    """Parse an MC version string into a comparable tuple of ints.

    Accepts forms like "1.21.8", "1.21", "26", "26.3", "26.3-snapshot-2".
    A trailing "-<qualifier>" (snapshot/pre/rc) is dropped for ordering; the
    numeric prefix decides the era (prerelease-inclusive by design).
    """
    core = str(mcver).strip()
    # strip any -snapshot / -pre / -rc qualifier
    for sep in ("-", "+", " "):
        if sep in core:
            core = core.split(sep, 1)[0]
    parts = []
    for tok in core.split("."):
        num = ""
        for ch in tok:
            if ch.isdigit():
                num += ch
            else:
                break
        parts.append(int(num) if num else 0)
    while len(parts) < 3:
        parts.append(0)
    return tuple(parts[:3])


def era(mcver):
    """Return the era name for an MC version.

    Boundaries (from the drift matrix, section 6):
      - net.minecraft.util.Util + ChunkMap-on-SimpleRegionStorage land at 1.21.11 -> modern_11plus
      - PriorityConsecutiveExecutor lands at 1.21.4                               -> modern_pre11
      - SimpleRegionStorage + ChunkResult land at 1.20.6 (1.20.5)                 -> transitional
      - otherwise                                                                 -> ancient
    The 26.x line (major version 26) is modern_11plus.
    """
    v = _parse(mcver)
    if v[0] >= 26:
        return "modern_11plus"
    if v >= (1, 21, 11):
        return "modern_11plus"
    if v >= (1, 21, 4):
        return "modern_pre11"
    if v >= (1, 20, 6):
        return "transitional"
    return "ancient"


def _stub(name, mcver):
    # TODO: ancient + transitional eras
    raise NotImplementedError(
        "compat.%s not implemented for era '%s' (mcver=%s); "
        "only modern_pre11 (1.21.8) and modern_11plus (26) are wired."
        % (name, era(mcver), mcver)
    )


# ---------------------------------------------------------------------------
# Drift point helpers. Each returns a plain string that Cog inserts verbatim.
# Prefix is uniformly chunksmith$ (cog_sources is already namespace-normalized).
# ---------------------------------------------------------------------------

def util_at_target(mcver):
    """WorldGenRegionMixin @Redirect @At target descriptor for Util.logAndPauseIfInIde.

    net.minecraft.Util (<= 1.21.10) vs net.minecraft.util.Util (1.21.11 / 26).
    STRING-IN-ANNOTATION -> reflection cannot touch it; Cog is mandatory here.
    """
    e = era(mcver)
    if e == "modern_11plus":
        return "Lnet/minecraft/util/Util;logAndPauseIfInIde(Ljava/lang/String;)V"
    if e == "modern_pre11":
        return "Lnet/minecraft/Util;logAndPauseIfInIde(Ljava/lang/String;)V"
    if e in ("ancient", "transitional"):
        # same as modern_pre11 (Util package move is 1.21.11); wired when those eras land.
        return "Lnet/minecraft/Util;logAndPauseIfInIde(Ljava/lang/String;)V"
    return _stub("util_at_target", mcver)


def dimension_identifier_call(mcver):
    """ResourceKey<Level> -> path/id accessor: .location() (<=1.21.10) vs .identifier() (1.21.11/26)."""
    e = era(mcver)
    if e == "modern_11plus":
        return "identifier"
    if e in ("modern_pre11", "transitional", "ancient"):
        return "location"
    return _stub("dimension_identifier_call", mcver)


def screen_holder(mcver):
    """Where the CURRENT SCREEN lives: "minecraft" (<=26.1) or "gui" (26.2+).

    MC 26.2 moved screen get/set off Minecraft and onto its `gui` field:
        26.1.2  ->  Minecraft.screen (public field) + Minecraft.setScreen(Screen)
        26.2+   ->  Minecraft.gui.screen()          + Minecraft.gui.setScreen(Screen)

    Verified with javap against BOTH jars, not inferred: on 26.1.2 `net.minecraft.client.gui.Gui`
    has no screen()/setScreen() at all, and Minecraft still carries them.

    Keys on the 26 MINOR, so it cannot be folded into era() -- every 26.x shares one era. Found by
    building 26.1.2 after the world-enter screen was written against 26.2 only (2026-09-03): the
    26.2-only form compiled clean there and failed on 26.1 with "cannot find symbol: method
    screen()". A reminder that "26.x" is not one API.
    """
    v = _parse(mcver)
    minor = v[1] if len(v) > 1 else 0
    if v[0] > 26 or (v[0] == 26 and minor >= 2):
        return "gui"
    return "minecraft"


def chunkpos_x(mcver):
    """ChunkPos x coord access: field 'x' (<=1.21.11) vs method 'x()' (26+).

    ChunkPos exposed x/z as PUBLIC FIELDS on every pre-26 line INCLUDING 1.21.11; the
    accessor-method form (x()/z()) is a 26-only change. So this keys on major>=26, NOT the
    era name (1.21.11 is modern_11plus but still uses the field).
    """
    if _parse(mcver)[0] >= 26:
        return "x()"
    return "x"


def chunkpos_z(mcver):
    """ChunkPos z coord access: field 'z' (<=1.21.11) vs method 'z()' (26+). See chunkpos_x."""
    if _parse(mcver)[0] >= 26:
        return "z()"
    return "z"


def has_broadcast_changed_chunks(mcver):
    """ServerChunkCacheMixin: is invokeBroadcastChangedChunks(ProfilerFiller) present?

    26-only invoker, called from the chunk-system housekeeping hook. Absent on
    the 1.21.x / 1.20.x lines.
    """
    v = _parse(mcver)
    return v[0] >= 26


def has_minecraft_server_access(mcver):
    """Is the MinecraftServerAccess mixin present? 26-only seam accessor."""
    v = _parse(mcver)
    return v[0] >= 26


def forge_needs_refmap(mcver):
    """Does a classic-Forge (LexForge / ForgeGradle 6) cell for this MC version need a mixin
    refmap wired (the "refmap" key in chunksmith.mixins.json + the mixin AP that generates it)?

    Loader-specific: this ONLY applies to the Forge loader. Fabric (loom always emits + consults
    its own refmap transparently) and NeoForge (mojmap-native runtime, no refmap) never key on
    this -- cog-gen calls it exclusively when -Loader Forge.

    Classic Forge runs SRG-mapped at runtime up to and including the 1.20.4 line, so the
    SpongePowered mixin loader needs the srg<->mojmap refmap to resolve @Inject/@At/@Accessor
    targets by name -> the "refmap" key is MANDATORY there (a missing refmap is a fatal
    "No refMap loaded" at boot). From MC 1.20.6 (ForgeGradle 6.0.16+, which SKIPS reobf) Forge
    runs OFFICIAL MOJANG MAPPINGS at runtime, so dev names == runtime names and NO refmap is
    consulted -> the "refmap" key must be ABSENT (the same mojmap-native posture as NeoForge and
    modern Forge 1.21.x). Ground truth: old-branch Forge cells carry "refmap" only on 1.20.1 /
    1.20.4; 1.20.6 and every 1.21.x cell omit it.

    Boundary == the "ancient" era (1.20.1 / 1.20.4). Returns True there, False from transitional
    (1.20.6) onward, so the four modern Forge cells (1.21.4/1.21.8/1.21.10/1.21.11) get NO refmap.
    """
    return era(mcver) == "ancient"


def has_chunk_storage_accessor(mcver):
    """Is ChunkStorageAccessor (@Mixin ChunkStorage) present?

    Present 1.20.1 .. 1.21.10; GONE at 1.21.11 and 26 (ChunkStorage removed;
    ChunkMap extends SimpleRegionStorage).
    """
    return era(mcver) != "modern_11plus"


def has_ticket_storage(mcver):
    """Is net.minecraft.world.level.TicketStorage present, and reachable off DistanceManager?

    Present on modern_11plus (1.21.11 and the 26.x line) ONLY. The 1.21.11 chunk-ticket rework
    split the ticket store out of DistanceManager into its own TicketStorage class; before that
    there is no such type and no `ticketStorage` field to @Accessor.

    Gates the whole TICKET-DIAGNOSTIC seam added 2026-08-20 during a chunk-residency hunt:
    DistanceManagerMixin (reaches the store so Chunksmith can NAME what is holding a chunk open),
    TicketMixin (reads Ticket.ticksLeft, because vanilla isTimedOut() cannot see the state the
    leaked minecraft:unknown tickets actually sit in), and the sweep/report blocks in
    MinecraftServerMixin that use them.

    Ungated, those files broke EVERY cell below 1.21.11 -- the diagnostics were written straight
    against the live server that had the bug and never compiled anywhere else. Found when the
    CRITICAL smoketest rebuild was attempted 2026-08-20 and Fabric/1.20.1 and Fabric/1.21.1 both
    failed on `cannot find symbol: TicketStorage`.
    """
    return era(mcver) == "modern_11plus"


def has_empty_ticks(mcver):
    """Does vanilla MinecraftServer carry the 'emptyTicks' idle-pause counter field?

    NEW AXIS (independent of the era classification). The empty-server pause feature (the
    pauseWhenEmptySeconds server property + the private int emptyTicks counter) landed at MC
    1.21.2 (snapshot 24w33a). VERIFIED against the decompiled mojmap sources in MC-Java:
    emptyTicks is ABSENT on 1.20.1/1.20.4/1.20.6/1.21/1.21.1 and FIRST PRESENT at 1.21.2
    (present 1.21.2 onward: 1.21.2/1.21.3/1.21.4/1.21.8/1.21.10/1.21.11/26). GROUND TRUTH from the
    OLD published cells confirms it: Fabric/1.20.6 + Fabric/1.21.1 carry NO @Shadow emptyTicks and
    do NOT reset it (keep-awake N/A - "nothing to reset"); Fabric/1.21.4 DOES @Shadow it and
    resets 'this.emptyTicks = 0'.

    DISTINCT from the era axis (a version below 1.21.2 can be transitional, e.g. 1.20.6 / 1.21.1)
    and from the 26-only setter axis (major>=26 routes the reset through the MinecraftServerAccess
    seam accessor rather than the direct @Shadow field). So:
      - major >= 26          : field present; reset via the accessor (has_empty_ticks True but
                               needs_empty_ticks_shadow False -- 26 uses the accessor).
      - 1.21.2 <= mcver < 26 : field present; reset via the direct @Shadow field.
      - mcver < 1.21.2       : field ABSENT -> no @Shadow, no reset (no-op keep-awake), matching
                               the pre-1.21.2 published cells.
    """
    v = _parse(mcver)
    if v[0] >= 26:
        return True
    return v >= (1, 21, 2)


def empty_ticks_reset(mcver):
    """MinecraftServerMixin keep-awake reset of the idle counter.

    26 (major>=26): via seam accessor '((MinecraftServerAccess) (Object) this).setEmptyTicks(0);'
      (26 routes through the accessor because the shadow-field form was flaky under its mixin/AT
      setup; both are equivalent.)
    1.21.2 <= mcver < 26: direct @Shadow field 'this.emptyTicks = 0;'
    mcver < 1.21.2 (all ancient + pre-1.21.2 transitional): the emptyTicks field + empty-server
      pause do NOT exist yet -> no-op (matches the old published 1.20.6 / 1.21.1 cells exactly).
    """
    if _parse(mcver)[0] >= 26:
        # 26 routes through the MinecraftServerAccess seam accessor (26-only mixin).
        return "((MinecraftServerAccess) (Object) this).setEmptyTicks(0);"
    if has_empty_ticks(mcver):
        # 1.21.2 .. 1.21.11: zero the @Shadow emptyTicks field directly. MinecraftServerAccess
        # does NOT exist pre-26, so the accessor form is 26-only.
        return "this.emptyTicks = 0;"
    # < 1.21.2: the empty-server pause + emptyTicks field do NOT exist -> no-op.
    return "// no idle-pause on this MC version (emptyTicks absent)"


def housekeeping_inject_at(mcver):
    """MinecraftServerMixin: where the chunk-system housekeeping @Inject binds.

    ALWAYS tickServer TAIL, on EVERY MC version. mcver is kept in the signature so the
    compat-matrix dump and the Cog call site stay uniform with the other drift points.

    HISTORY -- fixed 2026-08-13 (3.4.0); the 26 line was broken from the day it was added.
    26 used to bind this at
        @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;tickConnection()V")
    on the belief that 26 had moved the housekeeping-friendly point mid-tick. It had not.
    javap -c of net/minecraft/server/MinecraftServer on 26.1, 26.1.2, 26.2 and 26.3-snapshot-7
    shows tickServer reaching tickConnection() at exactly ONE bytecode offset, and only inside
    the "Server empty for {} seconds, pausing" branch, which then returns:

        13: iload      4          // pauseWhenEmptySeconds() * 20
        15: ifle       101        // pause-when-empty disabled -> skip the whole branch
        ...
        62: if_icmplt  101        // emptyTicks < threshold  -> skip the whole branch
        ...
        93: invokevirtual autoSave:()V
        96: aload_0
        97: invokevirtual tickConnection:()V
       100: return                // EARLY RETURN: the real tick never runs on this path
       101: ...                   // tickCount++, tickChildren(BooleanSupplier), profiler, ...
       274: return                // <-- the last RETURN in the method == @At("TAIL")

    The injection was LEGAL -- exactly one match, so the mixin applied cleanly and silently --
    but unreachable in practice. With pause-when-empty-seconds=0 the ifle at 15 skips the
    branch outright, and during a pre-gen Chunksmith's own keep-awake zeroes emptyTicks every
    tick by design, so the if_icmplt at 62 skips it too. Net effect: chunk-system housekeeping
    effectively NEVER ran on the 26 line. (The class does contain a second tickConnection()
    call site that fires every tick, but it lives in tickChildren() -- a different method,
    invisible to an @Inject targeting tickServer.)

    TAIL is bytecode offset 274, the last RETURN in tickServer. It is reached on every
    non-paused tick on every MC version, and it is the point the nine pre-26 cells have always
    used, so this unifies the matrix instead of special-casing it.

    SAFETY w.r.t. the 3.3.0 ticket safe point (chunksmith$onTickHead): housekeeping mutates
    ticket/holder state through runDistanceManagerUpdates() + ChunkMap.tick(), and that must
    never land inside ServerChunkCache.tickChunks' iteration of the simulation chunk tracker.
    At tickServer TAIL, tickChildren() has already RETURNED -- and with it
    ServerChunkCache.tick(), which calls runDistanceManagerUpdates() then tickChunks(). No
    chunk-system iteration is in flight, and we are on the server thread, not an executor pump.
    The 3.3.0 hazard was specifically ticket work arriving from the server EXECUTOR while that
    walk was live; TAIL cannot re-create it.
    """
    return 'at = @At("TAIL")'


def needs_empty_ticks_shadow(mcver):
    """Does MinecraftServerMixin need a @Shadow private int emptyTicks; field?

    Needed only when empty_ticks_reset() references the shadow field DIRECTLY: the emptyTicks
    field exists (has_empty_ticks) AND we are pre-26 (26 zeroes it through the
    MinecraftServerAccess seam accessor instead). So this is True for 1.21.2 <= mcver < 26 and
    False otherwise -- False on 26 (accessor), False below 1.21.2 (field absent: 1.20.1/1.20.4
    ancient, plus 1.20.6 / 1.21 / 1.21.1 transitional). A @Shadow of a nonexistent field is a
    RUNTIME mixin-apply FATAL ("@Shadow field emptyTicks not located"), which is exactly the
    smoketest failure this axis fixes.
    """
    if _parse(mcver)[0] >= 26:
        return False
    return has_empty_ticks(mcver)


def broadcast_changed_chunks_call(mcver):
    """MinecraftServerMixin housekeeping: the optional broadcastChangedChunks call.

    26 emits the extra invoker call; older lines emit nothing.
    """
    if has_broadcast_changed_chunks(mcver):
        return ("((ServerChunkCacheMixin) level.getChunkSource())"
                ".invokeBroadcastChangedChunks(InactiveProfiler.INSTANCE);")
    return "// broadcastChangedChunks invoker absent on this MC version"


def identifier_type(mcver):
    """The MC resource-id class name: ResourceLocation (<=1.21.10) vs Identifier (1.21.11/26).

    net.minecraft.resources.ResourceLocation was renamed to net.minecraft.resources.Identifier
    at 26 (the same rename family as ResourceKey.location()->identifier()). Emitted as a bare
    class name; pair with identifier_import() for the import line.
    """
    if dimension_identifier_call(mcver) == "identifier":
        return "Identifier"
    return "ResourceLocation"


def identifier_import(mcver):
    """The import line for the resource-id class (see identifier_type)."""
    return "import net.minecraft.resources.%s;" % identifier_type(mcver)


def gamemaster_permission_check(mcver, player_var):
    """Boss-bar visibility gate: does this player have gamemaster/op-level permission?

    modern_11plus (1.21.11 AND 26): the new permissions API -
        player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
      (requires import net.minecraft.server.permissions.Permissions)
    The permissions() API + Permissions class landed at 1.21.11 and ServerPlayer.hasPermissions(int)
    was REMOVED there, so this keys on the era (modern_11plus), NOT major>=26.
    pre-1.21.11: the classic op-level check player.hasPermissions(2). No extra import.
    """
    if era(mcver) == "modern_11plus":
        return "%s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)" % player_var
    return "%s.hasPermissions(2)" % player_var


def needs_permissions_import(mcver):
    """Is the net.minecraft.server.permissions.Permissions import needed?

    Needed on modern_11plus (1.21.11 + 26), where the permissions() API is used. See
    gamemaster_permission_check.
    """
    return era(mcver) == "modern_11plus"


def server_boss_event_has_uuid_arg(mcver):
    """Does the ServerBossEvent constructor take a leading UUID arg? 26 added one; pre-26 did not."""
    return _parse(mcver)[0] >= 26


def needs_uuid_import(mcver):
    """Is java.util.UUID needed by BossBarTaskUpdateListener (only the 26 ctor uses it)?"""
    return _parse(mcver)[0] >= 26


def needs_inactive_profiler_import(mcver):
    """MinecraftServerMixin import of InactiveProfiler (only when the broadcast call is emitted)."""
    return has_broadcast_changed_chunks(mcver)


# ---------------------------------------------------------------------------
# AXIS A -- IOWorker executor primitive (drift matrix section 2b/2c).
#
#   LEGACY (mailbox)  : ancient + transitional  (MC 1.20.1 .. 1.21.1/1.21.3)
#       field  mailbox            : ProcessorMailbox<StrictQueue.IntRunnable>
#       pendingWrites             : java.util.Map
#       submit  mailbox.tell(new StrictQueue.IntRunnable(0, () -> {...})) + own CompletableFuture
#   MODERN (executor) : modern_pre11 + modern_11plus (MC 1.21.4 .. 26)
#       field  consecutiveExecutor: PriorityConsecutiveExecutor
#       pendingWrites             : java.util.SequencedMap
#       submit  executor.<Boolean>scheduleWithResult(0, result -> {...})
#
# These two shapes were byte-IDENTICAL from 1.21.4 through 26, so the cog_sources
# copies (IOWorkerAccessor, PersistentEntitySectionManagerMixin) carry the MODERN form
# verbatim and were NOT previously Cog'd. Wiring the transitional/ancient eras makes the
# executor primitive a real Cog axis; the cog_sources copies of those two files select the
# right shape via the helpers below.
# ---------------------------------------------------------------------------

def worldgen_uses_chunkstep(mcver):
    """WorldGenRegion generation-step shape: 1.21.*+ carries a single ChunkStep record
    (generatingStep, with .targetStatus() + .blockStateWriteRadius()); 1.20.* carries the older
    two-field form (ChunkStatus generatingStatus + int writeRadiusCutoff). ChunkStep did not
    exist on the 1.20 line. Keyed on major/minor (1.21+ vs 1.20.*), same boundary as the
    Hanging->BlockAttached rename -- it cuts THROUGH the transitional era (1.20.6 = two-field,
    1.21.1 = ChunkStep)."""
    v = _parse(mcver)
    if v[0] == 1 and v[1] == 20:
        return False
    return True


def registry_lookup_call(mcver):
    """RegistryAccess registry fetch method: registryOrThrow (ancient+transitional, <=1.21.1) vs
    lookupOrThrow (modern, >=1.21.4). registryAccess().registryOrThrow(...) was renamed to
    lookupOrThrow at 1.21.4; on the transitional/ancient lines lookupOrThrow returns a
    RegistryLookup (not a Registry<T>), so the old name MUST be used there. Also surfaces in
    FabricWorld.playSound (.registryOrThrow(SOUND_EVENT) vs .lookupOrThrow(...)), handled per-cell."""
    return "registryOrThrow" if era(mcver) in ("ancient", "transitional") else "lookupOrThrow"


def use_mailbox_executor(mcver):
    """True on the LEGACY (ProcessorMailbox) IOWorker eras (ancient + transitional), False on
    the MODERN (PriorityConsecutiveExecutor) eras (modern_pre11 + modern_11plus)."""
    return era(mcver) in ("ancient", "transitional")


def ioworker_executor_field(mcver):
    """The @Accessor("...") field name for the IOWorker single-thread executor."""
    return "mailbox" if use_mailbox_executor(mcver) else "consecutiveExecutor"


def ioworker_executor_type(mcver):
    """The declared Java type of the IOWorker executor accessor return value."""
    if use_mailbox_executor(mcver):
        return "ProcessorMailbox<StrictQueue.IntRunnable>"
    return "PriorityConsecutiveExecutor"


def ioworker_executor_getter(mcver):
    """The IOWorkerAccessor getter name for the executor (kept role-descriptive per era)."""
    return "chunksmith$getMailbox" if use_mailbox_executor(mcver) else "chunksmith$getConsecutiveExecutor"


def pending_writes_type(mcver):
    """The declared Java type of the IOWorker pendingWrites map: Map (legacy) vs SequencedMap (modern).

    This is a real vanilla field-type change (LinkedHashMap declared as Map pre-1.21.4, as
    SequencedMap from 1.21.4), not just our accessor declaration.
    """
    return "Map" if use_mailbox_executor(mcver) else "SequencedMap"


def ioworker_executor_imports(mcver):
    """The import lines the IOWorker executor primitive needs, as a list (order-stable).

    Legacy: ProcessorMailbox + StrictQueue.
    Modern: PriorityConsecutiveExecutor.
    (The pendingWrites map import is emitted separately via pending_writes_import.)
    """
    if use_mailbox_executor(mcver):
        return [
            "import net.minecraft.util.thread.ProcessorMailbox;",
            "import net.minecraft.util.thread.StrictQueue;",
        ]
    return [
        "import net.minecraft.util.thread.PriorityConsecutiveExecutor;",
    ]


def pending_writes_import(mcver):
    """The single java.util import line for the pendingWrites map type (Map vs SequencedMap)."""
    return "import java.util.%s;" % pending_writes_type(mcver)


# ---------------------------------------------------------------------------
# EntityStorage worker reach (drift matrix section 2a) -- AXIS B entity side.
#   ancient (1.20.1/1.20.4): @Accessor("worker") -> IOWorker (no SimpleRegionStorage layer)
#   transitional .. 26      : @Accessor("simpleRegionStorage") -> SimpleRegionStorage
# The transitional+modern eras are identical here (SRS); only ancient differs. Wired so the
# ancient batch can slot in without reshaping the EntityStorageAccessor cog_source.
# ---------------------------------------------------------------------------

def entity_storage_uses_srs(mcver):
    """True when EntityStorage exposes 'simpleRegionStorage' (SimpleRegionStorage layer present:
    transitional and newer); False on ancient where it directly holds the 'worker' IOWorker."""
    return era(mcver) != "ancient"


def has_simple_region_storage_accessor(mcver):
    """Is the SimpleRegionStorageAccessor (@Mixin SimpleRegionStorage) present?

    The SimpleRegionStorage class landed at 1.20.5, so on ancient (1.20.1/1.20.4) the class
    does NOT exist and the accessor cannot compile -> ABSENT there, PRESENT from transitional
    onward. Same boolean as entity_storage_uses_srs but named for the file-presence concern
    (cog-gen drops the file when False). On ancient the ChunkMap->worker path uses
    ChunkStorageAccessor and the entity path reaches EntityStorage.worker directly, so nothing
    references SimpleRegionStorage at all."""
    return entity_storage_uses_srs(mcver)


def entity_storage_accessor_type(mcver):
    """EntityStorageAccessor @Accessor return type: IOWorker (ancient, direct 'worker' field) vs
    SimpleRegionStorage (transitional+, the 'simpleRegionStorage' layer)."""
    return "IOWorker" if not entity_storage_uses_srs(mcver) else "SimpleRegionStorage"


def entity_storage_accessor_field(mcver):
    """EntityStorageAccessor @Accessor("...") target field: 'worker' (ancient) vs
    'simpleRegionStorage' (transitional+)."""
    return "simpleRegionStorage" if entity_storage_uses_srs(mcver) else "worker"


def entity_storage_accessor_getter(mcver):
    """EntityStorageAccessor getter name (role-descriptive per era): chunksmith$getEntityWorker
    (ancient, returns the IOWorker directly) vs chunksmith$getSimpleRegionStorage (transitional+)."""
    return "chunksmith$getSimpleRegionStorage" if entity_storage_uses_srs(mcver) else "chunksmith$getEntityWorker"


def entity_storage_accessor_import(mcver):
    """The import line for the EntityStorageAccessor return type (IOWorker vs SimpleRegionStorage)."""
    if entity_storage_uses_srs(mcver):
        return "import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;"
    return "import net.minecraft.world.level.chunk.storage.IOWorker;"


# ---------------------------------------------------------------------------
# ServerChunkCache getChunkFutureMainThread return type (drift matrix section 2f).
#   ancient (1.20.1/1.20.4): Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>
#                            (imports com.mojang.datafixers.util.Either + ChunkHolder)
#   transitional .. 26     : ChunkResult<ChunkAccess>
#                            (import net.minecraft.server.level.ChunkResult)
# ChunkResult replaced the Either result at 1.20.5. Only the @Invoker declared return type +
# its imports drift; the invoker is a structural @Invoker so it just needs the right signature
# to bind. STRING-in-signature, so a reflection facade cannot help -> Cog.
# ---------------------------------------------------------------------------

def chunk_future_result_type(mcver):
    """The generic return type of ServerChunkCache.getChunkFutureMainThread's CompletableFuture:
    'Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>' (ancient) vs 'ChunkResult<ChunkAccess>'
    (transitional+)."""
    if era(mcver) == "ancient":
        return "Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>"
    return "ChunkResult<ChunkAccess>"


def chunk_future_result_imports(mcver):
    """The import lines the getChunkFutureMainThread return type needs, as a list (order-stable).

    ancient: com.mojang.datafixers.util.Either + net.minecraft.server.level.ChunkHolder.
    transitional+: net.minecraft.server.level.ChunkResult.
    (ChunkAccess is imported unconditionally by the file.)"""
    if era(mcver) == "ancient":
        return [
            "import com.mojang.datafixers.util.Either;",
            "import net.minecraft.server.level.ChunkHolder;",
        ]
    return [
        "import net.minecraft.server.level.ChunkResult;",
    ]


def chunkstatus_import(mcver):
    """The import line for net.minecraft ChunkStatus. Mojang moved ChunkStatus into the
    '...chunk.status' subpackage at 1.20.5 (same boundary as SimpleRegionStorage/ChunkResult).
    ancient (1.20.1/1.20.4): net.minecraft.world.level.chunk.ChunkStatus
    transitional+:           net.minecraft.world.level.chunk.status.ChunkStatus"""
    if era(mcver) == "ancient":
        return "import net.minecraft.world.level.chunk.ChunkStatus;"
    return "import net.minecraft.world.level.chunk.status.ChunkStatus;"


# ---------------------------------------------------------------------------
# Hanging / BlockAttached entity mixin (drift matrix section 1 presence anomaly).
#   1.20.* : @Mixin(HangingEntity)        -> HangingEntityMixin
#   1.21.*+: @Mixin(BlockAttachedEntity)  -> BlockAttachedEntityMixin
# Same @Redirect body + role; only the target class + file name differ. Keyed on major/minor,
# NOT era (the boundary is 1.20.2, i.e. 1.21+ vs 1.20.*, which cuts THROUGH the transitional
# era: 1.20.6 is Hanging, 1.21.1 is BlockAttached).
# ---------------------------------------------------------------------------

def hanging_entity_class(mcver):
    """The target class for the invalid-position log suppressor: HangingEntity (1.20.*) vs
    BlockAttachedEntity (1.21.*+). Mojang moved the save+log logic up to the new
    BlockAttachedEntity superclass at 1.20.2."""
    v = _parse(mcver)
    if v[0] == 1 and v[1] == 20:
        return "HangingEntity"
    return "BlockAttachedEntity"


def hanging_mixin_basename(mcver):
    """The mixin class/file base name matching hanging_entity_class()."""
    return "%sMixin" % hanging_entity_class(mcver)


# ---------------------------------------------------------------------------
# Self-test: print the full matrix for the wired versions.
# ---------------------------------------------------------------------------

_TEST_VERSIONS = [
    "1.20.1", "1.20.4",       # ancient (stubbed points may raise)
    "1.20.6", "1.21.1",       # transitional
    "1.21.4", "1.21.8", "1.21.10",  # modern_pre11
    "1.21.11", "26", "26.3-snapshot-2",  # modern_11plus
]


def _safe(fn, mcver):
    try:
        return repr(fn(mcver))
    except NotImplementedError as exc:
        return "<stub: %s>" % (str(exc).split(";")[0])


def main():
    print("ChunkSmith compat.py era matrix")
    print("=" * 78)
    header = ("mcver", "era", "util@At", "dim", "cpX", "bcast", "msAccess", "chunkStore", "inject")
    print("%-16s %-13s %-30s %-8s %-4s %-6s %-9s %-11s %s" % header)
    for v in _TEST_VERSIONS:
        e = era(v)
        util = util_at_target(v)
        util_short = "util.Util" if "util/Util" in util else "Util"
        row = (
            v, e, util_short,
            dimension_identifier_call(v),
            chunkpos_x(v),
            str(has_broadcast_changed_chunks(v)),
            str(has_minecraft_server_access(v)),
            str(has_chunk_storage_accessor(v)),
            "tickConn" if has_broadcast_changed_chunks(v) else "TAIL",
        )
        print("%-16s %-13s %-30s %-8s %-4s %-6s %-9s %-11s %s" % row)
    print("=" * 78)
    print("Detail (modern eras, fully wired):")
    for v in ("1.21.8", "26"):
        print("  --- mcver=%s (era=%s) ---" % (v, era(v)))
        print("    util_at_target        = %s" % util_at_target(v))
        print("    dimension_identifier  = %s" % dimension_identifier_call(v))
        print("    chunkpos_x / _z       = %s / %s" % (chunkpos_x(v), chunkpos_z(v)))
        print("    empty_ticks_reset     = %s" % empty_ticks_reset(v))
        print("    housekeeping_inject   = %s" % housekeeping_inject_at(v))
        print("    needs_emptyTicks_shad = %s" % needs_empty_ticks_shadow(v))
        print("    broadcast_call        = %s" % broadcast_changed_chunks_call(v))
        print("    has_chunkStorageAcc   = %s" % has_chunk_storage_accessor(v))
        print("    has_minecraftSrvAcc   = %s" % has_minecraft_server_access(v))
    return 0


if __name__ == "__main__":
    sys.exit(main())


def forge_new_eventbus(mcver):
    """Forge EventBus 7.x era. MC 1.21.8+ (Forge 58.x-61.x) uses the new EventBus 7.x API: the @Mod
    constructor takes an FMLJavaModLoadingContext and the game-bus lifecycle handlers register via each
    event's static BUS.addListener(this::...). Classic Forge (<=1.21.5, 47.x-57.x) uses
    MinecraftForge.EVENT_BUS.register(this) with @SubscribeEvent-annotated handlers. Loader-specific
    (Forge only) -- Fabric/NeoForge never call this.
    """
    return _parse(mcver)[:3] >= (1, 21, 8)


# ===========================================================================
# LOD FEATURE AXIS (3.0.0-beta-1, 2026-07-12)
#
# The server-side LOD feature (CSLOD store + codec + region store + the HTTP backchannel + tokens +
# the in-band channel + /cslod + the worldgen LodSink hook) is ported to exactly the cells that have
# a REAL client-side renderer to serve. Bounds come off the Modrinth
# API + jar manifests, 2026-07-12):
#   Distant Horizons 3.2.0-b : 1.20.1 (fabric + FORGE, no neoforge), 1.21.1 (fabric + neoforge),
#                              1.21.11 (fabric + neoforge), 26.1.2 / 26.2 (fabric + neoforge)
#   voxy (upstream)          : NEVER published for 1.20.1 or 1.21.1; 1.21.11 + 26.x, FABRIC only
# Plugin (Bukkit/Paper/Folia) is PERMANENTLY out of scope: there is no plugin-side renderer.
# ===========================================================================

# The (loader, MC) cells that carry the LOD feature. Exact tuples -- no ranges, deliberately.
#
# This list is a CURATION, not a capability boundary. Two different constraints are at work and it is
# worth keeping them apart, because conflating them is how a mod ends up "explaining" a gap it simply
# has not filled yet:
#
#   HARD (the feature CANNOT exist):
#     - Forge 26.x -- there is no Forge 26 line at all (FG6 cannot build it).
#     - Forge/NeoForge 1.20.1 for VOXY -- voxy is Fabric-only, and has never published 1.20.1 or 1.21.1
#       on any loader; those cells therefore carry the store/backchannel only, never VoxyLodSink.
#     - NeoForge 1.20.1 -- Distant Horizons ships Forge there, not NeoForge (and we ship no NeoForge
#       1.20.1 cell anyway).
#
#   CHOSEN (the feature COULD exist and does not, yet):
#     - Fabric/NeoForge 1.20.4, 1.20.6, 1.21.4, 1.21.5, 1.21.8, 1.21.10. Distant Horizons DOES ship on
#       these, so a renderer exists to serve. They are left out because these are the versions people
#       do not actually run -- the LOD feature ships first on the CRITICAL line (1.20.1, 1.21.1,
#       1.21.11, 26.x). This is a scope decision, and it is a real coverage gap to fill, not a fact
#       about the loaders. Do not write it up as one.
_LOD_CELLS = {
    "Fabric": ((1, 20, 1), (1, 21, 1), (1, 21, 11)),   # + every 26.x (handled below)
    "NeoForge": ((1, 21, 1), (1, 21, 11)),             # + every 26.x
    "Forge": ((1, 20, 1),),                            # DH ships Forge on 1.20.1; no 26 Forge at all
}


def has_lod(mcver, loader):
    """Does this (loader, MC) cell carry the server-side LOD feature?

    The cell must (a) have a client-side LOD renderer that exists for that (loader, MC) -- otherwise the
    server would be generating and serving data nothing can draw -- AND (b) be on the shipped list above.
    (b) is a CURATION, not a capability: see the _LOD_CELLS comment. 26.x is LOD-capable on Fabric and
    NeoForge (DH ships both); Forge has no 26 line at all (FG6 cannot build it), and Forge's only DH
    line is 1.20.1.
    """
    v = _parse(mcver)
    if loader not in _LOD_CELLS:
        return False
    if v[0] >= 26:
        return loader in ("Fabric", "NeoForge")
    return v[:3] in _LOD_CELLS[loader]


# ---------------------------------------------------------------------------
# RENDERER-ADAPTER AXIS (3.0.0-beta-1, 2026-07-12) -- the SINGLEPLAYER injection path.
#
# In singleplayer the integrated server runs INSIDE the client JVM, so Chunksmith can hand LODs to the
# renderer DIRECTLY -- no network hop. That path needs classes that compile against
# the third-party jar, so a cell can only carry an adapter where that jar EXISTS for its (loader, MC).
# Bounds are read off the Modrinth API + jar manifests (2026-07-12):
#
#   Distant Horizons 3.2.0-b : EVERY LOD cell. 1.20.1 (fabric + FORGE), 1.21.1 / 1.21.11 / 26.x
#                              (fabric + neoforge). So has_dh == has_lod, and the DH sink/override/push
#                              reaches all 8 cells.
#   voxy (upstream)          : FABRIC ONLY, and NEVER published for 1.20.1 or 1.21.1. Published lines are
#                              1.21.11 and 26.x. So exactly Fabric/1.21.11 + Fabric/26. Everywhere else
#                              the voxy seam is COMPILE-TIME ABSENT (the classes are not generated at all)
#                              -- the mod must never claim a renderer it cannot feed.
#
# The two are now INDEPENDENT gates. They used to be one (has_lod_renderer_integration, "Fabric 26
# only"), which conflated "DH exists here" with "voxy exists here" and cost every other cell its
# singleplayer LODs.
# ---------------------------------------------------------------------------

def has_dh(mcver, loader):
    """Does this cell carry the Distant Horizons adapter (CsLodDhSupport / CsLodDhGenerator /
    CsLodDhPusher, and the /cslod dhpush subcommand)?

    TRUE on every LOD cell: DH ships a jar for all of them (see the module note above). DH's whole API
    surface that we touch (DhApi.Delayed.terrainRepo, DhApiLevelLoadEvent, IDhApiLevelWrapper,
    DhApiChunk, DhApiResult) is com.seibel.* and names NO Minecraft type, so one plain compileOnly jar
    works on every loader and every runtime mapping -- nothing to remap.
    """
    return has_lod(mcver, loader)


def has_voxy(mcver, loader):
    """Does this cell carry the voxy adapter (VoxyLodSink + CsLodVoxyInjector, and /cslod inject)?

    Fabric >= 1.21.11: UPSTREAM voxy (Fabric-only; its VoxyCommon implements net.fabricmc.api.
    ModInitializer -- the adapter does not even COMPILE on another loader). Upstream has NEVER
    published a 1.20.1 or a 1.21.1 build on any loader. Published lines: 1.21.11, 26.1.x, 26.2.

    Fabric 1.21.1 and NeoForge 1.21.1: the m3t4f1v3 FORK (github.com/m3t4f1v3/voxy,
    `multiversion` branch), not upstream -- upstream never published 1.21.1 on any loader.
    Verified 2026-08-03 by building both cells from source: mod id is "voxy" on both loaders.
    Its NeoForge cell is a genuine native NeoForge port (real net.neoforged.moddev/ModDevGradle
    build, its own src/neoforge/java entrypoint set) rather than a Sinytra Connector repackage,
    so it compiles mojmap-native (VoxelIngestService.rawIngest takes real LevelChunkSection/
    DataLayer, not intermediary class_2826/class_2804) -- the same mapping Chunksmith's own
    NeoForge cells already compile against. NeoForgeVoxyCommon extends the shared VoxyCommon
    (same static INSTANCE/FACTORY), so VoxyCommon.getInstance() works unchanged on this loader.
    ARR-derived like every voxy fork: compileOnly soft-dep, never vendored. See
    the renderer-ecosystem recon, sec. "NEOFORGE/FORGE VOXY: BLOCKER OR GAP", for the
    this rests on.

    Every other cell gets NO voxy class at all -- a compile-time-absent seam, not a stub.
    """
    v = _parse(mcver)
    if loader == "Fabric":
        if v == (1, 21, 1):
            return True  # m3t4f1v3 fork
        if v[0] >= 26:
            return True
        return v >= (1, 21, 11)
    if loader == "NeoForge":
        return v == (1, 21, 1)  # m3t4f1v3 fork, native port
    return False


def has_section_builder(mcver, loader):
    """Is CsLodSectionBuilder generated? It is the shared inverse of CsLodExtractor (stored record ->
    vanilla LevelChunkSection) and is used by BOTH renderer adapters, so it is present wherever either
    is."""
    return has_dh(mcver, loader) or has_voxy(mcver, loader)


# Distant Horizons is NOT a per-MC-line jar pin any more, so there is deliberately no dh_jar(mcver).
#
# Chunksmith uses DH's PUBLIC API only -- no mixin into DH from this mod -- so it compiles against DH's
# standalone API artifact and needs NO full DH mod jar at compile time at all:
#
#     maven.modrinth:distanthorizonsapi:7.0.0     (Modrinth maven, group maven.modrinth)
#
# That artifact is MINECRAFT-AGNOSTIC: ONE 344 KB jar for every MC version and every loader, in place of
# four 28 MB mod jars. DH's own DhApi.READ_ME tells integrators to do exactly this -- compile against the
# API jar, use the full mod jar only at runtime -- and every API method Chunksmith calls has been
# signature-stable since DH 2.0.0-a across six API-major bumps. The old per-line pin implied a coupling to
# a specific DH build that never actually existed.
DH_API_ARTIFACT = "maven.modrinth:distanthorizonsapi:7.0.0"


# ---------------------------------------------------------------------------
# LOD -- the CLIENT half
# ---------------------------------------------------------------------------


def screen_extract(mcver):
    """26.1 replaced the Screen render entry point and the centred-text call.

    <= 1.21.11 : render(GuiGraphics, int, int, float)      + GuiGraphics.drawCenteredString
    >= 26.1    : extractRenderState(GuiGraphicsExtractor,) + GuiGraphicsExtractor.centeredText

    Read off Screen.java and GuiGraphics.java in every decompiled tree 1.20.3 .. 26.3-pre-2. This is
    the ONLY drift in WorldEnterScreen -- fill, Button.builder, isPauseScreen and the protected
    `font` field are identical across that whole range.
    """
    return _parse(mcver)[0] >= 26


def has_world_clock(mcver):
    """26 replaced the day-time API with a clock manager.

    <= 1.21.11 : ServerLevel.setDayTime(long)
    >= 26.1    : MinecraftServer.clockManager().setTotalTicks(Holder<WorldClock>, long), with the
                 clock from level.dimensionType().defaultClock()

    net.minecraft.world.clock.WorldClock does not exist at all below 26, so the import is
    conditional too. Confirmed absent in every 1.20.x and 1.21.x tree.
    """
    return _parse(mcver)[0] >= 26


def gamerules_moved(mcver):
    """1.21.11 moved GameRules to its own package AND reshaped the accessors.

    <= 1.21.10 : net.minecraft.world.level.GameRules
                 GameRules.Key<GameRules.BooleanValue> RULE_DAYLIGHT ("doDaylightCycle")
                 read  getBoolean(key)   write  getRule(key).set(value, server)
    >= 1.21.11 : net.minecraft.world.level.gamerules.GameRules
                 GameRule<Boolean> ADVANCE_TIME ("advance_time")
                 read  get(rule)         write  set(rule, value, server)

    Read off GameRules.java in both decompiled trees, not from release notes: RULE_DAYLIGHT at
    MC-Java\1.21.x\1.21.10 line 74, ADVANCE_TIME at MC-Java\1.21.x\1.21.11 line 20. Note 1.21.10
    ALSO carries a net.minecraft.server.jsonrpc.internalapi.GameRules -- a different class entirely,
    and the one a naive file search finds first.

    Shares era()'s modern_11plus boundary but is named for what it gates: the import and the two
    accessor shapes, which a cell can need without caring about the rest of that era.
    """
    return era(mcver) == "modern_11plus"


def gamerules_import(mcver):
    """The GameRules import for this version. See gamerules_moved."""
    if gamerules_moved(mcver):
        return "import net.minecraft.world.level.gamerules.GameRules;"
    return "import net.minecraft.world.level.GameRules;"


def gamerule_time_get(mcver, level):
    """Expression reading the time-advance gamerule as a boolean. See gamerules_moved."""
    if gamerules_moved(mcver):
        return "%s.getGameRules().get(GameRules.ADVANCE_TIME)" % level
    return "%s.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)" % level


def gamerule_time_set(mcver, level, value, server):
    """Statement setting the time-advance gamerule. See gamerules_moved."""
    if gamerules_moved(mcver):
        return "%s.getGameRules().set(GameRules.ADVANCE_TIME, %s, %s);" % (level, value, server)
    return "%s.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(%s, %s);" % (level, value, server)


def forge_tick_event_split(mcver):
    """Does Forge's `TickEvent.ClientTickEvent` carry the nested Pre/Post classes?

    Read straight out of the universal jars:
      * forge 47 (1.20.1)  -- ABSENT. One `ClientTickEvent`, phased: `event.phase == Phase.END`.
      * forge 49 (1.20.4)  -- PRESENT (`ClientTickEvent$Post`, `$Pre`).
      * forge 50 (1.20.6)  -- PRESENT.

    Keyed at **1.20.5**, i.e. the whole 1.20 line uses the PHASED form -- and the keying is on what a
    cell's RANGE must survive, not on the version it is named after.

    Root case (2026-09-05): keyed at 1.20.4 first, on the reasoning that the boundary lay somewhere in
    forge 47..49 and 1.20.4 was where our cells were. That was wrong, and wrong in a way a build cannot
    catch: the `Forge/1.20.4` CELL declares `[1.20.2,1.20.5)`, so its jar also runs on **forge 48**,
    which has no Pre/Post either. It compiled clean against 49 and then died at RUNTIME on a 1.20.2
    client with `NoClassDefFoundError: TickEvent$ClientTickEvent$Post`, taking the mod's whole client
    subscriber registration with it ("Failed to register automatic subscribers").

    The phased form is safe across the entire classic line, which is why it is now used for all of it:
    `TickEvent.phase` is a public final field on forge 49 AND 50 (checked with javap), `Post` EXTENDS
    `ClientTickEvent`, and Forge's EventBus delivers a subclass event to a superclass listener -- so a
    `ClientTickEvent` listener still fires on 49+, with `phase == END` at the same moment `Post` means.

    LESSON: a per-cell predicate must be evaluated against the cell's RANGE FLOOR, not its
    representative version. Every pre-26 cell is a range.
    """
    return _parse(mcver) >= (1, 20, 5)


def hand_rolled_freeze(mcver):
    """Which freeze MECHANISM does this CELL ship? (Not the same question as has_tick_rate_manager,
    which is a fact about vanilla.)

    True for every cell below 1.20.5. That is wider than "vanilla has no TickRateManager" (1.20.3),
    and deliberately so:

    The pre-26 cells are ranges, not points. `Fabric/1.20.4` and `Forge/1.20.4` both declare
    `[1.20.2,1.20.5)` -- which STRADDLES the 1.20.3 boundary. One jar cannot pick a freeze mechanism
    at runtime, so a cell spanning that boundary has to choose one that works on both sides.

    The hand-rolled one does. Verified by class/call presence across the decompiled trees: all seven
    `ServerLevel.tick` call sites the redirects target, plus `Level.guardEntityTick`, are present and
    unchanged at 1.20.2, 1.20.3 AND 1.20.4. Above 1.20.3 our redirects simply sit alongside vanilla's
    own guards -- we never call `setFrozen`, so vanilla thinks it is running normally and ours do the
    skipping. Same outcome, one mechanism, no double-freeze.

    This is why the 1.20.2/1.20.3 cell SPLIT that was on the table is not needed: the straddle was
    only a problem while the feature depended on a vanilla API that appears mid-range. It no longer
    does. No new cells, no extra published jars.

    1.20.5+ keeps the vanilla path: those cells are entirely above the boundary, `setFrozen` is the
    less invasive option, and it is what every cell from 1.20.6 up has already been gated on.
    """
    v = _parse(mcver)
    if v[0] >= 26:
        return False
    return v < (1, 20, 5)


def has_tick_rate_manager(mcver):
    """Does vanilla have a tick freeze we can borrow?

    `TickRateManager` and `ServerTickRateManager` first exist at **1.20.3** -- verified by class
    presence across the decompiled trees (1.20 / 1.20.1 / 1.20.2 absent; 1.20.3 / 1.20.4 present).
    Below that there is no `setFrozen`, no `runsNormally` and no `isEntityFrozen`, so:

      * `WorldEnterPregen.freeze()` has nothing to call and only sets our own flag;
      * `TickRateManagerMixin` (which overrides vanilla's PLAYER exemption) must not be generated --
        its @Mixin target class does not exist;
      * `ServerLevelFreezeMixin` + `LevelEntityFreezeMixin` are generated instead, re-adding
        vanilla's own guards from outside.

    The guard set is not invented: 1.20.1's `ServerLevel.tick` is 1.20.3's with the `if` removed.
    The guard set is enumerated in ServerLevelFreezeMixin's own class javadoc, which is the file
    that implements it.
    """
    v = _parse(mcver)
    if v[0] >= 26:
        return True
    return v >= (1, 20, 3)


def has_world_enter(mcver, loader):
    """Does this cell carry the WORLD-ENTER PREGEN (mod_support #20)?

    SINGLE-PLAYER, full-GUI feature: it freezes the world on entry, pregenerates behind a progress
    screen, and hands control over. A dedicated server has no world-entry moment and nobody to show a
    screen to, which is why Config.isWorldEnterPregenSupported() is false on Bukkit rather than the
    key being accepted and ignored.

    THE REAL FLOOR IS 1.20.3. ServerTickRateManager and TickRateManager.isEntityFrozen both arrive
    there and the freeze has nothing to call below it -- a whole-tree search of 1.20.1 finds no
    occurrence of TickRateManager, setFrozen, isEntityFrozen or runsNormally in any file. From 1.20.3
    up, isEntityFrozen is the SAME expression all the way to 26.3-pre-2, so the player-freeze mixin
    needs no per-version work at all; the only drift in the feature is the Screen entry point at 26.1
    (see screen_extract) and the gamerule key at 1.21.11 (ADVANCE_TIME, was RULE_DAYLIGHT).

    1.20 / 1.20.1 / 1.20.2 would need a hand-rolled freeze of our own. Owner's call (2026-09-04): best
    effort, some parity is better than none -- so they are a later step, not a permanent exclusion.

    WIDENING IS DELIBERATE AND ONE CELL AT A TIME. This list is the BUILD target set -- cog-gen emits
    nothing for a cell that is not in it, so a cell must be listed before it can be compiled at all.
    It is therefore not a record of what is proven; the harness gate is. Add one cell, build it, gate
    it on the harness, and only then add the next. Do not batch-add a family and build them together:
    the point is that a failure names exactly one cell.

    Proven on the harness so far (world-enter screen up, player held, screen eyeballed):
      1.21.11 Fabric -- 3.18.0, 2026-09-04.
    """
    v = _parse(mcver)
    if v[0] >= 26:
        return loader in ("Fabric", "NeoForge")
    # Proven backport targets, newest first. 1.21.11 is the first because it differs from 26.1 by
    # the Screen entry point ALONE -- it already has the ADVANCE_TIME gamerule -- so it isolates one
    # drift point in the first build.
    targets = {
        ("1.21.11", "Fabric"),
        ("1.21.11", "NeoForge"),
        ("1.21.11", "Forge"),
        ("1.21.10", "Fabric"),
        ("1.21.10", "NeoForge"),
        ("1.21.10", "Forge"),
        ("1.21.8", "Fabric"),
        ("1.21.8", "NeoForge"),
        ("1.21.8", "Forge"),
        # NeoForge 1.21.5/1.21.6/1.21.7 ride the NeoForge/1.21.8 cell -- its jar declares
        # [1.21.5,1.21.9), so there is no separate cell to gate. Fabric and Forge each need
        # their own: Fabric/1.21.5 is a one-version cell, and Forge/1.21.5 is CLASSIC EventBus.
        ("1.21.5", "Fabric"),
        ("1.21.5", "Forge"),
        ("1.21.4", "Fabric"),
        ("1.21.4", "NeoForge"),
        ("1.21.4", "Forge"),
        ("1.21.1", "Fabric"),
        ("1.21.1", "NeoForge"),
        ("1.21.1", "Forge"),
        ("1.20.6", "Fabric"),
        ("1.20.6", "NeoForge"),
        ("1.20.6", "Forge"),
        # Below 1.20.3 there is no vanilla tick freeze -- see has_tick_rate_manager. These cells get
        # the hand-rolled one. NeoForge has no Chunksmith cell below 1.20.6, so Fabric and Forge are
        # the whole supported set at 1.20.1.
        ("1.20.4", "Fabric"),
        ("1.20.4", "Forge"),
        ("1.20.1", "Fabric"),
        ("1.20.1", "Forge"),
    }
    return (mcver, loader) in targets


def has_lod_client(mcver, loader):
    """Does this cell carry the MULTIPLAYER LOD CLIENT (the download client, the renderer injectors,
    the DH dedupe-gate mixin)?

    Exactly the LOD cells -- the client half is only useful where a renderer exists to feed, which is
    the same condition has_lod() already encodes. Kept as its own name because it gates a DIFFERENT
    set of files and a DIFFERENT side, and conflating the two is how a cell ends up claiming a
    renderer it cannot feed.

    chunksmith:lod payload id, so a player who had both -- a self-hoster who plays singleplayer AND
    joins a friend's Chunksmith server -- crashed on startup with
    "Packet type [id=chunksmith:lod] is already registered!". One mod, one registration.
    """
    return has_lod(mcver, loader)


# The FULL DH mod jar, needed at COMPILE time only by the LOD client's dedupe-gate mixin, which targets
# DH's INTERNAL com.seibel.distanthorizons.core.level.DhClientLevel -- a class the standalone
# distanthorizonsapi artifact does not carry. DH is a plain library (com.seibel.*, names no Minecraft
# type, remap = false), so ONE jar serves every cell and every runtime mapping. compileOnly, never
# shipped: DH is LGPL and not ours to redistribute. Lives in the gitignored repo-root libs/.
DH_MIXIN_JAR = "DistantHorizons-3.2.0-b-1.21.1.jar"


def neo_client_send_import(mcver):
    """NeoForge moved the CLIENT's send off PacketDistributor between 21.1 and 21.11: on 21.1 the method
    is PacketDistributor.sendToServer and ClientPacketDistributor DOES NOT EXIST; by 21.11 it is
    ClientPacketDistributor.sendToServer and PacketDistributor.sendToServer is GONE. A hard either/or,
    not a deprecation. javap-proven against neoforge-21.1.233 and neoforge-21.11.42 universal jars."""
    if _parse(mcver) >= (1, 21, 11):
        return "import net.neoforged.neoforge.client.network.ClientPacketDistributor;"
    return "import net.neoforged.neoforge.network.PacketDistributor;"


def neo_client_send_call(mcver, arg):
    cls = "ClientPacketDistributor" if _parse(mcver) >= (1, 21, 11) else "PacketDistributor"
    return "%s.sendToServer(%s);" % (cls, arg)


def neo_lod_registration(mcver):
    """The ONE registration of chunksmith:lod on NeoForge -- now that the jar is BOTH sides.

    PayloadRegistrar.playBidirectional overloads, javap-proven:

      neoforge 21.1.233  (MC 1.21.1) : ONLY the 3-arg (type, codec, handler) form exists.
      neoforge 21.11.42+ (1.21.11/26): BOTH the 3-arg AND a 4-arg (type, codec, SERVERbound,
                                       CLIENTbound) form -- serverbound FIRST.

    While Chunksmith was SERVER-only the 3-arg form was right everywhere: a dedicated server only ever
    receives serverbound, so the single handler landing in the serverbound slot was correct, and the
    form exists on both -- no branch. The merge changed that. On 21.11+ a CLIENT whose clientbound slot
    is null does not warn and does not degrade: NeoForge refuses to load the mod ("Some clientbound
    payloads are missing client-side handlers") and drops to the loading-error screen. So:
      21.1   -> 3-arg, one handler, branch on the player inside it.
      21.11+ -> 4-arg, both slots named.

    NEVER a playToServer plus a playToClient: NeoForge keys its payload registry on the payload ID, so
    registering chunksmith:lod twice is a hard load failure ("Cannot register payload chunksmith:lod as
    it is already registered"). That is exactly the crash a player got with Chunksmith + the standalone

    The clientbound side names NO client class -- it drains into CsLodChannel's static sink, which is
    null unless the Dist.CLIENT entrypoint installed it. That is what keeps lod.client.* off a
    dedicated server.
    """
    # The serverbound branch, minus its closing brace -- the two forms close it differently.
    server_body = [
        "        (payload, context) -> context.enqueueWork(() -> {",
        "            if (context.player() instanceof final ServerPlayer player) {",
        "                CsLodServerNet.receive(player, payload.data());",
    ]
    if _parse(mcver) >= (1, 21, 11):
        # 4-arg: serverbound FIRST, then clientbound. Both slots named on both sides.
        return [
            "registrar.playBidirectional(Payload.TYPE, Payload.CODEC,",
        ] + server_body + [
            "            }",
            "        }),",
            "        (payload, context) -> context.enqueueWork(() -> dispatchClient(payload.data())));",
        ]
    # 21.1: the 3-arg form is the ONLY form. One handler serves both directions -- branch on the player.
    return [
        "registrar.playBidirectional(Payload.TYPE, Payload.CODEC,",
    ] + server_body + [
        "            } else {",
        "                dispatchClient(payload.data());",
        "            }",
        "        }));",
    ]


def voxy_jar(mcver):
    """The voxy soft-dep jar for this MC line, in libs/. Only called where has_voxy() is true."""
    v = _parse(mcver)
    if v[0] >= 26:
        return "voxy-0.2.16-beta+26.1.2.jar"
    # The 1.21.11 cell consumes the -loomcompat copy produced by scripts/prep-libs.py; see voxy_needs_remap.
    return "voxy-0.2.16-beta+1.21.11-loomcompat.jar"


def voxy_needs_remap(mcver):
    """Must the voxy jar be taken as a modCompileOnly (loom remaps it) rather than a plain compileOnly?

    YES below 26. The PUBLISHED voxy 1.21.11 jar is INTERMEDIARY-mapped
    (WorldIdentifier.of(net.minecraft.class_1937), rawIngest(..., class_2826, ..., class_2804, class_2804));
    the 26.1.2 jar is mojmap-native because the 26 line ships unobfuscated. Read out of both jars with
    javap, 2026-07-12. A plain compileOnly of the 1.21.11 jar does not compile at all -- the adapter's
    mojmap parameter types do not match voxy's intermediary ones.
    """
    return _parse(mcver)[0] < 26


# ---------------------------------------------------------------------------
# CsLodSectionBuilder drift -- the file MC churn hits hardest (stored record -> vanilla objects).
#
# The 1.21.11 boundary moved the WHOLE paletted-container construction path and renamed every registry
# accessor it leans on. [version-gates.md, 1.21.11 entry, src: mc-java 1.21.1 vs 1.21.11 --
# LevelChunkSection.java / PalettedContainer.java / PalettedContainerFactory.java / RegistryAccess.java /
# Registry.java / HolderGetter.java / BlockStateParser.java]:
#     < 1.21.11 : new PalettedContainer<>(IdMap, T, PalettedContainer.Strategy.SECTION_STATES)
#     >=1.21.11 : PalettedContainerFactory.create(registryAccess).createForBlockStates()/createForBiomes()
#                 (PalettedContainer.Strategy is GONE -- Strategy moved top-level, SECTION_* deleted)
#     RegistryAccess.registryOrThrow -> lookupOrThrow
#     Registry.getHolder(ResourceKey) -> get(ResourceKey)         (both Optional<Holder.Reference<T>>)
#     Registry.getHolderOrThrow      -> getOrThrow                (SILENT RETURN-TYPE FLIP: pre-1.21.11
#                                                                  getOrThrow returns T, not a Holder --
#                                                                  a naive rename compiles and is wrong)
#     BlockStateParser.parseForBlock takes a HolderLookup<Block> on EVERY version, but a Registry only IS
#     one from 1.21.11; before that vanilla passes .asLookup().
# The 2-arg LevelChunkSection(states, biomes) ctor is the ONE thing stable 1.20.1 -> 26, so every era is
# funnelled into it and only the two container locals are emitted.
# ---------------------------------------------------------------------------

def palette_factory(mcver):
    """True from 1.21.11 (incl. 26): PalettedContainerFactory exists and Strategy.SECTION_* does not."""
    v = _parse(mcver)
    if v[0] >= 26:
        return True
    return v >= (1, 21, 11)


def parse_id_expr(mcver, arg):
    """Parse a one-part 'ns:path' string into a resource id.
      < 1.21    : new ResourceLocation(s)          -- the String ctor; ResourceLocation.parse does not exist
      1.21..1.21.10 : ResourceLocation.parse(s)
      >= 1.21.11    : Identifier.parse(s)          -- the class was renamed at 1.21.11
    """
    v = _parse(mcver)
    if palette_factory(mcver):
        return "Identifier.parse(%s)" % arg
    if v >= (1, 21, 0):
        return "ResourceLocation.parse(%s)" % arg
    return "new ResourceLocation(%s)" % arg


def section_builder_imports(mcver):
    """The drifting imports of CsLodSectionBuilder, as a list (order-stable)."""
    lines = [identifier_import(mcver)]
    if palette_factory(mcver):
        lines.append("import net.minecraft.world.level.chunk.PalettedContainerFactory;")
    else:
        # Only the legacy era hand-builds the containers, so only it names the block-state IdMap
        # (Block.BLOCK_STATE_REGISTRY) and the default biome (Biomes.PLAINS). Importing either on
        # 1.21.11+ would compile, but they would be dead imports naming a path we do not use.
        lines.append("import net.minecraft.world.level.biome.Biomes;")
        lines.append("import net.minecraft.world.level.block.Block;")
    return lines


def palette_containers(mcver):
    """The `states` and `biomes` locals that the 2-arg LevelChunkSection ctor takes."""
    if palette_factory(mcver):
        return [
            "final PalettedContainerFactory factory = "
            "PalettedContainerFactory.create(level.registryAccess());",
            "final PalettedContainer<BlockState> states = factory.createForBlockStates();",
            "final PalettedContainer<Holder<Biome>> biomes = factory.createForBiomes();",
        ]
    return [
        "final Registry<Biome> biomeRegistry = "
        "level.registryAccess().registryOrThrow(Registries.BIOME);",
        "final PalettedContainer<BlockState> states = new PalettedContainer<>("
        "Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), "
        "PalettedContainer.Strategy.SECTION_STATES);",
        "final PalettedContainer<Holder<Biome>> biomes = new PalettedContainer<>("
        "biomeRegistry.asHolderIdMap(), biomeRegistry.getHolderOrThrow(Biomes.PLAINS), "
        "PalettedContainer.Strategy.SECTION_BIOMES);",
    ]


def block_lookup_expr(mcver):
    """BlockStateParser.parseForBlock's first argument: a Registry only IS a HolderLookup from 1.21.11."""
    if palette_factory(mcver):
        return ("return BlockStateParser.parseForBlock("
                "level.registryAccess().lookupOrThrow(Registries.BLOCK), key, false).blockState();")
    return ("return BlockStateParser.parseForBlock("
            "level.registryAccess().registryOrThrow(Registries.BLOCK).asLookup(), key, false)"
            ".blockState();")


def biome_lookup_body(mcver):
    """Biome id string -> Holder<Biome>, with plains as the fallback for an id we no longer know."""
    if palette_factory(mcver):
        return [
            "final Registry<Biome> registry = level.registryAccess().lookupOrThrow(Registries.BIOME);",
            "return registry.get(ResourceKey.create(Registries.BIOME, %s))" % parse_id_expr(mcver, "key"),
            "        .orElseGet(() -> registry.get(ResourceKey.create(Registries.BIOME, %s))"
            ".orElseThrow());" % parse_id_expr(mcver, '"minecraft:plains"'),
        ]
    return [
        "final Registry<Biome> registry = level.registryAccess().registryOrThrow(Registries.BIOME);",
        "return registry.getHolder(ResourceKey.create(Registries.BIOME, %s))"
        % parse_id_expr(mcver, "key"),
        "        .orElseGet(() -> registry.getHolderOrThrow("
        "ResourceKey.create(Registries.BIOME, %s)));" % parse_id_expr(mcver, '"minecraft:plains"'),
    ]


def _is_forge_ancient(mcver, loader):
    return loader == "Forge" and _parse(mcver) < (1, 21, 0)


def deprecation_suppression(mcver, loader):
    """Forge 47 marks the VANILLA registry fields (Block.BLOCK_STATE_REGISTRY and friends) @Deprecated --
    it wants you on ForgeRegistries. They still work, and there is NO non-deprecated way to reach the
    block-state IdMap that the pre-1.21.11 PalettedContainer ctor demands. Vanilla and Fabric do not
    deprecate them (the identical call compiles warning-free on Fabric/1.20.1), so this is a Forge-only,
    one-method suppression. [version-gates.md, MC 1.20.1 / Forge 47 lint traps]"""
    return '@SuppressWarnings("deprecation")' if _is_forge_ancient(mcver, loader) else ""


def removal_suppression(mcver, loader):
    """Forge 47 PATCHES new ResourceLocation(..) to @Deprecated(forRemoval = true); vanilla/Fabric 1.20.1
    does NOT, and MC 1.20.1 has no non-nullable replacement (fromNamespaceAndPath arrives at MC 1.21).
    Under -Xlint:all + zero warnings that fails the Forge/1.20.1 cell and ONLY that cell. The lint
    category is 'removal', NOT 'deprecation' -- suppressing "deprecation" does nothing here.
    [version-gates.md, MC 1.20.1 / Forge 47 lint traps]"""
    if loader == "Forge" and not palette_factory(mcver) and _parse(mcver) < (1, 21, 0):
        return '@SuppressWarnings("removal")'
    return ""


def lod_net_era(mcver, loader):
    """Which in-band custom-payload implementation CsLodChannel emits.

    'fabric_legacy'        Fabric < 1.20.2 -- CustomPacketPayload does not exist yet, so the channel is
                           the raw (ResourceLocation, FriendlyByteBuf) form:
                           ServerPlayNetworking.registerGlobalReceiver(id, PlayChannelHandler) and
                           ServerPlayNetworking.send(player, id, buf) with PacketByteBufs.create().
    'fabric_payload'       Fabric >= 1.20.5 -- PayloadTypeRegistry + StreamCodec + Type<>.
    'neoforge_payload'     NeoForge >= 1.21 -- RegisterPayloadHandlersEvent + PayloadRegistrar on the
                           MOD bus, PacketDistributor.sendToPlayer to send.
    'forge_simplechannel'  Forge 1.20.1 (Forge 47) -- NetworkRegistry.newSimpleChannel + messageBuilder.
    """
    v = _parse(mcver)
    if loader == "Fabric":
        return "fabric_legacy" if v[:3] < (1, 20, 2) else "fabric_payload"
    if loader == "NeoForge":
        return "neoforge_payload"
    if loader == "Forge":
        return "forge_simplechannel"
    raise NotImplementedError("lod_net_era: unknown loader %s" % loader)


def fabric_payload_registry_call(mcver, direction):
    """Fabric API `PayloadTypeRegistry` static accessor name.

    DISCOVERED 2026-07-12 (chunksmith 3.0.0-beta-1 LOD port). The fabric-networking-api-v1 module
    RENAMED both accessors between module v5.1.x and v6.3.x -- i.e. at the MC 26 fabric-api line:
        playC2S()  -> serverboundPlay()
        playS2C()  -> clientboundPlay()
    VERIFIED by javap of the cached module jars: 4.x (MC 1.21.1) and 5.1.x (MC 1.21.11) expose
    playC2S/playS2C/configurationC2S/configurationS2C; 6.3.x (MC 26.x) exposes
    serverboundPlay/clientboundPlay/serverboundConfiguration/clientboundConfiguration.
    Everything ELSE in the payload path is stable across 4.x/5.x/6.x -- ServerPlayNetworking
    .registerGlobalReceiver(Type, handler), .send(player, payload), and Context.server()/.player()
    are byte-for-byte the same signatures -- so this ONE pair of names is the whole drift.

    This is a fabric-API axis, not an MC axis: it happens to coincide with MC 26 only because that is
    when fabric-api cut the new module major.
    """
    if _parse(mcver)[0] >= 26:
        return "serverboundPlay" if direction == "serverbound" else "clientboundPlay"
    return "playC2S" if direction == "serverbound" else "playS2C"


def make_id_expr(mcver, ns_expr, path_expr):
    """Construct a resource id. `ResourceLocation(String,String)` was PRIVATIZED at 1.21 in favour of
    the `fromNamespaceAndPath` factory, and the class itself was renamed `Identifier` at 1.21.11.
    So three forms:
      < 1.21    : new ResourceLocation(ns, path)
      1.21..1.21.10 : ResourceLocation.fromNamespaceAndPath(ns, path)
      >= 1.21.11    : Identifier.fromNamespaceAndPath(ns, path)
    """
    v = _parse(mcver)
    if v[0] == 1 and v[:2] < (1, 21):
        return "new ResourceLocation(%s, %s)" % (ns_expr, path_expr)
    return "%s.fromNamespaceAndPath(%s, %s)" % (identifier_type(mcver), ns_expr, path_expr)


def profile_name_call(mcver):
    """authlib GameProfile display-name accessor: getName() (<=1.21.8) vs the record-style name()
    (1.21.9+, inherited by 1.21.11 and 26). [version-gates.md: '1.21.9 - GameProfile.name() accessor']"""
    v = _parse(mcver)
    if v[0] >= 26:
        return "name"
    return "name" if v >= (1, 21, 9) else "getName"


def client_chat_statement(mcver, player_expr, component_expr):
    """One line of chat, printed to the LOCAL player. Two eras, cut exactly at 1.21.11 -> 26.1.

    MC 26 SPLIT Player.displayClientMessage(Component, boolean) into sendSystemMessage(Component) and
    sendOverlayMessage(Component) -- same slot in the class, immediately after getSleepTimer():
      26.2  net/minecraft/world/entity/player/Player.java:1357  public void sendSystemMessage(Component)
      26.2  net/minecraft/world/entity/player/Player.java:1360  public void sendOverlayMessage(Component)
      1.21.11 net/minecraft/world/entity/player/Player.java:1319 public void displayClientMessage(Component, boolean)
    LocalPlayer overrides it at 26.2 net/minecraft/client/player/LocalPlayer.java:439, routing through
    minecraft.gui.chatListener().handleSystemMessage(message, true) -- the correct vanilla path for a
    client-generated chat line, so this is behaviourally right and not merely compilable.

    There is NO call that spans both eras, and the obvious dodges are worse:
      - sendSystemMessage is the MIRROR bug: Entity dropped CommandSource at 1.21.2, so it exists on
        1.20.1/1.21.1 (Entity.java:2765 / :2859) and NOT on 1.21.11.
      - the ChatComponent chain needs THREE variants, not two: gui.getChat().addMessage (<=1.21.11),
        gui.getChat().addClientSystemMessage (26.1, addMessage went private, ChatComponent.java:269),
        gui.hud.getChat().addClientSystemMessage (26.2+, Gui.getChat() moved to Gui.hud, Hud.java:1264).

    Discovered 2026-08-12 building the 3.3.0 /cslod set client reply on Fabric/26.
    """
    if _parse(mcver)[0] >= 26:
        return "%s.sendSystemMessage(%s);" % (player_expr, component_expr)
    return "%s.displayClientMessage(%s, false);" % (player_expr, component_expr)


def command_permission_gate(mcver, source_expr):
    """The /cslod root permission gate, keyed on a CommandSourceStack expression. Three eras:
      26+      : Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)  -- a Predicate, so it REPLACES the
                 whole .requires(..) argument rather than testing a source (see lod_requires_expr).
      1.21.11  : source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
      < 1.21.11: source.hasPermission(2)
    """
    if _parse(mcver)[0] >= 26:
        return "Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)"
    if era(mcver) == "modern_11plus":
        return "%s -> %s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)" % (source_expr, source_expr)
    return "%s -> %s.hasPermission(2)" % (source_expr, source_expr)


def chunk_min_section_call(mcver):
    """LevelHeightAccessor bottom-section-index accessor: getMinSection() (<1.21.2) vs getMinSectionY()
    (1.21.2+, incl. 26).

    DISCOVERED 2026-07-12 porting the LOD extractor (chunksmith 3.0.0-beta-1). Boundary PINNED from the
    MC source diff (mc-java diffs/family-1.21/1.21.1_vs_1.21.2.diff L64064-65):
        - for (int $$6 = this.level.getMinSection(); $$6 < this.level.getMaxSection(); $$6++)
        + for (int $$6 = this.level.getMinSectionY(); $$6 <= this.level.getMaxSectionY(); $$6++)
    Compile-verified both sides: Fabric 1.20.1 + 1.21.1 (getMinSection), Fabric 1.21.11 + 26
    (getMinSectionY).

    CAUTION for any future caller: the MAX accessor changed SEMANTICS in the same rename --
    getMaxSection() was EXCLUSIVE, getMaxSectionY() is INCLUSIVE (note the `<` -> `<=` above). The LOD
    extractor only uses the MIN accessor, so it is unaffected, but a naive rename of a max call is an
    off-by-one.
    """
    v = _parse(mcver)
    if v[0] >= 26:
        return "getMinSectionY"
    return "getMinSectionY" if v >= (1, 21, 2) else "getMinSection"


def chunk_result_success_block(mcver, result_var, chunk_var, body):
    """The 'if the chunk future succeeded, hand me the ChunkAccess' idiom in the World LOD hook.

    transitional+ : ChunkResult<ChunkAccess>            -> result.ifSuccess(c -> ...)
    ancient       : Either<ChunkAccess, ChunkLoadingFailure> -> result.left().ifPresent(c -> ...)
    (Same boundary as chunk_future_result_type: ChunkResult replaced the Either result at 1.20.5.)
    """
    if era(mcver) == "ancient":
        return "%s.left().ifPresent(%s -> { %s });" % (result_var, chunk_var, body)
    return "%s.ifSuccess(%s -> { %s });" % (result_var, chunk_var, body)
