package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.faction.FactionDefinition
import com.stratum.core.domain.faction.Stance
import com.stratum.core.domain.quest.QuestStatus
import com.stratum.core.domain.settings.GameSettings
import com.stratum.core.domain.settlement.BuildingRole
import com.stratum.core.domain.settlement.BuildingTemplate
import com.stratum.core.domain.settlement.SettlementRecipe
import com.stratum.core.domain.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Friendly towns with people in them, safe streets, and quests from those people. */
class TownQuestSessionTest {

    private val folk = FactionDefinition("t:folk", "Folk", startingStanding = 150)
    private val house = BuildingTemplate("t:house", "House", BuildingRole.HOUSE, 5, 5, 3, TestContent.stone.id, roofBlockId = TestContent.stone.id, minCount = 4)
    private val smithy = BuildingTemplate("t:smithy", "Smithy", BuildingRole.SMITHY, 5, 5, 3, TestContent.stone.id, roofBlockId = TestContent.stone.id, maxCount = 1)
    private val tavern = BuildingTemplate("t:tavern", "Tavern", BuildingRole.TAVERN, 5, 5, 3, TestContent.stone.id, roofBlockId = TestContent.stone.id, maxCount = 1)
    private val village = SettlementRecipe(
        id = "t:village", name = "Village", layoutId = SettlementRecipe.ORGANIC, factionId = folk.id, minRadius = 22, maxRadius = 24, chance = 1f,
        roadBlockId = TestContent.stone.id, foundationBlockId = TestContent.soil.id, groundBlockId = TestContent.soil.id,
        buildings = listOf(house, smithy, tavern),
    )
    private val wolf = EnemyDefinition(
        "t:wolf", "Wolf", baseStats = CombatStats(maxHealth = 30, attackPower = 4, attackSpeed = 1f, attackRange = 1),
        damageTypeId = TestContent.physical.id, spawnWeight = 100,
    )

    private fun session(settings: GameSettings = GameSettings()): WorldSession {
        val pack = TestContent.pack.copy(factions = listOf(folk), enemies = TestContent.enemies + wolf, settlements = listOf(village))
        return WorldSession(ContentPackAssembler().assemble(listOf(pack)), WorldConfig(seed = 4L, simulationRadius = 2), settings = settings)
    }

    @Test
    fun `a friendly town is peopled by its quest givers, who never fight and cannot be struck`() {
        val session = session()
        session.tick(0.05f)
        val town = assertNotNull(session.currentTown, "the player starts in the village")
        val folkHere = session.enemies.filter { it.civilian }
        assertTrue(folkHere.size >= 2, "nobody lives here")
        val names = session.townsfolk.map { it.name }
        assertTrue(folkHere.all { e -> names.any { e.name.startsWith(it) } }, "the people walking about are not the quest givers")
        assertTrue(folkHere.all { it.squadId == "civ:${town.id}" && it.home != null })

        val before = session.player.health
        repeat(40) { session.tick(0.1f) }
        assertEquals(before, session.player.health, "a townsperson struck the player")
        val healthBefore = session.enemies.filter { it.civilian }.associate { it.instanceId to it.health }
        repeat(5) { session.attack() }
        session.enemies.filter { it.civilian }.forEach { assertEquals(healthBefore[it.instanceId], it.health, "the player struck ${it.name}") }
    }

    @Test
    fun `townsfolk keep a day, home at night and about town by day`() {
        val session = session()
        session.tick(0.05f)
        val town = session.currentTown!!
        val life = TownLife(GameSettings(), 4L) { x, y -> com.stratum.core.domain.world.WorldPoint(x + 0.5f, y + 0.5f, 10f) }
        val people = life.residentsOf(town)
        val night = people.mapIndexed { i, p -> life.scheduleFor(town, p, i, 0.8f) }
        val noon = people.mapIndexed { i, p -> life.scheduleFor(town, p, i, 0.32f) }
        assertTrue(night.zip(noon).count { (a, b) -> a != b } >= people.size / 2, "nobody goes anywhere during the day")
        val square = com.stratum.core.domain.world.WorldPoint(town.centerX + 0.5f, town.centerY + 0.5f, 10f)
        val nearSquare = noon.count { it != null && it.horizontalDistanceTo(square) < 8f }
        assertTrue(nearSquare >= people.size / 3, "at midday the town gathers in the square: $nearSquare of ${people.size}")
    }

    @Test
    fun `hostiles that follow the player into a safe town turn back`() {
        val session = session()
        session.tick(0.05f)
        val inside = session.spawn(wolf, session.player.position.translated(2f, 0f, 0f))
        session.tick(0.05f)
        assertTrue(session.enemies.none { it.instanceId == inside.instanceId }, "the wolf walked into a safe town")

        val unsafe = session(GameSettings(safeTowns = false))
        unsafe.tick(0.05f)
        val brave = unsafe.spawn(wolf, unsafe.player.position.translated(2f, 0f, 0f))
        unsafe.tick(0.05f)
        assertTrue(unsafe.enemies.any { it.instanceId == brave.instanceId }, "with safe towns off the wolf stays")
    }

    @Test
    fun `the board offers quests from the town's people, and kills count toward a taken one`() {
        // Safe towns off: the quarry is brought to the player in the square rather than turned back at the wall.
        val session = session(GameSettings(questsPerTown = 6, safeTowns = false))
        session.tick(0.05f)
        val board = session.questBoard()
        assertTrue(board.size >= 3, "board: ${board.map { it.title }}")
        assertTrue(board.all { q -> session.townsfolk.any { it.id == q.giver.id } })
        // Counted against a plain slaying anywhere: which kills each twist counts is the domain tests' job.
        val base = board.firstOrNull { it.objective.kind == com.stratum.core.domain.quest.ObjectiveKind.SLAY } ?: board.first()
        val quest = base.copy(
            objective = com.stratum.core.domain.quest.QuestObjective(com.stratum.core.domain.quest.ObjectiveKind.SLAY, wolf.id, "wolves", count = 3),
            twist = com.stratum.core.domain.quest.Twist.NONE, constraint = com.stratum.core.domain.quest.Constraint.NONE, timeLimit = 0f,
        )
        assertIs<QuestResult.Accepted>(session.acceptQuest(base.id))
        assertTrue(session.questBoard().none { it.id == base.id }, "a taken quest stays on the board")
        // Swap in the plain slaying for the counting below.
        session.abandonQuest(base.id)
        assertIs<QuestResult.Accepted>(session.acceptQuestDirectly(quest))
        val target = session.content.enemies.first { it.id == quest.objective.targetId }
        val need = session.quests.single().required
        repeat(need) {
            val prey = session.spawn(target, session.player.position.translated(1f, 0f, 0f))
            session.enemies = session.enemies.map { if (it.instanceId == prey.instanceId) it.copy(health = 1) else it }
            // Swing as soon as the weapon is ready.
            var report = session.attack()
            var waited = 0
            while (report is AttackReport.NotReady && waited++ < 60) { session.tick(0.05f); report = session.attack() }
            session.tick(0.05f)
            assertTrue(session.enemies.none { it.instanceId == prey.instanceId }, "the wolf did not die: $report; near: ${session.enemies.filter { it.position.horizontalDistanceTo(session.player.position) < 3f }.map { it.name + "@" + it.position.horizontalDistanceTo(session.player.position) + " civ=" + it.civilian }}")
        }
        val active = session.quests.single()
        assertTrue(active.progress == need || active.status == QuestStatus.READY || active.status == QuestStatus.FAILED, "kills did not count: ${active.progress} of $need, ${active.status}")
    }
}
