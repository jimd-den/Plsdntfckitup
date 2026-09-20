package com.stratum.legacy.domain

import java.util.UUID

/**
 * CHAPTER 03: DOMAIN MODEL - HERO CLASSES, POWER BUILDER & VISUAL LOGIC NODE STUDIO
 *
 * Implements:
 * 1. Hero Class Builder (pre-configured classes + user-created custom classes).
 * 2. Custom Power & Attack Creator (custom incantations, damage types, cast types, status effects).
 * 3. Visual Programming Logic Nodes (Trigger -> Condition -> Action -> Modifier pipeline).
 * 4. Game Modes (Survival Arena, Dungeon Crawl, Horde Siege, Sandbox Testing).
 */

enum class CastType(val displayName: String) {
    MELEE_CLEAVE("Melee Cleave Arc"),
    PROJECTILE_BOLT("Direct Projectile Bolt"),
    NOVA_BURST("Radial 360° Nova"),
    SKY_STORM("Sky Thunder Fall"),
    DASH_STRIKE("Blink Dash Strike"),
    TOTEM_SUMMON("Ancestral Totem")
}

enum class StatusEffectType(val displayName: String, val description: String) {
    NONE("None", "Direct instant damage"),
    STUN("Stun", "Freezes target movement for 1.5 seconds"),
    BURN("Anyanwu Burn", "Deals 20 damage per sec for 4 seconds"),
    POISON("Idemili Poison", "Reduces armor and deals tick damage"),
    FREEZE("Glacial Chill", "Slows target speed by 60%"),
    CHAIN_SHOCK("Amadioha Chain", "Arcs lightning to 3 adjacent targets"),
    LIFESTEAL("Blood Communion", "Restores 25% of damage as Hero HP")
}

data class PowerAttackSkill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val incantation: String,
    val description: String,
    val damageType: DamageType = DamageType.PHYSICAL,
    val castType: CastType = CastType.MELEE_CLEAVE,
    val baseDamage: Float = 45f,
    val manaCost: Float = 20f,
    val cooldownSec: Float = 2.0f,
    val radius: Float = 90f,
    val projectileSpeed: Float = 12f,
    val statusEffect: StatusEffectType = StatusEffectType.NONE,
    val effectColorHex: String = "#FFD700",
    val isCustomCreated: Boolean = false,
    val specialProc: String = ""
) {
    val igboIncantationChant: String get() = incantation
    val cooldownMs: Long get() = (cooldownSec * 1000).toLong()
    val spiritCost: Int get() = manaCost.toInt()
    val damageMultiplier: Float get() = (baseDamage / 30f)
    val primaryDamageType: DamageType get() = damageType
    val specialProcEffect: String get() = if (specialProc.isNotEmpty()) specialProc else statusEffect.displayName
    val icon: String get() = when (damageType) {
        DamageType.THUNDER -> "⚡"
        DamageType.BRONZE_FIRE, DamageType.SOLAR_FIRE -> "🔥"
        DamageType.SPIRIT_POISON -> "🌿"
        else -> "⚔️"
    }
}

data class HeroClass(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val title: String,
    val patronDeity: String,
    val loreDescription: String,
    val baseHealth: Float = 250f,
    val baseSpirit: Float = 100f,
    val moveSpeed: Float = 4.5f,
    val baseArmor: Float = 15f,
    val critRate: Float = 0.12f,
    val primaryElement: DamageType = DamageType.PHYSICAL,
    val primarySkillId: String,
    val secondarySkillId: String,
    val colorHex: String = "#D4AF37",
    val isCustomClass: Boolean = false,
    val spriteSheetData: SpriteSheetData? = null
) {
    val igboTitle: String get() = title
    val lore: String get() = loreDescription
    val baseIke: Int get() = baseArmor.toInt()
    val baseUche: Int get() = (critRate * 100).toInt()
    val baseNdu: Int get() = baseHealth.toInt()
    val baseMmuo: Int get() = baseSpirit.toInt()
    val icon: String get() = when (name) {
        "Dike Ozo" -> "🛡️"
        "Amadioha Invoker" -> "⚡"
        "Dibia Nzu" -> "🌿"
        else -> "⚔️"
    }
}

