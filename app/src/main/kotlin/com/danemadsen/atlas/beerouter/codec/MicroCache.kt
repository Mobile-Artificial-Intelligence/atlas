package com.danemadsen.atlas.beerouter.codec

import com.danemadsen.atlas.beerouter.geo.Position

import com.danemadsen.atlas.beerouter.util.ByteDataWriter

/**
 * a micro-cache is a data cache for an area of some square kilometers or some
 * hundreds or thousands nodes
 *
 * This is the basic io-unit: always a full microcache is loaded from the
 * data-file if a node is requested at a position not yet covered by the caches
 * already loaded
 *
 * The nodes are represented in a compact way (typical 20-50 bytes per node),
 * but in a way that they do not depend on each other, and garbage collection is
 * supported to remove the nodes already consumed from the cache.
 *
 * The cache-internal data representation is different from that in the
 * data-files, where a cache is encoded as a whole, allowing more
 * redundancy-removal for a more compact encoding
 */
public open class MicroCache protected constructor(ab: ByteArray = ByteArray(0)) : ByteDataWriter(ab) {
    protected var faid: IntArray = IntArray(0)
    protected var fapos: IntArray = IntArray(0)
    public var size: Int = 0
        protected set

    private var delcount = 0
    private var delbytes = 0
    private var p2size = 0 // next power of 2 of size

    // cache control: a virgin cache can be
    // put to ghost state for later recovery
    public var virgin: Boolean = true
    public var ghost: Boolean = false

    protected fun init(size: Int) {
        this.size = size
        delcount = 0
        delbytes = 0
        p2size = 0x40000000
        while (p2size > size) p2size = p2size shr 1
    }

    /**
     * Finish a node and add it to the cache.
     *
     * @param id the node id
     * @throws UnsupportedOperationException if the id cannot be shrunk (base implementation)
     */
    public fun finishNode(id: Long) {
        fapos[size] = aboffset
        faid[size] = shrinkId(id)
        size++
    }

    /**
     * Discard the current node by resetting the offset.
     */
    public fun discardNode() {
        aboffset = startPos(size)
    }

    public val dataSize: Int
        get() = ab.size

    /**
     * Set the internal reader (aboffset, aboffsetEnd) to the body data for the given id
     *
     *
     * If a node is not found in an empty cache, this is usually an edge-effect
     * (data-file does not exist or neighboured data-files of differnt age),
     * but is can as well be a symptom of a node-identity breaking bug.
     *
     *
     * Current implementation always returns false for not-found, however, for
     * regression testing, at least for the case that is most likely a bug
     * (node found but marked as deleted = ready for garbage collection
     * = already consumed) the RunException should be re-enabled
     *
     * @return true if id was found
     * @throws UnsupportedOperationException if id cannot be shrunk (base implementation)
     */
    public fun getAndClear(id64: Long): Boolean {
        if (size == 0) {
            return false
        }
        val id = shrinkId(id64)
        val a = faid
        var offset = p2size
        var n = 0

        while (offset > 0) {
            val nn = n + offset
            if (nn < size && a[nn] <= id) {
                n = nn
            }
            offset = offset shr 1
        }
        if (a[n] == id) {
            if ((fapos[n] and -0x80000000) == 0) {
                aboffset = startPos(n)
                aboffsetEnd = fapos[n]
                fapos[n] = fapos[n] or -0x80000000 // mark deleted
                delbytes += aboffsetEnd - aboffset
                delcount++
                return true
            } else  // .. marked as deleted
            {
                // throw new RuntimeException( "MicroCache: node already consumed: id=" + id );
            }
        }
        return false
    }

    protected fun startPos(n: Int): Int {
        return if (n > 0) fapos[n - 1] and 0x7fffffff else 0
    }

