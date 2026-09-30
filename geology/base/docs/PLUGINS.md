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
| `damageTypes` | `id`, `name`, `color`, `symbol`, `armour` (whether armour reduces it, default true), `ailment` (a status id) and `ailmentChance`. Every weapon, skill and monster must use a damage type some loaded pack defines. |
| `weapons` | The short way to write a weapon: `id`, `name`, `damageType`, `minDamage`, `maxDamage`, `attackSpeed`, `attackRange`, `toolTier`, `twoHanded`, `tags`, `requiredLevel`, `minItemLevel`. |
| `itemBases`, `affixes`, `inserts`, `uniques`, `itemSets`, `itemNames`, `baseTiers` | Gear of every kind and what rolls on it; see *Gear*. |
| `enemies` | `id`, `name`, `rank` (minion, elite, champion...), `damageType`, `stats`, `moveSpeed`, `aggroRange`, `experience`, `spawnBiomes`, `spawnWeight`, `spriteSet`, `role`, `skills` and boss `phases`; see *The combat core*. |
| `skills` | `id`, `name`, `damageType`, `powerMultiplier`, `resourceCost`, `cooldownSeconds`, `delivery`, `range`, `area`, `effects`, `tags`, `castTime`, `charges`, `lifeCost`, `projectile`, `zone`, `summon`, `conversions`; see *The combat core*. The old `shape` (strike, nova, lance) still loads. |
| `statuses`, `traits`, `flasks` | Ailments and buffs, keystones and triggers, and the flask belt; see *The combat core*. |
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
id to a share, e.g. `0.5`), `lifeSteal`, `evasion`, `blockChance` and
`accuracy`.

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
`experience_gain`, `item_rarity`, `item_quantity`, `mining_speed`, and for the combat core
`evasion`, `accuracy`, `block_chance`, `life_regen`, `resource_regen`,
`penetration` and `max_resistance` (both take `"damageType"`),
`ailment_chance`, `duration`, `damage_over_time`, `damage_taken`,
`projectiles`, `pierce`, `chain`, `fork` (flat counts), `projectile_speed`,
`cast_speed`, `flask_charges`, `flask_effect`. `damage` with a
`"damageType"` is "increased fire damage" and touches only that type.

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

A support can also demand tags (`requiresTags`: it only links to a skill
carrying one of them), add tags (`addsTags`), add `effects` and partial
`conversions`, and carry a `trigger` that makes the linked skill cast
itself -- "cast on critical strike":

```json
{ "id": "yourname:answer", "name": "The Answer", "requiresTags": ["spell"],
  "trigger": { "on": "crit", "requiresTags": ["attack"], "cooldownSeconds": 0.3 } }
```

**Waystone mods** make a world harder and pay for it:

```json
"waystoneMods": [{ "id": "yourname:eclipse", "name": "Eclipse",
  "monster": [{ "stat": "damage", "kind": "more", "value": 0.3 }],
  "reward": [{ "stat": "item_rarity", "value": 0.25 }] }]
```

## Gear

A hero wears ten things: a weapon, an off hand, a helm, body armour,
gloves, boots, a belt, an amulet and two rings. Everything worn is a
**base**, rolls **affixes**, and becomes modifiers on the same sheet the
passive tree and supports use, so a ring's "increased damage" and a
keystone's stack exactly as they read.

**Bases** (`itemBases`) are any gear. `slot` is `weapon`, `offhand`, `helm`,
`chest`, `gloves`, `boots`, `belt`, `amulet` or `ring`. A base with a
`damageType` is a weapon and reads `minDamage`, `maxDamage`, `attackSpeed`,
`attackRange`, `toolTier` and `twoHanded` (a two-handed weapon empties the
off hand). `defences` are flat amounts of any stat the base gives before it
rolls anything; `implicits` are ranges rolled once when it drops. `tags` are
yours to invent -- "blade", "heavy", "caster" -- and decide which affixes can
roll on it; the slot, its family (`weapon`, `armour`, `offhand`,
`jewellery`) and `one_handed`/`two_handed` are tags already.
`requiredLevel` gates wearing it, `minItemLevel` gates it dropping. The old
`weapons` section still works and becomes bases the same way.

