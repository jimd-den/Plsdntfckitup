package com.stratum.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stratum.core.designsystem.component.LookChoice
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.app.AiWiring
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.session.PlayerLoadout
import com.stratum.core.domain.sprite.SpriteFallback
import com.stratum.core.domain.sprite.SpriteNamespace
import com.stratum.core.domain.sprite.WeaponPosing
import com.stratum.core.domain.sprite.WeaponRig
import com.stratum.feature.play.DrawableSprite
import com.stratum.feature.play.DrawableWeapon
import com.stratum.feature.play.SpriteKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every look the player can wear: every sprite sheet drawn in the forges or
 * brought in by a plugin -- heroes, characters, monsters and pack art alike
 * -- each with its first idle frame to show. Nothing is filtered out: what
 * a player chooses to look like is theirs to choose, so the list is every
 * sheet there is, hero art first and each labelled with what it was made as.
 * Read off the main thread; the sprite store caches the decoded images, so a
 * second screen asking is cheap.
 */
@Composable
internal fun rememberLookChoices(app: AppViewModel, content: AssembledContent): List<LookChoice> {
    val drawnSheets by app.graph.ai.sprites.sheets.collectAsStateWithLifecycle()
    val looks by produceState(emptyList<LookChoice>(), drawnSheets, content.spriteSheets) {
        value = withContext(Dispatchers.IO) {
            (drawnSheets + content.spriteSheets)
                .distinctBy { it.id }
                .sortedBy { if (SpriteNamespace.servesHero(it.id)) 0 else 1 }
                .map { sheet ->
                    val idle = sheet.clipOrFallback(AnimationState.IDLE)?.firstFrame ?: 0
                    LookChoice(
                        id = sheet.id,
                        name = if (SpriteNamespace.servesHero(sheet.id)) sheet.name else "${sheet.name} · ${SpriteNamespace.kindOf(sheet.id)}",
                        portrait = app.graph.ai.sprites.drawableBitmapFor(sheet.id)?.asImageBitmap(),
                        frame = sheet.frameRect(idle),
                    )
                }
        }
    }
    return looks
}

/**
 * Decides what every actor in a world is drawn with.
 *
 * Built from the chosen class as well as the art: the player's art is a
 * property of who they are playing, and resolving it without that was the
 * bug where every class was drawn with whichever hero sheet was newest.
 *
 * The resolver is asked for a sprite on every drawn frame, for every actor,
 * so anything built here is built once and kept. A rig is a map the size of
 * the sheet's frame count; rebuilding it per frame would allocate one per
 * actor per frame inside the draw loop.
 */
internal fun heroSpriteResolver(
    ai: AiWiring,
    content: AssembledContent,
    loadout: PlayerLoadout,
): (SpriteKey) -> DrawableSprite? {
    val rigs = HashMap<String, WeaponRig>()
    val held = loadout.equippedWeaponId?.let { id ->
        val weapon = ai.weapons.find(id)
        val bitmap = ai.weapons.bitmapFor(id)
        if (weapon != null && bitmap != null) weapon to bitmap.asImageBitmap() else null
    }

    return { key: SpriteKey ->
        // Candidates in order of preference rather than one guess. The first
        // choice can resolve to a sheet with no usable image -- one that came
        // back blank, or a pack sheet with no pixels on this device -- and
        // picking it and then drawing nothing is how a character ends up with
        // no skin and no explanation.
        val candidates = when (key) {
            SpriteKey.Player -> {
                val chosen = loadout.heroClassId ?: content.heroClasses.firstOrNull()?.id
                // What the player picked outright comes first: it is the most
                // explicit thing anybody has said about how they want to look.
                listOfNotNull(loadout.heroSheetId?.let { id -> content.spriteSheets.firstOrNull { it.id == id } }) +
                    listOfNotNull(chosen?.let(content::sheetForHero)) +
                    // A player who has drawn art but assigned none still gets
                    // to see it, rather than art they made sitting unused.
                    SpriteFallback.spread(
                        content.spriteSheets.filter { SpriteNamespace.servesHero(it.id) },
                        actorId = chosen.orEmpty(),
                    )
            }
            is SpriteKey.Monster ->
                listOfNotNull(content.sheetForEnemy(key.definitionId)) +
                    // Spread by the enemy's id: packs name enemies without
                    // naming art for them, so without this every kind of
                    // monster in the world is drawn with the same picture.
                    SpriteFallback.spread(
                        content.spriteSheets.filter { SpriteNamespace.servesMonster(it.id) },
                        actorId = key.definitionId,
                    )
        }

        candidates.distinctBy { it.id }.firstNotNullOfOrNull { found ->
            ai.sprites.drawableBitmapFor(found.id)?.let { bitmap ->
                DrawableSprite(
                    sheet = found,
                    image = bitmap.asImageBitmap(),
                    // Only the player carries one for now.
                    weapon = if (key == SpriteKey.Player && held != null) {
                        DrawableWeapon(
                            sprite = held.first,
                            image = held.second,
                            rig = rigs.getOrPut(found.id) {
                                WeaponPosing.rigFor(
                                    sheet = found,
                                    fit = ai.weaponFits.fitFor(found.id),
                                    // The poses the art was drawn against:
                                    // rigging against anything else hangs the
                                    // sword off a hand that is not there.
                                    guides = ai.poseGuides.guidesFor(found.id),
                                )
                            },
                        )
                    } else {
                        null
                    },
                )
            }
        }
    }
}
