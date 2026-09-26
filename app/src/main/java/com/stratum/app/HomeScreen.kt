package com.stratum.app

import com.stratum.core.designsystem.component.GameTile
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumDivider
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.component.StratumSection
import com.stratum.core.designsystem.component.StratumWell
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.domain.content.HeroClassDefinition
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.SpriteSheet
import com.stratum.feature.play.DrawableSprite

/** What the home screen shows about the hero kept for the chosen class. */
data class HeroSummary(val level: Int, val tier: Int, val unspentPoints: Int, val waystones: Int)

/**
 * The landing screen, laid out like a game's title menu rather than a
 * settings page: who you are playing and one big button to play, how the game
 * is played, and every tool for making it your own as a tile that says what
 * it does. Portrait stacks them; landscape puts the hero beside the tools.
 */
@Composable
internal fun HomeScreen(
    packName: String,
    blockCount: Int,
    biomeCount: Int,
    classCount: Int,
    heroClasses: List<HeroClassDefinition>,
    selectedClassId: String?,
    onSelectClass: (String) -> Unit,
    /** Art the player can wear, whichever class they are playing. */
    characterSheets: List<SpriteSheet> = emptyList(),
    selectedSheetId: String? = null,
    onSelectSheet: (String) -> Unit = {},
    /** The chosen character's art, so the picker shows who rather than what. */
    idleFrameFor: (String) -> DrawableSprite? = { null },
    unpackedCharacterCount: Int = 0,
    onPoseForge: () -> Unit = {},
    onBuildClass: () -> Unit,
    onDescend: () -> Unit,
    onForge: () -> Unit,
    onSprites: () -> Unit,
    spriteCount: Int,
    onSettings: () -> Unit,
    onStudio: () -> Unit,
    onLibrary: () -> Unit = {},
    importedCount: Int = 0,
    modifier: Modifier = Modifier,
    /** The saved hero for the chosen class, or null for a new one. */
    heroSummary: HeroSummary? = null,
    onTextures: () -> Unit = {},
    /** The style the world is painted in, when the player chose one. */
    paintedStyle: String? = null,
    /** Whether an image and language model is set up, which the AI tools need. */
    modelReady: Boolean = false,
) {
    val colors = StratumTheme.colors
    val play: @Composable () -> Unit = {
        PlayCard(
            heroClasses, selectedClassId, onSelectClass, characterSheets, selectedSheetId, onSelectSheet,
            idleFrameFor, unpackedCharacterCount, onPoseForge, onDescend, heroSummary,
        )
    }
    val tiles = listOf(
        TileSpec("🎨", "Texture forge", "Describe a look and AI paints the ground, walls and props.", onTextures,
            status = paintedStyle?.let { "Wearing: $it" } ?: if (modelReady) "Ready to paint" else "Needs a model key", badge = "NEW", highlighted = true),
        TileSpec("🧍", "Pose forge", "Draw your hero once, then pose them for every move.", onPoseForge,
            status = if (unpackedCharacterCount > 0) "$unpackedCharacterCount waiting to be packed" else null, badge = unpackedCharacterCount.takeIf { it > 0 }?.toString()),
        TileSpec("🖼", "Sprite forge", "Generate animated sprite sheets for heroes and monsters.", onSprites,
            status = if (spriteCount > 0) "$spriteCount sheets" else null),
        TileSpec("⚔", "Build a class", "Choose stats, skills and a starting weapon for a new class.", onBuildClass,
            status = "$classCount classes"),
        TileSpec("🌍", "World generator", "Generate a whole world with AI: blocks, regions, monsters, lore.", onForge),
        TileSpec("🧩", "Plugins and games", "Import Flame or Tiled games, install mods, share your own.", onLibrary,
            status = if (importedCount > 0) "$importedCount loaded" else "Nothing imported yet"),
        TileSpec("🛠", "Creator studio", "The full editor, for pack makers.", onStudio),
        TileSpec("⚙", "Model provider", "Connect the AI that paints and writes for the forges.", onSettings,
            status = if (modelReady) "Connected" else "Not set up"),
    )

    BoxWithConstraints(modifier.fillMaxSize().background(colors.surface).safeContent()) {
        val landscape = maxWidth > maxHeight && maxWidth >= LANDSCAPE_MIN_WIDTH
        if (landscape) {
            Row(Modifier.fillMaxSize().padding(Space.large), horizontalArrangement = Arrangement.spacedBy(Space.large)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Banner(packName, blockCount, biomeCount, classCount)
                    Spacer(Modifier.height(Space.large))
                    play()
                }
                Column(Modifier.weight(1.3f).verticalScroll(rememberScrollState())) {
                    HowToPlay()
                    Spacer(Modifier.height(Space.large))
                    ToolGrid(tiles, columns = 3)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.large)) {
                Banner(packName, blockCount, biomeCount, classCount)
                Spacer(Modifier.height(Space.large))
                play()
                Spacer(Modifier.height(Space.large))
                HowToPlay()
                Spacer(Modifier.height(Space.large))
                ToolGrid(tiles, columns = 2)
                Spacer(Modifier.height(Space.huge))
            }
        }
    }
}

