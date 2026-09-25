# Architecture

Stratum is an isometric voxel action RPG: a block world you mine and build in,
wrapped in an ARPG shell, where the content is data rather than code.

## The module graph

```
                       :app  (Activity, navigation, composition root)
                        │
        ┌───────────────┼───────────────┬──────────────────┐
        │               │               │                  │
  :feature:play   :feature:forge  :feature:studio    :core:data
        │               │               │                  │
        │               │       ┌───────┴──────┐           │
        │               │  :legacy:data   :legacy:domain    │
        │               │       │                           │
        └───────┬───────┴───────┴───────────┬───────────────┘
                │                           │
        :core:designsystem            :core:domain ◄── :content:igbo
                │                           ▲
                └───────────────────────────┤
                                     :engine:world
                                            ▲
                                     :engine:render ◄── :tools:artpreview
                                                                │
                                     :engine:scene ◄────────────┘
                                   (3D: -> :core:domain only)

  Importers, beside the engine rather than in it:

    :importer:flame ──► :importer:tiled ──► :importer:common ──► :core:domain
          ▲                                        ▲
        :app (registers them)          :core:data (keeps archives)
                                                   ▲
                                   :feature:library (-> :core:domain only)
```

Dependencies point inward only. Nothing in `:core:domain` knows that Android,
Room, OkHttp or Compose exist.

## The modules

| Module | Kind | Holds |
| --- | --- | --- |
| `:core:domain` | Pure Kotlin | The voxel model, content packs, combat and itemisation, enemies, skills, progression, player state, the AI ports and the generation use cases. |
| `:engine:world` | Pure Kotlin | Terrain generation, chunk streaming, mining and building rules, isometric projection, combat, loot rolling, the monster director, and the play session that joins them. |
| `:engine:render` | Pure Kotlin | Frame planning: walks the world, asks the art director how each thing looks, and emits drawing primitives. Knows nothing about Compose or Android. |
| `:engine:scene` | Pure Kotlin | The 3D world: voxel meshing with ambient occlusion, the action-RPG camera, sprites, lights, ray picking, the shared lighting equation, and the asset forge that turns image-model output into usable textures. |
| `:content:igbo` | Pure Kotlin | The built-in content pack, and the asset kits forged for it (`src/main/resources/forge`). |
| `:importer:common` | Pure Kotlin | Import sources (a zip, a folder, memory) and the path and naming rules every format shares. |
| `:importer:tiled` | Pure Kotlin | Tiled `.tmx`/`.tmj` maps and tilesets, and turning flat layers into a level with height. |
| `:importer:flame` | Pure Kotlin | Flame games: their Tiled levels, and characters from Aseprite, TexturePacker and Dart. |
| `:tools:artpreview` | Pure Kotlin | Renders the world headlessly to PNGs, one per style. Never shipped in the app. |
| `:core:data` | Android library | Adapters: the OpenAI-compatible model client and provider settings. |
| `:core:designsystem` | Android library | The visual language, driven entirely by the loaded pack's palette. |
| `:feature:play` | Android library | The play screen, and the Compose backend that puts the renderer's primitives on a canvas. |
| `:feature:forge` | Android library | AI pack generation and its preview. |
| `:feature:library` | Android library | Importing a game from a `.zip`, and the list of what has been imported. |
| `:legacy:domain` | Pure Kotlin | The original engine's rules, pending port. |
| `:legacy:data` | Android library | The original engine's Room and network layer. |
| `:feature:studio` | Android library | The original creator studio screens. |
| `:app` | Android app | The Activity, navigation between destinations, and the wiring that connects ports to adapters. |

## How the boundary is enforced

Not by review. `:core:domain`, `:engine:world`, `:engine:render`, `:engine:scene`,
`:content:igbo`, the three `:importer:*` modules, `:tools:artpreview` and
`:legacy:domain` apply only the Kotlin
JVM plugin, so the Android SDK is not on
their compile classpath and `import android.*` fails to compile.

`./gradlew architectureCheck` closes the loophole around that: it fails the
build if one of those modules picks up an Android plugin or an Android artifact.
It is wired into CI. To see it work, add `implementation(libs.androidx.core.ktx)`
to `core/domain/build.gradle.kts` and run it.

## Why content packs

The engine ships with no content. Blocks, regions, classes, lore and the
interface palette all come from a `ContentPack`, and the three sources — the
built-in module, an AI generation run, and a file a player imported — are
indistinguishable to everything downstream. Packs layer: a later pack overrides
an earlier one by id, so a generated pack can restyle a handful of blocks
without forking the whole thing.

`ContentPackAssembler` flattens the enabled packs into an `AssembledContent`, and
validates that every block a region names actually exists. That is why a
generated pack fails at the generate button rather than mid-world-generation.

The action RPG half is pack data too: damage types, weapon bases, affixes,
monsters and skills. The engine never names one. A weapon or monster referencing
a damage type nobody defined is rejected at load, because otherwise every hit of
that type would silently resolve as unresisted and quietly skew the balance of
every pack loaded alongside it — far harder to notice than a crash.

Two things are deliberately *not* pack data. Rarity tiers carry a fixed number of
affixes, and the experience curve is fixed, because a pack that could change
either could trivialise every fight in every other pack loaded with it. Packs
control what those tiers are called and what colour they are.

## Why generation logic lives in the domain

Prompt construction, JSON extraction, repair and validation are all in
`:core:domain`, behind a one-method `LanguageModelPort`. The network adapter in
`:core:data` only moves bytes.

That split is what makes the hard part testable: the generation tests run a
fake model that returns markdown-fenced JSON, missing namespaces, dangling block
references and out-of-range numbers, and assert the result is still playable —
with no network and no Android.

