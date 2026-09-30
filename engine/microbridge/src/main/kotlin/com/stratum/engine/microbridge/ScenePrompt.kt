package com.stratum.engine.microbridge

import com.stratum.core.domain.world.PassSpec
import com.stratum.core.domain.world.WorldRules
import com.stratum.engine.microvoxel.arch.MaterialFamilies
import com.stratum.engine.microvoxel.arch.PlanShape
import com.stratum.engine.microvoxel.arch.RoofForm
import com.stratum.engine.microvoxel.arch.Traditions
import com.stratum.engine.microvoxel.geo.Provinces
import com.stratum.engine.microvoxel.gen.StageSpec

/**
 * A world described as a scene: every dial the generator has, as named,
 * bounded parameters a person or a language model can write.
 *
 * Two ways in. [ScenePrompt.read] understands plain words at once, with no
 * model -- "the red dunes of the Sahara with a walled kasbah town" picks the
 * erg, the Amazigh tradition, walls and sparse life. [ScenePrompt.parse]
 * reads the JSON a language model writes when given [ScenePrompt.systemPrompt],
 * which lists every key, its range and its words. Either way the result is a
 * [SceneSpec]: checked values only, and a line per choice saying what it did,
 * so a player sees how their words were read before the world is built.
 */
data class SceneSpec(val values: Map<String, String>, val notes: List<String> = emptyList()) {

    val isEmpty: Boolean get() = values.isEmpty()

    fun float(key: String): Float? = values[key]?.toFloatOrNull()

    operator fun get(key: String): String? = values[key]

    /** Lays the scene over [base], the stages the world would otherwise run. */
    fun passes(base: List<StageSpec>): List<StageSpec> {
        var specs = base
        fun set(id: String, options: Map<String, String?>) {
            val clean = options.filterValues { it != null }.mapValues { it.value!! }
            if (clean.isEmpty()) return
            val existing = specs.firstOrNull { it.id == id }?.options.orEmpty()
            specs = MicrovoxelTerrainGenerator.withStage(specs, StageSpec(id, existing + clean))
        }
        fun drop(id: String) { specs = specs.filterNot { it.id == id } }

        set(TERRAIN, mapOf(
            "geology" to this["landscape"], "home" to this["home"]?.takeIf { (this["landscape"] ?: "africa") == "africa" },
            "height" to this["relief"], "mountains" to this["mountains"], "scale" to this["breadth"], "terrace" to this["plateaus"],
            "erosion" to this["erosion"], "rivers" to this["rivers"], "dunes" to this["dunes"], "scree" to this["scree"], "rockDetail" to this["rockDetail"],
        ))
        this["features"]?.let { set(FEATURES, mapOf("density" to it)) }
        when (val caves = float("caves")) {
            null -> Unit
            0f -> drop(CAVES)
            else -> set(CAVES, mapOf("threshold" to fmt(0.04f + caves * 0.2f)))
        }
        val vegetation = float("vegetation")
        set(GROUNDCOVER, mapOf("density" to (this["grass"] ?: vegetation?.let { fmt(it) }), "tall" to this["tallGrass"]))
        set(TREES, mapOf("density" to (this["trees"] ?: vegetation?.let { fmt(it * 0.85f) }), "style" to this["forest"]))
        if (float("trees") == 0f) drop(TREES)
        set(SettlementsStage.ID, mapOf(
            "density" to this["towns"], "style" to this["style"], "homeStyle" to this["homeStyle"], "parametric" to this["invented"],
            "homeSize" to this["homeSize"], "homeWalls" to this["walls"],
            "plans" to this["plans"], "roofs" to this["roofs"], "materials" to this["materials"], "storeys" to this["storeys"],
            "ornament" to this["ornament"], "towers" to this["towers"], "variety" to this["variety"],
        ))
        float("cities")?.let { cities ->
            if (cities <= 0f) { drop(CITY); drop(ROADS); drop(BUILDINGS) }
            else {
                set(CITY, mapOf("density" to fmt(cities)))
                set(ROADS, mapOf("lampSpacing" to "40"))
                set(BUILDINGS, mapOf("maxFloors" to "12"))
            }
        }
        return specs
    }

