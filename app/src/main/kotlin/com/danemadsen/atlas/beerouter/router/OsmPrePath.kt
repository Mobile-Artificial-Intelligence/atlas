/**
 * Simple version of OsmPath just to get angle and priority of first segment
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.map.OsmLink
import com.danemadsen.atlas.beerouter.map.OsmNode

public abstract class OsmPrePath {
    protected var sourceNode: OsmNode? = null
    protected var targetNode: OsmNode? = null
    protected var link: OsmLink? = null

    public var next: OsmPrePath? = null

    public fun init(origin: OsmPath, link: OsmLink, rc: RoutingContext) {
        val originTargetNode = requireNotNull(origin.targetNode)
        this.link = link
        sourceNode = originTargetNode
        targetNode = link.getTarget(originTargetNode)
        initPrePath(origin, rc)
    }

    protected abstract fun initPrePath(origin: OsmPath, rc: RoutingContext)
}
