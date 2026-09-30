package com.stratum.core.domain.character

import com.stratum.core.domain.sprite.SpriteNamespace

/**
 * What a character is for in the world: a hero the player can play as, or a
 * monster that populates the regions.
 *
 * The role decides the namespace in the set id, ensuring art is filed where
 * the world looks for each kind of actor.
 */
enum class CharacterRole(val label: String, val namespace: String) {
    HERO("Hero", SpriteNamespace.HERO),
    ENEMY("Enemy", SpriteNamespace.MONSTER);

    companion object {
        fun fromSetId(setId: String): CharacterRole =
            if (SpriteNamespace.servesMonster(setId)) ENEMY else HERO
    }
}