## Swapping world generation

Terrain has two seams, because there are two different things people want to
change.

**Describe a different landscape.** A pack ships a `TerrainRecipe`: elevation
noise layers, a terrace step, material strata. No code, so the AI pack forge or
a JSON file can author one. `terraceStep` is the important knob — smooth noise
produces a landscape of one-block steps that reads as texture and cannot be
walked or built on, while snapping heights to plateaus gives ledges you can see
and ground you can use.

**Replace the algorithm entirely.** Register a factory and name it in a recipe:

```kotlin
StratumTerrain.registry.register("mypack:caves") { context ->
    MyCaveGenerator(context.config, context.biomes, context.recipe)
}
```

A pack asking for `mypack:caves` then gets it. `TerrainGenerator` requires one
method; `BiomeSource` is a separate optional interface, so a generator with no
concept of biomes — a dungeon builder, a flat sandbox — does not have to invent
them. `WorldSession` also takes a generator directly, which is how tests run on
terrain they control.

An unknown generator id is an error rather than a silent fallback. A pack asking
for something this build does not have should say so, not quietly hand the
player a different world.

## Importing Flame games and Tiled maps

A player can bring in a level and its characters from another engine: a
`.zip` of a Flame game, or of plain Tiled maps. What comes out is an ordinary
`ContentPack` -- blocks, one region, the maps, sprite sheets and a playable
hero -- so an imported game goes through the same assembly, validation,
generation and rendering as the built-in pack, and is layered over it the
same way. Whatever the import does not bring, such as monsters or weapons,
comes from the packs underneath.

**Ports in the domain, formats beside it.** `:core:domain` defines what an
import is: an `ImportSource` (a read-only tree of files), a `ProjectImporter`,
an `ImporterRegistry` that asks each importer in turn, and `ImportProjectUseCase`.
Importers never decode an image. They describe art as `ImageRegion`s -- this
rectangle of that file -- and an `ImportedAssetWriter` on each platform cuts
it: Android crops bitmaps into app storage, the JVM tool writes PNGs. That is
what lets the formats be pure Kotlin and tested without a bitmap.

**A level is a stack, and the importer decides the heights.** Tiled has no
depth: a floor, a hedge and a roof are three layers drawn in order.
`TileMap` holds layers at an elevation and thickness, and `LayerRoles` decides
them -- from the layer's own `elevation`, `thickness` and `solid` properties
first, then from the names Flame RPGs conventionally use. The bottom layer is
the floor; `walls` stand two blocks high; decoration becomes scenery drawn as
standing sprites, the way the engine draws its own; anything that draws
*over* the player in 2D is left out, because in 3D it would bury the camera.
Collision rectangles on an object layer become a barrier.
`TileMapTerrainGenerator`, registered as `stratum:tilemap`, builds chunks
from the stack and walls in the map's edge. The map is centred on its player
spawn, and enemy markers become fights where the author put them.

**Characters without running Dart.** Aseprite and TexturePacker atlases are
read directly. Most Flame games cut sheets in code instead, so
`DartAnimationScanner` reads `SpriteAnimationData.sequenced(...)` and
`SpriteSheet(...).createAnimation(...)` when their arguments are literals,
following an image loaded into a variable. An animation built from computed
values is reported, not guessed. Each character becomes one grid sheet with a
clip per engine state, matched by the words in its animation names.

**Nothing silent.** Every import returns warnings for what it understood but
could not honour: a hidden layer, an image layer, a flipped tile, an
animation it could not read, a map that failed. One bad map is left out and
named; it does not fail the project.

**Kept as the archive, not as a pack.** The app stores the `.zip` the player
picked and re-imports it at launch. Importers are deterministic, so the pack
comes back with the same ids, and there is no second pack format to keep in
step with every field a pack can have. Only the cut art is stored.

**Checked against real projects.** `./gradlew :tools:artpreview:importPreview`
imports a project on the JVM, prints what came of it and renders its level.
Run on Flame's `flame_tiled` example and on Bonfire's example game, it found
map ids and tileset names that collided across folders, games built on
Bonfire that were not recognised, and a texture-library bug where every tile
past the 1024th was read as a ground map. Each has a test now.

## The session is an orchestrator

`WorldSession` owns the player and decides the order things happen in a
tick. It does not hold the rules. Each concern keeps its own state and rules
in its own part: `PlayerMotion` (movement, the roll), `MiningProgress`,
`BuildSession` (tool, ghost preview, commit), `GroundItems` and `LootDrops`,
`PlayerGear` (equip and sockets, as pure functions over `PlayerState`),
`ActorAnimator`, and `SessionCues` (every floating number, named for what
happened). The player is passed through them and handed back, because it is
the one thing they all touch. Adding a system means adding a part and one
line to the tick, not another two hundred lines to the session.

## Why the look is data

The renderer used to decide what the world looked like. Ground eight levels
down was fifty-five per cent as bright, a ledge cast at twenty-two per cent
black, the player was a cream circle with a bronze ring — all of it constants,
inside a composable, for every pack, forever.

Those constants were not wrong. They were unreachable. A content pack could
restyle its blocks but not its *world*; a player could not restyle anything; and
a look nobody can change is a look nobody can direct.

`ArtDirection` is that knowledge as one value: a palette, a lighting rule, a
contrast contract, a shape language, weather, and the words handed to image
models. `WorldArtDirector` is the seam that answers with it, per block, per
prop, per actor, per region:

