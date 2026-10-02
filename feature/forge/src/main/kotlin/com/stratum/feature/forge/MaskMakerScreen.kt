package com.stratum.feature.forge

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.engine.model.mask.EmojiMask
import com.stratum.engine.model.mask.EmojiMask.Look
import com.stratum.engine.model.mask.MaskMaker
import com.stratum.engine.model.mask.MaskMaker.Trait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The mask maker: make your own Igbo mask. A live preview that feels and
 * turns, a roll that keeps what is locked, variations to browse, the
 * carvers' masks to start from, every trait by hand, and keep, share and
 * wear.
 */
@Composable
fun MaskMakerScreen(
    viewModel: MaskMakerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** False in screenshots and tests: the preview paints one still frame. */
    animate: Boolean = true,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = StratumTheme.colors
    val look = state.look
    Column(
        modifier = modifier.fillMaxSize().background(colors.surface).safeContent()
            .verticalScroll(rememberScrollState()).padding(Space.large),
    ) {
        com.stratum.core.designsystem.component.StratumTopBar(title = "Mask maker", onBack = onBack)
        Text(
            "Make your own Igbo mask — one of ${remember { MaskMaker.spoken(MaskMaker.choices()) }} before a single slider.",
            style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted,
        )
        Spacer(Modifier.height(Space.medium))

        StratumPanel(Modifier.fillMaxWidth()) {
            MaskPortrait(look, state.feeling, animate, Modifier.fillMaxWidth().aspectRatio(1f))
            Spacer(Modifier.height(Space.small))
            OutlinedTextField(value = look.name, onValueChange = viewModel::setName, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Space.small))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                StratumAction(label = "🎲 Roll", onClick = viewModel::roll, emphasis = ActionEmphasis.PRIMARY, modifier = Modifier.weight(1.3f))
                StratumAction(label = "↶", onClick = viewModel::undo, enabled = state.canUndo, modifier = Modifier.weight(0.6f))
                StratumAction(label = "↷", onClick = viewModel::redo, enabled = state.canRedo, modifier = Modifier.weight(0.6f))
                StratumAction(label = "Keep", onClick = viewModel::keep, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(Space.small))
            StratumAction(
                label = if (state.worn) "✓ Your hero wears this mask" else "Wear as your mask",
                onClick = viewModel::wear, emphasis = if (state.worn) ActionEmphasis.SECONDARY else ActionEmphasis.PRIMARY, modifier = Modifier.fillMaxWidth(),
            )
            state.message?.let { Text(it, color = colors.accent, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Space.small).clickable { viewModel.dismissMessage() }) }
        }

        Spacer(Modifier.height(Space.medium))
        SectionLabel("How it feels")
        ChipRow {
            EmojiMask.expressions.keys.forEach { name ->
                StratumChip(EmojiMask.igboNames[name]?.let { "$it · $name" } ?: name, selected = state.feeling == name, onClick = { viewModel.setFeeling(name) })
            }
        }

        Spacer(Modifier.height(Space.medium))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Like this", Modifier.weight(1f))
            StratumChip("↻ More", selected = false, onClick = viewModel::vary)
        }
        ChipRow { state.variations.forEach { v -> Thumb(v, state.feeling, 92.dp) { viewModel.pick(v) } } }
        Spacer(Modifier.height(Space.small))
        SectionLabel("Start from a carver's mask")
        ChipRow { EmojiMask.presets.forEach { p -> Thumb(p, "Serene", 92.dp, p.name) { viewModel.startFrom(p) } } }

        Spacer(Modifier.height(Space.medium))
        ChipRow { MakerPage.entries.forEach { p -> StratumChip(p.label, selected = state.page == p, onClick = { viewModel.setPage(p) }) } }
        Spacer(Modifier.height(Space.small))
        StratumPanel(Modifier.fillMaxWidth()) {
            when (state.page) {
                MakerPage.FACE -> FacePage(state, viewModel)
                MakerPage.CREST -> CrestPage(state, viewModel)
                MakerPage.FEATURES -> FeaturesPage(state, viewModel)
                MakerPage.MARKS -> MarksPage(state, viewModel)
                MakerPage.COLOURS -> ColoursPage(state, viewModel)
                MakerPage.KEPT -> KeptPage(state, viewModel)
                MakerPage.SHARE -> SharePage(state, viewModel)
            }
        }
    }
}

