package com.danemadsen.atlas.beerouter.map.generator

import com.danemadsen.atlas.beerouter.codec.MicroCache
import com.danemadsen.atlas.beerouter.codec.MicroCache2
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.geo.UNSET_ELEVATION
import com.danemadsen.atlas.beerouter.geo.encodedAltitudeToMeters

/**
 * LOCAL PATCH (Atlas): the position lives as inline scalars instead of a
 * `Position` object. A 5-degree metro bucket holds ~3M nodes and must fit a
 * phone's largeHeap (~512-576 MB); the per-node `Position` object (header +
 * 2 ints + short + the precomputed id long, ~40 B) cost ~120 MB of that
 * heap. `idFromPos` computes the id on demand — `Position.computeId` is a
 * pure function of the two ints, so this is semantically identical.
 */
public open class OsmNodeP : OsmLinkP() {
    public var longitude: Int = 0
    public var latitude: Int = 0
    public var altitude: Short = UNSET_ELEVATION
    public var bits: Byte = 0

    public fun getILat(): Int = latitude
    public fun getILon(): Int = longitude

    public open fun getSElev(): Short =
        if ((bits.toInt() and NO_BRIDGE_BIT) == 0 || (bits.toInt() and NO_TUNNEL_BIT) == 0) UNSET_ELEVATION else altitude

    public fun getElev(): Double =
        if (altitude == UNSET_ELEVATION) 0.0 else encodedAltitudeToMeters(altitude)

    public fun createLink(source: OsmNodeP): OsmLinkP {
        if (sourceNode == null && targetNode == null) {
            sourceNode = source
            targetNode = this
            source.addLink(this)
            return this
        }
        val link = OsmLinkP(source, this)
        addLink(link)
        source.addLink(link)
        return link
    }

    public fun addLink(link: OsmLinkP) {
        link.setNext(previous, this)
        previous = link
    }

    public fun getFirstLink(): OsmLinkP? =
        if (sourceNode == null && targetNode == null) previous else this

    public open fun getNodeDecsription(): ByteArray? = null
    public open fun getFirstRestriction(): RestrictionData? = null
    public open fun retainWithoutLinks(): Boolean = false

    public fun writeNodeData(mc: MicroCache) {
        val valid = when (mc) {
            is MicroCache2 -> writeNodeData2(mc)
            else -> throw IllegalArgumentException("unknown cache version: ${mc::class.java}")
        }
        if (valid) {
            mc.finishNode(idFromPos)
        } else {
            mc.discardNode()
        }
    }

    public fun checkDuplicateTargets() {
        val targets: MutableMap<OsmNodeP, OsmLinkP> = HashMap()
        var link0 = getFirstLink()
        while (link0 != null) {
            var link: OsmLinkP? = link0
            var origin: OsmNodeP = this
            var target: OsmNodeP? = null
            while (link != null) {
                target = link.getTarget(origin)
                if (!target.isTransferNode()) {
                    break
                }
                var nextLink = target.getFirstLink()
                while (nextLink != null) {
                    if (nextLink.getTarget(target) != origin) {
                        break
                    }
                    nextLink = nextLink.getNext(target)
                }
                link = nextLink
                origin = target
            }
            if (link != null && target != null) {
                val oldLink = targets.put(target, link0)
                if (oldLink != null) {
                    unifyLink(oldLink)
                    unifyLink(link0)
                }
            }
            link0 = link0.getNext(this)
        }
    }

    private fun unifyLink(link: OsmLinkP) {
        if (link.isReverse(this)) return
        val target = link.getTarget(this)
        if (target.isTransferNode()) {
            target.incWayCount()
        }
    }

