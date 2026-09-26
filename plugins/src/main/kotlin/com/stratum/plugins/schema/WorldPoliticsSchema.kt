package com.stratum.plugins.schema

import com.stratum.core.domain.actor.EnemyPackDefinition
import com.stratum.core.domain.actor.PackMember
import com.stratum.core.domain.faction.FactionDefinition
import com.stratum.core.domain.faction.ReputationRank
import com.stratum.core.domain.faction.Stance
import com.stratum.core.domain.settlement.BuildingRole
import com.stratum.core.domain.settlement.BuildingTemplate
import com.stratum.core.domain.settlement.SettlementRecipe
import kotlinx.serialization.Serializable

private val FACTION = FactionDefinition(id = "", name = "")

@Serializable
internal data class RankSchema(val name: String, val threshold: Int, val modifiers: List<ModifierSchema> = emptyList()) {
    fun toDomain(owner: String) = ReputationRank(name, threshold, modifiers.map { it.toDomain("$owner rank '$name'") })

    companion object {
        fun of(r: ReputationRank) = RankSchema(r.name, r.threshold, r.modifiers.map(ModifierSchema::of))
    }
}

/** A side in the world: `relations` maps other faction ids to `hostile`, `neutral` or `allied`. */
@Serializable
internal data class FactionSchema(
    val id: String,
    val name: String,
    val description: String = "",
    val color: String = SchemaValues.color(FACTION.color),
    val glyph: String = FACTION.glyph,
    val relations: Map<String, String> = emptyMap(),
    val defaultStance: String = SchemaValues.name(FACTION.defaultStance),
    val startingStanding: Int = 0,
    val ranks: List<RankSchema> = emptyList(),
) {
    fun toDomain() = FactionDefinition(
        id, name, description, SchemaValues.color(color, "faction '$id' color"), glyph,
        relations.mapValues { (other, stance) -> SchemaValues.enum<Stance>(stance, "faction '$id' stance toward '$other'") },
        SchemaValues.enum<Stance>(defaultStance, "faction '$id' defaultStance"), startingStanding, ranks.map { it.toDomain("faction '$id'") },
    )

    companion object {
        fun of(f: FactionDefinition) = FactionSchema(
            f.id, f.name, f.description, SchemaValues.color(f.color), f.glyph, f.relations.mapValues { SchemaValues.name(it.value) },
            SchemaValues.name(f.defaultStance), f.startingStanding, f.ranks.map(RankSchema::of),
        )
    }
}

@Serializable
internal data class MemberSchema(val enemy: String, val count: Int = 1) {
    fun toDomain() = PackMember(enemy, count)

    companion object {
        fun of(m: PackMember) = MemberSchema(m.enemyId, m.count)
    }
}

@Serializable
internal data class EnemyPackSchema(
    val id: String,
    val name: String,
    val leader: String? = null,
    val members: List<MemberSchema> = emptyList(),
    val spawnBiomes: List<String> = emptyList(),
    val weight: Int = 100,
) {
    fun toDomain() = EnemyPackDefinition(id, name, leader, members.map { it.toDomain() }, spawnBiomes, weight)

    companion object {
        fun of(p: EnemyPackDefinition) = EnemyPackSchema(p.id, p.name, p.leaderId, p.members.map(MemberSchema::of), p.spawnBiomeIds, p.weight)
    }
}

private val BUILDING = BuildingTemplate(id = "", name = "", wallBlockId = "")

@Serializable
internal data class BuildingSchema(
    val id: String,
    val name: String,
    val role: String = SchemaValues.name(BUILDING.role),
    val width: Int = BUILDING.width,
    val depth: Int = BUILDING.depth,
    val height: Int = BUILDING.height,
    val wall: String,
    val floor: String? = null,
    val roof: String? = null,
    val window: String? = null,
    val furniture: String? = null,
    val weight: Int = BUILDING.weight,
    val minCount: Int = 0,
    val maxCount: Int = Int.MAX_VALUE,
) {
    fun toDomain() = BuildingTemplate(
        id, name, SchemaValues.enum<BuildingRole>(role, "building '$id' role"), width, depth, height, wall, floor, roof, window, furniture, weight, minCount, maxCount,
    )

    companion object {
        fun of(b: BuildingTemplate) = BuildingSchema(
            b.id, b.name, SchemaValues.name(b.role), b.width, b.depth, b.height, b.wallBlockId, b.floorBlockId, b.roofBlockId, b.windowBlockId, b.furnitureBlockId,
            b.weight, b.minCount, b.maxCount,
        )
    }
}

private val TOWN = SettlementRecipe(id = "", name = "", roadBlockId = "", foundationBlockId = "")

/** A kind of town: what it is built of and which layout arranges it. */
@Serializable
internal data class SettlementSchema(
    val id: String,
    val name: String,
    val layout: String = TOWN.layoutId,
    val faction: String? = null,
    val biomes: List<String> = emptyList(),
    val minRadius: Int = TOWN.minRadius,
    val maxRadius: Int = TOWN.maxRadius,
    val chance: Float = TOWN.chance,
    val road: String,
    val ground: String? = null,
    val foundation: String,
    val wall: String? = null,
    val wallHeight: Int = TOWN.wallHeight,
    val buildings: List<BuildingSchema> = emptyList(),
    val names: List<String> = emptyList(),
    val garrison: List<MemberSchema> = emptyList(),
    val weight: Int = TOWN.weight,
) {
    fun toDomain() = SettlementRecipe(
        id, name, layout, faction, biomes, minRadius, maxRadius, chance, road, ground, foundation, wall, wallHeight,
        buildings.map { it.toDomain() }, names, garrison.map { it.toDomain() }, weight,
    )

    companion object {
        fun of(s: SettlementRecipe) = SettlementSchema(
            s.id, s.name, s.layoutId, s.factionId, s.biomeIds, s.minRadius, s.maxRadius, s.chance, s.roadBlockId, s.groundBlockId, s.foundationBlockId,
            s.wallBlockId, s.wallHeight, s.buildings.map(BuildingSchema::of), s.names, s.garrison.map(MemberSchema::of), s.weight,
        )
    }
}
