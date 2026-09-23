package com.stratum.feature.play

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.item.ItemInstance
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.World
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import com.stratum.engine.world.IsometricProjection
import com.stratum.engine.world.AttackReport
import com.stratum.engine.world.CombatEvent
import com.stratum.engine.world.BuildResult
import com.stratum.engine.world.BuildTool
import com.stratum.engine.world.DodgeResult
import com.stratum.core.domain.sprite.AnimationPlayback
import com.stratum.engine.world.FeedbackMark
import com.stratum.engine.world.EquipResult
import com.stratum.engine.world.GroundInsert
import com.stratum.engine.world.GroundLoot
import com.stratum.engine.world.HeldInsert
import com.stratum.engine.world.SocketResult
import com.stratum.engine.world.MineResult
import com.stratum.engine.world.PlaceRejection
import com.stratum.engine.world.PlaceResult
import com.stratum.engine.world.ReviveResult
import com.stratum.core.domain.art.ArtDirection
import com.stratum.core.domain.art.BiomeArtKit
import com.stratum.core.domain.art.StyleLexicon
import com.stratum.core.domain.art.StyleSheetArtDirector
import com.stratum.core.domain.art.WorldArtDirector
import com.stratum.core.domain.art.WorldTime
import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.engine.world.WorldSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.android.awaitFrame

/**
 * Drives one play session.
 *
 * The view model owns no game rules. It forwards intent to [WorldSession] and
 * republishes the resulting snapshot, so the rules stay in the pure engine where
 * they can be tested without Android.
 */
