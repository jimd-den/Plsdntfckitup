package com.stratum.feature.forge

import com.stratum.core.domain.ai.GeneratedImage
import com.stratum.core.domain.ai.PoseFrameRequest
import com.stratum.core.domain.ai.PoseRunRecord
import com.stratum.core.domain.ai.SavedCharacter
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.FrameAnalysis
import com.stratum.core.domain.sprite.PackedSheet
import com.stratum.core.domain.sprite.PoseGuides
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The forge's view model, driven end to end with fakes: the part of the
 * pipeline that had no tests at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PoseForgeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val disk = mutableMapOf<String, ByteArray>()
    private val records = mutableMapOf<String, PoseRunRecord>()
    private val asked = mutableListOf<PoseFrameRequest>()
    private val packed = mutableListOf<String>()
    private var blankFirstTake = emptySet<String>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = PoseForgeViewModel(
        drawReference = { _, _ -> Result.success(GeneratedImage("ref".toByteArray(), "image/png", 8, 8)) },
        drawPose = { request, _ ->
            asked += request
            Result.success(GeneratedImage("${request.step.key}#${request.take}".toByteArray(), "image/png", 8, 8))
        },
        guideFor = { _, _ -> null },
        readGuideImage = { null },
        readGuideJson = { null },
        loadGuides = { PoseGuides() },
        saveGuides = { _, _ -> },
        saveReference = { _, bytes -> disk["reference"] = bytes },
        loadReference = { disk["reference"] },
        hasReference = { "reference" in disk },
        savePose = { _, key, bytes -> disk[key] = bytes },
        dropPose = { _, key -> disk.remove(key) },
        posesDrawn = { disk.keys.filter { it != "reference" }.toSet() },
        loadPose = { _, key -> disk[key] },
        composeSheet = { setId, plan -> packed += setId; PackedSheet(plan.sheet, emptyList()) },
        savedCharacters = { emptyList<SavedCharacter>() },
        deleteCharacter = {},
        exportSheet = { _, _ -> true },
        exportPoses = { _, _ -> true },
        exportReference = { _, _ -> true },
        exportClip = { _, _, _ -> true },
        clipsDrawn = { emptySet() },
        drawClipRow = { _, _ -> Result.failure(IllegalStateException("none")) },
        saveClip = { _, _, _ -> },
        isProviderConfigured = { true },
        inspectPose = { bytes ->
            val text = String(bytes)
            // A frame whose first take is in the blank set comes back empty.
            if (text.substringBefore('#') in blankFirstTake && text.endsWith("#0")) {
                FrameAnalysis(8, 8, null, null, 0, emptySet(), emptyList(), null)
            } else {
                null
            }
        },
        saveRunRecord = { records[it.setId] = it },
        loadRunRecord = { records[it] },
        runRecords = { records.values.toList() },
        launchRun = { block -> CoroutineScope(dispatcher).launch(block = block) },
        reportProgress = { _, _, _ -> },
        stopRun = {},
        isRunning = { false },
        sleep = {},
        io = dispatcher,
        compute = dispatcher,
    )

    private fun PoseForgeViewModel.ready(): PoseForgeViewModel = apply {
        updateSubject("Bronze Warrior")
        selectFrames(AnimationState.WALK, 3)
        drawReferencePose()
    }

    @Test
    fun `a run draws every frame, records itself, and packs the sheet`() {
        val vm = viewModel().ready()
        vm.buildAnimations()

        val state = vm.state.value
        assertFalse(state.busy)
        assertEquals(state.total, state.completed)
        assertEquals(3, asked.count { it.step.state == AnimationState.WALK })
        assertTrue(asked.filter { it.step.state == AnimationState.WALK }.all { it.step.frameCount == 3 })
        assertEquals(listOf("hero:bronze_warrior"), packed)
        assertNotNull(state.savedSheet)
        val record = assertNotNull(records["hero:bronze_warrior"])
        assertFalse(record.active, "a finished run must not be offered for resuming")
        assertEquals(3, record.frames[AnimationState.WALK])
    }

    @Test
    fun `a blank frame is asked for again with a new take`() {
        blankFirstTake = setOf("walk_1")
        val vm = viewModel().ready()
        vm.buildAnimations()

        assertEquals(listOf(0, 1), asked.filter { it.step.key == "walk_1" }.map { it.take })
        assertEquals("walk_1#1", String(disk.getValue("walk_1")))
        assertEquals(1, records.getValue("hero:bronze_warrior").takes["walk_1"])
    }

    @Test
    fun `redrawing a frame asks for the next take, never the cached one`() {
        val vm = viewModel().ready()
        vm.buildAnimations()
        asked.clear()

        vm.redrawPose("walk_2")
        vm.buildAnimations()

        assertEquals(listOf("walk_2" to 1), asked.map { it.step.key to it.take })
    }

    @Test
    fun `a run the process died in is offered, and resumes as it was set up`() {
        disk["reference"] = "ref".toByteArray()
        disk["walk_0"] = "old".toByteArray()
        records["hero:bronze_warrior"] = PoseRunRecord(
            setId = "hero:bronze_warrior",
            subject = "Bronze Warrior",
            frames = mapOf(AnimationState.WALK to 4),
            cellSize = 256,
            active = true,
        )
        val vm = viewModel()
        assertEquals("hero:bronze_warrior", vm.state.value.interruptedRun?.setId)

        vm.resumeInterrupted()

        assertNull(vm.state.value.interruptedRun)
        assertEquals(256, vm.state.value.cellSize)
        val walk = asked.filter { it.step.state == AnimationState.WALK }.map { it.step.key }
        assertEquals(listOf("walk_1", "walk_2", "walk_3"), walk, "the frame on disk was drawn again, or the count was lost")
    }

    @Test
    fun `dismissing an interrupted run clears it for good`() {
        records["hero:x"] = PoseRunRecord("hero:x", "X", active = true)
        val vm = viewModel()
        vm.dismissInterrupted()
        assertNull(vm.state.value.interruptedRun)
        assertFalse(records.getValue("hero:x").active)
    }
}