// ==================== VISUAL LOGIC NODE ENGINE ====================

enum class NodeCategory(val displayName: String, val colorHex: String) {
    TRIGGER("Trigger Event", "#FF6F00"),
    CONDITION("Condition Gate", "#2979FF"),
    ACTION("Spell Effect", "#D500F9"),
    MODIFIER("Power Modifier", "#00E676")
}

enum class TriggerType(val label: String) {
    ON_PRIMARY_ATTACK("On Normal Strike"),
    ON_MELEE_HIT("On Melee Hit"),
    ON_SKILL_CAST("On Skill Cast"),
    ON_DASH("On Dash Evade"),
    ON_ENEMY_HIT("On Enemy Impact"),
    ON_ENEMY_KILL("On Target Slain"),
    ON_LOW_HEALTH("When HP < 35%");

    val displayName: String get() = label
}

enum class ConditionType(val label: String) {
    ALWAYS("Always Triggers (100%)"),
    ON_CRITICAL_HIT("Only on Critical Hit"),
    CHANCE_50_PERCENT("50% Fortune Roll"),
    CHANCE_ROLL("Random Fortune Roll"),
    CLOSE_RANGE_ONLY("Enemy < 80px Proximity");

    val displayName: String get() = label
}

enum class ActionEffectType(val label: String, val description: String) {
    SPAWN_EXTRA_PROJECTILE("Fire Extra Energy Bolt", "Launches a guided bolt at the nearest foe"),
    UNLEASH_CHAIN_LIGHTNING("Amadioha Chain Arc", "Splits lightning to up to 4 nearby enemies"),
    SOLAR_SUPERNOVA("Anyanwu Solar Burst", "Detonates a 360° blinding solar ring"),
    KNOCKBACK_SHOCKWAVE("Ancestral Shockwave", "Repels all enemies back 140px"),
    LIFESTEAL_HEAL("Sacred Blood Mend", "Instantly heals Hero for 35 HP"),
    FREEZE_GROUND_RING("Frost Glyph Ring", "Freezes all nearby targets for 2 seconds");

    val displayName: String get() = label
}

enum class ModifierType(val label: String, val boostDamagePercent: Float) {
    DAMAGE_BOOST_50("+50% Cataclysmic Damage", 50f),
    MULTI_SHOT_TRIPLE("Triple Projectile Split", 0f),
    BURNING_TRAIL("Ignite Burning Trail", 25f),
    RADIUS_DOUBLE("Double Blast Radius (+100%)", 0f),
    ECHO_DOUBLE_CAST("Echo Double Cast", 0f);

    val displayName: String get() = label
}

typealias TriggerLogicNode = TriggerType
typealias ConditionLogicNode = ConditionType
typealias ActionLogicNode = ActionEffectType
typealias ModifierLogicNode = ModifierType

typealias TriggerEvent = TriggerType
typealias ConditionCheck = ConditionType
typealias LogicModifier = ModifierType

typealias HeroClassDefinition = HeroClass
typealias SkillDefinition = PowerAttackSkill
typealias SkillCastType = CastType

data class VisualProgramNode(
    val id: String = UUID.randomUUID().toString(),
    val category: NodeCategory,
    val title: String,
    val description: String,
    val triggerType: TriggerType? = null,
    val conditionType: ConditionType? = null,
    val actionType: ActionEffectType? = null,
    val modifierType: ModifierType? = null
)

data class VisualLogicScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Custom Attack Pipeline",
    val trigger: TriggerType = TriggerType.ON_MELEE_HIT,
    val condition: ConditionType = ConditionType.CHANCE_50_PERCENT,
    val action: ActionEffectType = ActionEffectType.UNLEASH_CHAIN_LIGHTNING,
    val modifier: ModifierType = ModifierType.DAMAGE_BOOST_50,
    val isActive: Boolean = true
)

