# Design notes: where Stratum stands against Diablo IV and Path of Exile

This is the gap analysis behind the world, crowd, survival, strategy and
studio work: what the two reference action RPGs do, what they do not, and
what Stratum does about each. It is a design document, not a feature list
-- the point is to be honest about what is borrowed, what is new, and
what is still missing.

## What the genre leaders do well

| System | Diablo IV | Path of Exile 2 | Stratum |
| --- | --- | --- | --- |
| Build depth | Skill tree plus paragon boards and glyphs; seasonal build churn | A very large passive tree, support gems, ascendancies | Plugin-defined or generated passive tree, support gems that tune and convert skills, one modifier formula (flat / increased / more) for everything |
| Item crafting | Tempering and masterworking | Currency orbs that each do one thing to an item | Currency verbs (imbue, reforge, ascend, temper, annul, socket, scour), each a plugin-nameable currency |
| Endgame loop | Helltides, the Pit, Infernal Hordes (wave survival with chosen boons), Nightmare dungeons, world bosses; War Plans to schedule a session's activities | The Atlas of Worlds: waystones open maps, towers apply tablets to regions of the atlas | World tiers opened by champions, waystones with mods that make a world harder and pay for it |
| Open world | A shared open world with regions, strongholds that become towns once cleared, renown per region | Mostly instanced maps | Endless generated worlds; hostile towns are strongholds that become the player's outposts when liberated |
| Factions | Renown is a per-region completion meter rather than a relationship | Little; Last Epoch's two trading factions are the genre's closest example of a faction choice changing the economy | Factions with stances, relations, reputation that moves with who you kill, ranks that pay out modifiers, towns that open or close to you |
| Monster crowds | Dense, readable packs; elites with affixes | Very dense packs, rare monsters with mods | Flow-field crowds with roles (melee, ranged, support, swarmer, brute), attack tokens so only a few swing at once, squads that alert together and can break when their leader falls |

## What neither does

These are the gaps the recent work targets. None is a criticism of the
reference games -- most are deliberate choices for a live-service game --
but each is room for a different kind of game.

1. **The world is theirs, not yours.** Neither ships a supported way to
   author a setting. Stratum's plugin format covers blocks, regions,
   terrain, classes, skills, monsters, factions, towns, warbands, food,
   outposts, rules and the studio crew itself, as data a person can write
   by hand -- enough for a Warhammer-like or any specific lore RPG without
   code ([example](../examples/plugins/ashen-crusade)).
2. **Towns are scenery.** In both, a town is a hub with vendors. Stratum
   generates towns from plugin recipes into the terrain itself -- grid
   cities, organic villages, walled fortresses, war camps -- owned by
   factions, garrisoned, and capturable.
3. **No holdings.** There is nothing to build, stock, defend or lose.
   Stratum adds outposts: found one with blocks, stock it with what you
   carry, build structures that produce and house, train soldiers,
   survive raids that grow with your wealth, and take followers into the
   field under simple orders. Survival-ARPG hybrids such as V Rising show
   the appetite for a base with servants; Stratum's is abstracted to a few
   taps so it suits a phone.
4. **No body.** Health and a resource are the only needs. Stratum has
   hunger, thirst and warmth, regional temperature and nights, foraging,
   cooking at fires -- as a dial from off to harsh, because the same world
   should be able to be a relaxed story or a survival siege.
5. **One way to play.** Difficulty tiers change numbers; they do not
   change what kind of game it is. Stratum's world rules and presets
   (Adventure, Story, Survivor, Conqueror) change survival, town and
   monster density, raids, day length and death penalty per world.
6. **Content comes from a studio you cannot see.** Stratum's agent studio
   lets a player run a crew of AI roles that write a pack the way a small
   team would, with every prompt, reply, rejection and approval on the
   record, and every result held to the same checks as a hand-written
   plugin.
7. **Mobile is an afterthought.** Both are designed for a mouse or a pad.
   Stratum's HUD is built for thumbs in either orientation, and every
   system above surfaces as a dock button and a panel, not a menu tree.

## What Stratum still lacks

Being honest about the other direction:

- **Trading and a shared world.** Both reference games are social; Stratum
  is single-player and offline-first.
- **Seasons and a live economy.** Neither is planned; plugins are the
  answer to "something new to play".
- **Boss design.** Champions exist, but not multi-phase encounters with
  telegraphed mechanics.
- **Unique items and set bonuses.** Affixes and inserts exist; build-
  defining uniques do not yet.
- **Wave modes.** Raids on outposts are the closest thing to Infernal
  Hordes; an opt-in arena with chosen boons would be a small step from
  there.
- **Diplomacy.** Reputation moves with who you kill; there are no quests,
  tribute or treaties yet.

## Sources

- Diablo IV endgame and activities: maxroll.gg/d4/resources/infernal-hordes,
  maxroll.gg/d4/resources/helltide-guide, maxroll.gg/d4/resources/war-plans,
  diablo4.wiki.fextralife.com/Endgame+Guide
- Diablo IV quality-of-life and build commentary: icy-veins.com (D4 QoL),
  charlieintel.com (D4 builds)
- Path of Exile 2 atlas and mapping: maxroll.gg/poe2/resources/atlas-of-worlds-and-mapping,
  poe2hub.net/mechanics/atlas-waystones-towers
- Path of Exile 2 reviews and respec costs: elyxir.com (PoE2 0.5 review),
  comicbook.com (PoE2 respecs)
- Last Epoch factions: gamerant.com
- Base-building ARPG hybrids: supercraft (V Rising castle guide),
  switchbladegaming (V Rising servants)
- Crowd techniques: howtorts.github.io (flow fields), jdxdev (flow fields),
  marian42.de/article/wfc (constraint-based generation)
