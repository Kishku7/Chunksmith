"""Regression gate for mod_support #17 -- the paused-tick ticket drain.

WHAT THIS PROVES (read this before trusting a green run):
  * the cog SOURCE for IntegratedServerMixin drains the ticket safe point on the paused path;
  * every generated per-cell copy carries that call;
  * every cell's chunksmith.mixins.json actually REGISTERS client.IntegratedServerMixin, so the
    mixin is applied rather than merely present on disk.

WHAT IT DOES NOT PROVE:
  * that a paused pre-gen actually advances. That is a RUNTIME behaviour on a client, and only an
    in-game gate can assert it: start a pre-gen, open the menu, wait, and confirm the completed
    chunk count MOVED. This script cannot see that, and a green result here must never be read as
    "the pause bug is fixed".

Background: 3.3.0 routed all chunk-ticket work through a queue drained only from
MinecraftServer.tickServer HEAD. IntegratedServer.tickServer calls tickPaused() and returns without
calling super when paused, so on a paused single-player world nothing drained the queue, no chunk
got a ticket, and the pre-gen sat at zero -- silently, with no exception to notice.

Exit code is non-zero if anything is missing, so it can gate a build.
"""
import io
import json
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

COG_SOURCE = os.path.join(
    REPO, "_codegen", "cog_sources", "shared", "com", "kishku7", "chunksmith",
    "mixin", "client", "IntegratedServerMixin.java")

DRAIN_CALL = "chunksmith$drainTicketSafePointNow()"
HOUSEKEEPING_CALL = "chunksmith$runChunkSystemHousekeeping("
MIXIN_ENTRY = "client.IntegratedServerMixin"

GEN_REL = os.path.join(
    "gen", "src", "main", "java", "com", "kishku7", "chunksmith",
    "mixin", "client", "IntegratedServerMixin.java")
MIXINS_REL = os.path.join("src", "main", "resources", "chunksmith.mixins.json")


def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as fh:
        return fh.read()


def cells():
    for loader in ("Fabric", "Forge", "NeoForge"):
        root = os.path.join(REPO, loader)
        if not os.path.isdir(root):
            continue
        for name in sorted(os.listdir(root)):
            if os.path.isdir(os.path.join(root, name)):
                yield loader, name


def main():
    problems = []

    # 1. The source of truth.
    if not os.path.isfile(COG_SOURCE):
        problems.append("cog source missing: %s" % COG_SOURCE)
    else:
        src = read(COG_SOURCE)
        if DRAIN_CALL not in src:
            problems.append(
                "cog source does NOT drain the safe point on the paused path -- this is the "
                "mod_support #17 regression itself: %s" % COG_SOURCE)
        elif HOUSEKEEPING_CALL in src:
            # Order matters: tickets must be applied before housekeeping flushes the distance
            # manager, or every chunk costs an extra tick on the one path where throughput is
            # the whole point.
            if src.index(DRAIN_CALL) > src.index(HOUSEKEEPING_CALL):
                problems.append(
                    "cog source drains the safe point AFTER housekeeping; the drain must come "
                    "first so the flush sees this tick's tickets")

    # 2. Every generated cell copy, and its registration.
    checked = 0
    for loader, cell in cells():
        base = os.path.join(REPO, loader, cell)
        gen = os.path.join(base, GEN_REL)
        mixins = os.path.join(base, MIXINS_REL)

        if not os.path.isfile(gen):
            # Not every cell has been cog-generated in this working tree; only judge what exists.
            continue
        checked += 1
        if DRAIN_CALL not in read(gen):
            problems.append("%s/%s: generated IntegratedServerMixin has no drain call" % (loader, cell))

        if not os.path.isfile(mixins):
            problems.append("%s/%s: no chunksmith.mixins.json" % (loader, cell))
            continue
        try:
            data = json.loads(read(mixins))
        except ValueError as e:
            problems.append("%s/%s: chunksmith.mixins.json is not valid JSON (%s)" % (loader, cell, e))
            continue
        client = data.get("client") or []
        if MIXIN_ENTRY not in client:
            problems.append(
                "%s/%s: chunksmith.mixins.json does not register %s -- the mixin exists on disk "
                "but is never applied" % (loader, cell, MIXIN_ENTRY))

    if checked == 0:
        problems.append("no generated cells found -- run a build (or cog-gen) first; this check "
                        "proved nothing")

    if problems:
        print("FAIL -- paused-tick drain gate (mod_support #17)")
        for p in problems:
            print("  * " + p)
        return 1

    print("OK -- paused-tick drain wired in the cog source and in %d generated cell(s)." % checked)
    print("NOTE: this proves the WIRING only. That a paused pre-gen actually advances still needs "
          "an in-game gate (start a run, open the menu, confirm the chunk count MOVES).")
    return 0


sys.exit(main())