```json
"itemBases": [
  { "id": "yourname:bronze_helm", "name": "Bronze Helm", "slot": "helm", "tags": ["heavy"],
    "defences": [{ "stat": "armour", "value": 20 }], "requiredLevel": 8, "minItemLevel": 10 },
  { "id": "yourname:jade_ring", "name": "Jade Ring", "slot": "ring",
    "implicits": [{ "stat": "max_health", "kind": "flat", "min": 5, "max": 10 }] },
  { "id": "yourname:maul", "name": "Maul", "slot": "weapon", "damageType": "yourname:crush",
    "minDamage": 20, "maxDamage": 34, "attackSpeed": 0.8, "twoHanded": true }
]
```

A range is `stat`, `kind`, and `min`/`max` (or one `value`), plus
`damageType` to scope it; `kind` defaults to `increased` here as everywhere.

**Base ladders.** A pack does not have to write every strength of a base.
Each base (unless it says `"grows": false`) grows stronger rungs that drop
deeper, named from `baseTiers` -- or `{base} II`, `III`, `IV` when no pack
names any:

```json
"baseTiers": [{ "name": "Riveted {base}", "levelsAbove": 12 }, { "name": "Titled {base}", "levelsAbove": 36, "bonus": 1.15 }]
```

A rung's id is its root's with `~2`, `~3`... after it. Only the two deepest
rungs of a family a drop can reach are in its pool.

**Affixes** have `tiers`, weakest first, each with its own `minItemLevel`,
`modifiers`, optional `name` and `weight`. `slots` and `tags` keep an affix
to gear it suits (empty means anything); a `group` stops two affixes of one
family rolling on one item; `local` makes it change the item's own numbers
-- its damage, speed or defences -- rather than its wearer's. Prefixes and
suffixes each take at most half of a rarity's affixes, rounded up.

```json
"affixes": [{ "id": "yourname:plated", "name": "Plated", "kind": "prefix", "local": true,
  "slots": ["helm", "chest", "gloves", "boots"], "group": "yourname:armour",
  "tiers": [
    { "modifiers": [{ "stat": "armour", "kind": "increased", "min": 0.1, "max": 0.2 }] },
    { "minItemLevel": 20, "name": "Fortified", "modifiers": [{ "stat": "armour", "kind": "increased", "min": 0.3, "max": 0.5 }] }
  ] }]
```

An affix of one strength can be written in one line: `stat`, `min`, `max`,
`modifierKind`, `minItemLevel`. Packs written before modifiers -- `stat`
one of attack_power, max_health, armour, crit_chance, crit_multiplier,
attack_speed, resistance, life_steal, mining_speed, and no `modifierKind` --
load unchanged and mean what they always did.

**Inserts** take `modifiers` and `convertsTo` (a damage type the weapon
they sit in switches to); the older `stat`, `value`, `damageType` and
`convertsDamageType` still read. Any item can hold them.

**Uniques** are named items on a `base`, with fixed or ranged `modifiers`,
`localModifiers` for the item's own numbers, `flavour`, and `flags`: rules
the engine lets gear break. The flags are `skills_cost_health`,
`cannot_crit`, `resource_shields_health`, `skills_use_weapon_type`,
`hits_ignore_resistance` and `life_steal_uncapped`. Currency can temper a
unique's values and add sockets, never replace its modifiers.

```json
"uniques": [{ "id": "yourname:blood_crown", "name": "Crown of Blood", "base": "yourname:bronze_helm",
  "flags": ["skills_cost_health"], "flavour": "It drinks.",
  "modifiers": [{ "stat": "skill_damage", "kind": "more", "value": 0.3 }] }]
```

**Sets** are uniques that name a `set`. The set's `bonuses` unlock by how
many distinct pieces are worn, and stack:

```json
"itemSets": [{ "id": "yourname:pair", "name": "The Pair",
  "bonuses": [{ "pieces": 2, "modifiers": [{ "stat": "damage", "value": 0.2 }] },
              { "pieces": 4, "modifiers": [], "flags": ["cannot_crit"] }] }]
```

**Names.** Rare items and better are named from `itemNames` pools -- one
word from `first`, one from `second` -- that fit the item's `slots` and
`tags`; with no pool they read their affixes, "Roped Bronze Blade of Storms".