// ==================== DEFAULT GAME MODES ====================

enum class GameMode(
    val title: String,
    val tagLine: String,
    val description: String,
    val objective: String,
    val iconRune: String
) {
    SURVIVAL_ARENA(
        title = "Ancestral Survival",
        tagLine = "Endless Escalating Waves",
        description = "Survive against surging tides of spirits and rogue bronze automatons.",
        objective = "Slay maximum enemies and collect sacred bronze relics.",
        iconRune = "⚔"
    ),
    DUNGEON_CRAWL(
        title = "Catacomb Altar Raid",
        tagLine = "Boss Altar Quest",
        description = "Traverse the procedural dungeon chambers to locate the Sacred Ofo Altar.",
        objective = "Defeat the guardian Agbara High Priest and secure the royal seal.",
        iconRune = "☩"
    ),
    HORDE_SIEGE(
        title = "Sacred Shrine Defense",
        tagLine = "Protect the Central Altar",
        description = "Defend the ancient Igbo-Ukwu shrine in the arena center from invading hordes.",
        objective = "Prevent enemy spirits from breaching the central shrine sanctum.",
        iconRune = "🛡"
    ),
    SANDBOX_PLAYGROUND(
        title = "Omenani God Mode",
        tagLine = "Infinite Power Testing",
        description = "Zero cooldowns, boundless Spirit mana, and instant ability to test visual logic nodes.",
        objective = "Freely craft, scale, and test powers, maps, and weapons.",
        iconRune = "⚡"
    );

    val displayName: String get() = title
    val icon: String get() = iconRune
}

// ==================== PRELOADED CATALOGS ====================

object PreloadedCatalog {

    val SKILL_OZO_SLASH = PowerAttackSkill(
        id = "skill_ozo_slash",
        name = "Mma Nkwu Cleave",
        incantation = "Ike Dike!",
        description = "Heavy bronze blade cleave dealing wide physical slash damage with knockback.",
        damageType = DamageType.PHYSICAL,
        castType = CastType.MELEE_CLEAVE,
        baseDamage = 55f,
        manaCost = 10f,
        cooldownSec = 0.6f,
        radius = 85f,
        effectColorHex = "#CD7F32"
    )

    val SKILL_AMADIOHA_BOLT = PowerAttackSkill(
        id = "skill_amadioha_bolt",
        name = "Amadioha Thunder Spear",
        incantation = "Igwe Ka Ala!",
        description = "Fires a high-velocity crackling lightning spear that shocks enemies in a straight line.",
        damageType = DamageType.THUNDER,
        castType = CastType.PROJECTILE_BOLT,
        baseDamage = 85f,
        manaCost = 25f,
        cooldownSec = 1.4f,
        radius = 45f,
        projectileSpeed = 16f,
        statusEffect = StatusEffectType.CHAIN_SHOCK,
        effectColorHex = "#00E5FF"
    )

    val SKILL_ANYANWU_NOVA = PowerAttackSkill(
        id = "skill_anyanwu_nova",
        name = "Anyanwu Solar Supernova",
        incantation = "Oku Na-Ere!",
        description = "Unleashes an expanding 360-degree ring of sacred solar fire that burns all targets.",
        damageType = DamageType.BRONZE_FIRE,
        castType = CastType.NOVA_BURST,
        baseDamage = 110f,
        manaCost = 45f,
        cooldownSec = 3.5f,
        radius = 160f,
        statusEffect = StatusEffectType.BURN,
        effectColorHex = "#FF6D00"
    )

