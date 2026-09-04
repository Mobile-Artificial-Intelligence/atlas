package com.danemadsen.atlas.beerouter.map

import com.danemadsen.atlas.beerouter.codec.MicroCache
import com.danemadsen.atlas.beerouter.codec.MicroCache2
import com.danemadsen.atlas.beerouter.geo.CheapRuler.distance
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.geo.UNSET_ELEVATION
import com.danemadsen.atlas.beerouter.geo.latitudeFromId
import com.danemadsen.atlas.beerouter.geo.longitudeFromId
import com.danemadsen.atlas.beerouter.geo.withAltitude
import com.danemadsen.atlas.beerouter.util.IByteArrayUnifier
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Container for an osm node
 *
 * @author ab
 */
public open class OsmNode : OsmLink, OsmPos {
    public var longitude: Int = 0
        private set

    public var latitude: Int = 0
        private set

    private var cachedPosition: Position? = Position.ZERO

    override var position: Position
        get() {
            cachedPosition?.let { return it }
            return Position(longitude, latitude).also { cachedPosition = it }
        }
        internal set(value) {
            longitude = value.longitude
            latitude = value.latitude
            _idFromPos = value.id
            cachedPosition = value.withAltitude(UNSET_ELEVATION)
            positionWithAltitude = null
        }

    override var altitude: Short = UNSET_ELEVATION
        internal set(value) {
            field = value
            positionWithAltitude = null
        }

    /**
     * The node-tags, if any
     */
    public var nodeDescription: ByteArray? = null
        internal set

    public var firstRestriction: TurnRestriction? = null
        internal set

    public var visitID: Int = 0
        internal set

    public fun addTurnRestriction(tr: TurnRestriction) {
        tr.next = firstRestriction
        firstRestriction = tr
    }

    /**
     * The links to other nodes
     */
    public var firstlink: OsmLink? = null
        internal set

    private var hollow: Boolean = false

    // Cached id computed from lon/lat — stable because lon/lat rarely change after construction
    private var _idFromPos: Long = Position.ZERO.id
    private var positionWithAltitude: Position? = null

    public fun positionWithAltitude(): Position {
        positionWithAltitude?.let { return it }
        return position.withAltitude(altitude).also { positionWithAltitude = it }
    }

    public constructor()

    public constructor(ilon: Int, ilat: Int) {
        longitude = ilon
        latitude = ilat
        _idFromPos = Position.computeId(ilon, ilat)
        cachedPosition = null
    }

    public constructor(id: Long) {
        longitude = id.longitudeFromId()
        latitude = id.latitudeFromId()
        _idFromPos = id
        cachedPosition = null
    }

    public constructor(position: Position) {
        this.position = position.withAltitude(UNSET_ELEVATION)
        this.altitude = position.altitude
    }



    public fun addLink(link: OsmLink, isReverse: Boolean, tn: OsmNode) {
        require(link !== firstlink) { "UUUUPS" }

        if (isReverse) {
            link.n1 = tn
            link.n2 = this
            link.next = tn.firstlink
            link.previous = firstlink
            tn.firstlink = link
            firstlink = link
        } else {
            link.n1 = this
            link.n2 = tn
            link.next = firstlink
            link.previous = tn.firstlink
            tn.firstlink = link
            firstlink = link
        }
    }

    override fun distanceTo(p: OsmPos): Int {
        val other = p.position
        return max(
            1,
            distance(position.longitude, position.latitude, other.longitude, other.latitude).roundToInt()
        )
    }

    override fun toString(): String {
        return "n_${position.longitude - 180000000}_${position.latitude - 90000000}"
    }

    /**
     * @throws IllegalStateException if the cache version is not supported
     */
    public fun parseNodeTags(mc: MicroCache, unifier: IByteArrayUnifier): ByteArray? {
        if (mc !is MicroCache2) throw IllegalStateException("unknown cache version: " + mc::class.simpleName)

        // skip turn restrictions
        while (mc.readBoolean()) {
            mc.readShort() // exceptions
            mc.readBoolean() // isPositive
            mc.readInt(); mc.readInt() // from
            mc.readInt(); mc.readInt() // to
        }

        val readSElev = mc.readShort()
        altitude = readSElev
        hollow = false
        val nodeDescSize = mc.readVarLengthUnsigned()
        nodeDescription = if (nodeDescSize == 0) null else mc.readUnified(nodeDescSize, unifier)
        return nodeDescription
    }

    /**
     * @throws IllegalStateException if the cache version is not supported
     */
    public fun parseNodeBody(mc: MicroCache, hollowNodes: OsmNodesMap, expCtxWay: IByteArrayUnifier) {
        if (mc is MicroCache2) {
            parseNodeBody2(mc, hollowNodes, expCtxWay)
        } else throw IllegalStateException("unknown cache version: " + mc::class.simpleName)
    }