    /** The same passes as the world save keeps them. */
    fun passSpecs(base: List<StageSpec>): List<PassSpec> = passes(base).map { PassSpec(it.id, it.options) }

    /** The world rules with the scene's danger. */
    fun rules(base: WorldRules): WorldRules = float("monsters")?.let { base.withMonsters(it.coerceIn(0.2f, 2f)) } ?: base

    companion object {
        const val TERRAIN = "micro:terrain"
        const val FEATURES = "micro:features"
        const val CAVES = "micro:caves"
        const val TREES = "micro:trees"
        const val GROUNDCOVER = "micro:groundcover"
        const val CITY = "micro:city_plan"
        const val ROADS = "micro:roads"
        const val BUILDINGS = "micro:buildings"

        internal fun fmt(v: Float): String = "%.2f".format(java.util.Locale.ROOT, v)
    }
}

object ScenePrompt {

    /** Every key a scene may set: its range or words, and what it does. */
    val KEYS: List<Triple<String, String, String>> by lazy {
        listOf(
            Triple("landscape", "africa | classic | ${PROVINCES.joinToString(" | ")}", "africa: the whole continent's geology, province by province; one province: a world made only of it; classic: plain noise hills"),
            Triple("home", PROVINCES.joinToString(" | "), "with landscape africa: the province the player starts in"),
            Triple("relief", "0.05..1", "how tall the land rises and deep it falls"),
            Triple("mountains", "0..2", "how much of it is ridged peaks"),
            Triple("breadth", "0.3..2", "how wide hills and valleys are"),
            Triple("plateaus", "0..16", "terraces the land into shelves; 0 off"),
            Triple("erosion", "0..2", "with a geology: how deeply running water cuts valleys, and the gravel fans it lays below them; 0 none"),
            Triple("rivers", "0..2", "with a geology: how many streams run with water, with levees and floodplain silt; 0 none"),
            Triple("dunes", "0..2", "with a geology: how tall the dunes stand and how many barchans roam the sand seas"),
            Triple("scree", "0..2", "with a geology: how much rubble piles at the foot of cliffs"),
            Triple("rockDetail", "0..2", "with a geology: how many dykes, quartz veins, ore lenses and nodules cut the rock"),
            Triple("vegetation", "0..2", "overall greenness: grass and trees together"),
            Triple("trees", "0..2", "tree density; 0 treeless"),
            Triple("grass", "0..2", "ground cover density"),
            Triple("tallGrass", "0..1", "share of elephant grass"),
            Triple("forest", "tropical | temperate", "iroko, oil palm and baobab, or oaks and firs"),
            Triple("caves", "0..1", "how much of the underground is tunnel; 0 none"),
            Triple("features", "0..2", "tors, termite mounds, sandstone arches"),
            Triple("towns", "0..3", "how many towns, relative to the rules"),
            Triple("style", "regional | parametric | plain | ${Traditions.ids.joinToString(" | ")}", "how towns are built: regional follows each land's own tradition"),
            Triple("homeStyle", "auto | parametric | ${Traditions.ids.joinToString(" | ")}", "how the home town is built"),
            Triple("invented", "0..1", "in regional mode, the share of towns inventing variations within their land's grammar"),
            Triple("homeSize", "0.5..2", "hamlet to city"),
            Triple("walls", "auto | on | off", "whether the home town is walled"),
            Triple("plans", "any | comma list of ${PlanShape.entries.joinToString(",") { it.name.lowercase() }}", "invented buildings' plans"),
            Triple("roofs", "any | comma list of ${RoofForm.entries.joinToString(",") { it.name.lowercase() }}", "invented buildings' roofs"),
            Triple("materials", "any | comma list of ${MaterialFamilies.ids.joinToString(",")}", "invented buildings' materials"),
            Triple("storeys", "0..5 or a range like 1-3", "storeys above the ground floor"),
            Triple("ornament", "0..1", "bare walls to richly dressed"),
            Triple("towers", "0..1", "how often buildings raise towers"),
            Triple("variety", "0..1", "how far each building strays from its town's look"),
            Triple("cities", "0..1", "share of modern city blocks with streets and lamps; 0 none"),
            Triple("monsters", "0.2..2", "danger: 0.6 calm, 1 normal, 1.4 swarming"),
        )
    }

