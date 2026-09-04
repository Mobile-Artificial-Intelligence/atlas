package com.danemadsen.atlas.graph

import androidx.collection.MutableLongList
import androidx.collection.MutableLongLongMap
import androidx.collection.MutableLongSet
import com.danemadsen.atlas.pmtiles.PmtilesReader
import com.danemadsen.atlas.pmtiles.TileBounds
import com.danemadsen.atlas.pmtiles.mvt.GeoPoint
import com.danemadsen.atlas.pmtiles.mvt.MvtGeomType
import com.danemadsen.atlas.pmtiles.mvt.MvtTile
import com.danemadsen.atlas.beerouter.expressions.BExpressionContextNode
import com.danemadsen.atlas.beerouter.expressions.BExpressionContextWay
import com.danemadsen.atlas.beerouter.expressions.BExpressionMetaData
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.map.generator.GeneratorBase
import com.danemadsen.atlas.beerouter.map.generator.NodeData
import com.danemadsen.atlas.beerouter.map.generator.NodeFilter
import com.danemadsen.atlas.beerouter.map.generator.WayCutter
import com.danemadsen.atlas.beerouter.map.generator.WayData
import java.io.File

/**
 * The PMTiles replacement for BeeRouter's `OsmCutter` phase: instead of
 * parsing an OSM PBF, it decodes the OpenMapTiles `transportation` layer from
 * the user's PMTiles archive, synthesizes routing nodes and ways from the
 * tile geometry, and writes the exact same phase-1 intermediate files
 * (`.ntl` node tiles and `.wtl` way tiles, 45-degree grid) that `OsmCutter`
 * would have produced. The rest of the vendored pipeline (`WayCutter5` ->
 * `PosUnifier` -> `WayLinker`) then runs unchanged.
 *
 * Parity rules this mirrors from `OsmCutter`:
 * - node ids are opaque longs (OSM ids there; snap-grid-assigned here) —
 *   only way membership decides a node's survival downstream;
 * - ways are encoded through `BExpressionContextWay` (value spaces become
 *   underscores) and kept only if `costfactor < 10000f` in either direction;
 * - all nodes are delivered before any way, because `WayCutter` builds its
 *   nid-to-tile map during the node phase;
 * - nodes live in `.ntl` files as one DiffCoder delta chain per file
 *   (channels 0/1/2 = nid/longitude/latitude, zigzag LEB128).
 *
 * ## Fragment relinking (the MVT buffer-clip problem)
 *
 * MVT encoders clip each feature to its tile plus a small buffer, so a road
 * crossing a tile border arrives as two fragments whose cut points sit on
 * two clip lines roughly `2 x buffer` apart (measured on the Melbourne
 * fixture at z14 with Planetiler's default 4-unit buffer: a 2-5 m cluster,
 * with only ~7% of pairs close enough to land in the same snap cell).
 * Relinking fragments by exact snap-cell identity therefore shatters the
 * network. Instead of writing while scanning, this cutter buffers every
 * fragment in memory and runs a merge pass before emitting anything:
 *
 *  1. endpoints of fragments sharing a way id are unified when within
 *     `tile/128` in E6 units (~19 m at z14) — the buffer-clip scale,
 *     sized to cover MVT producers using buffers through 16/4096;
 *  2. any two endpoints within `tile/1024` (~2.4 m at z14) are unified
 *     regardless of way id — two ways intersecting inside the clip band
 *     both cut near the same clip line, and at that scale their cut points
 *     are indistinguishable (parallel carriageways are never that close at
 *     MVT quantization). This is what restores intersections that fall in
 *     the band straddling a tile border;
 *  3. [stitchDanglingEndpoints] repairs junctions the per-way simplifier
 *     severed: an endpoint within `tile/1024` of a foreign way's vertex is
 *     unified with it, and an endpoint that distance from a foreign way's
 *     segment interior is inserted into that way's node list — restoring
 *     the junction vertex Planetiler's Douglas-Peucker dropped from the
 *     through-way (a straight arterial keeps no node at any of its side
 *     streets' junctions, so every side street would otherwise dangle).
 *
 * Merged nodes collapse to one canonical node (the union-find root); ways
 * are emitted with their node lists rewritten through the canonical map.
 * Both thresholds scale with the tile width so archives scanned at other
 * zooms (maxZoom < 14) still work.
 *
 * Memory: one 5-degree metro bucket holds ~1-2 M nodes; the scan-time
 * structures (two primitive long->long maps, one long->packed-position map
 * plus the fragment list) stay well inside a largeHeap budget, and
 * [snapCellToNid] is dropped as soon as the scan ends.
 */
class PmtilesCutter : GeneratorBase() {

    /** Set by the caller before [process]; writes the `.wtl` way tiles. */
    public var wayCutter: WayCutter? = null

    /** Set by the caller before [process]; populated from accepted ways. */
    public var nodeFilter: NodeFilter? = null

    /**
     * Set by the caller before [process]; invoked with every decompressed
     * tile the scan visits, before its transportation layer is decoded —
     * the search index's deep pass (`place`/`poi` at the scan zoom) rides
     * the same archive read instead of re-opening it. The callback must
     * not suspend or block long: it runs inline in the scan's visitor.
     */
    public var onTileScanned: ((zoom: Int, x: Int, y: Int, bytes: ByteArray) -> Unit)? = null

    private lateinit var expctxWay: BExpressionContextWay

