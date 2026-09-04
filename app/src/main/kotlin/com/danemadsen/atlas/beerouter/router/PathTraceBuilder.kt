package com.danemadsen.atlas.beerouter.router

internal object PathTraceBuilder {
    fun toPathElement(path: OsmPath): OsmPathElement = toFinalTrackElement(path)

    fun toFinalTrackElement(path: OsmPath): OsmPathElement = when (path) {
        is AcceptedPath -> toAcceptedElement(path)
        else -> toCompatibilityElement(path)
    }

    fun toMatchPathElement(path: OsmPath): OsmPathElement = when (path) {
        is AcceptedPath -> toAcceptedElement(path)
        else -> toCompatibilityElement(path)
    }

    fun toDetourElement(path: OsmPath): OsmPathElement = toCompatibilityElement(path)

    private fun toCompatibilityElement(path: OsmPath): OsmPathElement {
        path.materializeOriginElement()
        val target = requireNotNull(path.targetNode)
        return OsmPathElement(
            target.positionWithAltitude(),
            path.originElement
        ).also {
            it.cost = path.cost
            it.message = path.message
        }
    }

    private fun toAcceptedElement(path: AcceptedPath): OsmPathElement {
        var depth = 0
        var cursor: AcceptedPath? = path
        while (cursor != null) {
            depth++
            cursor = cursor.parent
        }

        val paths = arrayOfNulls<AcceptedPath>(depth)
        cursor = path
        for (idx in 0..<depth) {
            paths[idx] = cursor
            cursor = requireNotNull(cursor).parent
        }

        var origin: OsmPathElement? = null
        for (idx in depth - 1 downTo 0) {
            val accepted = requireNotNull(paths[idx])
            val target = requireNotNull(accepted.targetNode)
            origin = OsmPathElement(target.positionWithAltitude(), origin).also {
                it.cost = accepted.cost
                it.message = accepted.message ?: MessageData()
                it.time = accepted.totalTime.toFloat()
                it.energy = accepted.totalEnergy.toFloat()
            }
        }
        return requireNotNull(origin)
    }
}
