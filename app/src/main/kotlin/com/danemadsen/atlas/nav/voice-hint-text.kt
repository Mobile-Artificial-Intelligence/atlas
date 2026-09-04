package com.danemadsen.atlas.nav

import com.danemadsen.atlas.routing.TurnCommand

/**
 * [TurnCommand] -> the spoken/shown instruction, in the style the
 * platform TTS reads well: short verb first, street appended when the
 * engine knew it.
 */
fun turnInstruction(command: TurnCommand, street: String?): String {
    val verb = when (command) {
        TurnCommand.STRAIGHT -> "Continue straight"
        TurnCommand.TURN_LEFT -> "Turn left"
        TurnCommand.TURN_SLIGHT_LEFT -> "Turn slightly left"
        TurnCommand.TURN_SHARP_LEFT -> "Turn sharply left"
        TurnCommand.TURN_RIGHT -> "Turn right"
        TurnCommand.TURN_SLIGHT_RIGHT -> "Turn slightly right"
        TurnCommand.TURN_SHARP_RIGHT -> "Turn sharply right"
        TurnCommand.KEEP_LEFT -> "Keep left"
        TurnCommand.KEEP_RIGHT -> "Keep right"
        TurnCommand.U_TURN -> "Make a U-turn"
        TurnCommand.ROUNDABOUT -> "At the roundabout, go around and exit"
        TurnCommand.ROUNDABOUT_LEFT -> "At the roundabout, go around and exit left"
        TurnCommand.EXIT_LEFT -> "Take the exit on the left"
        TurnCommand.EXIT_RIGHT -> "Take the exit on the right"
        TurnCommand.ARRIVE -> "Arrive at your destination"
    }
    if (street.isNullOrBlank() || command == TurnCommand.ARRIVE) return verb
    return "$verb onto $street"
}