# The African world: geology and architecture

The microvoxel world (`stratum:microvoxel`, preset `micro:ancient`, and the
bundled *Microvoxel Realms* plugin) builds its land from Africa's real
geology. It builds its towns in the building traditions of the peoples who
live on that land. Everything here is data plus small, pure functions, so a
plugin can add a province or a tradition without touching the rest.

Screenshots of every province and tradition are in
[`docs/screenshots/africa/`](screenshots/africa/).

## How the land is made

Most of Africa is very old rock:

- Archaean cratons, whose granites and gneisses are worn flat and weathered
  deep into laterite under tropical rain.
- Younger sediments laid over them in basins: the Congo, the Karoo and the
  Sahara's sandstones.
- Flood basalts poured out where the crust stretched: Ethiopia and the
  Drakensberg.
- A rift still opening down the east, and one fold belt in the north-west
  (the Atlas).
- The deserts' sand, gravel and salt on top of it all.

The generator (`engine/microvoxel/.../geo`) models this in three layers:

1. **Provinces** (`Provinces.kt`). Each province is a kind of country. It has
   the following parts:
   - A list of **processes** (`Processes.kt`): a landform first, then what
     works over it. See [Geological processes](#geological-processes).
   - A **weathering profile** that follows the surface.
   - **Bedding**: bands of rock fixed to the province's floor, so both walls
     of a gorge show the same stripes. Bedding is folded where the province is
     folded, and capped by a harder rock above a set height where the province
     has one.
   - **Surface rules**: soil, crust, sand or salt, scree on slopes, bare rock
     on cliffs, lakes in hollows.
   - A **fertility** value that scales all vegetation.
   - Its own **trees**.
2. **The atlas** (`GeoAtlas.kt`) cuts the world into regions about 500
   blocks across. Each region takes the province that best fits the climate,
   tectonics and elevation at its heart. Climate varies over thousands of
   blocks, so provinces form coherent zones rather than a patchwork. Borders
   wander, and the landforms of neighbouring provinces blend over 55 blocks.
   Everything is sampled once per block on a world-aligned lattice, so it
   stays within a phone's budget (see `GeologyTest` and `GeoBenchTest`).
   The atlas also runs the **simulated** processes (`Drainage.kt`): water,
   rivers and scree, which need a node's neighbours and not just a point.
3. **Features** (`micro:features`) that a height field cannot make: termite
   mounds, balancing-rock tors and freestanding sandstone arches.

### The twenty-four provinces

| Province | Real places | What you see |
|---|---|---|
| Laterite plateaus | Fouta Djallon, Mossi plateau, the bowé of Guinea | Iron-crust mesas with pale mottled clay showing in their breakaway scarps |
| Granite inselbergs | Idanre, Olumo Rock, Zuma Rock, Matobo Hills, Spitzkoppe | Bare granite domes and castle kopjes on a flat plain, with tors |
| Rainforest basin | Cuvette Centrale of the Congo | Low red-soiled country under closed forest, broad rivers |
| Guinean forest hills | Igbo and Yoruba uplands, the Agulu–Nanka gullies, Ashanti | Rolling hills torn by deep gully erosion in friable Nanka sand |
| Sahel floodplain | Inner Niger Delta margins, Lake Chad plain, the Gezira | Black cracking clay, seasonal pools, baobab, termite mounds |
| Rift valley | Kenyan rift (Longonot, Suswa), Lake Natron, the Afar | Stepped fault scarps, volcanoes and calderas, red soda lakes with trona |
| Basalt traps and ambas | Simien, the Blue Nile gorge, Amba Aradam | Flow-banded cliffs with red boles, flat-topped ambas, deep gorges |
| Volcanic necks | Rhumsiki, Mandara, Mount Cameroon | Phonolite spires over green hills, cinder cones |
| Sandstone escarpment | Bandiagara, Ennedi, Tassili n'Ajjer | One sheer banded cliff, pillars and arches stranded below |
| Sand sea | Grand Ergs, Erg Chebbi, the Ténéré | Seif dunes with slip faces lined up with the wind, star dunes |
| Reg and hamada | Tanezrouft, Hamada du Draa, the Borkou | Gravel plains, varnished tablelands, wind-cut yardangs |
| Salt pans | Makgadikgadi and Kubu Island, Etosha, Chott el Djerid | Flat white crust buckled into polygons, granite islands |
| Kalahari sandveld | Central Kalahari | Red sand, low fossil dunes, calcrete pans |
| Namib dunes | Sossusvlei, the Skeleton Coast | The tallest dunes on earth, beside gravel flats |
| Karoo mesas | Great Karoo, Valley of Desolation | Flat-lying shale, every mesa and koppie capped with dolerite |
| Drakensberg | The Amphitheatre, Golden Gate | Golden sandstone cliffs under a wall of basalt |
| Atlas fold belt | High Atlas, Todra and Dades gorges | Parallel ridges whose rock layers bend with them, slot gorges |
| Tsingy karst | Bemaraha, Ankarana | A limestone plateau dissolved into razor pinnacles |
| Coral coast | Lamu, Mombasa, Zanzibar, Kilwa | Raised reef terraces and white coral beaches: the Swahili towns' stone |
| Delta and mangrove | Niger delta, Rufiji, Okavango | Creeks and levees at the tide line, mangroves on the mud |
| Canyon country | Fish River Canyon, Blyde River Canyon, the Tekezé gorge | A stony plateau cut by one looping canyon; flat Nama beds over tilted gneiss, a pebble bed on the unconformity |
| Dune cordon and lagoon | Maputaland and Wild Coast cordons; the Lagos, Ébrié and Keta lagoons | Forested sand ridges along the shore, a long still lagoon behind them |
| Montane plateau | Nyika, the Jos Plateau, the Bamenda highlands, the Aberdares | High rolling grassland, granite knolls, forest and a stream in every fold |
| Basement shield | Zimbabwe craton and the Great Dyke, Barberton, the Man shield and Nimba, the Ashanti belt | Worn roots of the oldest crust: quartzite and iron ridges, black dykes, gold-bearing reefs |

The four newest earn their place because nothing else made them. The canyon
shows the whole stack of time in one wall. The cordon is the West and East
African coast where it is not reef or delta. The montane plateau is the cool
green high country the traps and the Drakensberg are too harsh for. The
shield is where Africa's gold, iron and copper come from.

## Geological processes

A province is an ordered list of **steps**, each naming a process in the
`GeoProcesses` registry with its settings as strings:

```kotlin
processes = listOf(
    step("karoo"),                                    // the landform
    step("erosion", "strength" to 0.7f),              // its share of the simulation
    step("rivers", "rain" to 0.35f),
    step("scree", "amount" to 1.1f),
    step("dykes", "chance" to 0.8f, "width" to 4f),   // what cuts its rock
    step("concretions", "material" to R.CALCRETE),
)
```

A pack adds a kind of geology by calling `GeoProcesses.register("mypack:mesas", ...)`,
and a kind of country by listing steps. There are three kinds of process.

- **Relief** shapes the land, once per lattice node, over what the steps
  before it made. There are 24 landforms (`mode` = replace, max, add or min,
  and `weight`), and three modifiers:
  - `barchans`: crescent dunes marching downwind over the reg and between
    the seifs (Kharga, Lüderitz, Tarfaya).
  - `sinkholes`: dolines in limestone (Bemaraha, the Mahafaly plateau, the
    Middle Atlas).
  - `faults`: blocks dropped and lifted along the grain, with sharp scarps
    (the Kenyan and Ethiopian rift shoulders, the Fish River graben).
- **Simulated** processes run over the whole land on a coarse lattice (one
  node every 8 blocks), in tiles of 48 x 48 nodes with a 12-node apron. A
  province only sets its rates. Within a tile:
  1. A priority-flood (Barnes, Lehman and Mulla 2014) fills pits and gives
     every node its receiver.
  2. Flow accumulation gives each node its catchment.
  3. Stream-power erosion cuts valleys, and sediment is dropped where the
     slope eases, as **alluvial fans** and floodplain spreads.
  4. Nodes whose catchment passes a threshold become **river** segments,
     whose water level only falls downstream. Where they are carved in, they
     get a channel with a gravel bed, **levees**, and dark **floodplain silt**
     beside them. In dry provinces they are **wadis** of gravel with no water.

  Neighbouring tiles cross-fade their erosion at the seams, and each river
  segment belongs to exactly one tile. **Scree** is a bounded max-filter:
  rubble lies against every cliff's foot at its angle of rest. Everything
  is a pure function of world coordinates, so any chunk comes out the same
  in any order (`GeoProcessTest`).
- **Rock** processes rewrite one column's rock as it is laid down, below
  the soil, with no noise per voxel:
  - `dykes`: dolerite walls (the Karoo's, the Great Dyke).
  - `veins`: quartz and gold reef (the Ashanti and Zimbabwe belts).
  - `ore_lenses`: bauxite, malachite and banded iron.
  - `concretions`: ironstone and calcrete nodules in mudrock.
  - `cross_bedding`: the Clarens and Tassili fossil dunes.
  - `unconformity`: flat beds over bevelled gneiss (the Fish River Canyon,
    Sea Point).
  - `columnar_joints`: in basalt (the Blue Nile gorge, the Drakensberg).

  Fossil bands (fossil limestone, the Karoo's bone bed) are part of the
  provinces' bedding.

### Dials

The terrain stage takes five dials, each 0..2 with 1 as shipped. They are
in the World panel, and they are scene keys of the same names:

- `erosion`
- `rivers` (0 means none)
- `dunes`
- `scree`
- `rockDetail`

## How towns are built

Towns are planned by the same planner as always. Where each building, door
and window stands is a gameplay fact and never changes. What a town *looks*
like comes from a **tradition** (`engine/microvoxel/.../arch`). With
`style = regional` (the default), each town takes a tradition native to the
province it stands in. `Traditions.native` holds the weights. The home town
can be set apart with `homeStyle`; the shipped world uses `igbo`, the
built-in pack's people.

Every tradition follows one rule so that the game plays exactly as planned.
Inside a wall cell, every voxel except the outer skin is the plan's own wall
block. Doors stay open two blocks high, and windows stay the window block.
Everything else is free: the skin, ornaments beyond the wall, roofs,
pinnacles, domes and turrets.

### The eighteen traditions

| Tradition | Where and when | Signature in the game |
|---|---|---|
| Igbo | South-eastern Nigeria | Red earth on a plinth, uli designs in white nzu, steep ragged thatch, round huts, iroko |
| Yoruba | Oyo, Ife, Ibadan | Deep hipped roofs over verandas on carved posts (after Olowe of Ise) |
| Asante | Kumasi, 18th–19th c. (UNESCO) | Red-burnished dado, white walls with red relief spirals, steep thatch |
| Benin (Edo) | Benin City | Ribbed red laterite walls, roofs sloping inward to an open impluvium |
| Sudano-Sahelian | Djenné, Timbuktu, Mopti, Agadez, 13th c.+ | Pilasters rising into pinnacles, toron beams, flat roofs, rendered adobe |
| Hausa | Kano, Zaria, Katsina | Zanko horns at the parapet corners, zayyana relief around the door |
| Dogon | Bandiagara (UNESCO) | Flat mud houses with niched facades, granaries under millet-thatch hats, toguna |
| Batammariba | Koutammakou (UNESCO) | Tower-houses of round turrets under conical thatch caps |
| Kassena | Tiébélé, Burkina Faso | Walls painted in black, white and red geometric designs |
| Musgum | Logone floodplain | Pointed shell domes of earth with raised chevron ribs |
| Swahili | Kilwa, Gedi, Lamu, Zanzibar, 12th c.+ | Whitewashed coral stone, stepped merlons, carved doors, baraza benches, pillar tomb |
| Aksumite and Ethiopian | Aksum to Debre Damo | Stone walls with "monkey head" beam ends, tukul with pot finial, stele |
| Nubian | Aswan to Dongola | Nubian vaults, whitewash with blue and yellow door paintings |
| Amazigh | Aït Benhaddou, Draa and Dades (UNESCO) | Pisé with battered corner towers, lozenge-worked crowns, crenellations |
| Great Zimbabwe | 11th–15th c. (UNESCO) | Coursed dry-stone granite with a chevron frieze, conical tower, daga huts |
| Ndebele | Mpumalanga | White walls with bold black-outlined colour panels |
| Zulu | KwaZulu-Natal | Woven-grass beehive houses round a cattle kraal |
| Maasai and Himba | The Rift, the Kaokoveld | Low loaf-shaped dung houses inside a thorn fence |

These are respectful, simplified game interpretations of living and
historic traditions, drawn at a quarter-block scale. They are not
architectural reconstructions. Every tradition's `origin` text is shown in
the World panel.

### Invented towns, within each land's grammar

About a third of regional towns (the `parametric` option of
`micro:settlements`) are not drawn by their tradition's fixed painter. Each
building is rolled from a genome inside that tradition's vernacular grammar
(`Vernacular`): its plans, roof forms, storeys, wall relief, openings, ornament
and exact materials.

- A Hausa quarter gets new courtyard houses with zanko pinnacles and
  zayyana relief, and never a gable roof.
- A Swahili town gets new storeyed coral-stone houses.
- Great Zimbabwe gets new round dry-stone enclosures and conical towers.

The town keeps its tradition's compound wall and sacred heart. See
[COZY-BUILDER.md](COZY-BUILDER.md#parametric-buildings-and-vernacular-towns).

## Changing it

- **In game:** open **⛰ World**. *Landscape* picks all of Africa, or one
  province everywhere. *Towns & home → Building tradition* picks
  `regional` or one tradition. *Home tradition* sets the home town.
- **In a pack:** set `micro:terrain` to `{"geology": "africa", "home": "forest_hills", "rivers": "1.5"}`
  and `micro:settlements` to `{"style": "regional", "homeStyle": "igbo"}`.
  See `MicrovoxelTerrainGenerator.catalogue` for every option.
- **In code:** add a `Province` to the list, or a `Tradition` painter to
  `Traditions.all`, and give it a place in `Traditions.native`.

## Seeing it

```
./gradlew :tools:microvoxelpreview:geoPreview             # province map + one diorama per province
./gradlew :tools:artpreview:microScenePreview --args="out content/igbo/src/main/resources/forge 20260928 traditions"
```
