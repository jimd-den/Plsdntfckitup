package com.stratum.legacy.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * CHAPTER 05: DOMAIN ENGINE - REAL-TIME ACTION RPG GAME LOOP & MECHANICS LAB
 *
 * This literate engine implements an action RPG game loop with real-time entity
 * simulations, collision checks, customizable mechanics, and combat dummy testing.
 *
 * It is completely clean architecture: pure Kotlin, zero Android UI dependencies,
 * fully testable via unit tests and simulation ticks.
 */

data class CustomGameMechanic(
    val id: String,
    val name: String,
    val igboName: String,
    val description: String,
    val isEnabled: Boolean = true,
    val procChance: Float = 0.25f,
    val triggerType: MechanicTriggerType,
    val numericParameter: Float = 1.0f
)

enum class MechanicTriggerType {
    ON_HIT,
    ON_CRIT,
    ON_TAKE_DAMAGE,
    ON_KILL,
    PASSIVE_AURA
}

data class HeroCombatState(
    var x: Float = 0f,
    var y: Float = 0f,
    var currentHealth: Int = 250,
    val maxHealth: Int = 250,
    var currentSpirit: Int = 120,
    val maxSpirit: Int = 120,
    val speed: Float = 4.2f,
    var facingAngleRad: Float = 0f,
    var animationState: CharacterAnimationState = CharacterAnimationState.IDLE,
    var attackCooldownMs: Long = 0L,
    var thunderCooldownMs: Long = 0L,
    var sunNovaCooldownMs: Long = 0L,
    var dashCooldownMs: Long = 0L,
    var isInvulnerable: Boolean = false,
    var invulnerableTimerMs: Long = 0L,
    var level: Int = 1,
    var currentExp: Int = 0,
    var expToNextLevel: Int = 100,
    var gold: Int = 50,
    var equippedWeapon: WeaponItem? = null,
    val attributes: CombatAttributes = CombatAttributes(),
    var staggerMs: Long = 0L,
    var hitFlashMs: Long = 0L,
    var knockbackVx: Float = 0f,
    var knockbackVy: Float = 0f
)

data class ArpgWorldState(
    val hero: HeroCombatState = HeroCombatState(),
    val monsters: MutableList<MonsterEntity> = mutableListOf(),
    val projectiles: MutableList<ActiveProjectile> = mutableListOf(),
    val lootDrops: MutableList<ActiveLootDrop> = mutableListOf(),
    val combatTexts: MutableList<FloatingCombatText> = mutableListOf(),
    val impactSparks: MutableList<CombatImpactSpark> = mutableListOf(),
    val activeMechanics: MutableList<CustomGameMechanic> = mutableListOf(),
    var currentWave: Int = 1,
    var monstersDefeated: Int = 0,
    var isDummyTestMode: Boolean = false,
    var totalDamageDealtInSession: Long = 0L,
    var combatTimerMs: Long = 0L,
    var screenShakeIntensity: Float = 0f,
    var screenShakeDurationMs: Long = 0L,
    var hitFreezeMs: Long = 0L,
    var lastSlashAngleRad: Float = 0f,
    var slashArcTimerMs: Long = 0L,
    var isPaused: Boolean = false
) {
    val dpsInSession: Float get() {
        val seconds = (combatTimerMs / 1000f).coerceAtLeast(1f)
        return totalDamageDealtInSession / seconds
    }
}

