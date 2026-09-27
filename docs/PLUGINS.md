# Writing Stratum plugins

A plugin is how anything gets into Stratum that the engine does not ship:
a region, a class, a monster, a hand-made level, a whole pen-and-paper
system. Plugins are data, not code: two JSON files and some PNGs in a zip.
You need a text editor and, if you want art, an image editor.

The fastest start is to copy [`examples/plugins/nri-chronicles`](../examples/plugins/nri-chronicles)
and change it.

## The file

A `.stratum` file is a zip:

```
plugin.json               who made it, its version, license and dependencies
pack.json                 the content
art/textures/<key>.png    textures, one per texture key
art/sheets/<sheet>.png    one image per sprite sheet declared in pack.json
```

Check a plugin folder and pack it:

```sh
./gradlew :tools:artpreview:packPlugin --args="path/to/my-plugin build/my-plugin.stratum"
```

The packer loads your plugin exactly as the game will: on top of the
built-in pack, with every reference checked. A plugin that would not load is
not packed, and the message names the field that is wrong.

Players install it from **Plugins and games** on the home screen, or it can
be shared from one phone to another with any app that sends files.

## plugin.json

```json
{
  "id": "yourname.campaign",
  "name": "My Campaign",
  "version": "1.2.0",
  "author": "Your Name",
  "license": "CC-BY-4.0",
  "description": "What it adds, in a sentence.",
  "homepage": "https://example.com",
  "api": 1,
  "dependencies": [
    { "id": "igbo", "version": "^2.0" },
    { "id": "someone.monsters", "version": ">=1.1", "optional": true }
  ]
}
```

