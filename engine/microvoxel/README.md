# `:engine:microvoxel` — microvoxel worlds

Quarter-block voxels for an infinite, editable, generated world that a low-end
Android phone can run. Pure Kotlin, no dependencies, no Android.

One block of the block world is **4 × 4 × 4 microvoxels**. A chunk is
**64 × 64 × 64 microvoxels** (16 blocks a side). The world is chunked
vertically too, so height is as unbounded as distance.

```
              ┌──────────────── gen/ ─────────────────┐
 StageSpec[] ─▶ StageRegistry ─▶ MicroGenerator ──────┼─▶ MicroChunk (brick map)
 (pack JSON)    (by id, open)    stages in order      │        │
              │  terrain → city_plan → roads →        │        ├─▶ BinaryGreedyMesher ─▶ QuadMesh (8 B/quad)
              │  buildings → groundcover → trees      │        ├─▶ Lod.downsample(2|4) ─▶ mesher (far rings)
              └───────────────────────────────────────┘        └─▶ MicroWorld (streaming + edit overlay)
```

| Package | What it is |
|---|---|
| `MicroChunk` | 64³ brick map: 8³ bricks, uniform bricks cost 2 bytes, detailed ones 1 byte/voxel (palette). |
| `MaterialPalette` | Materials by dense id: colour, opacity, emission, gloss, per-voxel jitter. |
| `MicroWorld` | Streams chunks around a focus, keeps player edits as a sparse overlay, saves = edits only. |
| `gen/` | The generator pipeline, the built-in stages and presets. |
| `mesh/` | Binary greedy mesher, packed quads, LOD downsampling, quality profiles. |

Screenshots are produced by `:tools:microvoxelpreview` (an offline ray tracer,
never shipped):

```
./gradlew :tools:microvoxelpreview:microPreview                       # 1280x720, 4 spp
./gradlew :tools:microvoxelpreview:microPreview --args="build/p 1234 fast"   # quick, other seed
./gradlew :tools:artpreview:microScenePreview                         # through the GAME's renderer + per-tier benchmark
```

---

## In the game: how a microvoxel world runs on a phone

The ARPG plays on **blocks**: collision, pathing, combat, AI, digging and
saves all run on the 16 × 16 × 48 block world, unchanged. Microvoxels are
where the world *comes from* and what the camera *sees up close*.

```
 pack JSON "terrain" ──▶ stratum:microvoxel ──▶ MicroGenerator (stages) ──▶ MicroChunks (cached, LRU)
 (hand-written or                │                                              │
  the studio's Surveyor)         ▼                                              ▼
                        block chunk = 4³ micro per block          MicroDetailMesher (worker thread)
                        (3/8 fill, topmost material,              near the camera: AO-greedy quads,
                         pack blocks by kind + colour,            edited blocks patched in as blocks
                         tree sprite at trunk foot)                     │
                                 │                                      ▼
                                 ▼                               SceneBuilder / ChunkMeshCache
                   WorldSession: collision, combat,              (block mesh until detail is ready,
                   spawns (markers), towns, saves                 then swap) ──▶ GLES renderer
```

* **The default world (`micro:ancient`)** — the packs' own land and towns in
  microvoxels. Terrain takes each region's surface, subsoil and rock blocks
  (the pack's red earth, turf, granite); flora is tropical (iroko with
  buttress roots, oil palms, baobabs, elephant grass); and **every pack town,
  the home town first, is built by `micro:settlements`** from the same
  `SettlementPlanner` plans the game uses (same seed, recipes, density,
  starting town and welcoming rule), so names, factions, doors and
  garrisons are unchanged. The `earthen` style draws them as Igbo
  compounds: plinths, rounded mud corners, uli zigzags in white nzu, timber
  doorways, lattice windows, coursed thatch with ragged eaves, round huts
  under conical thatch, compound walls, a sacred iroko in the square. The
  generator is then the world's town atlas, so the session no longer stamps
  towns over the land -- which is what lets the home area render in
  microvoxel detail. Pack blocks are materials of their own (`block:<id>`)
  and read back into exactly those blocks.
* **`:engine:microbridge`** — `MicrovoxelTerrainGenerator`, registered as
  `stratum:microvoxel`. Converts each block from the microvoxels it covers,
  maps materials onto *any* pack's blocks (so AI-made packs just work),
  picks biomes by climate, and places monster spawns in the wild, points of
  interest at town centres and caches in parks for the encounter system.
  The pack's own towns (settlements) are still laid over the land.
