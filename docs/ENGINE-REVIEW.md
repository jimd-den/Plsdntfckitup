# Engine review: what Stratum is, what an ARPG still needs, and why it feels like a modpack

A whole-codebase review taken at `7933b65`. It does three things:

1. Says in plain words what each part of the engine does today.
2. Lists what is missing for an action RPG, and for the sandbox that is
   supposed to produce one.
3. Explains why the product reads as a pile of features, and proposes one
   shape that gives it focus.

The target this review measures against is the one stated for the project:

> A bare-bones ARPG sandbox. You prompt an AI to build the assets and the
> world, or import your own. Then you shape that into whatever ARPG you
> want, save it, and export it.

Every recommendation below is judged by whether it serves that loop.

## Status

The review below is kept as written at `7933b65`. Since then, much of its
roadmap has landed:

**Done**

- **Gear.** Ten equipment slots, items that roll `StatModifier`s, uniques
  and item sets with set bonuses, and gear that can break combat's rules
  (build flags and keystones).
- **Composable skills.** Delivery × area × effect, with projectiles
  (pierce, chain, fork), zones and traps, dashes, summons and supports;
  status effects and damage over time keyed off damage types; life and
  resource regeneration and flasks.
- **Monsters that fight back.** Monster skills with telegraphed wind-ups
  through the same cast path as the player's; ranged monsters shoot, support
  monsters heal and buff; bosses with phases, adds and enrage as data.
- **Staged world generation with dungeons** (`:engine:worldgen`): passes,
  presets and registries, climate-blended biomes, carvers, ores, trees,
  towns, and room-and-corridor dungeons and jigsaw structures that mark
  their monsters, boss and loot. The session now peoples those markers as
  the player arrives, boss at boss rank, chests with a rarity floor.
- **3D models** (`:engine:model`): providers behind a port, a guarded GLB and
  OBJ reader, props, voxelised blueprints and baked sprites.
- **Sprite pipeline fixes** and the pose forge.
- **The content forge**: short prompts for lore, bases, affixes, uniques
  and sets, with lenient repair and a power budget that labels rather than
  forbids broken gear, all kept in one growing `user.creations` plugin.
- **The world generator folded into the crew** as a preset, its result an
  installed plugin.
- **A build sandbox**: a sandbox world rule, a damage meter, stat
  breakdowns, training dummies and shareable build codes.
- **`WorldSession` split** into systems it orchestrates (building, gear,
  survival, progression, politics, encounters, the fight), with optional
  systems costing nothing when a world's rules turn them off; max life and
  resource answered in one place; followers fighting through the combat
  system with their skills.
- **Phase 1, item 5**: the Creator studio tile is gone, `:app` no longer
  depends on `:feature:studio`, and the Gemini wiring is removed. The
  legacy modules are frozen, still compiling, reached by nothing.

**Still to do**

- **Projects** (§3.1, §7): one unit of pack, art, rules and enabled
  systems, and exporting a whole game as that unit.
- **Structure**: quests as data, NPCs, vendors, a stash and a hub.
- **World-state saves**: the hero persists; the world does not.
- **Audio** hooks in the pack schema.
- **Standalone APK export** of a project.
- **The legacy modules**: whether to delete `:legacy:*` and
  `:feature:studio` is still an open decision.
- **Identity**: `metadata.json` now describes Stratum, but the
  `applicationId` is still `com.aistudio.igboarpg.omagvd`, kept because
  changing it would stop installed builds from upgrading in place.

---

## 1. The short version

- **The engine core is sound.** Clean module boundaries enforced by the
  build, pure-Kotlin domain, deterministic sessions, a real modifier engine
  (flat / increased / more), a validated plugin format with dependencies and
  load order, and a good test suite where it matters (`:engine:world` has
  almost as many test lines as source lines).
- **The ARPG itself is thin.** One equipment slot. Three skill shapes. No
  projectiles, no status effects, no monster abilities, no bosses, no
  dungeons, no vendors, no quests. The things that make an ARPG an ARPG are
  the smallest part of the code.
- **The asset pipeline is huge.** Sprite, pose, skeleton, mocap, OpenPose,
  video, weapon fitting, texture forging, atlas baking and art direction are
  about half of `:core:domain` on their own.
