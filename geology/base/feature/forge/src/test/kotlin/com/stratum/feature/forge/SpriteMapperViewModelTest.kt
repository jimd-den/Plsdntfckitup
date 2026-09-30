package com.stratum.feature.forge

import com.stratum.core.domain.sprite.AnimationClip
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.SourceRect
import com.stratum.core.domain.sprite.SpriteAtlas
import com.stratum.core.domain.sprite.SpriteMapper
import com.stratum.core.domain.sprite.SpriteSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SpriteMapperViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val sheet = SpriteSheet(
        id = "hero:t", name = "T", columns = 4, rows = 1, frameWidth = 64, frameHeight = 64,
        clips = listOf(AnimationClip(AnimationState.IDLE, 0, 2), AnimationClip(AnimationState.WALK, 2, 2)),
    )

    private val saved = mutableListOf<SpriteAtlas>()

    private fun viewModel() = SpriteMapperViewModel(
        openProject = { null },
        sourcePixels = { null },
        startProject = { SpriteMapper.fromSheet(it, imageWidth = 256, imageHeight = 64) },
        saveProject = { saved += it },
        bakeAtlas = { atlas -> sheet.copy(name = atlas.name) },
        loadSheets = { listOf(sheet) },
        compute = dispatcher,
    )

    @Test
    fun `edits undo in order and a slipped box leaves the grid alone`() {
        val vm = viewModel()
        vm.open(sheet)
        val original = vm.state.value.atlas

        vm.setGrid(columns = 2, rows = 1)
        assertEquals(2, vm.state.value.slice?.columns)
        vm.setGridFromBox(SourceRect(0, 0, 2, 2))
        assertNotNull(vm.state.value.error)
        assertEquals(2, vm.state.value.slice?.columns)

        vm.undo()
        assertEquals(original, vm.state.value.atlas)
    }

    @Test
    fun `stepping through frames wraps`() {
        val vm = viewModel()
        vm.open(sheet)
        val frames = vm.state.value.atlas!!.frames
        vm.stepFrame(forward = false)
        assertEquals(frames.first().id, vm.state.value.focusFrameId)
        vm.stepFrame(forward = false)
        assertEquals(frames.last().id, vm.state.value.focusFrameId)
    }

    @Test
    fun `baking saves the project and reports the sheet`() {
        val vm = viewModel()
        vm.open(sheet)
        vm.bake()
        assertEquals(1, saved.size)
        assertNotNull(vm.state.value.savedSheet)
        assertNull(vm.state.value.error)
    }
}