```kotlin
interface WorldArtDirector {
    fun terrainStyleFor(cue: TerrainCue): TerrainStyle
    fun propStyleFor(cue: PropCue): PropStyle?
    fun actorStyleFor(actor: ActorPresentation): ActorStyle
    fun atmosphereFor(biome: BiomeDefinition?, time: WorldTime): AtmosphereStyle
    fun effectsFor(cue: CombatCue): List<VisualEffect>
}
```

The renderer keeps the questions it is good at — where a thing lands on screen,
what order to draw in, how to get pixels down fast — and has no opinion about
what a sacred grove looks like.

## The contrast contract

The problem worth naming: in a field of isometric voxels, terrain, trees, ore,
loot and monsters all tend to end up in the same band of colour and the same
band of brightness. Nothing is *wrong*, and the eye has nowhere to land. It
reads as a pleasant prototype rather than as somewhere dangerous.

Contrast is treated as a budget. Terrain is charged for it and actors are paid
it, and `ContrastContract` is that transaction written down:

- terrain is desaturated and its brightness squeezed into a band,
- ore, loot and anything interactable is *boosted* past the ground,
- actors are drawn larger than the grid says they are, because a person who is
  literally one block wide is a speck and a speck cannot be cared about,
- every actor gets a contact shadow, a dark contour and a lit rim, which is what
  stops the cast looking pasted onto the terrain,
- rank is announced on the floor, in a ring, not in more furniture around the
  health bar.

`ArtDirection.enforcePlayable()` clamps every style to those bounds before it
reaches the renderer, whatever produced it. A style may be garish, washed out,
nearly black or nearly white. It may not hide the thing about to kill you.

## Promptable worlds

A style comes from a sentence. `StyleLexicon` reads one offline: it holds a few
dozen `StyleTrait`s — moods like *dark*, *kawaii*, *toxic*, *frozen*, and
rendering manners like *inked*, *woodblock*, *chiaroscuro*, *painterly* — and
each one is a small edit to the house style. They compose, so "dark kawaii
woodblock" is three edits rather than a fourth preset somebody had to author.

Two properties matter more than the trait list:

- **Order does not count.** Traits apply in lexicon order, not typing order, so
  "kawaii dark" and "dark kawaii" are the same world. A prompt is a description,
  not a program, and players do not order adjectives.
- **The seed does.** The same words with the same seed are the same world every
  time; with a different seed they are recognisably the same style in a
  different place. That is what makes a reroll a reroll rather than a reskin.

Words the lexicon cannot serve are *returned*, not ignored — they are the most
useful output of the call. `StyleBriefUseCase` hands them to a language model
along with the style so far, and gets back the same record, clamped on the way
in. That is the one thing a model is genuinely better at than a lookup table:
knowing that a painter's manner means broken complementary colour and a restless
stroke.

The offline path always works — no key, no network, no latency, no cost — and
the model is an upgrade to it rather than a replacement, so the game is playable
while the request is still in flight and stays playable if it fails.

## Why the renderer draws into a sink

`WorldFrameRenderer` walks a read-only `World` and emits primitives to a
`FrameSink`. Two backends implement it: `ComposeFrameSink` on a phone, and
`ImageFrameSink` in `:tools:artpreview`, which writes PNGs.

That is what makes the look reviewable. `./gradlew :tools:artpreview:artPreview`
renders the same scene at every built-in style, from the same seed and the same
camera, in a couple of seconds, with no device and no emulator. A style is a few
dozen numbers, and the only honest way to know whether a change to them helped
is to look at it beside the one before.

It is a sink rather than a list of draw objects because it runs sixty times a
second on a phone: a frame is tens of thousands of primitives, and allocating a
description of each one costs more than drawing it.

## What the AI is asked for, and what it is not

Generated terrain is the expensive answer to a question the voxel grammar has
already answered. The model is asked for *bounded ingredients* instead, and
never for the final image of an acre.

| Tier | Examples | How it is made |
| --- | --- | --- |
| Systemic | Blocks, tile variants, ore, grass, ordinary trees | Never generated. Procedural colour plus a silhouette family plus a deterministic variant per instance. Thousands exist and they must agree with each other, which rules do well and independent generations do badly. |
| Prop | Shrines, ruins, landmark trees, arenas | One generation each at most, usually one per *region*. The landmark is the asset; the generator's placement rules do the rest. |
| Hero | The player, elites, bosses, weapons | Generated from one curated reference so it is the same character every time, posed from motion data rather than from independent prompts, and accepted by a person before it ships. |

`BiomeArtKit` is the region's ingredient list — a few ground swatches, a few
prop silhouettes, one landmark, one weather. It is *derived* from rules a pack
already has rather than authored, so a pack a model invented ninety seconds ago
is art directed exactly as well as the one that shipped with the game.

`ArtBible` assembles prompts by holding everything fixed but one noun. Camera,
palette, edge treatment and prohibitions all come from the style record. Ask for
a tree, a rock and a shrine in three freely written prompts and you get three
objects from three different games; hold the rest still and you get a set.

Props are drawn as vector silhouettes rather than glyphs. A world of emoji
trees is one identical tree two hundred times, which reads as a repeating
texture, and the shapes belong to whichever font the device shipped rather than
to this game. Eight families with eight variants each cover everything a pack
can scatter, and every instance is a different individual.

## Why the world is 3D, and still made of sprites

The 2D isometric canvas could fake depth but not light: no shadow could fall
across a terrace, no torch could light the wall behind it, and a character
could only ever be pasted onto the ground. The play screen now draws a real
3D scene through a camera that looks down at about fifty degrees through a
narrow lens from a long way back — the Diablo and Hades camera — so the grid
still reads as a grid while walls get tops, terraces cast, and braziers light
what is around them.

