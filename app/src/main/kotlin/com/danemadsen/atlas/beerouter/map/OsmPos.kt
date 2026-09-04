/**
 * Interface for a position (OsmNode or OsmPath)
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.map

import com.danemadsen.atlas.beerouter.geo.Position

public interface OsmPos {
    public val position: Position

    public val altitude: Short

    public fun distanceTo(p: OsmPos): Int

    public val idFromPos: Long
}
