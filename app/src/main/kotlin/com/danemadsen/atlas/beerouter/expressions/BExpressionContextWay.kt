// context for simple expression
// context means:
// - the local variables
// - the local variable names
// - the lookup-input variables
package com.danemadsen.atlas.beerouter.expressions

import com.danemadsen.atlas.beerouter.codec.TagValueValidator

public class BExpressionContextWay : BExpressionContext, TagValueValidator {
    private var decodeForbidden = false

    override val buildInVariableNames: Array<String?> = buildInVariables

    public val costfactor: Float
        get() = getBuildInVariable(0)

    public val turncost: Float
        get() = getBuildInVariable(1)

    public val uphillCostfactor: Float
        get() = getBuildInVariable(2)

    public val downhillCostfactor: Float
        get() = getBuildInVariable(3)

    public val initialcost: Float
        get() = getBuildInVariable(4)

    public val nodeAccessGranted: Float
        get() = getBuildInVariable(5)

    public val initialClassifier: Float
        get() = getBuildInVariable(6)

    public val trafficSourceDensity: Float
        get() = getBuildInVariable(7)

    public val isTrafficBackbone: Float
        get() = getBuildInVariable(8)

    public val priorityClassifier: Float
        get() = getBuildInVariable(9)

    public val classifierMask: Float
        get() = getBuildInVariable(10)

    public val maxspeed: Float
        get() = getBuildInVariable(11)

    public val uphillcost: Float
        get() = getBuildInVariable(12)

    public val downhillcost: Float
        get() = getBuildInVariable(13)

    public val uphillcutoff: Float
        get() = getBuildInVariable(14)

    public val downhillcutoff: Float
        get() = getBuildInVariable(15)

    public val uphillmaxslope: Float
        get() = getBuildInVariable(16)

    public val downhillmaxslope: Float
        get() = getBuildInVariable(17)

    public val uphillmaxslopecost: Float
        get() = getBuildInVariable(18)

    public val downhillmaxslopecost: Float
        get() = getBuildInVariable(19)

    public constructor(meta: BExpressionMetaData) : super("way", meta)

    /**
     * Create an Expression-Context for way context
     *
     * @param hashSize size of hashmap for result caching
     */
    public constructor(hashSize: Int, meta: BExpressionMetaData) : super("way", hashSize, meta)

    /**
     * Determine the access type for the given tag value set.
     *
     * @param tagValueSet the tag value set
     * @return the access type (0=none, 1=restricted, 2=full)
     * @throws NullPointerException if tagValueSet is null
     * @throws IllegalArgumentException if expression evaluation fails
     */
    override fun accessType(tagValueSet: ByteArray?): Int {
        evaluate(false, tagValueSet!!)
        var minCostFactor = this.costfactor
        if (minCostFactor >= 9999f) {
            setInverseVars()
            val reverseCostFactor = this.costfactor
            if (reverseCostFactor < minCostFactor) {
                minCostFactor = reverseCostFactor
            }
        }
        return if (minCostFactor < 9999f) 2 else if (decodeForbidden) (if (minCostFactor < 10000f) 1 else 0) else 0
    }

    /**
     * Set whether decoding is forbidden for restricted ways.
     *
     * @param decodeForbidden true if decoding is forbidden
     */
    override fun setDecodeForbidden(decodeForbidden: Boolean) {
        this.decodeForbidden = decodeForbidden
    }


    public companion object {
        private val buildInVariables = arrayOf<String?>(
            "costfactor",
            "turncost",
            "uphillcostfactor",
            "downhillcostfactor",
            "initialcost",
            "nodeaccessgranted",
            "initialclassifier",
            "trafficsourcedensity",
            "istrafficbackbone",
            "priorityclassifier",
            "classifiermask",
            "maxspeed",
            "uphillcost",
            "downhillcost",
            "uphillcutoff",
            "downhillcutoff",
            "uphillmaxslope",
            "downhillmaxslope",
            "uphillmaxslopecost",
            "downhillmaxslopecost"
        )
    }
}
