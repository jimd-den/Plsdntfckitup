package com.stratum.app.tools

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stratum.app.shell.AppViewModel
import com.stratum.core.domain.micro.MicroModel
import com.stratum.engine.microbridge.ModelFactory
import com.stratum.engine.microvoxel.arch.Traditions
import com.stratum.engine.model.ArgbImage
import com.stratum.feature.forge.VoxelStudioScreen
import com.stratum.feature.forge.VoxelStudioStorage
import com.stratum.feature.forge.VoxelStudioViewModel

/** The model studio, over the device's model library, with the world's building generator and the photo picker. */
@Composable
internal fun VoxelStudioRoute(app: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = app.graph.microModels
    val studio: VoxelStudioViewModel = viewModel(
        factory = VoxelStudioViewModel.factory(
            storage = object : VoxelStudioStorage {
                override fun all(): List<MicroModel> = store.all()
                override fun save(model: MicroModel) = store.save(model)
                override fun delete(id: String) = store.delete(id)
            },
            generateBuilding = { seed, tradition -> ModelFactory.building(seed, tradition = tradition).first },
            traditions = Traditions.ids.map { id -> id to id.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) } },
            onLibraryChanged = app.game::modelsChanged,
        ),
    )
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val image = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // A photo is megapixels; the model is at most 128 voxels high, so read it small.
                val bytes = input.readBytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@use null
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                ArgbImage(bitmap.width, bitmap.height, pixels)
            }
        }.getOrNull()
        if (image == null) Toast.makeText(context, "That picture could not be read", Toast.LENGTH_SHORT).show()
        else studio.fromImage(image)
    }
    VoxelStudioScreen(
        viewModel = studio,
        onBack = onBack,
        onPickImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        modifier = modifier,
    )
}
