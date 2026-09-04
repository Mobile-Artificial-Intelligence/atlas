/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.map.OsmLinkHolder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

internal class StdPath : OsmPath() {
    /**
     * The elevation-hysteresis-buffer (0-10 m)
     */
    private var ehbd = 0 // in micrometer
    private var ehbu = 0 // in micrometer

    override var totalTime = 0.0 // travel time (seconds)
    override var totalEnergy = 0.0 // total route energy (Joule)
    private var elevation_buffer = 0f // just another elevation buffer (for travel time)

    private var uphillcostdiv = 0
    private var downhillcostdiv = 0

    override var nextForLink: OsmLinkHolder? = null

    public override fun init(orig: OsmPath?) {
        val origin = orig as StdPath
        this.ehbd = origin.ehbd
        this.ehbu = origin.ehbu
        this.totalTime = origin.totalTime
        this.totalEnergy = origin.totalEnergy
        this.elevation_buffer = origin.elevation_buffer
    }

    override fun resetState() {
        ehbd = 0
        ehbu = 0
        totalTime = 0.0
        totalEnergy = 0.0
        uphillcostdiv = 0
        downhillcostdiv = 0
        elevation_buffer = 0f
    }

    internal fun exportStdState(): StdPathState = StdPathState(
        ehbd = ehbd,
        ehbu = ehbu,
        totalTime = totalTime,
        totalEnergy = totalEnergy,
        elevationBuffer = elevation_buffer,
        uphillcostdiv = uphillcostdiv,
        downhillcostdiv = downhillcostdiv,
    )

    internal fun importStdState(state: StdPathState) {
        ehbd = state.ehbd
        ehbu = state.ehbu
        totalTime = state.totalTime
        totalEnergy = state.totalEnergy
        elevation_buffer = state.elevationBuffer
        uphillcostdiv = state.uphillcostdiv
        downhillcostdiv = state.downhillcostdiv
    }

    override fun processWaySection(
        rc: RoutingContext,
        dist: Double,
        delta_h: Double,
        elevation: Double,
        angle: Double,
        cosangle: Double,
        isStartpoint: Boolean,
        nsection: Int,
        lastpriorityclassifier: Int
    ): Double {
        // calculate the costfactor inputs
        val turncostbase = rc.way.turncost
        val uphillcutoff = rc.way.uphillcutoff * 10000
        val downhillcutoff = rc.way.downhillcutoff * 10000
        val uphillmaxslope = rc.way.uphillmaxslope * 10000
        val downhillmaxslope = rc.way.downhillmaxslope * 10000
        var cfup = rc.way.uphillCostfactor
        var cfdown = rc.way.downhillCostfactor
        val cf = rc.way.costfactor
        cfup = if (cfup == 0f) cf else cfup
        cfdown = if (cfdown == 0f) cf else cfdown

        downhillcostdiv = rc.way.downhillcost.toInt()
        if (downhillcostdiv > 0) {
            downhillcostdiv = 1000000 / downhillcostdiv
        }

        var downhillmaxslopecostdiv = rc.way.downhillmaxslopecost.toInt()
        downhillmaxslopecostdiv = if (downhillmaxslopecostdiv > 0) {
            1000000 / downhillmaxslopecostdiv
        } else {
            // if not given, use legacy behavior
            downhillcostdiv
        }

        uphillcostdiv = rc.way.uphillcost.toInt()
        if (uphillcostdiv > 0) {
            uphillcostdiv = 1000000 / uphillcostdiv
        }

        var uphillmaxslopecostdiv = rc.way.uphillmaxslopecost.toInt()
        uphillmaxslopecostdiv = if (uphillmaxslopecostdiv > 0) {
            1000000 / uphillmaxslopecostdiv
        } else {
            // if not given, use legacy behavior
            uphillcostdiv
        }

        val distInt = dist.toInt() // legacy arithmetics needs int

        // penalty for turning angle
        val turncost =
            ((1.0 - cosangle) * turncostbase + 0.2).toInt() // e.g. turncost=90 -> 90 degree = 90m penalty
        if (message != null) {
            message!!.linkturncost += turncost
            message!!.turnangle = angle.toFloat()
        }

        var sectionCost = turncost.toDouble()

        // *** penalty for elevation
        // only the part of the descend that does not fit into the elevation-hysteresis-buffers
        // leads to an immediate penalty
        val delta_h_micros = (1000000.0 * delta_h).toInt()
        ehbd = (ehbd + (-delta_h_micros - distInt * downhillcutoff)).toInt()
        ehbu = (ehbu + (delta_h_micros - distInt * uphillcutoff)).toInt()

        var downweight = 0f
        if (ehbd > rc.global.elevationpenaltybuffer) {
            downweight = 1f

            var excess = ehbd - rc.global.elevationpenaltybuffer
            var reduce = distInt * rc.global.elevationbufferreduce
            if (reduce > excess) {
                downweight = (excess.toFloat()) / reduce
                reduce = excess
            }
            excess = ehbd - rc.global.elevationmaxbuffer
            if (reduce < excess) {
                reduce = excess
            }
            ehbd -= reduce
            var elevationCost = 0f
            if (downhillcostdiv > 0) {
                elevationCost += min(reduce.toFloat(), distInt * downhillmaxslope) / downhillcostdiv
            }
            if (downhillmaxslopecostdiv > 0) {
                elevationCost += max(
                    0f,
                    reduce - distInt * downhillmaxslope
                ) / downhillmaxslopecostdiv
            }
            if (elevationCost > 0) {
                sectionCost += elevationCost.toDouble()
                if (message != null) {
                    message!!.linkelevationcost =
                        (message!!.linkelevationcost + elevationCost).toInt()
                }
            }
        } else if (ehbd < 0) {
            ehbd = 0
        }

        var upweight = 0f
        if (ehbu > rc.global.elevationpenaltybuffer) {
            upweight = 1f

            var excess = ehbu - rc.global.elevationpenaltybuffer
            var reduce = distInt * rc.global.elevationbufferreduce
            if (reduce > excess) {
                upweight = (excess.toFloat()) / reduce
                reduce = excess
            }
            excess = ehbu - rc.global.elevationmaxbuffer
            if (reduce < excess) {
                reduce = excess
            }
            ehbu -= reduce
            var elevationCost = 0f
            if (uphillcostdiv > 0) {
                elevationCost += min(reduce.toFloat(), distInt * uphillmaxslope) / uphillcostdiv
            }
            if (uphillmaxslopecostdiv > 0) {
                elevationCost += max(0f, reduce - distInt * uphillmaxslope) / uphillmaxslopecostdiv
            }
            if (elevationCost > 0) {
                sectionCost += elevationCost.toDouble()
                if (message != null) {
                    message!!.linkelevationcost =
                        (message!!.linkelevationcost + elevationCost).toInt()
                }
            }
        } else if (ehbu < 0) {
            ehbu = 0
        }

        // get the effective costfactor (slope dependent)
        val costfactor = cfup * upweight + cf * (1f - upweight - downweight) + cfdown * downweight

        if (message != null) {
            message!!.costfactor = costfactor
        }

        sectionCost += (distInt * costfactor + 0.5f).toDouble()

        return sectionCost
    }

