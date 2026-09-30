package com.stratum.app.shell

import com.stratum.app.nav.Route
import com.stratum.app.presetOf
import com.stratum.app.world.WorldLaunch
import com.stratum.core.domain.creation.InstantWorld
import com.stratum.core.domain.world.WorldRules

/**
 * Makes a new world's slot and puts the player in it: the one road every
 * "play something new" takes -- the new-world flow's Go, and the crew's Play
 * now -- so both are saved, listed and resumed the same way.
 *
 * With [described], the world is played at once in the look the words
 * suggest, and the world crew writes the rest in the background; its pack is
 * held until the player leaves this world.
 */
internal fun startNewWorld(
    app: AppViewModel,
    name: String,
    heroClassId: String?,
    rules: WorldRules,
    seed: Long,
    described: InstantWorld? = null,
    /** The scene the words (or a model reading them) asked for: the land, the towns and the danger. */
    scene: com.stratum.engine.microbridge.SceneSpec? = null,
) {
    val content = app.game.content.value
    val heroId = heroClassId ?: app.game.selectedHeroClassId()
    val identity = app.graph.worlds.create(
        name = name,
        presetName = presetOf(rules)?.name.orEmpty(),
        heroName = content.heroClasses.firstOrNull { it.id == heroId }?.name.orEmpty(),
        packIds = content.packs.map { it.id },
    )
    if (described != null) {
        app.game.saveStyle(described.stylePrompt)
        queueWorldCrew(app, described.prompt, identity.name) { pack -> app.deliver(pack) }
    }
    app.game.chooseHeroClass(heroId)
    val base = com.stratum.engine.microbridge.MicrovoxelTerrainGenerator.basePasses(content.terrain)
    val passes = scene?.takeIf { !it.isEmpty && base.isNotEmpty() }?.passSpecs(base)
    val launch = WorldLaunch.New(identity, heroId, scene?.rules(rules) ?: rules, seed, passes)
    // Replaced rather than pushed from a flow's last step: leaving the world
    // goes back to the hub, not to the step that started it.
    val stack = app.backStack
    if (stack.current is Route.Play.NewWorld) stack.replace(Route.Play.World(launch)) else stack.push(Route.Play.World(launch))
}
