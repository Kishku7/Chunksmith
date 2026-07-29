"""D16 hash oracle for the platform adapters.

Proves that what `_codegen/compat_platform.py` materialises into a cell's gen/ is byte-identical
(modulo line endings, which git rewrites on checkout anyway) to what that cell shipped before the
adapters were single-sourced.

Two modes:
  * default          -- compare compat_platform's output against the GENERATED files in each
                        cell's gen/ tree (run after cog-gen; this is the regression check).
  * --against-git R  -- compare against the committed pre-migration copies at git ref R, i.e. the
                        one-time migration proof. R defaults to the commit before the migration.

Exit code is non-zero if anything mismatches, so it can gate a build.
"""
import os, sys, io, glob, subprocess

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(REPO, "_codegen"))
import compat_platform  # noqa: E402

PKG = "src/main/java/com/kishku7/chunksmith/platform"


def norm(s):
    return s.replace("\r\n", "\n").replace("\r", "\n")


def cells():
    for loader in ("Fabric", "Forge", "NeoForge"):
        root = os.path.join(REPO, loader)
        if not os.path.isdir(root):
            continue
        for d in sorted(os.listdir(root)):
            if os.path.isdir(os.path.join(root, d)):
                yield loader, d


def check_gen():
    bad, n = [], 0
    for loader, cell in cells():
        want = compat_platform.platform_classes(loader, cell)
        if not want:
            bad.append(("%s/%s" % (loader, cell), "-", "NOT IN CELL_MAP"))
            continue
        for cls, txt in want.items():
            p = os.path.join(REPO, loader, cell, "gen", PKG, cls + ".java")
            n += 1
            if not os.path.exists(p):
                bad.append(("%s/%s" % (loader, cell), cls, "MISSING in gen/ (run cog-gen)"))
            elif norm(io.open(p, encoding="utf-8").read()) != norm(txt):
                bad.append(("%s/%s" % (loader, cell), cls, "DIFFERS from compat_platform"))
    return n, bad


def check_git(ref):
    bad, n = [], 0
    for loader, cell in cells():
        for cls, txt in compat_platform.platform_classes(loader, cell).items():
            rel = "%s/%s/%s/%s.java" % (loader, cell, PKG, cls)
            r = subprocess.run(["git", "-C", REPO, "show", "%s:%s" % (ref, rel)],
                               capture_output=True, text=True)
            n += 1
            if r.returncode != 0:
                bad.append((rel, "-", "absent at %s" % ref))
            elif norm(r.stdout) != norm(txt):
                bad.append((rel, "-", "DIFFERS from pre-migration copy"))
    return n, bad


if __name__ == "__main__":
    if "--against-git" in sys.argv:
        ref = sys.argv[sys.argv.index("--against-git") + 1]
        n, bad = check_git(ref)
        what = "pre-migration copies at %s" % ref
    else:
        n, bad = check_gen()
        what = "generated gen/ trees"
    print("platform hash oracle: %d file(s) checked against %s" % (n, what))
    for b in bad:
        print("  MISMATCH %s %s -- %s" % b)
    if bad:
        print("FAIL: %d mismatch(es)" % len(bad))
        sys.exit(1)
    print("OK: byte-identical (line endings normalised)")
