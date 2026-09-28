package com.stratum.feature.play

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.art.ArtDirection
import com.stratum.core.domain.art.BiomeArtKit
import com.stratum.core.domain.art.StyleLexicon
import com.stratum.core.domain.art.StyleSheetArtDirector
import com.stratum.core.domain.art.WorldArtDirector
import com.stratum.core.domain.art.WorldTime
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.crafting.CurrencyDefinition
import com.stratum.core.domain.difficulty.Difficulty
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.item.ItemInstance
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.session.HeroSave
import com.stratum.core.domain.session.WorldIdentity
import com.stratum.core.domain.session.WorldSave
import com.stratum.core.domain.session.WorldSaveRepository
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.sprite.AnimationPlayback
import com.stratum.core.domain.tabletop.ActiveBoon
import com.stratum.core.domain.tabletop.SkillCheck
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.World
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import com.stratum.engine.scene.quality.QualityTier
import com.stratum.engine.world.AttackReport
import com.stratum.engine.world.FlaskResult
import com.stratum.engine.world.BuildResult
import com.stratum.engine.world.BuildTool
import com.stratum.engine.world.CheckAttempt
import com.stratum.engine.scene.forge.ForgeProgress
import com.stratum.engine.world.CombatEvent
import com.stratum.engine.world.CraftResult
import com.stratum.engine.world.Held
import com.stratum.engine.world.PassiveResult
import com.stratum.engine.world.SupportResult
import com.stratum.engine.world.SurvivalResult
import com.stratum.engine.world.RealmEvent
import com.stratum.engine.world.RealmResult
import com.stratum.core.domain.strategy.Affordability
import com.stratum.core.domain.strategy.Colony
import com.stratum.core.domain.strategy.FollowerOrder
import com.stratum.engine.world.DodgeResult
import com.stratum.engine.world.EquipResult
import com.stratum.engine.world.FeedbackMark
import com.stratum.engine.world.GroundInsert
import com.stratum.engine.world.GroundLoot
import com.stratum.engine.world.HeldInsert
import com.stratum.engine.world.IsometricProjection
import com.stratum.engine.world.MineResult
import com.stratum.engine.world.PlaceRejection
import com.stratum.engine.world.PlaceResult
import com.stratum.engine.world.ReviveResult
import com.stratum.engine.world.SocketResult
import com.stratum.engine.world.WorldSession
import com.stratum.feature.play.gl.AndroidImageCodec
import com.stratum.feature.play.gl.ForgedKits
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives one play session.
 *
 * The view model owns no game rules. It forwards intent to [WorldSession] and
 * republishes the resulting snapshot, so the rules stay in the pure engine where
 * they can be tested without Android.
 */