    /** One buffered path-fragment of an accepted feature. */
    private class Fragment(
        val wid: Long,
        var nodes: LongArray, // rebuilt in place by junction insertion
        val description: ByteArray,
    )

    /** Every accepted path fragment, in deterministic scan order. */
    private val fragments = ArrayList<Fragment>(1 shl 14)

    /**
     * Snap-grid cell (packed) -> synthetic node id. Scan-time only: the
     * field is NULLED the moment the scan ends (see [process]) —
     * `MutableLongLongMap.clear()` resets the size and metadata but keeps
     * both backing long[capacity] arrays (~134 MB at a metro bucket's
     * scale), and the merge/stitch passes that follow need that heap.
     */
    private var snapCellToNid: MutableLongLongMap? = MutableLongLongMap()

    /**
     * nid -> (ilon shl 32) or ilat, in E6 offset integers. Nulled (not
     * cleared) by [release] for the same reason as [snapCellToNid].
     */
    private var nidPos: MutableLongLongMap? = MutableLongLongMap()

    private var next_nid = 0L

    /** Fallback way id for features without a usable MVT id (per feature). */
    /** Fallback way ids for id-less features; counts down from the ceiling. */
    private var next_unnamed_wid = MAX_WAY_ID - 1

    /** Tiles the scan actually visited (0 -> archive/bounds mismatch). */
    private var tiles_scanned = 0

    /** Number of features that survived the profile filter. */
    private var features_accepted = 0

    /** Junction nodes spliced into foreign ways by the stitch pass. */
    private var junctions_inserted = 0

    /**
     * Scans the archive's `transportation` layer at [zoom] within
     * [scanBounds] and writes phase-1 tiles into [nodeDir] (must exist).
     *
     * [lookupContent]/[profileContent] must be the exact same `lookups.dat`
     * and `all.brf` texts the later `WayLinker` stage uses.
     */
    public fun process(
        reader: PmtilesReader,
        lookupContent: String,
        profileContent: String,
        nodeDir: File,
        scanBounds: TileBounds,
        zoom: Int,
    ) {
        // Mirror of OsmCutter.process's setup, order included: both
        // expression contexts must register as meta listeners before
        // readMetaData dispatches the lookup table lines to them.
        val meta = BExpressionMetaData()
        expctxWay = BExpressionContextWay(meta)
        val expctx_node = BExpressionContextNode(meta)
        meta.readMetaData(lookupContent)
        expctxWay.parseProfile(profileContent, "global")

        outTileDir = nodeDir

        // A scan above the archive's detail maximum would silently see zero
        // tiles; say so before minutes of work discover it the hard way.
        check(zoom <= reader.header.maxZoom) {
            "scan zoom $zoom is above the archive's maxZoom ${reader.header.maxZoom}"
        }

        // Fresh scan-time state: both maps are nulled after their last use
        // (see below and [release]), so a reused instance starts clean.
        snapCellToNid = MutableLongLongMap()
        nidPos = MutableLongLongMap()
        scan(reader, scanBounds, zoom)
        // Scan-time identity, dead weight from here on. `clear()` is NOT
        // enough: MutableLongLongMap.clear() keeps both backing long arrays
        // (see the field's doc) — the map is dropped outright so the
        // merge/stitch passes get the heap back.
        snapCellToNid = null
        // Every node id this run will ever assign is already assigned
        // (1..next_nid): all wayCutter/nodeFilter traffic happens below, in
        // the emission loops. Handing them the final count lets their
        // nid-keyed structures switch from hashed maps to dense arrays
        // (the LOCAL PATCH note in the vendored WayCutter/NodeFilter) —
        // the difference between fitting a 4.6M-node bucket in a device's
        // largeHeap and OOMing mid-stitch.
        wayCutter?.beginDenseIndex(next_nid.toInt())
        nodeFilter?.beginDenseMarks(next_nid.toInt())
        dbg(
            "after scan: tiles=$tiles_scanned features=$features_accepted " +
                "fragments=${fragments.size} nodes=$next_nid heap=${heapStr()}",
        )

        val canonical = mergeFragmentEndpoints(zoom)
        val elided = MutableLongSet(canonical.size * 2)
        canonical.forEach { nid, _ -> elided.add(nid) }

        // Nodes first: WayCutter builds its nid-to-tile map from these.
        for (nid in 1L..next_nid) {
            if (elided.contains(nid)) continue
            writeNode(nid)
        }

        // Then the ways, with merged-away node ids rewritten to roots.
        for (fragment in fragments) {
            emitFragment(fragment, canonical)
        }

        closeTileOutStreams()
    }

    /** Number of distinct synthetic nodes assigned so far. */
    public val nodeCount: Int
        get() = next_nid.toInt()

    /** Number of tiles the last [process] scan visited. */
    public val tilesScanned: Int
        get() = tiles_scanned

    /** Number of features the last [process] scan accepted. */
    public val featuresAccepted: Int
        get() = features_accepted

    /** Junction nodes the last [process] spliced into foreign ways. */
    public val junctionsInserted: Int
        get() = junctions_inserted

    /**
     * Drops the scan/stitch working set. The whole cutter phase holds its
     * input in memory (fragments with full node chains, the nid→position
     * map); on a device the WayLinker stage that follows needs every byte of
     * the heap for its own node maps, so the caller releases the cutter the
     * moment [process] has written its tiles — the counters above stay valid.
     */
    public fun release() {
        fragments.clear()
        // Dropped, not cleared: clear() keeps the backing arrays (see the
        // field's doc) — the WayLinker stage that follows needs the heap.
        nidPos = null
    }

