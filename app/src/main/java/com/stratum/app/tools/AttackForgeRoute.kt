package com.stratum.app.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stratum.app.shell.AppViewModel
import com.stratum.feature.forge.AttackForgeScreen
import com.stratum.feature.forge.AttackForgeStorage
import com.stratum.feature.forge.AttackForgeViewModel

/** The Forge of Will, keeping forged attacks in the game state so the carried ones join the hero's skills. */
@Composable
internal fun AttackForgeRoute(app: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val game = app.game
    val forge: AttackForgeViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AttackForgeViewModel(
                object : AttackForgeStorage {
                    override fun kept() = game.forgedAttacks.value.kept
                    override fun equipped() = game.forgedAttacks.value.equipped
                    override fun keep(code: String) = game.keepAttack(code)
                    override fun forget(code: String) = game.forgetAttack(code)
                    override fun toggleEquipped(code: String) = game.toggleAttackEquipped(code)
                },
            ) as T
        },
    )
    AttackForgeScreen(viewModel = forge, onBack = onBack, modifier = modifier)
}
