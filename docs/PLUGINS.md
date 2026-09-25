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
| `terrain` | How the world is shaped: `generator` (`stratum:layered`, or `stratum:tilemap` with `options.map` naming a map), `elevation` noise layers, `terraceStep`, `strata`. |
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

`stats` blocks take `maxHealth`, `attackPower`, `armour`, `critChance`,
`critMultiplier`, `attackSpeed`, `attackRange`, `resistances` (damage type
id to a share, e.g. `0.5`) and `lifeSteal`.

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

## Other formats

Players can also install a Flame game or a folder of Tiled maps directly;
see the importing section of [`ARCHITECTURE.md`](../ARCHITECTURE.md). Those
become plugins with a manifest derived from their content, and are ordered,
switched and removed like any other.