- **There is no "game" object.** Everything a creator makes lands in a
  different store, with a different lifetime, reached from a different home
  tile. Nothing ties "the world I prompted, the sprites I made, the classes I
  built and the rules I chose" into one thing you can save, reopen and
  export.
- **A second engine is still shipped.** The legacy engine and its studio
  (about a fifth of the code) run their own combat loop, their own AI client,
  their own database and their own versions of the weapon forge and sprite
  importer, reachable from the "Creator studio" tile.

The modpack feeling is the sum of the last three: many tools, each good at
its own thing, with no project to belong to and a thin game at the centre
for them to feed.

---

## 2. What the engine does today

Line counts are main source only (tests in brackets).

### The game

| Module | Lines | What it does |
| --- | --- | --- |
| `:core:domain` | 27,455 (8,307) | All rules and models. See the breakdown below. |
| `:engine:world` | 5,833 (5,407) | `WorldSession`, the play session: streams voxel chunks, moves the player (walk, climb, roll with i-frames), resolves attacks and skills, drives monsters through the crowd AI, rolls loot, runs mining and building, survival, the realm (outposts, raids, followers), tabletop checks, passives, crafting and sockets. Also the layered and tile-map terrain generators. |
| `:engine:crowd` | 491 (159) | Monster movement: flow fields, spatial hash, attack tokens so only a few swing at once, roles (melee, ranged, support, swarmer, brute), squads and morale. Movement only; it decides *where* a monster stands, not *what* it does. |
| `:engine:settlement` | 766 (201) | Towns: picks sites, lays out grid / organic / fortress / camp plans, packs lots, stamps buildings, roads and walls into terrain. |
| `:engine:render` | 693 (326) | 2D isometric frame planning into a drawing sink. |
| `:engine:scene` | 2,934 (776) | The 3D view: voxel meshing with AO, camera, sprites as billboards, lights, ray picking, and the asset forge that turns image-model output into tileable textures. |
| `:content:igbo` | 1,370 (96) | The built-in pack and its forged texture kits. Always loaded first. |

`:core:domain`, by package:

| Area | Lines | Share | Packages |
| --- | --- | --- | --- |
| Asset pipeline | ~13,500 | ~49% | `sprite` (6,119), `art` (3,602), `ai` (2,756), `bvh` (1,012) |
| World and content | ~2,400 | ~9% | `world`, `content`, `map`, `importing`, `plugin` |
| Sandbox extras | ~1,200 | ~4% | `strategy`, `settlement`, `survival`, `faction`, `tabletop` |
| ARPG rules | ~2,000 | ~7% | `combat` (120), `actor` (275), `item` (363), `stats` (169), `passive` (426), `crafting` (118), `difficulty` (104), `session` (346) |

The combat model the whole game rests on is 120 lines. Skills, enemies and
cooldowns together are 275.

### The tools

| Module | Lines | What it does |
| --- | --- | --- |
| `:feature:play` | 7,004 (45) | The play screen, HUD, dock, and overlays: satchel, anvil, hero tree, realm, survival, style, table. Hosts both the 2D canvas and the 3D GL view behind a toggle. Also the **texture forge** screen. |
| `:feature:forge` | 7,504 (0) | Sprite forge, pose forge, weapon forge, sprite mapper, frame studio, **world generator**, and the **agent studio crew** screen. |
| `:feature:hero` | 589 (0) | Class builder. |
| `:feature:library` | 389 (137) | Plugins: install, order, enable, remove, share. |
| `:agents` | 561 (158) | The agent studio pipeline: roles write pack fragments, each checked against the real assembler, with a journal. |
| `:plugins` | 1,479 (311) | The `.stratum` format: manifest, schema, archive read/write, importer registry. |
| `:importer:*` | 1,970 (743) | Tiled maps, Flame games (Aseprite, TexturePacker, Dart sprite defs). |
| `:core:data` | 3,971 (335) | OpenRouter image / language / video clients, file stores for sprites, poses, weapons, classes, hero saves, settings and imported packs. |
| `:tools:artpreview` | 1,613 (213) | Headless renders and the plugin packer. Not shipped. |

### The legacy engine

