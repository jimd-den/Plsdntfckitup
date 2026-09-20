package com.stratum.content.igbo

import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.world.NoiseLayer
import com.stratum.core.domain.world.Stratum
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.content.HeroClassDefinition
import com.stratum.core.domain.content.LoreCategory
import com.stratum.core.domain.content.LoreEntry
import com.stratum.core.domain.content.PackOrigin
import com.stratum.core.domain.content.PackPalette

/**
 * The pack the game ships with.
 *
 * It is an ordinary content pack: the loader treats it exactly like one an AI
 * generated or a player imported, and a later pack can override any block,
 * biome or class in it by id.
 */
object IgboContentPack {

    const val ID = "igbo"

    private const val NS = "igbo"

    /**
     * Bronze, laterite and deep indigo. The interface reads these too, so a
     * different pack restyles the HUD along with the terrain.
     */
    val palette = PackPalette(
        surface = 0xFF14110E,
        surfaceRaised = 0xFF211C16,
        ink = 0xFFF4EBDC,
        inkMuted = 0xFFA1907A,
        accent = 0xFFCD7F32,
        accentAlt = 0xFF00B8A9,
        danger = 0xFFC1453B,
    )

    private val heroClasses = listOf(
        HeroClassDefinition(
            id = "$NS:dike_ozo",
            name = "Dike Ozo",
            title = "Titled Bladesman",
            description = "A titled warrior of the Ozo society who answers a problem with reach and " +
                "a wide arc. Digs well, dies slowly.",
            baseHealth = 260,
            baseResource = 90,
            resourceName = "Resolve",
            strength = 16,
            agility = 11,
            insight = 8,
            startingBlockIds = listOf(IgboPackBlocks.redEarth.id, IgboPackBlocks.graniteStone.id),
            abilityIds = listOf("$NS:mma_nkwu_cleave", "$NS:ikenga_tremor"),
            baseStats = CombatStats(
                maxHealth = 260,
                attackPower = 14,
                armour = 6,
                critChance = 0.08f,
                critMultiplier = 1.5f,
                attackSpeed = 1f,
                attackRange = 1,
            ),
            startingWeaponId = IgboPackCombat.weapons[1].id,
        ),
        HeroClassDefinition(
            id = "$NS:amadioha_invoker",
            name = "Amadioha Invoker",
            title = "Caller of the Storm",
            description = "Pulls the storm down onto a line of ground and everything standing on it. " +
                "Fragile until the first crystal is cut.",
            baseHealth = 180,
            baseResource = 160,
            resourceName = "Charge",
            strength = 8,
            agility = 12,
            insight = 18,
            startingBlockIds = listOf(IgboPackBlocks.obsidianCrag.id),
            abilityIds = listOf("$NS:thunder_spear", "$NS:shockwave_spark"),
            baseStats = CombatStats(
                maxHealth = 180,
                attackPower = 18,
                armour = 1,
                critChance = 0.12f,
                critMultiplier = 1.7f,
                attackSpeed = 1f,
                attackRange = 2,
                resistances = mapOf(IgboPackCombat.thunder.id to 0.25f),
            ),
            startingWeaponId = IgboPackCombat.weapons[2].id,
        ),
        HeroClassDefinition(
            id = "$NS:dibia_nzu",
            name = "Dibia Nzu",
            title = "Chalk Diviner",
            description = "Reads the ground before breaking it. Sees ore through stone and turns " +
                "poison back on whoever sent it.",
            baseHealth = 200,
            baseResource = 140,
            resourceName = "Communion",
            strength = 9,
            agility = 13,
            insight = 17,
            startingBlockIds = listOf(IgboPackBlocks.nsibidiSeal.id, IgboPackBlocks.groveTurf.id),
            abilityIds = listOf("$NS:venom_geyser", "$NS:solar_supernova"),
            baseStats = CombatStats(
                maxHealth = 200,
                attackPower = 15,
                armour = 3,
                critChance = 0.1f,
                critMultiplier = 1.6f,
                attackSpeed = 1f,
                attackRange = 3,
                resistances = mapOf(IgboPackCombat.venom.id to 0.3f),
                lifeSteal = 0.05f,
            ),
            startingWeaponId = IgboPackCombat.weapons[4].id,
        ),
        HeroClassDefinition(
            id = "$NS:ikenga_berserker",
            name = "Ikenga Berserker",
            title = "Horned Right Hand",
            description = "Trades armour for speed and swings until the room is quiet. The fastest " +
                "hands underground.",
            baseHealth = 300,
            baseResource = 70,
            resourceName = "Fury",
            strength = 19,
            agility = 15,
            insight = 6,
            startingBlockIds = listOf(IgboPackBlocks.catacombMasonry.id),
            abilityIds = listOf("$NS:ikenga_tremor", "$NS:mma_nkwu_cleave"),
            baseStats = CombatStats(
                maxHealth = 300,
                attackPower = 20,
                armour = 2,
                critChance = 0.14f,
                critMultiplier = 1.8f,
                attackSpeed = 1.25f,
                attackRange = 1,
            ),
            startingWeaponId = IgboPackCombat.weapons[0].id,
        ),
    )

