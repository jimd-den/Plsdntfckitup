package com.stratum.app.tools

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stratum.app.AiWiring
import com.stratum.core.data.sprite.PoseFrameInspector
import com.stratum.core.data.sprite.PoseGuideRenderer
import com.stratum.core.data.sprite.PoseSheetComposer
import com.stratum.core.data.sprite.SpriteAtlasBaker
import com.stratum.core.data.sprite.SpriteExporter
import com.stratum.core.domain.ai.ImageReference
import com.stratum.core.domain.ai.PoseStep
import com.stratum.core.domain.sprite.OpenPoseImageReader
import com.stratum.core.domain.sprite.OpenPoseImport
import com.stratum.core.domain.sprite.OpenPoseJson
import com.stratum.core.domain.sprite.SpriteDrift
import com.stratum.feature.forge.PoseForgeScreen
import com.stratum.feature.forge.PoseForgeViewModel

/**
 * The pose forge, wired to the pose, sprite and guide stores and to the
 * system pickers and share sheet. The biggest wiring in the app, so it has a
 * file of its own.
 */
@Composable
internal fun PoseForgeRoute(
    ai: AiWiring,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val poseViewModel: PoseForgeViewModel = viewModel(
        factory = PoseForgeViewModel.factory(
            drawReference = { request, observer -> ai.generateBasePose(request, observer) },
            drawPose = { request, observer -> ai.generatePoseFrame(request, observer) },
            guideFor = { step, guides ->
                // The same skeleton the weapon rig reads, drawn: one source of
                // truth for where the body is, so the art and the sword can
                // never disagree about it. The length is this step's own
                // animation, or every frame past the sixth of a longer row is
                // guided with the cycle's last pose.
                guides.poseFor(state = step.state, index = step.index, frameCount = step.frameCount)
                    ?.let { pose -> PoseGuideRenderer.render(pose, style = guides.style)?.let { ImageReference(it) } }
            },
            readGuideImage = { bytes ->
                // Pose libraries ship the rendered skeleton, not its keypoints,
                // so the picture is read back to find the joints the weapon needs.
                val bitmap = SpriteAtlasBaker.decode(bytes)
                val pose = bitmap?.let {
                    val found = OpenPoseImageReader.read(pixels = SpriteAtlasBaker.pixelsOf(it), width = it.width, height = it.height)
                    it.recycle()
                    found?.let(OpenPoseImport::toPose)
                }
                pose?.let(OpenPoseImport::normalised)
            },
            readGuideJson = { text -> OpenPoseJson.parse(text)?.let(OpenPoseImport::toPose)?.let(OpenPoseImport::normalised) },
            loadGuides = ai.poseGuides::guidesFor,
            saveGuides = { setId, guides -> ai.poseGuides.save(setId, guides) },
            saveReference = ai.poses::saveReference,
            loadReference = ai.poses::reference,
            hasReference = ai.poses::hasReference,
            savePose = ai.poses::savePose,
            dropPose = ai.poses::deletePose,
            posesDrawn = ai.poses::keysIn,
            loadPose = ai.poses::pose,
            composeSheet = { setId, plan ->
                // Loaded by key rather than all at once: a full character is
                // forty 1024-pixel images, more than a phone holds decoded at
                // once. Drift is judged against the poses the art was drawn under.
                val guides = ai.poseGuides.guidesFor(setId)
                val composed = PoseSheetComposer.compose(
                    plan = plan,
                    expectedHeights = { state, count -> SpriteDrift.authoredHeightsFor(guides, state, count) },
                ) { key -> ai.poses.pose(setId, key) }
                composed?.let {
                    ai.sprites.save(it.sheet, it.bytes)
                    it.packed()
                }
            },
            savedCharacters = { ai.characterRepository.all().map { it.toSavedCharacter() } },
            deleteCharacter = { setId -> ai.characterRepository.delete(setId) },
            exportSheet = { sheetId, name ->
                val file = ai.sprites.bytesFor(sheetId)?.let {
                    SpriteExporter.exportBytes(context = context, bytes = it, fileName = "${SpriteExporter.slug(name)}_sheet.png", mimeType = "image/png")
                }
                file?.let { SpriteExporter.share(context, it, "image/png", name) }
                file != null
            },
            exportReference = { setId, name ->
                val file = ai.poses.reference(setId)?.let { SpriteExporter.exportReference(context, name, it) }
                file?.let { SpriteExporter.share(context, it, "image/png", name) }
                file != null
            },
            exportClip = { setId, name, key ->
                val file = ai.poses.clip(setId, key)?.let { SpriteExporter.exportClip(context, name, key, it) }
                file?.let { SpriteExporter.share(context, it, SpriteExporter.MIME_VIDEO, name) }
                file != null
            },
            clipsDrawn = ai.poses::clipKeysIn,
            drawClipRow = { request, observer -> ai.generateClipRow(request, observer) },
            saveClip = ai.poses::saveClip,
            exportPoses = { setId, name ->
                val poses = ai.poses.keysIn(setId).mapNotNull { key -> ai.poses.pose(setId, key)?.let { key to it } }.toMap()
                val file = SpriteExporter.exportPoses(context, name, poses)
                file?.let { SpriteExporter.share(context, it, "application/zip", name) }
                file != null
            },
            isProviderConfigured = ai::isConfigured,
            inspectPose = PoseFrameInspector::analyse,
            saveRunRecord = ai.poses::saveRunRecord,
            loadRunRecord = ai.poses::runRecord,
            runRecords = ai.poses::runRecords,
        ),
    )
    // Which frame an imported pose is destined for. The picker hands back a
    // Uri and nothing else, so the step is remembered across the trip out to
    // the system and back.
    var importingStep by remember { mutableStateOf<PoseStep?>(null) }
    val poseFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val step = importingStep
        importingStep = null
        if (uri != null && step != null) {
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes != null) poseViewModel.importGuideImage(step, bytes)
        }
    }

    PoseForgeScreen(
        viewModel = poseViewModel,
        modifier = modifier,
        onImportPose = { step ->
            importingStep = step
            poseFilePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onBack = onBack,
        onOpenSettings = onOpenSettings,
        referenceFor = { setId ->
            ai.poses.reference(setId)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        },
        sheetPreviewFor = { id -> ai.sprites.drawableBitmapFor(id)?.asImageBitmap() },
    )
}