* **`MicroDetailMesher`** (`:engine:scene`) — draws chunks within
  `RenderSettings.microDetailRadius` of the camera from microvoxels:
  LOW 8 blocks, MEDIUM 20, HIGH 32, ULTRA 48, 0 = off. It runs on its own
  low-priority thread from a copied snapshot of the blocks, so a frame never
  waits: a chunk shows its block mesh until its detail is ready. Every block
  that differs from what was generated (dug, built, a town stamped on) is
  drawn as that block; a chunk changed past 35% falls back to blocks.
* **Configuring it** — a pack's `terrain` section names the generator and
  lists stages with string options (see `examples/plugins/microvoxel-realms`).
  The studio's **Surveyor** role writes exactly that, briefed from
  `MicroWorldgen.catalogue`; `DraftCheck` builds the generator to validate a
  draft, so an unknown stage or a bad option goes back to the model to fix.

### Measured (desktop JVM, 4 cores; `microScenePreview`)

| | blocks only | microvoxel detail |
|---|---|---|
| Generate a block chunk | 2.7 ms (pack default generator) | 4.3 ms |
| LOW: first frame / walk worst frame | 15.7 / 42.6 ms | 14.1 / 11.7 ms |
| LOW: terrain triangles / vertex memory | 11.5 k / 1.6 MB | 19.6 k / 2.7 MB |
| MEDIUM: walk worst frame | 8.4 ms | 7.9 ms |
| MEDIUM: triangles / vertex memory | 15.6 k / 2.2 MB | 51.6 k / 7.2 MB |
| HIGH: triangles / vertex memory | 17.0 k / 2.4 MB | 85.4 k / 12.0 MB |
| Detail catch-up after stopping | — | 2–50 ms |

Frame times are single-frame CPU scene building (meshing included), not GPU
time. A low-end phone core is roughly 3–6× slower than this desktop, so
expect ~15–20 ms to generate a chunk there. Crossing a chunk border
generates up to five chunks at once (`URGENT_STREAM_RADIUS`), which is the
one hitch left and the next thing to move off the game thread. Triangle
counts are well inside what GLES 3.0 phones draw at 30 fps; the LOW tier's
8-block detail ring adds ~8 k triangles.

### Known gaps

* Canopies and upper floors are part of the terrain mesh, so unlike prop
  sprites they do not yet fade when they hide the hero.
* Buildings are shells at block resolution (floors are thinner than a block);
  interiors are not yet walkable upstairs.
* Measurements above are from a desktop; profile on a real low-end device.

---

## An endless world, and a hero who is never hidden

**Infinite.** Every chunk is a function of the seed and its position alone,
so the world streams in wherever the player walks, and chunks left behind are
released. Only the chunks the player changed are kept, and saved.
`InfiniteTerrainTest` walks away from home and samples land 100 thousand,
1 million and 3 million blocks out. A scan of the height field finds the
same share of land (about 70%) and the same roughness out to 10 million
blocks. The practical limit is integer range: microvoxel coordinates are
four per block, so about 500 million blocks from spawn in any direction.

**See-through around the player.** Hills, walls and roofs between the camera
and the hero are cut away in a soft-edged circle, so the hero is never
covered. In 3D the cut is a cylinder along the line from the eye to the
hero's body: `Reveal` on the frame, `ShadingModel.revealCut` in Kotlin, and
`revealCut` in the GLES shader, with a dithered rim. It removes only what is
in front of the hero and above their feet. The floor underfoot and everything
behind them stays, and actors are never cut. The 2D view does the same per
block (`WorldFrameRenderer.isRevealed`), and repaints its cached terrain
layer only while something near the hero is actually being cut. Set
`SceneBuilder.revealRadius` or `WorldFrameRenderer.revealRadius` to 0 to turn
it off. See `docs/screenshots/microvoxel/reveal-indoors-off-vs-on.png`.

## Microvoxels to the edge of the view