// ---- pages ------------------------------------------------------------------------------

@Composable
private fun FacePage(state: MaskMakerUiState, vm: MaskMakerViewModel) {
    val look = state.look
    TraitRow("Face", Trait.SHAPE, state, vm) {
        EmojiMask.FaceShape.entries.forEach { s -> StratumChip(s.label, look.shape == s, { vm.edit { it.copy(shape = s) } }) }
    }
    Dial("Width", look.width, vm) { l, v -> l.copy(width = v) }
    TraitRow("Ears", Trait.EARS, state, vm) {
        EmojiMask.Ears.entries.forEach { e -> StratumChip(e.label, look.ears == e, { vm.edit { it.copy(ears = e) } }) }
    }
    TraitRow("Adorn", Trait.ADORNMENT, state, vm) {
        StratumChip("Earrings", look.earrings, { vm.edit { it.copy(earrings = !it.earrings) } })
        StratumChip("Beard", look.beard, { vm.edit { it.copy(beard = !it.beard) } })
    }
}

@Composable
private fun CrestPage(state: MaskMakerUiState, vm: MaskMakerViewModel) {
    val look = state.look
    TraitRow("Hair", Trait.COIFFURE, state, vm) {
        EmojiMask.Hairline.entries.forEach { h -> StratumChip(h.label, look.hairline == h, { vm.edit { it.copy(hairline = h) } }) }
        StratumChip("Carved bands", look.braids, { vm.edit { it.copy(braids = !it.braids) } })
        StratumChip("Cowries", look.cowries, { vm.edit { it.copy(cowries = !it.cowries) } })
    }
    TraitRow("Crest", Trait.CREST, state, vm) {
        EmojiMask.Crest.entries.forEach { c -> StratumChip(c.label, look.crest == c, { vm.edit { it.copy(crest = c) } }) }
    }
    Dial("Crest height", look.crestSize, vm) { l, v -> l.copy(crestSize = v) }
    Dial("Combs, lobes, knots: ${look.crestCount}", (look.crestCount - 1) / 8f, vm) { l, v -> l.copy(crestCount = 1 + (v * 8).roundToInt()) }
}

@Composable
private fun FeaturesPage(state: MaskMakerUiState, vm: MaskMakerViewModel) {
    val look = state.look
    TraitRow("Eyes", Trait.EYES, state, vm) { EmojiMask.EyeStyle.entries.forEach { e -> StratumChip(e.label, look.eyes == e, { vm.edit { it.copy(eyes = e) } }) } }
    TraitRow("Brows", Trait.BROWS, state, vm) { EmojiMask.BrowStyle.entries.forEach { b -> StratumChip(b.label, look.brows == b, { vm.edit { it.copy(brows = b) } }) } }
    TraitRow("Nose", Trait.NOSE, state, vm) { EmojiMask.NoseStyle.entries.forEach { n -> StratumChip(n.label, look.nose == n, { vm.edit { it.copy(nose = n) } }) } }
    TraitRow("Mouth", Trait.MOUTH, state, vm) { EmojiMask.MouthStyle.entries.forEach { m -> StratumChip(m.label, look.mouth == m, { vm.edit { it.copy(mouth = m) } }) } }
}

