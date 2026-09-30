package com.stratum.app.shell

import android.content.Context
import com.stratum.app.AiWiring
import com.stratum.app.GraphicsWiring
import com.stratum.app.PluginWiring
import com.stratum.app.world.SavedWorlds
import com.stratum.core.data.hero.CustomClassStore
import com.stratum.core.data.save.FileHeroSaveStore
import com.stratum.core.data.settings.WorldStyleStore
import com.stratum.core.data.save.WorldLibrary
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * Every long-lived adapter the app is wired from, built once per process.
 *
 * These used to be `remember`ed inside the shell composable, which tied a
 * disk store's lifetime to a composition and rebuilt them all whenever the
 * activity was. Here they are plain objects owned by [AppViewModel], and the
 * screens are handed what they need.
 */
class AppGraph(context: Context, scope: CoroutineScope) {
    val ai = AiWiring(context)

    /** Installed plugins: imported games, the crew's packs and the player's own creations. */
    val plugins = PluginWiring(context, ai.sprites)

    val graphics = GraphicsWiring(context)

    /** Classes the player built, loaded as a pack of their own. */
    val classes = CustomClassStore(context)

    /** Each class keeps its own hero, carried from world to world. */
    val heroes = FileHeroSaveStore(File(context.filesDir, "heroes"))

    /** The look the world is worn in. */
    val styles = WorldStyleStore(context)

    /** Where painted textures live. */
    val forgeDirectory = File(context.filesDir, "forge")

    /** The model studio's microvoxel models. */
    val microModels = com.stratum.core.data.micro.MicroModelStore(File(context.filesDir, "micro-models"))

    /** Saved worlds, one folder each under the app's files. */
    val worlds = SavedWorlds(WorldLibrary.inFiles(context.filesDir), scope)

    /** Which play hints the player has already seen, so each teaches once. */
    val hints = HintStore(context)
}