```json
"itemNames": [{ "id": "yourname:blades", "first": ["Storm", "Ash"], "second": ["Bite", "Oath"], "slots": ["weapon"] }]
```

**Power.** Nothing caps how strong gear may be -- broken builds are
allowed -- but the content forge labels every base, affix, unique and set
it makes as *balanced*, *strong* or *broken* against what an ordinary affix
gives at its item level, and scales a result asked for as balanced into
that budget. Hand-written gear is never scaled; the same yardstick
(`PowerBudget`) is there for any tool that wants to show it.

**Your creations.** What a player keeps from the content forge is an
ordinary plugin, `user.creations`, holding `itemBases`, `affixes`,
`uniques`, `itemSets` and `lore` in exactly the shapes above, with ids in
the `user.creations:` namespace and a dependency on the built-in pack. It
can be disabled or removed in the library like any plugin. Sharing exports
it (with the player's classes) as `shared.creations`, its ids moved to
that namespace so it installs beside the recipient's own.

## The combat core

Skills, statuses, triggers and bosses are data. The engine knows a handful
of verbs -- how a skill travels, what an effect does, how a status behaves,
when a trigger fires -- and a pack combines them. Nothing here names a
damage type: "fire burns" is a pack giving its fire an ailment whose
behaviour is damage over time.

### Skills

A skill is a **delivery**, an **area**, a list of **effects**, **tags** and
**costs**:

```json
{ "id": "yourname:fireball", "name": "Fireball", "damageType": "yourname:fire",
  "delivery": "projectile", "range": 10, "powerMultiplier": 1.2,
  "resourceCost": 12, "cooldownSeconds": 0.5, "castTime": 0, "charges": 1,
  "projectile": { "count": 1, "speed": 12, "pierce": 0, "chain": 0, "fork": 0, "spread": 20 },
  "tags": ["spell"],
  "effects": [
    { "type": "damage", "damageType": "yourname:fire" },
    { "type": "status", "status": "yourname:burn", "chance": 0.3 },
    { "type": "cast", "skill": "yourname:explosion" }
  ] }
```

- **delivery**: `melee` (the nearest thing in reach), `cone`, `nova`
  (around the caster), `area` (around a point: the nearest enemy, or `range`
  ahead), `beam` (a lane), `projectile` (a real thing that flies, stops at
  blocks unless `"collides": false`, and may pierce, chain and fork),
  `chain` (leaps between bodies `projectile.chain` times), `dash` (carries
  the caster, hitting what it passes), `summon` (with `summon`: `enemy`,
  `count`, `duration`, `limit`), `self` (the caster, and allies for effects
  aimed at `allies`), `zone` (ground that pulses: `zone`: `duration`,
  `pulse`, `trap: true` to wait for someone to step in).
- **area**: `radius` (of a nova, area, zone or chain leap), `angle` (a cone's
  width in degrees), `halfWidth` (of a beam or dash lane).
- **effects**, each with a `type`: `damage` (`damageType`, `share` of the
  skill's power; several make one multi-typed hit), `status` (`status`,
  `chance`, `stacks`, `target`), `heal` (`amount`, `maxShare`, `target`),
  `resource` (`amount`), `knockback` (`force`), `cast` (`skill`: cast
  another skill where this one landed). `target` is `target` (what was hit),
  `self`, or `allies`. No effects means one hit of the skill's `damageType`.
- **tags**: any strings. The delivery adds its own (`melee`, `projectile`,
  `area`...), every damage type the skill deals is a tag, and a skill is an
  `attack` or a `spell` (attacks can be evaded). Supports and conditions
  read them.
- **costs**: `resourceCost`, `lifeCost`, `cooldownSeconds`, `charges`
  (stored uses, each recharging on the cooldown), `castTime` (the wind-up;
  on a monster, what the player sees marked on the ground and can roll out
  of).
- **conversions**: `{ "from": "yourname:phys", "to": "yourname:fire", "share": 0.5 }`;
  add `"extra": true` for "gain as extra" instead. A missing `from` means
  all damage. Conversion happens once: converted damage is never converted
  again, so a cycle cannot loop.

### Hits and defences

Every hit, the player's or a monster's, runs the same order: evasion
(`evasion / (evasion + accuracy)`, attacks only), block, conversion, crit,
typed damage, penetration, resistance within the world's caps, damage
taken, armour, leech, ailments. Armour is a curve, `armour / (armour + 5 x
hit)`, so it shrugs off a swarm and barely dents a slam but never reaches
immunity on its own. Leech fills a pool drained at the world's leech rate.

### Statuses

```json
"statuses": [
  { "id": "yourname:burn", "name": "Burning", "durationSeconds": 4, "tags": ["burning"],
    "behaviours": [{ "type": "dot", "damageType": "yourname:fire", "hitShare": 0.25 }] },
  { "id": "yourname:shock", "name": "Shocked", "stacking": "intensity", "maxStacks": 3,
    "behaviours": [{ "type": "damage_taken", "amount": 0.08 }] }
]
```

Behaviours: `dot` (`damageType`, `perSecond`, `hitShare` of the hit that
caused it), `slow` (`amount` 0..1), `stun`, `damage_taken` (`amount`,
optional `damageType`), `modifiers` (any modifiers, per stack), `recover`
(`perSecond`, `maxShare`). `stacking` is `refresh` (one, keep the
stronger), `stack` (independent, up to `maxStacks`) or `intensity` (one,
stacks grow). `debuff: false` marks a buff. A damage type's `ailment` is
the status its hits may inflict, at `ailmentChance` plus the attacker's
`ailment_chance`.

### Traits: keystones, conditions and triggers

Classes (`traits`) and passive nodes (`traits`) grant traits:

```json
"traits": [{
  "id": "yourname:oath", "name": "Oath of Glass",
  "keystones": ["cannot_crit"],
  "modifiers": [{ "stat": "damage", "kind": "more", "value": 0.4 }],
  "conditional": [
    { "when": "target_has", "status": "burning", "stat": "damage", "value": 0.3 },
    { "when": "per", "attribute": "strength", "per": 10, "stat": "armour", "kind": "flat", "value": 5 }
  ],
  "conversions": [{ "from": "yourname:phys", "to": "yourname:fire", "share": 0.5 }],
  "triggers": [{ "on": "kill", "chance": 0.25, "cast": "yourname:nova", "cooldownSeconds": 1 }]
}]
```

- **keystones**: `instant_leech`, `cannot_crit`, `life_pays_costs`,
  `crits_inflict_ailments`, `unshakeable` (no stun or slow),
  `no_regeneration`.
- **conditions** (`when`): `full_life`, `low_life` (`threshold`),
  `target_has` / `self_has` (`status`: an id or a tag), `skill_tag`
  (`tag`), `per` (`attribute`: strength, agility, insight or level; `per`).
- **triggers** (`on`): `hit`, `crit`, `kill`, `hit_taken`, `block`,
  `evade`, `skill_use`, `low_life` (`lowLife`), each with `chance`,
  `cooldownSeconds`, `cast` (a skill, free), `status` (`statusOnSelf`), and
  `requiresTags`.

Triggers cannot loop forever. Each cast carries a depth; a cast at the
world's `triggerDepth` triggers nothing, every trigger waits its own
cooldown (never below `triggerCooldownFloor`), and one action may cause at
most `triggerBudget` triggered casts. A skill that casts itself obeys the
same limits.

### Monsters and bosses

```json
{ "id": "yourname:tyrant", "name": "Tyrant", "damageType": "yourname:phys", "role": "support",
  "skills": [{ "skill": "yourname:fireball", "weight": 100, "healthBelow": 1, "cooldownSeconds": 3 }],
  "phases": [
    { "name": "Rage", "healthBelow": 0.5, "announcement": "The tyrant roars",
      "skills": [{ "skill": "yourname:slam" }], "adds": [{ "enemy": "yourname:imp", "count": 2 }],
      "enrage": [{ "stat": "damage", "kind": "more", "value": 0.5 }], "status": "yourname:frenzy" }
  ] }
```

Monsters use the same skills as heroes, for free but on cooldown, when the
player is in reach (a heal or buff when an ally needs it). Ranged and
support monsters keep their distance at their longest skill's reach.
Phases are entered in order as health falls: they replace the skill list
when they name one, call adds, enrage and announce themselves.

### Flasks

```json
"flasks": [{ "id": "yourname:gourd", "name": "Gourd", "maxCharges": 30, "chargesPerUse": 10,
  "chargesPerKill": 2, "lifeShare": 0.4, "recoverySeconds": 2, "resource": 0,
  "status": "yourname:ward", "cleanses": false }]
```

Kills refill every flask (elites twice, champions four times, bosses ten).
The first five flasks loaded are the belt.

### Caps a world can lift

`rules.combat` sets the caps: `resistanceCap`, `resistanceHardCap` (what
`max_resistance` can raise it to; 1 allows immunity), `minResistance`,
`maxEvadeChance`, `maxBlockChance`, `maxArmourReduction`, `cooldownFloor`,
`maxLeechRate`, `critChanceCap`, `triggerDepth` (at most 8),
`triggerCooldownFloor`, `triggerBudget` (at most 256), `maxStatusStacks`.
The *Unbound* preset lifts all of them, for building something broken on
purpose.

### A build sandbox

`"rules": { "sandbox": true }` suggests a world for making builds rather
than playing them: free respecs, any base, unique or set piece at any item
level and rarity (and rerolled), currency, support gems and levels on
demand, training dummies with chosen defences that never fight back, any
monster or boss called in, a damage meter, and a breakdown of every number
by source. The *Sandbox* preset is one; the caps can be lifted in the world
itself. A sandbox never writes its hero back to the player's save.

Builds are shared as a small JSON blob a plugin could hold, or as the same
JSON behind `STRATUM-BUILD-1:` in URL-safe base64:

```json
{ "format": "stratum.build", "version": 1, "name": "Glass", "heroClassId": "yourname:warrior", "level": 42,
  "passives": ["yourname:might", "yourname:oath"],
  "gear": { "WEAPON": { "base": "yourname:maul", "itemLevel": 60, "rarity": "rare", "name": "Storm Bite",
    "affixes": [{ "id": "yourname:keen", "tier": 3, "modifiers": [{ "stat": "damage", "kind": "increased", "value": 0.4 }] }] },
    "RING_LEFT": { "base": "yourname:jade_ring", "itemLevel": 50, "rarity": "set", "unique": "yourname:left" } },
  "supports": { "yourname:fireball": ["yourname:ember"] } }
```

Gear is a recipe against the loaded packs -- base, item level, rarity, the
unique it is -- with its rolled numbers written out; a piece whose base or
unique is not loaded is left out and named, never guessed.

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

## 3D models

A pack can give its props, structures, weapons and monsters a 3D body. Each
entry in `models` names where the model is and what it dresses; every target
is optional, and naming one the loaded packs do not define is an error at
load, like any other unknown id.

```json
"models": [{ "id": "yourname:idol", "source": "asset:statue_bronze_idol", "name": "Bronze idol",
  "height": 2.5, "block": "yourname:shrine", "enemy": "yourname:guardian" }]
```

| Field | Default | Meaning |
| --- | --- | --- |
| `id` | required | The model's id; later packs override it like anything else. |
| `source` | required | `asset:<id>` for a model made in the model forge on this device; otherwise a path inside the plugin such as `models/idol.glb` (reserved: plugin archives do not carry model files yet). |
| `name` | `""` | What the player sees. |
| `height` | `1.5` | How tall it stands, in blocks, once stood upright and grounded (at most 32). |
| `block` | none | A prop block (one with a glyph) drawn as this model in the 3D view instead of a sprite, lit and shadowed like the terrain. |
| `structure` | none | An outpost structure the model belongs to. Checked at load; not drawn yet. |
| `weapon` | none | A weapon base the model belongs to. Checked at load; not drawn yet. |
| `enemy` | none | A monster drawn with the model's baked sprite (`actor:<enemy id>`). |

Models are binary glTF 2.0 (`.glb`) with their textures inside, or plain OBJ.
They are parsed, decimated to a couple of thousand triangles, voxelised and
baked on the device by `:engine:model`; see "3D models from AI endpoints" in
[`ARCHITECTURE.md`](../ARCHITECTURE.md).

## Other formats

Players can also install a Flame game or a folder of Tiled maps directly;
see the importing section of [`ARCHITECTURE.md`](../ARCHITECTURE.md). Those
become plugins with a manifest derived from their content, and are ordered,
switched and removed like any other.