class ArpgGameLoopEngine(
    private val world: ArpgWorldState = ArpgWorldState()
) {
    val state: ArpgWorldState get() = world

    init {
        // Initialize default inventable game mechanics
        world.activeMechanics.addAll(defaultInventedMechanics())
        // Start with default equipped weapon
        if (world.hero.equippedWeapon == null) {
            world.hero.equippedWeapon = WeaponStatRandomizer.rollRandomWeapon(
                baseType = BaseWeaponType.MMA_NKWU,
                targetRarity = WeaponRarity.RARE,
                itemLevel = 5
            ).copy(isEquipped = true)
        }
        spawnInitialEnemies()
    }

    fun setDummyTestMode(enabled: Boolean) {
        world.isDummyTestMode = enabled
        world.monsters.clear()
        if (enabled) {
            // Spawn high-HP training dummy in center
            world.monsters.add(
                MonsterEntity(
                    id = "dummy_01",
                    name = "Sacred Training Effigy (Dummy)",
                    archetype = MonsterArchetype.IKENGA_GOLEM,
                    x = 0f,
                    y = -120f,
                    maxHp = 99999,
                    currentHp = 99999,
                    attackPower = 0,
                    defense = 10,
                    speed = 0f
                )
            )
        } else {
            spawnWave(world.currentWave)
        }
    }

    fun resetCombatStats() {
        world.totalDamageDealtInSession = 0L
        world.combatTimerMs = 0L
    }

    val isPaused: Boolean get() = world.isPaused

    fun pause() {
        world.isPaused = true
    }

    fun resume() {
        world.isPaused = false
    }

    fun togglePause(): Boolean {
        world.isPaused = !world.isPaused
        return world.isPaused
    }

    fun tick(
        dtMs: Long,
        joystickX: Float,
        joystickY: Float,
        isAttackPressed: Boolean,
        isThunderSkillPressed: Boolean,
        isSunNovaSkillPressed: Boolean,
        isDashPressed: Boolean
    ) {
        if (world.isPaused) return

        world.combatTimerMs += dtMs
        val hero = world.hero

        // Screen shake decay
        if (world.screenShakeDurationMs > 0L) {
            world.screenShakeDurationMs = (world.screenShakeDurationMs - dtMs).coerceAtLeast(0L)
            world.screenShakeIntensity *= 0.90f
        }

        // Slash arc decay
        if (world.slashArcTimerMs > 0L) {
            world.slashArcTimerMs = (world.slashArcTimerMs - dtMs).coerceAtLeast(0L)
        }

        // Hit freeze (impact pause)
        if (world.hitFreezeMs > 0L) {
            world.hitFreezeMs = (world.hitFreezeMs - dtMs).coerceAtLeast(0L)
        }

        // Hero knockback physics
        hero.x += hero.knockbackVx * (dtMs / 16.6f)
        hero.y += hero.knockbackVy * (dtMs / 16.6f)
        hero.knockbackVx *= 0.80f
        hero.knockbackVy *= 0.80f

        // Hero stagger & hit flash decay
        if (hero.staggerMs > 0L) {
            hero.staggerMs = (hero.staggerMs - dtMs).coerceAtLeast(0L)
            hero.animationState = CharacterAnimationState.HURT
        } else if (hero.animationState == CharacterAnimationState.HURT) {
            hero.animationState = CharacterAnimationState.IDLE
        }

        if (hero.hitFlashMs > 0L) {
            hero.hitFlashMs = (hero.hitFlashMs - dtMs).coerceAtLeast(0L)
        }

        // 1. Cooldown management
        if (hero.attackCooldownMs > 0) hero.attackCooldownMs = (hero.attackCooldownMs - dtMs).coerceAtLeast(0)
        if (hero.thunderCooldownMs > 0) hero.thunderCooldownMs = (hero.thunderCooldownMs - dtMs).coerceAtLeast(0)
        if (hero.sunNovaCooldownMs > 0) hero.sunNovaCooldownMs = (hero.sunNovaCooldownMs - dtMs).coerceAtLeast(0)
        if (hero.dashCooldownMs > 0) hero.dashCooldownMs = (hero.dashCooldownMs - dtMs).coerceAtLeast(0)
        if (hero.invulnerableTimerMs > 0) {
            hero.invulnerableTimerMs = (hero.invulnerableTimerMs - dtMs).coerceAtLeast(0)
            if (hero.invulnerableTimerMs == 0L) hero.isInvulnerable = false
        }

        // 2. Dash input
        if (isDashPressed && hero.dashCooldownMs == 0L) {
            hero.dashCooldownMs = 1500L
            hero.isInvulnerable = true
            hero.invulnerableTimerMs = 350L
            val dashMag = 75f
            val angle = hero.facingAngleRad
            hero.x += cos(angle) * dashMag
            hero.y += sin(angle) * dashMag
            world.combatTexts.add(
                FloatingCombatText(
                    text = "Ekpe Dash!",
                    x = hero.x,
                    y = hero.y - 20f,
                    colorHex = "#FFD54F"
                )
            )
        }

        // 3. Movement (interrupted by stagger)
        val mag = sqrt(joystickX * joystickX + joystickY * joystickY)
        if (mag > 0.15f && hero.staggerMs == 0L) {
            val normX = joystickX / mag
            val normY = joystickY / mag
            hero.x += normX * hero.speed * (dtMs / 16.6f)
            hero.y += normY * hero.speed * (dtMs / 16.6f)
            hero.facingAngleRad = atan2(normY, normX)
            if (hero.animationState != CharacterAnimationState.ATTACK) {
                hero.animationState = CharacterAnimationState.WALK
            }
        } else {
            if (hero.animationState == CharacterAnimationState.WALK) {
                hero.animationState = CharacterAnimationState.IDLE
            }
        }

        // Constrain hero to arena bounds
        val arenaLimit = 650f
        hero.x = hero.x.coerceIn(-arenaLimit, arenaLimit)
        hero.y = hero.y.coerceIn(-arenaLimit, arenaLimit)

        // 3b. Magnetic Loot Vacuum & Automatic Pickup
        val collectedDrops = mutableListOf<ActiveLootDrop>()
        for (drop in world.lootDrops) {
            val dX = hero.x - drop.x
            val dY = hero.y - drop.y
            val dist = sqrt(dX * dX + dY * dY)

            // Magnetic vacuum pull towards hero within 130px
            if (dist < 130f && dist > 0.1f) {
                val pullSpeed = 10.0f * (1.0f - dist / 130f).coerceIn(0.25f, 1.0f) * (dtMs / 16.6f)
                drop.x += (dX / dist) * pullSpeed
                drop.y += (dY / dist) * pullSpeed
            }

            // Automatic proximity collection when hero touches item (< 38px)
            if (dist < 38f) {
                collectedDrops.add(drop)
            }
        }

        for (collected in collectedDrops) {
            pickUpLoot(collected.id)
        }

        // 4. Skills & Attacks
        val weapon = hero.equippedWeapon
        val attackIntervalMs = if (weapon != null) (1000f / weapon.attackSpeed).toLong() else 600L

        if (isAttackPressed && hero.attackCooldownMs == 0L && hero.staggerMs == 0L) {
            hero.attackCooldownMs = attackIntervalMs
            hero.animationState = CharacterAnimationState.ATTACK
            executePrimaryAttack()
        }

        if (isThunderSkillPressed && hero.thunderCooldownMs == 0L && hero.currentSpirit >= 25 && hero.staggerMs == 0L) {
            hero.currentSpirit -= 25
            hero.thunderCooldownMs = 2000L
            hero.animationState = CharacterAnimationState.CAST_SPELL
            castThunderOfAmadioha()
        }

        if (isSunNovaSkillPressed && hero.sunNovaCooldownMs == 0L && hero.currentSpirit >= 35 && hero.staggerMs == 0L) {
            hero.currentSpirit -= 35
            hero.sunNovaCooldownMs = 3500L
            hero.animationState = CharacterAnimationState.CAST_SPELL
            castSunNovaOfAnyanwu()
        }

        // Passive spirit regeneration
        if (hero.currentSpirit < hero.maxSpirit && Random.nextInt(10) == 0) {
            hero.currentSpirit = (hero.currentSpirit + 1).coerceAtMost(hero.maxSpirit)
        }

        // 5. Projectiles tick
        updateProjectiles(dtMs)

        // 6. Monsters tick
        updateMonsters(dtMs)

        // 7. Impact Sparks tick
        val sparkIterator = world.impactSparks.iterator()
        while (sparkIterator.hasNext()) {
            val spark = sparkIterator.next()
            spark.ageMs += dtMs
            if (spark.ageMs >= spark.maxLifetimeMs) {
                sparkIterator.remove()
            } else {
                spark.x += spark.vx * (dtMs / 16.6f)
                spark.y += spark.vy * (dtMs / 16.6f)
                spark.vx *= 0.90f
                spark.vy *= 0.90f
            }
        }

        // 8. Floating Combat Texts tick
        val textIterator = world.combatTexts.iterator()
        while (textIterator.hasNext()) {
            val text = textIterator.next()
            text.ageMs += dtMs
            if (text.ageMs >= text.lifetimeMs) {
                textIterator.remove()
            } else {
                text.y -= (dtMs * 0.04f)
            }
        }

        // 9. Auto wave check
        if (!world.isDummyTestMode && world.monsters.isEmpty()) {
            world.currentWave++
            spawnWave(world.currentWave)
        }
    }

    private fun executePrimaryAttack() {
        val hero = world.hero
        val weapon = hero.equippedWeapon
        val baseMin = weapon?.minDamage ?: 15
        val baseMax = weapon?.maxDamage ?: 25
        val rollDmg = Random.nextInt(baseMin, baseMax + 1)
        val isCrit = Random.nextFloat() < (hero.attributes.critChance + (weapon?.critBonusChance ?: 0f))
        val finalDmg = if (isCrit) (rollDmg * (hero.attributes.critMultiplier + (weapon?.critDamageBonus ?: 0f))).toInt() else rollDmg

        val reach = 95f
        val attackAngle = hero.facingAngleRad
        world.lastSlashAngleRad = attackAngle
        world.slashArcTimerMs = 180L
        var hitAny = false

        world.monsters.forEach { monster ->
            val dx = monster.x - hero.x
            val dy = monster.y - hero.y
            val dist = sqrt(dx * dx + dy * dy)
            if (dist <= reach) {
                val angleToEnemy = atan2(dy, dx)
                val angleDiff = kotlin.math.abs(angleToEnemy - attackAngle)
                // 120 degree frontal arc
                if (angleDiff <= 1.05f || angleDiff >= (2 * Math.PI - 1.05f)) {
                    hitAny = true
                    applyDamageToMonster(monster, finalDmg, isCrit, weapon?.primaryDamageType ?: DamageType.PHYSICAL, hitAngleRad = angleToEnemy)
                    triggerMechanics(MechanicTriggerType.ON_HIT, monster, finalDmg)
                    if (isCrit) {
                        triggerMechanics(MechanicTriggerType.ON_CRIT, monster, finalDmg)
                    }
                    // Life leech check
                    val leechPercent = (weapon?.lifeLeechPercent ?: 0f)
                    if (leechPercent > 0) {
                        val heal = (finalDmg * leechPercent).toInt().coerceAtLeast(1)
                        hero.currentHealth = (hero.currentHealth + heal).coerceAtMost(hero.maxHealth)
                    }
                }
            }
        }

        if (!hitAny) {
            // Visual slash floating indicator
            world.combatTexts.add(
                FloatingCombatText(
                    text = "Swing",
                    x = hero.x + cos(attackAngle) * 50f,
                    y = hero.y + sin(attackAngle) * 50f,
                    colorHex = "#B0BEC5",
                    lifetimeMs = 400L
                )
            )
        }
    }

    private fun castThunderOfAmadioha() {
        val hero = world.hero
        val angle = hero.facingAngleRad
        val proj = ActiveProjectile(
            startX = hero.x,
            startY = hero.y,
            currentX = hero.x,
            currentY = hero.y,
            targetX = hero.x + cos(angle) * 350f,
            targetY = hero.y + sin(angle) * 350f,
            speed = 9f,
            damage = Random.nextInt(45, 80),
            damageType = DamageType.THUNDER,
            colorHex = "#00E5FF",
            effectName = "Thunder Bolt"
        )
        world.projectiles.add(proj)
        world.combatTexts.add(
            FloatingCombatText(
                text = "Egbe Amadioha!",
                x = hero.x,
                y = hero.y - 30f,
                colorHex = "#00E5FF"
            )
        )
    }

    private fun castSunNovaOfAnyanwu() {
        val hero = world.hero
        val novaRadius = 160f
        val novaDmg = Random.nextInt(60, 110)
        world.combatTexts.add(
            FloatingCombatText(
                text = "Oku Anyanwu!",
                x = hero.x,
                y = hero.y - 35f,
                colorHex = "#FF9100",
                isCrit = true
            )
        )
        world.monsters.forEach { monster ->
            val dx = monster.x - hero.x
            val dy = monster.y - hero.y
            val dist = sqrt(dx * dx + dy * dy)
            if (dist <= novaRadius) {
                applyDamageToMonster(monster, novaDmg, isCrit = true, damageType = DamageType.BRONZE_FIRE, hitAngleRad = atan2(dy, dx))
            }
        }
    }

    private fun updateProjectiles(dtMs: Long) {
        val toRemove = mutableListOf<ActiveProjectile>()
        val hero = world.hero

        world.projectiles.forEach { proj ->
            val dx = proj.targetX - proj.startX
            val dy = proj.targetY - proj.startY
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val step = proj.speed * (dtMs / 16.6f)
            proj.currentX += (dx / len) * step
            proj.currentY += (dy / len) * step
            proj.traveledDistance += step

            if (proj.isHostile) {
                // Collide with Hero
                val hdx = hero.x - proj.currentX
                val hdy = hero.y - proj.currentY
                val distHero = sqrt(hdx * hdx + hdy * hdy)
                if (distHero <= (proj.radius + 18f) && !hero.isInvulnerable) {
                    val incomingDmg = (proj.damage - hero.attributes.baseArmor).coerceAtLeast(5)
                    hero.currentHealth = (hero.currentHealth - incomingDmg).coerceAtLeast(0)
                    hero.staggerMs = 240L
                    hero.hitFlashMs = 200L
                    hero.animationState = CharacterAnimationState.HURT

                    hero.knockbackVx = (dx / len) * 5.2f
                    hero.knockbackVy = (dy / len) * 5.2f
                    world.screenShakeIntensity = 8f
                    world.screenShakeDurationMs = 180L

                    for (i in 0 until 6) {
                        val sAngle = Random.nextFloat() * 6.28f
                        val sSpeed = Random.nextFloat() * 4.5f + 1.5f
                        world.impactSparks.add(
                            CombatImpactSpark(
                                x = hero.x,
                                y = hero.y,
                                vx = cos(sAngle) * sSpeed,
                                vy = sin(sAngle) * sSpeed,
                                colorHex = proj.colorHex,
                                radius = 3.2f,
                                maxLifetimeMs = 220L
                            )
                        )
                    }

                    world.combatTexts.add(
                        FloatingCombatText(
                            text = "-$incomingDmg",
                            x = hero.x,
                            y = hero.y - 15f,
                            colorHex = "#FF5252"
                        )
                    )
                    toRemove.add(proj)
                }
            } else {
                // Check collision with monsters
                world.monsters.forEach { monster ->
                    val mdx = monster.x - proj.currentX
                    val mdy = monster.y - proj.currentY
                    if (sqrt(mdx * mdx + mdy * mdy) <= (proj.radius + 20f)) {
                        applyDamageToMonster(monster, proj.damage, isCrit = false, damageType = proj.damageType, hitAngleRad = atan2(dy, dx))
                        toRemove.add(proj)
                    }
                }
            }

            if (proj.traveledDistance >= proj.maxDistance) {
                toRemove.add(proj)
            }
        }
        world.projectiles.removeAll(toRemove)
    }

    private fun triggerMonsterMeleeHit(
        monster: MonsterEntity,
        hero: HeroCombatState,
        isHeavy: Boolean,
        bonusDmg: Int
    ) {
        if (hero.isInvulnerable) return

        val baseDmg = monster.attackPower + bonusDmg
        val incomingDmg = (baseDmg - hero.attributes.baseArmor).coerceAtLeast(4)
        hero.currentHealth = (hero.currentHealth - incomingDmg).coerceAtLeast(0)

        // Hero hit reaction: Stagger, Hit Flash, Knockback, Screen Shake, Hurt Animation
        hero.staggerMs = if (isHeavy) 360L else 220L
        hero.hitFlashMs = 200L
        hero.animationState = CharacterAnimationState.HURT

        val hdx = hero.x - monster.x
        val hdy = hero.y - monster.y
        val hlen = sqrt(hdx * hdx + hdy * hdy).coerceAtLeast(1f)
        val knockForce = if (isHeavy) 10.5f else 6.2f
        hero.knockbackVx = (hdx / hlen) * knockForce
        hero.knockbackVy = (hdy / hlen) * knockForce
        world.screenShakeIntensity = if (isHeavy) 15f else 9f
        world.screenShakeDurationMs = if (isHeavy) 260L else 180L
        world.hitFreezeMs = if (isHeavy) 45L else 25L

        // Impact spark particles
        val sparkCount = if (isHeavy) 12 else 6
        for (i in 0 until sparkCount) {
            val sAngle = Random.nextFloat() * 6.28f
            val sSpeed = Random.nextFloat() * 5.5f + 2f
            world.impactSparks.add(
                CombatImpactSpark(
                    x = hero.x,
                    y = hero.y,
                    vx = cos(sAngle) * sSpeed,
                    vy = sin(sAngle) * sSpeed,
                    colorHex = if (isHeavy) "#FF8F00" else "#FF1744",
                    radius = 3.5f,
                    maxLifetimeMs = 260L
                )
            )
        }

        world.combatTexts.add(
            FloatingCombatText(
                text = "-$incomingDmg",
                x = hero.x,
                y = hero.y - 15f,
                colorHex = if (isHeavy) "#FF1744" else "#FF5252",
                isCrit = isHeavy
            )
        )
        triggerMechanics(MechanicTriggerType.ON_TAKE_DAMAGE, monster, incomingDmg)
    }

    private fun updateMonsters(dtMs: Long) {
        val hero = world.hero
        val deadMonsters = mutableListOf<MonsterEntity>()
        val newSpawns = mutableListOf<MonsterEntity>()

        val livingMonsters = world.monsters.filter { !it.isDead }
        val monsterCount = livingMonsters.size.coerceAtLeast(1)

        livingMonsters.forEachIndexed { index, monster ->
            // 1. Decay hit flash & stagger
            if (monster.hitFlashMs > 0L) {
                monster.hitFlashMs = (monster.hitFlashMs - dtMs).coerceAtLeast(0L)
            }
            if (monster.staggerMs > 0L) {
                monster.staggerMs = (monster.staggerMs - dtMs).coerceAtLeast(0L)
                monster.aiState = MonsterAiState.STAGGERED
                monster.telegraphMs = 0L // Player attacks interrupt monster windups!
            } else if (monster.aiState == MonsterAiState.STAGGERED) {
                monster.aiState = MonsterAiState.PURSUIT
            }

            // 2. Decay attack & ability cooldowns
            if (monster.attackCooldownMs > 0L) {
                monster.attackCooldownMs = (monster.attackCooldownMs - dtMs).coerceAtLeast(0L)
            }
            if (monster.specialCooldownMs > 0L) {
                monster.specialCooldownMs = (monster.specialCooldownMs - dtMs).coerceAtLeast(0L)
            }

            // 3. Recover squash & stretch
            monster.scaleSquash += (1.0f - monster.scaleSquash) * 0.22f

            // 4. Knockback physics with mass poise resistance
            val poiseFriction = when (monster.archetype) {
                MonsterArchetype.IKENGA_GOLEM, MonsterArchetype.ANCIENT_BRONZE_COLOSSUS -> 0.72f
                else -> 0.82f
            }
            monster.x += monster.knockbackVx * (dtMs / 16.6f)
            monster.y += monster.knockbackVy * (dtMs / 16.6f)
            monster.knockbackVx *= poiseFriction
            monster.knockbackVy *= poiseFriction

            // 5. Pounce leap movement (Leopard leap attack)
            if (monster.aiState == MonsterAiState.POUNCE) {
                monster.x += monster.pounceVx * (dtMs / 16.6f)
                monster.y += monster.pounceVy * (dtMs / 16.6f)
                monster.pounceVx *= 0.88f
                monster.pounceVy *= 0.88f
                if (sqrt(monster.pounceVx * monster.pounceVx + monster.pounceVy * monster.pounceVy) < 1.2f) {
                    monster.aiState = MonsterAiState.PURSUIT
                }
            }

            // 6. Boss Titan Phase Transitions: Summon reinforcements at 60% and 30% HP
            if (monster.isBoss) {
                val bossHpRatio = monster.currentHp.toFloat() / monster.maxHp.toFloat()
                if (bossHpRatio <= 0.6f && monster.summonTriggeredCount == 0) {
                    monster.summonTriggeredCount = 1
                    world.combatTexts.add(
                        FloatingCombatText(
                            text = "TITAN CALLS MMỤỌ SPIRITS!",
                            x = monster.x,
                            y = monster.y - 45f,
                            colorHex = "#FF1744",
                            isCrit = true,
                            lifetimeMs = 2200L
                        )
                    )
                    for (s in 0 until 2) {
                        val sAngle = monster.facingAngleRad + (if (s == 0) 1.2f else -1.2f)
                        newSpawns.add(
                            MonsterEntity(
                                name = MonsterArchetype.FOREST_MMUO.title,
                                archetype = MonsterArchetype.FOREST_MMUO,
                                x = monster.x + cos(sAngle) * 70f,
                                y = monster.y + sin(sAngle) * 70f,
                                maxHp = 50 + world.currentWave * 15,
                                currentHp = 50 + world.currentWave * 15,
                                attackPower = 14 + world.currentWave * 2,
                                defense = 4,
                                speed = 2.2f
                            )
                        )
                    }
                } else if (bossHpRatio <= 0.3f && monster.summonTriggeredCount == 1) {
                    monster.summonTriggeredCount = 2
                    world.combatTexts.add(
                        FloatingCombatText(
                            text = "ENRAGED! AMBUSH CULTISTS SUMMONED!",
                            x = monster.x,
                            y = monster.y - 45f,
                            colorHex = "#FF8F00",
                            isCrit = true,
                            lifetimeMs = 2200L
                        )
                    )
                    for (s in 0 until 2) {
                        val sAngle = monster.facingAngleRad + (if (s == 0) 1.5f else -1.5f)
                        newSpawns.add(
                            MonsterEntity(
                                name = MonsterArchetype.EKPE_LEOPARD_WARRIOR.title,
                                archetype = MonsterArchetype.EKPE_LEOPARD_WARRIOR,
                                x = monster.x + cos(sAngle) * 80f,
                                y = monster.y + sin(sAngle) * 80f,
                                maxHp = 70 + world.currentWave * 20,
                                currentHp = 70 + world.currentWave * 20,
                                attackPower = 18 + world.currentWave * 3,
                                defense = 6,
                                speed = 2.4f
                            )
                        )
                    }
                }
            }

            // 7. Tactical AI: Only active when speed > 0 and not staggered
            if (monster.speed > 0f && monster.staggerMs == 0L) {
                val dx = hero.x - monster.x
                val dy = hero.y - monster.y
                val distToHero = sqrt(dx * dx + dy * dy).coerceAtLeast(0.1f)
                monster.facingAngleRad = atan2(dy, dx)

                // Flocking / Boids Separation force: Prevents monsters from collapsing into a single blob
                var separationX = 0f
                var separationY = 0f
                livingMonsters.forEachIndexed { otherIdx, other ->
                    if (otherIdx != index) {
                        val odx = monster.x - other.x
                        val ody = monster.y - other.y
                        val odist = sqrt(odx * odx + ody * ody)
                        val sepThreshold = if (monster.isBoss || other.isBoss) 65f else 40f
                        if (odist in 0.1f..sepThreshold) {
                            val pushWeight = (1.0f - (odist / sepThreshold)) * 1.8f
                            separationX += (odx / odist) * pushWeight
                            separationY += (ody / odist) * pushWeight
                        }
                    }
                }

                when (monster.archetype) {
                    MonsterArchetype.CORRUPTED_DIBIA -> {
                        // Ranged Shaman: Kites away if hero closes in, strafes at range, fires hostile void projectiles
                        val desiredRange = 180f
                        val minSafeRange = 120f

                        if (monster.telegraphMs > 0L) {
                            monster.telegraphMs = (monster.telegraphMs - dtMs).coerceAtLeast(0L)
                            monster.animationState = CharacterAnimationState.CAST_SPELL
                            if (monster.telegraphMs == 0L) {
                                world.projectiles.add(
                                    ActiveProjectile(
                                        startX = monster.x,
                                        startY = monster.y,
                                        currentX = monster.x,
                                        currentY = monster.y,
                                        targetX = hero.x,
                                        targetY = hero.y,
                                        speed = 4.6f,
                                        damage = monster.attackPower + 4,
                                        damageType = DamageType.SACRED_NSIBIDI,
                                        radius = 12f,
                                        maxDistance = 450f,
                                        colorHex = "#AB47BC",
                                        effectName = "Corrupted Void Orb",
                                        isHostile = true
                                    )
                                )
                                monster.specialCooldownMs = Random.nextLong(2200L, 3200L)
                                monster.aiState = MonsterAiState.PURSUIT
                                monster.animationState = CharacterAnimationState.IDLE
                            }
                        } else if (distToHero < minSafeRange) {
                            // Hero is close: Kite away!
                            monster.aiState = MonsterAiState.RETREAT
                            val retreatX = -(dx / distToHero) * monster.speed * 1.25f * (dtMs / 16.6f)
                            val retreatY = -(dy / distToHero) * monster.speed * 1.25f * (dtMs / 16.6f)
                            monster.x += retreatX + separationX
                            monster.y += retreatY + separationY
                            monster.animationState = CharacterAnimationState.WALK
                        } else if (distToHero > desiredRange + 60f) {
                            // Move into casting range
                            monster.aiState = MonsterAiState.PURSUIT
                            monster.x += (dx / distToHero) * monster.speed * (dtMs / 16.6f) + separationX
                            monster.y += (dy / distToHero) * monster.speed * (dtMs / 16.6f) + separationY
                            monster.animationState = CharacterAnimationState.WALK
                        } else {
                            // Optimal casting perimeter: Strafe and cast
                            monster.aiState = MonsterAiState.FLANKING
                            val strafeX = -sin(monster.facingAngleRad) * monster.speed * 0.7f * (dtMs / 16.6f)
                            val strafeY = cos(monster.facingAngleRad) * monster.speed * 0.7f * (dtMs / 16.6f)
                            monster.x += strafeX + separationX
                            monster.y += strafeY + separationY
                            monster.animationState = CharacterAnimationState.WALK

                            if (monster.specialCooldownMs == 0L) {
                                monster.telegraphMs = 400L
                                monster.telegraphMaxMs = 400L
                                monster.aiState = MonsterAiState.TELEGRAPH
                            }
                        }
                    }

                    MonsterArchetype.EKPE_LEOPARD_WARRIOR -> {
                        // Ambush Predator: Flanks at medium range, crouches (telegraph), and performs a sudden high-speed pounce leap
                        if (monster.telegraphMs > 0L) {
                            monster.telegraphMs = (monster.telegraphMs - dtMs).coerceAtLeast(0L)
                            monster.animationState = CharacterAnimationState.IDLE
                            monster.scaleSquash = 0.65f // crouching to pounce
                            if (monster.telegraphMs == 0L) {
                                monster.aiState = MonsterAiState.POUNCE
                                val leapSpeed = 12.5f
                                monster.pounceVx = (dx / distToHero) * leapSpeed
                                monster.pounceVy = (dy / distToHero) * leapSpeed
                                monster.specialCooldownMs = 3600L
                                monster.scaleSquash = 1.35f
                                monster.animationState = CharacterAnimationState.ATTACK
                            }
                        } else if (monster.aiState == MonsterAiState.POUNCE) {
                            if (distToHero < 38f) {
                                triggerMonsterMeleeHit(monster, hero, isHeavy = true, bonusDmg = 8)
                                monster.aiState = MonsterAiState.PURSUIT
                            }
                        } else {
                            if (distToHero > 150f || monster.specialCooldownMs > 1200L) {
                                monster.aiState = MonsterAiState.PURSUIT
                                monster.x += (dx / distToHero) * monster.speed * 1.15f * (dtMs / 16.6f) + separationX
                                monster.y += (dy / distToHero) * monster.speed * 1.15f * (dtMs / 16.6f) + separationY
                                monster.animationState = CharacterAnimationState.WALK
                            } else {
                                monster.aiState = MonsterAiState.TELEGRAPH
                                monster.telegraphMs = 350L
                                monster.telegraphMaxMs = 350L
                            }

                            if (distToHero < 34f && monster.attackCooldownMs == 0L) {
                                triggerMonsterMeleeHit(monster, hero, isHeavy = false, bonusDmg = 0)
                                monster.attackCooldownMs = 700L
                            }
                        }
                    }

                    MonsterArchetype.IKENGA_GOLEM -> {
                        // Bronze Automaton: Armored tank, winds up heavy ground slam with glowing telegraph
                        if (monster.telegraphMs > 0L) {
                            monster.telegraphMs = (monster.telegraphMs - dtMs).coerceAtLeast(0L)
                            monster.animationState = CharacterAnimationState.ATTACK
                            if (monster.telegraphMs == 0L) {
                                monster.specialCooldownMs = 4000L
                                monster.aiState = MonsterAiState.PURSUIT
                                val slamRadius = 75f
                                if (distToHero <= slamRadius) {
                                    triggerMonsterMeleeHit(monster, hero, isHeavy = true, bonusDmg = 12)
                                    world.screenShakeIntensity = 15f
                                    world.screenShakeDurationMs = 240L
                                }
                                for (i in 0 until 10) {
                                    val sAngle = Random.nextFloat() * 6.28f
                                    val sSpeed = Random.nextFloat() * 5f + 2f
                                    world.impactSparks.add(
                                        CombatImpactSpark(
                                            x = monster.x,
                                            y = monster.y,
                                            vx = cos(sAngle) * sSpeed,
                                            vy = sin(sAngle) * sSpeed,
                                            colorHex = "#FF8F00",
                                            radius = 3.5f,
                                            maxLifetimeMs = 300L
                                        )
                                    )
                                }
                            }
                        } else {
                            if (distToHero > 50f) {
                                monster.aiState = MonsterAiState.PURSUIT
                                monster.x += (dx / distToHero) * monster.speed * (dtMs / 16.6f) + separationX
                                monster.y += (dy / distToHero) * monster.speed * (dtMs / 16.6f) + separationY
                                monster.animationState = CharacterAnimationState.WALK
                            } else {
                                if (monster.specialCooldownMs == 0L) {
                                    monster.aiState = MonsterAiState.TELEGRAPH
                                    monster.telegraphMs = 550L
                                    monster.telegraphMaxMs = 550L
                                } else if (monster.attackCooldownMs == 0L) {
                                    triggerMonsterMeleeHit(monster, hero, isHeavy = false, bonusDmg = 0)
                                    monster.attackCooldownMs = 900L
                                }
                            }
                        }
                    }

                    MonsterArchetype.ANCIENT_BRONZE_COLOSSUS -> {
                        // Boss Titan: Earthquake Stomps, 4-directional shockwaves, and summon thresholds
                        if (monster.telegraphMs > 0L) {
                            monster.telegraphMs = (monster.telegraphMs - dtMs).coerceAtLeast(0L)
                            monster.animationState = CharacterAnimationState.ATTACK
                            if (monster.telegraphMs == 0L) {
                                monster.specialCooldownMs = 3800L
                                monster.aiState = MonsterAiState.PURSUIT
                                if (distToHero <= 95f) {
                                    triggerMonsterMeleeHit(monster, hero, isHeavy = true, bonusDmg = 18)
                                }
                                world.screenShakeIntensity = 18f
                                world.screenShakeDurationMs = 300L
                                val cardinalAngles = listOf(0f, 1.57f, 3.14f, 4.71f)
                                cardinalAngles.forEach { ang ->
                                    world.projectiles.add(
                                        ActiveProjectile(
                                            startX = monster.x,
                                            startY = monster.y,
                                            currentX = monster.x,
                                            currentY = monster.y,
                                            targetX = monster.x + cos(ang) * 300f,
                                            targetY = monster.y + sin(ang) * 300f,
                                            speed = 3.8f,
                                            damage = monster.attackPower,
                                            damageType = DamageType.PHYSICAL,
                                            radius = 16f,
                                            maxDistance = 350f,
                                            colorHex = "#D4AF37",
                                            effectName = "Titan Quake Wave",
                                            isHostile = true
                                        )
                                    )
                                }
                            }
                        } else {
                            if (distToHero > 70f) {
                                monster.aiState = MonsterAiState.PURSUIT
                                monster.x += (dx / distToHero) * monster.speed * (dtMs / 16.6f) + separationX
                                monster.y += (dy / distToHero) * monster.speed * (dtMs / 16.6f) + separationY
                                monster.animationState = CharacterAnimationState.WALK
                            } else {
                                if (monster.specialCooldownMs == 0L) {
                                    monster.aiState = MonsterAiState.TELEGRAPH
                                    monster.telegraphMs = 650L
                                    monster.telegraphMaxMs = 650L
                                } else if (monster.attackCooldownMs == 0L) {
                                    triggerMonsterMeleeHit(monster, hero, isHeavy = false, bonusDmg = 0)
                                    monster.attackCooldownMs = 1100L
                                }
                            }
                        }
                    }

                    MonsterArchetype.FOREST_MMUO -> {
                        // Swarm Spirit: Flocking encircling slots, sinusoidal floating paths, dart-in flurries
                        val slotAngle = (index * (2 * Math.PI.toFloat() / monsterCount)) + (System.currentTimeMillis() % 10000 / 10000f * 6.28f * 0.1f)
                        val slotDist = 38f
                        val targetSlotX = hero.x + cos(slotAngle) * slotDist
                        val targetSlotY = hero.y + sin(slotAngle) * slotDist

                        val slotDx = targetSlotX - monster.x
                        val slotDy = targetSlotY - monster.y
                        val slotDlen = sqrt(slotDx * slotDx + slotDy * slotDy).coerceAtLeast(0.1f)

                        val waveOffset = sin(System.currentTimeMillis() / 180f + monster.id.hashCode() % 10) * 0.8f
                        val perpX = -sin(monster.facingAngleRad) * waveOffset
                        val perpY = cos(monster.facingAngleRad) * waveOffset

                        if (distToHero > 32f) {
                            monster.aiState = MonsterAiState.PURSUIT
                            monster.x += (slotDx / slotDlen) * monster.speed * (dtMs / 16.6f) + separationX + perpX
                            monster.y += (slotDy / slotDlen) * monster.speed * (dtMs / 16.6f) + separationY + perpY
                            monster.animationState = CharacterAnimationState.WALK
                        } else {
                            if (monster.attackCooldownMs == 0L) {
                                triggerMonsterMeleeHit(monster, hero, isHeavy = false, bonusDmg = 0)
                                monster.attackCooldownMs = 650L
                                monster.knockbackVx = -(dx / distToHero) * 3.5f
                                monster.knockbackVy = -(dy / distToHero) * 3.5f
                            }
                        }
                    }
                }

                // Enforce active playable arena boundary
                val arenaLimit = 640f
                monster.x = monster.x.coerceIn(-arenaLimit, arenaLimit)
                monster.y = monster.y.coerceIn(-arenaLimit, arenaLimit)
            }

            if (monster.isDead) {
                deadMonsters.add(monster)
            }
        }

        // Add summoned minions into combat
        world.monsters.addAll(newSpawns)

        deadMonsters.forEach { dead ->
            world.monsters.remove(dead)
            world.monstersDefeated++
            world.hero.currentExp += dead.expReward
            world.hero.gold += dead.goldReward

            // Level up check
            if (world.hero.currentExp >= world.hero.expToNextLevel) {
                world.hero.level++
                world.hero.currentExp -= world.hero.expToNextLevel
                world.hero.expToNextLevel = (world.hero.expToNextLevel * 1.5f).toInt()
                world.hero.currentHealth = world.hero.maxHealth
                world.hero.currentSpirit = world.hero.maxSpirit
                world.combatTexts.add(
                    FloatingCombatText(
                        text = "LEVEL UP! Lv.${world.hero.level}",
                        x = world.hero.x,
                        y = world.hero.y - 50f,
                        colorHex = "#FFD700",
                        isCrit = true,
                        lifetimeMs = 2000L
                    )
                )
            }

            triggerMechanics(MechanicTriggerType.ON_KILL, dead, 0)

            // Loot drop roll
            val dropChance = if (dead.isBoss) 1.0f else 0.45f
            if (Random.nextFloat() < dropChance) {
                val rolledRarity = when {
                    Random.nextFloat() < 0.08f -> WeaponRarity.ANCIENT_RELIC
                    Random.nextFloat() < 0.22f -> WeaponRarity.LEGENDARY
                    Random.nextFloat() < 0.50f -> WeaponRarity.RARE
                    else -> WeaponRarity.MAGIC
                }
                val droppedWeapon = WeaponStatRandomizer.rollRandomWeapon(
                    targetRarity = rolledRarity,
                    itemLevel = world.hero.level + Random.nextInt(0, 3)
                )
                world.lootDrops.add(
                    ActiveLootDrop(
                        weapon = droppedWeapon,
                        x = dead.x,
                        y = dead.y
                    )
                )
            }
        }
    }

    private fun applyDamageToMonster(
        monster: MonsterEntity,
        damage: Int,
        isCrit: Boolean,
        damageType: DamageType,
        hitAngleRad: Float = 0f
    ) {
        val reducedDmg = (damage - (monster.defense / 2)).coerceAtLeast(1)
        monster.currentHp = (monster.currentHp - reducedDmg).coerceAtLeast(0)
        world.totalDamageDealtInSession += reducedDmg

        // Combat Juice: Hit Flash, Stagger, and Squash
        monster.hitFlashMs = if (isCrit) 180L else 120L
        monster.staggerMs = if (isCrit) 340L else 200L
        monster.scaleSquash = if (isCrit) 0.65f else 0.80f

        // Knockback Physics
        val knockbackForce = if (isCrit) {
            if (monster.isBoss) 4f else 11.5f
        } else {
            if (monster.isBoss) 1.8f else 6.5f
        }
        monster.knockbackVx = cos(hitAngleRad) * knockbackForce
        monster.knockbackVy = sin(hitAngleRad) * knockbackForce

        // Hit Freeze & Screen Shake on Impact
        world.hitFreezeMs = if (isCrit) 60L else 30L
        world.screenShakeIntensity = if (isCrit) 10f else 4.5f
        world.screenShakeDurationMs = if (isCrit) 180L else 100L

        // Spawn Impact Sparks & Blood/Debris
        val sparkCount = if (isCrit) 12 else 6
        for (i in 0 until sparkCount) {
            val sparkAngle = hitAngleRad + (Random.nextFloat() - 0.5f) * 1.6f
            val sparkSpeed = Random.nextFloat() * 7f + 3f
            world.impactSparks.add(
                CombatImpactSpark(
                    x = monster.x,
                    y = monster.y,
                    vx = cos(sparkAngle) * sparkSpeed,
                    vy = sin(sparkAngle) * sparkSpeed,
                    colorHex = if (isCrit) "#FFD700" else damageType.colorHex,
                    radius = if (isCrit) 4.5f else 3f,
                    maxLifetimeMs = Random.nextLong(180, 320)
                )
            )
        }

        world.combatTexts.add(
            FloatingCombatText(
                text = if (isCrit) "$reducedDmg CRIT!" else "$reducedDmg",
                x = monster.x + Random.nextInt(-15, 15),
                y = monster.y - 25f,
                colorHex = if (isCrit) "#FFD54F" else damageType.colorHex,
                isCrit = isCrit
            )
        )
    }

    private fun triggerMechanics(trigger: MechanicTriggerType, target: MonsterEntity?, damage: Int) {
        world.activeMechanics.filter { it.isEnabled && it.triggerType == trigger }.forEach { mechanic ->
            if (Random.nextFloat() < mechanic.procChance) {
                world.combatTexts.add(
                    FloatingCombatText(
                        text = "Proc: ${mechanic.name}",
                        x = target?.x ?: world.hero.x,
                        y = (target?.y ?: world.hero.y) - 45f,
                        colorHex = "#76FF03",
                        lifetimeMs = 1200L
                    )
                )
                when (mechanic.id) {
                    "mech_nsibidi_burst" -> {
                        // Explode around target
                        target?.let {
                            applyDamageToMonster(it, (damage * 0.5f).toInt().coerceAtLeast(15), isCrit = false, DamageType.SACRED_NSIBIDI, hitAngleRad = Random.nextFloat() * 6.28f)
                        }
                    }
                    "mech_ikenga_thorns" -> {
                        // Reflect damage back
                        target?.let {
                            applyDamageToMonster(it, (damage * 0.75f).toInt().coerceAtLeast(10), isCrit = false, DamageType.PHYSICAL, hitAngleRad = Random.nextFloat() * 6.28f)
                        }
                    }
                    "mech_chain_spark" -> {
                        // Chain to another monster
                        world.monsters.filter { it != target }.take(2).forEach { other ->
                            val chainAngle = target?.let { atan2(other.y - it.y, other.x - it.x) } ?: 0f
                            applyDamageToMonster(other, (damage * 0.4f).toInt().coerceAtLeast(12), isCrit = false, DamageType.THUNDER, hitAngleRad = chainAngle)
                        }
                    }
                }
            }
        }
    }

    fun pickUpLoot(dropId: String): WeaponItem? {
        val drop = world.lootDrops.find { it.id == dropId } ?: return null
        world.lootDrops.remove(drop)

        if (drop.goldReward > 0) {
            world.hero.gold += drop.goldReward
            world.combatTexts.add(
                FloatingCombatText(
                    text = "+${drop.goldReward} Gold!",
                    x = world.hero.x,
                    y = world.hero.y - 30f,
                    colorHex = "#FFD700",
                    lifetimeMs = 1500L
                )
            )
            return null
        }

        if (drop.healthReward > 0) {
            world.hero.currentHealth = (world.hero.currentHealth + drop.healthReward).coerceAtMost(world.hero.maxHealth)
            world.combatTexts.add(
                FloatingCombatText(
                    text = "+${drop.healthReward} HP!",
                    x = world.hero.x,
                    y = world.hero.y - 30f,
                    colorHex = "#00E676",
                    lifetimeMs = 1500L
                )
            )
            return null
        }

        world.combatTexts.add(
            FloatingCombatText(
                text = "Acquired: ${drop.weapon.name}",
                x = world.hero.x,
                y = world.hero.y - 30f,
                colorHex = drop.weapon.rarity.colorHex,
                lifetimeMs = 1800L
            )
        )
        return drop.weapon
    }

    fun pickUpAllNearbyLoot(range: Float = 350f): List<WeaponItem> {
        val hero = world.hero
        val nearby = world.lootDrops.filter { drop ->
            val dx = hero.x - drop.x
            val dy = hero.y - drop.y
            sqrt(dx * dx + dy * dy) <= range
        }.toList()

        val acquired = mutableListOf<WeaponItem>()
        for (drop in nearby) {
            val weapon = pickUpLoot(drop.id)
            if (weapon != null) acquired.add(weapon)
        }
        return acquired
    }

    fun resetWholeGame() {
        world.monsters.clear()
        world.projectiles.clear()
        world.lootDrops.clear()
        world.combatTexts.clear()
        world.impactSparks.clear()
        world.currentWave = 1
        world.monstersDefeated = 0
        world.totalDamageDealtInSession = 0L
        world.combatTimerMs = 0L
        val hero = world.hero
        hero.x = 0f
        hero.y = 0f
        hero.level = 1
        hero.currentExp = 0
        hero.expToNextLevel = 100
        hero.gold = 50
        hero.currentHealth = hero.maxHealth
        hero.currentSpirit = hero.maxSpirit
        hero.equippedWeapon = WeaponStatRandomizer.rollRandomWeapon(
            baseType = BaseWeaponType.MMA_NKWU,
            targetRarity = WeaponRarity.RARE,
            itemLevel = 1
        ).copy(isEquipped = true)
        spawnInitialEnemies()
    }

    private fun spawnInitialEnemies() {
        spawnWave(1)
    }

    fun spawnWave(wave: Int) {
        val count = (4 + wave * 2).coerceAtMost(16)
        val archetypes = listOf(
            MonsterArchetype.FOREST_MMUO,
            MonsterArchetype.IKENGA_GOLEM,
            MonsterArchetype.EKPE_LEOPARD_WARRIOR,
            MonsterArchetype.CORRUPTED_DIBIA
        )
        for (i in 0 until count) {
            val angle = (i.toFloat() / count.toFloat()) * 6.28f + (Random.nextFloat() - 0.5f) * 0.4f
            // Screen-space aware spawn radius: Spawns just on/near the viewport perimeter (220 to 360px from hero)
            val radius = Random.nextInt(220, 360).toFloat()
            val arch = archetypes.random()
            val hp = (40 + wave * 18 * Random.nextDouble(0.8, 1.2)).toInt()
            world.monsters.add(
                MonsterEntity(
                    name = arch.title,
                    archetype = arch,
                    x = cos(angle) * radius,
                    y = sin(angle) * radius,
                    maxHp = hp,
                    currentHp = hp,
                    attackPower = 12 + wave * 3,
                    defense = 4 + wave * 2,
                    speed = when (arch) {
                        MonsterArchetype.IKENGA_GOLEM -> 1.15f
                        MonsterArchetype.CORRUPTED_DIBIA -> 1.55f
                        MonsterArchetype.EKPE_LEOPARD_WARRIOR -> 2.1f
                        MonsterArchetype.FOREST_MMUO -> 1.85f
                        else -> 1.5f
                    },
                    targetOffsetAngleRad = angle,
                    specialCooldownMs = Random.nextLong(900L, 2600L),
                    attackCooldownMs = Random.nextLong(250L, 650L)
                )
            )
        }
        if (wave % 3 == 0) {
            // Spawn Boss Titan
            world.monsters.add(
                MonsterEntity(
                    name = "Igbo-Ukwu Bronze Colossus (Boss)",
                    archetype = MonsterArchetype.ANCIENT_BRONZE_COLOSSUS,
                    x = 0f,
                    y = -220f,
                    maxHp = 250 + wave * 80,
                    currentHp = 250 + wave * 80,
                    attackPower = 28 + wave * 5,
                    defense = 18,
                    speed = 1.1f,
                    isBoss = true,
                    goldReward = 150,
                    expReward = 200,
                    specialCooldownMs = 1500L,
                    attackCooldownMs = 800L
                )
            )
            world.combatTexts.add(
                FloatingCombatText(
                    text = "BOSS WAVE! Colossus Awakens!",
                    x = 0f,
                    y = -180f,
                    colorHex = "#FF1744",
                    isCrit = true,
                    lifetimeMs = 2500L
                )
            )
        }
    }

    companion object {
        fun defaultInventedMechanics(): List<CustomGameMechanic> = listOf(
            CustomGameMechanic(
                id = "mech_nsibidi_burst",
                name = "Nsibidi Rune Burst",
                igboName = "Mgbawa Nsibidi",
                description = "On hit, 30% chance to unleash an ancestral glyph explosion dealing 50% extra sacred damage.",
                procChance = 0.30f,
                triggerType = MechanicTriggerType.ON_HIT
            ),
            CustomGameMechanic(
                id = "mech_ikenga_thorns",
                name = "Ikenga Horn Thorns",
                igboName = "Ogwu Ikenga",
                description = "When taking damage, 40% chance to reflect 75% back to the attacker.",
                procChance = 0.40f,
                triggerType = MechanicTriggerType.ON_TAKE_DAMAGE
            ),
            CustomGameMechanic(
                id = "mech_chain_spark",
                name = "Amadioha Arc",
                igboName = "Egbe Igwe",
                description = "On critical strike, 45% chance to arc lightning bolts to two nearby enemies.",
                procChance = 0.45f,
                triggerType = MechanicTriggerType.ON_CRIT
            )
        )
    }
}
