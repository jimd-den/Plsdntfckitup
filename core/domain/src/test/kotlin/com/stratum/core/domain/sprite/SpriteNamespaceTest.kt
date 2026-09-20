package com.stratum.core.domain.sprite

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpriteNamespaceTest {

    @Test
    fun `art drawn for an enemy is still a character`() {
        // What the main screen's list is built on. A character filed as an
        // enemy is still something a person drew and may want to play as, and
        // the picker refusing to show it would be the app arguing with them
        // about their own character. The namespace decides what the *world*
        // does by default, not what a person is allowed to choose.
        assertTrue(SpriteNamespace.isCharacter("monster:bronze_warrior"))
        assertTrue(SpriteNamespace.isCharacter("hero:bronze_warrior"))
        assertTrue(SpriteNamespace.isCharacter("pose:bronze_warrior"))
    }

    @Test
    fun `an enemy sheet is not offered to the player by accident`() {
        // The distinction that still matters: falling back to whatever art
        // exists must not put the player in a monster's body, and must not
        // put every monster in the player's. Only an outright choice does
        // that, and it goes through a different path.
        assertFalse(SpriteNamespace.servesHero("monster:ghoul"))
        assertFalse(SpriteNamespace.servesMonster("hero:warrior"))
    }

    @Test
    fun `characters drawn before the choice existed serve as heroes`() {
        // Being wrongly absent from a monster is something you can fix by
        // hand; being wrongly present is a world of one face.
        assertTrue(SpriteNamespace.servesHero("pose:old_character"))
        assertFalse(SpriteNamespace.servesMonster("pose:old_character"))
    }

    @Test
    fun `a weapon or a prop is not a character`() {
        assertFalse(SpriteNamespace.isCharacter("weapon:alo_staff"))
        assertFalse(SpriteNamespace.isCharacter("prop:barrel"))
    }
}
