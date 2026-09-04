package com.danemadsen.atlas.beerouter.map.generator

public open class OsmLinkP(
    protected var sourceNode: OsmNodeP? = null,
    protected var targetNode: OsmNodeP? = null,
) {
    public var descriptionBitmap: ByteArray? = null
    protected var previous: OsmLinkP? = null
    protected var next: OsmLinkP? = null

    public fun counterLinkWritten(): Boolean = descriptionBitmap == null

    public fun setNext(link: OsmLinkP?, source: OsmNodeP) {
        when (source) {
            sourceNode -> next = link
            targetNode -> previous = link
            else -> throw IllegalArgumentException("internal error: setNext: unknown source")
        }
    }

    public fun getNext(source: OsmNodeP): OsmLinkP? =
        when (source) {
            sourceNode -> next
            targetNode -> previous
            else -> throw IllegalArgumentException("internal error: gextNext: unknown source")
        }

    public fun getTarget(source: OsmNodeP): OsmNodeP =
        when (source) {
            sourceNode -> targetNode!!
            targetNode -> sourceNode!!
            else -> throw IllegalArgumentException("internal error: getTarget: unknown source")
        }

    public fun isReverse(source: OsmNodeP): Boolean =
        when (source) {
            sourceNode -> false
            targetNode -> true
            else -> throw IllegalArgumentException("internal error: isReverse: unknown source")
        }
}