| Module | Lines | What it does |
| --- | --- | --- |
| `:legacy:domain` | 3,613 (0) | A separate ARPG loop (`ArpgGameLoopEngine`) with hard-coded Igbo damage types and deity skills, projectiles, "custom mechanics" with triggers, pen-and-paper models, a codex. |
| `:legacy:data` | 2,278 (262) | Room database, a Gemini client, sprite file transfer. |
| `:feature:studio` | 12,510 (0) | The old creator studio: combat arena, a "Diablo eras" HUD, class/power studio, a second weapon forge, a second sprite-sheet importer, map studio, pen-and-paper studio, AI mechanic generator, Igbo history codex. |

Together: **18,401 lines, about 21% of all main source, no domain tests.**

---

## 3. Why it looks like a modpack

### 3.1 There is no project

The home screen is a play card plus nine tool tiles. Each tool saves into
its own place:

| What you make | Where it goes | Survives a restart | Exportable |
| --- | --- | --- | --- |
| Textures (texture forge) | `WorldStyleStore` + forge kit folder | Yes | No |
| Sprite sheets | `SpriteLibrary` | Yes | No |
| Posed characters | `PoseLibrary` / `PoseGuideStore` | Yes | Only as loose PNG/JSON |
| Weapons | `WeaponLibrary` / `WeaponFitStore` | Yes | No |
| Classes | `CustomClassStore` | Yes | **Yes, the only thing "Share your creations" exports** |
| A world from **World generator** | `forgedPacks`, a Compose `remember` in `StratumApp` | **No, lost when the app closes** | No |
| A world from **Agent studio** | Installed as a plugin | Yes | Via the plugin list |
| World rules | Home screen state | Per session | No |
| The hero | `HeroSaveStore`, per class | Yes | No |
| The world itself (dug, built, outposts) | Nowhere | **No** | No |

`PluginArchive.write` already accepts textures and sprite sheets. The app
never passes any. So the export path exists and is unused, and the one
format that could hold "a game" (`.stratum`) is only ever filled with
classes or AI text.

### 3.2 Two or three of everything

| Job | Implementations |
| --- | --- |
| Generate a world with AI | World generator (`GenerateContentPackUseCase`), Agent studio (`:agents`), legacy AI mechanic generator |
| Combat loop | `WorldSession` + `CombatResolver`, legacy `ArpgGameLoopEngine`, legacy combat arena |
| Weapon forge | `:feature:forge` `WeaponForgeScreen`, legacy `WeaponForgeAndLabScreen` |
| Import a sprite sheet | Sprite mapper, Flame importer, legacy `SpriteSheetImporterScreen` |
| Animate a character | Generated sheets, pose forge (OpenPose guides), procedural motion, BVH mocap, video frames, clip rows |
| Draw the world | 2D `WorldCanvas` via `:engine:render`, 3D GL via `:engine:scene` |
| AI provider | OpenRouter (`:core:data`), Gemini (`:legacy:data`) |
| Stat vocabulary | `AffixStat` (9 values, item affixes), `Stat` (17 values, passives and supports) |

A creator has no way to know which one is "the" tool, and every
improvement has to be made twice or leaves one of them behind.

### 3.3 Every system is always on, in one session

`WorldSession` (1,116 lines) owns terrain, mining, building, combat, loot,
crafting, sockets, passives, survival, the realm, raids, followers,
factions, towns, and tabletop dice. `WorldRules` can soften survival and
turn raids off, but a creator cannot say "my game has no mining" or "my game
has no outposts". So every game built with Stratum is a voxel-survival-
colony-tabletop ARPG with a different coat of paint. That is what a modpack
is.

### 3.4 The effort went where the pixels are

About half of the domain is making and fitting art. About 7% is the rules
the art is for. The tools to make a monster's sprite are far richer than
what that monster can do once it exists (walk toward you, and hit you when
in range).

---

## 4. ARPG gaps

Ordered by how much a player feels them. "Core" means an ARPG does not feel
like one without it.

### Combat (core)

- **Projectiles.** Skills resolve instantly as strike / nova / lance. No
  bolt that travels, can be dodged or pierces. The legacy engine had them.
- **Status effects.** No burn, poison, bleed, chill, freeze, stun, slow,
  shock. `damageTypes` are colours and resistances only; they do nothing
  distinct. No damage-over-time at all.
- **Monster abilities.** `EnemyDefinition` has no skills. A ranged monster
  keeps its distance (crowd AI) and then hits you with the same instant
  in-range swing as a melee one. Support monsters stand behind the pack and
  do nothing.
- **Telegraphs.** No wind-up, no ground marker, so the roll's i-frames have
  nothing to be timed against except the swing cooldown.