The terrain is voxels meshed into triangles, with only visible faces emitted
and ambient occlusion at every corner. Occlusion is what makes a voxel world
look solid; without it every inside corner is lit like open ground.

Scenery is not modelled. Trees, reeds, braziers and crystals are painted
sprites standing in the lit world and turned to face the lens — exactly what
Diablo II and Hades do, and exactly the kind of asset an image model is good
at. A sprite in front of a character is faded with an ordered dither (no
sorting, no depth-write problems), because a player who cannot see their own
character behind a tree cannot see what is attacking them either.

The camera is what the scene is drawn *through*; taps are resolved by casting
a ray back out through the same camera and walking it cell by cell
(`ScenePicker`), so the block under the finger is the block that gets dug.

## Two renderers, one lighting equation

`SceneBuilder` produces a `SceneFrame`: terrain, sprites, bodies, ground
decals, glows, point lights and the sun's shadow projection, in one vertex
layout. Two backends draw it:

- `SceneGlRenderer` in `:feature:play`, OpenGL ES 3: a sun shadow map, the
  scene into a high-range target, and one tone-mapping pass.
- `SceneRasterizer` in `:tools:artpreview`, on the CPU, supersampled.

Both implement `ShadingModel` — hemisphere ambient, sun with 3x3 PCF shadows,
point lights, rim light on actors, distance and height fog, filmic shoulder,
saturation and vignette. The GLSL is a line-for-line port of the Kotlin, and
the shaders are compiled and linked as GLSL ES 3.00 in headless WebGL2 as a
check. `./gradlew :tools:artpreview:scenePreview` renders every style from one
camera, so a change to the look is judged by looking at it.

The director now answers two new questions: what a surface *is*
(`surfaceFor`, albedo and texture key, no light baked in) and what the light
*does* (`lightingFor`). The 2D path keeps working unchanged.

Two pieces of the lighting model exist because the first version got them
wrong. Fog is measured from the point the camera looks at, not the lens: the
camera sits thirty-odd blocks back, and fog measured from the eye started
before the first thing on screen. And every style gives the hero a light
radius, Diablo's answer to dark styles: the darkness stays everywhere except
around the one thing the player must be able to see.

## The asset forge

`ForgePlanner` reads any content pack and orders a small kit: the ground and
cliff faces of each region, the walls a player can build, and one sprite per
kind of prop — a dozen or so images for a region, never one per block. Each
prompt is the style's words, the block's own colour stated as dominant, the
prop's silhouette family in plain words, and the prohibitions. Sprites are
drawn on flat magenta and cut out by flood fill from the border, so purple
*on* the object survives.

`AssetForge` calls whatever `ImageModelPort` is wired in — OpenRouter with
`meta/muse-image` in the app and in the `forgeKit` tool — cleans each image
up for its role (square, downscale, seamless in two separate axis passes;
key, de-spill, trim), rejects images that are flat key colour or featureless
and retries them, and never fails a whole kit for one bad image.

On a phone, the Style panel's *Forge art* button does this for whatever the
player typed, into app storage, and the world repaints as each image lands.
Four kits ship in the pack: house, dark, hades and kawaii. Styles without a
kit borrow the house kit and differ by light, fog and grade alone — which is
itself worth seeing: the neon preview is the house kit.

One lesson is recorded in the code: Muse Image answers in WebP by default,
and the JVM WebP plugin decoded some of those as a flat green channel — which
looked exactly like a bad generation. The JVM client now asks for PNG.

## Characters in 3D

An actor is drawn from the best art it has:

1. An animated sprite sheet made in the sprite forge. The 3D view projects the
   actor's feet and head through its camera and draws the current frame at
   that size over the scene, with the same code the 2D canvas uses — clips,
   procedural motion, weapon rig, mirroring and hit flash all come along. The
   scene still lays its contact shadow and rank ring on the real ground.
2. Otherwise, a still sprite from the asset forge (`actor:<class or enemy id>`),
   drawn as a depth-correct, shadow-casting billboard in both renderers and
   mirrored when it walks towards screen-left.
3. Otherwise, the low-poly stand-in body.

Animated sheets are drawn over the scene rather than inside it, so a character
standing behind a wall shows through it. That is a known limitation, chosen
because it reuses the proven sprite code rather than duplicating it on the GPU.

## A world you cannot get stuck in

The Igbo world used to be built from three-block terraces with a one-block
step, so every terrace edge was a one-way drop and any pit a player dug was a
trap. Three changes, each covered by tests:

- Holding into a ledge up to three blocks tall climbs it after a moment
  (`PlayerMotion.CLIMB_UP`). Thin walls cannot be climbed; that would make
  building one pointless.
- Biome height offsets are blended smoothly across borders, which used to be
  sheer walls as tall as the difference between two regions' biases.
- The pack's terrain uses one-block steps. `WalkableTerrainTest` checks the
  real generator on several seeds: no step taller than a climb, and fewer than
  one border in a hundred needing one.

Walking into a column whose chunk is not loaded is refused rather than read as
air, which would drop the player to the bottom of the world.

Painted ground no longer repeats visibly: every textured pixel is blended with
a rotated, rescaled second sample by a slow world-space noise, and a second
noise varies its brightness (`ShadingModel.detile`, ported to the shader).

## Walls a third of a block thick

`BlockShape.WALL` is a pane a third of a block thick that joins neighbouring
walls and solid cubes, so dragging a line makes a wall and a corner makes an
L with nothing to rotate. Collision uses the same boxes the mesher draws
(`BlockShapes`), so a player can walk along the inside of their own wall. The
`ERASE` build tool removes a dragged region and hands the blocks back.