    /**
     * Collect garbage from the cache, removing deleted nodes.
     *
     * @param threshold the maximum number of deleted nodes allowed before collection
     * @return the number of bytes deleted
     */
    public fun collect(threshold: Int): Int {
        if (delcount <= threshold) {
            return 0
        }

        virgin = false

        val nsize = size - delcount
        if (nsize == 0) {
            faid = IntArray(0)
            fapos = IntArray(0)
        } else {
            val nfaid = IntArray(nsize)
            val nfapos = IntArray(nsize)
            var idx = 0

            val nab = ByteArray(ab.size - delbytes)
            var nabOff = 0
            for (i in 0..<size) {
                val pos = fapos[i]
                if ((pos and -0x80000000) == 0) {
                    val start = startPos(i)
                    val end = fapos[i]
                    val len = end - start
                    ab.copyInto(nab, nabOff, start, start + len)
                    nfaid[idx] = faid[i]
                    nabOff += len
                    nfapos[idx] = nabOff
                    idx++
                }
            }
            faid = nfaid
            fapos = nfapos
            ab = nab
        }
        val deleted = delbytes
        init(nsize)
        return deleted
    }

    /**
     * Un-ghost the cache, clearing deleted flags.
     */
    public fun unGhost() {
        ghost = false
        delcount = 0
        delbytes = 0
        for (i in 0..<size) {
            fapos[i] = fapos[i] and 0x7fffffff // clear deleted flags
        }
    }

    /**
     * @return the 64-bit global id for the given cache-position
     * @throws UnsupportedOperationException if the id cannot be expanded (base implementation)
     */
    public fun getIdForIndex(i: Int): Long {
        val id32 = faid[i]
        return expandId(id32)
    }

    /**
     * expand a 32-bit micro-cache-internal id into a 64-bit (lon|lat) global-id
     *
     * @see .shrinkId
     * @throws UnsupportedOperationException base implementation always throws
     */
    public open fun expandId(id32: Int): Long {
        throw UnsupportedOperationException("expandId for empty cache")
    }

    /**
     * shrink a 64-bit (lon|lat) global-id into a a 32-bit micro-cache-internal id
     *
     * @see .expandId
     * @throws UnsupportedOperationException base implementation always throws
     */
    public open fun shrinkId(id64: Long): Int {
        throw UnsupportedOperationException("shrinkId for empty cache")
    }

    /**
     * @return true if the given lon/lat position is internal for that micro-cache
     * @throws UnsupportedOperationException base implementation always throws
     */
    public open fun isInternal(position: Position): Boolean {
        throw UnsupportedOperationException("isInternal for empty cache")
    }

    /**
     * LOCAL PATCH (Atlas): int-based variant of [isInternal] — the generator's
     * hot reverse-link path checks millions of nodes and must not allocate a
     * transient [Position] per check (see the WayLinker memory surgery).
     */
    public open fun isInternal(longitude: Int, latitude: Int): Boolean {
        throw UnsupportedOperationException("isInternal for empty cache")
    }

    /**
     * (stasticially) encode the micro-cache into the format used in the datafiles
     *
     * @param buffer byte array to encode into (considered big enough)
     * @return the size of the encoded data
     * @throws UnsupportedOperationException base implementation always throws
     */
    public open fun encodeMicroCache(buffer: ByteArray): Int {
        throw UnsupportedOperationException("encodeMicroCache for empty cache")
    }

    /**
     * Compare the content of this microcache to another
     *
     * @return null if equals, else a diff-report
     * @throws IndexOutOfBoundsException if internal arrays are accessed out of bounds
     */
    public fun compareWith(mc: MicroCache): String? {
        val msg = _compareWith(mc)
        if (msg != null) {
            val sb = StringBuilder(msg)
            sb.append("\nencode cache:\n").append(summary())
            sb.append("\ndecode cache:\n").append(mc.summary())
            return sb.toString()
        }
        return null
    }

    private fun summary(): String {
        val sb = StringBuilder("size=$size aboffset=$aboffset")
        for (i in 0..<size) {
            sb.append("\nidx=" + i + " faid=" + faid[i] + " fapos=" + fapos[i])
        }
        return sb.toString()
    }