@Composable
private fun MarksPage(state: MaskMakerUiState, vm: MaskMakerViewModel) {
    val look = state.look
    TraitRow("Marks", Trait.MARKS, state, vm) {
        EmojiMask.Mark.entries.forEach { m -> StratumChip(m.label, m in look.marks, { vm.edit { it.copy(marks = if (m in it.marks) it.marks - m else it.marks + m) } }) }
    }
    TraitRow("Paint", Trait.PAINT, state, vm) {
        EmojiMask.Paint.entries.forEach { p -> StratumChip(p.label, p in look.paint, { vm.edit { it.copy(paint = if (p in it.paint) it.paint - p else it.paint + p) } }) }
    }
}

@Composable
private fun ColoursPage(state: MaskMakerUiState, vm: MaskMakerViewModel) {
    val look = state.look
    TraitRow("Finish", Trait.FINISH, state, vm) {
        EmojiMask.Finish.entries.forEach { f ->
            StratumChip(f.label, look.finish == f, {
                vm.edit {
                    when (f) {
                        EmojiMask.Finish.CARVED -> it.copy(finish = f, ground = it.wood)
                        EmojiMask.Finish.BRASS -> it.copy(finish = f, ground = EmojiMask.BRASS, hair = EmojiMask.BRASS, crestColour = EmojiMask.BRASS)
                        else -> it.copy(finish = f)
                    }
                }
            })
        }
    }
    Swatches("Wood", look.wood) { p -> vm.edit { if (it.finish == EmojiMask.Finish.CARVED) it.copy(wood = p, ground = p) else it.copy(wood = p) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Colours", color = StratumTheme.colors.inkMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        LockChip(Trait.COLOURS, state, vm)
    }
    Swatches(if (look.finish == EmojiMask.Finish.PAINTED) "Painted face" else "Face", look.ground) { p -> vm.edit { it.copy(ground = p) } }
    Swatches("Coiffure", look.hair) { p -> vm.edit { it.copy(hair = p) } }
    Swatches("First paint", look.accent) { p -> vm.edit { it.copy(accent = p) } }
    Swatches("Second paint", look.accent2) { p -> vm.edit { it.copy(accent2 = p) } }
    Swatches("Crest", look.crestColour) { p -> vm.edit { it.copy(crestColour = p) } }
}

@Composable
private fun KeptPage(state: MaskMakerUiState, vm: MaskMakerViewModel) {
    if (state.kept.isEmpty()) {
        Text("Masks you keep live here. Tap Keep above to keep this one.", color = StratumTheme.colors.inkMuted, style = MaterialTheme.typography.bodySmall)
        return
    }
    state.kept.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            row.forEach { k ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Thumb(k, "Serene", 96.dp, k.name) { vm.load(k) }
                    StratumChip("Forget", selected = false, onClick = { vm.forget(k) })
                }
            }
        }
        Spacer(Modifier.height(Space.small))
    }
}