`BlockShape.FLOOR` is paving: a slab an eighth of a block thick that is not
solid and lies in the air cell above the ground. Laying it never raises the
floor or turns a doorway into a step, and dragging floor across grass paves
the grass (the build plan is lifted one level). Tiles that touch drop their
shared edges, so a courtyard is one surface with a lip only at its border.

## Variety: several of everything

A world where every tree is the same tree reads as machine-made faster than
anything else. The forge plans three individuals of each ground and each
prop (`ForgePlanner.variants`), keyed `key`, `key#1`, `key#2`, so a kit
forged before variants existed still loads.

- **Props** pick an individual by a hash of their cell
  (`TextureLibrary.variantsOf`), then vary in size and mirror, so neighbours
  differ and the same tree is the same tree every time you pass it.
- **Ground** weaves its three paintings together. Each ground-top vertex
  carries its two sister layers (`Vertex.VARIANT_A/B`), and the shader leans
  towards each in slow patches several blocks across
  (`ShadingModel.variants`), on top of the detile blend. One field, three
  paintings, no seams.
- **Litter** — fallen leaves, pebbles, flowers, roots — is forged as
  `detail:<biome>` cut-outs and laid flat on about one open ground cell in six,
  at a turn and size of its own (`TerrainMesher` records the spots; the scene
  lays the quads). Only on the region's own surface block, so paths, paving
  and walls stay clean.

## A visual hierarchy, or it looks like noise

Every screen of Diablo or Hades is ranked: a quiet floor, calm scenery that
frames the space, and only then the loud layer — characters, monsters, loot
and spell effects, the things a player must read in a fraction of a second.
Saturated colour and glow are spent on that layer and nowhere else. Without
the ranking, every painted asset competes and the result reads as noise
however good each piece is. Three rules enforce it:

- **The floor is quiet.** Painted floors keep only part of their contrast
  (`SceneLighting.floorDetail`, pulled towards the painting's own average —
  its one-pixel mip on the GPU) and part of their saturation; walls keep a
  little more, because cliffs also say where you can walk
  (`ShadingModel.calm`, ported to the shader). Litter is sparse.
- **Scenery frames, it does not fill.** The pack clusters scatter strongly
  (`scatterClustering`, `scatterClusterScale`) into groves and nearly bare
  clearings a few screens across: fights happen in glades, not in thickets.
- **Scenery does not glow.** Forge prompts for tiles, props and litter carry
  `ArtBible.SCENERY_RULE` and `FLOOR_RULE` and ask for no accent colour, and
  they name a region rather than quoting its description: "thick with emerald
  spirit mist" put a glowing wisp on every tree. Light sources are the one
  exception, because a lit brazier is a landmark.

## Ground maps: one large painting instead of a small tile

A small tile repeats every two blocks and the eye finds the grid however good
the painting is. So each region's ground and each path block also get a
`GROUND_MAP`: one large painting from the image model covering
`GroundMap.BLOCKS` (16) blocks a side, laid across the tops of those blocks
as a single surface and repeated only every sixteen. Inside that span it
holds real variety — a worn trail, a mossy hollow, a scatter of stones.

- **Asked for as one place, not a texture.** Called a "seamless tileable
  texture", the model paints a small motif and repeats it inside the frame,
  so sixteen blocks repeated every four. The prompt asks for one continuous
  painting with a stated, non-repeating layout instead.
- **Wrapped without copies.** The tile seam pass blends most of an image with
  its half-shifted self, which on a large map duplicated every feature.
  `Pixels.seamlessWide` cross-fades only a narrow band where opposite edges
  meet, so nothing is copied.
- **Kept large on the GPU.** Maps live at `TextureLibrary.MAP_BASE` and up
  and in their own 1024-texel array beside the 512-texel tile array, so the
  detail they exist for is not resampled away. Tile variants are skipped where
  a map is laid; the detile blend and floor calming still apply.
- `refinishKit` re-runs post-processing on the raw originals the forge kept,
  so a change like this costs nothing to apply to existing kits.

## Composition: roads, set pieces, and where fights happen

Noise makes a texture, not a place. `BiomeComposition` lets a pack say what a
region's paths and landmarks are made of; the generator decides where they go,
one column at a time from world coordinates, so chunks still generate in any
order without seams.

- **Paths** follow the 0.5 contour of a slow noise field: a contour never ends
  abruptly or crosses itself. The field's value divided by its slope is the
  distance to the contour, so a road keeps its width through bends; scenery is
  kept back from its verge.
- **Landmarks** — a centrepiece, a ring around it, a paved pad — sit one per
  cell of a fixed 44-block grid, never nearer a cell edge than the largest
  clearing, so a column only ever consults its own cell. Of several candidate
  spots in a cell the one nearest a path wins, so roads run through set
  pieces. A site is used only in open ground, its pad is levelled, and
  scenery is kept out of its clearing. In the Igbo grove: an Ofo shrine on
  laterite paving between four braziers.
- The forge plans art for whatever a region's paths and landmarks use.

`CompositionTest` checks the real pack: shrines exist, stand on level pads with
their four braziers, and paths are a thread through the grove with nothing
growing on them.

## One handedness, and a screen-relative stick

The 2D projection draws world +x down-right and +y down-left on screen. A true
camera looking north-west over a right-handed world shows the mirror image,
so the 3D view first shipped mirrored against every rule written for the 2D
one: pushed right, the hero walked left; characters faced away from where
they walked; the upper-left sun lit from the upper right. `SceneCamera`
flips screen X in its projection so both views share one handedness, and
its `right` is the on-screen right.