Every chunk in view is drawn from microvoxels. Chunks within
`RenderSettings.microDetailRadius` get full quarter-block detail. Chunks
beyond it, out to `microFarRadius` (the view's edge on every tier), are
drawn from half-block microvoxels (`Lod.downsample`, factor 2) instead of
textured blocks. Detail is meshed on a small pool of background threads,
one fewer than the cores and at most three. Measured with
`./gradlew :tools:artpreview:microScenePreview` on a 4-core desktop JVM:

| Tier | Microvoxels near, blocks beyond | Microvoxels to the edge | Detail catch-up |
|---|---|---|---|
| LOW | 22,252 triangles, 3.1 MB | 27,716 triangles, 3.9 MB | 11 ms → 45 ms |
| MEDIUM | 44,278 triangles, 6.2 MB | 46,622 triangles, 6.5 MB | 37 ms → 114 ms |
| HIGH | 61,010 triangles, 8.5 MB | 60,620 triangles, 8.5 MB | 121 ms → 204 ms |

Half-block microvoxels cost about what textured blocks did, so a view that
is microvoxels to its edge costs almost nothing extra to draw. It takes
longer to fill in after a move. Meshing one chunk takes 8.9 ms at full
detail (10.2 ms before it read the generated chunk whole instead of block by
block) and 5.8 ms at half-block. The game still plays on blocks: collision,
pathing, digging and saves are unchanged.

## Africa's geology and building traditions

See [docs/AFRICAN-WORLD.md](../../docs/AFRICAN-WORLD.md): twenty
geological provinces with their real rocks and landforms, and eighteen
building traditions chosen by the land each town stands on.

## Shaping the world while it is played

Terrain generation is a core part of play, not a setup screen. Every
microvoxel world is a `HotTerrain` (in `:engine:microbridge`): the session,
renderers and encounters hold one reference for the whole run while the
generator behind it is swapped.

- **Stages describe themselves.** A stage factory that implements
  `Describable` returns a `StageInfo`: a title, a summary and its knobs as
  `StageParam`s (`Number` with a range and step, `Choice`, `Toggle`). The
  in-game World panel (⛰ in the menu) builds its sliders from these, so a
  plugin's own stage gets controls without any UI code.
- **Retuning is safe.** `HotTerrain.prepare(passes)` builds and test-runs a
  whole new generator on a worker thread. A bad value comes back as a
  message and the old land stays. `WorldSession.installTerrain` swaps the new
  one in between frames, remakes every chunk the player has not changed, and
  puts the hero back on the ground. Chunks the player built or dug in keep
  their changes.
- **It saves.** The tuned stage list is stored in `WorldConfig.terrainPasses`
  and overrides the packs' `passes` for that world only.
- **The home town is editable.** `micro:settlements` takes `homeRecipe`,
  `homeSize` (0.5–2×), `homeLayout`, `homeWalls` (auto/on/off),
  `homeVariant` (a different arrangement of the same town), `density`,
  `style` and `sacredTree`. The land stage's `spawnRise` and `spawnRadius`
  shape the ground the town stands on.

Measured on the desktop JVM (`./gradlew :tools:artpreview:microScenePreview`),
a retune takes 3–10 ms to prepare. Screenshots of each edit are in
`docs/screenshots/microvoxel/hot-*.png`, and the panel itself is in
`app/src/test/screenshots/world_shaper.png`.

## The generator contract

Every stage keeps three rules (see `gen/Pipeline.kt`):

1. **Chunk-local and order-free.** What a stage writes is a function of the
   seed and world coordinates only. A thing that crosses a border — a canopy,
   a building, a road — is derived from the cell or region that *owns* it;
   every chunk it touches derives the same thing and rasterises its part.
   That is what makes the world infinite, multithreadable, and makes a save
   file nothing but the player's edits.
2. **Two phases.** At world creation each stage's factory runs in order and
   may **publish** services into `WorldFields` under typed keys. At generation
   time stages read the *final* services. So the city planner can replace
   `Fields.SURFACE` with a flattened version, and the terrain stage — listed
   earlier — fills the flattened ground.
3. **Touch only what you own.** Bounding boxes, column caches, whole-brick
   fills. `MicroBudgetTest` holds memory, quad counts and generation time.

### Built-in stages

| Id | Does | Publishes |
|---|---|---|
| `micro:terrain` | Domain-warped continents + derivative-damped ("eroded") fbm hills + masked ridged mountains; strata by slope, height and climate; sea. | `NATURAL_HEIGHT`, `SURFACE`, `CLIMATE`, `SEA_LEVEL`, `COLUMNS` |
| `micro:caves` | Two crossing 3D fields → spaghetti tunnels. Off in low-spec presets. | — |
| `micro:city_plan` | Infinite region grid → urban or wild, plateau, boulevards on shared region edges, BSP streets, lots with frontage, style per lot. Blends terrain onto plateaus. | `city`, `FOOTPRINT`, replaces `SURFACE` |
| `micro:roads` | Asphalt, raised quarter-block kerbs and pavements, lane markings, street lamps; junction handling. | — |
| `micro:buildings` | Rasterises each lot's building through its `ArchitectureStyle`. | `architecture` (if absent) |
| `micro:groundcover` | Grass tufts, flowers, shrubs, pebbles — one-voxel detail. | — |
| `micro:trees` | Jittered-grid broadleaf (noisy blob crowns) and conifers (whorled cones). | — |

Presets (`MicroWorldgen.presets`): `micro:wilds`, `micro:mixed`,
`micro:city`, `micro:caverns`. A pack can list its own stage specs with
options (all options are strings so JSON and sliders can drive them).

### Plugging in an algorithm

```kotlin
// A new stage, available to every world built afterwards:
MicroWorldgen.stages.register("mypack:canals") { setup ->
    val water = setup.palette.id(M.WATER)
    MicroStage { ctx -> /* write ctx.set(...) for voxels this chunk owns */ }
}

// A different city: publish your own CityPlanner (e.g. tensor-field streets)
MicroWorldgen.stages.register("mypack:tensor_city") { setup ->
    val planner = MyTensorFieldPlanner(setup.seed, setup.fields.require(Fields.NATURAL_HEIGHT))
    setup.fields.publish(CityPlanStage.KEY, planner)
    setup.fields.publish(Fields.FOOTPRINT, planner)
    MicroStage { }
}
// ...then use it in place of micro:city_plan; roads, buildings and trees keep working.

// A new architecture style: implement ArchitectureStyle.sample(building, x, y, z)
// and register it in an ArchitectureRegistry published under ArchitectureRegistry.KEY.
```

Styles are **functions from a voxel to a material** (`sample`), not
procedures that write voxels. That makes a building chunk-safe for free,
trivially swappable, and testable by sampling a column.

---

## Why these data structures (and what the cutting edge does)

### Storage: brick maps with palette compression

The leading approaches for large voxel worlds in 2024–26:

* **Brick maps** — a coarse grid of pointers to small dense bricks
  (van Wingerden 2015; the design behind Stijn Herfst's CUDA
  [BrickMap](https://github.com/stijnherfst/BrickMap) and most hobby
  microvoxel engines, e.g. [VoxelRT](https://github.com/dubiousconst282/VoxelRT)).
  Uniform regions cost one entry. **Used here**: 8³ bricks, uniform bricks
  stored as one short, detailed bricks palette-compressed to a byte a voxel.
* **Sparse voxel octrees / DAGs** (Laine & Karras 2010; Kämpe et al. 2013,
  "High resolution sparse voxel DAGs"): superb compression for static data,
  but edits mean rebuilding shared subtrees. Worth it for a far-field LOD
  cache, not for the editable near field.
* **64-trees ("tetrahexacontrees")**: 4³ children per node, with a 64-bit
  child mask — pairs naturally with bitwise traversal. A good next step for
  a GPU ray-marching backend.

### Meshing: binary greedy meshing

On mobile GPUs (Mali, Adreno), ray marching a microvoxel world at 60 fps
is out of reach on the low end; rasterised quads are the practical path.
Binary greedy meshing (cgerikj,
[binary-greedy-meshing](https://github.com/cgerikj/binary-greedy-meshing);
TanTan's popularisation) uses one 64-bit integer per row so culling is a
shift + and-not and merging walks set bits. Reported ~50–200 µs per 64³
chunk on desktop. **Used here** — which is exactly why chunks are 64 wide.
Quads are packed into 8 bytes each for **instanced rendering with vertex
pulling** (GLES 3.0: `gl_VertexID` expands one instance into a quad).

### Level of detail

Distance rings downsample by 2 (32³) and 4 (16³ = block resolution). The
downsampler keeps the *topmost* solid material (hills stay green) and uses
a 3/8 fill threshold so thin walls survive a step. `QualityProfile` sets
view radius, full-detail radius and chunks generated per frame (LOW: 4, 1,
1 — a 128-block horizon with microvoxels only around the player).
Transvoxel-style seam stitching is unnecessary for cubes; skirts (one
extra face ring at LOD borders) hide cracks if they show.

### Terrain

* **Derivative-damped fbm** (Quilez, "value noise derivatives"): octaves are
  divided by 1 + |∑∇|², so detail collects in valleys and plateaus — a cheap
  imitation of erosion that stays chunk-local. Real hydraulic erosion
  (e.g. particle-based, or the 2023–25 GPU "erosion filter" approaches)
  needs neighbourhoods and is best baked into region tiles, not per chunk.
* **Domain warping** removes the "noise look".
* Continuous heights at quarter-block resolution: slopes become quarter
  steps instead of block stairs — the single biggest visual win of microvoxels.

### Cities and architecture

* **Road networks**: L-systems (Parish & Müller 2001, CityEngine), tensor
  fields whose eigenvector streamlines become streets
  ([Chen et al. 2008](https://www.sci.utah.edu/~chengu/street_sig08/street_sig08.pdf)),
  and agent/graph growth. For *infinite* generation the hard part is
  consistency across tiles; the region-grid approach here (boulevards on
  shared region edges, BSP inside) is the robust baseline, and a tensor-field
  planner can be dropped in behind the `CityPlanner` interface.
  Recent research (SimWorld 2025, CityGenAgent 2026, Yo'City 2025) layers
  LLM/agent planning over the same procedural core: road graph → parcels →
  buildings → street furniture.
* **Parcels**: block subdivision into lots with street frontage (Vanegas et
  al. 2012 is the reference).
* **Buildings**: split/shape grammars (CGA shape, Müller et al. 2006):
  mass → floors → bays → window/frame/sill. Implemented here as per-voxel
  grammar functions per style (terrace, villa, tower). **Wave Function
  Collapse** / model synthesis (Gumin; Merrell) and Townscaper-style
  (Stålberg) marching-cube tile sets are the natural next styles for
  organic old towns — they fit the same `ArchitectureStyle` seam if solved
  per building (bounded, deterministic seed).

### Rendering on low-spec Android (what the app-side renderer should do)

1. One VAO, instanced quads from `QuadMesh.quads` (8 B each), colour from a
   material palette texture, per-voxel jitter from a hash in the shader.
2. Baked per-quad AO is lost by greedy merging; use **screen-space AO off**
   on LOW and a cheap **corner AO texture** (the preview's `cornerAO`) on
   MEDIUM+. Sun shadow: one cascaded shadow map on HIGH only.
3. Fog to hide the view-distance edge; sky gradient; emissive windows and
   lamps with a half-res bloom on MEDIUM+.
4. Generate and mesh on worker threads; upload at most N meshes per frame
   (`QualityProfile.chunksPerFrame`).

---

## Numbers

Measured by `microPreview` (`10_budget.txt`) on seed 20260928, downtown, 5 × 5
chunk columns (125 chunks, 77 non-empty), one desktop JVM thread:

| | |
|---|---|
| Generation | 1.4 ms per chunk on average (sky and rock chunks exit early) |
| Memory | 83 KB per non-empty chunk (a flat 64³ short array is 512 KB) |
| Full detail | 91,913 quads from 1,762,664 faces — 19× fewer via greedy merging; 2.1 ms to mesh a chunk |
| LOD ×2 | 38,754 quads, 1.7 ms per chunk |
| LOD ×4 (block resolution) | 13,696 quads, 0.9 ms per chunk |

Phones are roughly 3–6× slower per core than the desktop these came from, so
expect ~5–10 ms to generate and mesh a surface chunk on a low-end device —
fine on a worker thread at one chunk per frame (`QualityProfile.LOW`).
These are desktop measurements, not device measurements; profile on real
hardware before tuning.

The tests assert: average downtown chunk < 160 KB, greedy merging > 3× fewer
quads than faces, block LOD > 4× fewer quads than full detail.

## Status

* Generators, storage, mesher, LOD, streaming and edits: implemented and
  unit-tested. Wired into the game as `stratum:microvoxel` (see "In the
  game" above), drawn by the existing GLES renderer near the camera.
* Streets are an axis-aligned region grid. Organic street networks (tensor
  fields, L-systems) and WFC-style old towns are the next planners/styles
  behind the existing interfaces.