    val SKILL_IDEMILI_SERPENT = PowerAttackSkill(
        id = "skill_idemili_serpent",
        name = "Idemili Venom Geyser",
        incantation = "Mmiri Ogu!",
        description = "Erupts an emerald spirit pool beneath foes that dissolves armor and ticks poison.",
        damageType = DamageType.SPIRIT_POISON,
        castType = CastType.SKY_STORM,
        baseDamage = 70f,
        manaCost = 30f,
        cooldownSec = 2.2f,
        radius = 120f,
        statusEffect = StatusEffectType.POISON,
        effectColorHex = "#00E676"
    )

    val SKILL_IKENGA_SHOCKWAVE = PowerAttackSkill(
        id = "skill_ikenga_shockwave",
        name = "Ikenga Horned Tremor",
        incantation = "Chi Na Ike!",
        description = "Slams the earth with divine force, stunning all targets in a seismic tremor.",
        damageType = DamageType.PHYSICAL,
        castType = CastType.NOVA_BURST,
        baseDamage = 95f,
        manaCost = 35f,
        cooldownSec = 2.8f,
        radius = 130f,
        statusEffect = StatusEffectType.STUN,
        effectColorHex = "#FFD700"
    )

    val ALL_SKILLS = listOf(
        SKILL_OZO_SLASH,
        SKILL_AMADIOHA_BOLT,
        SKILL_ANYANWU_NOVA,
        SKILL_IDEMILI_SERPENT,
        SKILL_IKENGA_SHOCKWAVE
    )

    val CLASS_OZO_BLADESMAN = HeroClass(
        id = "class_ozo_bladesman",
        name = "Dike Ozo",
        title = "Royal Bronze Champion",
        patronDeity = "Anyanwu (The Solar Deity)",
        loreDescription = "Initiated titleholder armored in Igbo-Ukwu roped bronze, wielding sacred blades with supreme fortitude.",
        baseHealth = 320f,
        baseSpirit = 100f,
        moveSpeed = 4.4f,
        baseArmor = 25f,
        critRate = 0.15f,
        primaryElement = DamageType.PHYSICAL,
        primarySkillId = SKILL_OZO_SLASH.id,
        secondarySkillId = SKILL_ANYANWU_NOVA.id,
        colorHex = "#D4AF37"
    )

    val CLASS_AMADIOHA_STORMCALLER = HeroClass(
        id = "class_amadioha_storm",
        name = "Amadioha Invoker",
        title = "Storm Herald",
        patronDeity = "Amadioha (Deity of Thunder)",
        loreDescription = "Channels heavenly lightning and stormwinds to obliterate foes across the battlefield.",
        baseHealth = 220f,
        baseSpirit = 180f,
        moveSpeed = 4.8f,
        baseArmor = 10f,
        critRate = 0.22f,
        primaryElement = DamageType.THUNDER,
        primarySkillId = SKILL_AMADIOHA_BOLT.id,
        secondarySkillId = SKILL_ANYANWU_NOVA.id,
        colorHex = "#00E5FF"
    )

    val CLASS_DIBIA_ALCHEMIST = HeroClass(
        id = "class_dibia_shaman",
        name = "Dibia Nzu",
        title = "Sacred Healer & Shaman",
        patronDeity = "Idemili (Deity of Waters & Python)",
        loreDescription = "Master of ancestral chalks (Nzu), herbal extracts, and venom tides that siphon life.",
        baseHealth = 240f,
        baseSpirit = 160f,
        moveSpeed = 4.5f,
        baseArmor = 12f,
        critRate = 0.18f,
        primaryElement = DamageType.SPIRIT_POISON,
        primarySkillId = SKILL_IDEMILI_SERPENT.id,
        secondarySkillId = SKILL_AMADIOHA_BOLT.id,
        colorHex = "#00E676"
    )

