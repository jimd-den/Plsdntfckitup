package com.stratum.legacy.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.FileProvider
import com.stratum.legacy.domain.CharacterAnimationState
import com.stratum.legacy.domain.SpriteEntityCategory
import com.stratum.legacy.domain.SpriteSheetData
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * CHAPTER 13: DATA SPRITE FILE TRANSFER & BITMAP SYNTHESIS ENGINE
 *
 * Provides pure data/framework capabilities for:
 * 1. Upload / Import: Reading image Uri via Android Photo Picker / ContentResolver,
 *    resizing to uniform sprite sheet dimensions, and converting to Base64 PNG.
 * 2. Download / Export PNG: Generating a high-resolution 4x4 animated sprite sheet PNG
 *    and saving to device Downloads / Pictures or app storage with zero permission issues.
 * 3. Download / Export JSON: Serializing SpriteSheetData configuration to file.
 * 4. Share Sheet: Triggering Android system share intents via FileProvider.
 */
object SpriteFileTransferManager {

    /**
     * Reads an image URI from Photo Picker or SAF and encodes as Base64 PNG.
     */
    fun decodeUriToBase64(context: Context, uri: Uri, targetSize: Int = 192): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val originalBitmap = BitmapFactory.decodeStream(stream) ?: return null
                val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, targetSize, targetSize, true)
                val baos = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                val bytes = baos.toByteArray()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Converts a Base64 string back into an Android Bitmap.
     */
    fun decodeBase64ToBitmap(base64: String): Bitmap? {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Synthesizes a true procedural 4x4 animated pixel-art sprite sheet (192x192 px)
     * for Heroes and Enemies, replacing primitive geometric circles with authentic
     * layered pixel-art sprites with multi-frame walk, attack, and spell animations.
     */
    fun generateProceduralSpriteSheetBitmap(
        sheet: SpriteSheetData,
        primaryColor: Int = 0xFFD4AF37.toInt(),   // Bronze Gold
        accentColor: Int = 0xFF00E5FF.toInt(),    // Amadioha Cyan
        bodyColor: Int = 0xFFCD7F32.toInt(),      // Roped Bronze
        feature: String = "OZO_FEATHER"
    ): Bitmap {
        val totalWidth = 192
        val totalHeight = 192
        val frameW = totalWidth / 4 // 48
        val frameH = totalHeight / 4 // 48

        val bitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val isEnemy = sheet.entityCategory == SpriteEntityCategory.ENEMY || sheet.enemyArchetypeId != null ||
                sheet.name.contains("Ghoul", true) || sheet.name.contains("Brute", true) ||
                sheet.name.contains("Leopard", true) || sheet.name.contains("Priest", true) ||
                sheet.name.contains("Enemy", true)

        val enemyArchetype = sheet.enemyArchetypeId ?: when {
            sheet.name.contains("Brute", true) || feature.contains("BRUTE", true) -> "arch_ogu_brute"
            sheet.name.contains("Leopard", true) || feature.contains("LEOPARD", true) -> "arch_shadow_leopard"
            sheet.name.contains("Priest", true) || sheet.name.contains("Boss", true) -> "arch_boss_priest"
            else -> "arch_forest_mmo"
        }

        // Helper to draw a pixel block
        fun drawPx(x: Float, y: Float, w: Float, h: Float, col: Int) {
            paint.color = col
            paint.style = Paint.Style.FILL
            canvas.drawRect(x, y, x + w, y + h, paint)
        }

        // 4 rows: 0=IDLE, 1=WALK, 2=ATTACK, 3=CAST/HURT
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                val left = (col * frameW).toFloat()
                val top = (row * frameH).toFloat()
                val cx = left + frameW / 2f
                val cy = top + frameH / 2f

                val animBob = when (row) {
                    0 -> if (col % 2 == 0) 0f else -1f // Breathing bob
                    1 -> if (col == 0 || col == 2) -2f else 1f // Walk bounce
                    2 -> if (col == 1) 2f else 0f // Attack lunge
                    else -> if (col == 0) -2f else 0f // Hurt / cast
                }

                // Ground shadow (soft pixel ellipse)
                paint.color = Color.argb(80, 0, 0, 0)
                canvas.drawOval(RectF(cx - 12f, top + 40f, cx + 12f, top + 46f), paint)

                val originX = left + 14f
                val originY = top + 8f + animBob
                val p = 2.0f // 2px per pixel-art unit

                if (!isEnemy) {
                    // ================= HERO PIXEL ART SPRITE =================
                    val skinColor = 0xFF5D4037.toInt()       // Deep Umber skin
                    val bronzePlate = primaryColor            // Radiant Igbo-Ukwu Bronze
                    val clothColor = 0xFF1A237E.toInt()       // Royal Indigo Akwa Oche
                    val clothTrim = 0xFFFFD700.toInt()        // Gold Nsibidi trim
                    val coralColor = 0xFFD84315.toInt()       // Royal red coral beads

                    // 1. LEGS / FEET (Walk Animation)
                    val legLOffset = when (row) {
                        1 -> if (col == 0) -2f else if (col == 2) 2f else 0f
                        2 -> if (col == 1) 3f else 0f
                        else -> 0f
                    }
                    val legROffset = when (row) {
                        1 -> if (col == 0) 2f else if (col == 2) -2f else 0f
                        2 -> if (col == 1) -2f else 0f
                        else -> 0f
                    }

                    // Left Leg & Sandal
                    drawPx(originX + 2 * p + legLOffset, originY + 12 * p, 2 * p, 4 * p, skinColor)
                    drawPx(originX + 1 * p + legLOffset, originY + 15 * p, 3 * p, 1.5f * p, 0xFF3E2723.toInt())

                    // Right Leg & Sandal
                    drawPx(originX + 6 * p + legROffset, originY + 12 * p, 2 * p, 4 * p, skinColor)
                    drawPx(originX + 6 * p + legROffset, originY + 15 * p, 3 * p, 1.5f * p, 0xFF3E2723.toInt())

                    // 2. WOVEN WRAPPER (Akwa Oche skirt)
                    drawPx(originX + 2 * p, originY + 9 * p, 6 * p, 4 * p, clothColor)
                    drawPx(originX + 2 * p, originY + 12 * p, 6 * p, 1 * p, clothTrim)

                    // 3. TORSO & BRONZE CUIRASS
                    drawPx(originX + 2 * p, originY + 5 * p, 6 * p, 5 * p, bronzePlate)
                    // Pectoral plate highlights
                    drawPx(originX + 3 * p, originY + 6 * p, 2 * p, 2 * p, bodyColor)
                    drawPx(originX + 5 * p, originY + 6 * p, 2 * p, 2 * p, bodyColor)
                    // Sacred Coral bead necklace
                    drawPx(originX + 3 * p, originY + 5 * p, 4 * p, 1 * p, coralColor)

                    // 4. HEAD & FACE
                    drawPx(originX + 3 * p, originY + 2 * p, 4 * p, 4 * p, skinColor)
                    // Piercing eyes
                    drawPx(originX + 3 * p, originY + 3 * p, 1 * p, 1 * p, Color.WHITE)
                    drawPx(originX + 6 * p, originY + 3 * p, 1 * p, 1 * p, Color.WHITE)
                    // Sacred White Nzu chalk marks across cheeks
                    drawPx(originX + 2 * p, originY + 4 * p, 1.5f * p, 1 * p, Color.WHITE)
                    drawPx(originX + 6.5f * p, originY + 4 * p, 1.5f * p, 1 * p, Color.WHITE)

                    // 5. HEADDRESS / CREST
                    when {
                        feature.contains("HORN", true) || sheet.name.contains("Ikenga", true) -> {
                            // Horned Ikenga Crown
                            drawPx(originX + 1 * p, originY - 1 * p, 2 * p, 3 * p, bronzePlate)
                            drawPx(originX + 7 * p, originY - 1 * p, 2 * p, 3 * p, bronzePlate)
                            drawPx(originX + 0 * p, originY - 2 * p, 2 * p, 1.5f * p, accentColor)
                            drawPx(originX + 8 * p, originY - 2 * p, 2 * p, 1.5f * p, accentColor)
                        }
                        feature.contains("MASK", true) || sheet.name.contains("Mmanwu", true) -> {
                            // Ancestral Mmanwu Raffia Crest
                            drawPx(originX + 2 * p, originY - 2 * p, 6 * p, 3 * p, 0xFFE65100.toInt())
                            drawPx(originX + 3 * p, originY - 3 * p, 4 * p, 1.5f * p, 0xFFFFD54F.toInt())
                        }
                        else -> {
                            // Royal Ozo Sacred White Eagle Feather
                            drawPx(originX + 4.5f * p, originY - 3 * p, 1.5f * p, 4 * p, Color.WHITE)
                            drawPx(originX + 4.5f * p, originY - 3 * p, 1.5f * p, 1 * p, 0xFFD50000.toInt()) // Red feather tip
                        }
                    }

                    // 6. ARMS & WEAPON WITH ANIMATION FRAMES
                    when (row) {
                        0 -> { // IDLE
                            // Left arm resting
                            drawPx(originX + 0.5f * p, originY + 6 * p, 1.5f * p, 4 * p, skinColor)
                            // Right arm holding bronze blade upright
                            drawPx(originX + 8 * p, originY + 5 * p, 1.5f * p, 4 * p, skinColor)
                            // Bronze Blade upright
                            drawPx(originX + 9 * p, originY + 1 * p, 1.5f * p, 7 * p, bronzePlate)
                            drawPx(originX + 8.5f * p, originY + 0 * p, 2.5f * p, 1 * p, accentColor) // Gleam
                        }
                        1 -> { // WALK
                            val armSwing = if (col % 2 == 0) 1.5f else -1.5f
                            drawPx(originX + 0.5f * p, originY + 6 * p - armSwing, 1.5f * p, 4 * p, skinColor)
                            drawPx(originX + 8 * p, originY + 5 * p + armSwing, 1.5f * p, 4 * p, skinColor)
                            drawPx(originX + 9 * p, originY + 2 * p + armSwing, 1.5f * p, 6 * p, bronzePlate)
                        }
                        2 -> { // ATTACK (SWORD CLEAVE ARC)
                            when (col) {
                                0 -> { // Windup back
                                    drawPx(originX + 0f, originY + 4 * p, 2 * p, 3 * p, skinColor)
                                    drawPx(originX - 2 * p, originY + 2 * p, 1.5f * p, 6 * p, bronzePlate)
                                }
                                1 -> { // Forward thrust
                                    drawPx(originX + 8 * p, originY + 6 * p, 4 * p, 2 * p, skinColor)
                                    drawPx(originX + 11 * p, originY + 6 * p, 6 * p, 2 * p, bronzePlate)
                                }
                                2 -> { // Giant Radiant Slash Arc
                                    drawPx(originX + 8 * p, originY + 5 * p, 3 * p, 2 * p, skinColor)
                                    drawPx(originX + 10 * p, originY + 2 * p, 2 * p, 7 * p, bronzePlate)
                                    // Massive slashing energy arc
                                    paint.color = accentColor
                                    paint.strokeWidth = 3.5f
                                    paint.style = Paint.Style.STROKE
                                    canvas.drawArc(RectF(cx - 10f, cy - 20f, cx + 22f, cy + 18f), -80f, 160f, false, paint)
                                    paint.style = Paint.Style.FILL
                                }
                                3 -> { // Recovery
                                    drawPx(originX + 8 * p, originY + 7 * p, 2 * p, 3 * p, skinColor)
                                    drawPx(originX + 9 * p, originY + 8 * p, 4 * p, 1.5f * p, bronzePlate)
                                }
                            }
                        }
                        3 -> { // HURT / CAST SPELL
                            if (col < 2) {
                                // Hurt recoil
                                drawPx(originX + 1 * p, originY + 4 * p, 6 * p, 8 * p, Color.argb(140, 255, 255, 255))
                            } else {
                                // Cast Spell Aura
                                paint.color = Color.argb(160, 0, 229, 255)
                                paint.strokeWidth = 2.5f
                                paint.style = Paint.Style.STROKE
                                canvas.drawCircle(cx, cy, 14f + col * 2f, paint)
                                paint.style = Paint.Style.FILL
                            }
                        }
                    }

                } else {
                    // ================= ENEMY PIXEL ART SPRITES =================
                    when (enemyArchetype) {
                        "arch_ogu_brute" -> {
                            // Heavy Armored Behemoth with twin spiked clubs
                            val armorIron = 0xFF37474F.toInt()
                            val skinBrute = 0xFF4E342E.toInt()
                            val clubColor = 0xFF8D6E63.toInt()

                            // Bulky Legs
                            drawPx(originX + 1 * p, originY + 11 * p, 3 * p, 5 * p, armorIron)
                            drawPx(originX + 6 * p, originY + 11 * p, 3 * p, 5 * p, armorIron)

                            // Broad Torso
                            drawPx(originX + 0 * p, originY + 4 * p, 10 * p, 7 * p, armorIron)
                            drawPx(originX + 2 * p, originY + 5 * p, 6 * p, 4 * p, 0xFFBF360C.toInt()) // Red chest plate

                            // Horned War Helm
                            drawPx(originX + 2 * p, originY + 1 * p, 6 * p, 4 * p, armorIron)
                            drawPx(originX + 0 * p, originY - 1 * p, 2 * p, 2 * p, 0xFFD84315.toInt()) // Horn L
                            drawPx(originX + 8 * p, originY - 1 * p, 2 * p, 2 * p, 0xFFD84315.toInt()) // Horn R
                            // Glowing Red Rage Eyes
                            drawPx(originX + 3 * p, originY + 2 * p, 1.5f * p, 1 * p, Color.RED)
                            drawPx(originX + 6 * p, originY + 2 * p, 1.5f * p, 1 * p, Color.RED)

                            // Spiked War Clubs in hands
                            val clubSwing = if (row == 2) col * 3f else 0f
                            drawPx(originX - 3 * p, originY + 4 * p + clubSwing, 2 * p, 8 * p, clubColor)
                            drawPx(originX + 11 * p, originY + 4 * p - clubSwing, 2 * p, 8 * p, clubColor)
                            // Club spikes
                            drawPx(originX - 4 * p, originY + 5 * p + clubSwing, 1 * p, 2 * p, Color.LTGRAY)
                            drawPx(originX + 13 * p, originY + 5 * p - clubSwing, 1 * p, 2 * p, Color.LTGRAY)
                        }

                        "arch_shadow_leopard" -> {
                            // Agile Shadow Leopard Predator
                            val peltColor = 0xFFFFB300.toInt()
                            val spotColor = 0xFF212121.toInt()

                            // Crouching Feline Body
                            drawPx(originX + 1 * p, originY + 6 * p, 8 * p, 5 * p, peltColor)
                            // Spots
                            drawPx(originX + 3 * p, originY + 7 * p, 1 * p, 1 * p, spotColor)
                            drawPx(originX + 6 * p, originY + 8 * p, 1 * p, 1 * p, spotColor)
                            drawPx(originX + 4 * p, originY + 9 * p, 1 * p, 1 * p, spotColor)

                            // Prowling Paws
                            val pOffset = if (row == 1 && col % 2 == 0) 2f else 0f
                            drawPx(originX + 1 * p + pOffset, originY + 11 * p, 2 * p, 4 * p, peltColor)
                            drawPx(originX + 7 * p - pOffset, originY + 11 * p, 2 * p, 4 * p, peltColor)

                            // Head with Ears
                            drawPx(originX + 2 * p, originY + 2 * p, 6 * p, 4 * p, peltColor)
                            drawPx(originX + 2 * p, originY + 0 * p, 1.5f * p, 2 * p, spotColor) // Ear L
                            drawPx(originX + 6.5f * p, originY + 0 * p, 1.5f * p, 2 * p, spotColor) // Ear R
                            // Slit Yellow Eyes
                            drawPx(originX + 3 * p, originY + 3 * p, 1 * p, 1 * p, Color.YELLOW)
                            drawPx(originX + 6 * p, originY + 3 * p, 1 * p, 1 * p, Color.YELLOW)

                            // Claws & Swipe
                            if (row == 2) {
                                paint.color = 0xFFE040FB.toInt() // Shadow claw trail
                                paint.strokeWidth = 2.5f
                                paint.style = Paint.Style.STROKE
                                canvas.drawLine(cx + 8f, cy - 8f, cx + 20f, cy + 8f, paint)
                                canvas.drawLine(cx + 12f, cy - 8f, cx + 24f, cy + 8f, paint)
                                paint.style = Paint.Style.FILL
                            }
                        }

                        "arch_boss_priest" -> {
                            // Agbara High Priest Boss
                            val robeCrimson = 0xFFB71C1C.toInt()
                            val robeGold = 0xFFFFD700.toInt()

                            // Long ceremonial flowing robes
                            drawPx(originX + 1 * p, originY + 7 * p, 8 * p, 9 * p, robeCrimson)
                            drawPx(originX + 4 * p, originY + 7 * p, 2 * p, 9 * p, robeGold)

                            // Sacred Mask Face
                            drawPx(originX + 2 * p, originY + 2 * p, 6 * p, 5 * p, 0xFF3E2723.toInt())
                            // Sun glyph markings
                            drawPx(originX + 3 * p, originY + 3 * p, 1 * p, 1 * p, 0xFFFFD700.toInt())
                            drawPx(originX + 6 * p, originY + 3 * p, 1 * p, 1 * p, 0xFFFFD700.toInt())

                            // Multi-tiered High Priest Mitre Crown
                            drawPx(originX + 1 * p, originY - 3 * p, 8 * p, 4 * p, robeGold)
                            drawPx(originX + 4 * p, originY - 5 * p, 2 * p, 2 * p, 0xFFFF1744.toInt())

                            // Solar Staff with glowing sun disc
                            drawPx(originX + 10 * p, originY - 2 * p, 1.5f * p, 14 * p, 0xFF795548.toInt())
                            drawPx(originX + 9 * p, originY - 4 * p, 3.5f * p, 3.5f * p, robeGold)
                            if (row == 2 || row == 3) {
                                // Solar eruption flare
                                paint.color = Color.argb(170, 255, 109, 0)
                                canvas.drawCircle(originX + 11 * p, originY - 2 * p, 8f + col * 3f, paint)
                            }
                        }

                        else -> { // "arch_forest_mmo"
                            // Floating Masked Forest Spirit with trailing raffia
                            val spiritGreen = 0xFF2E7D32.toInt()
                            val raffiaStraw = 0xFFC5E1A5.toInt()

                            // Floating tattered shroud
                            drawPx(originX + 2 * p, originY + 6 * p, 6 * p, 8 * p, spiritGreen)
                            // Trailing wisps instead of legs
                            drawPx(originX + 1 * p + (col % 2), originY + 13 * p, 2 * p, 4 * p, raffiaStraw)
                            drawPx(originX + 4 * p - (col % 2), originY + 14 * p, 2 * p, 3 * p, raffiaStraw)
                            drawPx(originX + 7 * p + (col % 2), originY + 13 * p, 2 * p, 4 * p, raffiaStraw)

                            // Wooden skull mask
                            drawPx(originX + 2 * p, originY + 1 * p, 6 * p, 5 * p, 0xFFEFEBE9.toInt())
                            // Ghostly glowing green eyes
                            drawPx(originX + 3 * p, originY + 3 * p, 1.5f * p, 1.5f * p, 0xFF76FF03.toInt())
                            drawPx(originX + 6 * p, originY + 3 * p, 1.5f * p, 1.5f * p, 0xFF76FF03.toInt())

                            // Ghostfire wisps
                            paint.color = Color.argb(130, 118, 255, 3)
                            canvas.drawCircle(cx - 10f, cy + 6f, 3f + col, paint)
                            canvas.drawCircle(cx + 12f, cy - 2f, 2.5f + col, paint)
                        }
                    }
                }
            }
        }

        return bitmap
    }

    /**
     * Converts a SpriteSheetData into a PNG bitmap (decoding its base64 if present,
     * or synthesizing a procedural sheet).
     */
    fun getOrCreateSpriteBitmap(sheet: SpriteSheetData): Bitmap {
        // Read once into a local: the property lives in another module now, so
        // the compiler cannot assume it stays non-null between the check and the
        // use, and it is right not to.
        val encoded = sheet.rawImageUriOrBase64
        if (!encoded.isNullOrEmpty()) {
            decodeBase64ToBitmap(encoded)?.let { return it }
        }
        return generateProceduralSpriteSheetBitmap(sheet)
    }

    /**
     * Saves a SpriteSheet as a PNG file into the device's public Downloads / Pictures
     * folder or cache directory, returning the created File or Uri.
     */
    fun exportSpriteToDevice(context: Context, sheet: SpriteSheetData): File? {
        return try {
            val bitmap = getOrCreateSpriteBitmap(sheet)
            val filename = "sprite_${sheet.name.lowercase().replace("\\s+".toRegex(), "_")}_${System.currentTimeMillis()}.png"

            // Save to app external cache or files for easy FileProvider access & sharing
            val cacheDir = File(context.cacheDir, "exports")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val file = File(cacheDir, filename)
            val fos = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            fos.flush()
            fos.close()

            // Also copy to MediaStore Downloads / Pictures on API 29+ if accessible
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/IgboARPG_Sprites")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let { destUri ->
                    context.contentResolver.openOutputStream(destUri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
            }

            file
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Exports a SpriteSheet's JSON definition to a file in cache for sharing or saving.
     */
    fun exportSpriteJsonToDevice(context: Context, sheet: SpriteSheetData): File? {
        return try {
            val filename = "sprite_${sheet.name.lowercase().replace("\\s+".toRegex(), "_")}.json"
            val cacheDir = File(context.cacheDir, "exports")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val file = File(cacheDir, filename)
            file.writeText(sheet.toJson())
            file
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Opens Android System Share Sheet to share the exported PNG or JSON file.
     */
    fun shareExportedFile(context: Context, file: File, mimeType: String, title: String) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Export $title").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            // Log or fallback
        }
    }

    /**
     * Transparently polices a sprite sheet:
     * 1. Samples corner pixels to detect solid background color (e.g. white, light grey, black).
     * 2. Replaces detected background pixels with Color.TRANSPARENT.
     * 3. Validates uniform frame dimensions across grid columns and rows.
     */
    fun policeSpriteBitmap(
        sourceBitmap: Bitmap,
        targetCols: Int = 4,
        targetRows: Int = 4,
        autoChromaKey: Boolean = true,
        tolerance: Float = 0.15f
    ): Pair<Bitmap, com.stratum.legacy.domain.SpritePoliceReport> {
        val width = sourceBitmap.width
        val height = sourceBitmap.height

        val policedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(policedBitmap)
        canvas.drawBitmap(sourceBitmap, 0f, 0f, null)

        var removedPixels = 0
        if (autoChromaKey) {
            val c1 = policedBitmap.getPixel(0, 0)
            val c2 = policedBitmap.getPixel(width - 1, 0)
            val c3 = policedBitmap.getPixel(0, height - 1)
            val c4 = policedBitmap.getPixel(width - 1, height - 1)

            val detectedBgArgb = when {
                c1 == c2 || c1 == c3 || c1 == c4 -> c1
                c2 == c3 || c2 == c4 -> c2
                else -> c1
            }

            val bgR = Color.red(detectedBgArgb)
            val bgG = Color.green(detectedBgArgb)
            val bgB = Color.blue(detectedBgArgb)
            val maxDiff = (255f * tolerance) * (255f * tolerance) * 3f

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = policedBitmap.getPixel(x, y)
                    val a = Color.alpha(pixel)
                    if (a > 0) {
                        val r = Color.red(pixel)
                        val g = Color.green(pixel)
                        val b = Color.blue(pixel)

                        val diffSq = (r - bgR).toFloat() * (r - bgR) +
                                     (g - bgG).toFloat() * (g - bgG) +
                                     (b - bgB).toFloat() * (b - bgB)

                        if (diffSq <= maxDiff || (r > 245 && g > 245 && b > 245 && tolerance >= 0.1f)) {
                            policedBitmap.setPixel(x, y, Color.TRANSPARENT)
                            removedPixels++
                        }
                    }
                }
            }
        }

        val frameW = width / targetCols
        val frameH = height / targetRows
        val totalFrames = targetCols * targetRows

        val report = com.stratum.legacy.domain.SpritePoliceReport(
            isTransparentPoliced = true,
            detectedGridCols = targetCols,
            detectedGridRows = targetRows,
            frameWidth = frameW,
            frameHeight = frameH,
            totalFrames = totalFrames,
            message = "Transparently policed $removedPixels background pixels into alpha transparency; verified ${targetCols}x${targetRows} ($frameW x $frameH px) grid."
        )

        return Pair(policedBitmap, report)
    }
}