    public fun parseNodeBody2(mc: MicroCache2, hollowNodes: OsmNodesMap, expCtxWay: IByteArrayUnifier) {
        val abUnifier = hollowNodes.byteArrayUnifier

        // read turn restrictions
        while (mc.readBoolean()) {
            addTurnRestriction(
                TurnRestriction.create(
                    exceptions = mc.readShort(),
                    isPositive = mc.readBoolean(),
                    fromLongitude = mc.readInt(),
                    fromLatitude = mc.readInt(),
                    toLongitude = mc.readInt(),
                    toLatitude = mc.readInt(),
                )
            )
        }

        val readSElev = mc.readShort()
        altitude = readSElev
        hollow = false
        val nodeDescSize = mc.readVarLengthUnsigned()
        nodeDescription = if (nodeDescSize == 0) null else mc.readUnified(nodeDescSize, abUnifier)

        while (mc.hasMoreData()) {
            // read link data
            val endPointer = mc.endPointer
            val targetLongitude = position.longitude + mc.readVarLengthSigned()
            val targetLatitude = position.latitude + mc.readVarLengthSigned()
            val sizecode = mc.readVarLengthUnsigned()
            val isReverse = (sizecode and 1) != 0
            var description: ByteArray? = null
            val descSize = sizecode shr 1
            if (descSize > 0) {
                description = mc.readUnified(descSize, expCtxWay)
            }
            val geometry = mc.readDataUntil(endPointer)

            addLink(targetLongitude, targetLatitude, description, geometry, hollowNodes, isReverse)
        }
        hollowNodes.remove(this)
    }

    public fun addLink(
        targetPosition: Position,
        description: ByteArray?,
        geometry: ByteArray?,
        hollowNodes: OsmNodesMap,
        isReverse: Boolean
    ) {
        addLink(targetPosition.longitude, targetPosition.latitude, description, geometry, hollowNodes, isReverse)
    }

    public fun addLink(
        targetLongitude: Int,
        targetLatitude: Int,
        description: ByteArray?,
        geometry: ByteArray?,
        hollowNodes: OsmNodesMap,
        isReverse: Boolean
    ) {
        if (targetLongitude == position.longitude && targetLatitude == position.latitude) {
            return  // skip self-ref
        }

        var tn: OsmNode? = null // find the target node
        var link: OsmLink? = null

        // ...in our known links
        var l = firstlink
        while (l != null) {
            val t = l.getTarget(this)
            val tId = t!!.idFromPos
            if (tId.longitudeFromId() == targetLongitude && tId.latitudeFromId() == targetLatitude) {
                tn = t
                if (isReverse || ((l.descriptionBitmap == null || l.descriptionFromReverseRecord) && !l.isReverse(this))) {
                    link = l // the correct one that needs our data
                    break
                }
            }
            l = l.getNext(this)
        }
        if (tn == null) { // .. not found, then check the hollow nodes
            tn = hollowNodes.get(targetLongitude, targetLatitude) // target node
            if (tn == null) { // node not yet known, create a new hollow proxy
                tn = OsmNode(targetLongitude, targetLatitude)
                tn.setHollow()
                hollowNodes.put(tn)
                addLink(
                    tn.also { link = it },
                    isReverse,
                    tn
                ) // technical inheritance: link instance in node
            }
        }
        if (link == null) {
            addLink(OsmLink().also { link = it }, isReverse, tn)
        }
        if (!isReverse) {
            link!!.descriptionBitmap = description
            link.geometry = geometry
            link!!.descriptionFromReverseRecord = false
        } else if (description != null && link!!.descriptionBitmap == null) {
            // LOCAL PATCH (Atlas): records for external targets carry the
            // description bitmap in BOTH directions (see
            // OsmNodeP.writeNodeData2, which writes it for reverse records
            // too), but stock dropped it here. A link instance that never
            // saw the forward record — e.g. a fresh proxy created when a
            // 5°-bucket border tile re-weaves after an eviction, or an
            // instance re-created after OsmLink.clear() nulled its
            // description — then stayed desc-less forever, and routing
            // through it took the (crashing, zero-cost) beeline branch.
            // Fill it so every cross-bucket instance is self-describing.
            link!!.descriptionBitmap = description
            link!!.descriptionFromReverseRecord = true
        }
    }


    public val isHollow: Boolean
        get() = hollow

    public fun setHollow() {
        hollow = true
    }

    public fun clearHollow() {
        hollow = false
    }

    override val idFromPos: Long
        get() = _idFromPos

    public fun vanish() {
        if (!this.isHollow) {
            var l = firstlink
            while (l != null) {
                val target = l.getTarget(this)
                val nextLink = l.getNext(this)
                if (!target!!.isHollow) {
                    unlinkLink(l)
                    if (!l.isLinkUnused) {
                        target.unlinkLink(l)
                    }
                }
                l = nextLink
            }
        }
    }

    public fun unlinkLink(link: OsmLink) {
        val n = link.clear(this)

        if (link === firstlink) {
            firstlink = n
            return
        }
        var l = firstlink
        while (l != null) {
            // if ( l.isReverse( this ) )
            if (l.n1 !== this && l.n1 != null) { // isReverse inline
                val nl = l.previous
                if (nl === link) {
                    l.previous = n
                    return
                }
                l = nl
            } else if (l.n2 !== this && l.n2 != null) {
                val nl = l.next
                if (nl === link) {
                    l.next = n
                    return
                }
                l = nl
            } else {
                throw IllegalStateException("unlinkLink: unknown source")
            }
        }
    }


    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OsmNode) return false
        return other.idFromPos == idFromPos
    }

    override fun hashCode(): Int {
        return _idFromPos.hashCode()
    }

}
