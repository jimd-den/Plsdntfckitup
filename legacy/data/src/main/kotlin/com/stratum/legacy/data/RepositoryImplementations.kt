package com.stratum.legacy.data

import com.stratum.legacy.domain.BaseWeaponType
import com.stratum.legacy.domain.DamageType
import com.stratum.legacy.domain.NsibidiRune
import com.stratum.legacy.domain.PnpRuleRepository
import com.stratum.legacy.domain.PnpRulebook
import com.stratum.legacy.domain.PnpRulebookFactory
import com.stratum.legacy.domain.PreloadedSpritePacks
import com.stratum.legacy.domain.SpriteSheetData
import com.stratum.legacy.domain.SpriteSheetRepository
import com.stratum.legacy.domain.WeaponAffix
import com.stratum.legacy.domain.WeaponItem
import com.stratum.legacy.domain.WeaponRarity
import com.stratum.legacy.domain.WeaponRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * CHAPTER 11: DATA REPOSITORY IMPLEMENTATIONS
 *
 * Implements Domain Repository contracts by bridging Room DAOs and in-memory caches.
 */

class WeaponRepositoryImpl(
    private val weaponDao: WeaponDao
) : WeaponRepository {

    override fun observeAllWeapons(): Flow<List<WeaponItem>> {
        return weaponDao.observeAllWeapons().map { list -> list.map { it.toDomain() } }
    }

    override suspend fun getAllWeapons(): List<WeaponItem> {
        return weaponDao.getAllWeapons().map { it.toDomain() }
    }

    override suspend fun getWeaponById(id: String): WeaponItem? {
        return weaponDao.getWeaponById(id)?.toDomain()
    }

    override suspend fun saveWeapon(weapon: WeaponItem) {
        weaponDao.insertWeapon(weapon.toEntity())
    }

    override suspend fun deleteWeapon(id: String) {
        weaponDao.deleteWeapon(id)
    }

    override suspend fun equipWeapon(id: String) {
        weaponDao.unequipAll()
        weaponDao.setEquipped(id)
    }

    override suspend fun getEquippedWeapon(): WeaponItem? {
        return weaponDao.getEquippedWeapon()?.toDomain()
    }

    private fun WeaponEntity.toDomain(): WeaponItem {
        val base = try {
            BaseWeaponType.valueOf(baseType)
        } catch (e: Throwable) {
            BaseWeaponType.MMA_NKWU
        }
        val rar = try {
            WeaponRarity.valueOf(rarity)
        } catch (e: Throwable) {
            WeaponRarity.RARE
        }
        val dmgType = try {
            DamageType.valueOf(primaryDamageType)
        } catch (e: Throwable) {
            DamageType.PHYSICAL
        }

        val rune = if (nsibidiRuneSymbol != null && nsibidiRuneName != null) {
            NsibidiRune(nsibidiRuneSymbol, nsibidiRuneName, "Sacred Glyph", "+15 Bonus")
        } else null

        return WeaponItem(
            id = id,
            name = name,
            baseType = base,
            rarity = rar,
            itemLevel = itemLevel,
            minDamage = minDamage,
            maxDamage = maxDamage,
            attackSpeed = attackSpeed,
            critBonusChance = critBonusChance,
            critDamageBonus = critDamageBonus,
            primaryDamageType = dmgType,
            lifeLeechPercent = lifeLeechPercent,
            affixes = listOf(
                WeaponAffix("Masterwork", "Power", 15f, false, "Consecrated bronze.")
            ),
            specialMechanic = specialMechanic,
            nsibidiRune = rune,
            isEquipped = isEquipped,
            loreNotes = loreNotes
        )
    }

    private fun WeaponItem.toEntity(): WeaponEntity {
        return WeaponEntity(
            id = id,
            name = name,
            baseType = baseType.name,
            rarity = rarity.name,
            itemLevel = itemLevel,
            minDamage = minDamage,
            maxDamage = maxDamage,
            attackSpeed = attackSpeed,
            critBonusChance = critBonusChance,
            critDamageBonus = critDamageBonus,
            primaryDamageType = primaryDamageType.name,
            lifeLeechPercent = lifeLeechPercent,
            specialMechanic = specialMechanic,
            nsibidiRuneSymbol = nsibidiRune?.symbolCharacter,
            nsibidiRuneName = nsibidiRune?.igboName,
            isEquipped = isEquipped,
            loreNotes = loreNotes
        )
    }
}

