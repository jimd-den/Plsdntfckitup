package com.stratum.core.domain.settlement.culture

/**
 * One building tradition's way of making places: the kinds of settlement it
 * makes, who rules them, what they make, what stands at their hearts, and
 * how their names sound.
 *
 * Weights are relative within each list. Names are assembled from the
 * pieces, so every town of a people sounds like it belongs to them without
 * any two being the same; they are invented, not lifted from real places.
 */
class Culture(
    val id: String,
    /** The people or the building tradition, as a player reads it: "Igbo", "Sudano-Sahelian". */
    val name: String,
    val forms: List<Pair<Form, Int>>,
    val rule: List<Pair<Governance, Int>>,
    val crafts: List<Pair<Craft, Int>>,
    val hearts: List<Heart>,
    /** Quarters this people's towns tend to have, beyond the seat, compounds and the square every town has. */
    val quarters: List<Quarter>,
    /** Chance a town of theirs that could be walled is. */
    val walls: Float,
    val prefixes: List<String>,
    val roots: List<String>,
    val suffixes: List<String>,
    /** Their festivals and dances, as history can name them. */
    val festivals: List<String>,
)

/** Every people's way of making places, by building-tradition id (the ids the microvoxel traditions draw in). */
object Cultures {

    private fun c(
        id: String, name: String, forms: List<Pair<Form, Int>>, rule: List<Pair<Governance, Int>>, crafts: List<Pair<Craft, Int>>,
        hearts: List<Heart>, quarters: List<Quarter>, walls: Float, prefixes: String, roots: String, suffixes: String, festivals: String,
    ) = Culture(id, name, forms, rule, crafts, hearts, quarters, walls, prefixes.split("|"), roots.split("|"), suffixes.split("|"), festivals.split("|"))

