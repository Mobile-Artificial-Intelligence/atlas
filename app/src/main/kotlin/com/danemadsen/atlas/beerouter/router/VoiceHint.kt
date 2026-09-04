/**
 * Container for a voice hint
 * (both input- and result data for voice hint processing)
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.geo.Position
import kotlin.math.abs

public class VoiceHint {
    public enum class Command {
        UNSET,
        C,    // continue (go straight)
        TL,   // turn left
        TSLL, // turn slightly left
        TSHL, // turn sharply left
        TR,   // turn right
        TSLR, // turn slightly right
        TSHR, // turn sharply right
        KL,   // keep left
        KR,   // keep right
        TLU,  // U-turn left
        TRU,  // U-turn right
        OFFR, // Off route
        RNDB, // Roundabout
        RNLB, // Roundabout left
        TU,   // 180 degree u-turn
        BL,   // Beeline routing
        EL,   // exit left
        ER,   // exit right
        END,  // end point
    }

    public var position: Position = Position.ZERO

    public var command: Command = Command.UNSET
    public var oldWay: MessageData? = null
    public var goodWay: MessageData? = null
    public var badWays: MutableList<MessageData> = mutableListOf()
    public var distanceToNext: Double = 0.0
    public var indexInTrack: Int = 0

    public val time: Float
        get() = oldWay?.time ?: 0f

    public var angle: Float = Float.MAX_VALUE
    public var lowerBadWayAngle: Float = -181f
    public var higherBadWayAngle: Float = 181f

    public var turnAngleConsumed: Boolean = false
    public var needsRealTurn: Boolean = false
    public var maxBadPrio: Int = -1

    public var exitNumber: Int = 0

    public val isRoundabout: Boolean
        get() = this.exitNumber != 0

    public fun addBadWay(badWay: MessageData?) {
        badWay?.let(badWays::add)
    }

    public fun updateCommand() {
        for (badWay in badWays) {
            if (badWay.isBadOneway) {
                continue
            }
            if (lowerBadWayAngle < badWay.turnangle && badWay.turnangle < goodWay!!.turnangle) {
                lowerBadWayAngle = badWay.turnangle
            }
            if (higherBadWayAngle > badWay.turnangle && badWay.turnangle > goodWay!!.turnangle) {
                higherBadWayAngle = badWay.turnangle
            }
        }

        val cmdAngle = if (angle == Float.MAX_VALUE) {
            goodWay!!.turnangle
        } else {
            angle
        }
        if (this.command == Command.BL) return

        if (this.exitNumber > 0) {
            this.command = Command.RNDB
        } else if (this.exitNumber < 0) {
            this.command = Command.RNLB
        } else if (is180DegAngle(cmdAngle) && cmdAngle <= -179f && higherBadWayAngle == 181f && lowerBadWayAngle == -181f) {
            this.command = Command.TU
        } else if (cmdAngle < -159f) {
            this.command = Command.TLU
        } else if (cmdAngle < -135f) {
            this.command = Command.TSHL
        } else if (cmdAngle < -45f) {
            if (cmdAngle < -95f && higherBadWayAngle < -30f && lowerBadWayAngle < -180f) {
                this.command = Command.TSHL
            } else if (cmdAngle > -85f && lowerBadWayAngle > -180f && higherBadWayAngle > -10f) {
                this.command = Command.TSLL
            } else {
                if (cmdAngle < -110f) {
                    this.command = Command.TSHL
                } else if (cmdAngle > -60f) {
                    this.command = Command.TSLL
                } else {
                    this.command = Command.TL
                }
            }
        } else if (cmdAngle < -21f) {
            if (this.command != Command.KR) { // don't overwrite KR with TSLL
                this.command = Command.TSLL
            }
        } else if (cmdAngle < -5f) {
            if (lowerBadWayAngle < -100f && higherBadWayAngle < 45f) {
                this.command = Command.TSLL
            } else if (lowerBadWayAngle >= -100f && higherBadWayAngle < 45f) {
                this.command = Command.KL
            } else {
                if (lowerBadWayAngle > -35f && higherBadWayAngle > 55f) {
                    this.command = Command.KR
                } else {
                    this.command = Command.C
                }
            }
        } else if (cmdAngle < 5f) {
            if (lowerBadWayAngle > -30f) {
                this.command = Command.KR
            } else if (higherBadWayAngle < 30f) {
                this.command = Command.KL
            } else {
                this.command = Command.C
            }
        } else if (cmdAngle < 21f) {
            if (lowerBadWayAngle > -45f && higherBadWayAngle > 100f) {
                this.command = Command.TSLR
            } else if (lowerBadWayAngle > -45f && higherBadWayAngle <= 100f) {
                this.command = Command.KR
            } else {
                if (lowerBadWayAngle < -55f && higherBadWayAngle < 35f) {
                    this.command = Command.KL
                } else {
                    this.command = Command.C
                }
            }
        } else if (cmdAngle < 45f) {
            this.command = Command.TSLR
        } else if (cmdAngle < 135f) {
            if (cmdAngle < 85f && higherBadWayAngle < 180f && lowerBadWayAngle < 10f) {
                this.command = Command.TSLR
            } else if (cmdAngle > 95f && lowerBadWayAngle > 30f && higherBadWayAngle > 180f) {
                this.command = Command.TSHR
            } else {
                if (cmdAngle > 110.0) {
                    this.command = Command.TSHR
                } else if (cmdAngle < 60.0) {
                    this.command = Command.TSLR
                } else {
                    this.command = Command.TR
                }
            }
        } else if (cmdAngle < 159f) {
            this.command = Command.TSHR
        } else if (is180DegAngle(cmdAngle) && cmdAngle >= 179f && higherBadWayAngle == 181f && lowerBadWayAngle == -181f) {
            this.command = Command.TU
        } else {
            this.command = Command.TRU
        }
    }

    public fun formatGeometry(): String {
        val oldPrio = oldWay?.priorityclassifier?.toFloat() ?: 0f
        val sb = StringBuilder(30)
        sb.append(' ').append(oldPrio.toInt())
        appendTurnGeometry(sb, goodWay!!)
        for (badWay in badWays) {
            sb.append(" ")
            appendTurnGeometry(sb, badWay)
        }
        return sb.toString()
    }

    private fun appendTurnGeometry(sb: StringBuilder, msg: MessageData) {
        sb.append("(").append((msg.turnangle + 0.5).toInt()).append(")")
            .append((msg.priorityclassifier))
    }

    public fun hasGiveWay(): Boolean {
        val wayTags = oldWay?.wayTags
        val nodeTags = oldWay?.nodeTags
        if (wayTags != null && nodeTags != null) {
            return if (wayTags["reversedirection"] == "yes") {
                (nodeTags["highway"] == "give_way" || nodeTags["highway"] == "stop") &&
                        nodeTags["direction"] == "backward"
            } else {
                (nodeTags["highway"] == "give_way" || nodeTags["highway"] == "stop") &&
                        nodeTags["direction"] != "backward"
            }
        }
        return false
    }

    public companion object {
        public fun is180DegAngle(angle: Float): Boolean {
            return (abs(angle) in 179f..180f)
        }
    }
}
