package com.stratum.app.tools

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stratum.app.shell.AppViewModel
import com.stratum.feature.forge.MaskMakerScreen
import com.stratum.feature.forge.MaskMakerStorage
import com.stratum.feature.forge.MaskMakerViewModel

/** The mask maker, keeping masks on the device and dressing the hero in the one worn. */
@Composable
internal fun MaskMakerRoute(app: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val prefs = LocalContext.current.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val maker: MaskMakerViewModel = viewModel(
        factory = MaskMakerViewModel.factory(
            storage = object : MaskMakerStorage {
                override fun kept(): List<String> = prefs.getString(KEPT, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
                override fun save(codes: List<String>) { prefs.edit().putString(KEPT, codes.joinToString("\n")).apply() }
            },
            onWear = app.game::wearMask,
            wornCode = app.game.loadout.value.heroMask,
        ),
    )
    MaskMakerScreen(viewModel = maker, onBack = onBack, modifier = modifier)
}

private const val PREFS = "mask_maker"
private const val KEPT = "kept"