    // ---- scanning ----

    /**
     * Single pass over the scan window: for every routable `transportation`
     * line feature, snap its vertices to node ids and buffer each path as a
     * fragment. Nothing is written yet — fragment endpoints must be merged
     * before nodes and ways can be emitted.
     */
    private fun scan(reader: PmtilesReader, bounds: TileBounds, zoom: Int) {
        val tile_observer = onTileScanned
        reader.forEachTileInBounds(zoom, bounds) { z, x, y, bytes ->
            tiles_scanned++
            tile_observer?.invoke(z, x, y, bytes)
            val tile = MvtTile.decode(bytes)
            val layer = tile.layer(TRANSPORTATION_LAYER) ?: return@forEachTileInBounds
            for (feature in layer.features) {
                if (feature.geomType != MvtGeomType.LINESTRING) continue
                val tags = TransportationTagSynthesis
                    .synthesizeTags(layer.properties(feature))
                    ?: continue
                val description = acceptedDescription(tags) ?: continue

                // One way id per feature — WITHIN one tile. Planetiler's OMT
                // profile merges contiguous same-attribute ways into one
                // feature per tile and labels the group with one
                // constituent's id, so the same id can label different
                // linework in different tiles; the linking stages must not
                // treat wid equality as way identity.
                val wid = if (feature.id in 1 until MAX_WAY_ID) {
                    feature.id
                } else {
                    // Count DOWN from the id ceiling: counting up from 0
                    // collides with genuine small feature ids, merging
                    // unrelated ways under the loose same-wid threshold.
                    next_unnamed_wid--
                }

                val paths = layer.pathsLonLat(feature, z, x, y)
                for (path in paths) {
                    val nodes = snapPath(path)
                    if (nodes.size < MIN_PATH_VERTICES) continue
                    fragments.add(Fragment(wid, nodes, description))
                }
                features_accepted++
            }
        }
        // Zero accepted features over a scanned window is legitimate (an
        // all-ocean bucket); the caller reads [featuresAccepted] and treats
        // it as "nothing to build" rather than a failure.
    }

    /**
     * Maps a path's vertices to snap-cell node ids, collapsing consecutive
     * duplicates; returns the surviving ids or an empty array below the
     * two-vertex minimum.
     */
    private fun snapPath(path: List<GeoPoint>): LongArray {
        val nodes = MutableLongList(path.size)
        var last_nid = -1L
        for (point in path) {
            val nid = ensureNode(point.lon, point.lat)
            if (nid == last_nid) continue
            nodes.add(nid)
            last_nid = nid
        }
        val snapped = LongArray(nodes.size)
        for (i in 0 until nodes.size) snapped[i] = nodes.get(i)
        return snapped
    }

    // ---- node synthesis ----

    /**
     * Returns the synthetic nid for a vertex, assigning one (and recording
     * its grid-aligned position) the first time its snap cell is seen.
     */
    private fun ensureNode(lon: Double, lat: Double): Long {
        val ilon = snapE6(lon, LON_SNAP_OFFSET, SNAP_CELLS_PER_DEGREE)
        val ilat = snapE6(lat, LAT_SNAP_OFFSET, SNAP_CELLS_PER_DEGREE)
        val cell = packCell(ilon, ilat)

        val cell_to_nid = snapCellToNid!!
        val known = cell_to_nid.getOrDefault(cell, -1L)
        if (known != -1L) return known

        val nid = ++next_nid
        cell_to_nid[cell] = nid
        nidPos!![nid] = packPosition(ilon, ilat)
        return nid
    }

    /**
     * Snaps a degree coordinate onto the 1e-5-degree grid in BeeRouter's
     * integer space: `floor((deg + offset) * 1e5) * 10` gives the same E6
     * value `Position(lon, lat)` would produce for any degree value inside
     * the cell, so node positions are exactly grid-aligned.
     */
    private fun snapE6(deg: Double, offset: Double, cells: Int): Int {
        val cell = ((deg + offset) * cells).toInt() // floor: deg+offset >= 0
        return cell * E6_PER_SNAP_CELL
    }

    private fun packCell(ilon: Int, ilat: Int): Long =
        (ilon.toLong() shl LAT_CELL_BITS) or ilat.toLong()

    private fun packPosition(ilon: Int, ilat: Int): Long =
        (ilon.toLong() shl 32) or ilat.toLong()

    /** OsmCutter.getTileIndex: 45x30-degree global grid, -1 when out of range. */
    private fun tileIndexFor(position: Position): Int {
        val lon = position.longitude / LON_45_E6
        val lat = position.latitude / LAT_30_E6
        if (lon !in 0..7 || lat !in 0..5) {
            return -1
        }
        return lon * 6 + lat
    }

    // ---- fragment endpoint merge ----

