package com.stratum.core.domain.bvh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BvhParserTest {

    private val sampleBvh = """
HIERARCHY
ROOT Hips
{
  OFFSET 0.00 0.00 0.00
  CHANNELS 6 Xposition Yposition Zposition Zrotation Yrotation Xrotation
  JOINT Spine
  {
    OFFSET 0.00 12.00 0.00
    CHANNELS 3 Zrotation Yrotation Xrotation
    End Site
    {
      OFFSET 0.00 10.00 0.00
    }
  }
}
MOTION
Frames: 2
Frame Time: 0.0333333
1.0 2.0 3.0 10.0 20.0 30.0 5.0 6.0 7.0
1.5 2.5 3.5 12.0 22.0 32.0 6.0 7.0 8.0
""".trimIndent()

    @Test
    fun `parses root and child joints accurately`() {
        val result = BvhParser.parse(sampleBvh, "test_clip")
        assertTrue(result.isSuccess, "BVH parse should succeed: ${result.exceptionOrNull()?.message}")

        val clip = result.getOrThrow()
        assertEquals("test_clip", clip.name)
        assertEquals("Hips", clip.root.name)
        assertEquals(6, clip.root.channels.size)
        assertEquals(1, clip.root.children.size)

        val spine = clip.root.children[0]
        assertEquals("Spine", spine.name)
        assertEquals(3, spine.channels.size)
        assertEquals(12f, spine.offset[1])
        assertNotNull(spine.endSiteOffset)
        assertEquals(10f, spine.endSiteOffset!![1])
    }

    @Test
    fun `parses motion section frames and frame time`() {
        val clip = BvhParser.parse(sampleBvh).getOrThrow()
        assertEquals(2, clip.frameCount)
        assertEquals(0.0333333f, clip.frameTime, 0.0001f)

        val frame0 = clip.frameAt(0)
        assertNotNull(frame0)
        assertEquals(9, frame0.values.size)
        assertEquals(1.0f, frame0.values[0])
        assertEquals(2.0f, frame0.values[1])
        assertEquals(3.0f, frame0.values[2])
        assertEquals(10.0f, frame0.values[3])
        assertEquals(7.0f, frame0.values[8])

        val frame1 = clip.frameAt(1)
        assertNotNull(frame1)
        assertEquals(1.5f, frame1.values[0])
    }

    @Test
    fun `fails gracefully on empty or invalid text`() {
        assertTrue(BvhParser.parse("").isFailure)
        assertTrue(BvhParser.parse("INVALID HEADER").isFailure)
    }
}