    private val lore = listOf(
        LoreEntry(
            id = "$NS:lore_bronze",
            title = "The Roped Vessels",
            body = "The bronzes of Igbo-Ukwu were cast before most of the world had a word for the " +
                "technique. Rope, insect, and leaf were pressed into wax and lost to the fire, and " +
                "what came out was metal that remembered them. What you pull out of a vein down here " +
                "is the raw form of that memory.",
            category = LoreCategory.ARTIFACT,
            subjectId = "$NS:bronze_ore",
        ),
        LoreEntry(
            id = "$NS:lore_amadioha",
            title = "Amadioha's Judgement",
            body = "Thunder is not weather. It is a verdict delivered without appeal, and the crags " +
                "on the peak are where it has been delivered most often. Crystal grows where the " +
                "charge had nowhere left to go.",
            category = LoreCategory.DEITY,
            subjectId = "$NS:thunder_peak",
        ),
        LoreEntry(
            id = "$NS:lore_idemili",
            title = "Idemili's Grove",
            body = "The python passes and the ground is not disturbed. Iroko stands where it was " +
                "planted and outlives the argument about who planted it. Cut the canopy if you must, " +
                "but the roots are older than your claim.",
            category = LoreCategory.PLACE,
            subjectId = "$NS:sacred_grove",
        ),
        LoreEntry(
            id = "$NS:lore_nsibidi",
            title = "Marks That Are Not Letters",
            body = "Nsibidi was never an alphabet. A mark settles a debt, names a union, or warns a " +
                "stranger away from a doorway, and the reading depends on who is entitled to read. " +
                "A seal cut into the ground here refuses the pick for the same reason.",
            category = LoreCategory.RITUAL,
            subjectId = "$NS:nsibidi_seal",
        ),
        LoreEntry(
            id = "$NS:lore_ofo",
            title = "The Ofo Staff",
            body = "Ofo is held by the one entitled to speak. It is not a weapon and it does not make " +
                "its holder right, only answerable. The shrines keep that weight in stone.",
            category = LoreCategory.RITUAL,
            subjectId = "$NS:ofo_shrine",
        ),
        LoreEntry(
            id = "$NS:lore_ikenga",
            title = "The Right Hand",
            body = "Ikenga is carved for a man's own achievement, horned because strength should be " +
                "visible and answerable. It is retired when he is, and broken when he is buried.",
            category = LoreCategory.DEITY,
            subjectId = "$NS:ikenga_berserker",
        ),
    )

    /**
     * How this pack shapes its world.
     *
     * Terraced on purpose. Smooth noise gives a landscape of one-block steps
     * that reads as texture rather than terrain: you cannot tell which level you
     * are on, cannot see where you can climb, and cannot put a building on it.
     * Snapping to three-block plateaus gives ledges you can read at a glance and
     * ground flat enough to build on.
     */
    val terrain = TerrainRecipe(
        elevation = listOf(
            // One broad landform, so a region has a shape rather than a texture.
            NoiseLayer(scale = 0.010f, amplitude = 1f),
            // A quieter second octave, enough to break up the plateau edges.
            NoiseLayer(scale = 0.045f, amplitude = 0.3f, seedOffset = 101),
        ),
        terraceStep = 3,
        strata = listOf(
            Stratum(IgboPackBlocks.redEarth.id, thickness = 2),
            Stratum(IgboPackBlocks.riverClay.id, thickness = 2),
        ),
    )

    val pack = ContentPack(
        id = ID,
        name = "Igbo-Ukwu Bronze",
        author = "Stratum",
        version = "2.0.0",
        description = "The built-in pack: five regions of Igbo mythology rendered as a mineable " +
            "voxel world, with bronze at the bottom of it and something in it that bites.",
        origin = PackOrigin.BUILT_IN,
        palette = palette,
        blocks = IgboPackBlocks.all,
        biomes = IgboPackBiomes.all,
        heroClasses = heroClasses,
        loreEntries = lore,
        damageTypes = IgboPackCombat.damageTypes,
        affixes = IgboPackCombat.affixes,
        inserts = IgboPackCombat.inserts,
        weapons = IgboPackCombat.weapons,
        enemies = IgboPackCombat.enemies,
        skills = IgboPackCombat.skills,
        rarityStyles = IgboPackCombat.rarityStyles,
        terrain = terrain,
    )
}