    private val PROVINCES = listOf(
        Provinces.FOREST_HILLS, Provinces.RAINFOREST_BASIN, Provinces.DELTA, Provinces.LATERITE_PLATEAU, Provinces.INSELBERGS,
        Provinces.SAHEL_PLAIN, Provinces.SANDSTONE_ESCARPMENT, Provinces.RIFT, Provinces.TRAPS, Provinces.VOLCANIC_NECKS,
        Provinces.ERG, Provinces.REG_HAMADA, Provinces.SALT_PAN, Provinces.KALAHARI, Provinces.NAMIB, Provinces.KAROO,
        Provinces.DRAKENSBERG, Provinces.FOLD_BELT, Provinces.TSINGY, Provinces.CORAL_COAST,
        Provinces.CANYON, Provinces.COASTAL_DUNES, Provinces.MONTANE, Provinces.SHIELD,
    )

    /** What a language model is told: describe the scene as one JSON object of these keys, and nothing else. */
    fun systemPrompt(): String = buildString {
        appendLine("You turn a player's description of a scene into parameters for a microvoxel world generator of African landscapes and architecture.")
        appendLine("Reply with ONE JSON object and nothing else. Use only these keys; leave out any the description does not suggest.")
        appendLine("Numbers are plain numbers; lists are comma-separated strings. Be faithful to geography and history: pick the province and")
        appendLine("building tradition a knowledgeable geographer and architectural historian would, and say why in a \"why\" string.")
        appendLine()
        for ((key, range, meaning) in KEYS) appendLine("\"$key\": $range -- $meaning")
        appendLine("\"why\": one sentence")
    }

    /**
     * Reads a language model's reply: the first JSON object in it, keys
     * checked and values clamped. Anything it got wrong is dropped with a note,
     * never an error: a half-right reply still makes the half it got right.
     */
    fun parse(reply: String): SceneSpec {
        val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return SceneSpec(emptyMap(), listOf("The model's reply held no scene"))
        val raw = runCatching { FlatJson.parse(reply.substring(start, end + 1)) }.getOrElse { return SceneSpec(emptyMap(), listOf("The model's reply was not JSON: ${it.message}")) }
        val values = LinkedHashMap<String, String>()
        val notes = ArrayList<String>()
        raw["why"]?.let { notes += it }
        for ((key, value) in raw) {
            if (key == "why") continue
            val checked = check(key, value)
            if (checked == null) notes += "Ignored $key = $value" else values[key] = checked
        }
        return SceneSpec(values, notes)
    }

    /** A value made safe for [key], or null when it is not one the key takes. */
    fun check(key: String, value: String): String? {
        val v = value.trim().lowercase()
        fun num(min: Float, max: Float) = v.toFloatOrNull()?.coerceIn(min, max)?.let(SceneSpec::fmt)
        fun names(all: List<String>) = if (v == "any") "any" else v.split(',', '|', ' ').map { it.trim() }.filter { it in all }.distinct().takeIf { it.isNotEmpty() }?.joinToString(",")
        return when (key) {
            "landscape" -> v.takeIf { it == "africa" || it == "classic" || it in PROVINCES }
            "home" -> v.takeIf { it in PROVINCES }
            "relief" -> num(0.05f, 1f)
            "mountains" -> num(0f, 2f)
            "breadth" -> num(0.3f, 2f)
            "plateaus" -> v.toFloatOrNull()?.toInt()?.coerceIn(0, 16)?.toString()
            "erosion", "rivers", "dunes", "scree", "rockDetail" -> num(0f, 2f)
            "vegetation", "trees", "grass", "features" -> num(0f, 2f)
            "tallGrass", "caves", "invented", "ornament", "towers", "variety", "cities" -> num(0f, 1f)
            "forest" -> v.takeIf { it == "tropical" || it == "temperate" }
            "towns" -> num(0f, 3f)
            "style" -> v.takeIf { it == "regional" || it == "parametric" || it == "plain" || it in Traditions.ids }
            "homeStyle" -> v.takeIf { it == "auto" || it == "parametric" || it in Traditions.ids }
            "homeSize" -> num(0.5f, 2f)
            "walls" -> v.takeIf { it == "auto" || it == "on" || it == "off" }
            "plans" -> names(PlanShape.entries.map { it.name.lowercase() })
            "roofs" -> names(RoofForm.entries.map { it.name.lowercase() })
            "materials" -> names(MaterialFamilies.ids)
            "storeys" -> v.takeIf { Regex("[0-5](-[0-5])?").matches(it) }
            "monsters" -> num(0.2f, 2f)
            else -> null
        }
    }

