package com.danemadsen.atlas.beerouter.map.generator

public class OsmNodePT() : OsmNodeP() {
    public var descriptionBits: ByteArray? = null
    public var firstRestrictionData: RestrictionData? = null
    private var keepWithoutLinks: Boolean = false

    public constructor(node: OsmNodeP) : this() {
        longitude = node.longitude
        latitude = node.latitude
        altitude = node.altitude
        bits = node.bits
    }

    public constructor(descriptionBits: ByteArray?, retainWithoutLinks: Boolean = false) : this() {
        this.descriptionBits = descriptionBits
        this.keepWithoutLinks = retainWithoutLinks
    }

    override fun getNodeDecsription(): ByteArray? = descriptionBits
    override fun getFirstRestriction(): RestrictionData? = firstRestrictionData
    override fun retainWithoutLinks(): Boolean = keepWithoutLinks
    override fun isTransferNode(): Boolean = false
}
