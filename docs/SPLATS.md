# Voxel splats

The microvoxel land can be drawn two ways, chosen per tier and overridable by
the player (Style panel → **Terrain**: Auto, Mesh, Voxels, Voxels sharp):

- **Mesh**: the greedy mesher's quads, as before.
- **Voxels**: one lit point per surface voxel, *splats*.

Splats are the default at every tier. The mesh stays one tap away.

## What a splat is

Sixteen bytes per surface voxel (`VoxelSplat`):

| int | bits |
| --- | --- |
| 0 | x 6, y 6, z 8 (microvoxels in the chunk), lod 2, open faces 6, top-face contact occlusion 4 |
| 1 | colour 24 (per-voxel jittered tone), emission 8 |
| 2 | bounced colour 24, sky seen 8 |
| 3 | lamp light 24, spare 8 |

Only voxels with an open face other than their underside are kept. Solid
bricks buried in the ground cost nothing. Far rings use the same code on a
2× or 4× coarser grid, with bigger splats.

## Voxels that understand light

When a chunk layer is built (always on the meshing threads, never the frame),
`VoxelLight` walks the voxel grid around each surface voxel:

- **Sky**: a dozen short rays over the upper hemisphere, cosine-weighted, out
  to about six blocks. The share that escape scales the ambient light, so
  alleys, eaves and the ground under a canopy darken by what really covers
  them.
- **Bounce**: each ray that is stopped picks up the colour of what stopped
  it. The shader scales this by the hour's sky and sun, so a red wall warms
  the path beside it by day and gives nothing at night.
- **Lamps**: every glowing voxel within reach is gathered a block at a time,
  from this micro chunk and the 26 around it (cached per chunk), plus placed
  lamp blocks. Each lights the voxel with the mesh path's own falloff and
  facing, in its own colour, if the voxel can see it (walls leak 15%). This
  is every lamp and lit window in view, not just the 2–8 point lights a tier
  can shade per pixel. The splat shaders therefore shade only the lights
  that move (the hero, impacts): `SceneFrame.dynamicLights`.

The sun, its shadow map and the moving lights stay live on top. With
`RenderSettings.voxelLight` off, splats are lit like the mesh and build
faster.

## Drawing them

`SceneGlRenderer.drawSplats`: one `GL_POINTS` draw per chunk layer, from
buffers uploaded once and kept by identity like the terrain's. The vertex
shader decodes the splat, lights its top and the two sides turned to the eye
once each, and sizes the sprite to hold the cube. The fragment shader then
picks the face:

- **Fast** (LOW, MEDIUM): three slab tests cut the sprite to the cube's
  hexagonal outline, and three line tests pick top, x side or y side
  (`SplatFaces`). That is six dot products a pixel, and the depth is the
  voxel centre's, from the rasteriser.
- **Exact** (HIGH, ULTRA): a ray against the voxel's box per pixel, writing
  the hit's depth. Exact is required under a finish that reads depth
  (edges, screen-space occlusion, tilt-shift). With one depth per voxel, the
  edge ink found a crease at every seam, so `RenderSettings.splatDraw`
  upgrades fast to exact there.

Splats cast into the sun's map as squares of their centre's depth.

## What it costs (`splat-benchmark.txt`)

Desktop JVM, seed 20260928, around the home town:

| per chunk (full detail) | mesh | splats |
| --- | --- | --- |
| GPU bytes | 1,635 KB | 98 KB |
| vertex shader runs | 23,912 | 5,730 |
| build (worker thread) | 9.6 ms | 16.8 ms lit, 6.0 ms unlit |
| one edit (a layer) | 3.78 ms | 3.82 ms lit, 1.05 ms unlit |

| whole view | mesh | splats |
| --- | --- | --- |
| LOW | 11.8 MB, 133k vertices | 1.0 MB, 49k points |
| MEDIUM | 25.5 MB, 302k | 2.3 MB, 104k |
| HIGH | 45.6 MB, 379k | 3.5 MB, 121k |
| ULTRA | 62.7 MB, 419k | 4.8 MB, 134k |

So the terrain holds 11–13× less GPU memory, and the vertex stage runs
2.7–3.5× fewer times. The light costs build time on the workers, not frame
time.

**Not measured here:** fragment cost on a real phone GPU. A splat covers its
cube's outline plus a hairline. Fast splats discard outside the hexagon,
which on some tile-based GPUs moves the depth test after the shader. The
next step is a device profile (Adreno 5xx/6xx, Mali-G5x) against the mesh.

## Evidence

- `docs/screenshots/splats/splats-<shot>-{high,medium,low}.png`: mesh against
  splats at each tier (CPU twin of the GL renderer).
- `…-voxel-light.png`: splats lit like the mesh against splats with voxel light.
- `…-fast-vs-exact.png`, `…-closeup.png`: 2× crops.
- `gpu-<shot>-{fast,exact}.png`: the game's own splat GLSL, dumped by
  `SceneShadersTest` and drawn in headless Chromium's WebGL 2 (SwiftShader) by
  `tools/splatgl`. These frames contain splats only (no shadow map, actors or
  water). `gpu-report.json` records that each compiled, linked and drew
  without a GL error.

Regenerate:

```
./gradlew :tools:artpreview:microScenePreview --args="out content/igbo/src/main/resources/forge 20260928 splats bench"
STRATUM_SHADER_DUMP=out/shaders ./gradlew :feature:play:testDebugUnitTest --tests '*SceneShadersTest*'
NODE_PATH=$(npm root -g) node tools/splatgl/render.mjs out/gpu out/shaders out
```

## Known gaps

- Surfels (pebbles, tufts) scatter over mesh quads, so they are off on
  splats. Each voxel's own tone stands in for them.
- The mesh's bevels are not drawn on splats.
- The cut-away around the hero removes whole voxels on splats, rather than
  the mesh's dithered edge.
- Lamps in a neighbouring chunk that a player placed (not generated) light
  only their own layer's splats.