| Field | Meaning |
| --- | --- |
| `id` | Stable and unique. `pack.json` must use the same id. |
| `version` | `major.minor.patch`. Raise the major number when you remove or rename things other plugins may depend on. |
| `license` | An [SPDX identifier](https://spdx.org/licenses/): `CC-BY-4.0`, `CC-BY-SA-4.0`, `MIT`, `CC0-1.0`... Say what others may do with your work. |
| `api` | The plugin API level you wrote for. This build is level **1**. A plugin for a newer level is refused with a message to update the game, never loaded and misread. |
| `dependencies` | Other plugins yours builds on. `version` is `*`, an exact `1.2.0`, `>=1.2`, `^1.2` (same major version) or `~1.2` (same minor). `igbo` is the built-in pack. |

### Load order

Players choose the order; later plugins override earlier ones where they
define the same id. The one exception: a plugin always loads after the
plugins it depends on. A plugin whose dependency is missing, switched off,
the wrong version, or itself refused does not load, and the player is told
why. It never half-loads, and it never stops other plugins from loading.

## pack.json

Every section is optional, and every field has a default except ids and
names, so a pack that only adds three monsters is three monsters long.
Colours are `#AARRGGBB` (or `#RRGGBB`); named options are case-insensitive.
Ids are namespaced, `yourname:thing`, and can refer to anything already
loaded, including the built-in pack's `igbo:` content.

| Section | What it holds |
| --- | --- |
| `blocks` | `id`, `name`, `material` (soil, stone, ore, wood, foliage, liquid, cloth, metal, ritual), `hardness`, `requiredTier`, `solid`, `opaque`, `gravity`, `light` (0-15), `drop`, `glyph` (set it to make the block scenery drawn as a sprite), `topColor`, `sideColor`, `shape` (cube, wall, floor). |
| `biomes` | `id`, `name`, `surface`, `subsurface`, `filler` block ids, `heightBias`, `roughness`, `scatter` (`block`, `chance`, `height`, `cap`), `deposits` (`block`, `minZ`, `maxZ`, `chance`), `path`, `landmark`. |
| `terrain` | How the world is shaped: `generator` (`stratum:overworld`, `stratum:islands`, `stratum:caverns`/`stratum:underworld`, `stratum:flat`, `stratum:layered`, or `stratum:tilemap` with `options.map` naming a map), `elevation` noise layers, `terraceStep`, `strata`, and for the staged generators `passes`, `climate`, `carvers`, `ores`, `trees`, `liquids`; see *World generation*. |
| `structureTemplates` | Dungeons, ruins and shrines the world generator builds; see *World generation*. |
| `maps` | Hand-made levels: `width`, `height`, `ground`, `groundLevel`, `layers` (`palette` of block ids and one `cells` index per cell, `-1` empty; `elevation`, `thickness`), `markers` (`kind`: player_spawn, enemy_spawn, point_of_interest; `x`, `y`, `ref`). |
| `classes` | `id`, `name`, `title`, `strength`, `agility`, `insight`, `abilities` (skill ids), `startingWeapon`, `resourceName`, `spriteSet`, `stats`. |
| `damageTypes` | `id`, `name`, `color`, `symbol`. Every weapon, skill and monster must use a damage type some loaded pack defines. |
| `weapons` | `id`, `name`, `damageType`, `minDamage`, `maxDamage`, `attackSpeed`, `attackRange`, `toolTier`, `minItemLevel`. |
| `affixes`, `inserts` | Item modifiers and socketable runes: `stat` is one of attack_power, max_health, armour, crit_chance, crit_multiplier, attack_speed, resistance, life_steal, mining_speed. |
| `enemies` | `id`, `name`, `rank` (minion, elite, champion...), `damageType`, `stats`, `moveSpeed`, `aggroRange`, `experience`, `spawnBiomes`, `spawnWeight`, `spriteSet`. |
| `skills` | `id`, `name`, `damageType`, `powerMultiplier`, `resourceCost`, `cooldownSeconds`, `shape` (strike, nova, lance), `range`. |
| `lore` | `id`, `title`, `body`, `category` (history, deity, artifact, bestiary, place, ritual), `subject`. |
| `sheets` | Sprite sheet layouts: `id`, `columns`, `rows`, `frameWidth`, `frameHeight`, `clips` (`state`: idle, walk, attack, special, hurt, roll, die; `first`, `count`, `frameMs`, `loops`). The image goes in `art/sheets/`. |
| `checks` | Tabletop rules; see below. |
| `palette`, `rarities` | Interface colours, and what item rarities are called. |
| `factions`, `enemyPacks`, `settlements` | Sides, warbands and towns; see *Worlds with their own lore*. |
| `needs`, `consumables`, `recipes`, `forage` | Survival. |
| `resources`, `structures`, `units` | Outposts and the soldiers they train. |
| `rules`, `agents` | How its worlds should be played, and the studio crew that writes more of it. |

`stats` blocks take `maxHealth`, `attackPower`, `armour`, `critChance`,
`critMultiplier`, `attackSpeed`, `attackRange`, `resistances` (damage type
id to a share, e.g. `0.5`) and `lifeSteal`.

## World generation

The staged generators -- `stratum:overworld` (hills, climate-placed
biomes, caves, tunnels, ores, dungeons), `stratum:islands` (an
archipelago), `stratum:caverns` or `stratum:underworld` (a thin crust over
stacked cavern layers, Terraria-style) and `stratum:flat` -- build each
chunk in **passes**, in order. `stratum:layered` and `stratum:tilemap`
work exactly as they always have, and ignore everything in this section.

| Pass | What it does | Options |
| --- | --- | --- |
| `stratum:climate` | Picks every column's biome by heat and wet, and blends heights across borders. | `scale`, `blend` |
| `stratum:hills` | Rolling ground from `elevation`, biome `heightBias` and `roughness`. | `base`, `variation`, `topMargin` |
| `stratum:island_shape` | Islands and sea floor. | `landShare`, `islandScale`, `oceanDepth`, `shoreSlope`, `variation` |
| `stratum:flat_shape` | Level ground. | `level` |
| `stratum:surface` | Bedrock, filler, `strata`, subsurface and surface blocks; underground biomes. | |
| `stratum:carvers` | Runs `carvers`, or the preset's own when there are none. | `defaults` (`overworld`, `caverns`, `none`) |
| `stratum:liquids` | Fills air with `liquids`. | |
| `stratum:ores` | Each biome's `deposits`, then `ores` veins. | `biomeDeposits` |
| `stratum:decoration` | Paths, landmarks and scatter, as the layered generator draws them. | `paths`, `landmarks`, `scatter` |
| `stratum:trees` | `trees`, whose canopies spill across chunks. | |
| `stratum:structures` | `structureTemplates`. | `only` (comma-separated ids) |
| `stratum:settlements` | Towns, as a pass, so they can be ordered among the others. Left out of the presets: towns are otherwise laid over the finished world. | |
| `stratum:spawns` | Marks cave floors where monsters wait. | `perChunk`, `refId` |

A recipe that lists `passes` replaces its preset's list outright, which is
how a pack reorders, drops, adds or configures a stage. Each entry is an
`id` and string `options`. A pass reads only what passes before it
published, so put climate before shape and shape before everything else.
A pass or carver this build does not know is an error naming the ones it
does. Passes are also registered in code:
`StratumWorldgen.passes.register("mypack:lava_lakes") { setup -> ... }`.

```json
"terrain": {
  "generator": "stratum:overworld",
  "climate": { "points": [
    { "biome": "yourname:tundra", "temperature": 0.1, "moisture": 0.4 },
    { "biome": "yourname:jungle", "temperature": 0.9, "moisture": 0.9 },
    { "biome": "yourname:crystal_deep", "temperature": 0.5, "moisture": 0.5, "minDepth": 12, "maxDepth": 30 } ],
    "scale": 0.005, "blend": 0.15 },
  "carvers": [
    { "kind": "caves", "minZ": 2, "maxZ": 40, "amount": 0.66, "size": 2.4, "headroom": 4 },
    { "kind": "tunnels", "minZ": 3, "maxZ": 18, "amount": 0.8, "size": 1.6 },
    { "kind": "ravines", "minZ": 6, "maxZ": 30, "amount": 0.1, "size": 1.5, "options": { "depth": "8" } } ],
  "ores": [{ "block": "yourname:silver", "minZ": 2, "maxZ": 10, "veinsPerChunk": 2, "veinSize": 8, "biomes": ["yourname:tundra"] }],
  "trees": [{ "trunk": "yourname:log", "leaves": "yourname:leaves", "chance": 0.02, "minHeight": 3, "maxHeight": 5, "canopyRadius": 2 }],
  "liquids": [{ "block": "yourname:water", "maxZ": 12, "target": "open" },
              { "block": "yourname:lava", "maxZ": 4, "target": "caves", "share": 0.6 }]
}
```

**Climate.** Each biome sits at a `temperature` and `moisture` (0 to 1),
and each column is the biome nearest the climate there. Neighbours in
climate are neighbours on the map, and heights blend between biomes within
`blend` of each other, so a border is a slope and not a wall. A biome with
no point keeps its own `temperature` and gets an even share of moisture.
A point with `minDepth` and `maxDepth` is an underground biome: its
`filler` replaces the rock in that band below the surface.

**Carvers** are `caves` (3D noise; `amount` is the threshold, higher is
less cave), `caverns` (the same, fading out at the band's edges so layers
stack with rock between them), `tunnels` and `ravines` (winding paths;
`amount` is how many start per 64-block region, `size` the radius, options
`length`, `depth`, `open`). `headroom` keeps that many blocks of ground
under the surface. **Liquids** fill air up to `maxZ`: `open` above the
ground (a sea, which is what the islands generator expects), `caves` below
it by region (`share` of regions flooded), or `all`.

**Structures** are dungeons or sets of pieces. `placement` says where:
`biomes`, `spacing` (one per cell that many blocks across), `chance` (the
rarity), `anchor` (`surface`, or `underground` between `minZ` and `maxZ`).
Structures keep out of towns, landmark clearings, the sea and each other.

A `dungeon` is rooms joined by corridors under the ground, with a stair
down from the surface: `floor`, `wall`, `ceiling`, `stair`, `light`
blocks; `minRooms`, `maxRooms`, `minRoomSize`, `maxRoomSize`,
`roomHeight`, `depth` below the entrance, `extent`, `corridorWidth`;
`spawnsPerRoom`, `lootChance`, `bossRoom`; `enemies` and `boss` (enemy
ids named on its markers) and `loot` (a name for the loot layer).

`pieces` are small block templates joined at `connectors`, up to
`maxPieces`. `layers` are rows of characters, bottom layer first; layer 0
replaces the ground. Each character is a `palette` entry -- a block id,
or a marker: `@enemy`, `@boss`, `@loot`, `@poi`, `@entrance`, optionally
with a reference (`@boss:yourname:lord`). A space leaves the world alone
and `.` is air. A connector is a `side` (north is up the rows) and an
`offset` along it; a piece joins another where their connectors face.
Pieces are never rotated. `foundation` fills under a surface structure
where the ground falls away.

```json
"structureTemplates": [{
  "id": "yourname:crypt", "name": "Crypt",
  "placement": { "biomes": ["yourname:tundra"], "spacing": 160, "chance": 0.5 },
  "dungeon": { "floor": "yourname:tile", "wall": "yourname:brick", "light": "yourname:torch",
    "enemies": ["yourname:ghoul"], "boss": "yourname:lich", "loot": "yourname:crypt_hoard" }
}, {
  "id": "yourname:shrine", "name": "Shrine", "maxPieces": 3, "foundation": "yourname:stone",
  "pieces": [
    { "id": "yourname:shrine_core", "start": true,
      "palette": { "#": "yourname:brick", "_": "yourname:tile", "L": "@loot" },
      "layers": [["#####", "#___#", "#____", "#___#", "#####"], ["#   #", "     ", "  L  ", "     ", "#   #"]],
      "connectors": [{ "side": "east", "offset": 2 }] },
    { "id": "yourname:shrine_wing",
      "palette": { "#": "yourname:brick", "_": "yourname:tile", "E": "@enemy" },
      "layers": [["###", "__#", "###"], ["   ", " E ", "   "]],
      "connectors": [{ "side": "west", "offset": 1 }] } ]
}]
```

The markers a structure and the spawns pass leave -- enemy spawns, the
boss, loot, the entrance -- are read by the game through `MarkedWorld`,
by chunk, and come out the same whether or not the chunk was generated.

## Tabletop rules

A pen-and-paper system becomes a plugin through **checks**: dice against a
difficulty, paid out as boons in the fight. Players roll them from the
**Table** button in play.

```json
{
  "id": "yourname:war_cry",
  "name": "War Cry",
  "dice": "1d20",
  "attribute": "strength",
  "difficulty": 12,
  "cooldownSeconds": 60,
  "boon": { "name": "Ancestral Might", "durationSeconds": 60, "attackPower": 0.25 },
  "bane": { "name": "Shaken", "durationSeconds": 20, "armour": -5 }
}
```

- **Dice** are written the way tables write them: `d20`, `2d6+3`,
  `1d8+1d4-1`, `4d6kh3` (keep the highest three), `2d20kh1` (advantage),
  `2d20kl1` (disadvantage).
- **The roll** is the dice plus the hero's attribute modifier (every two
  points above 10 is +1) plus proficiency (+2, one more every four levels).
  Meeting the difficulty succeeds.
