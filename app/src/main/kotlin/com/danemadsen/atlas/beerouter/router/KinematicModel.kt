/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.expressions.BExpressionContextNode
import com.danemadsen.atlas.beerouter.expressions.BExpressionContextWay

internal class KinematicModel : OsmPathModel() {
    override fun createPrePath(): OsmPrePath = KinematicPrePath()

    override fun createPath(): OsmPath = KinematicPath()

    var turnAngleDecayTime: Double = 0.0
    var fRoll: Double = 0.0
    var fAir: Double = 0.0
    var fRecup: Double = 0.0
    var pStandby: Double = 0.0
    var outsideTemp: Double = 0.0
    var recupEfficiency: Double = 0.0
    var totalweight: Double = 0.0
    var vmax: Double = 0.0
    var leftWaySpeed: Double = 0.0
    var rightWaySpeed: Double = 0.0

    // derived values
    var pw: Double = 0.0 // balance power
    var cost0: Double = 0.0 // minimum possible cost per meter

    private var wayIdxMaxspeed = 0
    private var wayIdxMaxspeedExplicit = 0
    private var wayIdxMinspeed = 0

    private var nodeIdxMaxspeed = 0

    private lateinit var ctxWay: BExpressionContextWay
    private lateinit var ctxNode: BExpressionContextNode
    private val params: MutableMap<String, String> = mutableMapOf()

    private var lastEffectiveLimit = 0.0
    private var lastBrakingSpeed = 0.0

    override fun init(
        expctxWay: BExpressionContextWay?,
        expctxNode: BExpressionContextNode?,
        keyValues: Map<String, String>
    ) {
        if (!::ctxWay.isInitialized) {
            ctxWay = requireNotNull(expctxWay)
            ctxNode = requireNotNull(expctxNode)
            wayIdxMaxspeed = ctxWay.getOutputVariableIndex("maxspeed", false)
            wayIdxMaxspeedExplicit = ctxWay.getOutputVariableIndex("maxspeed_explicit", false)
            wayIdxMinspeed = ctxWay.getOutputVariableIndex("minspeed", false)
            nodeIdxMaxspeed = ctxNode.getOutputVariableIndex("maxspeed", false)
        }

        params.clear()
        params.putAll(keyValues)

        turnAngleDecayTime = getParam("turnAngleDecayTime", 5f).toDouble()
        fRoll = getParam("f_roll", 232f).toDouble()
        fAir = getParam("f_air", 0.4f).toDouble()
        fRecup = getParam("f_recup", 400f).toDouble()
        pStandby = getParam("p_standby", 250f).toDouble()
        outsideTemp = getParam("outside_temp", 20f).toDouble()
        recupEfficiency = getParam("recup_efficiency", 0.7f).toDouble()
        totalweight = getParam("totalweight", 1640f).toDouble()
        vmax = getParam("vmax", 80f) / 3.6
        leftWaySpeed = getParam("leftWaySpeed", 12f) / 3.6
        rightWaySpeed = getParam("rightWaySpeed", 12f) / 3.6

        pw = 2.0 * fAir * vmax * vmax * vmax - pStandby
        cost0 = (pw + pStandby) / vmax + fRoll + fAir * vmax * vmax
    }

    protected fun getParam(name: String, defaultValue: Float): Float {
        val value = params.getOrPut(name) {
            ctxWay.getVariableValue(name, defaultValue).toString()
        }
        return value.toFloat()
    }

    val wayMaxspeed: Float
        get() = ctxWay.getBuildInVariable(wayIdxMaxspeed) / 3.6f

    val wayMaxspeedExplicit: Float
        get() = ctxWay.getBuildInVariable(wayIdxMaxspeedExplicit) / 3.6f

    val wayMinspeed: Float
        get() = ctxWay.getBuildInVariable(wayIdxMinspeed) / 3.6f

    val nodeMaxspeed: Float
        get() = ctxNode.getBuildInVariable(nodeIdxMaxspeed) / 3.6f

    val effectiveSpeedLimit: Double
        /**
         * get the effective speed limit from the way-limit and vmax/vmin
         */
        get() = minOf(wayMaxspeed.toDouble(), maxOf(wayMinspeed.toDouble(), vmax))

    /**
     * get the braking speed for current balance-power (pw) and effective speed limit (vl)
     */
    fun getBrakingSpeed(vl: Double): Double {
        if (vl == lastEffectiveLimit) {
            return lastBrakingSpeed
        }

        var v = vl * 0.8
        val pw2 = pw + pStandby
        val e = recupEfficiency
        val x0 = pw2 / vl + fAir * e * vl * vl + (1.0 - e) * fRoll
        for (i in 0..4) {
            val v2 = v * v
            val x = pw2 / v + fAir * e * v2 - x0
            val dx = 2.0 * e * fAir * v - pw2 / v2
            v -= x / dx
        }
        lastEffectiveLimit = vl
        lastBrakingSpeed = v

        return v
    }
}
