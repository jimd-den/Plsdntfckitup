package com.stratum.legacy.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * CHAPTER 04: DOMAIN MODEL - SPRITE SHEET IMPORT & ANIMATION ENGINE
 *
 * This literate module provides data structures and animation controllers for
 * importing sprite sheets (PNG grid layouts, frame widths, animations) and
 * dynamically binding them to the player avatar or enemies in the ARPG game loop.
 */

enum class CharacterAnimationState {
    IDLE,
    WALK,
    ATTACK,
    CAST_SPELL,
    HURT,
    DEFEAT
}

data class SpriteFrame(
    val frameIndex: Int,
    val column: Int,
    val row: Int,
    val durationMs: Long = 125L // 8 frames per second default
)

data class SpriteAnimationTrack(
    val state: CharacterAnimationState,
    val startFrame: Int,
    val frameCount: Int,
    val loop: Boolean = true,
    val frameDurationMs: Long = 120L
)

enum class SpriteEntityCategory {
    HERO,
    ENEMY
}

data class SpriteSheetData(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val frameWidth: Int = 48,
    val frameHeight: Int = 48,
    val columns: Int = 4,
    val rows: Int = 4,
    val animations: Map<CharacterAnimationState, SpriteAnimationTrack> = defaultAnimationTracks(),
    val isCustomImport: Boolean = false,
    val rawImageUriOrBase64: String? = null,
    val igboThemeRole: String = "Ozo Bronze Warrior",
    val entityCategory: SpriteEntityCategory = SpriteEntityCategory.HERO,
    val enemyArchetypeId: String? = null
) {
    val totalFrames: Int get() = columns * rows

    fun getFrameForTime(state: CharacterAnimationState, elapsedMs: Long): Int {
        val track = animations[state] ?: return 0
        if (track.frameCount <= 1) return track.startFrame
        val totalTrackDuration = track.frameCount * track.frameDurationMs
        val currentMs = if (track.loop) elapsedMs % totalTrackDuration else elapsedMs.coerceAtMost(totalTrackDuration - 1)
        val frameOffset = (currentMs / track.frameDurationMs).toInt().coerceIn(0, track.frameCount - 1)
        return track.startFrame + frameOffset
    }

    /**
     * Serialises the sheet for export and sharing.
     *
     * Uses kotlinx.serialization rather than `org.json`, which is an Android
     * class. The format is unchanged, so sheets exported by earlier builds still
     * import.
     */
    fun toJson(): String {
        val root = buildJsonObject {
            put("id", id)
            put("name", name)
            put("description", description)
            put("frameWidth", frameWidth)
            put("frameHeight", frameHeight)
            put("columns", columns)
            put("rows", rows)
            put("isCustomImport", isCustomImport)
            put("igboThemeRole", igboThemeRole)
            put("entityCategory", entityCategory.name)
            enemyArchetypeId?.let { put("enemyArchetypeId", it) }
            rawImageUriOrBase64?.let { put("rawImageUriOrBase64", it) }
            putJsonArray("animations") {
                animations.forEach { (state, track) ->
                    addJsonObject {
                        put("state", state.name)
                        put("startFrame", track.startFrame)
                        put("frameCount", track.frameCount)
                        put("loop", track.loop)
                        put("frameDurationMs", track.frameDurationMs)
                    }
                }
            }
        }
        return SPRITE_JSON.encodeToString(JsonObject.serializer(), root)
    }

    companion object {
        private val SPRITE_JSON = Json { prettyPrint = true; ignoreUnknownKeys = true }

        /**
         * Reads a sheet back. An imported file is untrusted, so every field
         * falls back to a default and a malformed file returns null rather than
         * throwing into the caller.
         */
        fun fromJson(jsonStr: String): SpriteSheetData? = try {
            val json = Json.parseToJsonElement(jsonStr).jsonObject

            fun str(key: String, fallback: String): String =
                json[key]?.jsonPrimitive?.contentOrNull ?: fallback

            fun strOrNull(key: String): String? = json[key]?.jsonPrimitive?.contentOrNull

            fun int(key: String, fallback: Int): Int =
                json[key]?.jsonPrimitive?.intOrNull ?: fallback

            fun bool(key: String, fallback: Boolean): Boolean =
                json[key]?.jsonPrimitive?.booleanOrNull ?: fallback

            val catStr = str("entityCategory", "HERO")
            val cat = SpriteEntityCategory.entries.firstOrNull { it.name == catStr }
                ?: SpriteEntityCategory.HERO

            val animsMap = mutableMapOf<CharacterAnimationState, SpriteAnimationTrack>()
            json["animations"]?.jsonArray?.forEach { element ->
                val obj = element.jsonObject
                val stName = obj["state"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val st = CharacterAnimationState.entries.firstOrNull { it.name == stName }
                    ?: return@forEach
                animsMap[st] = SpriteAnimationTrack(
                    state = st,
                    startFrame = obj["startFrame"]?.jsonPrimitive?.intOrNull ?: 0,
                    frameCount = obj["frameCount"]?.jsonPrimitive?.intOrNull ?: 4,
                    loop = obj["loop"]?.jsonPrimitive?.booleanOrNull ?: true,
                    frameDurationMs = obj["frameDurationMs"]?.jsonPrimitive?.longOrNull ?: 120L,
                )
            }

            SpriteSheetData(
                id = str("id", java.util.UUID.randomUUID().toString()),
                name = str("name", "Custom Sprite"),
                description = str("description", "Imported Sprite Sheet"),
                frameWidth = int("frameWidth", 48),
                frameHeight = int("frameHeight", 48),
                columns = int("columns", 4),
                rows = int("rows", 4),
                animations = animsMap.ifEmpty { defaultAnimationTracks() },
                isCustomImport = bool("isCustomImport", true),
                rawImageUriOrBase64 = strOrNull("rawImageUriOrBase64"),
                igboThemeRole = str("igboThemeRole", "Imported Hero"),
                entityCategory = cat,
                enemyArchetypeId = strOrNull("enemyArchetypeId"),
            )
        } catch (e: Exception) {
            null
        }

        fun defaultAnimationTracks(): Map<CharacterAnimationState, SpriteAnimationTrack> {
            return mapOf(
                CharacterAnimationState.IDLE to SpriteAnimationTrack(CharacterAnimationState.IDLE, startFrame = 0, frameCount = 4, loop = true, frameDurationMs = 180L),
                CharacterAnimationState.WALK to SpriteAnimationTrack(CharacterAnimationState.WALK, startFrame = 4, frameCount = 4, loop = true, frameDurationMs = 120L),
                CharacterAnimationState.ATTACK to SpriteAnimationTrack(CharacterAnimationState.ATTACK, startFrame = 8, frameCount = 4, loop = false, frameDurationMs = 90L),
                CharacterAnimationState.CAST_SPELL to SpriteAnimationTrack(CharacterAnimationState.CAST_SPELL, startFrame = 12, frameCount = 4, loop = false, frameDurationMs = 110L),
                CharacterAnimationState.HURT to SpriteAnimationTrack(CharacterAnimationState.HURT, startFrame = 14, frameCount = 2, loop = false, frameDurationMs = 100L),
                CharacterAnimationState.DEFEAT to SpriteAnimationTrack(CharacterAnimationState.DEFEAT, startFrame = 15, frameCount = 1, loop = false, frameDurationMs = 200L)
            )
        }
    }
}

object PreloadedSpritePacks {

    val IGBO_OZO_WARRIOR = SpriteSheetData(
        id = "sprite_ozo_warrior",
        name = "Dike Ozo (Bronze Hero)",
        description = "Royal Igbo titleholder adorned in bronze pectoral plates, roped armlets, and wielding the sacred bronze sword.",
        frameWidth = 48,
        frameHeight = 48,
        columns = 4,
        rows = 4,
        isCustomImport = false,
        igboThemeRole = "Dike Ozo Warrior",
        entityCategory = SpriteEntityCategory.HERO
    )

    val IKENGA_BRONZE_GOLEM = SpriteSheetData(
        id = "sprite_ikenga_golem",
        name = "Ikenga Horned Automaton",
        description = "Ancient guardian statue sculpted from 9th-century Igbo-Ukwu roped bronze, charged with ancestral lightning.",
        frameWidth = 48,
        frameHeight = 48,
        columns = 4,
        rows = 4,
        isCustomImport = false,
        igboThemeRole = "Ikenga Golem",
        entityCategory = SpriteEntityCategory.HERO
    )

    val MMANWU_SPIRIT = SpriteSheetData(
        id = "sprite_mmanwu_spirit",
        name = "Mmanwu Masked Spirit",
        description = "Otherworldly entity from the ancestral grove cloaked in vivid raffia and intricate geometric Uli symbols.",
        frameWidth = 48,
        frameHeight = 48,
        columns = 4,
        rows = 4,
        isCustomImport = false,
        igboThemeRole = "Mmanwu Spirit",
        entityCategory = SpriteEntityCategory.HERO
    )

    // Preloaded Enemy Sprite Packs with authentic pixel animations
    val ENEMY_FOREST_MMO = SpriteSheetData(
        id = "sprite_enemy_forest_mmo",
        name = "Forest Mmo (Masked Ghoul)",
        description = "Wandering ancestral woodland spirit cloaked in woven raffia and green ghostfire.",
        frameWidth = 48,
        frameHeight = 48,
        columns = 4,
        rows = 4,
        isCustomImport = false,
        igboThemeRole = "Forest Spirit",
        entityCategory = SpriteEntityCategory.ENEMY,
        enemyArchetypeId = "arch_forest_mmo"
    )

    val ENEMY_OGU_BRUTE = SpriteSheetData(
        id = "sprite_enemy_ogu_brute",
        name = "Ogu Bronze Behemoth",
        description = "Massive armored warrior from the iron clans brandishing twin spiked bronze bludgeons.",
        frameWidth = 48,
        frameHeight = 48,
        columns = 4,
        rows = 4,
        isCustomImport = false,
        igboThemeRole = "Bronze Brute",
        entityCategory = SpriteEntityCategory.ENEMY,
        enemyArchetypeId = "arch_ogu_brute"
    )

    val ENEMY_LEOPARD_ASSASSIN = SpriteSheetData(
        id = "sprite_enemy_leopard_stalker",
        name = "Agu Shadow Leopard",
        description = "Sacred leopard hunter blessed by Idemili with shadowstep swiftness and razor claws.",
        frameWidth = 48,
        frameHeight = 48,
        columns = 4,
        rows = 4,
        isCustomImport = false,
        igboThemeRole = "Shadow Leopard",
        entityCategory = SpriteEntityCategory.ENEMY,
        enemyArchetypeId = "arch_shadow_leopard"
    )

    val ENEMY_AGBARA_HIGH_PRIEST = SpriteSheetData(
        id = "sprite_enemy_agbara_boss",
        name = "Agbara High Priest (Boss)",
        description = "Ancient ceremonial high priest channeling thunderbolts and solar pyres from the shrine altar.",
        frameWidth = 48,
        frameHeight = 48,
        columns = 4,
        rows = 4,
        isCustomImport = false,
        igboThemeRole = "Agbara High Priest",
        entityCategory = SpriteEntityCategory.ENEMY,
        enemyArchetypeId = "arch_boss_priest"
    )

    val ALL_PRELOADED = listOf(
        IGBO_OZO_WARRIOR,
        IKENGA_BRONZE_GOLEM,
        MMANWU_SPIRIT,
        ENEMY_FOREST_MMO,
        ENEMY_OGU_BRUTE,
        ENEMY_LEOPARD_ASSASSIN,
        ENEMY_AGBARA_HIGH_PRIEST
    )
}
