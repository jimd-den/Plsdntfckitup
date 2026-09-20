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
```

Dependencies point inward only. Nothing in `:core:domain` knows that Android,
Room, OkHttp or Compose exist.

## The modules

| Module | Kind | Holds |
| --- | --- | --- |
| `:core:domain` | Pure Kotlin | The voxel model, content packs, combat and itemisation, enemies, skills, progression, player state, the AI ports and the generation use cases. |
| `:engine:world` | Pure Kotlin | Terrain generation, chunk streaming, mining and building rules, isometric projection, combat, loot rolling, the monster director, and the play session that joins them. |
| `:content:igbo` | Pure Kotlin | The built-in content pack. Data only. |
| `:core:data` | Android library | Adapters: the OpenAI-compatible model client and provider settings. |
| `:core:designsystem` | Android library | The visual language, driven entirely by the loaded pack's palette. |
| `:feature:play` | Android library | The isometric renderer and the play screen. |
| `:feature:forge` | Android library | AI pack generation and its preview. |
| `:legacy:domain` | Pure Kotlin | The original engine's rules, pending port. |
| `:legacy:data` | Android library | The original engine's Room and network layer. |
| `:feature:studio` | Android library | The original creator studio screens. |
| `:app` | Android app | The Activity, navigation between destinations, and the wiring that connects ports to adapters. |

## How the boundary is enforced

Not by review. `:core:domain`, `:engine:world`, `:content:igbo` and
`:legacy:domain` apply only the Kotlin JVM plugin, so the Android SDK is not on
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
```

Release builds are always signed; see `SIGNING.md` for which key and how to
switch to a real upload key.

Screenshot tests render the shell, the play screen and the forge. Re-record them
after an intentional visual change:

```sh
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true
```
