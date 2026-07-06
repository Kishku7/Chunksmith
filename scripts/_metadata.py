"""ChunkSmith metadata single-source: the canonical issue-tracker URL, stamped into every manifest.
This is THE one place the URL lives. `stamp` writes it into all cell manifests (Fabric contact.issues,
Forge/NeoForge issueTrackerURL). `check` fails (exit 1) if any manifest disagrees -- run it in the audit
and before publish so the URL can never drift again.
Usage: python scripts/_metadata.py stamp   |   python scripts/_metadata.py check
"""
import sys, os, re, json, glob

CANON_ISSUES = "https://github.com/Kishku7/mod_support/issues"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODE = sys.argv[1] if len(sys.argv) > 1 else "check"

def manifests():
    out = []
    for loader in ("Fabric", "NeoForge", "Forge"):
        out += glob.glob(os.path.join(ROOT, loader, "*", "src", "main", "resources", "**", "*.toml"), recursive=True)
        out += glob.glob(os.path.join(ROOT, loader, "*", "src", "main", "resources", "fabric.mod.json"), recursive=True)
    return out

bad = []; stamped = 0
for m in manifests():
    t = open(m, encoding="utf-8").read()
    if m.endswith(".json"):
        j = json.loads(t)
        cur = (j.get("contact") or {}).get("issues")
        if cur != CANON_ISSUES:
            if MODE == "stamp":
                j.setdefault("contact", {})["issues"] = CANON_ISSUES
                open(m, "w", encoding="utf-8").write(json.dumps(j, indent=2) + "\n"); stamped += 1
            else: bad.append((m, cur))
    else:  # mods.toml
        mm = re.search(r'issueTrackerURL\s*=\s*"([^"]*)"', t)
        cur = mm.group(1) if mm else None
        if cur != CANON_ISSUES:
            if MODE == "stamp":
                if mm: t = t[:mm.start()] + f'issueTrackerURL="{CANON_ISSUES}"' + t[mm.end():]
                else:  # insert after the license line
                    t = re.sub(r'(license\s*=\s*"[^"]*"\s*\n)', r'\1issueTrackerURL="' + CANON_ISSUES + '"\n', t, count=1)
                open(m, "w", encoding="utf-8").write(t); stamped += 1
            else: bad.append((m, cur))

if MODE == "stamp":
    print(f"stamped {stamped} manifest(s) to {CANON_ISSUES}")
else:
    if bad:
        print(f"METADATA CHECK FAILED -- {len(bad)} manifest(s) not at canonical issue URL:")
        for m, c in bad: print(f"  {os.path.relpath(m, ROOT)}: {c}")
        sys.exit(1)
    print(f"metadata OK: all {len(manifests())} manifests at {CANON_ISSUES}")