    // ---- Reading plain words --------------------------------------------------------------

    private class Rule(val words: List<String>, val note: String, val set: MutableMap<String, String>.() -> Unit)

    private fun rule(vararg words: String, note: String, set: MutableMap<String, String>.() -> Unit) = Rule(words.toList(), note, set)

    /** The land a word names -- unless the whole continent was asked for, when it is where the player starts. */
    private fun MutableMap<String, String>.province(p: String) { if (this["landscape"] != "africa") this["landscape"] = p }

    private fun MutableMap<String, String>.tradition(t: String) { this["homeStyle"] = t; putIfAbsent("style", "regional") }

    /**
     * What words mean, most specific first. Places and peoples are read the
     * way a geographer and a historian would: Djenné is Sudano-Sahelian mud
     * on the Sahel, Lamu is Swahili coral stone on the coast, Great Zimbabwe
     * is dry stone among granite domes.
     */
    private val RULES: List<Rule> = listOf(
        // Lands.
        rule("whole continent", "all of africa", "across africa", "continent", note = "The whole continent's geology") { this["landscape"] = "africa" },
        rule("sahara", "dunes", "dune", "erg", "sand sea", note = "Sand sea: the erg") { province(Provinces.ERG) },
        rule("hamada", "rocky desert", "gravel plain", "reg", note = "Stony desert: reg and hamada") { province(Provinces.REG_HAMADA) },
        rule("namib", "skeleton coast", "fog desert", note = "The Namib's red dunes by a cold sea") { province(Provinces.NAMIB) },
        rule("kalahari", note = "The Kalahari's red sand and pans") { province(Provinces.KALAHARI) },
        rule("karoo", note = "The Karoo: dolerite-capped mesas") { province(Provinces.KAROO) },
        rule("salt flat", "salt pan", "danakil", "etosha", "makgadikgadi", note = "A salt pan") { province(Provinces.SALT_PAN) },
        rule("sahel", "savanna", "savannah", "grassland", "steppe", note = "The Sahel's open plains") { province(Provinces.SAHEL_PLAIN) },
        rule("rainforest", "jungle", "congo", "equatorial forest", note = "The rainforest basin") { province(Provinces.RAINFOREST_BASIN); this["forest"] = "tropical" },
        rule("forest hills", "guinea", "igboland", "yorubaland", "ashanti", "benin city", "nigeria", note = "The Guinean forest hills") { province(Provinces.FOREST_HILLS); this["forest"] = "tropical" },
        rule("delta", "mangrove", "swamp", "lagoon", "creeks", note = "A delta of creeks and mangroves") { province(Provinces.DELTA) },
        rule("laterite", "red plateau", "burkina", "togo", note = "The laterite plateau") { province(Provinces.LATERITE_PLATEAU) },
        rule("inselberg", "granite dome", "kopje", "zimbabwe", "matobo", note = "Granite domes and tors") { province(Provinces.INSELBERGS) },
        rule("escarpment", "bandiagara", "cliff", "cliffs", note = "A sandstone escarpment") { province(Provinces.SANDSTONE_ESCARPMENT) },
        rule("rift", "great lakes", "soda lake", "rift valley", note = "The Rift: fault scarps and soda lakes") { province(Provinces.RIFT) },
        rule("volcano", "volcanoes", "volcanic", "lava", "kilimanjaro", note = "Volcanic necks and cones") { province(Provinces.VOLCANIC_NECKS) },
        rule("ethiopia", "abyssinia", "highland", "highlands", "tigray", note = "The Ethiopian highland traps") { province(Provinces.TRAPS) },
        rule("drakensberg", "basalt", "amphitheatre", note = "The Drakensberg's basalt walls") { province(Provinces.DRAKENSBERG) },
        rule("atlas", "morocco", "maghreb", note = "The Atlas folds") { province(Provinces.FOLD_BELT) },
        rule("tsingy", "madagascar", "karst", "limestone needles", note = "Tsingy karst") { province(Provinces.TSINGY) },
        rule("coast", "coastal", "beach", "reef", "island", "islands", "zanzibar", "swahili coast", note = "The coral coast") { province(Provinces.CORAL_COAST) },
        rule("fish river", "canyon country", "canyonlands", note = "Canyon country: flat beds over an old basement") { province(Provinces.CANYON) },
        rule("coastal dunes", "dune coast", "lagoon coast", "maputaland", "mozambique coast", note = "Coastal dune cordons and lagoons") { province(Provinces.COASTAL_DUNES) },
        rule("montane", "moorland", "nyika", "bale mountains", "mountain grassland", note = "A cool montane plateau") { province(Provinces.MONTANE) },
        rule("craton", "shield", "greenstone", "gold fields", "copperbelt", note = "The old basement shield") { province(Provinces.SHIELD) },
        // Peoples and places: how their towns are built.
        rule("djenne", "djenné", "timbuktu", "mud mosque", "mali", "mopti", "sudano", note = "Sudano-Sahelian mud architecture") { tradition("sudano_sahelian"); putIfAbsent("landscape", Provinces.SAHEL_PLAIN) },
        rule("kano", "hausa", "zaria", "katsina", note = "Hausa courtyard houses with zanko pinnacles") { tradition("hausa"); putIfAbsent("landscape", Provinces.SAHEL_PLAIN) },
        rule("dogon", note = "Dogon villages under the cliffs") { tradition("dogon"); putIfAbsent("landscape", Provinces.SANDSTONE_ESCARPMENT) },
        rule("tamberma", "batammariba", "takienta", "koutammakou", note = "Batammariba tower-houses") { tradition("batammariba"); putIfAbsent("landscape", Provinces.LATERITE_PLATEAU) },
        rule("kassena", "tiebele", "tiébélé", note = "Kassena painted houses") { tradition("kassena"); putIfAbsent("landscape", Provinces.LATERITE_PLATEAU) },
        rule("musgum", "teleuk", note = "Musgum earth domes") { tradition("musgum"); putIfAbsent("landscape", Provinces.SAHEL_PLAIN) },
        rule("igbo", "uli", "obi", note = "Igbo compounds") { tradition("igbo"); putIfAbsent("landscape", Provinces.FOREST_HILLS) },
        rule("yoruba", "ile-ife", "ife", "oyo", note = "Yoruba courtyard houses") { tradition("yoruba"); putIfAbsent("landscape", Provinces.FOREST_HILLS) },
        rule("asante", "ashanti", "kumasi", note = "Asante courts") { tradition("asante"); putIfAbsent("landscape", Provinces.FOREST_HILLS) },
        rule("benin kingdom", "edo", "oba", note = "Benin's red-walled palace city") { tradition("benin"); putIfAbsent("landscape", Provinces.FOREST_HILLS) },
        rule("swahili", "lamu", "kilwa", "mombasa", "stone town", note = "Swahili coral-stone towns") { tradition("swahili"); putIfAbsent("landscape", Provinces.CORAL_COAST) },
        rule("aksum", "axum", "lalibela", "gondar", note = "Aksumite stone and timber") { tradition("aksumite"); putIfAbsent("landscape", Provinces.TRAPS) },
        rule("nubia", "nubian", "kush", "meroe", "meroë", "kerma", note = "Nubian vaults and painted fronts") { tradition("nubian"); putIfAbsent("landscape", Provinces.REG_HAMADA) },
        rule("berber", "amazigh", "kasbah", "ksar", "ksour", "ait benhaddou", note = "Amazigh kasbahs") { tradition("amazigh"); putIfAbsent("landscape", Provinces.FOLD_BELT) },
        rule("great zimbabwe", "shona", "khami", "mapungubwe", note = "Great Zimbabwe's dry stone") { tradition("great_zimbabwe"); putIfAbsent("landscape", Provinces.INSELBERGS) },
        rule("ndebele", note = "Ndebele painted homesteads") { tradition("ndebele"); putIfAbsent("landscape", Provinces.KAROO) },
        rule("zulu", "kraal", "beehive hut", note = "Zulu beehive homesteads") { tradition("zulu"); putIfAbsent("landscape", Provinces.DRAKENSBERG) },
        rule("maasai", "masai", "herders", "pastoral", note = "Pastoral homesteads") { tradition("pastoral"); putIfAbsent("landscape", Provinces.RIFT) },
        // The land's shape.
        rule("flat", "plains", "plain", "lowland", note = "Low relief") { this["relief"] = "0.30"; this["mountains"] = "0.20" },
        rule("rolling", "hills", "hilly", note = "Rolling hills") { this["relief"] = "0.65" },
        rule("mountains", "mountainous", "peaks", "rugged", "alpine", "craggy", note = "Mountains") { this["relief"] = "1.00"; this["mountains"] = "1.80" },
        rule("mesa", "mesas", "tableland", "tablelands", "terraces", "terraced", note = "Stepped tablelands") { this["plateaus"] = "6" },
        rule("vast", "wide", "sweeping", "open", note = "Broad landforms") { this["breadth"] = "1.60" },
        rule("canyons", "gorges", "ravines", note = "Deep-cut relief") { this["relief"] = "1.00"; this["breadth"] = "0.60" },
        rule("rivers", "river", "floodplain", "streams", "waterways", note = "Many rivers") { this["rivers"] = "1.60" },
        rule("no rivers", "riverless", note = "No rivers") { this["rivers"] = "0" },
        rule("eroded", "badlands", "gullies", "weathered", note = "Deeply eroded") { this["erosion"] = "1.70" },
        rule("scree", "talus", "rubble", "rockfall", note = "Scree below the cliffs") { this["scree"] = "1.70" },
        rule("ore", "mines", "mining", "veins", "prospecting", "dykes", note = "Veined, ore-bearing rock") { this["rockDetail"] = "1.80" },
        rule("caves", "caverns", "underground", "tunnels", note = "Riddled with caves") { this["caves"] = "0.60" },
        rule("boulders", "rock formations", "termite mounds", "arches", note = "Rock features") { this["features"] = "1.60" },
        // Life.
        rule("lush", "dense", "overgrown", "verdant", "green", note = "Lush growth") { this["vegetation"] = "1.70" },
        rule("sparse", "arid", "dry", "barren", "parched", "desolate", note = "Sparse, dry growth") { this["vegetation"] = "0.25" },
        rule("treeless", "no trees", note = "No trees") { this["trees"] = "0" },
        rule("tall grass", "elephant grass", note = "Tall elephant grass") { this["tallGrass"] = "0.70" },
        rule("pine", "pines", "oak", "oaks", "temperate", note = "Temperate trees") { this["forest"] = "temperate" },
        // People.
        rule("wilderness", "uninhabited", "empty", "lonely", "untouched", note = "Few towns") { this["towns"] = "0.20" },
        rule("villages", "hamlets", "rural", note = "Villages") { this["towns"] = "1.20"; this["homeSize"] = "0.70" },
        rule("bustling", "crowded", "kingdom", "empire", "populous", "trade routes", note = "Many towns") { this["towns"] = "2.20" },
        rule("city", "metropolis", "capital", "great city", note = "A great home city") { this["homeSize"] = "2.00"; putIfAbsent("towns", "1.60") },
        rule("walled", "fortified", "fortress", "citadel", note = "Walled") { this["walls"] = "on" },
        rule("modern", "skyscrapers", "streets", "downtown", note = "Modern city blocks") { this["cities"] = "0.50"; this["materials"] = "modern,brick" },
        // Buildings.
        rule("domes", "domed", "dome", note = "Domes") { this["roofs"] = "dome,onion,flat" },
        rule("towers", "tower", "minaret", "minarets", "spires", note = "Towers") { this["towers"] = "0.70" },
        rule("tall buildings", "multi-storey", "storeyed", "towering", note = "Tall buildings") { this["storeys"] = "2-4" },
        rule("ornate", "decorated", "carved", "elaborate", "grand", note = "Richly dressed") { this["ornament"] = "0.95" },
        rule("simple", "humble", "plain houses", "modest", note = "Simple buildings") { this["ornament"] = "0.20" },
        rule("courtyards", "courtyard", "compounds", note = "Courtyard plans") { this["plans"] = "courtyard,u,rect" },
        rule("round houses", "round huts", "circular", "roundhouses", note = "Round plans") { this["plans"] = "round,octagon" },
        rule("stone", "masonry", note = "Stone") { this["materials"] = "stone" },
        rule("whitewashed", "white", "limewashed", note = "Lime-washed") { this["materials"] = "lime" },
        rule("mud", "adobe", "earthen", "clay", note = "Earth") { this["materials"] = "earth" },
        rule("painted", "colourful", "colorful", "murals", note = "Painted") { this["materials"] = "painted"; this["ornament"] = "0.90" },
        rule("wooden", "timber", "thatched", note = "Timber and thatch") { this["materials"] = "timber" },
        rule("fantasy", "invented", "unique", "strange", "otherworldly", "varied", "eclectic", note = "Invented architecture") { this["invented"] = "0.80"; this["variety"] = "0.80" },
        // Danger.
        rule("peaceful", "calm", "cozy", "cosy", "safe", "serene", "quiet", note = "Calm: fewer, gentler monsters") { this["monsters"] = "0.60" },
        rule("dangerous", "deadly", "monsters", "infested", "haunted", "perilous", note = "Dangerous") { this["monsters"] = "1.40" },
    )

