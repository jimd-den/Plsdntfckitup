package com.stratum.core.data.sprite

import androidx.test.core.app.ApplicationProvider
import com.stratum.core.domain.sprite.PoseGuideMode
import com.stratum.core.domain.sprite.PoseGuideStyle
import com.stratum.core.domain.sprite.PoseGuides
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class PoseGuideStoreTest {

    private val store = PoseGuideStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `choosing the style that used to be the default still sticks`() {
        // The trap: absence-of-a-record used to be decided by comparing
        // against a hand-copied set of the default's fields. The moment the
        // default style changed, picking the old default looked like picking
        // nothing -- so it was dropped and came back as the new default, and
        // the person's choice silently would not take.
        store.save("hero:a", PoseGuides(style = PoseGuideStyle.DIAGRAM))
        assertEquals(PoseGuideStyle.DIAGRAM, store.guidesFor("hero:a").style)
    }

    @Test
    fun `the untouched default is stored as nothing at all`() {
        store.save("hero:b", PoseGuides())
        assertEquals(PoseGuides().style, store.guidesFor("hero:b").style)
        assertEquals(PoseGuides().mode, store.guidesFor("hero:b").mode)
    }

    @Test
    fun `a mode chosen with nothing imported yet survives`() {
        // The older bug this rule already existed for, kept honest.
        store.save("hero:c", PoseGuides(mode = PoseGuideMode.IMPORTED))
        assertEquals(PoseGuideMode.IMPORTED, store.guidesFor("hero:c").mode)
    }
}