@Composable
private fun Banner(packName: String, blockCount: Int, biomeCount: Int, classCount: Int) {
    val colors = StratumTheme.colors
    Text("STRATUM", style = MaterialTheme.typography.displaySmall, color = colors.ink)
    Text(
        "Dig, build and fight through endless worlds. Import them, generate them, or make your own.",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.inkMuted,
    )
    Spacer(Modifier.height(Space.small))
    Text(
        "Playing $packName · $blockCount blocks · $biomeCount regions · $classCount classes",
        style = MaterialTheme.typography.labelSmall,
        color = colors.accent,
    )
}

/** Who you are, and the one button that matters. */
@Composable
private fun PlayCard(
    heroClasses: List<HeroClassDefinition>,
    selectedClassId: String?,
    onSelectClass: (String) -> Unit,
    characterSheets: List<SpriteSheet>,
    selectedSheetId: String?,
    onSelectSheet: (String) -> Unit,
    idleFrameFor: (String) -> DrawableSprite?,
    unpackedCharacterCount: Int,
    onPoseForge: () -> Unit,
    onDescend: () -> Unit,
    heroSummary: HeroSummary?,
) {
    val colors = StratumTheme.colors
    val chosen = heroClasses.firstOrNull { it.id == selectedClassId }
    StratumPanel(modifier = Modifier.fillMaxWidth()) {
        SectionLabel("Your hero")
        Spacer(Modifier.height(Space.small))
        val drawn = remember(selectedSheetId, selectedClassId, idleFrameFor) { idleFrameFor(selectedClassId.orEmpty()) }
        if (drawn != null) {
            IdlePortrait(drawn, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Space.small))
        }
        if (chosen != null) {
            Text(chosen.name, style = MaterialTheme.typography.headlineSmall, color = colors.ink)
            Text(
                listOfNotNull(chosen.title.takeIf { it.isNotBlank() }, "${chosen.resolvedStats.maxHealth} health", "${chosen.resolvedStats.attackPower} attack")
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
            )
        }
        Spacer(Modifier.height(Space.small))
        Text(
            text = heroSummary?.let { hero ->
                buildString {
                    append("Level ${hero.level}")
                    if (hero.tier > 0) append(" · world tier ${hero.tier} open")
                    if (hero.unspentPoints > 0) append(" · ${hero.unspentPoints} points to spend")
                    if (hero.waystones > 0) append(" · ${hero.waystones} waystones")
                }
            } ?: "A new hero, level 1. Everything you earn carries into every world you enter.",
            style = MaterialTheme.typography.bodySmall,
            color = if (heroSummary != null) colors.accent else colors.inkMuted,
        )
        Spacer(Modifier.height(Space.medium))
        if (heroClasses.size > 1) {
            Text("Class", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
            Spacer(Modifier.height(Space.tight))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                items(heroClasses, key = HeroClassDefinition::id) { hero ->
                    StratumChip(label = hero.name, selected = hero.id == selectedClassId, onClick = { onSelectClass(hero.id) })
                }
            }
            Spacer(Modifier.height(Space.small))
        }
        if (characterSheets.isNotEmpty()) {
            Text("Look", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
            Spacer(Modifier.height(Space.tight))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                // Tapping the chosen one again clears it, back to the class's own art.
                items(characterSheets, key = SpriteSheet::id) { sheet ->
                    StratumChip(label = sheet.name, selected = sheet.id == selectedSheetId, onClick = { onSelectSheet(sheet.id) })
                }
            }
            Spacer(Modifier.height(Space.small))
        }
        if (unpackedCharacterCount > 0) {
            StratumAction(
                label = "Pack $unpackedCharacterCount drawn character" + if (unpackedCharacterCount == 1) "" else "s",
                onClick = onPoseForge,
                emphasis = ActionEmphasis.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.small))
        }
        StratumAction(
            label = if (heroSummary != null && heroSummary.level > 1) "Continue" else "Start adventure",
            onClick = onDescend,
            emphasis = ActionEmphasis.PRIMARY,
            modifier = Modifier.fillMaxWidth().height(PLAY_BUTTON_HEIGHT),
        )
    }
}