    /**
     * Unifies fragment endpoints that the MVT tile clipping separated and
     * returns the resulting remap: nid -> canonical nid, holding an entry
     * only for nodes that were merged away (never for the surviving root).
     */
    private fun mergeFragmentEndpoints(zoom: Int): MutableLongLongMap {
        val nid_pos = nidPos!! // live from scan end to release()
        val tile_e6 = E6_PER_DEGREE * DEGREES / (1L shl zoom)
        val same_wid_limit = tile_e6 / SAME_WID_DIVISOR
        val cross_wid_limit = tile_e6 / CROSS_WID_DIVISOR

        // Collect the endpoints: first and last node of every fragment (a
        // closed one-fragment loop contributes its shared node once).
        val ep_nid = LongArray(fragments.size * 2)
        val ep_pos = LongArray(fragments.size * 2)
        val ep_wid = LongArray(fragments.size * 2)
        val ep_frag = IntArray(fragments.size * 2)
        var endpoint_count = 0
        for (index in fragments.indices) {
            val fragment = fragments[index]
            val first = fragment.nodes.first()
            val last = fragment.nodes.last()
            ep_nid[endpoint_count] = first
            ep_pos[endpoint_count] = nid_pos[first]
            ep_wid[endpoint_count] = fragment.wid
            ep_frag[endpoint_count] = index
            endpoint_count++
            if (last != first) {
                ep_nid[endpoint_count] = last
                ep_pos[endpoint_count] = nid_pos[last]
                ep_wid[endpoint_count] = fragment.wid
                ep_frag[endpoint_count] = index
                endpoint_count++
            }
        }
        if (endpoint_count < 2) return MutableLongLongMap()

        // Bucket the endpoints into a grid of same-wid-limit-sized cells
        // (counting sort over primitive arrays — no boxed collections at
        // this scale), so each endpoint only compares against its 3x3
        // neighborhood.
        val grid = same_wid_limit
        val keys = LongArray(endpoint_count)
        val cell_to_slot = MutableLongLongMap()
        var slot_count = 0
        for (i in 0 until endpoint_count) {
            keys[i] = cellKey(ep_pos[i], grid)
            if (cell_to_slot.getOrDefault(keys[i], -1L) == -1L) {
                cell_to_slot[keys[i]] = (++slot_count).toLong()
            }
        }
        val slot_starts = IntArray(slot_count + 2)
        for (i in 0 until endpoint_count) {
            slot_starts[cell_to_slot[keys[i]].toInt() + 1]++
        }
        for (slot in 1..slot_count + 1) {
            slot_starts[slot] += slot_starts[slot - 1]
        }
        val fill = slot_starts.copyOf()
        val members = IntArray(endpoint_count)
        for (i in 0 until endpoint_count) {
            members[fill[cell_to_slot[keys[i]].toInt()]++] = i
        }

        val parent = MutableLongLongMap()
        for (i in 0 until endpoint_count) {
            val pos_i = ep_pos[i]
            val lon_i = (pos_i shr 32)
            val lat_i = (pos_i and 0xFFFFFFFFL)
            val cell_lon = lon_i / grid
            val cell_lat = lat_i / grid
            for (dlon in -1..1) {
                for (dlat in -1..1) {
                    val slot = cell_to_slot.getOrDefault(
                        (cell_lon + dlon shl 32) or (cell_lat + dlat),
                        -1L,
                    )
                    if (slot == -1L) continue
                    var m = slot_starts[slot.toInt()]
                    val m_end = slot_starts[slot.toInt() + 1]
                    while (m < m_end) {
                        val j = members[m]
                        m++
                        if (j <= i) continue // visit each pair once
                        if (ep_frag[j] == ep_frag[i]) continue // one fragment's own ends stay apart
                        val pos_j = ep_pos[j]
                        val dlon_e6 = lon_i - (pos_j shr 32)
                        val dlat_e6 = lat_i - (pos_j and 0xFFFFFFFFL)
                        val d2 = dlon_e6 * dlon_e6 + dlat_e6 * dlat_e6
                        val limit = if (ep_wid[j] == ep_wid[i]) same_wid_limit else cross_wid_limit
                        if (d2 < limit * limit) {
                            union(parent, ep_nid[i], ep_nid[j])
                        }
                    }
                }
            }
        }

        // Remap every merged-away endpoint to its chain's root.
        stitchDanglingEndpoints(
            parent, ep_nid, ep_pos, ep_wid, endpoint_count, cross_wid_limit,
        )
        // Remap every merged-away node to its chain's root. This must walk
        // the union-find's KEYS, not just the endpoint list: a vertex
        // interior to a foreign way becomes a non-root the moment a later
        // union chains past it, and leaving its raw id in that way's node
        // list would silently drop the repair that union was making.
        val canonical = MutableLongLongMap()
        parent.forEach { nid, _ ->
            val root = find(parent, nid)
            if (root != nid) canonical[nid] = root
        }
        return canonical
    }