    val all: List<Culture> = listOf(
        c(
            "igbo", "Igbo",
            listOf(Form.VILLAGE_GROUP to 5, Form.VILLAGE to 4, Form.HAMLET to 3, Form.HOMESTEAD to 2, Form.MARKET_TOWN to 2, Form.SACRED_CITY to 1),
            listOf(Governance.ASSEMBLY to 4, Governance.ELDERS to 4, Governance.TITLED to 3, Governance.PRIEST_KING to 1),
            listOf(Craft.YAMS to 5, Craft.PALM_OIL to 3, Craft.PALM_WINE to 2, Craft.IRON to 3, Craft.BRONZE to 2, Craft.CARVING to 3, Craft.POTTERY to 2, Craft.KOLA to 2),
            listOf(Heart.EARTH_SHRINE, Heart.IROKO, Heart.MBARI, Heart.MASK_GROUND, Heart.SHRINE_GROVE, Heart.MARKET_TREE),
            listOf(Quarter.MARKET, Quarter.SHRINES, Quarter.FIELDS, Quarter.AGE_GRADES, Quarter.CRAFTS, Quarter.GRANARIES),
            0.15f, "Umu|Ama|Ogbo|Isi|Nkwo|Eke|Orie|Afo|Obi|Ezi", "agu|ezi|oke|ike|ndu|uzo|ala|ofo|nta|ebo|odu|ani", "|-Ukwu|-Nta|ala|-Uno|-Agu",
            "the new yam festival|the masquerade of the ancestors|the wrestling season|the feast of the earth",
        ),
        c(
            "yoruba", "Yoruba",
            listOf(Form.ROYAL_CAPITAL to 4, Form.MARKET_TOWN to 4, Form.WALLED_CITY to 2, Form.VILLAGE to 3, Form.SACRED_CITY to 2),
            listOf(Governance.OBA to 6, Governance.ELDERS to 2, Governance.QUEEN_MOTHER to 1, Governance.TITLED to 2),
            listOf(Craft.INDIGO to 4, Craft.WEAVING to 4, Craft.CARVING to 3, Craft.BRONZE to 2, Craft.BEADS to 3, Craft.KOLA to 3, Craft.YAMS to 3, Craft.POTTERY to 2),
            listOf(Heart.PALACE_COURT, Heart.SHRINE_GROVE, Heart.MARKET_TREE, Heart.SACRED_TREE),
            listOf(Quarter.MARKET, Quarter.SHRINES, Quarter.CRAFTS, Quarter.FIELDS, Quarter.GATES, Quarter.STRANGERS),
            0.55f, "Ile-|Oke-|Ado-|Ise-|Ilu-|Ita-|Igbo-", "Oro|Ayo|Ola|Owo|Odo|Isa|Ere|Ogun|Ife|Ara|Imo", "||-Oja|-Ile|-Oke",
            "the festival of the river goddess|the masquerade of the egungun|the king's yearly procession|the festival of iron",
        ),
        c(
            "asante", "Asante",
            listOf(Form.ROYAL_CAPITAL to 3, Form.MARKET_TOWN to 3, Form.VILLAGE to 4, Form.HAMLET to 2),
            listOf(Governance.STOOL to 6, Governance.QUEEN_MOTHER to 3, Governance.ELDERS to 2),
            listOf(Craft.GOLD to 5, Craft.WEAVING to 4, Craft.KOLA to 4, Craft.CARVING to 3, Craft.POTTERY to 2, Craft.YAMS to 2),
            listOf(Heart.PALACE_COURT, Heart.SHRINE_GROVE, Heart.SACRED_TREE, Heart.ANCESTOR_HOUSE),
            listOf(Quarter.MARKET, Quarter.SHRINES, Quarter.CRAFTS, Quarter.FIELDS, Quarter.STRANGERS, Quarter.GUARD),
            0.2f, "Nkwanta|Asa|Ofori|Bo|Kwa|Adu|Mampo|Effi", "man|kwa|so|ase|bour|bea|dua|kese|ntim", "|so|ase|-Kese|man",
            "the festival of the yam and the stool|the gathering of the chiefs|the purification of the stools",
        ),
        c(
            "benin", "Edo",
            listOf(Form.ROYAL_CAPITAL to 5, Form.WALLED_CITY to 3, Form.VILLAGE to 3, Form.HAMLET to 1),
            listOf(Governance.OBA to 7, Governance.TITLED to 2, Governance.ELDERS to 1),
            listOf(Craft.BRONZE to 6, Craft.CARVING to 3, Craft.BEADS to 3, Craft.LEATHER to 1, Craft.YAMS to 2, Craft.PALM_OIL to 2),
            listOf(Heart.PALACE_COURT, Heart.ANCESTOR_HOUSE, Heart.SHRINE_GROVE),
            listOf(Quarter.CRAFTS, Quarter.MARKET, Quarter.SHRINES, Quarter.GUARD, Quarter.GATES, Quarter.FIELDS),
            0.8f, "Ugu|Iye|Oka|Uze|Evb|Igu|Ogi", "ere|oba|ehi|ota|ugu|ewa|ido|osa", "|-Nugie|-Oba|ho",
            "the festival of the coral beads|the king's dance with the swords|the commemoration of the ancestors",
        ),
        c(
            "sudano_sahelian", "Sudano-Sahelian",
            listOf(Form.CARAVAN_CITY to 4, Form.WALLED_CITY to 3, Form.SACRED_CITY to 3, Form.MARKET_TOWN to 3, Form.RIVER_PORT to 2, Form.VILLAGE to 3),
            listOf(Governance.SCHOLARS to 3, Governance.SULTAN to 3, Governance.MERCHANTS to 3, Governance.ELDERS to 2),
            listOf(Craft.MANUSCRIPTS to 4, Craft.CARAVANS to 5, Craft.SALT to 4, Craft.GOLD to 3, Craft.LEATHER to 3, Craft.WEAVING to 2, Craft.MILLET to 3, Craft.FISHING to 1),
            listOf(Heart.MOSQUE, Heart.MARKET_TREE, Heart.WELL),
            listOf(Quarter.MARKET, Quarter.SCHOOL, Quarter.STRANGERS, Quarter.GRANARIES, Quarter.CRAFTS, Quarter.GATES, Quarter.WATERFRONT),
            0.6f, "Dia|Tin|Kou|San|Djen|Ga|Wa|Ten", "bara|kene|dougou|koro|nema|souma|bali|kala|nkoro", "|dougou|-Koro|ba|so",
            "the replastering of the mosque|the crossing of the cattle|the great fishing of the pool",
        ),
        c(
            "hausa", "Hausa",
            listOf(Form.WALLED_CITY to 6, Form.CARAVAN_CITY to 3, Form.MARKET_TOWN to 3, Form.VILLAGE to 3, Form.HAMLET to 1),
            listOf(Governance.EMIR to 6, Governance.MERCHANTS to 2, Governance.SCHOLARS to 1, Governance.HEADMAN to 2),
            listOf(Craft.INDIGO to 6, Craft.LEATHER to 4, Craft.WEAVING to 3, Craft.CARAVANS to 3, Craft.MILLET to 3, Craft.IRON to 2, Craft.SALT to 1),
            listOf(Heart.MOSQUE, Heart.PALACE_COURT, Heart.MARKET_TREE, Heart.WELL),
            listOf(Quarter.MARKET, Quarter.CRAFTS, Quarter.GATES, Quarter.STRANGERS, Quarter.SCHOOL, Quarter.GRANARIES, Quarter.GUARD),
            0.85f, "Birnin |Gidan |Dan |Kauyen |Tudun ", "Kura|Zaki|Dawa|Rano|Jaba|Gari|Tsofo|Yamma|Gabas|Fari", "||wa| Gari",
            "the durbar of horsemen|the festival of the dye pits|the end of the fast",
        ),
        c(
            "dogon", "Dogon",
            listOf(Form.CLIFF_VILLAGE to 7, Form.VILLAGE to 2, Form.HAMLET to 2),
            listOf(Governance.HOGON to 6, Governance.ELDERS to 3, Governance.AGE_SETS to 1),
            listOf(Craft.MILLET to 6, Craft.CARVING to 3, Craft.IRON to 2, Craft.BASKETRY to 2, Craft.WEAVING to 1),
            listOf(Heart.TOGUNA, Heart.ANCESTOR_HOUSE, Heart.MASK_GROUND),
            listOf(Quarter.GRANARIES, Quarter.FIELDS, Quarter.SHRINES, Quarter.AGE_GRADES),
            0.05f, "Ban|Son|Ir|Tel|Yen|End|Gon|Kom", "di|go|ani|li|ama|de|ogo|anda", "|-Na|-Da|go",
            "the dance of the masks|the great festival of the generations|the sowing rites",
        ),
        c(
            "batammariba", "Batammariba",
            listOf(Form.TOWER_HAMLET to 7, Form.HAMLET to 3, Form.VILLAGE to 2),
            listOf(Governance.ELDERS to 4, Governance.HEADMAN to 3, Governance.AGE_SETS to 2),
            listOf(Craft.MILLET to 5, Craft.IRON to 2, Craft.POTTERY to 2, Craft.BASKETRY to 2, Craft.CATTLE to 1),
            listOf(Heart.ANCESTOR_HOUSE, Heart.SACRED_TREE, Heart.BAOBAB),
            listOf(Quarter.FIELDS, Quarter.GRANARIES, Quarter.SHRINES),
            0f, "Ko|Ta|Nat|Bou|Ti|Kou", "ntam|tamba|kou|tingou|baka|dè", "||-Tam|ba",
            "the initiation of the young men|the harvest of the fonio",
        ),
        c(
            "kassena", "Kassena",
            listOf(Form.HOMESTEAD to 4, Form.VILLAGE to 4, Form.HAMLET to 2, Form.ROYAL_CAPITAL to 1),
            listOf(Governance.HEADMAN to 3, Governance.ELDERS to 3, Governance.STOOL to 2),
            listOf(Craft.MILLET to 4, Craft.POTTERY to 3, Craft.BASKETRY to 2, Craft.CATTLE to 2, Craft.WEAVING to 1),
            listOf(Heart.ANCESTOR_HOUSE, Heart.BAOBAB, Heart.SHRINE_GROVE),
            listOf(Quarter.FIELDS, Quarter.GRANARIES, Quarter.SHRINES, Quarter.MARKET),
            0.1f, "Ti|Pa|Nav|Ka|Siri", "ebele|go|rongo|ssena|bisi", "|-Nania|go",
            "the painting of the walls|the harvest dance",
        ),
        c(
            "musgum", "Musgum",
            listOf(Form.HOMESTEAD to 5, Form.FISHING_VILLAGE to 3, Form.VILLAGE to 3, Form.KRAAL to 1),
            listOf(Governance.HEADMAN to 4, Governance.ELDERS to 3),
            listOf(Craft.FISHING to 4, Craft.MILLET to 3, Craft.CATTLE to 2, Craft.POTTERY to 2, Craft.BASKETRY to 2),
            listOf(Heart.WELL, Heart.SACRED_TREE, Heart.CATTLE_BYRE),
            listOf(Quarter.GRANARIES, Quarter.FIELDS, Quarter.WATERFRONT),
            0.05f, "Pou|Mou|Gue|Zi|Ma", "ss|lam|gum|dou|vay", "|-Gum|lam",
            "the flood-fishing|the harvest of the sorghum",
        ),
        c(
            "swahili", "Swahili",
            listOf(Form.STONE_TOWN to 6, Form.RIVER_PORT to 2, Form.FISHING_VILLAGE to 3, Form.MARKET_TOWN to 2),
            listOf(Governance.SULTAN to 4, Governance.MERCHANTS to 5, Governance.SCHOLARS to 1),
            listOf(Craft.BOATS to 5, Craft.FISHING to 3, Craft.BEADS to 2, Craft.CARVING to 3, Craft.COPPER to 1, Craft.CARAVANS to 2, Craft.WEAVING to 1),
            listOf(Heart.CORAL_MOSQUE, Heart.MARKET_TREE, Heart.WELL, Heart.BAOBAB),
            listOf(Quarter.WATERFRONT, Quarter.MARKET, Quarter.STRANGERS, Quarter.SCHOOL, Quarter.CRAFTS, Quarter.GATES),
            0.45f, "Ki|Mji |Pa|Gedi|Ma|Shan", "lwa|mba|tegeta|linde|siwa|ngoni|rafiki|pwani|fumbo", "||ni| Mkuu",
            "the new-year bonfires|the dhow race|the poetry contest of the harbour",
        ),
        c(
            "aksumite", "Highland",
            listOf(Form.SACRED_CITY to 4, Form.ROYAL_CAPITAL to 3, Form.HILL_CITADEL to 2, Form.VILLAGE to 3, Form.HAMLET to 2),
            listOf(Governance.NEGUS to 5, Governance.SCHOLARS to 2, Governance.ELDERS to 2),
            listOf(Craft.COFFEE to 4, Craft.HONEY to 3, Craft.WEAVING to 3, Craft.CATTLE to 2, Craft.MANUSCRIPTS to 3, Craft.POTTERY to 1),
            listOf(Heart.STELE, Heart.CHURCH, Heart.PALACE_COURT),
            listOf(Quarter.SHRINES, Quarter.MARKET, Quarter.FIELDS, Quarter.SCHOOL, Quarter.GRANARIES),
            0.25f, "Deb|Ad|Lal|Gon|Mek|Ab|Yeh", "re|wa|ibela|der|ele|ay|unta|aba", "||-Mariam|-Selassie",
            "the finding of the true cross|the festival of the epiphany|the new-year flowers",
        ),
        c(
            "nubian", "Nubian",
            listOf(Form.FISHING_VILLAGE to 2, Form.RIVER_PORT to 3, Form.VILLAGE to 4, Form.OASIS to 3, Form.HAMLET to 2),
            listOf(Governance.ELDERS to 4, Governance.HEADMAN to 3, Governance.MERCHANTS to 1),
            listOf(Craft.MILLET to 3, Craft.BASKETRY to 3, Craft.POTTERY to 2, Craft.FISHING to 2, Craft.CARAVANS to 2, Craft.CATTLE to 1),
            listOf(Heart.WELL, Heart.MOSQUE, Heart.SACRED_TREE),
            listOf(Quarter.WATERFRONT, Quarter.FIELDS, Quarter.GRANARIES, Quarter.MARKET),
            0.1f, "Ab|Sol|Kar|Dong|Wad|Ta", "ri|eb|ima|ola|odi|aru", "||-Gharb|-Sharq",
            "the painting of the gates|the date harvest|the river-wedding processions",
        ),
        c(
            "amazigh", "Amazigh",
            listOf(Form.KSAR to 6, Form.OASIS to 4, Form.CARAVAN_CITY to 2, Form.VILLAGE to 2, Form.HAMLET to 1),
            listOf(Governance.ASSEMBLY to 4, Governance.ELDERS to 3, Governance.MERCHANTS to 1),
            listOf(Craft.SALT to 3, Craft.CARAVANS to 3, Craft.WEAVING to 3, Craft.LEATHER to 2, Craft.HONEY to 1, Craft.POTTERY to 2, Craft.MILLET to 2),
            listOf(Heart.WELL, Heart.MOSQUE, Heart.MARKET_TREE),
            listOf(Quarter.GRANARIES, Quarter.MARKET, Quarter.GATES, Quarter.FIELDS, Quarter.STRANGERS),
            0.9f, "Tin|Ait |Ag|Tam|Igh|Tiz", "ghir|elli|oudad|ezza|arit|iouine|ilal", "||t|-n-Ouzrou",
            "the rose festival|the betrothal fair|the date harvest",
        ),
        c(
            "great_zimbabwe", "Shona",
            listOf(Form.HILL_CITADEL to 5, Form.ROYAL_CAPITAL to 2, Form.VILLAGE to 3, Form.KRAAL to 2),
            listOf(Governance.MAMBO to 6, Governance.ELDERS to 2, Governance.HEADMAN to 1),
            listOf(Craft.GOLD to 4, Craft.CATTLE to 4, Craft.COPPER to 2, Craft.IRON to 2, Craft.POTTERY to 2, Craft.BEADS to 2),
            listOf(Heart.CONICAL_TOWER, Heart.CATTLE_BYRE, Heart.ANCESTOR_HOUSE),
            listOf(Quarter.FIELDS, Quarter.GRANARIES, Quarter.CRAFTS, Quarter.MARKET, Quarter.GUARD),
            0.65f, "Dzi|Kha|Mapu|Nya|Man|Chi", "mbabwe|mi|ngwe|nga|kwe|rira|tora", "||we|-Guru",
            "the rain-making ceremony|the gathering of the herds|the first-fruits feast",
        ),
        c(
            "ndebele", "Ndebele",
            listOf(Form.KRAAL to 4, Form.HOMESTEAD to 3, Form.VILLAGE to 3, Form.ROYAL_CAPITAL to 1),
            listOf(Governance.HEADMAN to 3, Governance.ELDERS to 2, Governance.AGE_SETS to 2),
            listOf(Craft.BEADS to 5, Craft.CATTLE to 4, Craft.MILLET to 2, Craft.BASKETRY to 2, Craft.POTTERY to 1),
            listOf(Heart.CATTLE_BYRE, Heart.ANCESTOR_HOUSE, Heart.BAOBAB),
            listOf(Quarter.FIELDS, Quarter.GRANARIES, Quarter.CRAFTS),
            0.2f, "Kwa|Ema|Ko|Ezi|Nde", "ndebele|hlanga|zolo|mnyama|bheka|langa", "|ni|-Mhlophe",
            "the painting of the homesteads|the initiation feast",
        ),
        c(
            "zulu", "Nguni",
            listOf(Form.KRAAL to 6, Form.ROYAL_CAPITAL to 2, Form.VILLAGE to 2, Form.HAMLET to 2),
            listOf(Governance.HEADMAN to 4, Governance.AGE_SETS to 3, Governance.ELDERS to 2),
            listOf(Craft.CATTLE to 6, Craft.BEADS to 3, Craft.BASKETRY to 3, Craft.MILLET to 2, Craft.IRON to 1),
            listOf(Heart.CATTLE_BYRE, Heart.ANCESTOR_HOUSE),
            listOf(Quarter.FIELDS, Quarter.GRANARIES, Quarter.GUARD),
            0.4f, "kwa|Emu|eNt|uMg|Esi", "langeni|bandla|thombe|ngoye|shiya|hlabathi|zimu", "||ini|-Kulu",
            "the first-fruits feast of the king|the reed dance|the gathering of the regiments",
        ),
        c(
            "pastoral", "Pastoral",
            listOf(Form.CAMP to 6, Form.KRAAL to 3, Form.HAMLET to 2, Form.OASIS to 1),
            listOf(Governance.AGE_SETS to 6, Governance.ELDERS to 3),
            listOf(Craft.CATTLE to 7, Craft.BEADS to 3, Craft.LEATHER to 3, Craft.HONEY to 1),
            listOf(Heart.CATTLE_BYRE, Heart.SACRED_TREE, Heart.WELL),
            listOf(Quarter.FIELDS, Quarter.AGE_GRADES),
            0.7f, "Ol|En|Il|Ena|Lo", "kesumet|doinyo|gatuny|laikipi|turot|serian", "||-Oiboro|-Narok",
            "the warriors' shaving|the rains' return|the naming of the age-set",
        ),
    )

    private val byId: Map<String, Culture> = all.associateBy { it.id }

    val ids: List<String> = all.map { it.id }

    fun of(id: String?): Culture = byId[id] ?: byId.getValue("igbo")
}
