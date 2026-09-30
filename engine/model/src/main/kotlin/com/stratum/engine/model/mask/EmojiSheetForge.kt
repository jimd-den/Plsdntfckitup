package com.stratum.engine.model.mask

import com.stratum.core.domain.ai.GenerationException
import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.ImageModelPort
import com.stratum.core.domain.ai.ImageRequest
import com.stratum.core.domain.sprite.AnimationClip
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.SpriteOrigin
import com.stratum.core.domain.sprite.SpriteSheet
import com.stratum.engine.scene.Texture
import com.stratum.engine.scene.forge.ImageCodec
import com.stratum.engine.scene.forge.Pixels
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Igbo emoji characters as 2D sprite sheets, drawn by an image model.
 *
 * A character is a [MaskGenome] -- the same dials the creator edits -- told
 * to the model in words ([brief]). The model is asked for one image: a 4x4
 * sheet of that one emoji, each cell a named expression or action frame, on
 * flat magenta. What comes back is never trusted to be tidy, so it is cut
 * here ([cut]):
 *
 * 1. the magenta is keyed out ([Pixels.keyOut]), fringe de-spilled;
 * 2. the image is sliced on the grid that was asked for, at whatever size
 *    it actually came back;
 * 3. in each cell the character is the largest blob; anything touching it
 *    or near it (a sparkle, a tear) stays, specks and bleed from the next
 *    cell go;
 * 4. every frame is scaled so the head is the same size in all sixteen and
 *    set on the same centre, because models draw a character a little
 *    bigger or smaller cell to cell and a sheet that breathes in size looks
 *    broken when it plays;
 * 5. each frame is checked, and a blank or uncut one is reported.
 *
 * The result is a clean [SpriteSheet] with clips -- idle, attack, hurt,
 * special, die -- and two emotes, ready to draw as a character.
 */
