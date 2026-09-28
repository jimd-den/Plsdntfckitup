# Microvoxel Realms — a world made of quarter blocks

Switches the world generator to `stratum:microvoxel` and nothing else: the
land is generated at a quarter block, then played on the built-in pack's
blocks, creatures, factions and towns. Near the hero the game draws the
quarter-block detail (smooth slopes, kerbs, sills, leaves); further out it
draws blocks.

Everything is in the `terrain` section of `pack.json`:

| Key | What it does |
| --- | --- |
| `generator` | `stratum:microvoxel` picks this generator. |
| `options.spawns` | Monster spawn markers per 8 x 8 blocks of wild land, 0..1. |
| `options.preset` | Used when `passes` is empty: `micro:arpg` (default), `micro:wilds`, `micro:mixed`, `micro:city`. |
| `options.block.<material>` | Pins a material to a block, e.g. `"block.asphalt": "igbo:granite"`. Otherwise each material takes the pack block of the right kind with the nearest colour. |
| `passes` | The stages, in order, each with string options. |

| Stage | Options |
| --- | --- |
| `micro:terrain` | `height`, `mountains` (0..2), `scale`, `seaLevel`, `maxHeight`, `snowLine`, `spawnRadius`, `spawnRise`, `sampleStep` |
| `micro:caves` | `threshold`, `minDepth` |
| `micro:city_plan` | `density` (0..1), `regionSize`, `styles` (`terrace,villa,tower`), `maxFloors` |
| `micro:roads` | `lampSpacing` |
| `micro:buildings` | — |
| `micro:groundcover` | `density` (0..2) |
| `micro:trees` | `cell`, `density` (0..2) |

Heights are in microvoxels, four to a block. The world is 48 blocks tall, so
the generator keeps the land under 40 unless told otherwise.

This is also what the studio's AI writes when asked for a new kind of
world: a `terrain` section like this one.