    /** Reads plain words into a scene, at once and without a model. */
    fun read(text: String): SceneSpec {
        val lower = " " + text.lowercase().replace(Regex("[^\\p{L}\\p{N}' -]+"), " ") + " "
        val values = LinkedHashMap<String, String>()
        val notes = ArrayList<String>()
        for (r in RULES) {
            val hit = r.words.firstOrNull { w -> Regex("(?<![\\p{L}])" + Regex.escape(w) + "(?![\\p{L}])").containsMatchIn(lower) } ?: continue
            r.set(values)
            notes += "“$hit” → ${r.note}"
        }
        // Asked for the whole continent and a place: the continent, starting in that place.
        if (values["landscape"] == "africa") RULES.firstNotNullOfOrNull { r ->
            val probe = LinkedHashMap<String, String>()
            if (r.words.any { w -> Regex("(?<![\\p{L}])" + Regex.escape(w) + "(?![\\p{L}])").containsMatchIn(lower) }) r.set(probe)
            probe["landscape"]?.takeIf { it != "africa" }
        }?.let { values["home"] = it }
        values.entries.removeAll { (k, v) -> check(k, v) == null }
        return SceneSpec(values, notes)
    }

    /** A spec and the model's refinement together: the model's values win where it gave any. */
    fun merge(words: SceneSpec, model: SceneSpec): SceneSpec = SceneSpec(words.values + model.values, words.notes + model.notes)
}