    val CLASS_IKENGA_BERSERKER = HeroClass(
        id = "class_ikenga_berserker",
        name = "Ikenga Berserker",
        title = "Horned Force of Will",
        patronDeity = "Ikenga (Deity of Accomplishment & Strength)",
        loreDescription = "Fights with unyielding willpower, delivering devastating seismic critical strikes.",
        baseHealth = 360f,
        baseSpirit = 90f,
        moveSpeed = 4.2f,
        baseArmor = 20f,
        critRate = 0.28f,
        primaryElement = DamageType.PHYSICAL,
        primarySkillId = SKILL_IKENGA_SHOCKWAVE.id,
        secondarySkillId = SKILL_OZO_SLASH.id,
        colorHex = "#FF8F00"
    )

    val ALL_CLASSES = listOf(
        CLASS_OZO_BLADESMAN,
        CLASS_AMADIOHA_STORMCALLER,
        CLASS_DIBIA_ALCHEMIST,
        CLASS_IKENGA_BERSERKER
    )

    val DEFAULT_LOGIC_SCRIPT = VisualLogicScript(
        id = "script_default_combo",
        name = "Amadioha Shockwave Spark",
        trigger = TriggerType.ON_ENEMY_HIT,
        condition = ConditionType.CHANCE_50_PERCENT,
        action = ActionEffectType.UNLEASH_CHAIN_LIGHTNING,
        modifier = ModifierType.DAMAGE_BOOST_50
    )
}

object PreloadedClasses {
    val OZO_WARRIOR: HeroClass get() = PreloadedCatalog.CLASS_OZO_BLADESMAN
    val AMADIOHA_STORMCALLER: HeroClass get() = PreloadedCatalog.CLASS_AMADIOHA_STORMCALLER
    val DIBIA_ALCHEMIST: HeroClass get() = PreloadedCatalog.CLASS_DIBIA_ALCHEMIST
    val IKENGA_BERSERKER: HeroClass get() = PreloadedCatalog.CLASS_IKENGA_BERSERKER
    val ALL: List<HeroClass> get() = PreloadedCatalog.ALL_CLASSES
}

// ==================== DIABLO ERA & MIYAMOTO NINTENDO SYSTEMS ====================

enum class DiabloEra(
    val id: String,
    val title: String,
    val subtitle: String,
    val yearTag: String,
    val description: String,
    val iconRune: String
) {
    DIABLO_1(
        id = "era_d1",
        title = "Diablo I",
        subtitle = "Gothic Cathedral 1996",
        yearTag = "1996",
        description = "Dark stone gothic archways, heavy iron bezel, high-contrast ruby & sapphire globes with menacing gargoyle brackets, and retro gold lettering.",
        iconRune = "🕯️"
    ),
    DIABLO_2(
        id = "era_d2",
        title = "Diablo II: LoD",
        subtitle = "Lord of Destruction Era",
        yearTag = "2000",
        description = "Ornamental antique brass filigree, aged leather parchment panels, 4-slot utility potion belt (1-4), stamina gauge, and classic runic borders.",
        iconRune = "📜"
    ),
    DIABLO_3(
        id = "era_d3",
        title = "Diablo III",
        subtitle = "High-Fantasy Arcade",
        yearTag = "2012",
        description = "Curved dark obsidian wings, fluid glowing arc meters, high-visibility neon cooldown halos, and dynamic kill-streak massacre counters.",
        iconRune = "⚡"
    ),
    DIABLO_4(
        id = "era_d4",
        title = "Diablo IV",
        subtitle = "Sanctuary Grimdark",
        yearTag = "2023",
        description = "Modern brutalist slate dock, visceral fluid blood physics, muted gothic gold filigree, runic badges, and sleek high-readability typography.",
        iconRune = "🩸"
    ),
    CUSTOM_STYLE(
        id = "era_custom",
        title = "Custom Style",
        subtitle = "Miyamoto Sandbox",
        yearTag = "USER",
        description = "Create your own style! Personalize health/mana orb presentation, custom color palettes, camera projection, and tactile button feedback.",
        iconRune = "🎨"
    )
}