class PlayViewModel(
    private val content: AssembledContent,
    private val config: WorldConfig,
    private val heroClassId: String? = null,
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
    private val kitDirectory: java.io.File? = null,
) : ViewModel() {

    /**
     * Replaced wholesale by [newRun]. Every reader goes through this field
     * rather than capturing it, so starting a fresh world cannot leave a lambda
     * pointing at the world the player just left.
     */
    private var session = WorldSession(content, config, heroClassId)

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

    init {
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
            kit = com.stratum.feature.play.gl.ForgedKits.kitFor(artDirector.direction),
        )
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
    fun forgeStyle() {
        val model = imageModel ?: return publish(message = "Add an OpenRouter key in settings to forge art")
        val root = kitDirectory ?: return publish(message = "No storage for forged art")
        if (_state.value.forging != null) return
        val direction = artDirector.direction
        val folder = java.io.File(root, "style-" + direction.id.replace(Regex("[^a-zA-Z0-9+_-]"), "_")).apply { mkdirs() }
        val biome = session.currentBiome.id
        val pack = content.packs.firstOrNull { p -> p.biomes.any { it.id == biome } } ?: content.packs.first()
        val orders = com.stratum.core.domain.art.ForgePlanner.plan(direction, pack, setOf(biome))
            .filterNot { java.io.File(folder, com.stratum.feature.play.gl.ForgedKits.fileNameFor(it.key)).exists() }
        if (orders.isEmpty()) {
            _state.value = _state.value.copy(kit = com.stratum.feature.play.gl.ForgedKits.LOCAL + folder.absolutePath)
            return publish(message = "This style is already forged")
        }
        val forge = com.stratum.engine.scene.forge.AssetForge(model, com.stratum.feature.play.gl.AndroidImageCodec)
        _state.value = _state.value.copy(forging = "Forging 0/${orders.size}")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var done = 0
            var made = 0
            forge.forge(orders, concurrency = 3) { asset ->
                done++
                asset.texture?.let { texture ->
                    java.io.File(folder, com.stratum.feature.play.gl.ForgedKits.fileNameFor(asset.order.key))
                        .writeBytes(com.stratum.feature.play.gl.AndroidImageCodec.encodePng(texture))
                    made++
                }
                _state.value = _state.value.copy(
                    forging = "Forging $done/${orders.size}",
                    kit = com.stratum.feature.play.gl.ForgedKits.LOCAL + folder.absolutePath,
                )
            }
            // Back on the main thread: publishing reads the session, which the
            // game loop mutates there.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                _state.value = _state.value.copy(forging = null)
                publish(message = "Forged $made of ${orders.size}")
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
    }

    // ---- dying -----------------------------------------------------------

    /**
     * Gets back up in the same world, keeping the character and everything on
     * it. The engine decides what that costs.
     */
    fun revive() {
        when (val result = session.revive()) {
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
     * Throws the world away and starts another, with the same class and a new
     * seed. Everything the character had goes with it — that is the difference
     * between this and [revive].
     */
    fun newRun() {
        miningJob?.cancel()
        loopJob?.cancel()
        session = WorldSession(content, config.copy(seed = System.nanoTime()), heroClassId)
        // The panels belong to the run that just ended; a fresh world opens on
        // the world, not on someone else's bag.
        _state.value = initialState(content)
        publish(message = "A new world.")
        startLoop()
    }

    // ---- the satchel -----------------------------------------------------

    /** Opens or closes the bag. Like the anvil, it does not pause the world. */
    fun toggleSatchel() {
        _state.value = _state.value.copy(satchelOpen = !_state.value.satchelOpen)
        publish()
    }

    fun equip(instanceId: String) {
        publish(message = describe(session.equip(instanceId)))
    }

    fun discard(instanceId: String) {
        publish(message = describe(session.discard(instanceId)))
    }

    private fun describe(result: EquipResult): String = when (result) {
        is EquipResult.Equipped -> "Equipped ${result.item.name}"
        is EquipResult.Discarded -> "Dropped ${result.item.name}"
        EquipResult.NotInBag -> "That is not in your bag"
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
        kit = com.stratum.feature.play.gl.ForgedKits.kitFor(artDirector.direction),
        // A lambda rather than a bound reference: starting a fresh world
        // replaces the session, and a captured reference would keep answering
        // for the world the player just left.
        biomeAt = { x, y -> session.biomeAt(x, y) },
    )

    /**
     * The joystick reports where the thumb is, every frame it moves. The session
     * integrates it on its own clock, so this only records intent.
     */
    fun setMoveInput(dx: Float, dy: Float) {
        session.setMoveInput(dx, dy)
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
            worldTime = WorldTime(elapsedSeconds = elapsed),
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
            frame = _state.value.frame + 1,
            message = message ?: _state.value.message,
        )
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

    override fun onCleared() {
        miningJob?.cancel()
        loopJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000f
        /** Below this a step is noise; it also covers the very first frame. */
        private const val MIN_STEP = 1f / 240f
        /** A frame longer than this is a stall, and is served in slow motion. */
        private const val MAX_STEP = 1f / 15f
        private const val MIN_ZOOM = 0.6f
        private const val MAX_ZOOM = 2.2f

        fun factory(
            content: AssembledContent,
            config: WorldConfig,
            heroClassId: String? = null,
            spriteResolver: (SpriteKey) -> DrawableSprite? = { null },
            imageModel: com.stratum.core.domain.ai.ImageModelPort? = null,
            kitDirectory: java.io.File? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PlayViewModel(content, config, heroClassId, spriteResolver, imageModel = imageModel, kitDirectory = kitDirectory) as T
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
    /** What the player last asked for, so the field can show it back to them. */
    val stylePrompt: String = "",
    /** What the game understood by it, which is how a player learns the vocabulary. */
    val styleSummary: String = "",
    val styleOpen: Boolean = false,
    /** Which forged asset kit the 3D view draws with. */
    val kit: String = "house",
    /** The lit 3D view, or the flat 2D canvas it replaced. */
    val use3D: Boolean = true,
    /** Progress of an art forge in flight, or null when none is running. */
    val forging: String? = null,
    val message: String? = null,
) {
    val isDead: Boolean get() = !player.isAlive

    fun cooldownFraction(skill: SkillDefinition): Float = player.cooldowns.fractionRemaining(skill)

    fun canAfford(skill: SkillDefinition): Boolean = player.resource >= skill.resourceCost

    /** Everything the player could socket, equipped weapon first. */
    val anvilItems: List<ItemInstance>
        get() = (listOfNotNull(player.equippedWeapon) + player.bag).filter { it.socketCount > 0 }

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
