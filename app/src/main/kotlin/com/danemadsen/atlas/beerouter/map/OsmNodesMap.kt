/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.map

import androidx.collection.MutableLongObjectMap
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.util.ByteArrayUnifier

public class OsmNodesMap {
    public enum class CleanupMode {
        COUNT_ONLY,
        PENINSULAS,
        TREE
    }

    private val hmap: MutableLongObjectMap<OsmNode> = MutableLongObjectMap(4096)

    public val byteArrayUnifier: ByteArrayUnifier = ByteArrayUnifier(16384, false)

    public var nodesCreated: Int = 0
    public var maxMemoryBytes: Long = 0
    private var currentMaxMemoryBytes: Long = 4000000 // start with 4 MB
    public var lastVisitID: Int = 1000
    public var baseID: Int = 1000

    public var destination: OsmNode? = null

    public var currentPathCost: Int = 0

    public var currentMaxCost: Int = 1000000000

    public var endNode1: OsmNode? = null

    public var endNode2: OsmNode? = null

    public var cleanupMode: CleanupMode = CleanupMode.COUNT_ONLY

    private val dfsFrames: ArrayList<DfsFrame> = ArrayList(256)

    internal fun configureMemoryPolicy(memoryPolicy: RoutingMemoryPolicy) {
        currentMaxMemoryBytes = memoryPolicy.graphInitialBudgetBytes
        maxMemoryBytes = memoryPolicy.graphHardLimitBytes
    }

    public fun cleanupAndCount(nodes: List<OsmNode>) {
        if (cleanupMode == CleanupMode.COUNT_ONLY) {
            justCount(nodes)
        } else {
            cleanupPeninsulas(nodes)
        }
    }

    internal fun cleanupAndCount(nodes: Array<Any?>, size: Int) {
        if (cleanupMode == CleanupMode.COUNT_ONLY) {
            justCount(nodes, size)
        } else {
            cleanupPeninsulas(nodes, size)
        }
    }

    private fun justCount(nodes: List<OsmNode>) {
        for (n in nodes) {
            if (n.firstlink != null) {
                nodesCreated++
            }
        }
    }

    private fun justCount(nodes: Array<Any?>, size: Int) {
        for (i in 0..<size) {
            val node = nodes[i] as? OsmNode ?: continue
            if (node.firstlink != null) {
                nodesCreated++
            }
        }
    }

    private fun cleanupPeninsulas(nodes: List<OsmNode>) {
        baseID = lastVisitID++
        for (n in nodes) { // loop over nodes again just for housekeeping
            if (n.firstlink != null) {
                if (n.visitID == 1) {
                    minVisitIdInSubtree(null, n)
                }
            }
        }
    }

    private fun cleanupPeninsulas(nodes: Array<Any?>, size: Int) {
        baseID = lastVisitID++
        for (i in 0..<size) { // loop over nodes again just for housekeeping
            val n = nodes[i] as? OsmNode ?: continue
            if (n.firstlink != null) {
                if (n.visitID == 1) {
                    minVisitIdInSubtree(null, n)
                }
            }
        }
    }

    private class DfsFrame {
        var source: OsmNode? = null
        var n: OsmNode? = null
        var l: OsmLink? = null
        var minId: Int = 0
        var pendingLink: OsmLink? = null
        var pendingTarget: OsmNode? = null
        var pendingNodesCreated: Int = 0

        fun reset(source: OsmNode?, n: OsmNode, minId: Int): DfsFrame {
            this.source = source
            this.n = n
            this.l = n.firstlink
            this.minId = minId
            pendingLink = null
            pendingTarget = null
            pendingNodesCreated = 0
            return this
        }

        fun clear() {
            source = null
            n = null
            l = null
            pendingLink = null
            pendingTarget = null
            pendingNodesCreated = 0
        }
    }

    private fun minVisitIdInSubtree(source: OsmNode?, n: OsmNode): Int {
        var depth = 0
        var maxDepth = 0

        fun frameAt(index: Int): DfsFrame {
            while (dfsFrames.size <= index) dfsFrames.add(DfsFrame())
            return dfsFrames[index]
        }

        fun push(src: OsmNode?, node: OsmNode) {
            if (node.visitID == 1) node.visitID = baseID // border node
            else node.visitID = lastVisitID++
            nodesCreated++
            frameAt(depth++).reset(src, node, node.visitID)
            if (depth > maxDepth) maxDepth = depth
        }

        push(source, n)
        var childResult = 0

        while (depth > 0) {
            val frame = dfsFrames[depth - 1]
            val frameNode = requireNotNull(frame.n)

            // Apply the result of the child that just completed
            if (frame.pendingLink != null) {
                val pl = frame.pendingLink!!
                val pt = frame.pendingTarget!!
                if (childResult > frameNode.visitID) { // peninsula?
                    nodesCreated = frame.pendingNodesCreated
                    frameNode.unlinkLink(pl)
                    pt.unlinkLink(pl)
                } else if (childResult < frame.minId) {
                    frame.minId = childResult
                }
                frame.pendingLink = null
                frame.pendingTarget = null
            }

            // Continue walking this node's links
            var pushed = false
            while (frame.l != null) {
                val l = frame.l!!
                val nextLink = l.getNext(frameNode)
                val t = requireNotNull(l.getTarget(frameNode))

                if (t === frame.source) {
                    frame.l = nextLink
                    continue
                }
                if (t.isHollow) {
                    frame.l = nextLink
                    continue
                }

                var minIdSub = t.visitID
                when {
                    minIdSub == 1 -> minIdSub = baseID
                    minIdSub == 0 -> {
                        // Descend into unvisited child — save state, push child frame
                        frame.pendingLink = l
                        frame.pendingTarget = t
                        frame.pendingNodesCreated = nodesCreated
                        frame.l = nextLink
                        push(frameNode, t)
                        pushed = true
                        break
                    }
                    minIdSub < baseID -> {
                        frame.l = nextLink
                        continue
                    }
                    cleanupMode == CleanupMode.TREE -> minIdSub = baseID // in tree-mode, hitting anything is like a gateway
                }

                if (minIdSub < frame.minId) frame.minId = minIdSub
                frame.l = nextLink
            }

            if (!pushed) {
                // All links processed — pop and report result to parent
                childResult = frame.minId
                depth--
            }
        }

        for (i in 0..<maxDepth) dfsFrames[i].clear()
        return childResult
    }


