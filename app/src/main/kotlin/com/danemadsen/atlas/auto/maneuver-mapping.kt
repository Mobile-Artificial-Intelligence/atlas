package com.danemadsen.atlas.auto

import androidx.car.app.navigation.model.Maneuver
import com.danemadsen.atlas.routing.TurnCommand

/**
 * Pure TurnCommand -> Maneuver.Type mapping for the Android Auto
 * NavigationTemplate. Exhaustive `when` over the 15 [TurnCommand]
 * members so a new maneuver kind fails the build rather than rendering
 * an empty car maneuver icon.
 */
fun maneuverType(command: TurnCommand): Int = when (command) {
    TurnCommand.STRAIGHT -> Maneuver.TYPE_STRAIGHT
    TurnCommand.TURN_LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
    TurnCommand.TURN_SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
    TurnCommand.TURN_SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
    TurnCommand.TURN_RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
    TurnCommand.TURN_SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
    TurnCommand.TURN_SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
    TurnCommand.KEEP_LEFT -> Maneuver.TYPE_KEEP_LEFT
    TurnCommand.KEEP_RIGHT -> Maneuver.TYPE_KEEP_RIGHT
    TurnCommand.U_TURN -> Maneuver.TYPE_U_TURN_LEFT
    TurnCommand.ROUNDABOUT -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
    TurnCommand.ROUNDABOUT_LEFT -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
    TurnCommand.EXIT_LEFT -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
    TurnCommand.EXIT_RIGHT -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
    TurnCommand.ARRIVE -> Maneuver.TYPE_DESTINATION
}

/** The renderable [Maneuver] for a [TurnCommand]. */
fun maneuver(command: TurnCommand): Maneuver {
    val builder = Maneuver.Builder(maneuverType(command))
    // Roundabout maneuver types mandate an exit number; Atlas's graph does
    // not track exits, so report the first exit — the cue line carries the
    // real instruction.
    if (command == TurnCommand.ROUNDABOUT || command == TurnCommand.ROUNDABOUT_LEFT) {
        builder.setRoundaboutExitNumber(1)
    }
    return builder.build()
}