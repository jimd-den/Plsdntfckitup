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
import androidx.compose.runtime.produceState
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
import com.stratum.engine.model.mask.sculpt.Anatomy.Part
import com.stratum.engine.model.mask.sculpt.MaskCarver
import com.stratum.engine.model.mask.sculpt.MaskCarver.Control
import com.stratum.engine.model.mask.sculpt.MaskCulture
import com.stratum.engine.model.mask.sculpt.MaskPortrait
import com.stratum.engine.model.mask.sculpt.MaskSculptor
import com.stratum.engine.model.mask.sculpt.MaskSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.math.BigInteger
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The mask carver: carve your own African mask. A sculpted preview that
 * floats and turns, a roll after one people's carvers or any that keeps the
 * locked parts, variations to browse, every part carved afresh or set by
 * hand down to its last proportion, and keep, share and wear.
 */
@Composable
fun MaskCarverScreen(
    viewModel: MaskCarverViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** False in screenshots and tests: pictures are carved on the spot and the preview holds still. */
    animate: Boolean = true,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = StratumTheme.colors
    val spec = state.spec
    Column(
        modifier = modifier.fillMaxSize().background(colors.surface).safeContent()
            .verticalScroll(rememberScrollState()).padding(Space.large),
    ) {
        com.stratum.core.designsystem.component.StratumTopBar(title = "Mask carver", onBack = onBack)
        Text(
            "Carve a mask after ${MaskCulture.traditions.size} African traditions — ${remember { spoken(MaskCarver.designs()) }} designs from the named choices alone, and every proportion besides.",
            style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted,
        )
        Spacer(Modifier.height(Space.medium))

        StratumPanel(Modifier.fillMaxWidth()) {
            CarvedPortrait(spec, animate, Modifier.fillMaxWidth().aspectRatio(0.85f))
            Text(
                traditionLine(spec), color = colors.inkMuted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = Space.small),
            )
            Spacer(Modifier.height(Space.small))
            OutlinedTextField(value = spec.name, onValueChange = viewModel::setName, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Like this", Modifier.weight(1f))
            StratumChip("↻ More", selected = false, onClick = viewModel::vary)
        }
        ChipRow { state.variations.forEach { v -> Thumb(v, 96.dp, animate) { viewModel.pick(v) } } }

        Spacer(Modifier.height(Space.medium))
        ChipRow {
            CarverPage.all.forEach { p ->
                val locked = p is CarverPage.Of && p.part in state.locked
                StratumChip(if (locked) "🔒 ${p.label}" else p.label, selected = state.page == p, onClick = { viewModel.setPage(p) })
            }
        }
        Spacer(Modifier.height(Space.small))
        StratumPanel(Modifier.fillMaxWidth()) {
            when (val page = state.page) {
                CarverPage.Tradition -> TraditionPage(state, viewModel)
                is CarverPage.Of -> PartPage(page.part, state, viewModel)
                CarverPage.Kept -> KeptPage(state, viewModel, animate)
                CarverPage.Share -> SharePage(state, viewModel)
            }
        }
    }
}

private fun traditionLine(spec: MaskSpec): String {
    val ids = spec.tradition.split('+')
    val ts = ids.map { MaskCulture.tradition(it) }.distinctBy { it.id }
    return if (ts.size == 1) "After the ${ts[0].people} carvers of ${ts[0].region}: ${ts[0].name}"
    else "Between the ${ts[0].people} and the ${ts[1].people}: ${ts[0].name} and ${ts[1].name}"
}

// ---- pages ------------------------------------------------------------------------------------

@Composable
private fun TraditionPage(state: MaskCarverUiState, vm: MaskCarverViewModel) {
    val colors = StratumTheme.colors
    Text("Roll masks after one people's carvers, or any — and now and then two at once.", color = colors.inkMuted, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(Space.small))
    ChipRow {
        StratumChip("Any people", selected = state.tradition == null, onClick = { vm.chooseTradition(null) })
        MaskCulture.traditions.forEach { t -> StratumChip("${t.name} · ${t.people}", selected = state.tradition?.id == t.id, onClick = { vm.chooseTradition(t) }) }
    }
    state.tradition?.let { t ->
        Spacer(Modifier.height(Space.small))
        Text("${t.name} — ${t.people}, ${t.region}", color = colors.ink, style = MaterialTheme.typography.titleSmall)
        Text(t.about, color = colors.inkMuted, style = MaterialTheme.typography.bodySmall)
    }
}