    /**
     * Re-links endpoints the two merge rules above cannot reach: Planetiler
     * simplifies each way independently, so a junction vertex that is
     * collinear on the through-way is dropped there while the ending way
     * keeps it — the endpoint then dangles on a foreign segment with no
     * shared snap cell in sight (measured at ~60% of all Melbourne CBD
     * endpoints: every side street meeting a straight arterial).
     *
     * For each endpoint within `cross_wid_limit` of another fragment's
     * segment (same-wid included: Planetiler's per-tile merge groups alias
     * one constituent's id onto different physical linework in different
     * tiles, so wid equality proves nothing):
     * - a near VERTEX is unified with it (a junction both ways kept, but
     *   quantized into different snap cells — the cell-boundary case the
     *   endpoint-endpoint pass cannot see because the vertex is interior);
     * - a near segment INTERIOR gets the endpoint's node id inserted into
     *   that segment's node list at its projected position, restoring the
     *   junction node the simplifier dropped.
     *
     * Only endpoints participate: an interior vertex within 2.4 m of a
     * foreign segment is almost always a parallel carriageway, service road
     * or slip lane, and inserting those would cross-connect separate roads.
     * The residual case — both ways dropping the same junction vertex —
     * leaves no endpoint to repair from and stays unlinked.
     */
    private fun stitchDanglingEndpoints(
        parent: MutableLongLongMap,
        ep_nid: LongArray,
        ep_pos: LongArray,
        ep_wid: LongArray,
        endpoint_count: Int,
        limit: Long,
    ) {
        val nid_pos = nidPos!! // live from scan end to release()
        val limit2 = limit.toDouble() * limit
        val grid = maxOf(SEG_INDEX_GRID_E6, limit * 2)
        val lon_shift = 32
        dbg("stitch start: endpoints=$endpoint_count grid=$grid limit=$limit heap=${heapStr()}")

        // ---- segment index: counting sort of every fragment's segments
        // into the grid cells the segment traverses (DDA walk; cells are
        // ~2x the stitch threshold so a 3x3 lookup sees every candidate) ----
        var segment_count = 0
        for (fragment in fragments) segment_count += fragment.nodes.size - 1
        if (segment_count == 0) return
        val seg_frag = IntArray(segment_count)
        val seg_k = IntArray(segment_count)

        val cell_to_slot = MutableLongLongMap()
        var slot_counts = IntArray(1024)
        var slot_total = 0
        var entry_count = 0
        var seg_id = 0
        for (frag_index in fragments.indices) {
            val nodes = fragments[frag_index].nodes
            for (k in 0 until nodes.size - 1) {
                seg_frag[seg_id] = frag_index
                seg_k[seg_id] = k
                seg_id++
                forEachSegmentCell(nid_pos[nodes[k]], nid_pos[nodes[k + 1]], grid) { cell ->
                    var slot = cell_to_slot.getOrDefault(cell, -1L)
                    if (slot == -1L) {
                        slot_total++
                        if (slot_total > slot_counts.size) {
                            slot_counts = slot_counts.copyOf(slot_counts.size * 2)
                        }
                        slot = slot_total.toLong()
                        cell_to_slot[cell] = slot
                    }
                    slot_counts[slot.toInt() - 1]++
                    entry_count++
                }
            }
        }

        val slot_starts = IntArray(slot_total + 1)
        for (slot in 0 until slot_total) {
            slot_starts[slot + 1] = slot_starts[slot] + slot_counts[slot]
        }
        dbg("stitch index built: segments=$segment_count cells=$slot_total entries=$entry_count heap=${heapStr()}")
        val fill = slot_starts.copyOf()
        val members = IntArray(entry_count)
        seg_id = 0
        for (frag_index in fragments.indices) {
            val nodes = fragments[frag_index].nodes
            for (k in 0 until nodes.size - 1) {
                forEachSegmentCell(nid_pos[nodes[k]], nid_pos[nodes[k + 1]], grid) { cell ->
                    members[fill[cell_to_slot[cell].toInt() - 1]++] = seg_id
                }
                seg_id++
            }
        }

        // ---- scan: every endpoint vs the foreign segments in its 3x3 ----
        // A segment longer than the grid crosses several of the endpoint's
        // nine cells; an epoch-marked visited array keeps each
        // (endpoint, segment) pair to exactly one pass.
        val visited = IntArray(segment_count)
        var epoch = 0
        val t0 = System.currentTimeMillis()
        var pairs_checked = 0L
        var unions_done = 0
        var max_cell_members = 0
        // Fragments this endpoint has already been spliced into (reset per
        // endpoint): a sharp wedge in the foreign way can leave the endpoint
        // within the limit of two adjacent segment INTERIORS at once, and
        // splicing twice would leave the same junction node twice, one
        // non-consecutive, in one emitted way.
        var frag_inserted = IntArray(16)
        var frag_inserted_count = 0
        val insertions = ArrayList<Insertion>(1024)
        for (i in 0 until endpoint_count) {
            epoch++
            val nid = ep_nid[i]
            val pos = ep_pos[i]
            val wid = ep_wid[i]
            val lon = pos shr lon_shift
            val lat = pos and 0xFFFFFFFFL
            val cell_lon = lon / grid
            val cell_lat = lat / grid
            frag_inserted_count = 0
            for (dlon in -1..1) {
                for (dlat in -1..1) {
                    val slot = cell_to_slot.getOrDefault(
                        (cell_lon + dlon shl lon_shift) or (cell_lat + dlat),
                        -1L,
                    )
                    if (slot == -1L) continue
                    var m = slot_starts[slot.toInt() - 1]
                    val m_end = slot_starts[slot.toInt()]
                    if (m_end - m > max_cell_members) max_cell_members = m_end - m
                    while (m < m_end) {
                        val hit = members[m]
                        m++
                        if (visited[hit] == epoch) continue
                        visited[hit] = epoch
                        pairs_checked++
                        val frag_index = seg_frag[hit]
                        val fragment = fragments[frag_index]
                        // Deliberately NO same-wid skip here. Planetiler's
                        // OMT profile merges contiguous same-attribute ways
                        // into one feature PER TILE, labeling the group with
                        // one constituent's id — the groups form
                        // independently per tile, so the same feature id
                        // labels DIFFERENT physical linework in neighboring
                        // tiles (measured: id 288000183×10+0 covers one
                        // carriageway of the divided Geelong Rd plus side
                        // legs in one tile, the other carriageway in the
                        // next). A same-wid endpoint can therefore dangle on
                        // a genuinely foreign segment, and skipping it cut
                        // the whole car graph in two. Genuinely-own
                        // fragments are protected by the shares_root check
                        // below instead: same-way pieces whose border
                        // endpoints the 19m pass already unioned share a
                        // union-find root, so the insertion never fires.
                        val nodes = fragment.nodes
                        val k = seg_k[hit]
                        val a = nodes[k]
                        val b = nodes[k + 1]
                        if (a == nid || b == nid) continue // junction already shared

                        val pa = nid_pos[a]
                        val pb = nid_pos[b]
                        val d2a = dist2To(lon, lat, pa)
                        if (d2a < limit2) {
                            union(parent, nid, a)
                            unions_done++
                            continue
                        }
                        val d2b = dist2To(lon, lat, pb)
                        if (d2b < limit2) {
                            union(parent, nid, b)
                            unions_done++
                            continue
                        }

                        // interior: projected distance and position along
                        val ax = (pa shr lon_shift).toDouble()
                        val ay = (pa and 0xFFFFFFFFL).toDouble()
                        val bx = (pb shr lon_shift).toDouble()
                        val by = (pb and 0xFFFFFFFFL).toDouble()
                        val dx = bx - ax
                        val dy = by - ay
                        val len2 = dx * dx + dy * dy
                        val t = if (len2 == 0.0) {
                            0.0
                        } else {
                            (((lon - ax) * dx + (lat - ay) * dy) / len2).coerceIn(0.0, 1.0)
                        }
                        val ex = ax + t * dx - lon
                        val ey = ay + t * dy - lat
                        if (ex * ex + ey * ey < limit2 &&
                            !isSplicedInto(frag_inserted, frag_inserted_count, frag_index)
                        ) {
                            // Canonical, not raw, identity: the endpoint may
                            // be union'd (directly or via a chain) with a node
                            // the fragment already contains — inserting a
                            // second copy of that root would make the way
                            // double back through the junction node.
                            val root = find(parent, nid)
                            var shares_root = false
                            for (x in nodes) {
                                if (find(parent, x) == root) {
                                    shares_root = true
                                    break
                                }
                            }
                            if (!shares_root) {
                                insertions.add(Insertion(frag_index, k, t, nid))
                                if (frag_inserted_count == frag_inserted.size) {
                                    frag_inserted = frag_inserted.copyOf(frag_inserted.size * 2)
                                }
                                frag_inserted[frag_inserted_count++] = frag_index
                            }
                        }
                    }
                }
            }
            if ((i + 1) % STITCH_PROGRESS_EVERY == 0) {
                dbg(
                    "stitch: ${i + 1}/$endpoint_count endpoints, " +
                        "pairs=$pairs_checked, unions=$unions_done, " +
                        "insertions=${insertions.size}, cells=$slot_total, " +
                        "entries=$entry_count, " +
                        "elapsed=${System.currentTimeMillis() - t0}ms",
                )
            }
        }
        dbg(
            "stitch stats: endpoints=$endpoint_count segments=$segment_count " +
                "entries=$entry_count cells=$slot_total pairs=$pairs_checked " +
                "unions=$unions_done insertions=${insertions.size} " +
                "max_cell_members=$max_cell_members " +
                "elapsed=${System.currentTimeMillis() - t0}ms",
        )

        if (insertions.isEmpty()) return
        junctions_inserted += insertions.size
        insertions.sortWith(compareBy({ it.fragIndex }, { it.segIndex }, { it.t }))
        var idx = 0
        while (idx < insertions.size) {
            val frag_index = insertions[idx].fragIndex
            var end = idx
            while (end < insertions.size && insertions[end].fragIndex == frag_index) end++
            applyInsertions(frag_index, insertions.subList(idx, end))
            idx = end
        }
    }

