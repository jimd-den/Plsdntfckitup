package com.stratum.core.domain.settlement.culture

import com.stratum.core.domain.settlement.BuildingRole

/** How big a settlement is, against its recipe's own radius: hamlets shrink it, capitals stretch it. */
enum class Scale(val radius: Float) { SMALL(0.8f), MEDIUM(1f), LARGE(1.55f) }

/**
 * How a settlement's streets and buildings are arranged on the ground.
 * Each is a distinct way African towns have actually been laid out.
 */
enum class Pattern(val label: String) {
    /** Houses in a ring round a cattle byre or a meeting tree, a fence round both: the umuzi, the enkang. */
    RINGED("Ringed homestead"),
    /** Walled family compounds scattered through farmland, joined by paths to a shared square. */
    DISPERSED("Dispersed compounds"),
    /** One great compound: courtyards opening into courtyards. */
    COMPOUND("Single compound"),
    /** Rows stepping up a scarp, each a terrace along the contour. */
    TERRACES("Cliff terraces"),
    /** A tight fortified block, narrow lanes, granaries at its heart. */
    KSAR("Fortified block"),
    /** A wall pierced by gates, a road from each gate to the centre, quarters between. */
    WALLED("Walled city"),
    /** A palace or a shrine at the centre of everything, broad ways radiating from its court. */
    RADIAL("Radial capital"),
    /** Close-packed houses on winding lanes, a waterfront along one side. */
    LANES("Stone lanes"),
    /** A walled enclosure on the height, the town spread in the valley below. */
    CITADEL("Hill citadel"),
    /** Strung along one way: a river bank, a ridge road, a caravan track. */
    LINEAR("Linear street"),
}

/** What kind of place a settlement is. */
enum class Form(val label: String, val scale: Scale, val pattern: Pattern, val city: Boolean) {
    HOMESTEAD("Homestead", Scale.SMALL, Pattern.COMPOUND, false),
    KRAAL("Kraal homestead", Scale.SMALL, Pattern.RINGED, false),
    CAMP("Thorn-fenced camp", Scale.SMALL, Pattern.RINGED, false),
    HAMLET("Hamlet", Scale.SMALL, Pattern.DISPERSED, false),
    TOWER_HAMLET("Tower-house hamlet", Scale.SMALL, Pattern.DISPERSED, false),
    VILLAGE("Village", Scale.MEDIUM, Pattern.DISPERSED, false),
    VILLAGE_GROUP("Village-group", Scale.LARGE, Pattern.DISPERSED, false),
    CLIFF_VILLAGE("Cliff village", Scale.MEDIUM, Pattern.TERRACES, false),
    KSAR("Ksar", Scale.MEDIUM, Pattern.KSAR, false),
    OASIS("Oasis village", Scale.MEDIUM, Pattern.LINEAR, false),
    FISHING_VILLAGE("Fishing village", Scale.MEDIUM, Pattern.LINEAR, false),
    MARKET_TOWN("Market town", Scale.MEDIUM, Pattern.RADIAL, true),
    RIVER_PORT("River port", Scale.MEDIUM, Pattern.LINEAR, true),
    WALLED_CITY("Walled city", Scale.LARGE, Pattern.WALLED, true),
    CARAVAN_CITY("Caravan city", Scale.LARGE, Pattern.WALLED, true),
    STONE_TOWN("Stone town", Scale.LARGE, Pattern.LANES, true),
    ROYAL_CAPITAL("Royal capital", Scale.LARGE, Pattern.RADIAL, true),
    SACRED_CITY("Sacred city", Scale.LARGE, Pattern.RADIAL, true),
    HILL_CITADEL("Hill citadel", Scale.LARGE, Pattern.CITADEL, true),
}

/** Who rules, and what their seat is called: the building at the heart of the town's affairs. */
enum class Governance(val label: String, val seat: String) {
    ELDERS("Council of elders", "Elders' meeting house"),
    TITLED("Titled society", "Lodge of the titled"),
    ASSEMBLY("Assembly of all the people", "Assembly house"),
    PRIEST_KING("Priest-king", "Obi of the priest-king"),
    OBA("Oba and palace chiefs", "Palace of the oba"),
    STOOL("Paramount chief of the stool", "Stool house"),
    EMIR("Emir and his council", "Emir's palace"),
    SULTAN("Sultan and the great houses", "Sultan's house"),
    NEGUS("Negus and the nobles", "Royal enclosure"),
    MAMBO("Mambo on the hill", "Royal enclosure on the hill"),
    AGE_SETS("Age-sets and their elders", "Elders' shade house"),
    MERCHANTS("Council of merchant houses", "Council house"),
    QUEEN_MOTHER("Queen mother's court", "Court of the queen mother"),
    HOGON("Hogon", "House of the Hogon"),
    SCHOLARS("Council of scholars", "Scholars' hall"),
    HEADMAN("Headman and household heads", "Headman's house"),
}

/**
 * What a settlement makes and trades, and the building its makers work in.
 * A town takes one to three; the first is what it is famous for.
 */
