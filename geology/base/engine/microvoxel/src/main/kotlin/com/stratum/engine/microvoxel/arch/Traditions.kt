package com.stratum.engine.microvoxel.arch

import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.gen.Hash
import com.stratum.engine.microvoxel.geo.Provinces

/**
 * Africa's building traditions, and where each belongs.
 *
 * A town takes the tradition of the land it stands on: Sudano-Sahelian mud
 * mosques and Hausa palaces on the Sahel's plains, Dogon villages under the
 * sandstone escarpment, Igbo, Yoruba, Asante and Benin compounds in the
 * forest belt, Batammariba tower-houses and Kassena painted courts on the
 * laterite savanna, Musgum domes on the floodplains, Great Zimbabwe's dry
 * stone among the granite domes, Swahili coral stone on the coast, Aksumite
 * masonry and tukuls in the Ethiopian highlands, Nubian vaults by the desert,
 * Amazigh kasbahs in the Atlas and its oases, Ndebele painted homesteads,
 * Zulu beehive houses and pastoral dung houses in the south and the Rift.
 */
object Traditions {

    /** Every tradition, drawing with [palette]. */
    fun all(palette: MaterialPalette): List<Tradition> {
        val m = ArchPalette(palette)
        return listOf(
            Igbo(m), Yoruba(m), Asante(m), Benin(m),
            SudanoSahelian(m), Hausa(m), Dogon(m), Batammariba(m), Kassena(m), Musgum(m),
            Swahili(m), Aksumite(m), Nubian(m), Amazigh(m),
            GreatZimbabwe(m), Ndebele(m), Zulu(m), Pastoral(m),
        )
    }

    val ids: List<String> = listOf(
        "igbo", "yoruba", "asante", "benin", "sudano_sahelian", "hausa", "dogon", "batammariba", "kassena", "musgum",
        "swahili", "aksumite", "nubian", "amazigh", "great_zimbabwe", "ndebele", "zulu", "pastoral",
    )

    /**
     * Which traditions build in each province, with weights. Where a province
     * straddles several peoples' lands, each has its share.
     */
    val native: Map<String, List<Pair<String, Int>>> = mapOf(
        Provinces.FOREST_HILLS to listOf("igbo" to 4, "yoruba" to 3, "asante" to 2, "benin" to 2),
        Provinces.RAINFOREST_BASIN to listOf("asante" to 2, "igbo" to 2, "benin" to 1, "yoruba" to 1),
        Provinces.DELTA to listOf("benin" to 2, "igbo" to 2, "swahili" to 1),
        Provinces.LATERITE_PLATEAU to listOf("batammariba" to 3, "kassena" to 3, "hausa" to 2, "sudano_sahelian" to 1),
        Provinces.INSELBERGS to listOf("great_zimbabwe" to 4, "yoruba" to 2, "hausa" to 1, "batammariba" to 1),
        Provinces.SAHEL_PLAIN to listOf("sudano_sahelian" to 4, "hausa" to 3, "musgum" to 2, "kassena" to 1),
        Provinces.SANDSTONE_ESCARPMENT to listOf("dogon" to 5, "sudano_sahelian" to 2),
        Provinces.RIFT to listOf("pastoral" to 4, "aksumite" to 2),
        Provinces.TRAPS to listOf("aksumite" to 5),
        Provinces.VOLCANIC_NECKS to listOf("musgum" to 2, "pastoral" to 1, "aksumite" to 1),
        Provinces.ERG to listOf("amazigh" to 3, "nubian" to 2, "sudano_sahelian" to 1),
        Provinces.REG_HAMADA to listOf("amazigh" to 3, "nubian" to 3),
        Provinces.SALT_PAN to listOf("pastoral" to 2, "amazigh" to 2, "nubian" to 1),
        Provinces.KALAHARI to listOf("ndebele" to 2, "pastoral" to 2, "zulu" to 1),
        Provinces.NAMIB to listOf("pastoral" to 3),
        Provinces.KAROO to listOf("ndebele" to 3, "zulu" to 2),
        Provinces.DRAKENSBERG to listOf("zulu" to 3, "ndebele" to 2),
        Provinces.FOLD_BELT to listOf("amazigh" to 5),
        Provinces.TSINGY to listOf("swahili" to 2, "igbo" to 1),
        Provinces.CORAL_COAST to listOf("swahili" to 5),
    )

    /** The tradition a town in [province] takes, fixed by [seed] (the town's own). */
    fun choose(province: String?, seed: Long, fallback: String = "igbo"): String {
        val options = native[province] ?: return fallback
        val total = options.sumOf { it.second }
        var roll = (Hash.unit(seed, 0, 0, 0, 91) * total).toInt()
        for ((id, weight) in options) { roll -= weight; if (roll < 0) return id }
        return options.first().first
    }
}
