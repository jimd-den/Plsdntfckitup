# Microvoxel Realms — a world made of quarter blocks

Switches the world generator to `stratum:microvoxel` and nothing else: the
land is generated at a quarter block, then played on the built-in pack's
blocks, creatures, factions and towns. With the built-in pack that is an
ancient West African world: red laterite and grove turf from the pack's own
regions, iroko, oil palm and baobab, elephant grass, and the pack's towns --
the home town where every hero starts among them -- built as walled
compounds of mud and thatch, with uli zigzags in white nzu, round huts under
conical thatch and a sacred iroko in the square. Near the hero the game draws the
quarter-block detail (smooth slopes, kerbs, sills, leaves); further out it
draws blocks.

Everything is in the `terrain` section of `pack.json`:

| Key | What it does |
| --- | --- |
| `generator` | `stratum:microvoxel` picks this generator. |
| `options.spawns` | Monster spawn markers per 8 x 8 blocks of wild land, 0..1. |
| `options.preset` | Used when `passes` is empty: `micro:ancient` (default: the packs' soils and towns), `micro:arpg`, `micro:wilds`, `micro:mixed`, `micro:city`. |
| `options.strata` | `builtin` to use the engine's grass, dirt and rock instead of the packs' region blocks. |
| `options.block.<material>` | Pins a material to a block, e.g. `"block.asphalt": "igbo:granite"`. Otherwise each material takes the pack block of the right kind with the nearest colour. |
| `passes` | The stages, in order, each with string options. |

| Stage | Options |
| --- | --- |
| `micro:terrain` | `height`, `mountains` (0..2), `scale`, `terrace` (plateau steps), `seaLevel`, `maxHeight`, `snowLine`, `spawnRadius`, `spawnRise`, `sampleStep` |
| `micro:settlements` | `style` (`earthen`: mud, uli, thatch, round huts, compound walls; `plain`: the block buildings, bevelled) -- the packs' towns, the home town first |
| `micro:caves` | `threshold`, `minDepth` |
| `micro:city_plan` | `density` (0..1), `regionSize`, `styles` (`terrace,villa,tower`), `maxFloors` |
| `micro:roads` | `lampSpacing` |
| `micro:buildings` | — |
| `micro:groundcover` | `density` (0..2), `tall` (0..1, elephant grass) |
| `micro:trees` | `style` (`temperate` or `tropical`: iroko, oil palm, baobab), `cell`, `density` (0..2) |

Heights are in microvoxels, four to a block. The world is 48 blocks tall, so
the generator keeps the land under 40 unless told otherwise.

This is also what the studio's AI writes when asked for a new kind of
world: a `terrain` section like this one.

## Ships inside the APK

The build zips this folder into `assets/plugins/microvoxel-realms.stratum`
(the `bundlePlugins` task in `app/build.gradle.kts`). On first launch it is
installed like any downloaded plugin. The player can switch it off or
uninstall it, and a newer build's copy replaces the old one. In a world it
makes, open **⛰ World** in the menu to reshape the land and the home town
while you play.
