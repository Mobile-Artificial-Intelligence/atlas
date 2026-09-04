/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.expressions.BExpressionContextNode
import com.danemadsen.atlas.beerouter.expressions.BExpressionContextWay

public abstract class OsmPathModel {
    public abstract fun createPrePath(): OsmPrePath?

    public abstract fun createPath(): OsmPath

    public open fun recyclePath(path: OsmPath) {
    }

    public abstract fun init(
        expctxWay: BExpressionContextWay?,
        expctxNode: BExpressionContextNode?,
        keyValues: Map<String, String>
    )
}