- **Criticals:** a single die showing its highest face is a critical success,
  and the boon lasts half as long again; showing a one is a critical failure,
  and the bane applies.
- **Boons** change real combat stats while they last: `attackPower` and
  `attackSpeed` are shares (`0.25` is +25%); `armour`, `maxHealth`,
  `critChance` and `lifeSteal` are added.

## Builds and the endgame

A pack with monsters gets a generated passive tree, standard currency,
support gems and waystone mods for free. A plugin that wants its own writes
any of them; each kind it defines replaces the standard set of that kind.

**Modifiers** are the unit of all of it, read the way the tooltip says them:

```json
{ "stat": "damage", "kind": "increased", "value": 0.1 }
```

`kind` is `flat`, `increased` (the default) or `more`; a negative value is
"reduced" or "less". `value` is a share for percent stats and for
increased and more (`0.1` is 10%). Stats: `max_health`, `damage`, `armour`,
`crit_chance`, `crit_multiplier`, `attack_speed`, `life_steal`, `resistance`
(add `"damageType"` to scope it), `skill_damage`, `area`,
`cooldown_recovery`, `resource_cost`, `move_speed`, `max_resource`,
`experience_gain`, `item_rarity`, `item_quantity`.

**Passive trees** — the last pack with one wins; trees replace, never merge:

```json
"passiveTrees": [{
  "id": "yourname:paths", "name": "Paths",
  "nodes": [
    { "id": "yourname:gate", "name": "Gate", "kind": "start", "classes": ["yourname:warrior"] },
    { "id": "yourname:might", "name": "Might", "x": 100,
      "modifiers": [{ "stat": "damage", "value": 0.08 }] },
    { "id": "yourname:oath", "name": "Glass Oath", "kind": "keystone", "x": 200,
      "description": "Hit harder than anything alive, and break like it.",
      "modifiers": [{ "stat": "damage", "kind": "more", "value": 0.4 },
                    { "stat": "max_health", "kind": "more", "value": -0.3 }] }
  ],
  "links": [["yourname:gate", "yourname:might"], ["yourname:might", "yourname:oath"]]
}]
```

`kind` is `start`, `small` (the default), `notable` or `keystone`; `x` and
`y` only place the node on screen. A start with no `classes` is open to all.

**Currency** names one of the engine's verbs — `imbue`, `reforge`, `ascend`,
`temper`, `annul`, `socket`, `scour`:

```json
"currencies": [{ "id": "yourname:cowrie", "name": "Cowrie of Change", "effect": "reforge", "color": "#E0C068", "weight": 120 }]
```

**Supports** tune the skill they are linked to, and can convert its damage:

```json
"supports": [{ "id": "yourname:ember", "name": "Ember Soul", "convertsTo": "yourname:fire",
  "modifiers": [{ "stat": "skill_damage", "kind": "more", "value": 0.2 }] }]
```

**Waystone mods** make a world harder and pay for it:

```json
"waystoneMods": [{ "id": "yourname:eclipse", "name": "Eclipse",
  "monster": [{ "stat": "damage", "kind": "more", "value": 0.3 }],
  "reward": [{ "stat": "item_rarity", "value": 0.25 }] }]
```

## Worlds with their own lore

Everything that makes a world a *setting* -- who lives there, where they
live, how they fight, what keeps a body alive, what the player can build --
is data. [`examples/plugins/ashen-crusade`](../examples/plugins/ashen-crusade)
uses all of it to turn the built-in world grimdark without a line of code,
and a test keeps it loading.

**Factions** are the sides. `defaultStance` (`hostile`, `neutral`,
`allied`) is how they regard a stranger; standing moves as the player kills
their enemies (up) or their members (down), crossing into hostility below
-100 and alliance from 100. `ranks` pay out modifiers at a standing.

```json
"factions": [{ "id": "yourname:order", "name": "The Order", "color": "#FFB0BEC5", "glyph": "✠",
  "defaultStance": "neutral", "startingStanding": 110,
  "relations": { "yourname:cult": "hostile" },
  "ranks": [{ "name": "Sworn", "threshold": 300, "modifiers": [{ "stat": "armour", "kind": "flat", "value": 5 }] }] }]
```