enum class NamingSystem(
    val title: String,
    val subtitle: String,
    val description: String
) {
    GENERIC_RPG(
        title = "Classic ARPG",
        subtitle = "Warrior, Longsword, Health Potion",
        description = "Universal fantasy terminology: Warrior, Sorcerer, Rogue, Iron Longsword, Tower Shield, Health Potion, and Mana Flask."
    ),
    MYTHIC_ANCIENT(
        title = "Mythic Legends",
        subtitle = "Dike Ozo, Mma Nkwu, Ndụ",
        description = "Ancestral Igbo-Ukwu terminology: Dike Ozo, Amadioha Invoker, Mma Nkwu, Aro Igwe, Ndụ (Life Force), and Mmụọ (Spirit)."
    ),
    AI_THEMED(
        title = "AI Prompted Theme",
        subtitle = "Custom Universe Names",
        description = "Names, weapons, and monsters morph dynamically based on your custom AI prompt (e.g. Cyberpunk, Cosmic Horror, 16-Bit)."
    )
}

data class CustomStyleOptions(
    val globeStyle: GlobeRenderStyle = GlobeRenderStyle.CLASSIC_TWIN_GLOBES,
    val colorThemePreset: String = "Blood & Gold",
    val primaryColorHex: String = "#C6772E",
    val lifeColorHex: String = "#E53935",
    val manaColorHex: String = "#00B0FF",
    val showStaminaBar: Boolean = true,
    val showPotionBelt: Boolean = true,
    val showKillStreak: Boolean = true,
    val hudScale: Float = 1.0f
)

enum class GlobeRenderStyle(val label: String) {
    CLASSIC_TWIN_GLOBES("Classic Twin Globes (Left/Right)"),
    MODERN_ARC_BARS("Curved Modern Arc Meters"),
    BOTTOM_MINIMAL_BARS("Compact Minimalist Deck"),
    RETRO_PIXEL_BOXES("16-Bit Pixel Gauges")
}

data class GameThemeProfile(
    val id: String = UUID.randomUUID().toString(),
    val themeName: String,
    val subtitle: String,
    val loreDescription: String,
    val recommendedEra: DiabloEra = DiabloEra.DIABLO_2,
    val heroClassName: String = "Warrior",
    val heroSkill1Name: String = "Thunder Strike",
    val heroSkill2Name: String = "Solar Supernova",
    val heroDashName: String = "Dash Evade",
    val heroWeaponName: String = "Iron Longsword",
    val lifeResourceName: String = "Health",
    val spiritResourceName: String = "Mana",
    val enemyBossName: String = "Catacomb Overlord",
    val floorHex: String = "#1B221E",
    val wallHex: String = "#0D1411",
    val primaryHex: String = "#C6772E",
    val accentHex: String = "#FFD700",
    val lifeColorHex: String = "#E53935",
    val manaColorHex: String = "#00B0FF",
    val rawSpritesheetPrompt: String = "Pixel art 4x4 sprite sheet of a fantasy warrior hero, 48x48 frames, top-down isometric ARPG, walk attack cast animations",
    val isAiGenerated: Boolean = false
)

object PreloadedThemes {
    val CLASSIC_GENERIC = GameThemeProfile(
        id = "theme_classic_generic",
        themeName = "Classic Dark Fantasy",
        subtitle = "Cathedral of Torment",
        loreDescription = "A grim gothic realm overrun by undead shades and iron automatons. Champions delve into underground crypts seeking legendary blades.",
        recommendedEra = DiabloEra.DIABLO_1,
        heroClassName = "Warrior",
        heroSkill1Name = "Lightning Strike",
        heroSkill2Name = "Flame Nova",
        heroDashName = "Tactical Roll",
        heroWeaponName = "Iron Greatsword",
        lifeResourceName = "Health",
        spiritResourceName = "Mana",
        enemyBossName = "Crypt Overlord",
        floorHex = "#1A1A1A",
        wallHex = "#0C0C0C",
        primaryHex = "#C6772E",
        accentHex = "#FFD700",
        lifeColorHex = "#E53935",
        manaColorHex = "#00B0FF",
        rawSpritesheetPrompt = "16-bit pixel art sprite sheet of a knight warrior with sword and shield, 4x4 grid, 48x48 pixels per frame, transparent background"
    )