/** A part of the mask: every one of its choices, colours and proportions. */
@Composable
private fun PartPage(part: Part, state: MaskCarverUiState, vm: MaskCarverViewModel) {
    val spec = state.spec
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        StratumChip(if (part in state.locked) "🔒 Locked" else "Lock", selected = part in state.locked, onClick = { vm.toggleLock(part) })
        StratumChip("🎲 Carve afresh", selected = false, onClick = { vm.reroll(part) })
        StratumChip("Reset proportions", selected = false, onClick = { vm.resetDials(part) })
    }
    Spacer(Modifier.height(Space.small))
    for (c in MaskCarver.controls(part)) ControlView(c, spec, vm)
}

@Composable
private fun ControlView(control: Control, spec: MaskSpec, vm: MaskCarverViewModel) {
    val colors = StratumTheme.colors
    when (control) {
        is Control.Pick -> {
            Label(control.label)
            val at = control.get(spec)
            ChipRow { control.options.forEachIndexed { i, o -> StratumChip(o, selected = i == at, onClick = { vm.pick(control, i) }) } }
        }
        is Control.Flags -> {
            Label(control.label)
            val on = control.get(spec)
            ChipRow { control.options.forEachIndexed { i, o -> StratumChip(o, selected = i in on, onClick = { vm.toggle(control, i) }) } }
        }
        is Control.Colour -> {
            val at = control.get(spec)
            Label("${control.label}: ${control.options.getOrNull(at)?.first.orEmpty()}")
            ChipRow {
                control.options.forEachIndexed { i, (_, argb) ->
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(Color(argb))
                            .border(if (i == at) 3.dp else 1.dp, if (i == at) colors.accent else colors.hairline, RoundedCornerShape(6.dp))
                            .clickable { vm.colour(control, i) },
                    )
                }
            }
        }
        is Control.Slider -> {
            var sliding by remember { mutableStateOf(false) }
            val v = control.get(spec)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(control.label, color = colors.inkMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(96.dp))
                Slider(
                    value = v.coerceIn(0f, 1f),
                    onValueChange = { x -> if (!sliding) { sliding = true; vm.beginSlide() }; vm.slide(control, x) },
                    onValueChangeFinished = { sliding = false },
                    modifier = Modifier.weight(1f).height(32.dp),
                )
                Text("${(v * 100).roundToInt()}", color = colors.inkMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(28.dp))
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun KeptPage(state: MaskCarverUiState, vm: MaskCarverViewModel, animate: Boolean) {
    if (state.kept.isEmpty()) {
        Text("Masks you keep live here. Tap Keep above to keep this one.", color = StratumTheme.colors.inkMuted, style = MaterialTheme.typography.bodySmall)
        return
    }
    state.kept.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            row.forEach { k ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Thumb(k, 96.dp, animate, k.name) { vm.load(k) }
                    StratumChip("Forget", selected = false, onClick = { vm.forget(k) })
                }
            }
        }
        Spacer(Modifier.height(Space.small))
    }
}

@Composable
private fun SharePage(state: MaskCarverUiState, vm: MaskCarverViewModel) {
    val clipboard = LocalClipboardManager.current
    var pasted by remember { mutableStateOf("") }
    Text("This mask's code carries every choice, colour and proportion — send it to a friend to open in their carver:", color = StratumTheme.colors.inkMuted, style = MaterialTheme.typography.bodySmall)
    SelectionContainer { Text(state.code, color = StratumTheme.colors.ink, style = MaterialTheme.typography.bodySmall) }
    StratumAction(label = "Copy code", onClick = { clipboard.setText(AnnotatedString(state.code)) }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(Space.small))
    OutlinedTextField(value = pasted, onValueChange = { pasted = it }, label = { Text("Paste a mask code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    StratumAction(label = "Open", onClick = { if (vm.openCode(pasted)) pasted = "" }, enabled = pasted.isNotBlank(), modifier = Modifier.fillMaxWidth())
}

// ---- parts --------------------------------------------------------------------------------------

@Composable
private fun Label(text: String) = Text(text, color = StratumTheme.colors.inkMuted, style = MaterialTheme.typography.bodySmall)

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.small), verticalAlignment = Alignment.CenterVertically) { content() }
}