- **Skill composition.** `SkillShape` is a closed enum of three. A pack
  cannot make a cone, a chain, a dash, a summon, a buff, a trap or an aura.
  Supports tune numbers but cannot change delivery.
- **Evasion / block.** Armour is flat reduction; there is no chance to avoid
  a hit, and no block.
- **Life and resource regeneration, potions / flasks.** Survival food is the
  only healing loop, and it is off in Story mode.

### Items (core)

- **One equipment slot.** `EquipmentSlot` declares `ARMOUR` and `CHARM`, but
  nothing equips them: `PlayerState` and `HeroSave` hold `equippedWeapon`
  only. No helm, chest, gloves, boots, rings, amulet, offhand.
- **No uniques or sets** (already noted in `GAME-DESIGN.md`).
- **No item types beyond weapons** in the pack schema (`weapons` only).
- **No stash, no vendors, no gold sink.** Currency is only for crafting.
- **Two stat vocabularies.** `AffixStat` cannot express "increased" or
  "more", skill damage, area, cooldown, move speed or rarity; `Stat` can.
  Items should roll `StatModifier`s like everything else.

### Encounters and structure (core)

- **Bosses.** `EnemyRank.BOSS` is a multiplier. No phases, no arenas, no
  scripted mechanics.
- **Dungeons / instances.** The world is one endless streamed surface. No
  entrance, no floor, no "clear this and come back".
- **Quests and objectives.** Nothing tells the player what to do next. Map
  markers (`point_of_interest`) exist in the tile-map format but drive
  nothing but encounters.
- **NPCs and dialogue.** Towns have garrisons, not people you talk to.
- **Hub / town portal loop.** No safe place to return to sell, stash and
  craft between runs. Outposts are the nearest thing, and are a colony
  system rather than a hub.

### Persistence (core for a sandbox)

- **World state is not saved.** Blocks you dug, what you built, outposts,
  followers and the seed are all lost when the session ends. For a voxel game with
  building and outposts this contradicts the systems themselves.
- **One hero per class.** `HeroSaveStore` keys by class id.

### Feel

- **Audio.** There is none: no sound code anywhere in the app.
- **Hit feedback** is good (knockback, flashes, floating text, combat
  theatre). Keep it.

---

## 5. Sandbox and creator gaps

These matter as much as the ARPG gaps, because the product is the
*sandbox*, not the game.

1. **A Project (or Game) as the unit of work.** Missing entirely; see §3.1.
2. **One AI world generator.** World generator and Agent studio overlap; the
   first loses its output. Keep the Agent studio (it is journaled, checked
   and installs as a plugin) and make the quick generator a one-click
   "default crew" preset of it.
3. **A way to turn systems off.** A creator should pick which systems their
   game has: mining/building, survival, factions, outposts/raids, tabletop
   checks. Today they are always compiled into the one session.
4. **An editor mode.** There is no in-app way to place a boss, mark a quest
   giver, draw a dungeon or set a spawn. The only authored-layout path is to
   make a Tiled map on a computer and import it.
5. **Assets bound to content.** A sprite sheet made in the sprite forge is
   picked on the home screen as "Look" for the player, and matched to
   monsters in `StratumApp`'s sprite resolver by spreading whatever monster
   sheets exist across enemy ids when a pack names none. It should be
   attached to a class or monster definition inside the project, and ride
   along on export.
6. **Full export.** Export the project as one `.stratum` with its pack,
   textures and sheets (the archive already supports both), and later as a
   standalone APK that boots straight into that game.
7. **Replace, not only extend, the built-in pack.** The app always assembles
   `IgboContentPack.pack` first and every export depends on `igbo`, and the
   interface theme is fixed to `IgboContentPack.palette` in `MainActivity`.
   A creator's game should be able to stand alone.

---

## 6. Robustness

- **Tests where the code is newest.** `:feature:forge` (7,504 lines),
  `:feature:hero`, `:feature:studio` and `:legacy:domain` have no tests;
  `:feature:play` has 45 lines. The view-models there hold real logic
  (`PoseForgeViewModel` is 1,228 lines, `PlayViewModel` 1,079). Move the
  logic into the pure modules where it can be tested headlessly.
- **`WorldSession` is a god object.** Splitting it into systems registered by
  the project (see §7) is both the focus fix and the robustness fix.
