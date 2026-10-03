#!/usr/bin/env python3
"""
Asks Jev (typesafe/jev-latest, OpenRouter's Decisions API) the same questions
about every Kotlin source file, and ranks the files by what it answers.

Run it yourself, with your own key in the environment -- never in a file in
the repository:

    export OPENROUTER_API_KEY=sk-or-...
    python3 tools/jev_audit.py                # every src/main Kotlin file
    python3 tools/jev_audit.py engine/scene   # only under these paths

It writes jev_audit.json (every answer) and prints the files most likely to
need a look. Jev is a classifier: treat what it flags as places to read, not
as findings.
"""
import json
import os
import subprocess
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ENDPOINT = "https://openrouter.ai/api/alpha/decisions"
MODEL = os.environ.get("JEV_MODEL", "~typesafe/jev-latest")
# Jev reads up to about 32k tokens of state; keep well under it.
MAX_CHARS = 90_000

QUESTIONS = {
    "main_thread_load": {
        "type": "score",
        "instructions": "This is a Kotlin source file from an Android voxel game. How much CPU-heavy work "
                        "(meshing, world scans, large loops, generation) does it do on whatever thread calls it, "
                        "with no hand-off to a worker thread or coroutine dispatcher?",
        "criteria": [
            "None: cheap bookkeeping or UI only",
            "Some: bounded loops over a handful of items",
            "Heavy: scans chunks, meshes, or loops over thousands of cells per call",
        ],
    },
    "unsafe_sharing": {
        "type": "noul",
        "instructions": "Does this file share mutable state between threads (executors, coroutines on other "
                        "dispatchers, worker threads) without synchronisation, immutable snapshots, atomics or "
                        "thread confinement?",
        "criteria": {
            "true": "Mutable state is read or written from more than one thread without protection.",
            "false": "Everything is confined to one thread, immutable, snapshotted, or properly synchronised.",
        },
    },
    "parallelisable": {
        "type": "noul",
        "instructions": "Does this file contain CPU-heavy work over independent items (chunks, columns, "
                        "entities, files) that runs sequentially but could run on several worker threads?",
        "criteria": {
            "true": "There is independent heavy work done one item after another.",
            "false": "Work is light, already parallel, or each step depends on the last.",
        },
    },
    "complexity": {
        "type": "score",
        "instructions": "How hard is this file to change safely?",
        "criteria": ["Simple", "Moderate", "Complex", "Very complex"],
    },
    "area": {
        "type": "choice",
        "instructions": "Which part of the game is this file mostly about?",
        "criteria": {
            "rendering": "Meshing, scene building, GPU, shaders, sprites, cameras.",
            "simulation": "World session, ticking, combat, movement, enemies, physics.",
            "worldgen": "Terrain, biomes, geology, structures, voxel generation.",
            "content": "Content packs, data definitions, validation, schemas.",
            "ui": "Compose screens, view models, navigation.",
            "ai": "AI agents, prompts, model providers, generation jobs.",
            "persistence": "Saving, loading, storage, files.",
            "other": "Anything else.",
        },
    },
}


def sources(paths):
    files = subprocess.run(["git", "ls-files", "*.kt"], capture_output=True, text=True, check=True).stdout.split()
    files = [f for f in files if "/src/main/" in f and not f.startswith(".claude/")]
    if paths:
        files = [f for f in files if any(f.startswith(p.rstrip("/")) for p in paths)]
    return files


def ask(key, path):
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    if len(text) > MAX_CHARS:
        text = text[:MAX_CHARS] + "\n// ... truncated ..."
    body = json.dumps({"model": MODEL, "state": {"path": path, "source": text}, "questions": QUESTIONS}).encode()
    req = urllib.request.Request(ENDPOINT, data=body, headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return path, json.load(resp)
    except Exception as e:  # keep going; report at the end
        return path, {"error": str(e)}


def main():
    key = os.environ.get("OPENROUTER_API_KEY")
    if not key:
        sys.exit("Set OPENROUTER_API_KEY first.")
    files = sources(sys.argv[1:])
    print(f"Asking Jev about {len(files)} files...", file=sys.stderr)
    with ThreadPoolExecutor(max_workers=6) as pool:
        results = dict(pool.map(lambda f: ask(key, f), files))
    with open("jev_audit.json", "w") as fh:
        json.dump(results, fh, indent=1)

    rows, cost, failed = [], 0.0, []
    for path, r in results.items():
        if "answers" not in r:
            failed.append((path, r.get("error", r)))
            continue
        a = r["answers"]
        cost += r.get("usage", {}).get("cost", 0) or 0
        rows.append({
            "path": path,
            "load": a["main_thread_load"]["score"],
            "unsafe": a["unsafe_sharing"]["noul"],
            "parallel": a["parallelisable"]["noul"],
            "complexity": a["complexity"]["score"],
            "area": a["area"]["choice"],
        })

    def show(title, key, n=15):
        print(f"\n== {title} ==")
        for row in sorted(rows, key=lambda r: -r[key])[:n]:
            print(f"{row[key]:5.2f}  {row['area']:<11} {row['path']}")

    show("Heavy work on the calling thread (0-2)", "load")
    show("Possibly unsafe sharing between threads (probability)", "unsafe")
    show("Sequential work that could run in parallel (probability)", "parallel")
    show("Hardest to change (0-3)", "complexity", 10)
    print(f"\n{len(rows)} files answered, {len(failed)} failed, cost ${cost:.4f}. Full answers in jev_audit.json.")
    for path, err in failed[:10]:
        print(f"  failed: {path}: {err}", file=sys.stderr)


if __name__ == "__main__":
    main()