The stick was also fed in as world axes — right meant east, which on an
isometric screen is down-right. `IsometricProjection.screenToWorldDirection`
turns the stick's screen vector into a ground direction first, so up walks up
the screen. `ScreenDirectionTest` pins stick, 2D and 3D together by where a
step actually lands on screen, and fails with the mirror removed.

## Scenery is painted from the camera's angle

The 3D camera looks down at `IsometricCamera.SCENE_ELEVATION_DEGREES` (52),
and `SceneCamera` takes its default from there. Prop prompts state that angle
in degrees and in consequences for each shape family — a tree's crown seen
from above and dominating, its trunk foreshortened; a brazier's rim a wide
ellipse. Asked only for "a high three-quarter angle", the model painted trees
from eye level, and side-on trunks stood up in a world seen from above.

## Sprite shadows

A camera-facing card seen from the sun casts a sliver or a slab — in the
preview, literally a rectangle beside every tree. Sprites therefore do not
cast into the shadow map. Each lays its own silhouette on the ground instead:
a decal (`Vertex.SPRITE_SHADOW`) that samples the sprite's alpha, stretched
away from the sun and longer the lower it is, darker at the foot than at the
tip. The Diablo II and Hades answer; one quad per sprite, identical in both
backends. Terrain and walls still cast through the shadow map.

## A larger map

The game loads nine by nine chunks around the player (144 blocks across, up
from 80) and meshes 56 blocks around the camera, so the loaded edge stays far
past the fog.

## Combat theatre

The art director already described each moment of combat as data
(`effectsFor(CombatCue)` returns rings, flashes, debris, beams, arcs,
afterimages, ground glows and shakes). `EffectTrack` in `engine:scene` plays
them: pure, clocked by the caller, capped so a crowded fight drops its oldest
effect rather than its newest. `SceneBuilder` turns each one into additive
glows and soft decals, and the brightest few also become short-lived point
lights, so a hit lights the ground around it. The camera's aim takes
`EffectTrack.shake()`; picking uses the same camera, so a tap during a shake
still lands where the finger is.

On the phone, `CombatTheatre` reads what the engine already reports — every
new `FeedbackMark` (hit, crit, block, dodge, heal, kill, loot, level-up) and
every change of the hero's animation into attack, special or roll — and turns
each into a cue. Nothing new is threaded through the engine. A struck
character's painted sprite blanches for a moment through its emissive term.

## Why sprites have two models

`SpriteSheet` is a uniform grid read left to right, with each clip a contiguous
run of cells. That is the right thing to *play* — the renderer cuts a rectangle
and the playback clock counts — and the wrong thing to *author*. It can only
describe art that already landed on a perfect grid with the states in the order
the engine expects, and generated art almost never does. It comes back with a
border the model drew, three blank cells, two duplicates, one superb attack pose
and no death frames.

So there is a second model, `SpriteAtlas`, which names frames instead of
counting them. A clip is a list of frame ids, a frame may appear in several
clips or none, and a state with no frames is unmapped rather than invalid —
which is the normal condition of a sheet halfway through being mapped. It also
carries what the grid could not: a per-frame anchor, a flip, and an on/off flag
for the cells that are not art.

`AtlasBaker` collapses one into the other. It packs a mapping into an ordinary
`SpriteSheet` — one clip per row, a frame used twice copied into both, each
frame hung from its anchor so differently cropped poses share a baseline. The
renderer, the library, the playback clock and the save format are all untouched
by hand-mapping: it is something that happened before the asset existed, not
something the engine carries at runtime.

The split runs through the platform boundary too. Where the frames go is decided
in `:core:domain` and tested without a bitmap; `SpriteAtlasBaker` in `:core:data`
only decodes, blits and encodes. That is what makes the interesting question —
does a walk built from six differently cropped cells line up — answerable by a
unit test rather than by looking at a phone.

Baking is one-way, so `SpriteProjectStore` keeps the working file: the untouched
source image beside the mapping. Re-cutting a sheet never destroys the image it
was cut from.

`FrameGeometry` is the escape hatch from grids altogether. A collage, a figure
drawn twice at different scales, one good pose in the corner of an image that
was never a sheet — no arrangement of columns, margins and gutters describes any
of them, so frames can also be placed and sized one at a time. Hand-placed
frames are marked, because re-slicing rebuilds frames from grid positions and a
rectangle someone drew has none; without the mark, changing the column count
would delete their work.

## Why a character is drawn once and posed forty times

Asking an image model for a sprite sheet asks it to solve two problems at once,
and it is only good at one of them. Drawing a striking character once is
something it does well. Drawing the *same* character forty times, in forty
consistent poses, it does badly — and no prompt fixes that, because nothing ties
forty separate calls together. Each one invents a slightly different helmet.

So the pose pipeline separates them. `GenerateBasePoseUseCase` asks for one
figure, large, in a T-pose: never a frame anyone plays, and exactly right as an
anchor, because nothing is occluded. An editor can only preserve what it can
see, and a character anchored on a three-quarter action pose loses whatever that
pose was hiding the first time the arms move.

Then `GeneratePoseFrameUseCase` hands that picture back to the model once per
frame, changing only the body. Identity comes from pixels rather than from a
description, which is the one thing a model cannot misremember. Every frame is
edited from the *reference*, never from the previous frame: chaining compounds
each generation's drift until frame eight is somebody else. A star, not a chain.

`PoseScript` holds the poses themselves as data. A walk cycle is contact,
passing, contact, passing — that has been true since before computers, and it is
not something a model should be improvising per run.

The cost shapes everything downstream. Forty generations is minutes and money,
so `PoseSheetPlanner` plans the sheet before a single pose exists: each frame
knows its cell from the moment it is asked for, which is what makes a run
resumable at frame thirty-one and lets one bad frame be redrawn on its own.
`PoseLibrary` writes every pose to disk as it arrives, at full size, so a set can
be re-packed at another frame size later without paying for anything twice.