    val DIABLO_SANCTUARY = GameThemeProfile(
        id = "theme_diablo_sanctuary",
        themeName = "Sanctuary: Lord of Darkness",
        subtitle = "Burning Hells vs High Heavens",
        loreDescription = "Demonic legions siege the rogue encampment. Soulstones crackle with primeval malice as nephalem forge runewords.",
        recommendedEra = DiabloEra.DIABLO_2,
        heroClassName = "Paladin",
        heroSkill1Name = "Holy Bolt",
        heroSkill2Name = "Blessed Hammer",
        heroDashName = "Charge",
        heroWeaponName = "Runic Longsword",
        lifeResourceName = "Life",
        spiritResourceName = "Mana",
        enemyBossName = "Lord of Terror",
        floorHex = "#231B15",
        wallHex = "#120B07",
        primaryHex = "#D4AF37",
        accentHex = "#FF8F00",
        lifeColorHex = "#D32F2F",
        manaColorHex = "#1976D2",
        rawSpritesheetPrompt = "Diablo 2 style pixel art sprite sheet, paladin in gilded plate armor with glowing blade, 4x4 animations, 48x48 px"
    )

    val MYTHIC_IGBO_UKWU = GameThemeProfile(
        id = "theme_mythic_igbo",
        themeName = "Ala Igbo: Bronze Age of Chukwu",
        subtitle = "Ancestral Spirit Realm & 9th Century Bronze",
        loreDescription = "Initiated titleholders clad in roped bronze invoke the thunder of Amadioha and the solar fire of Anyanwu against corrupted Mmuo spirits.",
        recommendedEra = DiabloEra.DIABLO_2,
        heroClassName = "Dike Ozo",
        heroSkill1Name = "Egbe Amadioha",
        heroSkill2Name = "Oku Anyanwu",
        heroDashName = "Agụ Leap",
        heroWeaponName = "Mma Nkwu",
        lifeResourceName = "Ndụ",
        spiritResourceName = "Mmụọ",
        enemyBossName = "Agbara High Priest",
        floorHex = "#1B231F",
        wallHex = "#111714",
        primaryHex = "#C6772E",
        accentHex = "#FFB300",
        lifeColorHex = "#E53935",
        manaColorHex = "#00E5FF",
        rawSpritesheetPrompt = "Pixel art sprite sheet of an ancient West African warrior titleholder with Igbo-Ukwu roped bronze armor and curved blade, 4x4 grid, 48x48"
    )

    val CYBERPUNK_2099 = GameThemeProfile(
        id = "theme_cyberpunk_2099",
        themeName = "Cyberpunk 2099: Neon Syndicate",
        subtitle = "High-Tech, Low-Life Underworld",
        loreDescription = "Cyber-samurai and bio-hackers battle rogue AIs and corporate death squads in rain-slick neon alleys.",
        recommendedEra = DiabloEra.DIABLO_3,
        heroClassName = "Cyber-Samurai",
        heroSkill1Name = "EMP Overload",
        heroSkill2Name = "Plasma Thermal Grenade",
        heroDashName = "Nanite Dash",
        heroWeaponName = "Monomolecular Katana",
        lifeResourceName = "Vitality",
        spiritResourceName = "RAM Energy",
        enemyBossName = "Mainframe Overmind",
        floorHex = "#0E141B",
        wallHex = "#070A0F",
        primaryHex = "#00E5FF",
        accentHex = "#FF007F",
        lifeColorHex = "#FF1744",
        manaColorHex = "#00E5FF",
        rawSpritesheetPrompt = "Cyberpunk pixel art sprite sheet of a cyborg street samurai with glowing neon katana and cybernetic visor, 4x4 grid 48x48"
    )