    override fun processTargetNode(rc: RoutingContext): Double {
        // finally add node-costs for target node
        if (targetNode!!.nodeDescription != null) {
            val nodeAccessGranted = rc.way.nodeAccessGranted.toDouble() != 0.0
            rc.node.evaluate(nodeAccessGranted, targetNode!!.nodeDescription!!)
            val initialcost = rc.node.initialcost
            if (initialcost >= 1000000.0) {
                return -1.0
            }
            if (message != null) {
                message!!.linknodecost += initialcost.toInt()
                message!!.nodeTags = rc.node.getMap(
                    nodeAccessGranted,
                    targetNode!!.nodeDescription!!
                )
            }
            return initialcost.toDouble()
        }
        return 0.0
    }

    override fun elevationCorrection(): Int {
        return exportStdState().elevationCorrection()
    }

    override fun definitlyWorseThan(p: OsmPath?): Boolean {
        val path = p as StdPath
        return exportStdState().isDefinitelyWorseThan(cost, path.cost, path.exportStdState())
    }

    private fun computeIncline(dist: Double): Double {
        val min_delta = 3.0
        var shift = 0.0
        if (elevation_buffer > min_delta) {
            shift = -min_delta
        } else if (elevation_buffer < -min_delta) {
            shift = min_delta
        }
        val decayFactor = exp(-dist / 100.0)
        val new_elevation_buffer = ((elevation_buffer + shift) * decayFactor - shift).toFloat()
        val incline = (elevation_buffer - new_elevation_buffer) / dist
        elevation_buffer = new_elevation_buffer
        return incline
    }

    override fun computeKinematic(
        rc: RoutingContext,
        dist: Double,
        delta_h: Double,
        detailMode: Boolean
    ) {
        if (!detailMode) {
            return
        }

        // compute incline
        elevation_buffer = (elevation_buffer + delta_h).toFloat()
        val incline = computeIncline(dist)

        var maxSpeed = rc.global.maxSpeed
        val speedLimit = (rc.way.maxspeed / 3.6f).toDouble()
        if (speedLimit > 0) {
            maxSpeed = min(maxSpeed, speedLimit)
        }

        var speed = maxSpeed // Travel speed
        val f_roll: Double = rc.global.totalMass * GRAVITY * (rc.global.defaultC_r + incline)
        if (rc.global.footMode) {
            // Use Tobler's hiking function for walking sections
            speed = rc.global.maxSpeed * exp(-3.5 * abs(incline + 0.05))
        } else if (rc.global.bikeMode) {
            speed = solveCubic(rc.global.S_C_x, f_roll, rc.global.bikerPower)
            speed = min(speed, maxSpeed)
        }
        val dt = (dist / speed).toFloat()

        // hack: brouter compat, do not remove conversion
        totalTime = (totalTime + dt).toFloat().toDouble()

        // Calc energy assuming biking (no good model yet for hiking)
        // (Count only positive, negative would mean braking to enforce maxspeed)
        val energy = dist * (rc.global.S_C_x * speed * speed + f_roll)
        if (energy > 0.0) {
            totalEnergy = (totalEnergy + energy).toFloat().toDouble()
        }
    }

    companion object {
        // Gravitational constant, g
        private const val GRAVITY = 9.81 // in meters per second^(-2)

        private fun solveCubic(a: Double, c: Double, d: Double): Double {
            // Solves a * v^3 + c * v = d with a Newton method
            // to get the speed v for the section.

            var v = 8.0
            var findingStartvalue = true
            for (i in 0..9) {
                val y = (a * v * v + c) * v - d
                if (y < .1) {
                    if (findingStartvalue) {
                        v *= 2.0
                        continue
                    }
                    break
                }
                findingStartvalue = false
                val y_prime = 3 * a * v * v + c
                v -= y / y_prime
            }
            return v
        }
    }
}