private val Backdrop = Brush.verticalGradient(listOf(Color(0xFF2A211B), Color(0xFF120E0C)))

/** A still carved mask, small, to tap: carved roughly, off the main thread when live. */
@Composable
private fun Thumb(spec: MaskSpec, size: Dp, animate: Boolean, label: String? = null, onClick: () -> Unit) {
    val image = if (animate) {
        produceState<ImageBitmap?>(null, spec) { value = withContext(Dispatchers.Default) { picture(spec, MaskSculptor.Detail.FAR, 128, 154, 0.35f, 1) } }.value
    } else remember(spec) { picture(spec, MaskSculptor.Detail.FAR, 128, 154, 0.35f, 1) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(size).clickable(onClick = onClick)) {
        Box(Modifier.width(size).aspectRatio(128f / 154f).clip(RoundedCornerShape(10.dp)).background(Backdrop)) {
            image?.let { Image(it, contentDescription = spec.name, modifier = Modifier.fillMaxSize()) }
        }
        if (label != null) Text(label, color = StratumTheme.colors.inkMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/**
 * The carved mask, floating: carved roughly first and then finely, off the
 * main thread; it sways and bobs as if breathing, and a sideways drag turns it.
 */
@Composable
fun CarvedPortrait(spec: MaskSpec, animate: Boolean, modifier: Modifier = Modifier, width: Int = 340, height: Int = 400) {
    var turn by remember { mutableFloatStateOf(0.35f) }
    val currentTurn by rememberUpdatedState(turn)
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val still = if (!animate) remember(spec, turn) { picture(spec, MaskSculptor.Detail.GAME, width, height, turn, 2) } else null
    if (animate) {
        LaunchedEffect(spec) {
            val rough = withContext(Dispatchers.Default) { MaskSculptor.cached(spec, MaskSculptor.Detail.FAR) }
            var mesh = rough
            frame = withContext(Dispatchers.Default) { bitmap(MaskPortrait.render(mesh, width, height, yaw = currentTurn, supersample = 1), width, height) }
            mesh = withContext(Dispatchers.Default) { MaskSculptor.cached(spec, MaskSculptor.Detail.GAME) }
            val start = System.nanoTime()
            while (true) {
                val t = (System.nanoTime() - start) / 1e9f
                // Alive: a slow sway, a nod, and a pulse in the eyes.
                val yaw = currentTurn + 0.22f * sin(t * 0.7f)
                val pitch = 0.18f + 0.05f * sin(t * 1.1f)
                val glow = 0.7f + 0.25f * sin(t * 2.3f)
                frame = withContext(Dispatchers.Default) { bitmap(MaskPortrait.render(mesh, width, height, yaw = yaw, pitch = pitch, glow = glow, supersample = 1), width, height) }
                delay(60)
            }
        }
    }
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(Backdrop)
            .pointerInput(Unit) { detectHorizontalDragGestures { _, dx -> turn = (turn + dx * 0.006f).coerceIn(-1.4f, 1.4f) } },
        contentAlignment = Alignment.Center,
    ) {
        (still ?: frame)?.let { Image(it, contentDescription = spec.name, modifier = Modifier.fillMaxSize()) }
    }
}

private fun picture(spec: MaskSpec, detail: MaskSculptor.Detail, w: Int, h: Int, yaw: Float, supersample: Int): ImageBitmap =
    bitmap(MaskPortrait.render(MaskSculptor.cached(spec, detail), w, h, yaw = yaw, supersample = supersample), w, h)

private fun bitmap(argb: IntArray, w: Int, h: Int): ImageBitmap = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()

/** A big number in words: "12 septillion". */
internal fun spoken(n: BigInteger): String {
    val names = listOf("", "thousand", "million", "billion", "trillion", "quadrillion", "quintillion", "sextillion", "septillion", "octillion", "nonillion", "decillion")
    val digits = n.toString().length
    val group = ((digits - 1) / 3).coerceAtMost(names.size - 1)
    if (group == 0) return n.toString()
    val lead = n.divide(BigInteger.TEN.pow(group * 3))
    return "$lead ${names[group]}"
}