class PlayViewModel(
    private val content: AssembledContent,
    /** The world to make when nothing is resumed; a resumed world brings its own. */
    config: WorldConfig,
    heroClassId: String? = null,
    /**
     * Resolves an actor to drawable art. Supplied by the composition root,
     * because decoding a bitmap is a platform concern and this view model is
     * otherwise free of one.
     */
    private val spriteResolver: (SpriteKey) -> DrawableSprite? = { null },
    /**
     * What the player asked their world to look like, in their own words.
     *
     * Empty is the house style. Anything else is read by [StyleLexicon] into a
     * set of rendering rules, so "dark", "kawaii" or "a weird old woodblock
     * print" are all the same amount of work and none of them touch the
     * simulation.
     */
    private val stylePrompt: String = "",
    /**
     * Draws new art for a style the player typed, or null when no image model
     * is configured. OpenRouter with `meta/muse-image` in the shipped app.
     */
    private val imageModel: com.stratum.core.domain.ai.ImageModelPort? = null,
    /** Where kits forged on this device are kept between runs. */
    private val kitDirectory: File? = null,
    /** Texture folders of imported packs, drawn over whichever kit the style picks. */
    private val kitOverlays: List<File> = emptyList(),
    /** The player's graphics choice when the run began; null lets the device decide. */
    quality: QualityTier? = null,
    /** Keeps a new graphics choice for next time. Supplied by the composition root. */
    private val saveQuality: (QualityTier?) -> Unit = {},
    /** Keeps the world style for next time, so a painted style is still worn after a restart. */
    private val saveStyle: (String) -> Unit = {},
    /** The character carried in from earlier play, or null for a new one. */
    hero: HeroSave? = null,
    /** Keeps the character for next time, in the roster that carries heroes between worlds. Called off the main thread. */
    saveHero: (HeroSave) -> Unit = {},
    /** Prop blocks drawn as generated 3D models, by block id. */
    private val propModels: Map<String, com.stratum.engine.scene.PropModel> = emptyMap(),
    /** Structures made from generated models, which the build tray can raise. */
    private val blueprints: List<com.stratum.core.domain.content.VoxelBlueprint> = emptyList(),
    /** A saved world to resume, or null to make a new one from [config] and [hero]. */
    resume: WorldSave? = null,
    /** Where this world is kept, or null for a run with no save slot (the hero is still kept). */
    private val worlds: WorldSaveRepository? = null,
    /** The slot a new world saves into; ignored when resuming. Null makes one up. */
    slot: WorldIdentity? = null,
    /**
     * Where saves are written. Outlives the view model on purpose: the save
     * taken as the screen closes must land after the screen is gone.
     */
    saveScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Ids for worlds this screen starts itself: a new run, or a tier opened. */
    private val newWorldId: () -> String = { java.util.UUID.randomUUID().toString() },
) : ViewModel() {

    private val initialQuality = quality

    /** The hero class this run plays: a resumed world's own, else the one asked for. */
    private val heroClassId: String? = resume?.heroClassId ?: heroClassId

    /** The world's settings now: a sandbox that lifts its caps is rebuilt with different ones. */
    private var worldConfig = resume?.config ?: config

    /**
     * Replaced wholesale by [newRun]. Every reader goes through this field
     * rather than capturing it, so starting a fresh world cannot leave a lambda
     * pointing at the world the player just left.
     */
    private var session = resume?.let { WorldSession.restore(content, it) } ?: WorldSession(content, worldConfig, this.heroClassId, hero = hero)

    /** The slot this world saves into. Changes only when the player leaves for a new world from inside play. */
    private var identity: WorldIdentity = resume?.identity ?: slot ?: WorldIdentity(
        id = newWorldId(), name = DEFAULT_WORLD_NAME, createdAt = System.currentTimeMillis(), heroName = heroName(),
    )

    /** The last save that landed, read into the state on the next publish so the cue never races the game loop. */
    private val lastSave = MutableStateFlow<SaveNotice?>(null)

    private val saver = WorldSaver(worlds, saveHero, saveScope, onSaved = { lastSave.value = it })

    /**
     * The ingredients each region is drawn from, read out of the loaded packs.
     *
     * Derived rather than authored, so a pack a model generated a minute ago is
     * art directed exactly as well as the one that shipped with the game.
     */
    private val artKits: Map<String, BiomeArtKit> = content.packs
        .flatMap { BiomeArtKit.deriveAll(it).entries }
        .associate { it.key to it.value }

    private var artDirector: WorldArtDirector = directorFor(stylePrompt)

    /** Accumulated play time, which is what the light and the weather drift on. */
    private var elapsed = 0f

    /** The current roll of the current prompt, so a reroll is the next one. */
    private var styleSeed: Long = stylePrompt.lowercase().hashCode().toLong()

    private val _state = MutableStateFlow(initialState(content))
    val state: StateFlow<PlayUiState> = _state.asStateFlow()

    val world: World get() = session.world

    private var miningJob: kotlinx.coroutines.Job? = null
    private var loopJob: kotlinx.coroutines.Job? = null

    /**
     * Held as fields rather than written inline in [publish].
     *
     * A method reference allocates a new object each time it is evaluated, so
     * writing `session::flashFor` into the state every tick made the state
     * unequal to its predecessor no matter what else had changed — and the whole
     * HUD recomposed twenty times a second for nothing.
     */
    private val flashFor: (String) -> Float = { id -> session.flashFor(id) }
    private val impactFor: (String) -> Float = { id -> session.impactFor(id) }
    private val animationFor: (String) -> AnimationPlayback = { id -> session.animationFor(id) }
    private val insertFor: (String) -> com.stratum.core.domain.item.InsertDefinition? =
        { id -> session.insertOrNull(id) }
    private val rarityColors: (com.stratum.core.domain.item.ItemRarity) -> Long =
        { rarity -> session.content.rarityColor(rarity) }

    /** Damage types by id, for the gear panel's resistance lines and the sandbox's pickers. Above [init]: the first publish reads them. */
    private val damageTypeNames: Map<String, String> = content.damageTypes.associate { it.id to it.name }
    private val damageTypeChoices = content.damageTypes.map { NamedChoice(it.id, it.name, color = it.color) }

    init {
        // A new world is written at once, so it is in the list even if the app dies in its first minute.
        if (resume == null && worlds != null) persist(SaveReason.NEW_WORLD) else saver.mark(elapsed, session.player.level)
        publish()
        startLoop()
    }

    /**
     * The game loop. Monsters only move because something advances them, so the
     * world is simulated whether or not the player touches the screen.
     *
     * Paced by the display rather than by a timer. A fixed 50ms sleep capped the
     * whole game at twenty frames a second on a panel perfectly capable of
     * ninety, and it lied about how much time had passed: the world advanced by
     * exactly 50ms whether the frame took 10ms or 200.
     *
     * The step is the real elapsed time, clamped. Clamping means a stall makes
     * the world run slow for a moment rather than teleporting the player through
     * a wall — falling behind is recoverable, tunnelling is not.
     */
    /**
     * Changes what the world looks like, without changing the world.
     *
     * The seed comes from the prompt, so asking for the same thing twice gives
     * the same world back; passing a different one is the reroll. Nothing here
     * touches a block, a monster or the player's bag — a restyle is a change of
     * opinion about colour, not a new game.
     */
    fun restyle(prompt: String, seed: Long = prompt.lowercase().hashCode().toLong()) {
        artDirector = directorFor(prompt, seed)
        styleSeed = seed
        _state.value = _state.value.copy(
            artDirector = artDirector,
            stylePrompt = prompt,
            styleSummary = artDirector.direction.summary,
            kit = ForgedKits.kitFor(artDirector.direction, kitDirectory),
        )
        saveStyle(prompt)
    }

    /**
     * Paints the current style's asset kit with the image model.
     *
     * One call per style, a dozen or so images, cents rather than dollars: the
     * forge plans only the ground, cliff faces, walls and prop kinds of the
     * region the player is standing in, never the world. What it finishes is
     * saved and swapped in as it arrives, so the world repaints itself while
     * the player watches, and anything that fails simply stays as it was.
     */
    /**
     * Changes how hard the renderer works, and remembers it. Null hands the
     * choice back to the device. Takes effect on the next frame; nothing about
     * the world changes.
     */
    fun chooseQuality(tier: QualityTier?) {
        _state.value = _state.value.copy(quality = tier)
        saveQuality(tier)
    }

    /**
     * Paints this style's textures, the part of the world the lighting
     * restyle cannot change. Runs in the background while the player plays;
     * each finished texture is swapped in as it lands, and the Style panel
     * shows how far along it is.
     */
    fun forgeStyle() {
        val model = imageModel ?: return publish(message = "Add an OpenRouter key in Model provider to paint textures")
        val root = kitDirectory ?: return publish(message = "No storage for forged art")
        if (_state.value.forgeProgress?.isFinished == false) return
        val direction = artDirector.direction
        val biome = session.currentBiome.id
        val pack = content.packs.firstOrNull { p -> p.biomes.any { it.id == biome } } ?: content.packs.first()
        val runner = TextureForgeRunner(model, root)
        val plan = runner.plan(direction, pack, setOf(biome), includeActors = true)
        if (plan.isEmpty) {
            _state.value = _state.value.copy(kit = plan.kit)
            return publish(message = "This style is already painted")
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val finished = runner.run(plan) { progress ->
                _state.value = _state.value.copy(forgeProgress = progress, kit = plan.kit)
            }
            // Back on the main thread: publishing reads the session, which the
            // game loop mutates there.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                publish(message = "Painted ${finished.made.size} of ${plan.orders.size}")
            }
        }
    }

    /** The same request again, somewhere else. Asking twice should not be futile. */
    fun rerollStyle() {
        restyle(_state.value.stylePrompt, styleSeed + 1)
    }

    fun toggle3D() {
        _state.value = _state.value.copy(use3D = !_state.value.use3D)
    }

    fun toggleStyle() {
        _state.value = _state.value.copy(styleOpen = !_state.value.styleOpen)
    }

    // ---- shaping the world ---------------------------------------------------

    /** Counts requests to reshape the land, so only the newest one lands when several overlap. */
    private var shapeTicket = 0

    /** Opens or closes the World panel. Like the anvil, the world keeps running behind it. */
    fun toggleWorldShaper() {
        val panel = _state.value.worldShaper
        _state.value = _state.value.copy(worldShaper = shaperPanel().copy(open = !panel.open))
        publish()
    }

    /** One knob of one stage, applied now. */
    fun setTerrainOption(stageId: String, key: String, value: String) {
        val hot = session.hotTerrain ?: return
        val stage = hot.passes.firstOrNull { it.id == stageId } ?: com.stratum.engine.microvoxel.gen.StageSpec(stageId)
        reshape(com.stratum.engine.microbridge.MicrovoxelTerrainGenerator.withStage(hot.passes, stage.copy(options = stage.options + (key to value))))
    }

    /** The land stage's options replaced by a whole shape at once: plains, hills, terraces. */
    fun shapeLand(options: Map<String, String>) {
        val hot = session.hotTerrain ?: return
        val land = hot.passes.firstOrNull { it.id == LAND_STAGE } ?: return
        reshape(com.stratum.engine.microbridge.MicrovoxelTerrainGenerator.withStage(hot.passes, land.copy(options = land.options + options)))
    }

    /** Switches a stage on (with its defaults) or off. */
    fun toggleTerrainStage(stageId: String, enabled: Boolean) {
        val hot = session.hotTerrain ?: return
        reshape(
            if (enabled) com.stratum.engine.microbridge.MicrovoxelTerrainGenerator.withStage(hot.passes, com.stratum.engine.microvoxel.gen.StageSpec(stageId))
            else hot.passes.filter { it.id != stageId },
        )
    }

    /** A stage back to its defaults. */
    fun resetTerrainStage(stageId: String) {
        val hot = session.hotTerrain ?: return
        reshape(com.stratum.engine.microbridge.MicrovoxelTerrainGenerator.withStage(hot.passes, com.stratum.engine.microvoxel.gen.StageSpec(stageId)))
    }

    /**
     * Builds the new land on a worker, then swaps it in between frames: the
     * slow part (every stage set up, a chunk generated to prove it works)
     * never stalls the game, and the game thread only regenerates what is
     * near. A request overtaken by a newer one is dropped.
     */
    private fun reshape(passes: List<com.stratum.engine.microvoxel.gen.StageSpec>) {
        val target = session
        val hot = target.hotTerrain ?: return
        val ticket = ++shapeTicket
        _state.value = _state.value.copy(worldShaper = _state.value.worldShaper.copy(busy = true))
        viewModelScope.launch {
            val prepared = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { hot.prepare(passes) }
            if (ticket != shapeTicket || target !== session) return@launch
            val problem = target.installTerrain(prepared)
            _state.value = _state.value.copy(worldShaper = shaperPanel().copy(open = _state.value.worldShaper.open, busy = false))
            if (problem == null) persist(SaveReason.AUTOSAVE)
            publish(message = problem ?: "The land reshapes around you")
        }
    }

    /** The panel as the live generator describes it. */
    private fun shaperPanel(): WorldShaperPanel {
        val hot = session.hotTerrain ?: return WorldShaperPanel()
        val running = hot.passes.associateBy { it.id }
        return WorldShaperPanel(
            available = true,
            stages = hot.catalogue().map { info ->
                ShaperStage(info.id, info.title, info.summary, info.id in running, info.params, running[info.id]?.options.orEmpty())
            },
            revision = hot.revision,
        )
    }

    // ---- survival ----------------------------------------------------------

    fun toggleCamp() {
        _state.value = _state.value.copy(campOpen = !_state.value.campOpen)
        publish()
    }

    fun eat(itemId: String) = publish(message = describe(session.consume(itemId)))

    fun drink() = publish(message = describe(session.drink()))

    fun make(recipeId: String) = publish(message = describe(session.make(recipeId)))

    private fun describe(result: SurvivalResult): String = when (result) {
        is SurvivalResult.Consumed -> "Ate ${result.food.name}"
        is SurvivalResult.Drank -> "You drink deep"
        is SurvivalResult.Made -> "Made ${result.recipe.name.lowercase()}"
        SurvivalResult.NoneHeld -> "You have none"
        SurvivalResult.NoWaterNear -> "No water within reach"
        SurvivalResult.NeedsStation -> "That needs a fire, or the right workbench"
        SurvivalResult.MissingIngredients -> "Missing ingredients"
        SurvivalResult.Unknown -> "That does not exist here"
    }

    /** What the HUD and the camp panel show of the body. Recipes are only worked out while the panel is open. */
    private fun survivalPanel(): SurvivalPanel {
        if (!session.survivalActive) return SurvivalPanel()
        val environment = session.surroundings
        return SurvivalPanel(
            active = true,
            needs = session.content.needs.map { NeedView(it, session.player.needs[it.id] ?: com.stratum.core.domain.survival.Survival.MAX) },
            night = session.clock.isNight,
            day = session.clock.day,
            sheltered = environment.sheltered,
            nearFire = environment.nearFire,
            canDrink = session.canDrink,
            food = session.heldFood,
            recipes = if (_state.value.campOpen) session.recipeOptions else emptyList(),
        )
    }

    // ---- realm -------------------------------------------------------------

    fun toggleRealm() {
        _state.value = _state.value.copy(realmOpen = !_state.value.realmOpen)
        publish()
    }

    fun foundOutpost() = publish(message = describe(session.foundOutpost("Outpost ${session.outposts.size + 1}")))

    fun deposit() = publish(message = describe(session.deposit()))

    fun buildStructure(structureId: String) = session.currentOutpost?.let { publish(message = describe(session.build(it.id, structureId))) }

    fun recruit(unitId: String) = session.currentOutpost?.let { publish(message = describe(session.recruit(it.id, unitId))) }

    fun muster() = publish(message = describe(session.muster()))

    fun command(order: FollowerOrder) = publish(message = describe(session.command(order)))

    private fun describe(result: RealmResult): String = when (result) {
        is RealmResult.Founded -> "${result.outpost.name} is founded"
        is RealmResult.Built -> "Built ${session.content.strategyBook.structure(result.structureId)?.name ?: "it"}"
        is RealmResult.Recruited -> "${session.content.strategyBook.unit(result.unitId)?.name ?: "A soldier"} joins the garrison"
        is RealmResult.Deposited -> if (result.gained.isEmpty()) "Nothing you carry is of use here" else "Stored " + result.gained.entries.joinToString(", ") { (id, n) -> "${n.toInt()} ${resourceName(id)}" }
        is RealmResult.Mustered -> "${result.count} follow you"
        is RealmResult.Ordered -> result.order.label
        is RealmResult.CannotAfford -> when (val why = result.why) {
            is Affordability.Missing -> "Short of " + why.resources.keys.joinToString(", ", transform = ::resourceName)
            is Affordability.Requires -> "Needs a ${session.content.strategyBook.structure(why.structureId)?.name ?: why.structureId} first"
            Affordability.AtLimit -> "There is no room for another"
            else -> "That cannot be done here"
        }
        RealmResult.NotHere -> "Stand inside an outpost for that"
        RealmResult.TooClose -> "Too close to a town or another outpost"
        RealmResult.NotEnoughBlocks -> "Founding takes ${com.stratum.core.domain.strategy.StandardStrategy.FOUNDING_BLOCKS} blocks from your bag"
        RealmResult.NoStrategy -> "This world has no outposts"
        RealmResult.NoneToMuster -> "Nobody in the garrison to muster"
    }

    private fun describe(event: RealmEvent): String = when (event) {
        is RealmEvent.RaidArrived -> "Raiders at ${event.outpost.name}: ${event.attackers} of them"
        is RealmEvent.RaidRepelled -> "${event.outpost.name} holds"
        is RealmEvent.RaidResolved -> if (event.outcome.defended) {
            "${event.outpost.name} beat off a raid"
        } else {
            "${event.outpost.name} was sacked" + if (event.outcome.razed.isNotEmpty()) ", and a building burned" else ""
        }
    }

    private fun resourceName(id: String): String = session.content.strategyBook.resources.firstOrNull { it.id == id }?.name?.lowercase() ?: id

    /** What the realm panel shows. Options are only worked out while it is open. */
    private fun realmPanel(): RealmPanel {
        if (!session.realmActive) return RealmPanel()
        val book = session.content.strategyBook
        val here = session.currentOutpost
        val open = _state.value.realmOpen
        return RealmPanel(
            active = true,
            here = here,
            outposts = session.outposts,
            resources = book.resources,
            netPerMinute = here?.let { Colony.netPerMinute(it, book) }.orEmpty(),
            population = here?.let { Colony.population(it, book) } ?: 0,
            workers = here?.let { Colony.workersNeeded(it, book) } ?: 0,
            defense = here?.let { Colony.defense(it, book) } ?: 0,
            structures = if (open && here != null) book.structures.map { RealmOption(it, Colony.canBuild(here, book, it.id), here.count(it.id)) } else emptyList(),
            units = if (open && here != null) book.units.map { RealmOption(it, Colony.canRecruit(here, book, it.id), here.garrison[it.id] ?: 0) } else emptyList(),
            followers = session.followers.size,
            order = session.followerOrder,
        )
    }

    fun toggleTable() {
        _state.value = _state.value.copy(tableOpen = !_state.value.tableOpen)
    }

    /** Rolls a tabletop check and reads the result out the way a table would. */
    fun rollCheck(checkId: String) {
        when (val attempt = session.attemptCheck(checkId)) {
            is CheckAttempt.Rolled -> {
                val result = attempt.result
                val effect = result.effect
                val verdict = when {
                    effect != null -> "${effect.boon.name} for ${effect.remainingSeconds.toInt()}s"
                    result.outcome.succeeded -> "Success"
                    else -> "Failure"
                }
                publish(message = "${result.check.name}: ${result.summary}. $verdict")
            }
            is CheckAttempt.OnCooldown -> publish(message = "Ready again in ${attempt.secondsLeft.toInt() + 1}s")
            CheckAttempt.UnknownCheck -> publish(message = "That check is not available")
            CheckAttempt.Refused -> Unit
        }
    }

    private fun directorFor(
        prompt: String,
        seed: Long = prompt.lowercase().hashCode().toLong(),
    ): WorldArtDirector = StyleSheetArtDirector(
        direction = StyleLexicon.interpret(prompt, ArtDirection.HOUSE, seed).direction,
        kits = artKits,
    )

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = viewModelScope.launch {
            var previousFrame = 0L
            while (isActive) {
                val now = awaitFrame()
                val delta = if (previousFrame == 0L) {
                    MIN_STEP
                } else {
                    ((now - previousFrame) / NANOS_PER_SECOND).coerceIn(MIN_STEP, MAX_STEP)
                }
                previousFrame = now
                elapsed += delta

                val events = session.tick(delta)
                session.sandbox?.advance(delta)
                if (events.any { it is CombatEvent.PlayerDied }) persist(SaveReason.DEATH) else autosave()
                if (events.isEmpty()) {
                    publish()
                } else {
                    publish(message = events.firstOrNull()?.let(::describe))
                }
            }
        }
    }

    private fun describe(event: CombatEvent): String? = when (event) {
        is CombatEvent.LootTaken ->
            if (event.equipped) "Equipped ${event.item.name}" else "Picked up ${event.item.name}"
        is CombatEvent.PlayerDied -> "You have fallen"
        // A dodge is the one defensive moment worth naming: it is the player
        // getting something right, and it is invisible otherwise.
        is CombatEvent.PlayerDodged -> "Dodged"
        // Taking a hit is already visible on the health meter; saying so as well
        // would drown out the messages that are not.
        is CombatEvent.PlayerHurt -> null
        is CombatEvent.InsertTaken -> "Picked up ${event.insert.name}"
        is CombatEvent.TownLiberated -> "${event.town.name} is liberated, and yours to hold"
        is CombatEvent.Realm -> describe(event.event)
        is CombatEvent.BossPhaseBegan -> event.announcement.ifBlank { "${event.enemyName}: ${event.phaseName}" }
    }

    // ---- dying -----------------------------------------------------------

    /**
     * Gets back up in the same world, keeping the character and everything on
     * it. The engine decides what that costs.
     */
    fun revive() {
        val result = session.revive()
        if (result is ReviveResult.Revived) persist(SaveReason.REVIVE)
        when (result) {
            is ReviveResult.Revived -> publish(
                message = if (result.experienceLost > 0) {
                    "You rise. ${result.experienceLost} experience stayed behind."
                } else {
                    "You rise."
                },
            )
            ReviveResult.StillStanding -> publish()
        }
    }

    /**
     * Leaves this world for a new one with a new seed. The hero goes along --
     * level, tree, gear, pouch -- and the world stays behind: the difference
     * between this and [revive] is where you stand, not who you are.
     */
    fun newRun() = newWorld(session.difficulty)

    private fun newWorld(difficulty: Difficulty) {
        miningJob?.cancel()
        loopJob?.cancel()
        // The world being left is kept in its own slot; the new one gets a slot of its own.
        persist(SaveReason.EXIT)
        val base = identity.name.substringBefore(TIER_SEPARATOR)
        identity = identity.copy(
            id = newWorldId(),
            name = if (difficulty.isBase) base else "$base$TIER_SEPARATOR${difficulty.tier}",
            createdAt = System.currentTimeMillis(),
        )
        session = WorldSession(content, worldConfig.copy(seed = System.nanoTime()), heroClassId, difficulty = difficulty, hero = session.heroSave())
        persist(SaveReason.NEW_WORLD)
        // The panels belong to the run that just ended; a fresh world opens on
        // the world, not on someone else's bag.
        _state.value = initialState(content)
        publish(message = if (difficulty.isBase) "A new world." else "A new world, tier ${difficulty.tier}.")
        startLoop()
    }

    // ---- the satchel -----------------------------------------------------

    /** Opens or closes the bag. Like the anvil, it does not pause the world. */
    fun toggleSatchel() {
        _state.value = _state.value.copy(satchelOpen = !_state.value.satchelOpen)
        publish()
    }

    /** Wears a bagged item: in the slot the gear panel is looking at when it fits there, or wherever it goes. */
    fun equip(instanceId: String) {
        val slot = _state.value.gearSlot?.takeIf { slot -> session.player.itemById(instanceId)?.slot?.fits?.contains(slot) == true }
        _state.value = _state.value.copy(gearInspected = null)
        publish(message = describe(session.equip(instanceId, slot)))
    }

    /** Looks at one place on the paper doll; the same one again looks at the whole bag. */
    fun selectGearSlot(slot: com.stratum.core.domain.item.EquipmentSlot?) {
        val current = _state.value.gearSlot
        _state.value = _state.value.copy(gearSlot = if (slot == current) null else slot, gearInspected = null)
        publish()
    }

    /** Reads one item in full; null puts it down. */
    fun inspectItem(instanceId: String?) {
        _state.value = _state.value.copy(gearInspected = instanceId.takeIf { it != _state.value.gearInspected })
        publish()
    }

    /** What the satchel draws, worked out only while it is open. */
    // The satchel compares every bagged item against the whole character. That
    // answer changes when the gear, the bag, the passives or the selection do,
    // not sixty times a second while the panel is open.
    private var gearKey: List<Any?>? = null

    /** Keys of immutable state compare by identity: a new object is the only way any of it changes, and deep equality cost as much as it saved. */
    private fun sameObjects(a: List<Any?>, b: List<Any?>?): Boolean =
        b != null && a.size == b.size && a.indices.all { i -> a[i] === b[i] || (a[i] is Number || a[i] is Boolean || a[i] is Enum<*>) && a[i] == b[i] }
    private var gearCache = GearPanelState()

    private fun gearPanel(): GearPanelState {
        if (!_state.value.satchelOpen) return GearPanelState().also { gearKey = null }
        val player = session.player
        val key = listOf(player.equipment, player.bag, player.insertBag, player.build, player.level, _state.value.gearSlot, _state.value.gearInspected)
        if (sameObjects(key, gearKey)) return gearCache
        gearKey = key
        val catalogue = session.content.itemCatalogue
        return gearPanelUncached(catalogue).also { gearCache = it }
    }

    private fun gearPanelUncached(catalogue: com.stratum.core.domain.item.ItemCatalogue): GearPanelState {
        return GearPanelBuilder.build(
            player = session.player,
            selectedSlot = _state.value.gearSlot,
            inspectedId = _state.value.gearInspected,
            inserts = insertFor,
            setInfo = { id -> catalogue.set(id)?.let { SetInfo(it.name, catalogue.piecesOf(id).size) } },
            statsOf = session::statsFor,
            damageTypeNames = damageTypeNames,
        )
    }

    fun unequip(slot: com.stratum.core.domain.item.EquipmentSlot) {
        publish(message = describe(session.unequip(slot)))
    }

    fun discard(instanceId: String) {
        publish(message = describe(session.discard(instanceId)))
    }

    private fun describe(result: EquipResult): String = when (result) {
        is EquipResult.Equipped -> "Equipped ${result.item.name}"
        is EquipResult.Unequipped -> "Took off ${result.item.name}"
        is EquipResult.Discarded -> "Dropped ${result.item.name}"
        EquipResult.NotInBag -> "That is not in your bag"
        is EquipResult.TooLowLevel -> "${result.item.name} needs level ${result.requiredLevel}"
        is EquipResult.WrongSlot -> "${result.item.name} is not worn there"
        EquipResult.NothingWorn -> "Nothing is worn there"
    }

    // ---- the anvil -------------------------------------------------------

    /**
     * Opens or closes the anvil. Opening it does not pause the world: the fight
     * is still happening, and re-socketing mid-fight is a decision with a cost
     * rather than a free menu.
     */
    fun toggleAnvil() {
        _state.value = _state.value.copy(anvilOpen = !_state.value.anvilOpen)
        publish()
    }

    /** Chooses which of the player's items the anvil is working on. */
    fun selectAnvilItem(instanceId: String) {
        _state.value = _state.value.copy(anvilItemId = instanceId)
        publish()
    }

    fun slotInsert(instanceId: String, insertId: String) {
        publish(message = describe(session.slotInsert(instanceId, insertId)))
    }

    fun unslotInsert(instanceId: String, socketIndex: Int) {
        publish(message = describe(session.unslotInsert(instanceId, socketIndex)))
    }

    private fun describe(result: SocketResult): String = when (result) {
        is SocketResult.Slotted ->
            "Set ${session.insertOrNull(result.insertId)?.name ?: "it"} into ${result.item.name}"
        is SocketResult.Unslotted ->
            "Drew ${session.insertOrNull(result.insertId)?.name ?: "it"} back out"
        SocketResult.NoFreeSocket -> "No socket free"
        SocketResult.NoneHeld -> "You have none of those"
        SocketResult.NoSuchInsert -> "Unknown insert"
        SocketResult.NoSuchItem -> "You are not carrying that"
        SocketResult.EmptySocket -> "That socket is already empty"
    }

    private fun initialState(content: AssembledContent) = PlayUiState(
        player = session.player,
        camera = session.player.position,
        projection = IsometricProjection(),
        palette = content.palette,
        biomeName = session.currentBiome.name,
        artDirector = artDirector,
        stylePrompt = stylePrompt,
        styleSummary = artDirector.direction.summary,
        kit = ForgedKits.kitFor(artDirector.direction, kitDirectory),
        kitOverlays = kitOverlays,
        propModels = propModels,
        blueprints = blueprints.map { BlueprintChoice(it.id, it.name, it.filledCount) },
        onRaiseBlueprint = ::raiseBlueprint,
        quality = initialQuality,
        checks = content.checks,
        // A lambda rather than a bound reference: starting a fresh world
        // replaces the session, and a captured reference would keep answering
        // for the world the player just left.
        biomeAt = { x, y -> session.biomeAt(x, y) },
        // The world's quarter-block detail, when it was generated in microvoxels.
        microTerrain = session.microTerrain,
        worldShaper = shaperPanel(),
    )

    /**
     * The joystick reports where the thumb is, every frame it moves. The session
     * integrates it on its own clock, so this only records intent.
     */
    fun setMoveInput(dx: Float, dy: Float) {
        // The stick is screen-relative: up walks up the screen, whichever way
        // the world's axes happen to run under it.
        val world = com.stratum.engine.world.IsometricProjection.screenToWorldDirection(dx, dy)
        session.setMoveInput(world.x, world.y)
    }

    /** Single nudge, for anything that is not the stick. */
    fun move(dx: Float, dy: Float) {
        session.move(dx, dy)
        publish()
    }

    fun dodge() {
        when (session.dodge()) {
            DodgeResult.Rolling -> publish()
            DodgeResult.OnCooldown, DodgeResult.AlreadyRolling, DodgeResult.Rejected -> Unit
        }
    }

    fun selectSlot(slot: Int) {
        session.selectSlot(slot)
        publish()
    }

    fun zoom(delta: Float) {
        _state.value = _state.value.let {
            it.copy(projection = it.projection.copy(zoom = (it.projection.zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)))
        }
    }

    /**
     * Mining runs on a coroutine rather than a tap because effort accumulates
     * over time. Starting a new dig cancels the previous one so two blocks can
     * never make progress at once.
     */
    fun beginMining(target: BlockPos) {
        miningJob?.cancel()
        session.cancelMining()
        miningJob = viewModelScope.launch {
            // Frame paced like the world loop, and for the same reason: a block's
            // hardness is stated in seconds, so effort has to accumulate in real
            // seconds rather than in however often a timer happened to fire.
            var previousFrame = 0L
            while (isActive) {
                val now = awaitFrame()
                val delta = if (previousFrame == 0L) {
                    MIN_STEP
                } else {
                    ((now - previousFrame) / NANOS_PER_SECOND).coerceIn(MIN_STEP, MAX_STEP)
                }
                previousFrame = now

                when (val result = session.mine(target, delta)) {
                    is MineResult.Broken -> {
                        publish(message = "Recovered ${displayName(result.drop)}")
                        return@launch
                    }
                    is MineResult.Rejected -> {
                        publish(message = rejectionMessage(result.reason))
                        return@launch
                    }
                    is MineResult.InProgress -> publish()
                }
            }
        }
    }

    fun stopMining() {
        miningJob?.cancel()
        miningJob = null
        session.cancelMining()
        publish()
    }

    /** A basic swing at whatever is in reach. */
    fun attack() {
        when (val report = session.attack()) {
            is AttackReport.Landed -> publish(message = describeAttack(report))
            AttackReport.Missed -> publish(message = "Nothing in reach")
            AttackReport.NotReady -> Unit
            else -> publish()
        }
    }

    fun castSkill(skillId: String) {
        when (val report = session.castSkill(skillId)) {
            is AttackReport.Landed -> publish(message = describeAttack(report))
            AttackReport.Missed -> publish(message = "Nothing in reach")
            AttackReport.NotEnoughResource -> publish(message = "Not enough ${session.player.resourceName}")
            AttackReport.OnCooldown -> Unit
            AttackReport.NotReady -> Unit
            AttackReport.UnknownSkill -> publish(message = "That skill is not available")
            is AttackReport.Cast -> publish()
            AttackReport.Stunned -> publish(message = "Stunned")
        }
    }

    /** Drinks from the flask in [slot] on the belt. */
    fun useFlask(slot: Int) {
        when (val result = session.useFlask(slot)) {
            is FlaskResult.Drunk -> publish(message = result.flask.name)
            is FlaskResult.Empty -> publish(message = "${result.flask.name} is empty")
            FlaskResult.NoSuchFlask -> Unit
        }
    }

    private fun describeAttack(report: AttackReport.Landed): String {
        val slain = report.slain
        return when {
            slain.size > 1 -> "Slew ${slain.size}"
            slain.size == 1 -> "Slew ${slain.single().name}"
            report.skill != null -> "${report.skill!!.name} hit for ${report.totalDamage}"
            else -> "Hit for ${report.totalDamage}"
        }
    }

    // ---- building --------------------------------------------------------

    fun toggleBuildMode() {
        val entering = !_state.value.buildMode
        if (!entering) session.cancelBuild()
        // Digging and building share the screen, so entering build mode has to
        // stop any dig in progress or the first drag does both.
        stopMining()
        _state.value = _state.value.copy(buildMode = entering, buildAffordable = true)
        publish()
    }

    fun selectBuildTool(tool: BuildTool) {
        session.selectBuildTool(tool)
        publish()
    }

    fun previewBuild(from: BlockPos, to: BlockPos) {
        val preview = session.previewBuild(from, to)
        _state.value = _state.value.copy(buildAffordable = preview.affordable)
        publish()
    }

    fun commitBuild() {
        when (val result = session.commitBuild()) {
            is BuildResult.Built ->
                publish(
                    message = if (result.short > 0) {
                        "Built ${result.placed}, ${result.short} short"
                    } else {
                        "Built ${result.placed}"
                    },
                )
            BuildResult.OutOfBlocks -> publish(message = "Out of blocks")
            BuildResult.NothingSelected -> publish(message = "Nothing selected to build with")
            BuildResult.NothingToBuild -> publish()
            is BuildResult.Erased -> publish(message = "Cleared ${result.removed}")
        }
    }

    /** Raises a model's blueprint in front of the player; see [WorldSession.raise]. */
    fun raiseBlueprint(id: String) {
        val blueprint = blueprints.firstOrNull { it.id == id } ?: return
        val placed = session.raise(blueprint)
        publish(message = if (placed > 0) "Raised ${blueprint.name}: $placed blocks" else "No room to raise ${blueprint.name} here")
    }

    fun place(target: BlockPos) {
        // The session stops its own mining, but the coroutine driving it lives
        // here and would otherwise keep calling mine() on the old target.
        miningJob?.cancel()
        when (val result = session.place(target)) {
            is PlaceResult.Placed -> publish(message = "Placed ${result.block.displayName}")
            is PlaceResult.Rejected -> publish(message = placeRejectionMessage(result.reason))
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun publish(message: String? = null) {
        val snapshot = session.snapshot()
        _state.value = _state.value.copy(
            player = snapshot.player,
            camera = snapshot.player.position,
            biomeName = snapshot.biome.name,
            // The session's clock, so the light and the cold agree on when night is.
            worldTime = WorldTime(dayFraction = session.clock.dayFraction, elapsedSeconds = elapsed),
            miningTarget = snapshot.miningTarget,
            miningFraction = snapshot.miningFraction,
            worldRevision = snapshot.worldRevision,
            enemies = snapshot.enemies,
            groundLoot = snapshot.groundLoot,
            groundInserts = snapshot.groundInserts,
            heldInserts = snapshot.heldInserts,
            insertFor = insertFor,
            rarityColors = rarityColors,
            isRolling = snapshot.isRolling,
            isInvulnerable = snapshot.isInvulnerable,
            rollCooldownFraction = snapshot.rollCooldownFraction,
            feedback = snapshot.feedback,
            playerFlash = snapshot.playerFlash,
            flashFor = flashFor,
            impactFor = impactFor,
            playerAnimation = snapshot.playerAnimation,
            animationFor = animationFor,
            spriteFor = spriteResolver,
            buildPreview = snapshot.buildPreview,
            buildTool = snapshot.buildTool,
            skills = snapshot.skills,
            activeBoons = snapshot.activeBoons,
            checkCooldowns = content.checks.associate { it.id to session.checkCooldown(it.id) },
            heldCurrency = session.heldCurrency,
            survival = survivalPanel(),
            realm = realmPanel(),
            settlementName = snapshot.settlement?.name,
            settlementHostile = snapshot.settlementHostile,
            projectiles = snapshot.projectiles,
            zones = snapshot.zones,
            telegraphs = snapshot.telegraphs,
            flasks = snapshot.flasks,
            maxHealth = snapshot.maxHealth,
            maxResource = snapshot.maxResource,
            hero = heroPanel(),
            gear = gearPanel(),
            sandbox = sandboxPanel(),
            lifePaysCosts = com.stratum.core.domain.combat.Keystone.LIFE_PAYS_COSTS in session.keystones,
            frame = _state.value.frame + 1,
            message = message ?: _state.value.message,
            saveNotice = lastSave.value,
        )
    }

    private fun heroPanel(): HeroPanelState {
        val panel = _state.value.hero
        if (!panel.open) return panel
        val build = session.passiveBuild
        return panel.copy(
            tree = session.passiveTree,
            startId = build?.startId,
            supportsBySkill = session.skills.associate { it.id to session.supportsOn(it.id) },
            heldSupports = session.heldSupports,
            tier = session.difficulty.tier,
            worldMods = session.difficulty.mods,
            breakdown = if (panel.tab == HeroTab.STATS) session.explain(panel.query) else null,
            damageTypes = damageTypeChoices,
        )
    }

    /** Explains another number on the stats page. */
    fun explainHeroStat(query: com.stratum.core.domain.sandbox.StatQuery) {
        _state.value = _state.value.copy(hero = _state.value.hero.copy(query = query))
        publish()
    }

    private fun displayName(blockId: String): String =
        session.content.registry.indexOrNull(blockId)
            ?.let { session.content.registry.typeOf(it).displayName }
            ?: blockId.substringAfter(':').replace('_', ' ')

    private fun rejectionMessage(reason: com.stratum.engine.world.MineRejection) = when (reason) {
        com.stratum.engine.world.MineRejection.NOTHING_THERE -> "Nothing there"
        com.stratum.engine.world.MineRejection.UNBREAKABLE -> "This will not break"
        com.stratum.engine.world.MineRejection.TOOL_TOO_WEAK -> "A stronger tool is needed"
        com.stratum.engine.world.MineRejection.OUT_OF_REACH -> "Too far away"
    }

    private fun placeRejectionMessage(reason: PlaceRejection) = when (reason) {
        PlaceRejection.UNKNOWN_BLOCK -> "Nothing to place"
        PlaceRejection.OCCUPIED -> "Something is already there"
        PlaceRejection.OUT_OF_REACH -> "Too far away"
        PlaceRejection.OUT_OF_BOUNDS -> "Outside the world"
        PlaceRejection.NOTHING_TO_ATTACH_TO -> "Needs something to rest against"
        PlaceRejection.ACTOR_IN_THE_WAY -> "You are standing there"
    }

    // ---- the hero ------------------------------------------------------------

    fun toggleHero() {
        val hero = _state.value.hero
        _state.value = _state.value.copy(hero = hero.copy(open = !hero.open))
        publish()
    }

    fun selectHeroTab(tab: HeroTab) {
        _state.value = _state.value.copy(hero = _state.value.hero.copy(tab = tab))
        publish()
    }

    /** Selects a node and lights the path to it, so the player sees the cost before paying it. */
    fun selectPassive(nodeId: String) {
        val path = session.passiveBuild?.pathTo(nodeId).orEmpty()
        _state.value = _state.value.copy(hero = _state.value.hero.copy(selectedNode = nodeId, path = path))
    }

    fun allocatePassive() {
        val nodeId = _state.value.hero.selectedNode ?: return
        val message = when (val result = session.allocatePassive(nodeId)) {
            is PassiveResult.Allocated -> "Took ${result.nodes.last().name}" + if (result.nodes.size > 1) " and ${result.nodes.size - 1} on the way" else ""
            is PassiveResult.NotEnoughPoints -> "Needs ${result.needed} points; you have ${result.available}"
            PassiveResult.Unreachable -> "Nothing you hold reaches it"
            PassiveResult.AlreadyTaken -> "Already yours"
            else -> null
        }
        afterPassiveChange(message)
    }

    fun refundPassive() {
        val nodeId = _state.value.hero.selectedNode ?: return
        val message = when (val result = session.refundPassive(nodeId)) {
            is PassiveResult.Refunded -> "Gave back ${result.node.name}"
            PassiveResult.HoldsOthers -> "Other nodes you hold depend on it"
            else -> null
        }
        afterPassiveChange(message)
    }

    private fun afterPassiveChange(message: String?) {
        _state.value.hero.selectedNode?.let(::selectPassive)
        persist(SaveReason.BUILD_CHANGE)
        publish(message = message)
    }

    fun selectSkill(skillId: String) {
        _state.value = _state.value.copy(hero = _state.value.hero.copy(selectedSkill = skillId))
        publish()
    }

    /** Links a held support to the selected skill, or the first one. */
    fun linkSupport(supportId: String) {
        val skillId = _state.value.hero.selectedSkill ?: session.skills.firstOrNull()?.id ?: return
        publish(message = describe(session.linkSupport(skillId, supportId)))
        persist(SaveReason.BUILD_CHANGE)
    }

    fun unlinkSupport(skillId: String, supportId: String) {
        publish(message = describe(session.unlinkSupport(skillId, supportId)))
        persist(SaveReason.BUILD_CHANGE)
    }

    private fun describe(result: SupportResult): String = when (result) {
        is SupportResult.Linked -> "${result.support.name} linked to ${result.skill.name}"
        is SupportResult.Unlinked -> "${result.support.name} back in the pouch"
        SupportResult.SkillFull -> "That skill holds ${com.stratum.core.domain.crafting.StandardCrafting.MAX_SUPPORTS_PER_SKILL} supports"
        SupportResult.AlreadyLinked -> "Already linked there"
        SupportResult.NoneHeld -> "You hold none of those"
        SupportResult.UnknownSkill, SupportResult.UnknownSupport, SupportResult.NotLinked, SupportResult.DoesNotFit -> "That does not fit"
    }

    /** Spends currency on the item the anvil is showing. */
    fun craft(currencyId: String) {
        val item = _state.value.anvilItem ?: return
        val message = when (val result = session.craft(item.instanceId, currencyId)) {
            is CraftResult.Crafted -> "${result.currency.name}: ${result.after.name}"
            is CraftResult.NoEffect -> result.reason
            CraftResult.NoneHeld -> "You hold none of those"
            CraftResult.NoSuchItem, CraftResult.NoSuchCurrency -> null
        }
        persist(SaveReason.BUILD_CHANGE)
        publish(message = message)
    }

    /** Opens a new world at a tier this hero has reached. */
    fun enterTier(tier: Int) {
        if (tier !in 0..session.player.highestTier) return publish(message = "Fell a champion at tier ${session.player.highestTier} first")
        newWorld(Difficulty(tier))
    }

    /** Spends a waystone on a new world at its tier, with its mods. */
    fun openWaystone(waystoneId: String) {
        val waystone = session.takeWaystone(waystoneId) ?: return
        newWorld(Difficulty.of(waystone))
    }

    /** Writes the world and the hero down now and then: on a level, and every minute of play. */
    private fun autosave() {
        saver.due(elapsed, session.player.level)?.let(::persist)
    }

    /** Saves now: for the shell, when it knows the player is about to leave. */
    fun saveNow() = persist(SaveReason.EXIT)

    /** The app went into the background, where it may be killed without warning. */
    fun onBackground() = persist(SaveReason.BACKGROUND)

    /**
     * Takes the snapshot here, on the thread that ticks the world, and hands
     * the writing to [saver]. The world's copy of the hero is the one kept in
     * the roster too, so the two can never disagree about the same moment. A
     * sandbox saves its world to its own slot, and never its hero: a conjured
     * character never writes over the real one.
     */
    private fun persist(reason: SaveReason) {
        val now = System.currentTimeMillis()
        val world = worlds?.let { session.worldSave(identity, savedAt = now) }
        val hero = world?.hero ?: session.heroSave(savedAt = now)
        saver.write(world, hero, sandbox = session.rules.sandbox, reason = reason, elapsed = elapsed, level = session.player.level)
    }

    override fun onCleared() {
        // Written in the save scope, which outlives this one: leaving to the menu is a save.
        persist(SaveReason.EXIT)
        miningJob?.cancel()
        loopJob?.cancel()
        super.onCleared()
    }

    /** What the menu calls the hero of a world this screen names itself. */
    private fun heroName(): String =
        content.heroClasses.firstOrNull { it.id == session.player.heroClassId }?.name ?: session.player.heroClassId

    // ---- the build sandbox ----------------------------------------------------

    fun toggleSandbox() {
        val panel = _state.value.sandbox
        _state.value = _state.value.copy(sandbox = panel.copy(open = !panel.open))
        publish()
    }

    fun selectSandboxTab(tab: SandboxTab) = updateSandbox { it.copy(tab = tab) }

    fun filterSandboxSlot(slot: com.stratum.core.domain.item.ItemSlot?) = updateSandbox { it.copy(slotFilter = slot) }

    fun setSandboxItemLevel(level: Int) = updateSandbox { it.copy(itemLevel = level.coerceIn(1, com.stratum.engine.world.SandboxTools.MAX_ITEM_LEVEL)) }

    fun setSandboxRarity(rarity: ItemRarity) = updateSandbox { it.copy(rarity = rarity) }

    fun setDummy(spec: com.stratum.core.domain.sandbox.DummySpec) = updateSandbox { it.copy(dummy = spec) }

    fun explainSandboxStat(query: com.stratum.core.domain.sandbox.StatQuery) = updateSandbox { it.copy(query = query) }

    fun spawnBase(baseId: String) = sandboxDo {
        val panel = _state.value.sandbox
        it.spawnItem(com.stratum.engine.world.ItemRequest(baseId = baseId, itemLevel = panel.itemLevel, rarity = panel.rarity))
    }

    fun spawnUnique(uniqueId: String) = sandboxDo { it.spawnItem(com.stratum.engine.world.ItemRequest(uniqueId = uniqueId, itemLevel = _state.value.sandbox.itemLevel)) }

    fun rerollItem(instanceId: String) = sandboxDo { it.reroll(instanceId) }

    fun setHeroLevel(level: Int) = sandboxDo { it.setLevel(level) }

    fun respec() = sandboxDo { it.respec() }

    fun grantCurrency(currencyId: String) = sandboxDo { it.grantCurrency(currencyId) }

    fun grantSupport(supportId: String) = sandboxDo { it.grantSupport(supportId) }

    fun grantEverything() = sandboxDo { it.grantEverything() }

    fun spawnDummy() = sandboxDo { it.spawnDummy(_state.value.sandbox.dummy) }

    fun healDummies() = sandboxDo { it.healDummies() }

    fun clearDummies() = sandboxDo { it.clearDummies() }

    fun spawnMonster(definitionId: String) = sandboxDo { it.spawnMonster(definitionId) }

    fun resetMeter() {
        session.sandbox?.meter?.reset()
        publish(message = "Meter reset")
    }

    /** Writes the build down as a one-line code, shown in the panel to copy. */
    fun exportBuild(): String? {
        val tools = session.sandbox ?: return null
        val code = com.stratum.core.domain.sandbox.BuildCode.toCode(tools.exportBuild())
        _state.value = _state.value.copy(sandbox = _state.value.sandbox.copy(exported = code))
        publish(message = "Build code ready to copy")
        return code
    }

    /**
     * Takes on a shared build. A class is chosen when a world is made, so the
     * build arrives in a new sandbox world rather than on this body.
     */
    fun importBuild(text: String) {
        val tools = session.sandbox ?: return publish(message = "Builds are tried on in a sandbox world")
        tools.importBuild(text).fold(
            onSuccess = { imported ->
                val skipped = imported.skipped.takeIf { it.isNotEmpty() }?.let { " (${it.size} pieces left out: ${it.joinToString("; ")})" }.orEmpty()
                replaceWorld(worldConfig, imported.hero, "Build imported$skipped")
            },
            onFailure = { publish(message = it.message ?: "That is not a build") },
        )
    }

    /** Lifts every cap or puts them back. Caps are a rule of the world, so the world is made again around the same hero. */
    fun toggleCaps() {
        if (session.sandbox == null) return
        val lifted = worldConfig.rules.combat == com.stratum.core.domain.combat.CombatRules.UNBOUND
        val combat = if (lifted) com.stratum.core.domain.combat.CombatRules() else com.stratum.core.domain.combat.CombatRules.UNBOUND
        replaceWorld(worldConfig.copy(rules = worldConfig.rules.copy(combat = combat)), session.heroSave(), if (lifted) "Caps restored" else "Every cap lifted")
    }

    /**
     * The same world again, with [config] and [hero]: resumed from a save of
     * itself, so the ground dug and built and where the player stands are
     * all kept -- only the rules change.
     */
    private fun replaceWorld(config: WorldConfig, hero: HeroSave, message: String) {
        miningJob?.cancel()
        loopJob?.cancel()
        worldConfig = config
        session = WorldSession.restore(content, session.worldSave(identity).copy(config = config, hero = hero))
        val panel = _state.value.sandbox
        _state.value = initialState(content).copy(sandbox = panel.copy(exported = null))
        publish(message = message)
        startLoop()
    }

    private fun updateSandbox(change: (SandboxPanelState) -> SandboxPanelState) {
        _state.value = _state.value.copy(sandbox = change(_state.value.sandbox))
        publish()
    }

    private fun sandboxDo(action: (com.stratum.engine.world.SandboxTools) -> com.stratum.engine.world.SandboxResult) {
        val tools = session.sandbox ?: return
        publish(message = describe(action(tools)))
    }

    private fun describe(result: com.stratum.engine.world.SandboxResult): String = when (result) {
        is com.stratum.engine.world.SandboxResult.ItemMade -> "Made ${result.item.name}"
        is com.stratum.engine.world.SandboxResult.Rerolled -> "Rerolled into ${result.after.name}"
        is com.stratum.engine.world.SandboxResult.Granted -> "Granted ${result.name}" + if (result.count > 1) " ×${result.count}" else ""
        is com.stratum.engine.world.SandboxResult.LevelSet -> "Level ${result.level}"
        is com.stratum.engine.world.SandboxResult.Respecced -> "Gave back ${result.nodes} passives"
        is com.stratum.engine.world.SandboxResult.Spawned -> result.enemies.singleOrNull()?.let { "${it.name} stands ready" } ?: "${result.enemies.size} stand ready"
        is com.stratum.engine.world.SandboxResult.Cleared -> "${result.count} dummies"
        com.stratum.engine.world.SandboxResult.NotFound -> "The loaded packs have no such thing"
        is com.stratum.engine.world.SandboxResult.Refused -> result.reason
    }

    /** What the sandbox panel and the meter chip draw; lists only for the page that is open. */
    // The sandbox's lists change with the tab and filter; its meter and
    // numbers are read by a person, so four refreshes a second is plenty.
    private var sandboxKey: List<Any?>? = null
    private var sandboxRefreshedAt = -1f

    private fun sandboxPanel(): SandboxPanelState {
        val tools = session.sandbox ?: return SandboxPanelState()
        val panel = _state.value.sandbox
        val key = listOf(panel.open, panel.tab, panel.slotFilter, panel.query, tools.capsLifted, tools.dummies.size,
            session.player.currency, session.player.supportBag)
        if (sameObjects(key, sandboxKey) && elapsed - sandboxRefreshedAt < SANDBOX_REFRESH_SECONDS) return panel
        sandboxKey = key
        sandboxRefreshedAt = elapsed
        val open = panel.open
        val catalogue = session.content.itemCatalogue
        return panel.copy(
            active = true,
            capsLifted = tools.capsLifted,
            meter = tools.meter.report(),
            damageTypes = damageTypeChoices,
            dummies = tools.dummies.size,
            bases = if (open && panel.tab == SandboxTab.ITEMS) tools.bases.filter { panel.slotFilter == null || it.slot == panel.slotFilter }
                .map { NamedChoice(it.id, it.name, "${it.slot.name.lowercase()} · level ${it.requiredLevel}") } else emptyList(),
            uniques = if (open && panel.tab == SandboxTab.ITEMS) tools.uniques.filter { unique -> panel.slotFilter == null || catalogue.base(unique.baseId)?.slot == panel.slotFilter }
                .map { NamedChoice(it.id, it.name, it.setId?.let { id -> "set: ${catalogue.set(id)?.name ?: id}" } ?: "unique", color = session.content.rarityColor(if (it.setId != null) ItemRarity.SET else ItemRarity.UNIQUE)) } else emptyList(),
            currencies = if (open && panel.tab == SandboxTab.HERO) session.content.currencies.map { NamedChoice(it.id, it.name, "×${session.player.currencyCount(it.id)}", it.color) } else emptyList(),
            supports = if (open && panel.tab == SandboxTab.HERO) session.content.supports.map { NamedChoice(it.id, it.name, "×${session.player.supportCount(it.id)}", it.color) } else emptyList(),
            monsters = if (open && panel.tab == SandboxTab.TARGETS) tools.monsters.map { NamedChoice(it.id, it.name, if (tools.isBoss(it)) "boss" else it.rank.name.lowercase(), it.bodyColor) } else emptyList(),
            breakdown = if (open && panel.tab == SandboxTab.BREAKDOWN) session.explain(panel.query) else null,
        )
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000f
        /** Below this a step is noise; it also covers the very first frame. */
        private const val MIN_STEP = 1f / 240f
        /** A frame longer than this is a stall, and is served in slow motion. */
        private const val MAX_STEP = 1f / 15f
        private const val MIN_ZOOM = 0.6f
        private const val MAX_ZOOM = 2.2f
        private const val DEFAULT_WORLD_NAME = "New world"

        /** Between a world's name and the tier a waystone or the tier list opened it at. */
        private const val TIER_SEPARATOR = ", tier "
        private const val SANDBOX_REFRESH_SECONDS = 0.25f

        fun factory(
            content: AssembledContent,
            config: WorldConfig,
            heroClassId: String? = null,
            spriteResolver: (SpriteKey) -> DrawableSprite? = { null },
            imageModel: com.stratum.core.domain.ai.ImageModelPort? = null,
            kitDirectory: File? = null,
            kitOverlays: List<File> = emptyList(),
            quality: QualityTier? = null,
            saveQuality: (QualityTier?) -> Unit = {},
            /** Read only when the view model is created, so a recomposition does not touch the disk. */
            loadHero: () -> HeroSave? = { null },
            saveHero: (HeroSave) -> Unit = {},
            stylePrompt: String = "",
            saveStyle: (String) -> Unit = {},
            propModels: Map<String, com.stratum.engine.scene.PropModel> = emptyMap(),
            blueprints: List<com.stratum.core.domain.content.VoxelBlueprint> = emptyList(),
            /** A saved world to resume. Its seed, rules, hero class and hero win over [config], [heroClassId] and [loadHero]. */
            resume: WorldSave? = null,
            /** Where worlds are kept; null plays without a world slot, as before world saving. */
            worlds: WorldSaveRepository? = null,
            /** The slot a new world saves into, from the world library; ignored when resuming. */
            slot: WorldIdentity? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PlayViewModel(
                content, config, heroClassId, spriteResolver,
                imageModel = imageModel, kitDirectory = kitDirectory, kitOverlays = kitOverlays,
                quality = quality, saveQuality = saveQuality, hero = if (resume == null) loadHero() else null, saveHero = saveHero,
                stylePrompt = stylePrompt, saveStyle = saveStyle,
                propModels = propModels, blueprints = blueprints,
                resume = resume, worlds = worlds, slot = slot,
            ) as T
        }
    }
}

/** Everything the play screen renders, and nothing it does not. */
data class PlayUiState(
    val player: PlayerState,
    val camera: WorldPoint,
    val projection: IsometricProjection,
    val palette: com.stratum.core.domain.content.PackPalette,
    val biomeName: String,
    val miningTarget: BlockPos? = null,
    val miningFraction: Float = 0f,
    val worldRevision: Int = 0,
    val enemies: List<EnemyInstance> = emptyList(),
    val groundLoot: List<GroundLoot> = emptyList(),
    val groundInserts: List<GroundInsert> = emptyList(),
    val heldInserts: List<HeldInsert> = emptyList(),
    /** Pack lookups the anvil needs. Passed as functions rather than copies so
     * the UI never holds a stale snapshot of the loaded packs. */
    val insertFor: (String) -> InsertDefinition? = { null },
    val rarityColors: (ItemRarity) -> Long = { DEFAULT_RARITY_TINT },
    val satchelOpen: Boolean = false,
    val anvilOpen: Boolean = false,
    /** Which item the anvil is working on; falls back to what is equipped. */
    val anvilItemId: String? = null,
    val isRolling: Boolean = false,
    val isInvulnerable: Boolean = false,
    val rollCooldownFraction: Float = 0f,
    val feedback: List<FeedbackMark> = emptyList(),
    /** Things in flight, burning ground and wind-ups, from the combat core. */
    val projectiles: List<com.stratum.engine.world.Projectile> = emptyList(),
    val zones: List<com.stratum.engine.world.Zone> = emptyList(),
    val telegraphs: List<com.stratum.engine.world.Telegraph> = emptyList(),
    val flasks: List<com.stratum.engine.world.FlaskView> = emptyList(),
    /** The bars' ceilings with traits and boons counted, from the session; the player's own fields cannot see those. */
    val maxHealth: Int = player.maxHealthWithGear,
    val maxResource: Int = player.resourceCeiling,
    val playerFlash: Float = 0f,
    /** Per-actor hit flash, read by the renderer for each visible monster. */
    val flashFor: (String) -> Float = { 0f },
    /** How hard an actor is being knocked back, 0..1. */
    val impactFor: (String) -> Float = { 0f },
    val playerAnimation: AnimationPlayback = AnimationPlayback(),
    val animationFor: (String) -> AnimationPlayback = { AnimationPlayback() },
    val spriteFor: (SpriteKey) -> DrawableSprite? = { null },
    val buildMode: Boolean = false,
    val buildPreview: List<BlockPos> = emptyList(),
    val buildTool: BuildTool = BuildTool.SINGLE,
    val buildAffordable: Boolean = true,
    val skills: List<SkillDefinition> = emptyList(),
    /** Advances every tick so the canvas redraws while the fight is moving. */
    val frame: Int = 0,
    /** How the world is drawn. Swapped by [PlayViewModel.restyle], never by the canvas. */
    val artDirector: WorldArtDirector = StyleSheetArtDirector(),
    val worldTime: WorldTime = WorldTime(),
    val biomeAt: (Int, Int) -> BiomeDefinition? = { _, _ -> null },
    /** Microvoxel detail behind the blocks, for worlds generated that way; null draws blocks only. */
    val microTerrain: com.stratum.engine.microvoxel.MicroTerrainSource? = null,
    /** The World panel: the terrain's stages, live-editable in microvoxel worlds. */
    val worldShaper: WorldShaperPanel = WorldShaperPanel(),
    /** What the player last asked for, so the field can show it back to them. */
    val stylePrompt: String = "",
    /** What the game understood by it, which is how a player learns the vocabulary. */
    val styleSummary: String = "",
    val styleOpen: Boolean = false,
    /** Which forged asset kit the 3D view draws with. */
    val kit: String = "house",
    /** Imported packs' textures, laid over [kit]. */
    val kitOverlays: List<File> = emptyList(),
    /** The graphics tier the player chose; null is the device's own. */
    val quality: QualityTier? = null,
    /** Tabletop checks the loaded plugins offer. */
    val checks: List<SkillCheck> = emptyList(),
    /** Seconds until each check can be rolled again; 0 when ready. */
    val checkCooldowns: Map<String, Float> = emptyMap(),
    /** Boons and banes running from checks. */
    val activeBoons: List<ActiveBoon> = emptyList(),
    val tableOpen: Boolean = false,
    /** The lit 3D view, or the flat 2D canvas it replaced. */
    val use3D: Boolean = true,
    /** The texture forge's latest progress, or null when it has not run. */
    val forgeProgress: ForgeProgress? = null,
    /** Needs, food and the camp's recipes. */
    val survival: SurvivalPanel = SurvivalPanel(),
    val campOpen: Boolean = false,
    /** Outposts, their stock and the followers in the field. */
    val realm: RealmPanel = RealmPanel(),
    val realmOpen: Boolean = false,
    /** The town the player stands in, or null in the wilds. */
    val settlementName: String? = null,
    /** Whether that town is a stronghold held against the player. */
    val settlementHostile: Boolean = false,
    /** Crafting currency held, for the anvil. */
    val heldCurrency: List<Held<CurrencyDefinition>> = emptyList(),
    /** The tree, skills and worlds panel. */
    val hero: HeroPanelState = HeroPanelState(),
    val message: String? = null,
    /** Prop blocks drawn as generated 3D models, by block id. */
    val propModels: Map<String, com.stratum.engine.scene.PropModel> = emptyMap(),
    /** Blueprints the build tray offers to raise. */
    val blueprints: List<BlueprintChoice> = emptyList(),
    val onRaiseBlueprint: (String) -> Unit = {},
    /** Skills cost life rather than resource: a keystone or a piece of gear says so. */
    val lifePaysCosts: Boolean = false,
    /** The paper doll and the bag, compared against the whole character. */
    val gear: GearPanelState = GearPanelState(),
    /** The place on the doll the satchel is looking at; null looks at the whole bag. */
    val gearSlot: com.stratum.core.domain.item.EquipmentSlot? = null,
    val gearInspected: String? = null,
    /** The build sandbox, in a world that has one. */
    val sandbox: SandboxPanelState = SandboxPanelState(),
    /** The last save that landed, for a moment's "Saved"; null until the first. */
    val saveNotice: SaveNotice? = null,
) {
    val isDead: Boolean get() = !player.isAlive

    fun cooldownFraction(skill: SkillDefinition): Float = player.cooldowns.fractionRemaining(skill)

    /** What one use of [skill] costs now: in life, when a keystone says skills are paid for in blood. */
    fun costOf(skill: SkillDefinition): com.stratum.core.domain.actor.SkillCost =
        com.stratum.core.domain.actor.SkillCost.of(skill, lifePaysCosts)

    /** Whether the cast would be paid for: the same rule the cast itself applies, so a lit button is a castable skill. */
    fun canAfford(skill: SkillDefinition): Boolean = costOf(skill).affordable(player.resource, player.health)

    /** Charges ready now, for a skill that stores more than one. */
    fun chargesLeft(skill: SkillDefinition): Int = player.cooldowns.chargesLeft(skill)

    /** Whether the player is winding [skill] up this moment. */
    fun isWindingUp(skill: SkillDefinition): Boolean =
        telegraphs.any { !it.hostile && it.casterId == com.stratum.engine.world.WorldSession.PLAYER_ACTOR_ID && it.skillId == skill.id }

    /** Everything the player could craft on or socket, worn gear first. */
    val anvilItems: List<ItemInstance>
        get() = player.equipment.all + player.bag

    /**
     * The item the anvil is showing. Falls back rather than showing nothing when
     * the selected item was equipped, sold or replaced out from under the panel.
     */
    val anvilItem: ItemInstance?
        get() = anvilItemId?.let { id -> anvilItems.firstOrNull { it.instanceId == id } }
            ?: anvilItems.firstOrNull()

    fun insertOrNull(insertId: String): InsertDefinition? = insertFor(insertId)

    fun rarityColor(item: ItemInstance): Long = rarityColors(item.rarity)
}

/** Used before a pack is resolved, and by previews. */
private const val DEFAULT_RARITY_TINT = 0xFFB0BEC5L

/** A blueprint as the build tray lists it. */
data class BlueprintChoice(val id: String, val name: String, val blocks: Int)