    public fun writeNodeData2(mc: MicroCache2): Boolean {
        var hasLinks = false
        var restriction = getFirstRestriction()
        while (restriction != null) {
            if (restriction.isValid() && restriction.fromPosition.longitude != 0 && restriction.toPosition.longitude != 0) {
                mc.writeBoolean(true)
                mc.writeShort(restriction.exceptions.toInt())
                mc.writeBoolean(restriction.isPositive())
                mc.writeInt(restriction.fromPosition.longitude)
                mc.writeInt(restriction.fromPosition.latitude)
                mc.writeInt(restriction.toPosition.longitude)
                mc.writeInt(restriction.toPosition.latitude)
            }
            restriction = restriction.next
        }
        mc.writeBoolean(false)
        mc.writeShort(getSElev().toInt())
        mc.writeVarBytes(getNodeDecsription())

        val internalReverse: MutableList<OsmNodeP> = ArrayList()
        var link0 = getFirstLink()
        while (link0 != null) {
            var link: OsmLinkP? = link0
            var origin: OsmNodeP = this
            var target: OsmNodeP? = null
            val linkNodes: MutableList<OsmNodeP> = ArrayList()
            linkNodes.add(this)
            while (link != null) {
                target = link.getTarget(origin)
                linkNodes.add(target)
                if (!target.isTransferNode()) {
                    break
                }
                var nextLink = target.getFirstLink()
                while (nextLink != null) {
                    if (nextLink.getTarget(target) != origin) {
                        break
                    }
                    nextLink = nextLink.getNext(target)
                }
                link = nextLink
                if (link != null && link.descriptionBitmap !== link0.descriptionBitmap) {
                    throw IllegalArgumentException("assertion failed: description change along transfer nodes")
                }
                origin = target
            }
            if (link != null && target != this && target != null) {
                hasLinks = true
                val isReverse = link0.isReverse(this)
                if (isReverse && mc.isInternal(target.longitude, target.latitude)) {
                    internalReverse.add(target)
                } else {
                    val sizeoffset = mc.writeSizePlaceHolder()
                    mc.writeVarLengthSigned(target.longitude - longitude)
                    mc.writeVarLengthSigned(target.latitude - latitude)
                    mc.writeModeAndDesc(isReverse, link0.descriptionBitmap)
                    if (!isReverse && linkNodes.size > 2) {
                        DPFilter.doDPFilter(linkNodes)
                        origin = this
                        for (i in 1 until linkNodes.size - 1) {
                            val transferNode = linkNodes[i]
                            if ((transferNode.bits.toInt() and DP_SURVIVOR_BIT) != 0) {
                                mc.writeVarLengthSigned(transferNode.longitude - origin.longitude)
                                mc.writeVarLengthSigned(transferNode.latitude - origin.latitude)
                                mc.writeVarLengthSigned(transferNode.getSElev() - origin.getSElev())
                                origin = transferNode
                            }
                        }
                    }
                    mc.injectSize(sizeoffset)
                }
            }
            link0 = link0.getNext(this)
        }

        while (internalReverse.isNotEmpty()) {
            var nextIdx = 0
            if (internalReverse.size > 1) {
                var max32 = Int.MIN_VALUE
                for (i in internalReverse.indices) {
                    val id32 = mc.shrinkId(internalReverse[i].idFromPos)
                    if (id32 > max32) {
                        max32 = id32
                        nextIdx = i
                    }
                }
            }
            val target = internalReverse.removeAt(nextIdx)
            val sizeoffset = mc.writeSizePlaceHolder()
            mc.writeVarLengthSigned(target.longitude - longitude)
            mc.writeVarLengthSigned(target.latitude - latitude)
            mc.writeModeAndDesc(true, null)
            mc.injectSize(sizeoffset)
        }
        return hasLinks || retainWithoutLinks()
    }

    public fun toString2(): String =
        "${longitude - 180000000}_${latitude - 900000000}_${getElev()}"

    public val idFromPos: Long
        get() = Position.computeId(longitude, latitude)

    public fun isBorderNode(): Boolean = (bits.toInt() and BORDER_BIT) != 0
    public fun hasTraffic(): Boolean = (bits.toInt() and TRAFFIC_BIT) != 0

    public fun incWayCount() {
        if ((bits.toInt() and ANY_WAY_BIT) != 0) {
            bits = (bits.toInt() or MULTI_WAY_BIT).toByte()
        }
        bits = (bits.toInt() or ANY_WAY_BIT).toByte()
    }

    public open fun isTransferNode(): Boolean =
        (bits.toInt() and BORDER_BIT) == 0 && (bits.toInt() and MULTI_WAY_BIT) == 0 && linkCount() == 2

    private fun linkCount(): Int {
        var cnt = 0
        var link = getFirstLink()
        while (link != null) {
            cnt++
            link = link.getNext(this)
        }
        return cnt
    }

    public companion object {
        public const val NO_BRIDGE_BIT: Int = 1
        public const val NO_TUNNEL_BIT: Int = 2
        public const val BORDER_BIT: Int = 4
        public const val TRAFFIC_BIT: Int = 8
        public const val ANY_WAY_BIT: Int = 16
        public const val MULTI_WAY_BIT: Int = 32
        public const val DP_SURVIVOR_BIT: Int = 64
    }
}