    /** One junction node to splice into a fragment's node list. */
    private class Insertion(
        val fragIndex: Int,
        val segIndex: Int, // node list index the insertion follows
        val t: Double, // position along that segment, for ordering
        val nid: Long,
    )

    /**
     * Rebuilds one fragment's node list with its insertions spliced in —
     * each insertion follows node index `segIndex`, ordered by `t` along
     * the segment so the way keeps traversing its geometry in order.
     */
    private fun applyInsertions(fragIndex: Int, ins: List<Insertion>) {
        val fragment = fragments[fragIndex]
        val old = fragment.nodes
        val out = LongArray(old.size + ins.size)
        var w = 0
        var r = 0
        for (item in ins) {
            while (r <= item.segIndex) out[w++] = old[r++]
            out[w++] = item.nid
        }
        while (r < old.size) out[w++] = old[r++]
        fragment.nodes = out
    }

    /** True when this endpoint was already spliced into that fragment. */
    private fun isSplicedInto(frag_inserted: IntArray, count: Int, frag_index: Int): Boolean {
        for (j in 0 until count) {
            if (frag_inserted[j] == frag_index) return true
        }
        return false
    }

    /** Squared E6 distance between an endpoint and another node's position. */
    private fun dist2To(lon: Long, lat: Long, packed: Long): Double {
        val dlon = lon - (packed shr 32)
        val dlat = lat - (packed and 0xFFFFFFFFL)
        return dlon.toDouble() * dlon + dlat.toDouble() * dlat
    }