class SpriteSheetRepositoryImpl(
    private val spriteSheetDao: SpriteSheetDao
) : SpriteSheetRepository {

    private var activeId: String = PreloadedSpritePacks.IGBO_OZO_WARRIOR.id

    override fun observeAllSpriteSheets(): Flow<List<SpriteSheetData>> {
        return spriteSheetDao.observeAllSpriteSheets().map { list ->
            val domainList = list.map { it.toDomain() }.toMutableList()
            // Ensure preloaded packs are present
            if (domainList.none { it.id == PreloadedSpritePacks.IGBO_OZO_WARRIOR.id }) {
                domainList.add(0, PreloadedSpritePacks.IGBO_OZO_WARRIOR)
                domainList.add(1, PreloadedSpritePacks.IKENGA_BRONZE_GOLEM)
                domainList.add(2, PreloadedSpritePacks.MMANWU_SPIRIT)
            }
            domainList
        }
    }

    override suspend fun getAllSpriteSheets(): List<SpriteSheetData> {
        val list = spriteSheetDao.getAllSpriteSheets().map { it.toDomain() }.toMutableList()
        if (list.none { it.id == PreloadedSpritePacks.IGBO_OZO_WARRIOR.id }) {
            list.add(0, PreloadedSpritePacks.IGBO_OZO_WARRIOR)
            list.add(1, PreloadedSpritePacks.IKENGA_BRONZE_GOLEM)
            list.add(2, PreloadedSpritePacks.MMANWU_SPIRIT)
        }
        return list
    }

    override suspend fun getSpriteSheetById(id: String): SpriteSheetData? {
        if (id == PreloadedSpritePacks.IGBO_OZO_WARRIOR.id) return PreloadedSpritePacks.IGBO_OZO_WARRIOR
        if (id == PreloadedSpritePacks.IKENGA_BRONZE_GOLEM.id) return PreloadedSpritePacks.IKENGA_BRONZE_GOLEM
        if (id == PreloadedSpritePacks.MMANWU_SPIRIT.id) return PreloadedSpritePacks.MMANWU_SPIRIT
        return spriteSheetDao.getSpriteSheetById(id)?.toDomain()
    }

    override suspend fun saveSpriteSheet(spriteSheet: SpriteSheetData) {
        spriteSheetDao.insertSpriteSheet(spriteSheet.toEntity())
    }

    override suspend fun getActiveSpriteSheetId(): String = activeId

    override suspend fun setActiveSpriteSheetId(id: String) {
        activeId = id
    }

    private fun SpriteSheetEntity.toDomain(): SpriteSheetData {
        return SpriteSheetData(
            id = id,
            name = name,
            description = description,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            columns = columns,
            rows = rows,
            isCustomImport = isCustomImport,
            rawImageUriOrBase64 = rawImageUriOrBase64,
            igboThemeRole = igboThemeRole
        )
    }

    private fun SpriteSheetData.toEntity(): SpriteSheetEntity {
        return SpriteSheetEntity(
            id = id,
            name = name,
            description = description,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            columns = columns,
            rows = rows,
            isCustomImport = isCustomImport,
            rawImageUriOrBase64 = rawImageUriOrBase64,
            igboThemeRole = igboThemeRole
        )
    }
}

class PnpRuleRepositoryImpl(
    private val pnpRulebookDao: PnpRulebookDao
) : PnpRuleRepository {

    private val defaultRulebook = PnpRulebookFactory.createDefaultIgboRulebook()

    override fun observeAllRulebooks(): Flow<List<PnpRulebook>> {
        return pnpRulebookDao.observeAllRulebooks().map { list ->
            val result = list.map { it.toDomain() }.toMutableList()
            if (result.none { it.id == defaultRulebook.id }) {
                result.add(0, defaultRulebook)
            }
            result
        }
    }

    override suspend fun getAllRulebooks(): List<PnpRulebook> {
        val list = pnpRulebookDao.getAllRulebooks().map { it.toDomain() }.toMutableList()
        if (list.none { it.id == defaultRulebook.id }) {
            list.add(0, defaultRulebook)
        }
        return list
    }

    override suspend fun getRulebookById(id: String): PnpRulebook? {
        if (id == defaultRulebook.id) return defaultRulebook
        return pnpRulebookDao.getRulebookById(id)?.toDomain()
    }

    override suspend fun saveRulebook(rulebook: PnpRulebook) {
        pnpRulebookDao.insertRulebook(rulebook.toEntity())
    }

    override suspend fun getActiveRulebook(): PnpRulebook {
        return defaultRulebook
    }

    private fun PnpRulebookEntity.toDomain(): PnpRulebook {
        val template = PnpRulebookFactory.createDefaultIgboRulebook()
        return PnpRulebook(
            id = id,
            title = title,
            settingName = settingName,
            authorNotes = authorNotes,
            systemVersion = systemVersion,
            classes = template.classes,
            skillChecks = template.skillChecks,
            rawMarkdownNotes = rawMarkdownNotes
        )
    }

    private fun PnpRulebook.toEntity(): PnpRulebookEntity {
        return PnpRulebookEntity(
            id = id,
            title = title,
            settingName = settingName,
            authorNotes = authorNotes,
            systemVersion = systemVersion,
            rawMarkdownNotes = rawMarkdownNotes
        )
    }
}
