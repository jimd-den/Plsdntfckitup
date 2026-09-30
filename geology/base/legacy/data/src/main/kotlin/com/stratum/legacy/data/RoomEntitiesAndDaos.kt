package com.stratum.legacy.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * CHAPTER 08: DATA ROOM ENTITIES & DAOS
 *
 * Persists player inventories, forged ARPG weapons, imported custom sprite sheets,
 * and pen-and-paper rulebooks locally using SQLite via Room.
 */

@Entity(tableName = "weapons")
data class WeaponEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseType: String,
    val rarity: String,
    val itemLevel: Int,
    val minDamage: Int,
    val maxDamage: Int,
    val attackSpeed: Float,
    val critBonusChance: Float,
    val critDamageBonus: Float,
    val primaryDamageType: String,
    val lifeLeechPercent: Float,
    val specialMechanic: String,
    val nsibidiRuneSymbol: String?,
    val nsibidiRuneName: String?,
    val isEquipped: Boolean,
    val loreNotes: String
)

@Dao
interface WeaponDao {
    @Query("SELECT * FROM weapons ORDER BY itemLevel DESC, name ASC")
    fun observeAllWeapons(): Flow<List<WeaponEntity>>

    @Query("SELECT * FROM weapons ORDER BY itemLevel DESC, name ASC")
    suspend fun getAllWeapons(): List<WeaponEntity>

    @Query("SELECT * FROM weapons WHERE id = :id LIMIT 1")
    suspend fun getWeaponById(id: String): WeaponEntity?

    @Query("SELECT * FROM weapons WHERE isEquipped = 1 LIMIT 1")
    suspend fun getEquippedWeapon(): WeaponEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWeapon(weapon: WeaponEntity)

    @Query("UPDATE weapons SET isEquipped = 0")
    suspend fun unequipAll()

    @Query("UPDATE weapons SET isEquipped = 1 WHERE id = :id")
    suspend fun setEquipped(id: String)

    @Query("DELETE FROM weapons WHERE id = :id")
    suspend fun deleteWeapon(id: String)
}

@Entity(tableName = "sprite_sheets")
data class SpriteSheetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val columns: Int,
    val rows: Int,
    val isCustomImport: Boolean,
    val rawImageUriOrBase64: String?,
    val igboThemeRole: String
)

@Dao
interface SpriteSheetDao {
    @Query("SELECT * FROM sprite_sheets")
    fun observeAllSpriteSheets(): Flow<List<SpriteSheetEntity>>

    @Query("SELECT * FROM sprite_sheets")
    suspend fun getAllSpriteSheets(): List<SpriteSheetEntity>

    @Query("SELECT * FROM sprite_sheets WHERE id = :id LIMIT 1")
    suspend fun getSpriteSheetById(id: String): SpriteSheetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpriteSheet(sheet: SpriteSheetEntity)
}

@Entity(tableName = "pnp_rulebooks")
data class PnpRulebookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val settingName: String,
    val authorNotes: String,
    val systemVersion: String,
    val rawMarkdownNotes: String
)

@Dao
interface PnpRulebookDao {
    @Query("SELECT * FROM pnp_rulebooks")
    fun observeAllRulebooks(): Flow<List<PnpRulebookEntity>>

    @Query("SELECT * FROM pnp_rulebooks")
    suspend fun getAllRulebooks(): List<PnpRulebookEntity>

    @Query("SELECT * FROM pnp_rulebooks WHERE id = :id LIMIT 1")
    suspend fun getRulebookById(id: String): PnpRulebookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRulebook(rulebook: PnpRulebookEntity)
}
