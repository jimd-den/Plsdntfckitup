# Spirit masks: broken open (opt-in)

> Masks fly **whole** by default (see [MASK-CARVING.md](MASK-CARVING.md)).
> Everything here is an optional mode: `MaskSculptor.shatter` and
> `MaskCharacters.heroBroken = true`. It is kept for effects such as a
> boss's death or a transformation, not as the masks' normal look.

A floating mask in the game is not one rigid mesh glued from primitives. It is
carved as one block, broken along the lines its own forms draw, and flown as
pieces held round the spirit that broke it. The same pieces are its animation
rig: even a mask that looks whole breathes, parts its jaw, lifts its brows and
looks about with its eyes.


## The method

1. **Grammar.** A tradition (`MaskCulture.traditions`, 19 of them) says which
   forms its carvers use and how often: outline, eyes, nose, mouth, marks,
   coiffure, crown, finish, pigments. Rolling it gives a `MaskSpec`. Its
   spirit grammar says what the spirit is for (`Spirit.Role`), how it moves
   (`Spirit.Temper`), how it tends to break (`Spirit.Fracture`), what burns
   inside (`Spirit.Core`) and what drifts round it (`Spirit.Relic`). Rolling
   that gives a `SpiritSpec`.
2. **Carving.** `MaskSculptor` carves the spec as one signed distance field.
   The big masses of the face (eye sockets, cheeks, muzzle, chin, a domed
   brow, tube eyes) join with a polynomial smooth minimum, so they flow into
   each other as a carver roughs them out; the detail (lids, lips, ridges,
   teeth, incisions) keeps chisel-cut chamfers. Ichi are cut into the head
   itself, spaced evenly round its curve, so they flow over the temples.
3. **Breaking.** `FractureCells` places anchors on the face's landmarks (each
   brow, each cheek, the bridge, the jaw, the crest) for the chosen fracture,
   and splits space into Voronoi cells under an anisotropic metric that weighs
   distance across the face more than distance down it: the walls stand with
   the grain. One shared warp makes the splits zigzag long and sharp down the
   grain and short and ragged across it. The gaps between cells are one more
   cut in the field, so the mask falls into pieces **in the same meshing pass**
   that carves it.
4. **Colouring.** The whole carving is coloured as one piece (finish, wear,
   grain, patina, paint), so paint and wear run unbroken across the breaks.
   The split faces are raw heartwood, lit faintly by the core.
5. **Splitting.** Each vertex goes to its cell; swept parts go whole (each
   horn becomes a relic of its own, the raffia one hanging shroud). Every
   piece is built about its own centre and given a rest place out from the
   core and a drift of its own. Relics are added in a ring round the head.
6. **Flying.** `ShardRig` (one per spirit on screen, allocation-free) puts
   every piece on a damped spring in the world, plus:
   - an idle Lissajous drift, different for every piece;
   - **the breath**: in slowly, a pause, out a little faster; the pieces
     swell out from the core and back, the jaw parts, brows and crest lift,
     the core and the eyes brighten with it;
   - **the eyes**: their light rides the breath and surges with a strike or a
     cast; they blink every few seconds; a hot glint in each looks about in
     quick saccades and locks forward while the spirit strikes or casts;
   - **inertia**: moving, the pieces trail by a fraction of a second of the
     spirit's velocity; stopping, they snap back into the face;
   - **reactions**: a strike or a cast bursts them out (brows up, jaw down,
     crest up, halves apart); a blow kicks them; death throws them apart,
     tumbling, and puts out the core.

   `SpiritStage` draws the core, the pieces, beaded tethers of light from the
   core to each piece, and for storm spirits arcs that leap as they strike.

Drift 0 keeps the mask whole: its cracks become hairlines of light and it
only breathes. That is the version of an exact carved mask that comes alive.


## Temperaments

| Temper | Traditions | How it moves |
|---|---|---|
| Austere | Ngil, Deangle, Okuyi, Kpeliye'e | Stiff, overdamped springs; almost no sway; a slow, shallow breath; eyes rarely glance |
| Storm | Mgbedike, Kifwebe, Bugle | Snappy, bouncing springs; crackling jitter; wide flare on a strike; arcs; quick breath, darting eyes |
| Monumental | Ijele, Gelede, Mwaash aMbooy, Ikenga, Nwantantay, Kanaga, Ogbodo Enyi | Soft, heavy springs that sway; long, deep breath; relics swing round |
| Breathing | Agbogho Mmuo, Mwana Pwo, Ngady aMwaash | A solemn swell; moderate springs |
| Restless | Okoroshi, Okumkpa | Never still; loose springs; quick glances |

## How many

19 traditions, each with dozens of named choices and 36 continuous dials,
times 6 fractures, 8 cores, 64 combinations of relics, 5 temperaments, and
continuous drift, piece count and splintering: the spirit layer alone
multiplies every carved design by more than 15,000, before its dials.

Pictures render with `-Ponly=spirits` (below); they are not kept in the repo.

## A note on the cultures

The traditions are distinct systems (theology, law, ancestral communion,
theatre), not one "African mask" style, and the generator keeps them apart:
each tradition's grammar and spirit come from what its masks are for.
Descriptions are kept to what is well documented. The Nsibidi-style signs are
composed from that script's stroke vocabulary (rings, crossings, ladders,
paired arcs) and do not copy or claim the meaning of real glyphs. The cores
are named by what they look like; the Igbo storm spirits' thunder recalls
Amadioha, but the generator does not put one people's deity in another's mask.

## Regenerating

    ./gradlew :tools:artpreview:maskSpiritPreview -Pout=docs/screenshots/spirit-masks -Ponly=spirits
    # faster, at play's detail, one section:
    ./gradlew :tools:artpreview:maskSpiritPreview -Pout=build/spirits -Ponly=spirits-quick:breathing

In code:

```kotlin
val spec = MaskCulture.generate(MaskCulture.tradition("agbogho_mmuo"), seed)
val spirit = MaskCulture.spiritOf(spec)            // or .copy(drift = 0f) to breathe whole
val broken = MaskSculptor.cachedShatter(spec, spirit)
cast.track("hero", broken, profile, x, y, z, fx, fy) // MaskCast springs its rig every frame
```

`MaskCharacters.heroCarved` flies a carved hero this way when
`heroBroken` is set (`heroSpirit` chooses how it breaks).
