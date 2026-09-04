package com.danemadsen.atlas.beerouter.map

/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
public open class OsmLink {
    /**
     * The description bitmap contains the waytags (valid for both directions)
     */
    public var descriptionBitmap: ByteArray? = null

    /**
     * True when the description was pre-attached by a reverse record
     * (LOCAL PATCH, Atlas): such an instance is still awaiting its forward
     * record, which must be allowed to complete it (fill geometry) rather
     * than fork a duplicate, geometry-less parallel instance.
     */
    public var descriptionFromReverseRecord: Boolean = false
        internal set

    /**
     * The geometry contains intermediate nodes, null for none (valid for both directions)
     */
    public var geometry: ByteArray? = null

    // a link logically knows only its target, but for the reverse link, source and target are swapped
    public var n1: OsmNode? = null
        internal set
    public var n2: OsmNode? = null
        internal set

    // same for the next-link-for-node pointer: previous applies to the reverse link
    public var previous: OsmLink? = null
        internal set
    public var next: OsmLink? = null
        internal set

    private var reverselinkholder: OsmLinkHolder? = null
    private var firstlinkholder: OsmLinkHolder? = null

    public constructor()

    public constructor(source: OsmNode?, target: OsmNode?) {
        n1 = source
        n2 = target
    }

    // Forward direction when n2 is non-null and differs from the source node
    private fun isForward(source: OsmNode?): Boolean = n2 != null && n2 !== source

    /**
     * Get the relevant target-node for the given source
     */
    public fun getTarget(source: OsmNode?): OsmNode? = if (isForward(source)) n2 else n1

    /**
     * Get the relevant next-pointer for the given source
     */
    public fun getNext(source: OsmNode?): OsmLink? = if (isForward(source)) next else previous

    /**
     * Reset this link for the given direction
     */
    public fun clear(source: OsmNode?): OsmLink? {
        val nextLink: OsmLink?
        if (isForward(source)) {
            nextLink = next; next = null; n2 = null; firstlinkholder = null
        } else {
            nextLink = previous; previous = null; n1 = null; reverselinkholder = null
        }

        if (n1 == null && n2 == null) {
            descriptionBitmap = null
            geometry = null
            descriptionFromReverseRecord = false
        }
        return nextLink
    }

    public fun setFirstLinkHolder(holder: OsmLinkHolder?, source: OsmNode?) {
        if (isForward(source)) firstlinkholder = holder else reverselinkholder = holder
    }

    public fun getFirstLinkHolder(source: OsmNode?): OsmLinkHolder? {
        return if (isForward(source)) firstlinkholder else reverselinkholder
    }

    public fun isReverse(source: OsmNode?): Boolean {
        return !isForward(source) && n1 != null && n1 !== source
    }

    public val isBidirectional: Boolean
        get() = n1 != null && n2 != null

    public val isLinkUnused: Boolean
        get() = n1 == null && n2 == null

    public fun addLinkHolder(holder: OsmLinkHolder, source: OsmNode?) {
        val firstHolder = getFirstLinkHolder(source)
        if (firstHolder != null) {
            holder.nextForLink = firstHolder
        }
        setFirstLinkHolder(holder, source)
    }
}
