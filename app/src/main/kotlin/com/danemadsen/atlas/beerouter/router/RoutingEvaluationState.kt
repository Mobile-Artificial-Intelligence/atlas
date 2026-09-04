package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.geo.Position

internal data class RoutingEvaluationState(
    internal val nogopoints: MutableList<OsmNodeNamed>,
    internal val keepnogopoints: MutableList<OsmNodeNamed>,
    internal val pendingEndpoint: OsmNodeNamed?,
    internal val nogoCost: Double,
    internal val isEndpoint: Boolean,
    internal val shortestmatch: Boolean,
    internal val wayfraction: Double,
    internal val shortestPosition: Position?,
)
