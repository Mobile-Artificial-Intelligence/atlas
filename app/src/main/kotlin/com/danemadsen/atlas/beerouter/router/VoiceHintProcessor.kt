/**
 * Processor for Voice Hints
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.router

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal class VoiceHintProcessor // this.catchingRange = catchingRange;
    (
    catchingRange: Double, // private double catchingRange; // range to catch angles and merge turns
    private val explicitRoundabouts: Boolean, private val transportMode: VoiceHintList.TransportMode
) {
    private val significantAngle: Double = 22.5
    private val internalCatchingRangeNear: Double = 2.0
    private val internalCatchingRangeWide: Double = 10.0

    private fun sumNonConsumedWithinCatchingRange(
        inputs: List<VoiceHint>,
        offset: Int,
        range: Double
    ): Float {
        var index = offset
        var distance = 0.0
        var angle = 0f
        while (index >= 0 && distance < range) {
            val input = inputs[index--]
            if (input.turnAngleConsumed || input.command == VoiceHint.Command.BL || input.command == VoiceHint.Command.END) {
                break
            }
            val goodWay = requireNotNull(input.goodWay)
            angle += goodWay.turnangle
            distance += goodWay.linkdist.toDouble()
            input.turnAngleConsumed = true
        }
        return angle
    }


    /**
     * process voice hints. Uses VoiceHint objects
     * for both input and output. Input is in reverse
     * order (from target to start), but output is
     * returned in travel-direction and only for
     * those nodes that trigger a voice hint.
     *
     *
     * Input objects are expected for every segment
     * of the track, also for those without a junction
     *
     *
     * VoiceHint objects in the output list are enriched
     * by the voice-command, the total angle and the distance
     * to the next hint
     *
     * @param inputs tracknodes, un reverse order
     * @return voice hints, in forward order
     */
    fun process(inputs: List<VoiceHint>): MutableList<VoiceHint> {
        val results = mutableListOf<VoiceHint>()
        var distance = 0.0
        var roundAboutTurnAngle = 0f // sums up angles in roundabout

        var roundaboutExit = 0
        var roundaboudStartIdx = -1

        for (hintIdx in inputs.indices) {
            val input = inputs[hintIdx]
            val goodWay = requireNotNull(input.goodWay)
            val oldWay = requireNotNull(input.oldWay)

            if (input.command == VoiceHint.Command.BL) {
                results.add(input)
                continue
            }

            val turnAngle = goodWay.turnangle
            if (hintIdx != 0) distance += goodWay.linkdist.toDouble()

            val currentPrio = goodWay.priorityclassifier
            val oldPrio = oldWay.priorityclassifier
            val minPrio = min(oldPrio, currentPrio)

            val isLink2Highway = oldWay.isLinkType && !goodWay.isLinkType
            val isHighway2Link = !oldWay.isLinkType && goodWay.isLinkType

            if (explicitRoundabouts && oldWay.isRoundabout) {
                if (roundaboudStartIdx == -1) roundaboudStartIdx = hintIdx
                roundAboutTurnAngle += sumNonConsumedWithinCatchingRange(
                    inputs,
                    hintIdx,
                    internalCatchingRangeNear
                )
                if (roundaboudStartIdx == hintIdx) {
                    if (input.badWays.isNotEmpty()) {
                        // remove goodWay
                        roundAboutTurnAngle -= goodWay.turnangle
                        // add a badWay
                        for (badWay in input.badWays) {
                            if (!badWay.isBadOneway) roundAboutTurnAngle += badWay.turnangle
                        }
                    }
                }
                var isExit = roundaboutExit == 0 // exit point is always exit
                if (input.badWays.isNotEmpty()) {
                    for (badWay in input.badWays) {
                        if (!badWay.isBadOneway &&
                            badWay.isGoodForCars
                        ) {
                            isExit = true
                            break
                        }
                    }
                }
                if (isExit) {
                    roundaboutExit++
                }
                continue
            }
            if (roundaboutExit > 0) {
                input.angle = roundAboutTurnAngle
                goodWay.turnangle = roundAboutTurnAngle
                input.distanceToNext = distance
                input.turnAngleConsumed = true
                //input.roundaboutExit = startTurn < 0 ? roundaboutExit : -roundaboutExit;
                input.exitNumber = if (roundAboutTurnAngle < 0) roundaboutExit else -roundaboutExit
                var tmpangle = 0f
                val tmpRndAbt = VoiceHint()
                val tmpRndAbtBadWays = mutableListOf<MessageData>()
                tmpRndAbt.badWays = tmpRndAbtBadWays
                for (i in hintIdx - 1 downTo roundaboudStartIdx + 1) {
                    val vh = inputs[i]
                    val vhGoodWay = requireNotNull(vh.goodWay)
                    tmpangle += vhGoodWay.turnangle
                    if (vh.badWays.isNotEmpty()) {
                        for (badWay in vh.badWays) {
                            if (!badWay.isBadOneway) {
                                val md = MessageData()
                                md.linkdist = vhGoodWay.linkdist
                                md.priorityclassifier = vhGoodWay.priorityclassifier
                                md.turnangle = tmpangle
                                tmpRndAbtBadWays.add(md)
                            }
                        }
                    }
                }
                distance = 0.0

                input.badWays = tmpRndAbt.badWays

                results.add(input)
                roundAboutTurnAngle = 0f
                roundaboutExit = 0
                roundaboudStartIdx = -1
                continue
            }

            val inputNext = if (hintIdx + 1 < inputs.size) inputs[hintIdx + 1] else null

            var maxPrioAll = -1 // max prio of all detours
            var maxPrioCandidates = -1 // max prio of real candidates

            var maxAngle = -180f
            var minAngle = 180f
            var minAbsAngeRaw = 180f

            var isBadwayLink = false

            if (input.badWays.isNotEmpty()) {
                for (badWay in input.badWays) {
                    val badPrio = badWay.priorityclassifier
                    val badTurn = badWay.turnangle
                    if (badWay.isLinkType) {
                        isBadwayLink = true
                    }

                    val isOldWayToLink = !oldWay.isLinkType && badWay.isLinkType
                    if (badPrio > maxPrioAll && !isOldWayToLink) {
                        maxPrioAll = badPrio
                        input.maxBadPrio = max(input.maxBadPrio, badPrio)
                    }

                    if (badWay.costfactor < 20f && abs(badTurn) < minAbsAngeRaw) {
                        minAbsAngeRaw = abs(badTurn)
                    }

                    if (badPrio < minPrio) {
                        continue  // ignore low prio ways
                    }

                    if (badWay.isBadOneway) {
                        if (minAbsAngeRaw == 180f) {
                            minAbsAngeRaw = abs(turnAngle) // disable hasSomethingMoreStraight
                        }

                        continue  // ignore wrong oneways
                    }

                    if (abs(badTurn) - abs(turnAngle) > 80f) {
                        if (minAbsAngeRaw == 180f) {
                            minAbsAngeRaw = abs(turnAngle) // disable hasSomethingMoreStraight
                        }

                        continue  // ways from the back should not trigger a slight turn
                    }

                    if (badPrio > maxPrioCandidates) {
                        maxPrioCandidates = badPrio
                        input.maxBadPrio = max(input.maxBadPrio, badPrio)
                    }
                    if (badTurn > maxAngle) {
                        maxAngle = badTurn
                    }
                    if (badTurn < minAngle) {
                        minAngle = badTurn
                    }
                }
            }


            // has a significant angle and one or more bad ways around
            // https://brouter.de/brouter-test/#map=17/53.07509/-0.95780/standard&lonlats=-0.95757,53.073428;-0.95727,53.076064&profile=car-eco
            val hasSomethingMoreStraight = (abs(turnAngle) > 35f) && input.badWays.isNotEmpty()


            // bad way has more prio, but is not a link
            //
            val noLinkButBadWayPrio = (maxPrioAll > minPrio && !isLink2Highway)


            // bad way has more prio
            //
            val badWayHasPrio = (maxPrioCandidates > currentPrio)


            // is a u-turn - same way back
            // https://brouter.de/brouter-test/#map=16/51.0608/13.7707/standard&lonlats=13.7658,51.060989;13.767893,51.061628;13.765273,51.062953&pois=13.76739,51.061609,Biergarten2956
            val isUTurn = VoiceHint.is180DegAngle(turnAngle)


            // way has prio, but also has an angle
            // https://brouter.de/brouter-test/#map=15/47.7925/16.2582/standard&lonlats=16.24952,47.785458;16.269679,47.794653&profile=car-eco
            val isBadWayLinkButNoLink =
                (!isHighway2Link && isBadwayLink && abs(turnAngle) > 5f)


            //
            // https://brouter.de/brouter-test/#map=14/47.7927/16.2848/standard&lonlats=16.267617,47.795275;16.286438,47.787354&profile=car-eco
            val isLinkButNoBadWayLink =
                (isHighway2Link && !isBadwayLink && abs(turnAngle) < 5f)


            // way has same prio, but bad way has smaller angle and is not a bad link and prio is near
            // small: https://brouter.de/brouter-test/#map=17/49.40750/8.69257/standard&lonlats=8.692461,49.407997;8.694028,49.408478&profile=car-eco
            // high:  https://brouter.de/brouter-test/#map=14/52.9951/-0.5786/standard&lonlats=-0.59261,52.991576;-0.583606,52.998947&profile=car-eco
            val samePrioSmallBadAngle =
                (currentPrio == oldPrio) && minAbsAngeRaw != 180f && minAbsAngeRaw < 35f


            // way has prio, but has to give way
            // https://brouter.de/brouter-test/#map=15/54.1344/-4.6015/standard&lonlats=-4.605432,54.136747;-4.609336,54.130058&profile=car-eco
            val mustGiveWay =
                transportMode != VoiceHintList.TransportMode.FOOT && input.badWays.isNotEmpty() && !badWayHasPrio &&
                        (input.hasGiveWay() || (inputNext != null && inputNext.hasGiveWay()))


            // unconditional triggers are all junctions with
            // - higher detour prios than the minimum route prio (except link->highway junctions)
            // - or candidate detours with higher prio then the route exit leg
            val unconditionalTrigger = hasSomethingMoreStraight ||
                    noLinkButBadWayPrio ||
                    badWayHasPrio ||
                    isUTurn ||
                    isBadWayLinkButNoLink ||
                    isLinkButNoBadWayLink ||
                    samePrioSmallBadAngle ||
                    mustGiveWay

            // conditional triggers (=real turning angle required) are junctions
            // with candidate detours equal in priority than the route exit leg
            val conditionalTrigger = maxPrioCandidates >= minPrio

            if (unconditionalTrigger || conditionalTrigger) {
                input.angle = turnAngle
                input.updateCommand()
                val isStraight = input.command == VoiceHint.Command.C
                input.needsRealTurn = (!unconditionalTrigger) && isStraight

                // check for KR/KL
                if (abs(turnAngle) > 5.0) { // don't use too small angles
                    if (maxAngle < turnAngle && maxAngle > turnAngle - 45f - (max(turnAngle, 0f))) {
                        input.command = VoiceHint.Command.KR
                    }
                    if (minAngle > turnAngle && minAngle < turnAngle + 45f - (min(turnAngle, 0f))) {
                        input.command = VoiceHint.Command.KL
                    }
                }

                input.angle =
                    sumNonConsumedWithinCatchingRange(inputs, hintIdx, internalCatchingRangeWide)
                input.distanceToNext = distance
                distance = 0.0
                results.add(input)
            }
            if (results.isNotEmpty() && distance < internalCatchingRangeNear) { //catchingRange
                results[results.size - 1].angle += sumNonConsumedWithinCatchingRange(
                    inputs,
                    hintIdx,
                    internalCatchingRangeNear
                )
            }
        }

        // go through the hint list again in reverse order (=travel direction)
        // and filter out non-significant hints and hints too close to its predecessor
        val results2= mutableListOf<VoiceHint>()
        var i = results.size
        while (i > 0) {
            var hint = results[--i]
            if (hint.command == VoiceHint.Command.UNSET) {
                hint.updateCommand()
            }
            if (hint.command == VoiceHint.Command.END) {
                results2.add(hint)
                continue
            }
            if (!(hint.needsRealTurn && (hint.command == VoiceHint.Command.C || hint.command == VoiceHint.Command.BL))) {
                var dist = hint.distanceToNext
                // sum up other hints within the catching range (e.g. 40m)
                while (dist < internalCatchingRangeNear && i > 0) {
                    val h2 = results[i - 1]
                    dist = h2.distanceToNext
                    hint.distanceToNext += dist
                    hint.angle += h2.angle
                    i--
                    if (h2.isRoundabout) { // if we hit a roundabout, use that as the trigger
                        h2.angle = hint.angle
                        hint = h2
                        break
                    }
                }

                if (!explicitRoundabouts) {
                    hint.exitNumber = 0 // use an angular hint instead
                }
                hint.updateCommand()
                results2.add(hint)
            } else if (hint.command == VoiceHint.Command.BL) {
                results2.add(hint)
            } else {
                if (results2.isNotEmpty()) results2[results2.size - 1].distanceToNext += hint.distanceToNext
            }
        }
        return results2
    }

    fun postProcess(
        inputs: List<VoiceHint>,
        catchingRange: Double,
        minRange: Double
    ): MutableList<VoiceHint> {
        val results = mutableListOf<VoiceHint>()
        var inputLast: VoiceHint? = null
        var inputLastSaved: VoiceHint? = null
        var hintIdx = 0
        while (hintIdx < inputs.size) {
            val input = inputs[hintIdx]
            val goodWay = requireNotNull(input.goodWay)
            var nextInput: VoiceHint? = null
            if (hintIdx + 1 < inputs.size) {
                nextInput = inputs[hintIdx + 1]
            }

            if (input.command == VoiceHint.Command.BL) {
                results.add(input)
                hintIdx++
                continue
            }

            if (nextInput == null) {
                if (input.command == VoiceHint.Command.END) {
                    hintIdx++
                    continue
                } else if ((input.command == VoiceHint.Command.C || input.command == VoiceHint.Command.KR || input.command == VoiceHint.Command.KL)
                    && !goodWay.isLinkType
                ) {
                    if (checkStraightHold(input, inputLastSaved, minRange)) {
                        results.add(input)
                    } else {
                        if (inputLast != null) { // when drop add distance to last
                            inputLast.distanceToNext += input.distanceToNext
                        }
                        hintIdx++
                        continue
                    }
                } else {
                    results.add(input)
                }
            } else {
                if ((inputLastSaved != null && inputLastSaved.distanceToNext > catchingRange) || input.distanceToNext > catchingRange) {
                    if ((input.command == VoiceHint.Command.C || input.command == VoiceHint.Command.KR || input.command == VoiceHint.Command.KL)
                        && !goodWay.isLinkType
                    ) {
                        if (checkStraightHold(input, inputLastSaved, minRange)) {
                            // add only on prio
                            results.add(input)
                            inputLastSaved = input
                        } else {
                            if (inputLastSaved != null) { // when drop add distance to last
                                inputLastSaved.distanceToNext += input.distanceToNext
                            }
                        }
                    } else if ((goodWay.priorityclassifier == 29 && input.maxBadPrio == 30) &&
                        checkForNextNoneMotorway(inputs, hintIdx, 3)
                    ) {
                        // leave motorway
                        if (input.command == VoiceHint.Command.KR || input.command == VoiceHint.Command.TSLR) {
                            input.command = VoiceHint.Command.ER
                        } else if (input.command == VoiceHint.Command.KL || input.command == VoiceHint.Command.TSLL) {
                            input.command = VoiceHint.Command.EL
                        }
                        results.add(input)
                        inputLastSaved = input
                    } else {
                        // add all others
                        // ignore motorway / primary continue
                        if (((goodWay.priorityclassifier != 28) &&
                                    (goodWay.priorityclassifier != 30) &&
                                    (goodWay.priorityclassifier != 26))
                            || input.isRoundabout
                            || abs(input.angle) > 21f
                        ) {
                            results.add(input)
                            inputLastSaved = input
                        } else {
                            if (inputLastSaved != null) { // when drop add distance to last
                                inputLastSaved.distanceToNext += input.distanceToNext
                            }
                        }
                    }
                } else if (input.distanceToNext < catchingRange) {
                    var dist = input.distanceToNext
                    var angles = input.angle
                    var save = false

                    dist += nextInput.distanceToNext
                    angles += nextInput.angle

                    if ((input.command == VoiceHint.Command.C || input.command == VoiceHint.Command.KR || input.command == VoiceHint.Command.KL)
                    && !goodWay.isLinkType
                    ) {
                        if (goodWay.priorityclassifier < input.maxBadPrio) {
                            if (inputLastSaved != null && inputLastSaved.command != VoiceHint.Command.C && inputLastSaved.distanceToNext > minRange
                                && transportMode != VoiceHintList.TransportMode.CAR
                            ) {
                                // add when straight and not linktype
                                // and last vh not straight
                                save = true
                                // remove when next straight and not linktype
                                if (nextInput.command == VoiceHint.Command.C && !requireNotNull(nextInput.goodWay).isLinkType) {
                                    input.distanceToNext += nextInput.distanceToNext
                                    hintIdx++
                                }
                            }
                        } else {
                            if (inputLastSaved != null) { // when drop add distance to last
                                inputLastSaved.distanceToNext += input.distanceToNext
                            }
                        }
                    } else if ((goodWay.priorityclassifier == 29 && input.maxBadPrio == 30)) {
                        // leave motorway
                        if (input.command == VoiceHint.Command.KR || input.command == VoiceHint.Command.TSLR) {
                            input.command = VoiceHint.Command.ER
                        } else if (input.command == VoiceHint.Command.KL || input.command == VoiceHint.Command.TSLL) {
                            input.command = VoiceHint.Command.EL
                        }
                        save = true
                    } else if (VoiceHint.is180DegAngle(input.angle)) {
                        // add u-turn, 180 degree
                        save = true
                    } else if (transportMode == VoiceHintList.TransportMode.CAR && abs(angles) > 180 - significantAngle) {
                        // add when inc car mode and u-turn, collects e.g. two left turns in range
                        input.angle = angles
                        input.updateCommand()
                        input.distanceToNext += nextInput.distanceToNext
                        save = true
                        hintIdx++
                    } else if (abs(angles) < significantAngle && input.distanceToNext < minRange) {
                        input.angle = angles
                        input.updateCommand()
                        input.distanceToNext += nextInput.distanceToNext
                        save = true
                        hintIdx++
                    } else if (abs(input.angle) > significantAngle) {
                        // add when angle above 22.5 deg
                        save = true
                    } else if (abs(input.angle) < significantAngle) {
                        // add when angle below 22.5 deg ???
                        // save = true;
                    } else {
                        // otherwise ignore but add distance to next
                        // when drop add distance to last
                        nextInput.distanceToNext += input.distanceToNext
                        save = false
                    }

                    if (save) {
                        results.add(input) // add when last
                        inputLastSaved = input
                    }
                } else {
                    results.add(input)
                    inputLastSaved = input
                }
            }
            inputLast = input
            hintIdx++
        }
        if (results.isNotEmpty()) {
            // don't use END tag
            if (results.last().command == VoiceHint.Command.END) results.removeAt(results.size - 1)
        }

        return results
    }

    fun checkForNextNoneMotorway(
        inputs: List<VoiceHint>,
        offset: Int,
        testsize: Int
    ): Boolean {
        var i = 1
        while (i < testsize + 1 && offset + i < inputs.size) {
            val prio = requireNotNull(inputs[offset + i].goodWay).priorityclassifier
            if (prio < 29) return true
            if (prio == 30) return false
            i++
        }
        return false
    }

    fun checkStraightHold(input: VoiceHint, inputLastSaved: VoiceHint?, minRange: Double): Boolean {
        if (input.indexInTrack == 0) return false

        val goodWay = requireNotNull(input.goodWay)
        val oldWay = requireNotNull(input.oldWay)

        return ((abs(input.lowerBadWayAngle) < 35f || input.higherBadWayAngle < 35f)
                || goodWay.priorityclassifier < input.maxBadPrio || goodWay.priorityclassifier > oldWay.priorityclassifier)
                && (inputLastSaved == null || inputLastSaved.distanceToNext > minRange)
                && (input.distanceToNext > minRange)
    }
}
