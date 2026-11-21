package com.ytmusic.clone

import com.ytmusic.clone.ui.components.PanelGesture
import com.ytmusic.clone.ui.components.PanelGesture.Action
import com.ytmusic.clone.ui.components.PanelGesture.Axis
import org.junit.Assert.assertEquals
import org.junit.Test

/** v7.21: full-player swipe decisions (pure logic). */
class PanelGestureTest {
    private val side = 100f
    private val down = 120f
    private val up = 80f

    private fun decide(axis: Axis, x: Float, y: Float) = PanelGesture.decide(axis, x, y, side, down, up)

    @Test fun axisLocksOnTheLargerFirstMovement() {
        assertEquals(Axis.HORIZONTAL, PanelGesture.lockAxis(12f, 3f))
        assertEquals(Axis.VERTICAL, PanelGesture.lockAxis(3f, -12f))
    }

    @Test fun sidewaysSwipeChangesSong() {
        assertEquals(Action.NEXT, decide(Axis.HORIZONTAL, -150f, 0f))
        assertEquals(Action.PREVIOUS, decide(Axis.HORIZONTAL, 150f, 0f))
    }

    @Test fun shortSidewaysSwipeDoesNothing() {
        assertEquals(Action.NONE, decide(Axis.HORIZONTAL, 60f, 0f))
        assertEquals(Action.NONE, decide(Axis.HORIZONTAL, -99f, 0f))
    }

    @Test fun downCollapsesAndUpOpensQueue() {
        assertEquals(Action.COLLAPSE, decide(Axis.VERTICAL, 0f, 200f))
        assertEquals(Action.OPEN_QUEUE, decide(Axis.VERTICAL, 0f, -100f))
    }

    @Test fun shortVerticalSwipeDoesNothing() {
        assertEquals(Action.NONE, decide(Axis.VERTICAL, 0f, 90f))
        assertEquals(Action.NONE, decide(Axis.VERTICAL, 0f, -50f))
    }

    @Test fun lockedAxisIgnoresTheOtherDirection() {
        // a sideways swipe that also drifted down far must NOT collapse the player
        assertEquals(Action.NEXT, decide(Axis.HORIZONTAL, -150f, 400f))
        // a vertical swipe that drifted sideways must NOT change the song
        assertEquals(Action.COLLAPSE, decide(Axis.VERTICAL, -300f, 200f))
        assertEquals(Action.NONE, decide(Axis.NONE, -300f, 300f))
    }

    @Test fun miniPlayerSwipesAndUpwardDrag() {
        // Horizontal swipes switch track
        assertEquals(PanelGesture.MiniPlayerAction.NEXT, PanelGesture.decideMini(Axis.HORIZONTAL, -80f, 0f, 50f, 30f))
        assertEquals(PanelGesture.MiniPlayerAction.PREVIOUS, PanelGesture.decideMini(Axis.HORIZONTAL, 80f, 0f, 50f, 30f))
        assertEquals(PanelGesture.MiniPlayerAction.NONE, PanelGesture.decideMini(Axis.HORIZONTAL, 20f, 0f, 50f, 30f))

        // Dragging UP expands the full player
        assertEquals(PanelGesture.MiniPlayerAction.EXPAND, PanelGesture.decideMini(Axis.VERTICAL, 0f, -40f, 50f, 30f))
        // Dragging down does not expand
        assertEquals(PanelGesture.MiniPlayerAction.NONE, PanelGesture.decideMini(Axis.VERTICAL, 0f, 40f, 50f, 30f))
        // Too small drag up does nothing
        assertEquals(PanelGesture.MiniPlayerAction.NONE, PanelGesture.decideMini(Axis.VERTICAL, 0f, -10f, 50f, 30f))
    }

    // v7.23: sheet settle
    @Test fun flickUpOpensEvenFromLowProgress() {
        assertEquals(true, PanelGesture.settleExpanded(0.1f, -2000f, 800f))
    }

    @Test fun flickDownClosesEvenFromHighProgress() {
        assertEquals(false, PanelGesture.settleExpanded(0.9f, 2000f, 800f))
    }

    @Test fun slowReleaseSettlesToNearestEnd() {
        assertEquals(true, PanelGesture.settleExpanded(0.6f, 100f, 800f))
        assertEquals(false, PanelGesture.settleExpanded(0.4f, -100f, 800f))
    }
}
