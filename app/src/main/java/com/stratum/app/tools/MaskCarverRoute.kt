package com.stratum.app.tools

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stratum.app.shell.AppViewModel
import com.stratum.feature.forge.MaskCarverScreen
import com.stratum.feature.forge.MaskCarverViewModel
import com.stratum.feature.forge.MaskMakerStorage

/** The mask carver, keeping carved masks on the device and dressing the hero in the one worn. */
@Composable
internal fun MaskCarverRoute(app: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val prefs = LocalContext.current.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val carver: MaskCarverViewModel = viewModel(
        factory = MaskCarverViewModel.factory(
            storage = object : MaskMakerStorage {
                override fun kept(): List<String> = prefs.getString(KEPT, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
                override fun save(codes: List<String>) { prefs.edit().putString(KEPT, codes.joinToString("\n")).apply() }
            },
            onWear = app.game::wearMask,
            wornCode = app.game.loadout.value.heroMask,
        ),
    )
    MaskCarverScreen(viewModel = carver, onBack = onBack, modifier = modifier)
}

private const val PREFS = "mask_carver"
private const val KEPT = "kept"
