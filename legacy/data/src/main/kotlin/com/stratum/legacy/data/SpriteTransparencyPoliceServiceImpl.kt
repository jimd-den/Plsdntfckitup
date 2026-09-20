package com.stratum.legacy.data

import android.graphics.Bitmap
import android.util.Base64
import com.stratum.legacy.domain.SpritePoliceReport
import com.stratum.legacy.domain.SpriteSheetData
import com.stratum.legacy.domain.SpriteTransparencyPoliceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Implements SpriteTransparencyPoliceService adhering to Clean Architecture.
 * Bridges domain sprite sheet data with Android graphics transparency policing.
 */
class SpriteTransparencyPoliceServiceImpl : SpriteTransparencyPoliceService {

    override suspend fun policeSpriteSheet(
        sheet: SpriteSheetData,
        autoChromaKey: Boolean,
        tolerance: Float
    ): Pair<SpriteSheetData, SpritePoliceReport> = withContext(Dispatchers.Default) {
        val originalBitmap = SpriteFileTransferManager.getOrCreateSpriteBitmap(sheet)
        val (policedBitmap, report) = SpriteFileTransferManager.policeSpriteBitmap(
            sourceBitmap = originalBitmap,
            targetCols = sheet.columns,
            targetRows = sheet.rows,
            autoChromaKey = autoChromaKey,
            tolerance = tolerance
        )

        val baos = ByteArrayOutputStream()
        policedBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val policedBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        val policedSheet = sheet.copy(
            rawImageUriOrBase64 = policedBase64,
            frameWidth = report.frameWidth,
            frameHeight = report.frameHeight,
            columns = report.detectedGridCols,
            rows = report.detectedGridRows
        )

        Pair(policedSheet, report)
    }
}
