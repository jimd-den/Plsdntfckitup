package com.stratum.core.data.sprite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.stratum.core.domain.ai.GeneratedImage
import com.stratum.core.domain.ai.PoseRunRecord
import com.stratum.core.domain.sprite.AnimationState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PoseRunStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a run record is kept beside the poses and never counted as one`() {
        val poses = PoseLibrary(context)
        poses.savePose("hero:rec", "walk_0", byteArrayOf(1, 2, 3))
        val record = PoseRunRecord("hero:rec", "Rec", frames = mapOf(AnimationState.WALK to 12), active = true)
        poses.saveRunRecord(record)

        assertEquals(record, poses.runRecord("hero:rec"))
        assertEquals(setOf("walk_0"), poses.keysIn("hero:rec"))
        assertTrue(poses.runRecords().any { it.setId == "hero:rec" && it.active })
        assertNull(poses.runRecord("hero:none"))
    }

    @Test
    fun `an atomic write leaves no temporary file behind`() {
        val dir = File(context.cacheDir, "atomic").apply { deleteRecursively(); mkdirs() }
        val target = File(dir, "pose.png")
        AtomicFiles.write(target, byteArrayOf(1))
        AtomicFiles.write(target, byteArrayOf(2, 3))
        assertEquals(listOf(2, 3), target.readBytes().map { it.toInt() })
        assertEquals(listOf("pose.png"), dir.list()!!.toList())
    }

    @Test
    fun `the image cache returns what it was given and evicts the oldest past its limit`() {
        val dir = File(context.cacheDir, "images").apply { deleteRecursively() }
        val cache = FileImageCache(dir, maxBytes = 10)
        val first = GeneratedImage(ByteArray(6) { 1 }, "image/png", 4, 3)
        cache.put("aaaaaaaaaaaaaaaa", first)
        assertEquals(first, cache.get("aaaaaaaaaaaaaaaa"))

        File(dir.listFiles()!!.single().path).setLastModified(1_000L)
        cache.put("bbbbbbbbbbbbbbbb", GeneratedImage(ByteArray(6) { 2 }, "image/png", 4, 3))
        assertNull(cache.get("aaaaaaaaaaaaaaaa"))
        assertEquals(4, cache.get("bbbbbbbbbbbbbbbb")?.width)
        cache.put("../escape", first)
        assertNull(cache.get("../escape"))
    }
}
