package com.stratum.legacy.domain

/**
 * CHAPTER 01: DOMAIN MODEL - ACTION RPG COMBAT & HERO SYSTEM
 *
 * This literate module encapsulates the mathematical and ontological foundation
 * of an Action Role Playing Game (ARPG) combat loop,
 * deeply rooted in ancient Igbo philosophy and cosmology.
 *
 * Key Igbo Philosophical Foundations:
 * - NDU (Life Force): Health pool, vital energy granted by Chukwu.
 * - MMUO (Spirit / Mana): Arcane/divine conduit for invoking deities like Amadioha & Anyanwu.
 * - IKE (Strength): Physical power, mastery over bronze, iron, and heavy weaponry.
 * - UCHE (Wisdom & Agility): Cunning, dexterity, critical strikes, and defensive reflexes.
 */

enum class DamageType(val displayName: String, val igboName: String, val colorHex: String) {
    PHYSICAL("Physical", "Ike Akpu", "#E0E0E0"),
    BRONZE_FIRE("Bronze Fire (Anyanwu)", "Oku Anyanwu", "#FF6D00"),
    SOLAR_FIRE("Solar Fire (Anyanwu)", "Oku Anyanwu", "#FF6D00"),
    THUNDER("Thunder (Amadioha)", "Egbe Igwe", "#00E5FF"),
    SACRED_NSIBIDI("Sacred Nsibidi", "Nsibidi Dibia", "#B388FF"),
    DIVINE("Divine Spirit", "Chukwu Nso", "#FFD700"),
    EARTH_ALA("Ala's Sanctity", "Ala Nso", "#76FF03"),
    SPIRIT_POISON("Idemili Venom", "Mmiri Ogu", "#00E676");

    val colorArgb: Int = parseHexColor(colorHex, 0xFFE0E0E0.toInt())
}

data class CombatAttributes(
    val ikeStrength: Int = 18,       // Increases base physical damage and armor
    val ucheAgility: Int = 14,        // Increases critical strike chance and dodge
    val nduVitality: Int = 20,        // Increases max health and health recovery
    val mmuoFocus: Int = 16           // Increases spirit/mana pool and elemental spell damage
) {
    val maxHealth: Int get() = nduVitality * 12 + ikeStrength * 2
    val maxSpirit: Int get() = mmuoFocus * 10 + ucheAgility * 2
    val baseArmor: Int get() = ikeStrength / 2 + 5
    val critChance: Float get() = (0.05f + (ucheAgility * 0.005f)).coerceIn(0.05f, 0.75f)
    val critMultiplier: Float get() = 1.5f + (ucheAgility * 0.015f)
}

data class DamageInstance(
    val baseMinDamage: Int,
    val baseMaxDamage: Int,
    val damageType: DamageType = DamageType.PHYSICAL,
    val isCritical: Boolean = false,
    val isEvaded: Boolean = false,
    val actualDamageDealt: Int = 0,
    val lifeLeeched: Int = 0,
    val mechanicTriggered: String? = null
)

data class FloatingCombatText(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    var x: Float,
    var y: Float,
    val colorHex: String,
    val lifetimeMs: Long = 1000L,
    var ageMs: Long = 0L,
    val isCrit: Boolean = false
) {
    val colorArgb: Int = parseHexColor(colorHex, 0xFFFFFFFF.toInt())
}

enum class MonsterArchetype(val title: String, val igboTitle: String, val colorHex: String) {
    FOREST_MMUO("Corrupted Spirit", "Mmuo Ohia", "#26A69A"),
    IKENGA_GOLEM("Ikenga Bronze Automaton", "Ikenga Onye Ozo", "#FF8F00"),
    EKPE_LEOPARD_WARRIOR("Ekpe Leopard Cultist", "Onye Ekpe", "#E53935"),
    CORRUPTED_DIBIA("Fallen Shaman", "Dibia Ojoo", "#AB47BC"),
    ANCIENT_BRONZE_COLOSSUS("Igbo-Ukwu Bronze Titan", "Oka Bronze Ukwu", "#D4AF37");

    val colorArgb: Int = parseHexColor(colorHex, 0xFFE53935.toInt())
}

enum class MonsterAiState {
    PURSUIT,    // Moving towards target or surround slot
    FLANKING,   // Circling and seeking flanking position
    TELEGRAPH,  // Preparing an attack (ground indicator / windup)
    POUNCE,     // High speed leap/dash attack
    RETREAT,    // Backing away to range (kiting for casters)
    STAGGERED   // Hit stunned
}

data class MonsterEntity(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val archetype: MonsterArchetype,
    var x: Float,
    var y: Float,
    val maxHp: Int,
    var currentHp: Int,
    val attackPower: Int,
    val defense: Int,
    val speed: Float = 1.8f,
    val goldReward: Int = 15,
    val expReward: Int = 40,
    val isBoss: Boolean = false,
    var hitFlashMs: Long = 0L,
    var staggerMs: Long = 0L,
    var knockbackVx: Float = 0f,
    var knockbackVy: Float = 0f,
    var scaleSquash: Float = 1.0f,
    var animationState: CharacterAnimationState = CharacterAnimationState.WALK,
    var aiState: MonsterAiState = MonsterAiState.PURSUIT,
    var attackCooldownMs: Long = 0L,
    var specialCooldownMs: Long = 0L,
    var targetOffsetAngleRad: Float = 0f,
    var telegraphMs: Long = 0L,
    var telegraphMaxMs: Long = 0L,
    var pounceVx: Float = 0f,
    var pounceVy: Float = 0f,
    var facingAngleRad: Float = 0f,
    var summonTriggeredCount: Int = 0
) {
    val isDead: Boolean get() = currentHp <= 0
    val archetypeId: String get() = when (archetype) {
        MonsterArchetype.FOREST_MMUO -> "arch_forest_mmo"
        MonsterArchetype.IKENGA_GOLEM -> "arch_ogu_brute"
        MonsterArchetype.EKPE_LEOPARD_WARRIOR -> "arch_shadow_leopard"
        MonsterArchetype.CORRUPTED_DIBIA, MonsterArchetype.ANCIENT_BRONZE_COLOSSUS -> "arch_boss_priest"
    }
}

data class CombatImpactSpark(
    val id: String = java.util.UUID.randomUUID().toString(),
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val colorHex: String = "#FFD54F",
    val radius: Float = 3.5f,
    val maxLifetimeMs: Long = 280L,
    var ageMs: Long = 0L
)

data class ActiveProjectile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val startX: Float,
    val startY: Float,
    var currentX: Float,
    var currentY: Float,
    val targetX: Float,
    val targetY: Float,
    val speed: Float,
    val damage: Int,
    val damageType: DamageType,
    val radius: Float = 14f,
    val maxDistance: Float = 400f,
    var traveledDistance: Float = 0f,
    val colorHex: String = "#00E5FF",
    val effectName: String = "Amadioha Bolt",
    val isHostile: Boolean = false
)

data class ActiveLootDrop(
    val id: String = java.util.UUID.randomUUID().toString(),
    val weapon: WeaponItem,
    var x: Float,
    var y: Float,
    val dropTimeMs: Long = System.currentTimeMillis(),
    val goldReward: Int = 0,
    val healthReward: Int = 0
)