@Composable
private fun SharePage(state: MaskMakerUiState, vm: MaskMakerViewModel) {
    val clipboard = LocalClipboardManager.current
    var pasted by remember { mutableStateOf("") }
    Text("This mask's code — send it to a friend to open in their mask maker:", color = StratumTheme.colors.inkMuted, style = MaterialTheme.typography.bodySmall)
    SelectionContainer { Text(state.code, color = StratumTheme.colors.ink, style = MaterialTheme.typography.bodyMedium) }
    StratumAction(label = "Copy code", onClick = { clipboard.setText(AnnotatedString(state.code)) }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(Space.small))
    OutlinedTextField(value = pasted, onValueChange = { pasted = it }, label = { Text("Paste a mask code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    StratumAction(label = "Open", onClick = { if (vm.openCode(pasted)) pasted = "" }, enabled = pasted.isNotBlank(), modifier = Modifier.fillMaxWidth())
}

// ---- parts ------------------------------------------------------------------------------

@Composable
private fun TraitRow(label: String, trait: Trait, state: MaskMakerUiState, vm: MaskMakerViewModel, chips: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = StratumTheme.colors.inkMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(52.dp))
        LockChip(trait, state, vm)
        Spacer(Modifier.width(Space.small))
        ChipRow(chips)
    }
    Spacer(Modifier.height(4.dp))
}

/** 🔒 keeps a trait through the next roll. */
@Composable
private fun LockChip(trait: Trait, state: MaskMakerUiState, vm: MaskMakerViewModel) {
    StratumChip(if (trait in state.locked) "🔒 Locked" else "Lock", selected = trait in state.locked, onClick = { vm.toggleLock(trait) })
}

@Composable
private fun Dial(label: String, value: Float, vm: MaskMakerViewModel, set: (Look, Float) -> Look) {
    var sliding by remember { mutableStateOf(false) }
    Text(label, color = StratumTheme.colors.inkMuted, style = MaterialTheme.typography.bodySmall)
    Slider(
        value = value.coerceIn(0f, 1f),
        onValueChange = { v -> if (!sliding) { sliding = true; vm.beginSlide() }; vm.edit(record = false) { set(it, v) } },
        onValueChangeFinished = { sliding = false },
        modifier = Modifier.height(32.dp),
    )
}

@Composable
private fun Swatches(label: String, selected: Int, onPick: (Int) -> Unit) {
    val colors = StratumTheme.colors
    Text(label, color = colors.inkMuted, style = MaterialTheme.typography.bodySmall)
    ChipRow {
        for (i in 0 until EmojiMask.PIGMENT_COUNT) {
            val c = Color(EmojiMask.pigment(i))
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(c)
                    .border(if (i == selected) 3.dp else 1.dp, if (i == selected) colors.accent else colors.hairline, RoundedCornerShape(6.dp))
                    .clickable { onPick(i) },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.small), verticalAlignment = Alignment.CenterVertically) { content() }
}

private val Backdrop = Brush.verticalGradient(listOf(Color(0xFF2A211D), Color(0xFF0E0A09)))

/** A still mask, small, to tap. */
@Composable
private fun Thumb(look: Look, feeling: String, size: Dp, label: String? = null, onClick: () -> Unit) {
    val image = remember(look, feeling) { render(look, EmojiMask.expressions.getValue(feeling), 128, 0.15f, 0.4f) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(size).clickable(onClick = onClick)) {
        Image(image, contentDescription = look.name, modifier = Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(Backdrop))
        if (label != null) Text(label, color = StratumTheme.colors.inkMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/**
 * The live mask: it breathes, blinks and plays its feeling, and a sideways
 * drag turns it. Painted off the main thread a dozen times a second.
 */
@Composable
fun MaskPortrait(look: Look, feeling: String, animate: Boolean, modifier: Modifier = Modifier, pixels: Int = 360) {
    var turn by remember { mutableFloatStateOf(0.15f) }
    val face = EmojiMask.expressions.getValue(feeling)
    val currentTurn by rememberUpdatedState(turn)
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val still = if (!animate) remember(look, feeling, turn) { render(look, face, pixels, turn, 0.4f) } else null
    if (animate) {
        LaunchedEffect(look, feeling) {
            val start = System.nanoTime()
            while (true) {
                val t = (System.nanoTime() - start) / 1e9f
                frame = withContext(Dispatchers.Default) { render(look, face, pixels, currentTurn, t, blink = true) }
                delay(70)
            }
        }
    }
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(Backdrop)
            .pointerInput(Unit) { detectHorizontalDragGestures { _, dx -> turn = (turn + dx * 0.004f).coerceIn(-0.8f, 0.8f) } },
        contentAlignment = Alignment.Center,
    ) {
        (still ?: frame)?.let { Image(it, contentDescription = look.name, modifier = Modifier.fillMaxSize()) }
    }
}

private fun render(look: Look, face: EmojiMask.Face, px: Int, turn: Float, time: Float, blink: Boolean = false): ImageBitmap {
    val argb = EmojiMask.image(look, face, px, turn, time, blink)
    return Bitmap.createBitmap(argb, px, px, Bitmap.Config.ARGB_8888).asImageBitmap()
}