enum class Craft(val label: String, val noun: String, val workplace: String, val role: BuildingRole) {
    IRON("Iron smelting", "iron hoes and blades", "Smelting furnace", BuildingRole.SMITHY),
    BRONZE("Bronze casting", "lost-wax bronzes", "Casters' yard", BuildingRole.SMITHY),
    GOLD("Goldsmithing", "gold weights and jewellery", "Goldsmiths' house", BuildingRole.SMITHY),
    COPPER("Copper working", "copper crosses and wire", "Copper furnace", BuildingRole.SMITHY),
    WEAVING("Strip weaving", "narrow-strip cloth", "Weavers' shed", BuildingRole.SHOP),
    INDIGO("Indigo dyeing", "indigo cloth", "Dye pits", BuildingRole.SHOP),
    POTTERY("Pottery", "coiled and fired pots", "Potters' kiln yard", BuildingRole.SHOP),
    CARVING("Woodcarving", "masks, doors and stools", "Carvers' court", BuildingRole.SHOP),
    BEADS("Beadwork", "glass and stone beads", "Bead-makers' house", BuildingRole.SHOP),
    LEATHER("Leatherwork", "tooled leather", "Tannery", BuildingRole.SHOP),
    BASKETRY("Basketry", "coiled baskets and mats", "Basket-makers' shade", BuildingRole.SHOP),
    SALT("Salt", "salt slabs", "Salt store", BuildingRole.WAREHOUSE),
    KOLA("Kola", "kola nuts", "Kola store", BuildingRole.WAREHOUSE),
    PALM_OIL("Palm oil", "palm oil", "Palm-oil press", BuildingRole.WAREHOUSE),
    PALM_WINE("Palm wine", "palm wine", "Palm-wine house", BuildingRole.TAVERN),
    YAMS("Yam farming", "great yams", "Yam barn", BuildingRole.FARM),
    MILLET("Millet and sorghum", "millet and sorghum", "Granary court", BuildingRole.FARM),
    CATTLE("Cattle", "cattle", "Cattle byre", BuildingRole.FARM),
    COFFEE("Coffee", "coffee", "Coffee house", BuildingRole.TAVERN),
    HONEY("Honey", "honey and wax", "Hive house", BuildingRole.FARM),
    FISHING("Fishing", "smoked fish", "Fish-smoking shed", BuildingRole.WAREHOUSE),
    BOATS("Boatbuilding", "canoes and dhows", "Boat yard", BuildingRole.WAREHOUSE),
    CARAVANS("Caravan trade", "caravan goods", "Caravanserai", BuildingRole.TAVERN),
    MANUSCRIPTS("Scholarship", "manuscripts", "Library", BuildingRole.TEMPLE),
}

/** What stands at a town's heart: where it was founded, and what it gathers round. */
enum class Heart(val label: String) {
    SACRED_TREE("A sacred silk-cotton tree"),
    IROKO("An iroko where the paths cross"),
    BAOBAB("A great baobab"),
    EARTH_SHRINE("The earth shrine"),
    SHRINE_GROVE("A shrine grove"),
    MBARI("An mbari house of painted figures"),
    MASK_GROUND("The masquerade ground"),
    MOSQUE("A great earthen mosque"),
    CORAL_MOSQUE("A coral-stone mosque"),
    CHURCH("A church cut from the rock"),
    STELE("A field of carved stelae"),
    CONICAL_TOWER("A conical stone tower"),
    TOGUNA("A toguna, the low house of words"),
    CATTLE_BYRE("The cattle byre"),
    PALACE_COURT("The palace court"),
    MARKET_TREE("The market tree"),
    WELL("The old well"),
    ANCESTOR_HOUSE("The house of the ancestors"),
}

/** A part of a town given to one use. Each becomes a cluster of buildings of its [role]. */
enum class Quarter(val label: String, val building: String, val role: BuildingRole?) {
    SEAT("The seat", "Seat", BuildingRole.HALL),
    COMPOUNDS("Family compounds", "Compound house", BuildingRole.HOUSE),
    MARKET("Market", "Market stall", BuildingRole.SHOP),
    SHRINES("Shrines", "Shrine", BuildingRole.TEMPLE),
    CRAFTS("Craft quarter", "Workshop", BuildingRole.SMITHY),
    GRANARIES("Granaries", "Granary", BuildingRole.WAREHOUSE),
    FIELDS("Farms and pens", "Farm house", BuildingRole.FARM),
    STRANGERS("Strangers' quarter", "Rest house", BuildingRole.TAVERN),
    GATES("Gates and watch", "Gatehouse", BuildingRole.TOWER),
    GUARD("Guard", "Guard house", BuildingRole.BARRACKS),
    AGE_GRADES("Age-grade houses", "Age-grade house", BuildingRole.HOUSE),
    SCHOOL("School", "School", BuildingRole.TEMPLE),
    WATERFRONT("Waterfront", "Store on the water", BuildingRole.WAREHOUSE),
    SQUARE("Village square", "Square", null),
}
