# The cozy builder

Building, digging and making things should feel good: bold because nothing
is permanent, fine-grained because the world is made of quarter-block
microvoxels, and open-ended because the generator and the player share the
same tools. This covers what landed together:

1. [Calm means calm](#calm-means-calm)
2. [Build tools](#build-tools)
3. [Sculpting and placing models](#sculpting-and-placing-models)
4. [The model studio](#the-model-studio)
5. [Pictures into voxels](#pictures-into-voxels)
6. [Parametric buildings and vernacular towns](#parametric-buildings-and-vernacular-towns)
7. [Describing a scene](#describing-a-scene)

## Calm means calm

The monster dial (`WorldRules.withMonsters`) now sets how many monsters wait,
how far they notice the player (`enemyAlertness`) and how hard they hit
(`enemyDamage`). *Calm* (0.6) thins spawn points to 60%, shrinks aggro
ranges and softens hits to 60%.

The root cause of calm worlds killing new heroes was separate. Ordinary spawn
markers picked any monster of their region at random, bosses included. So a
level-1 hero met the Agbara priest, with attack 50, a minute in. Spawn points
now pick by spawn weight and never pick a boss; bosses wait at boss markers.
`CalmWorldTest` wanders a passive hero for three minutes to guard this: in a
calm world it must never die, and it must take less damage than in a normal one.

Old saves derive the new fields from their monster density, so a calm save is
calm when loaded.

## Build tools

Drag-to-build tools in the build tray (`BuildTool`):

| Tool | What a drag makes |
| --- | --- |
| Block, Line, Floor | as before |
| Walls, Room | perimeter, or a shell with a doorway, *height* high |
| Fill | a solid box, *height* high |
| Pillar | a column from a tap, *height* high |
| Stairs | a flight up the drag's long axis, as wide as the drag is across, solid underneath |
| Roof | a gable over the rectangle, with closed gable ends |
| Dome | a hemispherical shell over the rectangle |
| Round | an elliptical wall: a tower, a kraal, a round hut |
| Paint | swaps blocks already there for the selected one and hands back what it replaced |
| Erase | removes what the drag covers and hands it back |
| Dig | excavates *height* deep from the ground the drag started on: cellars, ponds, moats |

- **Height** (−/+ in the tray) sets how tall walls, rooms, fills, pillars and
  round walls rise, and how deep a dig goes (1 to 16).
- **Undo and redo** cover every build, erase, paint, sculpt and placed
  model, 40 steps deep. The bag is settled along the way: blocks taken off
  come back to it, and blocks put back are paid for. A cell the player can no
  longer pay for stays as it is, so undo never makes blocks from nothing.
- **The eyedropper** (*Pick block*) turns a tap into "build with this". It
  selects that block when the player holds one and adds it to the hotbar if
  it is only in the bag.
- A build that runs short lays its lowest cells first, so it leaves
  foundations rather than a floating roof.

## Sculpting and placing models

In a microvoxel world the build tray also offers **Chisel** and **Heap**. A
tap carves a rounded hollow into the land, or heaps material onto it, with a
brush 1 to 12 quarter-blocks in radius. Whatever the chisel breaks goes into
the bag. Heaps take a colour from the tray: earths, stones, sand, grass and snow.

Models from the model studio appear in the tray as well. A tap sets one on the
ground in front of the player; ⟳ turns it a quarter each tap.

Under the hood these are **stamps** (`MicroStamp`): a model and where its low
corner lands, its turn and mirror, and whether it carves.

- Stamps are *generation*, not edits. The generator lays them over each micro
  chunk as it makes it (`StampLayer`), so a statue is drawn in full microvoxel
  detail. Its blocks read back exactly as the land's do, and collision, pathing
  and saves all agree.
- After a stamp, the session brings the block world into line over the
  stamp's box. A cell the player never changed takes the new generated
  block. A cell they built on stays theirs, unless the stamp carves it away.
- A save keeps the stamps and the models they name (`WorldSave.stamps` and
  `WorldSave.microModels`), so a statue outlives its library entry. Retuning
  the land in the World panel keeps every stamp standing, because
  `HotTerrain` hands the same layer to each generator it builds.

Colours are free. Every palette carries a fixed cube of paints (8 levels per
channel plus 16 greys, see `Paints`), and a `#RRGGBB` lands on its nearest
paint. The palette therefore never changes under a renderer. At block scale
a paint reads as the nearest sturdy stone block.

## The model studio

*Create → Model studio.* You make microvoxel assets here, a quarter block at a time:

- **A layer grid** to draw on, with the layer below showing through like
  tracing paper and block edges marked every four cells. Tools: Pencil,
  Erase, Fill (flood within the layer), Line and Box (tap both ends; change
  layer between taps to span layers), Ball (a sphere of any radius), and Pick.
  X and Y mirroring apply to every tool.
- **A turning isometric preview** (`MicroModelRenderer`) of the whole model.
- **Shape tools:** turn, flip, trim to content, ×2 and ½ scale, weather
  (colour noise for stone and earth that is not a flat fill), hollow, and
  grow the box.
- **Three starts:** a blank box, a building rolled from the world's own
  generator (any tradition, or one named), or a picture.
- Keep it; it appears in the play screen's build tray. Undo holds 60 steps.

Models are data (`MicroModel`: sizes, a palette of `#RRGGBB` or material
names, and cells). They are stored one JSON file each, with run-length coded
cells (`MicroModelCodec`), under `files/micro-models`.

## Pictures into voxels

`ImageVoxelizer` turns one view of a thing, such as an image model's output,
a photo or a sketch, into a model. The background is keyed out by flooding in
from the border through pixels near the border's colour. A white shirt on a
white ground survives, because the flood never reaches it. The colours are
then reduced to at most 48.

| Mode | Reading |
| --- | --- |
| Inflate (default) | puffed out from the silhouette: thickest far from the outline, like a pillow or a statue |
| Extrude | cut out at an even thickness: signs, shields, doors, murals |
| Relief | a plaque whose surface stands out by brightness |
| Heightmap | laid flat, brightness as height: a garden, a rocky patch |
| Two view | a front and a side view carved into each other (the visual hull) |
| Depth | a front view plus a depth map from a depth model |

For best results, ask the image model for *one object, centred, on a plain
background*.

## Parametric buildings and vernacular towns

`BuildingGenome` is one building's dials:

- plan: rect, L, T, U, courtyard, round, octagon, cross, stepped
- upper storeys and storey height
- material family and exact wall, accent, roof and trim materials
- wall pattern: bands, plinth, quoins, checker, zigzag, diamond, pilasters, frieze
- window form and spacing
- roof: gable, hip, flat, dome, cone, pyramid, barrel, mansard, sawtooth, onion, terrace
- pitch, eaves and plinth
- veranda, towers and their caps, chimney, balconies, jutting beams,
  pinnacles, jetties, roof gardens, finials and setbacks

Their product is well over 10^15 distinct buildings (`BuildingGenome.VARIANTS`).
One painter, `ParametricTradition`, draws every one of them. It keeps the same
promise as the traditions: on the ground floor the wall ring reads as the
plan's wall, the door is open two blocks high, and the window cells are the
plan's windows. A plan that would cut the door falls back to the rectangle. Hip,
pyramid, cone, mansard, dome and onion roofs are fitted to *any* outline from
its distance field, so an L-plan gets a hipped L roof and a round plan gets a cone.

A town shares a look. Each dial first takes the town's choice, and a building
strays from it by `variety`.

**Vernacular, not generic.** A regional town that invents its look (the
`parametric` share of `micro:settlements`, 35% by default) builds within its
land's own grammar (`Vernacular`). Every tradition has its own set of plans,
roofs, storeys, wall dress, openings, ornament and exact materials:

- Kano: courtyards, zanko-horned flat roofs and zayyana relief. Never a gable.
- Djenné: pilasters, pinnacles and toron beams.
- Lamu: storeyed coral-stone houses with terraces and carved doors.
- Great Zimbabwe: round dry-stone buildings with chevron courses and conical towers.
- The Draa: storeyed pisé kasbahs with corner towers.

These towns keep their tradition's compound wall and sacred heart. Across the
map, each town still takes the tradition of the geological province it
stands on (`Traditions.native`). A walk across the continent reads as its
history does, with no two streets alike.

Town-stage options (the World panel shows each of them):

| Option | Values |
| --- | --- |
| `style` | `regional`, `parametric`, `plain`, or a tradition id |
| `homeStyle` | `auto`, `parametric`, a tradition id, or `parametric:<tradition>` |
| `parametric` | share of invented towns in regional mode, 0 to 1 |
| `plans`, `roofs`, `materials` | `any` or a comma list |
| `storeys` | a number or a range, such as `2` or `1-3` |
| `ornament`, `towers`, `variety` | 0 to 1 |

Preview: `./gradlew :tools:artpreview:microScenePreview --args="<out> <forge> <seed> parametric [looks...]"`.
The looks are `any`, `lime-domes`, `stone-hall`, `painted-round`, `brick-mill`,
`earth-courts`, `timber-town`, and `vernacular-<tradition>` for each tradition.

## Describing a scene

A world can be described in words, when making it or later from the World
panel ("Describe a scene"). `ScenePrompt` reads the words into a `SceneSpec`:
every dial as a named, bounded parameter. The new-world screen shows a line
for each choice, so a player sees how their words were read.

- **Without a model**, a lexicon reads places, peoples, climates and moods the
  way a geographer and a historian would.
  - "Sahara" is the erg.
  - "Djenné" is Sudano-Sahelian mud on the Sahel.
  - "Lamu" is Swahili coral stone on the coast.
  - "the whole continent, starting in the Ethiopian highlands" is Africa's
    full geology, with home in the traps.
  - "peaceful" is the calm monster dial.
  - "walled", "domed", "towers", "ornate" and "courtyards" shape the towns.
- **With a model** ("✨ Read the scene with AI"), the language model is given
  `ScenePrompt.systemPrompt()`, which lists every key, its range and its words.
  It answers with one JSON object. `ScenePrompt.parse` reads the reply
  leniently: values are clamped, and unknown keys and values are dropped with
  a note, never an error. The model's values win over the lexicon's.

Keys: `landscape`, `home`, `relief`, `mountains`, `breadth`, `plateaus`,
`vegetation`, `trees`, `grass`, `tallGrass`, `forest`, `caves`, `features`,
`towns`, `style`, `homeStyle`, `invented`, `homeSize`, `walls`, `plans`,
`roofs`, `materials`, `storeys`, `ornament`, `towers`, `variety`, `cities`,
`monsters`, and `why`.

A scene becomes the world's terrain stages (`WorldConfig.terrainPasses`) and
its danger (`WorldRules.withMonsters`), and both are saved with the world.
