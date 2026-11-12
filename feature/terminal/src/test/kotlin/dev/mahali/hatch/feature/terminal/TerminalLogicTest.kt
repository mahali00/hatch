package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.core.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotTest {
    @Test fun `a second tap disarms`() {
        val once = OneShot.None.toggle(ModKey.Ctrl)
        assertTrue(once.mods.ctrl)
        assertEquals(OneShot.None, once.toggle(ModKey.Ctrl))
    }

    @Test fun `armed modifiers combine and super is meta`() {
        val mods = OneShot.None.toggle(ModKey.Ctrl).toggle(ModKey.Shift).toggle(ModKey.Super).mods
        assertEquals("shift+ctrl+super", mods.wire())
        assertFalse(mods.alt)
    }
}

class JoystickTest {
    @Test fun `inside the dead zone sends nothing`() = assertNull(joystickDirection(3f, -4f, deadZone = 10f))

    @Test fun `straight drags send one arrow`() {
        assertEquals(listOf(Arrow.Right), joystickDirection(30f, 2f, 10f)!!.arrows)
        assertEquals(listOf(Arrow.Left), joystickDirection(-30f, 2f, 10f)!!.arrows)
        assertEquals(listOf(Arrow.Up), joystickDirection(1f, -30f, 10f)!!.arrows)
        assertEquals(listOf(Arrow.Down), joystickDirection(1f, 30f, 10f)!!.arrows)
    }

    @Test fun `diagonals send both arrows and point the wedge between them`() {
        val upLeft = joystickDirection(-20f, -20f, 10f)!!
        assertEquals(setOf(Arrow.Up, Arrow.Left), upLeft.arrows.toSet())
        assertEquals(225f, upLeft.degrees)
    }

}

class ScrollModeTest {
    @Test fun `mouse reporting wins`() = assertEquals(ScrollMode.Wheel, scrollMode(alternateScreen = true, mouseTracking = true, alternateScroll = true))

    @Test fun `alternate screen uses arrows only with 1007`() {
        assertEquals(ScrollMode.Arrows, scrollMode(alternateScreen = true, mouseTracking = false, alternateScroll = true))
        assertEquals(ScrollMode.None, scrollMode(alternateScreen = true, mouseTracking = false, alternateScroll = false))
    }

    @Test fun `normal screen scrolls history`() = assertEquals(ScrollMode.History, scrollMode(alternateScreen = false, mouseTracking = false, alternateScroll = true))

    @Test fun `lines carry their remainder`() {
        val lines = LineAccumulator(20f)
        assertEquals(0, lines.add(15f))
        assertEquals(1, lines.add(10f))
        assertEquals(-1, lines.add(-25f))
    }
}

class LinksTest {
    @Test fun `finds the url under the index`() {
        val text = "open https://github.com/rex/pull/42 now"
        assertEquals(5..34, urlAt(text, 10))
        assertNull(urlAt(text, 2))
        assertNull(urlAt(text, 37))
    }

    @Test fun `trailing punctuation is not part of the url`() {
        val text = "(see https://example.com/a.)"
        assertEquals("https://example.com/a", text.substring(urlAt(text, 10)!!))
    }

    @Test fun `balanced brackets stay`() {
        val text = "https://en.wikipedia.org/wiki/Rex_(app)"
        assertEquals(text, text.substring(urlAt(text, 3)!!))
    }
}

class WordsTest {
    @Test fun `selects a path as one word`() {
        val text = "cat ~/Developer/rex/SPEC.md | less"
        assertEquals("~/Developer/rex/SPEC.md", text.substring(wordAt(text, 10)))
    }

    @Test fun `a space selects itself`() = assertEquals(3..3, wordAt("cat file", 3))

    @Test fun `outside the text selects nothing`() = assertTrue(wordAt("abc", 7).isEmpty())
}

class TilesTest {
    private val columns = List(4) { Rect(it * 0.25f, 0f, 0.25f, 1f) }
    private val halves = listOf(Rect(0f, 0f, 0.5f, 1f), Rect(0.5f, 0f, 0.5f, 1f))

    @Test fun `four columns on an unfolded phone page instead of tiling`() = assertFalse(tiles(columns, 750f, 700f))

    @Test fun `a quarter-height tile still tiles on the Fold`() {
        val five = listOf(Rect(0f, 0f, 0.5f, 0.5f), Rect(0f, 0.5f, 0.5f, 0.25f), Rect(0f, 0.75f, 0.5f, 0.25f), Rect(0.5f, 0f, 0.5f, 0.5f), Rect(0.5f, 0.5f, 0.5f, 0.5f))
        assertTrue(tiles(five, 456f, 690f))
    }

    @Test fun `a two by two grid tiles on the Fold beside the widest sidebar`() {
        val grid = List(4) { Rect((it % 2) * 0.5f, (it / 2) * 0.5f, 0.5f, 0.5f) }
        assertTrue(tiles(grid, 750f * 0.55f, 700f))
    }

    @Test fun `two halves tile when each has room`() = assertTrue(tiles(halves, 840f, 700f))

    @Test fun `too short tiles page`() = assertFalse(tiles(halves, 840f, 130f))

    @Test fun `seven panes never tile`() = assertFalse(tiles(List(7) { Rect(0f, it / 7f, 1f, 1f / 7f) }, 4000f, 4000f))

    @Test fun `one tall pane beside a two by two grid tiles on a large window`() {
        val five = listOf(Rect(0f, 0f, 0.4f, 1f)) + List(4) { Rect(0.4f + (it % 2) * 0.3f, (it / 2) * 0.5f, 0.3f, 0.5f) }
        assertTrue(tiles(five, 884f, 900f))
        assertFalse(tiles(five, 600f, 900f))
    }
}

class PullToSplitTest {
    @Test fun `below the threshold stays idle and springs back`() {
        assertEquals(Pull.Idle, pullState(71f, 72f))
        assertFalse(splitsOnRelease(71f, 72f))
    }

    @Test fun `at or past the threshold arms and splits on release`() {
        assertEquals(Pull.Armed, pullState(72f, 72f))
        assertTrue(splitsOnRelease(200f, 72f))
    }

    @Test fun `a quick swipe past the last pane does not split, a held pull does`() {
        assertFalse(splitsOnRelease(900f, 72f, speed = 4000f, maxSpeed = 1500f))
        assertTrue(splitsOnRelease(900f, 72f, speed = 200f, maxSpeed = 1500f))
    }

}

class DropZoneTest {
    @Test fun `the centre third swaps`() {
        assertEquals(DropZone.Centre, dropZone(50f, 50f, 100f, 100f))
        assertEquals(DropZone.Centre, dropZone(40f, 60f, 100f, 100f))
    }

    @Test fun `the nearest edge wins elsewhere`() {
        assertEquals(DropZone.Left, dropZone(10f, 50f, 100f, 100f))
        assertEquals(DropZone.Right, dropZone(90f, 50f, 100f, 100f))
        assertEquals(DropZone.Top, dropZone(50f, 10f, 100f, 100f))
        assertEquals(DropZone.Bottom, dropZone(50f, 90f, 100f, 100f))
    }

    @Test fun `diagonals divide a wide pane`() {
        // 400 x 100: 30 from the left edge is nearer, as a fraction, than 20 from the top.
        assertEquals(DropZone.Left, dropZone(30f, 20f, 400f, 100f))
        assertEquals(DropZone.Top, dropZone(100f, 10f, 400f, 100f))
        assertEquals(DropZone.Bottom, dropZone(300f, 95f, 400f, 100f))
    }

}