`PoseSheetComposer` does the part that needs pixels: chroma key, measure, scale,
pack. The measuring pass is the point. Each pose arrives on its own canvas with
the figure at whatever size and height the model felt like, and dropping those
into cells as they arrive gives a character that pulses in size and bobs off the
floor — which reads as broken in a way the individual frames never hint at. So
the whole set is measured first, scaled by one factor, and hung from one
baseline.

The background is asked for as flat chroma green rather than as transparency.
Models answer a request for alpha by *drawing* the editor checkerboard at least
as often as they return a real alpha channel, and a drawn checkerboard is
unrecoverable; a flat colour is unambiguous to produce and trivial to key out.

## Why there is a skeleton

Prose is a poor way to specify a body. "Right leg forward with the heel
touching the ground, left arm swung forward" is unambiguous to a person and
merely suggestive to an image model — measured, an attack described that way
came back as a cross-body guard. A drawing of the pose is not suggestive.

So `MocapPoses` holds every pose as joint angles, `Skeleton` resolves them to
positions by ordinary forward kinematics, and `PoseGuideRenderer` draws the
result as a stick figure that rides alongside the character reference. Angles
rather than coordinates, because bone lengths are then fixed: no authored pose
can stretch a forearm, and a guide with wrong proportions teaches the model
wrong proportions. The prose instruction is still sent, generated from the same
skeleton, so the two can never disagree about what frame three of a walk is.

The second reason is the one that pays for it. A weapon is held in a hand and
points along a forearm, and a skeleton knows exactly where both are.
`WeaponPosing` used to author that arc separately and reconcile it by eye;
replaying the renderer's arithmetic over real art found the authored hand a full
hand's width outside the character. Now the anchor *is* the hand joint and the
rotation *is* the forearm direction, from the same data the model was given to
draw the pose from. The drawing and the sword cannot disagree, because there is
only one skeleton.

Two conventions meet here and the conversion lives in exactly one function.
Limbs are authored anticlockwise from straight down, because that is what reads
naturally when writing a pose — an arm out to the near side is +90. Weapon
rotation is clockwise from straight up, because that is what the renderer's
`rotate()` does to a weapon drawn pointing up. Opposite handedness from
different zeroes, so the conversion is a reflection rather than an offset.

Joints are named near and far rather than left and right. Everything is drawn at
one three-quarter camera, so one side is always closer to the viewer; naming
them that way means a pose never has to be rewritten when the character mirrors,
and the weapon stays in the hand the viewer can see.

## Why both pose sources stay

A pose library is a far larger and better-observed body of work than anything
authored here: real anatomy, taken from photographs, in quantity. What it cannot
know is this game's camera, its frame counts, or which hand holds the weapon.
So `PoseGuides` keeps three modes rather than picking a winner — the built-in
skeletons, imported OpenPose poses, and words alone, which is the only mode that
works with a provider accepting one input image and the mode every pose set
generated before guides existed was drawn under.

Imports fall back per frame, not wholesale. Filling a library set is a frame at
a time — someone finds a good wind-up and a good impact and has nothing for the
recovery — and a gap that disabled the guide would make a partly-imported set
worse than either pure source.

What ties it together is that the weapon rig reads the same object. A character
generated against an imported skeleton is rigged against that skeleton, which is
why `PoseGuideStore` keeps them per character: rigging against the built-in set
while the art followed an imported pose hangs the sword where the drawing did
not put the hand, which is precisely the bug the skeleton was introduced to
remove.

## What OpenPose support actually involves

Three separate things, because the format is three things in practice.

**JSON keypoints.** `OpenPoseJson` reads what the ecosystem emits, which is not
one schema but OpenPose's own output plus a dozen tools that each kept the parts
they needed — a `people` array, a bare `keypoints` array, coordinate pairs with
no confidence, pixels with a named canvas and pixels without one. Two layouts
are in circulation and they are incompatible: BODY_18 has a neck, BODY_17 does
not and must derive one from the shoulders. Read with the wrong layout a file
does not fail, it produces a person whose elbows are where their hips were.

**Rendered PNGs.** What a pose library actually hands you is the skeleton
already drawn, ready for a ControlNet preprocessor. That file is a perfectly
good guide as it is, and for generation nothing more is needed — but the weapon
needs to know where the hand is, so `OpenPoseImageReader` reads the picture
back. OpenPose draws each joint as an opaque disc in one of eighteen fixed
palette colours; finding the discs gives the keypoints back. Limbs share that
palette, so the largest *compact* blob is taken rather than the centroid of
everything matching — a disc fills most of its bounding box and a limb fills
almost none of one. Best-effort, and the result carries a confidence because a
limb lying along its own colour can still be misread.

**The canonical rendering.** `OpenPoseStyle` reproduces the palette and limb
order exactly, so a pose authored here drops into that ecosystem unchanged.
Interoperability that only reads is half a feature.

Two things about this were measured rather than assumed, and both changed the
design. A real library publishes its keypoints as `{x, y}` objects — a fourth
shape none of the documented readers handled, and one that would have been
rejected as not a pose at all. And it draws its skeleton previews in Material
blues and pinks rather than the canonical eighteen, so reading a downloaded PNG
back finds nothing. That is the right failure — the image still works as a guide
and only the weapon anchor is lost — but it makes pixel-reading the fallback
route and pasted keypoints the main one. Exact beats inferred.

