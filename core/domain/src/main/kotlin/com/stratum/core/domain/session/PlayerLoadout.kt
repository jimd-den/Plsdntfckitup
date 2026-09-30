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
    /**
     * The mask the hero wears, as a mask genome code, chosen in the model
     * studio with "Wear as your mask". Null wears the first preset.
     */
    val heroMask: String? = null,
    /** Characters drawn as floating mask spirits (true) or as the sprite art (false). */
    val maskCharacters: Boolean = true,
)