    public fun isInMemoryBounds(npaths: Int, extend: Boolean): Boolean {
        //    long total = nodesCreated * 76L + linksCreated * 48L;
        var total = nodesCreated * 95L + npaths * 200L

        if (extend) {
            total += 100000

            // when extending, try to have 1 MB  space
            val delta = total + 1900000 - currentMaxMemoryBytes
            if (delta > 0) {
                currentMaxMemoryBytes += delta
                if (currentMaxMemoryBytes > maxMemoryBytes) {
                    currentMaxMemoryBytes = maxMemoryBytes
                }
            }
        }
        return total <= currentMaxMemoryBytes
    }

    private val nodesToCheck: MutableList<OsmNode> = mutableListOf()
    private var hasTempNodes: Boolean = false

    // is there an escape from this node
    // to a hollow node (or destination node) ?
    public fun canEscape(n0: OsmNode?): Boolean {
        if (!hasTempNodes || n0 == null) return false
        var sawLowIDs = false
        lastVisitID++
        nodesToCheck.clear()
        nodesToCheck.add(n0)
        while (nodesToCheck.isNotEmpty()) {
            val n = nodesToCheck.removeAt(nodesToCheck.size - 1)
            if (n.visitID < baseID) {
                n.visitID = lastVisitID
                nodesCreated++
                var l = n.firstlink
                while (l != null) {
                    nodesToCheck.add(requireNotNull(l.getTarget(n)))
                    l = l.getNext(n)
                }
            } else if (n.visitID < lastVisitID) {
                sawLowIDs = true
            }
        }
        if (sawLowIDs) {
            return true
        }

        nodesToCheck.add(n0)
        while (nodesToCheck.isNotEmpty()) {
            val n = nodesToCheck.removeAt(nodesToCheck.size - 1)
            if (n.visitID == lastVisitID) {
                n.visitID = lastVisitID
                nodesCreated--
                var l = n.firstlink
                while (l != null) {
                    nodesToCheck.add(requireNotNull(l.getTarget(n)))
                    l = l.getNext(n)
                }
                n.vanish()
            }
        }

        return false
    }

    private fun addActiveNode(nodes2check: MutableList<OsmNode>, n: OsmNode) {
        n.visitID = lastVisitID
        nodesCreated++
        nodes2check.add(n)
    }

    public fun clearTemp() {
        hasTempNodes = false
        nodesToCheck.clear()
    }

    public fun collectOutreachers() {
        hasTempNodes = true
        nodesToCheck.clear()
        nodesCreated = 0
        hmap.forEach { _, node ->
            addActiveNode(nodesToCheck, node)
        }

        lastVisitID++
        baseID = lastVisitID

        while (nodesToCheck.isNotEmpty()) {
            val n = nodesToCheck.removeAt(nodesToCheck.size - 1)
            n.visitID = lastVisitID

            var l = n.firstlink
            while (l != null) {
                val t = requireNotNull(l.getTarget(n))
                if (t.visitID != lastVisitID) {
                    addActiveNode(nodesToCheck, t)
                }
                l = l.getNext(n)
            }
            val currentDestination = destination
            if (currentDestination != null && currentMaxCost < 1000000000) {
                val distance = n.distanceTo(currentDestination)
                if (distance > currentMaxCost - currentPathCost + 100) {
                    n.vanish()
                }
            }
            if (n.firstlink == null) {
                nodesCreated--
            }
        }
    }


    /**
     * Get a node from the map
     *
     * @return the node for the given id if exist, else null
     */
    public fun get(id: Long): OsmNode? = hmap[id]

    public fun get(position: Position): OsmNode? = hmap[position.id]

    internal fun get(longitude: Int, latitude: Int): OsmNode? = hmap[Position.computeId(longitude, latitude)]



    public fun remove(node: OsmNode) {
        if (node !== endNode1 && node !== endNode2) { // keep endnodes in hollow-map even when loaded
            hmap.remove(node.idFromPos)
        }
    }

    /**
     * Put a node into the map
     *
     * @return the previous node if that id existed, else null
     */
    public fun put(node: OsmNode): OsmNode? {
        return hmap.put(node.idFromPos, node)
    }
}