    /**
     * Visits every grid cell the segment a->b passes THROUGH (Amanatides &
     * Woo grid traversal), not every cell its bounding box touches: a long
     * diagonal way would otherwise register quadratically many cells (a
     * 10 km diagonal = ~330 bbox cells at 550 m) and swamp both the index build
     * and every endpoint's 3x3 scan.
     *
     * All coordinates are offset-positive E6, so floor division is plain
     * division. Any segment within `grid/2` of a point visits a cell in
     * that point's 3x3 neighborhood, which is what the endpoint scan
     * relies on.
     */
    internal inline fun forEachSegmentCell(
        pa: Long,
        pb: Long,
        grid: Long,
        action: (Long) -> Unit,
    ) {
        val lon_mask = 0xFFFFFFFFL
        val ax = pa shr 32
        val ay = pa and lon_mask
        val bx = pb shr 32
        val by = pb and lon_mask
        var cx = ax / grid
        var cy = ay / grid
        val cx_end = bx / grid
        val cy_end = by / grid
        action(cx shl 32 or cy)
        if (cx == cx_end && cy == cy_end) return

        // Boundary crossings remaining per axis; the loop ends when both
        // hit zero, so a walk can never run past its end cell. (The float
        // t_max version this replaces terminated on end-cell EQUALITY and
        // accumulated rounding error on long walks: one step past the end
        // cell meant the axis never matched again and walked to infinity,
        // emitting unbounded cells — a single runaway segment OOMed the
        // whole build at 4 GB.)
        var steps_x = Math.abs(cx_end - cx)
        var steps_y = Math.abs(cy_end - cy)
        val step_cx = if (bx >= ax) 1L else -1L
        val step_cy = if (by >= ay) 1L else -1L
        // Distance in E6 from a to the next boundary on each axis. Taking
        // the x boundary when bx_next / dx_span <= by_next / dy_span,
        // cross-multiplied, keeps the decision exact in integers. For legal
        // offset-positive E6 the products stay below ~7e16, far under
        // Long.MAX; only decoder-garbage bit patterns can wrap, and even
        // there the step counters above still bound the walk to at most
        // steps_x + steps_y iterations (no hang, no OOM recurrence) — the
        // wrapped comparison can only mis-order cell visits on positions
        // that are already invalid.
        var bx_next = if (step_cx > 0) (cx + 1) * grid - ax else ax - cx * grid
        var by_next = if (step_cy > 0) (cy + 1) * grid - ay else ay - cy * grid
        val dx_span = Math.abs(bx - ax)
        val dy_span = Math.abs(by - ay)
        while (steps_x > 0 || steps_y > 0) {
            val take_x = steps_y == 0L ||
                (steps_x > 0 && bx_next * dy_span <= by_next * dx_span)
            if (take_x) {
                cx += step_cx
                steps_x--
                bx_next += grid
            } else {
                cy += step_cy
                steps_y--
                by_next += grid
            }
            action(cx shl 32 or cy)
        }
    }

    private fun cellKey(packed: Long, grid: Long): Long =
        ((packed shr 32) / grid shl 32) or ((packed and 0xFFFFFFFFL) / grid)

    /**
     * Phase instrumentation for the dev-machine pipeline diagnostics. The
     * Gradle test worker buffers stdout per test method — and an OOM loses
     * the buffer entirely — so this also appends to a log file when one is
     * writable. `/tmp` does not exist on Android, which makes this a no-op
     * there (and runCatching swallows any other unwritable-path case).
     */
    private fun dbg(line: String) {
        println(line)
        runCatching { File("/tmp/atlas-cutter-dbg.log").appendText(line + "\n") }
    }

    private fun heapStr(): String {
        val runtime = Runtime.getRuntime()
        return "${(runtime.totalMemory() - runtime.freeMemory()) shr 20}M/${runtime.maxMemory() shr 20}M"
    }

    // Union-find over primitive longs (path-halving find, no bloat on read).

    private fun find(parent: MutableLongLongMap, x: Long): Long {
        var r = x
        while (true) {
            val p = parent.getOrDefault(r, -1L)
            if (p == -1L || p == r) return r
            parent[r] = parent.getOrDefault(p, p) // halve
            r = p
        }
    }

    private fun union(parent: MutableLongLongMap, a: Long, b: Long) {
        val ra = find(parent, a)
        val rb = find(parent, b)
        if (ra != rb) parent[ra] = rb
    }

    // ---- emission ----

    /** Writes one surviving node's `.ntl` record and feeds `WayCutter`. */
    private fun writeNode(nid: Long) {
        val packed = nidPos!![nid]
        val ilon = (packed shr 32).toInt()
        val ilat = (packed and 0xFFFFFFFFL).toInt()
        val data = NodeData(
            nid,
            ilon / 1_000_000.0 - LON_SNAP_OFFSET,
            ilat / 1_000_000.0 - LAT_SNAP_OFFSET,
        )
        data.position = Position(ilon, ilat)
        val tile_index = tileIndexFor(data.position)
        if (tile_index >= 0) {
            data.writeTo(getOutStreamForTile(tile_index))
            wayCutter?.nextNode(data)
        }
    }