/**
 * The whole game in seven lines, each led by the glyph its button wears in
 * play, so the first time a player sees the HUD they already know it.
 */
@Composable
private fun HowToPlay() {
    val colors = StratumTheme.colors
    StratumPanel(modifier = Modifier.fillMaxWidth()) {
        SectionLabel("How to play")
        Spacer(Modifier.height(Space.small))
        HOW_TO_PLAY.forEach { (glyph, line) ->
            Row(Modifier.padding(vertical = Space.tight), verticalAlignment = Alignment.CenterVertically) {
                Text(glyph, fontSize = 20.sp, modifier = Modifier.width(36.dp), color = colors.accent)
                Text(line, style = MaterialTheme.typography.bodySmall, color = colors.ink)
            }
        }
    }
}

private val HOW_TO_PLAY = listOf(
    "🕹" to "Move with the stick under your left thumb.",
    "⚔" to "Strike with the big button; your skills and roll fan out around it.",
    "⛏" to "Tap the ground to dig. Hold to place a block, or open Build for walls and rooms.",
    "✦" to "Each level gives two points. Spend them on the Hero tree: tap a far node and the path lights up.",
    "⚒" to "Currency drops straight into your pouch. Use it at the Anvil to reroll, upgrade and socket gear.",
    "🗺" to "Fell a champion to open the next world tier. Waystones open harder worlds with bigger rewards.",
    "🎨" to "Style changes the look instantly. The texture forge paints it for real.",
)

private data class TileSpec(
    val glyph: String,
    val title: String,
    val description: String,
    val onClick: () -> Unit,
    val status: String? = null,
    val badge: String? = null,
    val highlighted: Boolean = false,
)

@Composable
private fun ToolGrid(tiles: List<TileSpec>, columns: Int) {
    SectionLabel("Make it yours")
    Spacer(Modifier.height(Space.small))
    tiles.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            row.forEach { tile ->
                GameTile(
                    glyph = tile.glyph,
                    title = tile.title,
                    description = tile.description,
                    onClick = tile.onClick,
                    status = tile.status,
                    badge = tile.badge,
                    highlighted = tile.highlighted,
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(Space.small))
    }
}

private val LANDSCAPE_MIN_WIDTH = 600.dp
private val PLAY_BUTTON_HEIGHT = 56.dp

/**
 * One idle frame of a character, drawn the way the world draws it.
 *
 * Deliberately the first frame rather than a running animation: this is a menu
 * and a looping character in it competes with the thing the person came here
 * to press. The point is recognition -- which of the characters you made is
 * this -- and one frame settles that.
 */
@Composable
private fun IdlePortrait(sprite: DrawableSprite, modifier: Modifier = Modifier) {
    val sheet = sprite.sheet
    val frame = sheet.clip(AnimationState.IDLE)?.firstFrame ?: 0
    val rect = sheet.frameRect(frame)
    if (rect.width <= 0 || rect.height <= 0) return

    Canvas(
        modifier = modifier.height(PORTRAIT_HEIGHT),
    ) {
        // Fitted by height and centred: the frame's proportions belong to the
        // character, and squeezing them to a fixed box would make a lunging
        // stance a different person from a standing one.
        val drawHeight = size.height
        val drawWidth = drawHeight * rect.width / rect.height.coerceAtLeast(1)
        drawImage(
            image = sprite.image,
            srcOffset = IntOffset(rect.left, rect.top),
            srcSize = IntSize(rect.width, rect.height),
            dstOffset = IntOffset(((size.width - drawWidth) / 2f).toInt(), 0),
            dstSize = IntSize(drawWidth.toInt().coerceAtLeast(1), drawHeight.toInt()),
            filterQuality = FilterQuality.None,
        )
    }
}

private val PORTRAIT_HEIGHT = 132.dp