The conversion that matters is the one nobody writes down: OpenPose names sides
from the subject, this engine names them from the camera. Which of the subject's
sides is nearer depends on which way they face, so it is a parameter rather than
an assumption — get it wrong and the sword is in the hand the viewer cannot see.

## What a guide does and does not control

Measured against a live model, with the same character and the same prompt text
and only the guide added. Pose *structure* is followed well: asked for an impact
— weight forward, arms extended — the unguided attempt returned a compact
cross-body guard, and the guided one returned a full lunge with the arm
extended and the body leaning into it. That is the whole case for the skeleton,
and it holds.

Handedness is not followed. The guide put the weapon arm on one side; the
drawing put it on the other. Mirroring the guide and asking again produced the
*same* handedness, so this is the model imposing its own rather than a coin
toss, and no phrasing of the prompt is going to argue it out of that.

So `WeaponFit` carries a mirror. A whole flip rather than a wider offset,
because it is one: the hand measured at 0.86 of the frame where the rig expected
0.2, and halfway between those is the character's navel. The rotation negates
with it, since a sword in the correct fist pointing the wrong way reads worse
than the original error did.

## Why weapons are not drawn on characters

A sword drawn into a character belongs to that character forever. It cannot be
dropped, cannot be swapped for a better one, and has to be drawn again for every
actor that carries one — which, in a game whose whole loop is picking up better
loot, is exactly backwards.

It was also a measured failure. A weapon held in a character's reference pose
*disappeared* the moment the pose changed: an image editor reads a held object
as part of the pose and drops it along with the old one. A weapon that was never
in the reference cannot be lost from it, so references are now drawn with empty
hands and every pose edit is told to keep them empty.

So `WeaponSprite` is one drawing that nobody owns, and `WeaponAnchor` says where
it sits for one frame — position as a fraction of the character's frame box,
rotation from vertical, and which side of the body it passes. `WeaponPosing`
holds the arcs: a wind-up goes back and up behind the shoulder, a strike comes
down and across the front, a corpse lets go halfway through its death. Authored
rather than derived, for the same reason the pose instructions are — finding a
hand in a drawing is a harder problem than this is worth, and would have to be
solved again for every character.

Two details carry it. The weapon rotates about its *grip*, not its centre,
because a sword turns in the hand and pivoting anywhere else swings the hilt out
of the fist every frame. And it is painted inside the same mirror transform as
the body, so a character facing the other way holds it in the other hand for
free.

The arcs cannot be authored accurately, and `WeaponFit` is the answer to that.
Replaying the renderer's arithmetic over real generated art showed the first set
of anchors putting the hand a full hand's width outside the character, and the
blade sized like a greatsword. The shape of a swing is universal — back and up,
down and across — but a figure's proportions are not, and neither is how large
it was drawn in its cell. So the arcs stay authored and each character supplies
three numbers: two offsets and a scale, tuned once against its own attack and
saved per sheet. Authoring twenty-four anchors per character is work nobody does
twice; deriving them means finding a hand in a drawing.

Weapons are the one piece of art deliberately **not** drawn at the game's camera
angle. They are drawn flat and upright, tip at the top, because they get rotated
through a swing: a blade foreshortened for the isometric view is correct at one
angle and wrong at every other one it gets turned to. Characters are drawn at
the camera because they never rotate; weapons rotate constantly.

## Why the camera is stated in degrees

`IsometricCamera` is the one description of the camera, because there were four
and the art showed it. "A three-quarter overhead angle" is not an instruction —
models read it as a licence to pick any flattering angle, and the one they pick
is a straight-on hero shot, which is the angle this game never shows. The
projection is 2:1, an elevation of about 30 degrees, and art drawn at any other
elevation sits wrong on the ground however good it is. So the camera is stated
in degrees, and then in consequences a model can act on: the tops of the
shoulders are visible, one side of the body is nearer than the other, the figure
faces the bottom-right corner.

## Why the renderer makes up the difference

Letting a rat's death borrow its idle keeps a dungeon shippable. On its own it
also ships a rat that stands still and then vanishes, which reads as a bug
rather than a death — worse than the missing animation it was meant to cover.

So a borrowed clip says so. `ClipMapping.borrowedFrom` survives baking as
`AnimationClip.standsInFor` and reaches the renderer, which asks
`ProceduralMotion` what to lay over the frame: a body that sags, spreads and
fades reads as dying even when every frame of it is the idle pose. The same
mechanism gives a held still its breath and a borrowed walk its step.

The guard is what keeps it honest: nothing is applied to a clip with real frames
behind it. A drawn attack does not want a procedural lunge fighting it, and a
game where both happen looks like it is made of rubber. The effect fires only
where there is nothing to fight — a clip standing in for another, or a single
frame held as an animation.

## Determinism

The world seed drives one `Random` for the whole session, so a run replays
exactly: the same seed produces the same terrain, the same spawns, the same crit
rolls and the same drops. `DamageCalculator` takes its crit roll as a parameter
rather than rolling internally, which is what lets a test pin a critical hit
instead of hoping for one.

## Conventions

Module build files stay declarative because `build-logic` carries the shared
configuration: `stratum.jvm`, `stratum.android.library` and
`stratum.android.compose`.

## Running it

```sh
./gradlew test                  # every module's unit tests
./gradlew architectureCheck     # boundary enforcement
./gradlew :app:assembleRelease  # the signed APK CI attaches to each pull request

# Import a Flame game or Tiled project and render its level, no device needed
./gradlew :tools:artpreview:importPreview --args="path/to/game build/import-preview [map]"
```

Release builds are always signed; see `SIGNING.md` for which key and how to
switch to a real upload key.

Screenshot tests render the shell, the play screen and the forge. Re-record them
after an intentional visual change:

```sh
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true
```