    private fun _compareWith(mc: MicroCache): String? {
        if (size != mc.size) {
            return "size mismatch: " + size + "->" + mc.size
        }
        for (i in 0..<size) {
            if (faid[i] != mc.faid[i]) {
                return "faid mismatch at index " + i + ":" + faid[i] + "->" + mc.faid[i]
            }
            val start = if (i > 0) fapos[i - 1] else 0
            val end = if (fapos[i] < mc.fapos[i]) fapos[i] else mc.fapos[i]
            val len = end - start
            for (offset in 0..<len) {
                if (mc.ab.size <= start + offset) {
                    return "data buffer too small"
                }
                if (ab[start + offset] != mc.ab[start + offset]) {
                    return "data mismatch at index $i offset=$offset"
                }
            }
            if (fapos[i] != mc.fapos[i]) {
                return "fapos mismatch at index " + i + ":" + fapos[i] + "->" + mc.fapos[i]
            }
        }
        if (aboffset != mc.aboffset) {
            return "datasize mismatch: " + aboffset + "->" + mc.aboffset
        }
        return null
    }

    /**
     * Build a delta between two micro-caches.
     *
     * @param mc1 the first micro-cache
     * @param mc2 the second micro-cache
     */
    public fun buildDelta(mc1: MicroCache, mc2: MicroCache) {
        var idx1 = 0
        var idx2 = 0

        while (idx1 < mc1.size || idx2 < mc2.size) {
            val id1 = if (idx1 < mc1.size) mc1.faid[idx1] else Int.MAX_VALUE
            val id2 = if (idx2 < mc2.size) mc2.faid[idx2] else Int.MAX_VALUE
            val id: Int
            if (id1 >= id2) {
                id = id2
                val start2 = if (idx2 > 0) mc2.fapos[idx2 - 1] else 0
                val len2 = mc2.fapos[idx2++] - start2

                if (id1 == id2) {
                    // id exists in both caches, compare data
                    val start1 = if (idx1 > 0) mc1.fapos[idx1 - 1] else 0
                    val len1 = mc1.fapos[idx1++] - start1
                    if (len1 == len2) {
                        var i = 0
                        while (i < len1) {
                            if (mc1.ab[start1 + i] != mc2.ab[start2 + i]) {
                                break
                            }
                            i++
                        }
                        if (i == len1) {
                            continue  // same data -> do nothing
                        }
                    }
                }
                write(mc2.ab, start2, len2)
            } else {
                idx1++
                id = id1 // deleted node
            }
            fapos[size] = aboffset
            faid[size] = id
            size++
        }
    }

    /**
     * Add a delta to this micro-cache.
     *
     * @param mc1 the base micro-cache
     * @param mc2 the delta micro-cache
     * @param keepEmptyNodes whether to keep empty nodes
     */
    public fun addDelta(mc1: MicroCache, mc2: MicroCache, keepEmptyNodes: Boolean) {
        var idx1 = 0
        var idx2 = 0

        while (idx1 < mc1.size || idx2 < mc2.size) {
            val id1 = if (idx1 < mc1.size) mc1.faid[idx1] else Int.MAX_VALUE
            val id2 = if (idx2 < mc2.size) mc2.faid[idx2] else Int.MAX_VALUE
            if (id1 >= id2) { // data from diff file wins
                val start2 = if (idx2 > 0) mc2.fapos[idx2 - 1] else 0
                val len2 = mc2.fapos[idx2++] - start2
                if (keepEmptyNodes || len2 > 0) {
                    write(mc2.ab, start2, len2)
                    fapos[size] = aboffset
                    faid[size++] = id2
                }
                if (id1 == id2) { // // id exists in both caches
                    idx1++
                }
            } else  // use data from base file
            {
                val start1 = if (idx1 > 0) mc1.fapos[idx1 - 1] else 0
                val len1 = mc1.fapos[idx1++] - start1
                write(mc1.ab, start1, len1)
                fapos[size] = aboffset
                faid[size++] = id1
            }
        }
    }

    public companion object {
        public val emptyNonVirgin: MicroCache = MicroCache()

        init {
            emptyNonVirgin.virgin = false
        }

        /**
         * Returns an empty micro-cache.
         *
         * @return an empty micro-cache
         */
        public fun emptyCache(): MicroCache {
            return MicroCache() // TODO: singleton?
        }
    }
}