    val COSMIC_HORROR = GameThemeProfile(
        id = "theme_cosmic_horror",
        themeName = "Eldritch Abyss: Sunken City",
        subtitle = "Non-Euclidean Cosmic Terror",
        loreDescription = "A doomed occultist traverses cyclopean ruins submerged beneath black tides, battling tentacled horrors and mind-shattering visions.",
        recommendedEra = DiabloEra.DIABLO_4,
        heroClassName = "Occult Inquisitor",
        heroSkill1Name = "Void Tendril",
        heroSkill2Name = "Abyssal Singularity",
        heroDashName = "Phase Shift",
        heroWeaponName = "Eldritch Relic Blade",
        lifeResourceName = "Sanity",
        spiritResourceName = "Aether",
        enemyBossName = "The Sleeper Below",
        floorHex = "#121A1A",
        wallHex = "#080E0E",
        primaryHex = "#00BFA5",
        accentHex = "#AA00FF",
        lifeColorHex = "#C2185B",
        manaColorHex = "#651FFF",
        rawSpritesheetPrompt = "Dark lovecraftian pixel art sprite sheet of a trenchcoated occultist with lantern and obsidian dagger, 4x4 grid 48x48"
    )

    val ALL_PRELOADED = listOf(
        CLASSIC_GENERIC,
        DIABLO_SANCTUARY,
        MYTHIC_IGBO_UKWU,
        CYBERPUNK_2099,
        COSMIC_HORROR
    )
}

// Naming system translators for Pure Clean Architecture
fun HeroClass.resolveName(namingSystem: NamingSystem): String {
    return when (namingSystem) {
        NamingSystem.GENERIC_RPG -> when (id) {
            "class_ozo_bladesman" -> "Warrior (Knight)"
            "class_amadioha_stormcaller" -> "Storm Mage"
            "class_dibia_alchemist" -> "Druid Alchemist"
            "class_ikenga_berserker" -> "Berserker"
            else -> name
        }
        NamingSystem.MYTHIC_ANCIENT -> name
        NamingSystem.AI_THEMED -> name
    }
}

fun WeaponItem.resolveName(namingSystem: NamingSystem): String {
    return when (namingSystem) {
        NamingSystem.GENERIC_RPG -> when (baseType) {
            BaseWeaponType.OFO_SCEPTER -> "${rarity.displayName} Mystic Staff"
            BaseWeaponType.ALO_WAR_STAFF -> "${rarity.displayName} War Stave"
            BaseWeaponType.MMA_NKWU -> "${rarity.displayName} Iron Broadsword"
            BaseWeaponType.IKENGA_CLEAVER -> "${rarity.displayName} Heavy Cleaver"
            BaseWeaponType.ASA_JAVELIN -> "${rarity.displayName} Battle Spear"
            BaseWeaponType.ULI_SACRED_BOW -> "${rarity.displayName} Recurve Bow"
        }
        NamingSystem.MYTHIC_ANCIENT -> name
        NamingSystem.AI_THEMED -> name
    }
}

fun MonsterEntity.resolveName(namingSystem: NamingSystem): String {
    return when (namingSystem) {
        NamingSystem.GENERIC_RPG -> when (archetype) {
            MonsterArchetype.FOREST_MMUO -> "Corrupted Shade"
            MonsterArchetype.IKENGA_GOLEM -> "Stone Golem"
            MonsterArchetype.EKPE_LEOPARD_WARRIOR -> "Shadow Stalker"
            MonsterArchetype.CORRUPTED_DIBIA -> "Dark Cultist"
            MonsterArchetype.ANCIENT_BRONZE_COLOSSUS -> "Ancient Titan (Boss)"
        }
        NamingSystem.MYTHIC_ANCIENT -> name
        NamingSystem.AI_THEMED -> name
    }
}