- **`StratumApp` is the whole app's state holder.** 798 lines of
  `remember`ed state and lambdas wiring thirteen destinations. State that
  outlives a screen (forged packs, chosen sheet, equipped weapon, world
  rules) should live in a project repository, not in composition.
- **Building needs the Android SDK.** The pure-Kotlin modules (`:core:domain`,
  `:engine:*`, `:plugins`, `:agents`, `:importer:*`, `:tools:artpreview`) can
  be tested on any JVM; the Android modules and the APK need the SDK, which CI
  provides. This review was done by reading the code, not by running the app.
- **Identity leftovers.** `applicationId` is `com.aistudio.igboarpg.omagvd`
  and `metadata.json` still describes the old "Igbo ARPG Engine" with Gemini.
  Settle the name before anything is published.

---

## 7. The shape that gives it focus

One noun, one pipeline, one engine.

```
  ┌──────────── PROJECT (one .stratum, saved as you go) ────────────┐
  │                                                                 │
  │  1. Setting     prompt the crew, or import Tiled / Flame / pack │
  │  2. Assets      textures, characters, weapons: bound to the     │
  │                 blocks, classes and monsters that use them      │
  │  3. Rules       which systems are on, difficulty, world rules   │
  │  4. Content     classes, skills, monsters, bosses, items,       │
  │                 dungeons, quests: forms, or ask the crew        │
  │  5. Playtest    the same engine a player gets                   │
  │  6. Export      .stratum now; standalone APK later              │
  │                                                                 │
  └─────────────────────────────────────────────────────────────────┘
```

- **Project** = a `ContentPack` plus its art plus its `WorldRules` plus a
  list of enabled systems, stored as one plugin folder. Every tool opens *in*
  a project and writes *to* it. The home screen becomes "your games" and
  "play".
- **Systems as modules of the session.** `WorldSession` keeps movement,
  combat, loot and progression. Mining/building, survival, factions/towns,
  realm/raids and tabletop become systems the project switches on, each with
  its own panel that only appears when it is on. That turns the modpack into
  a menu the creator chooses from.
- **One engine.** Port what is worth keeping from the legacy engine
  (projectiles, trigger-based mechanics) into `:engine:world` and delete
  `:legacy:*` and `:feature:studio`. One AI provider adapter.
- **One of each tool.** One world generator (the crew), one weapon forge,
  one sprite import path, one stat vocabulary.

---

## 8. Roadmap

Each phase leaves the app shippable.

### Phase 1: Focus (no new features)

1. Introduce `Project`: pack + art + rules + enabled systems, persisted as a
   plugin folder. Move the forge / crew / class / sprite stores to write into
   the open project.
2. Save the World generator's output (or fold it into the crew as a preset).
3. Export the whole project, art included, through the existing
   `PluginArchive.write(textures, sheets)`.
4. Let a project stand alone from `igbo`, including its interface palette.
5. Hide the Creator studio tile; freeze `:legacy:*` for porting.

### Phase 2: A real ARPG core

1. Equipment slots: armour, helm, gloves, boots, two rings, amulet, offhand.
   Items roll `StatModifier`s; retire `AffixStat`.
2. Projectiles and a small, composable skill model (delivery × area ×
   effect) replacing the three shapes; pack schema to match.
3. Status effects and damage-over-time, keyed off damage types.
4. Monster skills with telegraphs, using the same skill model as the
   player. Ranged monsters shoot; support monsters heal and buff.
5. Health and resource regen, and potions.

### Phase 3: Structure

1. Dungeons: an entrance in the world opens a generated or authored level
   (reuse `TerrainGenerator` and the Tiled path).
2. Bosses: phases and mechanics as data, on the monster skill model.
3. A hub with a vendor and a stash; outposts can serve as hubs when the
   realm system is on.
4. Quests as data: objectives (kill, collect, reach, talk), rewards, and
   chains. The crew gets a quest-writer role.
5. Save world state per project and seed; several heroes per class.

### Phase 4: Systems as options, and an editor

1. Split `WorldSession` into toggled systems; panels follow.
2. An editor mode in play: place spawns, bosses, NPCs, dungeon entrances and
   quest markers, written back to the project's map.
3. Audio hooks in the pack schema.

### Phase 5: Ship a game

1. Export a project as a standalone APK that boots into it.
2. Retire `:legacy:*` and `:feature:studio`.