An enemy joins one with `"faction"`, and fights as a `"role"`: `melee`
(holds the line), `ranged` (keeps its distance), `support` (hangs back),
`swarmer` (flanks) or `brute` (charges). Only so many attack the player at
once; the rest circle, which is what makes a crowd readable on a phone.
`spawnWeight: 0` keeps a town guard out of the wilds.

**Enemy packs** spawn together and share an alarm: hit one and they all
come, and when the leader falls the rest may break.

```json
"enemyPacks": [{ "id": "yourname:warband", "name": "Warband", "leader": "yourname:priest",
  "members": [{ "enemy": "yourname:thrall", "count": 5 }], "spawnBiomes": ["yourname:ruins"], "weight": 60 }]
```

**Settlements** are recipes the world generator stamps into the terrain:
a `layout` (`grid`, `organic`, `fortress`, `camp`), the blocks for its
`road`, `foundation`, optional `wall`, and `buildings` it chooses from --
each with a `role` (`house`, `shop`, `smithy`, `tavern`, `temple`,
`barracks`, `tower`, `warehouse`, `hall`, `farm`), a size and its blocks.
A town of a faction hostile to the player is a stronghold: kill its
`garrison` and it is liberated, becoming the player's outpost.

```json
"settlements": [{ "id": "yourname:keep", "name": "Keep", "layout": "fortress", "faction": "yourname:order",
  "biomes": ["yourname:ruins"], "road": "yourname:paving", "foundation": "yourname:paving", "wall": "yourname:plate",
  "names": ["Vigil", "Last Bell"],
  "buildings": [{ "id": "yourname:chapel", "name": "Chapel", "role": "hall", "width": 9, "depth": 11, "wall": "yourname:stone", "maxCount": 1 }],
  "garrison": [{ "enemy": "yourname:knight", "count": 3 }] }]
```

**Survival** is the standard hunger, thirst and warmth unless a pack
defines its own `needs`. `consumables` restore needs (by need id) and may
heal or grant timed `modifiers`; `recipes` turn `inputs` into an `output`,
at a `station` (a block id, or `stratum:fire` for any fire); `forage`
yields an `item` at a `chance` when a `block` (or any block of a
`material`) is broken. Regions carry a `temperature` from -1 to 1, which
is what makes a night in the snow dangerous. A pack that defines no food
adds its recipes and forage rules on top of the standard ones.

**Strategy** is the standard economy (food, timber, stone, metal; hearths,
farms, quarries, forges, barracks, watchtowers, palisades) unless a pack
defines `resources` or `structures`. `units` are soldiers an outpost
trains: an `actor` (an enemy id, the body that walks the world), a `cost`,
the structure it `requires` and the `defense` it adds while garrisoned.

```json
"units": [{ "id": "yourname:oathsworn", "name": "Oathsworn", "actor": "yourname:knight",
  "cost": { "stratum:food": 15, "stratum:metal": 6 }, "requires": "stratum:barracks", "defense": 9 }]
```

**Rules** are how the pack suggests its worlds be played; the player sees
them as the starting point on the home screen and can change any of them:
`survival` (`off`, `gentle`, `harsh`), `townDensity`, `monsterDensity`,
`raids`, `dayLengthMinutes`, `startInTown`, `lootMultiplier`,
`experienceMultiplier`, `deathPenalty`.

**Agents** are the studio crew a pack brings. Each writes some `sections`
of a new pack (by their names in this file), after the roles it
`dependsOn`, following its `brief`; `requiresApproval` makes it wait for a
person. A pack that brings none gets the standard crew: loremaster,
cartographer, bestiary, architect, steward, warlord, arbiter.

```json
"agents": [{ "id": "yourname:chronicler", "name": "Chronicler", "sections": ["lore", "factions"],
  "brief": "Write as the Order's chronicle: grim, liturgical, certain.", "requiresApproval": true }]
```

## Other formats

Players can also install a Flame game or a folder of Tiled maps directly;
see the importing section of [`ARCHITECTURE.md`](../ARCHITECTURE.md). Those
become plugins with a manifest derived from their content, and are ordered,
switched and removed like any other.
