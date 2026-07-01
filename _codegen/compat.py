"""ChunkSmith cross-version "era brain" for Cog code generation.

Given an MC version string (mcver), this module returns the correct per-era code
fragments for each mixin/accessor drift point identified in the drift matrix
(Temp/chunksmith-drift-matrix.md). Cog source files import this module and call
its helpers inside //[[[cog ... //]]] blocks so ONE shared_minecraft source can be
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

For the current task only modern_pre11 (1.21.8) and modern_11plus (26) are fully
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
    raise NotImplementedError(
        "compat.%s not implemented for era '%s' (mcver=%s); "
        "only modern_pre11 (1.21.8) and modern_11plus (26) are wired for this task."
        % (name, era(mcver), mcver)
    )


# ---------------------------------------------------------------------------
# Drift point helpers. Each returns a plain string that Cog inserts verbatim.
# Prefix is uniformly chunksmith$ (shared_minecraft is already namespace-normalized).
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

    26-only invoker (added with the tickConnection housekeeping hook). Absent on
    the 1.21.x / 1.20.x lines.
    """
    v = _parse(mcver)
    return v[0] >= 26


def has_minecraft_server_access(mcver):
    """Is the MinecraftServerAccess mixin present? 26-only seam accessor."""
    v = _parse(mcver)
    return v[0] >= 26


def has_chunk_storage_accessor(mcver):
    """Is ChunkStorageAccessor (@Mixin ChunkStorage) present?

    Present 1.20.1 .. 1.21.10; GONE at 1.21.11 and 26 (ChunkStorage removed;
    ChunkMap extends SimpleRegionStorage).
    """
    return era(mcver) != "modern_11plus"


def empty_ticks_reset(mcver):
    """MinecraftServerMixin keep-awake reset of the idle counter.

    1.21.8 (modern_pre11): direct @Shadow field 'this.emptyTicks = 0;'
    26 (modern_11plus): via seam accessor '((MinecraftServerAccess) (Object) this).setEmptyTicks(0);'
    (26 routes through the accessor because the shadow-field form was flaky under
    its mixin/AT setup; both are equivalent.)
    """
    e = era(mcver)
    if _parse(mcver)[0] >= 26:
        # 26 routes through the MinecraftServerAccess seam accessor (26-only mixin).
        return "((MinecraftServerAccess) (Object) this).setEmptyTicks(0);"
    if e in ("modern_pre11", "transitional", "modern_11plus"):
        # pre-26 (incl 1.21.11): zero the @Shadow emptyTicks field directly. MinecraftServerAccess
        # does NOT exist on 1.21.11, so the accessor form is 26-only.
        return "this.emptyTicks = 0;"
    if e == "ancient":
        # 1.20.1/1.20.4: the empty-server pause + emptyTicks field do NOT exist -> no-op.
        return "// no idle-pause on this MC version (emptyTicks absent)"
    return _stub("empty_ticks_reset", mcver)


def housekeeping_inject_at(mcver):
    """MinecraftServerMixin: where the chunk-system housekeeping @Inject binds.

    The tickConnection()V hook is a 26-only addition (matrix section 2h / era 4
    additions). 1.21.11 is otherwise modern_11plus but KEEPS the older TAIL form,
    so this is keyed on the 26-only marker (major >= 26), NOT on the era name.
      26+ : at INVOKE tickConnection()V (mid-tick).
      else: at TAIL of tickServer.
    """
    if _parse(mcver)[0] >= 26:
        return 'at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;tickConnection()V")'
    return 'at = @At("TAIL")'


def needs_empty_ticks_shadow(mcver):
    """Does MinecraftServerMixin need a @Shadow private int emptyTicks; field?

    Needed whenever empty_ticks_reset() references the shadow field directly: every pre-26
    non-ancient line, INCLUDING 1.21.11 (modern_11plus but pre-26, so no MinecraftServerAccess).
    26 uses the accessor; ancient (1.20.1/1.20.4) has no such field.
    """
    if _parse(mcver)[0] >= 26:
        return False
    return era(mcver) in ("modern_pre11", "transitional", "modern_11plus")


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
# These two shapes were byte-IDENTICAL from 1.21.4 through 26, so the shared_minecraft
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