class EmojiSheetForge(
    private val images: ImageModelPort,
    private val codec: ImageCodec,
) {
    /** One cell: what it shows, and the clip it plays in (null for an emote). */
    data class Cell(val name: String, val state: AnimationState?, val direction: String)

    class Sheet(
        val genome: MaskGenome,
        /** The frames laid out 4x4, each [frameSize] square. */
        val image: Texture,
        val frames: List<Texture>,
        val sprite: SpriteSheet,
        /** Per frame, why it is unusable, or null. */
        val defects: List<String?>,
        val prompt: String,
    ) {
        val usable: Boolean get() = defects.count { it != null } <= MAX_BAD_FRAMES
    }

    suspend fun forge(
        genome: MaskGenome,
        frameSize: Int = FRAME,
        modelId: String? = null,
        observer: GenerationObserver = GenerationObserver.None,
        style: String = STYLE,
    ): Result<Sheet> {
        val prompt = prompt(genome, style)
        val image = images.generateImage(
            ImageRequest(prompt = prompt, modelId = modelId, width = CANVAS, height = CANVAS, requireTransparency = false),
            observer,
        ).getOrElse { return Result.failure(it) }
        val raw = codec.decode(image.bytes) ?: return Result.failure(GenerationException("The image could not be read"))
        Pixels.defect(raw)?.let { return Result.failure(GenerationException("The sheet came back unusable: $it")) }
        return Result.success(cut(genome, raw, frameSize, prompt))
    }

    companion object {
        const val COLUMNS = 4
        const val ROWS = 4
        const val CANVAS = 1024
        const val FRAME = 256
        const val MAX_BAD_FRAMES = 2

        /** The look asked for: the sticker emoji of the reference, as 2D art. */
        const val STYLE = "Clean, cute 2D emoji art, like a premium sticker pack: smooth rounded shapes, soft cel shading " +
            "with one glossy highlight, bold flat colours, crisp dark line work for the face, and a thick clean white " +
            "sticker outline around the whole character."

        /** The sixteen cells, in reading order. */
        val CELLS: List<Cell> = listOf(
            Cell("idle-calm", AnimationState.IDLE, "calm and content, eyes softly open, gentle smile"),
            Cell("idle-blink", AnimationState.IDLE, "the same, blinking: eyes closed as curved lines"),
            Cell("idle-smile", AnimationState.IDLE, "a warm closed-eye smile, floating a little higher"),
            Cell("idle-look", AnimationState.IDLE, "calm, glancing to one side, head tilted slightly"),
            Cell("attack-windup", AnimationState.ATTACK, "winding up: eyes narrowed fiercely, brows down, leaning back"),
            Cell("attack-lunge", AnimationState.ATTACK, "lunging forward head first, shouting, speed lines behind"),
            Cell("attack-impact", AnimationState.ATTACK, "the hit lands: fierce grin, a burst of small gold sparkles"),
            Cell("attack-recover", AnimationState.ATTACK, "settling back, a confident smirk"),
            Cell("hurt-wince", AnimationState.HURT, "struck: eyes squeezed shut as > <, wobbly mouth, a sweat drop"),
            Cell("hurt-reel", AnimationState.HURT, "reeling: spiral eyes, tilted, small stars circling"),
            Cell("special-charge", AnimationState.SPECIAL, "charging a power: eyes glowing gold, uli patterns shining"),
            Cell("special-burst", AnimationState.SPECIAL, "unleashing it: radiant, mouth open in a shout, a ring of light around"),
            Cell("die-fall", AnimationState.DIE, "defeated: x x eyes, tongue out, tilting over"),
            Cell("die-fade", AnimationState.DIE, "fading away: eyes closed peacefully, drawn paler and smaller"),
            Cell("emote-wink", null, "winking playfully with a little heart"),
            Cell("emote-laugh", null, "laughing hard, eyes closed in happy arcs, mouth wide open"),
        )

        /** The character in words, from its dials: what the model is asked to draw in every cell. */
        fun brief(g: MaskGenome): String {
            val p = MaskPalettes[g.palette]
            if (g.flower) return "a cute red poppy flower character: six round glossy red petals around a dark round centre that is its face"
            val parts = ArrayList<String>()
            val face = colourName(p.face)
            parts += "a round oval Igbo mask emoji head with a $face face (no body, it floats)"
            when (g.paint) {
                FacePaint.HALF -> parts += "the face painted half ${colourName(p.second)}"
                FacePaint.T_ZONE -> parts += "a ${colourName(p.second)} T painted across the brow and down the nose"
                FacePaint.BROW -> parts += "the brow painted ${colourName(p.second)}"
                FacePaint.CHIN -> parts += "the chin painted ${colourName(p.second)}"
                FacePaint.EYE_BAND -> parts += "a ${colourName(p.second)} band painted across the eyes"
                FacePaint.NONE -> Unit
            }
            val hair = colourName(p.crest)
            when (g.hair) {
                HairStyle.CAP -> parts += "short $hair hair"
                HairStyle.PUFFS -> parts += "a ring of round $hair afro puffs"
                HairStyle.BUN -> parts += "$hair hair in a high bun"
                HairStyle.CROWN -> parts += "$hair hair with a gold headband of ${colourName(p.second)} zigzags"
                HairStyle.BRAIDS -> parts += "$hair cornrow braids"
                HairStyle.NONE -> when (g.crest) {
                    CrestForm.HORNS -> parts += "two curved $hair horns"
                    CrestForm.COMBS -> parts += "a crested $hair coiffure of combs"
                    CrestForm.TIERS -> parts += "a stepped tiered crown"
                    CrestForm.PLUMES -> parts += "a fan of plumes"
                    CrestForm.DISC -> parts += "a sun disc halo behind the head"
                    CrestForm.NONE -> Unit
                }
            }
            if (g.ichi > 0) parts += "${g.ichi} short vertical ichi marks on the forehead"
            else if (g.ornament > 0.45f) parts += "a small uli cross mark on the forehead"
            if (g.cheekMarks > 0) parts += "${g.cheekMarks} short cheek stripes on each cheek"
            if (g.ornament > 0.6f) parts += "a few gold uli dots"
            parts += when (g.eyes) {
                EyeForm.ALMOND -> "calm almond eyes"
                EyeForm.CRESCENT -> "eyes that curve into happy crescents"
                EyeForm.ROUND -> "round glossy eyes"
                EyeForm.TUBULAR -> "big round eyes ringed in gold"
            }
            if (g.mouth == MouthForm.OPEN_TEETH) parts += "a toothy grin" else parts += "red lips"
            parts += "rosy cheeks"
            when (g.earrings) { Earrings.HOOPS -> parts += "gold hoop earrings"; Earrings.DROPS -> parts += "gold drop earrings"; Earrings.NONE -> Unit }
            if (g.hat) parts += "a cream bucket hat with a small black uli knot emblem"
            if (g.shades) parts += "dark sunglasses"
            if (g.chain) parts += "a gold chain"
            return parts.joinToString(", ")
        }

        fun prompt(g: MaskGenome, style: String = STYLE): String = buildString {
            appendLine("A 2D game sprite sheet: one image made of 16 small separate pictures of the same emoji character,")
            appendLine("arranged in a strict grid of 4 columns by 4 rows.")
            appendLine()
            appendLine("The character: ${brief(g)}.")
            appendLine(style)
            appendLine("Inspired by Igbo masquerade masks from Nigeria: camwood red, kaolin white, ochre and gold.")
            appendLine()
            appendLine("The 16 cells, left to right, top to bottom:")
            CELLS.forEachIndexed { i, c -> appendLine("${i + 1}. ${c.direction}.") }
            appendLine()
            appendLine("Layout, exactly:")
            appendLine("- The canvas is $CANVAS by $CANVAS pixels, each cell ${CANVAS / COLUMNS} by ${CANVAS / ROWS}.")
            appendLine("- One whole character per cell, centred, drawn small enough to leave a clear margin on every side.")
            appendLine("- Do NOT draw one large character across the whole image.")
            appendLine("- The same character, the same size, the same colours in every cell; only the expression and pose change.")
            appendLine("- No text, no labels, no numbers, no grid lines, no borders.")
            appendLine()
            appendLine("Background: flat, solid, pure magenta (#FF00FF) filling every cell and every gap.")
            appendLine("No gradient, no texture, no shadow on the background, and no magenta anywhere on the character.")
        }

        /** The model's image, cut into a clean sheet: see the class notes. */
        fun cut(genome: MaskGenome, raw: Texture, frameSize: Int = FRAME, prompt: String = ""): Sheet {
            val keyed = Pixels.keyOut(raw)
            val cw = keyed.width / COLUMNS; val ch = keyed.height / ROWS
            val cells = (0 until COLUMNS * ROWS).map { i -> crop(keyed, (i % COLUMNS) * cw, (i / COLUMNS) * ch, cw, ch) }
            val kept = cells.map { character(it) }
            // The head's size, frame to frame: the character's main blob. Scale each to the sheet's median, so sizes agree.
            val heads = kept.map { it.second }
            val target = frameSize * HEAD_SHARE
            val frames = kept.mapIndexed { i, (cell, head) ->
                if (head == null) Texture(frameSize, frameSize, IntArray(frameSize * frameSize))
                else place(cell, head, target, frameSize)
            }
            val defects = frames.mapIndexed { i, f -> if (heads[i] == null) "nothing drawn in this cell" else Pixels.spriteDefect(f) }
            val image = Texture(frameSize * COLUMNS, frameSize * ROWS, IntArray(frameSize * frameSize * COLUMNS * ROWS))
            frames.forEachIndexed { i, f ->
                val ox = (i % COLUMNS) * frameSize; val oy = (i / COLUMNS) * frameSize
                for (y in 0 until frameSize) System.arraycopy(f.argb, y * frameSize, image.argb, (oy + y) * image.width + ox, frameSize)
            }
            val clips = CELLS.withIndex().filter { it.value.state != null }.groupBy { it.value.state!! }.map { (state, cs) ->
                AnimationClip(state, firstFrame = cs.first().index, frameCount = cs.size, frameDurationMs = state.defaultFrameDurationMs, loops = state !in AnimationState.oneShot)
            }
            val sprite = SpriteSheet(
                id = "emoji:" + slug(genome.name), name = genome.name, columns = COLUMNS, rows = ROWS,
                frameWidth = frameSize, frameHeight = frameSize, clips = clips, origin = SpriteOrigin.AI_GENERATED,
            )
            return Sheet(genome, image, frames, sprite, defects, prompt)
        }

        /** The share of a frame's height the head fills. */
        private const val HEAD_SHARE = 0.62f

        private fun crop(src: Texture, x0: Int, y0: Int, w: Int, h: Int): Texture {
            val out = IntArray(w * h)
            for (y in 0 until h) System.arraycopy(src.argb, (y0 + y) * src.width + x0, out, y * w, w)
            return Texture(w, h, out)
        }

        /**
         * The character in a cell: its largest blob (the head) and anything
         * near it bigger than a speck; everything else cleared. Returns the
         * cleaned cell and the head's box (x0, y0, x1, y1), or null if empty.
         */
        private fun character(cell: Texture): Pair<Texture, IntArray?> {
            val w = cell.width; val h = cell.height
            val label = IntArray(w * h) { -1 }
            val boxes = ArrayList<IntArray>(); val sizes = ArrayList<Int>()
            val queue = IntArray(w * h)
            for (start in 0 until w * h) {
                if (label[start] >= 0 || alpha(cell.argb[start]) < 128) continue
                val id = boxes.size
                val box = intArrayOf(w, h, -1, -1)
                var head = 0; var tail = 0
                queue[tail++] = start; label[start] = id
                var n = 0
                while (head < tail) {
                    val i = queue[head++]; n++
                    val x = i % w; val y = i / w
                    box[0] = min(box[0], x); box[1] = min(box[1], y); box[2] = max(box[2], x); box[3] = max(box[3], y)
                    for (j in intArrayOf(i - 1, i + 1, i - w, i + w)) {
                        if (j < 0 || j >= w * h) continue
                        if ((j == i - 1 && x == 0) || (j == i + 1 && x == w - 1)) continue
                        if (label[j] < 0 && alpha(cell.argb[j]) >= 128) { label[j] = id; queue[tail++] = j }
                    }
                }
                boxes += box; sizes += n
            }
            if (boxes.isEmpty()) return cell to null
            val main = sizes.indices.maxBy { sizes[it] }
            val mb = boxes[main]
            val reach = max(mb[2] - mb[0], mb[3] - mb[1]) * 0.35f
            val keep = BooleanArray(boxes.size) { k ->
                k == main || (sizes[k] > w * h * SPECK && boxes[k].let { b ->
                    b[2] >= mb[0] - reach && b[0] <= mb[2] + reach && b[3] >= mb[1] - reach && b[1] <= mb[3] + reach
                })
            }
            val out = IntArray(w * h)
            for (i in 0 until w * h) {
                val l = label[i]
                // Soft edge pixels (below the blob threshold) follow their nearest kept neighbour.
                out[i] = if (l >= 0) { if (keep[l]) cell.argb[i] else 0 } else if (alpha(cell.argb[i]) > 0 && near(label, keep, i, w, h)) cell.argb[i] else 0
            }
            return Texture(w, h, out) to mb
        }

        private fun near(label: IntArray, keep: BooleanArray, i: Int, w: Int, h: Int): Boolean {
            val x = i % w; val y = i / w
            for (dy in -2..2) for (dx in -2..2) {
                val xx = x + dx; val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                val l = label[yy * w + xx]
                if (l >= 0 && keep[l]) return true
            }
            return false
        }

        /** The cell scaled so the head's larger side is [target] pixels, the head centred a little above the frame's middle. */
        private fun place(cell: Texture, head: IntArray, target: Float, size: Int): Texture {
            val hw = head[2] - head[0] + 1; val hh = head[3] - head[1] + 1
            val s = target / max(hw, hh)
            val cx = (head[0] + head[2]) / 2f; val cy = (head[1] + head[3]) / 2f
            val out = IntArray(size * size)
            val fx = size / 2f; val fy = size * 0.52f
            for (y in 0 until size) for (x in 0 until size) {
                val sx = cx + (x + 0.5f - fx) / s - 0.5f; val sy = cy + (y + 0.5f - fy) / s - 0.5f
                out[y * size + x] = bilinear(cell, sx, sy)
            }
            return Texture(size, size, out)
        }

        /** Premultiplied bilinear sample, so transparent neighbours do not bleed a dark fringe. */
        private fun bilinear(t: Texture, x: Float, y: Float): Int {
            val x0 = kotlin.math.floor(x).toInt(); val y0 = kotlin.math.floor(y).toInt()
            val tx = x - x0; val ty = y - y0
            var a = 0f; var r = 0f; var g = 0f; var b = 0f
            for (k in 0 until 4) {
                val xx = x0 + (k and 1); val yy = y0 + (k shr 1)
                if (xx < 0 || yy < 0 || xx >= t.width || yy >= t.height) continue
                val wgt = (if (k and 1 == 0) 1 - tx else tx) * (if (k shr 1 == 0) 1 - ty else ty)
                val c = t.argb[yy * t.width + xx]
                val ca = alpha(c) / 255f * wgt
                a += ca; r += ((c shr 16) and 255) * ca; g += ((c shr 8) and 255) * ca; b += (c and 255) * ca
            }
            if (a <= 1e-4f) return 0
            return ((a * 255f).roundToInt().coerceIn(0, 255) shl 24) or ((r / a).roundToInt().coerceIn(0, 255) shl 16) or
                ((g / a).roundToInt().coerceIn(0, 255) shl 8) or (b / a).roundToInt().coerceIn(0, 255)
        }

        /** Blobs smaller than this share of a cell are specks. */
        private const val SPECK = 0.0015f

        private fun alpha(c: Int) = (c ushr 24) and 255

        private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "emoji" }

        /** A colour in words a model draws reliably. */
        fun colourName(hex: String): String {
            val c = hex.removePrefix("#").toInt(16)
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            return when {
                l > 0.88f -> "kaolin white"
                l < 0.16f -> "near-black"
                r > 170 && g > 140 && b < 110 -> "ochre yellow"
                r > 120 && g < 70 && b < 70 -> if (l < 0.35f) "deep camwood red" else "red"
                r > g && g > b && l < 0.35f -> "dark brown"
                r > g && g > b -> "terracotta"
                b > r && b > g -> if (l < 0.35f) "indigo" else "blue"
                g > r && g > b -> "jade green"
                r > 200 && b > 150 -> "rose pink"
                else -> "warm grey"
            }
        }
    }
}
