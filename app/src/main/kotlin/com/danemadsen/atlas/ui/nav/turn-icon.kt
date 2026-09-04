package com.danemadsen.atlas.ui.nav

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.danemadsen.atlas.routing.TurnCommand

/**
 * Canvas-drawn maneuver arrows — no icon-font or image assets, so the
 * banner stays fully offline and theme-tintable. A straight arrow is
 * drawn once and rotated per command (the classic turn-arrow approach);
 * U-turn, roundabout, and arrival get dedicated shapes.
 */
@Composable
fun TurnIcon(
    command: TurnCommand,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(
            width = size.minDimension * 0.12f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        when (command) {
            TurnCommand.U_TURN -> drawUTurn(tint, stroke)
            TurnCommand.ROUNDABOUT, TurnCommand.ROUNDABOUT_LEFT ->
                drawRoundabout(command == TurnCommand.ROUNDABOUT_LEFT, tint, stroke)
            TurnCommand.ARRIVE -> drawArrival(tint, stroke)
            else -> {
                val angle = when (command) {
                    TurnCommand.STRAIGHT -> 0f
                    TurnCommand.TURN_SLIGHT_LEFT, TurnCommand.KEEP_LEFT -> -35f
                    TurnCommand.TURN_LEFT, TurnCommand.EXIT_LEFT -> -90f
                    TurnCommand.TURN_SHARP_LEFT -> -135f
                    TurnCommand.TURN_SLIGHT_RIGHT, TurnCommand.KEEP_RIGHT -> 35f
                    TurnCommand.TURN_RIGHT, TurnCommand.EXIT_RIGHT -> 90f
                    TurnCommand.TURN_SHARP_RIGHT -> 135f
                    else -> 0f
                }
                rotate(angle) { drawStraightArrow(tint, stroke) }
            }
        }
    }
}

/** A vertical arrow pointing up: shaft from the base to the head. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStraightArrow(
    tint: Color,
    stroke: Stroke,
) {
    val w = size.width
    val h = size.height
    val shaft = Path().apply {
        moveTo(w * 0.5f, h * 0.85f)
        lineTo(w * 0.5f, h * 0.30f)
    }
    val head = Path().apply {
        moveTo(w * 0.28f, h * 0.52f)
        lineTo(w * 0.5f, h * 0.30f)
        lineTo(w * 0.72f, h * 0.52f)
    }
    drawPath(shaft, tint, style = stroke)
    drawPath(head, tint, style = stroke)
}

/** A loop: up the right side, over the top, down with an arrowhead. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUTurn(
    tint: Color,
    stroke: Stroke,
) {
    val w = size.width
    val h = size.height
    val loop = Path().apply {
        moveTo(w * 0.65f, h * 0.85f)
        lineTo(w * 0.65f, h * 0.45f)
        cubicTo(w * 0.65f, h * 0.15f, w * 0.30f, h * 0.15f, w * 0.30f, h * 0.45f)
        lineTo(w * 0.30f, h * 0.70f)
    }
    val head = Path().apply {
        moveTo(w * 0.16f, h * 0.55f)
        lineTo(w * 0.30f, h * 0.72f)
        lineTo(w * 0.44f, h * 0.55f)
    }
    drawPath(loop, tint, style = stroke)
    drawPath(head, tint, style = stroke)
}

/** A circle with an exit arrow: left-exits mirror the arrow. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundabout(
    exitLeft: Boolean,
    tint: Color,
    stroke: Stroke,
) {
    val w = size.width
    val h = size.height
    val center = Offset(w * 0.42f, h * 0.55f)
    drawCircle(
        color = tint,
        radius = size.minDimension * 0.22f,
        center = center,
        style = stroke,
    )
    if (exitLeft) {
        val exit = Path().apply {
            moveTo(center.x, center.y)
            lineTo(w * 0.15f, center.y)
        }
        val head = Path().apply {
            moveTo(w * 0.28f, center.y - h * 0.10f)
            lineTo(w * 0.15f, center.y)
            lineTo(w * 0.28f, center.y + h * 0.10f)
        }
        drawPath(exit, tint, style = stroke)
        drawPath(head, tint, style = stroke)
    } else {
        val exit = Path().apply {
            moveTo(center.x, center.y)
            lineTo(w * 0.90f, center.y)
            lineTo(w * 0.90f, h * 0.30f)
        }
        val head = Path().apply {
            moveTo(w * 0.78f, h * 0.45f)
            lineTo(w * 0.90f, h * 0.30f)
            lineTo(w * 1.02f, h * 0.45f)
        }
        drawPath(exit, tint, style = stroke)
        drawPath(head, tint, style = stroke)
    }
}

/** A flag on a pole — the arrival maneuver. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrival(
    tint: Color,
    stroke: Stroke,
) {
    val w = size.width
    val h = size.height
    val pole = Path().apply {
        moveTo(w * 0.35f, h * 0.85f)
        lineTo(w * 0.35f, h * 0.20f)
    }
    val flag = Path().apply {
        moveTo(w * 0.35f, h * 0.22f)
        lineTo(w * 0.75f, h * 0.33f)
        lineTo(w * 0.35f, h * 0.44f)
        close()
    }
    drawPath(pole, tint, style = stroke)
    drawPath(flag, tint)
}