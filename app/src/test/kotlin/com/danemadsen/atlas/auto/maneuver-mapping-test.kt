package com.danemadsen.atlas.auto

import androidx.car.app.navigation.model.Maneuver
import com.danemadsen.atlas.routing.TurnCommand
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exhaustive [TurnCommand] -> [Maneuver.Type] mapping — all 15 members. */
class ManeuverMappingTest {

    @Test
    fun everyTurnCommandMapsToItsCarManeuverType() {
        val expected = mapOf(
            TurnCommand.STRAIGHT to Maneuver.TYPE_STRAIGHT,
            TurnCommand.TURN_LEFT to Maneuver.TYPE_TURN_NORMAL_LEFT,
            TurnCommand.TURN_SLIGHT_LEFT to Maneuver.TYPE_TURN_SLIGHT_LEFT,
            TurnCommand.TURN_SHARP_LEFT to Maneuver.TYPE_TURN_SHARP_LEFT,
            TurnCommand.TURN_RIGHT to Maneuver.TYPE_TURN_NORMAL_RIGHT,
            TurnCommand.TURN_SLIGHT_RIGHT to Maneuver.TYPE_TURN_SLIGHT_RIGHT,
            TurnCommand.TURN_SHARP_RIGHT to Maneuver.TYPE_TURN_SHARP_RIGHT,
            TurnCommand.KEEP_LEFT to Maneuver.TYPE_KEEP_LEFT,
            TurnCommand.KEEP_RIGHT to Maneuver.TYPE_KEEP_RIGHT,
            TurnCommand.U_TURN to Maneuver.TYPE_U_TURN_LEFT,
            TurnCommand.ROUNDABOUT to Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW,
            TurnCommand.ROUNDABOUT_LEFT to Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW,
            TurnCommand.EXIT_LEFT to Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT,
            TurnCommand.EXIT_RIGHT to Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT,
            TurnCommand.ARRIVE to Maneuver.TYPE_DESTINATION,
        )
        for ((command, type) in expected) {
            assertEquals(type, maneuverType(command))
            assertEquals(type, maneuver(command).type)
        }
    }
}