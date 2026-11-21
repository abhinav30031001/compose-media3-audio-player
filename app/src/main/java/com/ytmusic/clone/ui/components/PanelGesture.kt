package com.ytmusic.clone.ui.components

import kotlin.math.abs

/**
 * v7.21 / v7.22: pure decision logic for player swipe gestures (JVM-testable, no Android).
 *  - full player: sideways swipe changes song, swipe down collapses, swipe up opens queue
 *  - mini player: sideways swipe changes song, swipe up expands full player
 * The axis is locked by the first movement of a drag, so diagonal drifts don't trigger wrong actions.
 */
object PanelGesture {
    enum class Axis { NONE, HORIZONTAL, VERTICAL }
    enum class Action { NONE, NEXT, PREVIOUS, COLLAPSE, OPEN_QUEUE }
    enum class MiniPlayerAction { NONE, NEXT, PREVIOUS, EXPAND }

    /** Called with the first movement of a drag (after touch slop). */
    fun lockAxis(dx: Float, dy: Float): Axis =
        if (abs(dx) > abs(dy)) Axis.HORIZONTAL else Axis.VERTICAL

    /** Full player drag decision when the finger is lifted. Thresholds in px. */
    fun decide(
        axis: Axis,
        dragX: Float,
        dragY: Float,
        sideThreshold: Float,
        downThreshold: Float,
        upThreshold: Float
    ): Action = when (axis) {
        Axis.HORIZONTAL -> when {
            dragX <= -sideThreshold -> Action.NEXT
            dragX >= sideThreshold -> Action.PREVIOUS
            else -> Action.NONE
        }
        Axis.VERTICAL -> when {
            dragY >= downThreshold -> Action.COLLAPSE
            dragY <= -upThreshold -> Action.OPEN_QUEUE
            else -> Action.NONE
        }
        Axis.NONE -> Action.NONE
    }

    /** Mini player drag decision: horizontal = change track, vertical up = expand player. */
    fun decideMini(
        axis: Axis,
        dragX: Float,
        dragY: Float,
        sideThreshold: Float,
        expandThreshold: Float
    ): MiniPlayerAction = when (axis) {
        Axis.HORIZONTAL -> when {
            dragX <= -sideThreshold -> MiniPlayerAction.NEXT
            dragX >= sideThreshold -> MiniPlayerAction.PREVIOUS
            else -> MiniPlayerAction.NONE
        }
        Axis.VERTICAL -> when {
            dragY <= -expandThreshold -> MiniPlayerAction.EXPAND
            else -> MiniPlayerAction.NONE
        }
        Axis.NONE -> MiniPlayerAction.NONE
    }

    /**
     * v7.23: where a finger-following sheet should settle when released.
     * [progress] 0 = closed, 1 = fully open. [velocityY] in px/s (negative = moving up).
     * A quick flick wins over position; otherwise it settles to whichever end is closer.
     */
    fun settleExpanded(progress: Float, velocityY: Float, flingVelocity: Float): Boolean = when {
        velocityY <= -flingVelocity -> true
        velocityY >= flingVelocity -> false
        else -> progress >= 0.5f
    }
}