/** Just enough JSON for a flat object of scalars and arrays of scalars; arrays come back comma-joined. */
internal object FlatJson {
    fun parse(text: String): Map<String, String> {
        val p = Parser(text)
        return p.obj()
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun expect(c: Char) { ws(); require(i < s.length && s[i] == c) { "expected '$c' at $i" }; i++ }
        fun obj(): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            expect('{'); ws()
            if (s.getOrNull(i) == '}') { i++; return out }
            while (true) {
                ws(); val key = string(); expect(':'); ws()
                val value = value()
                if (value != null) out[key] = value
                ws()
                when (s.getOrNull(i)) { ',' -> i++; '}' -> { i++; return out }; else -> error("expected ',' or '}' at $i") }
            }
        }
        fun value(): String? = when (s.getOrNull(i)) {
            '"' -> string()
            '[' -> { i++; val parts = ArrayList<String>(); ws(); if (s.getOrNull(i) == ']') i++ else while (true) { ws(); value()?.let(parts::add); ws(); if (s.getOrNull(i) == ',') i++ else { expect(']'); break } }; parts.joinToString(",") }
            '{' -> { obj(); null }
            else -> { val start = i; while (i < s.length && s[i] !in ",}] \n\r\t") i++; s.substring(start, i).takeIf { it != "null" } }
        }
        fun string(): String {
            require(s.getOrNull(i) == '"') { "expected a string at $i" }
            i++
            val sb = StringBuilder()
            while (i < s.length && s[i] != '"') {
                if (s[i] == '\\' && i + 1 < s.length) { i++; sb.append(when (s[i]) { 'n' -> '\n'; 't' -> '\t'; else -> s[i] }) } else sb.append(s[i])
                i++
            }
            i++
            return sb.toString()
        }
    }
}
