package com.stratum.core.domain.session

/**
 * The player's active character customization choices.
 *
 * Persisted across app restarts so that characters and weapons selected
 * or forged by the player remain active.
 */
data class PlayerLoadout(
    val heroClassId: String? = null,
    val heroSheetId: String? = null,
    val equippedWeaponId: String? = null,
)