    /**
     * Emits one fragment as a `WayData`: node ids rewritten through the
     * canonical map (a merge can make two consecutive ids equal — collapse
     * them), exactly as OsmCutter.nextWay does for accepted ways.
     */
    private fun emitFragment(fragment: Fragment, canonical: MutableLongLongMap) {
        val nodes = MutableLongList(fragment.nodes.size)
        var last_nid = -1L
        for (raw in fragment.nodes) {
            val nid = canonical.getOrDefault(raw, -1L).let { if (it == -1L) raw else it }
            if (nid == last_nid) continue
            nodes.add(nid)
            last_nid = nid
        }
        if (nodes.size < MIN_PATH_VERTICES) return

        val data = WayData(fragment.wid, nodes)
        data.description = fragment.description
        wayCutter?.nextWay(data)
        nodeFilter?.nextWay(data)
    }

    /**
     * OsmCutter.nextWay's acceptance filter: encode the tags through the way
     * expression context, then keep the way only if the profile's costfactor
     * is below 10000 in the forward OR reverse direction.
     */
    private fun acceptedDescription(tags: Map<String, String>): ByteArray? {
        val lookup_data = expctxWay.createNewLookupData()!!
        for (key in tags.keys) {
            expctxWay.addLookupValue(key, tags.getValue(key).replace(' ', '_'), lookup_data)
        }
        val description = expctxWay.encode(lookup_data) ?: return null
        expctxWay.evaluate(false, description)
        var ok = expctxWay.costfactor < COSTFACTOR_LIMIT
        expctxWay.evaluate(true, description)
        ok = ok || expctxWay.costfactor < COSTFACTOR_LIMIT
        if (!ok) return null
        return description
    }

    /** 45-degree node-tile names, identical to OsmCutter's (E135_S30.ntl). */
    public override fun getNameForTile(tileIndex: Int): String {
        val lon = (tileIndex / 6) * 45 - 180
        val lat = (tileIndex % 6) * 30 - 90
        val slon = if (lon < 0) "W${-lon}" else "E$lon"
        val slat = if (lat < 0) "S${-lat}" else "N$lat"
        return "${slon}_${slat}.ntl"
    }

    private companion object {
        private const val TRANSPORTATION_LAYER = "transportation"
        private const val MIN_PATH_VERTICES = 2

        /**
         * Ways with an id outside this range fall back to a counter. The
         * vendored `WayData.writeId` stores `(id shr 5)` in a signed Int, so
         * ids >= 2^36 do not round-trip through `readId`.
         */
        private const val MAX_WAY_ID = 1L shl 36

        /** Costfactor at or above this makes a way unroutable (all.brf). */
        private const val COSTFACTOR_LIMIT = 10000f

        // Snap grid: 1e-5-degree cells -> E6 offset integers. ilat spans
        // 0..180M, which needs 28 bits — packing with fewer aliases cells.
        private const val SNAP_CELLS_PER_DEGREE = 100_000
        private const val LON_SNAP_OFFSET = 180.0
        private const val LAT_SNAP_OFFSET = 90.0
        private const val E6_PER_SNAP_CELL = 10
        private const val LAT_CELL_BITS = 28

        private const val E6_PER_DEGREE = 1_000_000L
        private const val DEGREES = 360L

        /**
         * Same-way endpoint merge threshold, as a divisor of the tile width
         * in E6 units: MVT clips at `buffer/extent` of a tile per side, and
         * the two clip lines sit `2*buffer/extent` of a tile apart —
         * Planetiler's default buffer 4/4096 puts them tile/512 apart, but
         * producers using buffer 8 or 16 push them to tile/256 or tile/128.
         * tile/128 (~19 m at z14) covers buffer sizes through 16 with
         * headroom for vertex quantization; same-way fragments this close
         * are clip pairs, not geometry (a way's clipped ends meeting again
         * within 19 m of a loop's closure is the rare and harmless cost).
         */
        private const val SAME_WID_DIVISOR = 128L

        /**
         * Cross-way endpoint unification threshold: two ways crossing inside
         * the clip band cut the same clip line within quantization distance
         * of each other (~2.4 m at z14). Doubles as the junction-repair
         * threshold in [stitchDanglingEndpoints].
         */
        private const val CROSS_WID_DIVISOR = 1024L

        /**
         * Cell size of the stitch segment index, in E6 units. 5,000 E6 =
         * 0.005 degrees = ~555 m of latitude (equator) / ~440 m of
         * longitude at Melbourne. This is a MEMORY budget, not a precision
         * choice: the index holds one map entry per distinct cell a segment
         * passes through, and OSM-level linework (every footway and track)
         * runs ~200,000 km in a metro bucket — at ~7 m cells that is ~30M
         * cells, and the cell map's resize transients alone exceed a 4 GB
         * heap (measured: OOM at 4g; at ~5.5 km cells it was merely slow,
         * 18e9 pairs for the first 250k endpoints). ~550 m keeps the cell
         * map in the hundreds of thousands of entries while the 3x3
         * lookup still sees every candidate (any cell >= the stitch
         * threshold preserves that guarantee) and the per-endpoint scan
         * stays tens of segments.
         */
        private const val SEG_INDEX_GRID_E6 = 5_000L
        private const val STITCH_PROGRESS_EVERY = 250_000

        private const val LON_45_E6 = 45_000_000
        private const val LAT_30_E6 = 30_000_000
    }
}