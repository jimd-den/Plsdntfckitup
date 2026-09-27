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
| `damageTypes` | `id`, `name`, `color`, `symbol`, `armour` (whether armour reduces it, default true), `ailment` (a status id) and `ailmentChance`. Every weapon, skill and monster must use a damage type some loaded pack defines. |
| `weapons` | `id`, `name`, `damageType`, `minDamage`, `maxDamage`, `attackSpeed`, `attackRange`, `toolTier`, `minItemLevel`. |
| `affixes`, `inserts` | Item modifiers and socketable runes: `stat` is one of attack_power, max_health, armour, crit_chance, crit_multiplier, attack_speed, resistance, life_steal, mining_speed. |
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
`experience_gain`, `item_rarity`, `item_quantity`, and for the combat core
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
