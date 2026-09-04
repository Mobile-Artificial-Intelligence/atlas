package com.danemadsen.atlas.beerouter.codec

import com.danemadsen.atlas.beerouter.util.BitCoderContext
import com.danemadsen.atlas.beerouter.router.exceptions.DataCorruptionException

/**
 * Encoder/Decoder for way-/node-descriptions
 *
 * It detects identical descriptions and sorts them
 * into a huffman-tree according to their frequencies
 *
 * Adapted for 3-pass encoding (counters -> statistics -> encoding )
 * but doesn't do anything at pass1
 */
public class TagValueCoder {
    private val identityMap: MutableMap<TagValueSet, TagValueSet> = mutableMapOf()
    private var tree: Any? = null
    private var bc: BitCoderContext = BitCoderContext(ByteArray(0))
    private var pass = 0
    private var nextTagValueSetId = 0

    /**
     * Encode a tag value set.
     *
     * @param data the tag value data
     * @throws IllegalArgumentException if an unknown tag value set is encountered in pass 3
     */
    public fun encodeTagValueSet(data: ByteArray?) {
        if (pass == 1) {
            return
        }
        val tvsProbe = TagValueSet(nextTagValueSetId)
        tvsProbe.data = data
        var tvs = identityMap[tvsProbe]
        if (pass == 3) {
            val knownSet = requireNotNull(tvs)
            bc.encodeBounded(knownSet.range - 1, knownSet.code)
        } else if (pass == 2) {
            if (tvs == null) {
                tvs = tvsProbe
                nextTagValueSetId++
                identityMap[tvs] = tvs
            }
            tvs.frequency++
        }
    }

    /**
     * Decode a tag value set.
     *
     * @return the decoded tag value wrapper, or null
     */
    public fun decodeTagValueSet(): TagValueWrapper? {
        var node = tree
        while (node is TreeNode) {
            val tn = node
            val nextBit = bc.decodeBit()
            node = if (nextBit) tn.child2 else tn.child1
        }
        return node as TagValueWrapper?
    }

    /**
     * Encode the tag value dictionary.
     *
     * @param bc the bit coder context
     */
    public fun encodeDictionary(bc: BitCoderContext) {
        if (++pass == 3) {
            if (identityMap.isEmpty()) {
                val dummy = TagValueSet(nextTagValueSetId++)
                identityMap[dummy] = dummy
            }
            val comparator = TagValueSet.frequencyComparator
            val queue = ArrayDeque<TagValueSet>()
            queue.addAll(identityMap.values)
            queue.sortWith(comparator)
            while (queue.size > 1) {
                val child1 = queue.removeFirst()
                val child2 = queue.removeFirst()
                val node = TagValueSet(nextTagValueSetId++)
                node.child1 = child1
                node.child2 = child2
                node.frequency = child1.frequency + child2.frequency
                val insertIdx = queue.indexOfFirst { comparator.compare(node, it) <= 0 }
                if (insertIdx == -1) queue.addLast(node) else queue.add(insertIdx, node)
            }
            val root = queue.removeFirst()
            root.encode(bc, 1, 0)
        }
        this.bc = bc
    }

    public constructor(
        bc: BitCoderContext,
        buffers: DataBuffers,
        validator: TagValueValidator?
    ) {
        tree = decodeTree(bc, buffers, validator) { data ->
            val accessType = validator?.accessType(data) ?: 2
            if (accessType > 0) TagValueWrapper(data, accessType) else null
        }
        this.bc = bc
    }

    public constructor()

    public class RawDataDecoder internal constructor(
        bc: BitCoderContext,
        buffers: DataBuffers,
        validator: TagValueValidator?
    ) {
        private val tree: Any? = decodeTree(bc, buffers, validator) { data -> data }
        private val bc: BitCoderContext = bc

        public fun decodeTagValueData(): ByteArray? {
            var node = tree
            while (node is TreeNode) {
                val tn = node
                val nextBit = bc.decodeBit()
                node = if (nextBit) tn.child2 else tn.child1
            }
            return node as ByteArray?
        }
    }

    public data class TreeNode(public val child1: Any?, public val child2: Any?)

    public companion object {
        public fun rawDataDecoder(
            bc: BitCoderContext,
            buffers: DataBuffers,
            validator: TagValueValidator?
        ): RawDataDecoder = RawDataDecoder(bc, buffers, validator)

        private fun decodeTree(
            bc: BitCoderContext,
            buffers: DataBuffers,
            validator: TagValueValidator?,
            leaf: (ByteArray?) -> Any?
        ): Any? {
            val isNode = bc.decodeBit()
            if (isNode) {
                return TreeNode(
                    child1 = decodeTree(bc, buffers, validator, leaf),
                    child2 = decodeTree(bc, buffers, validator, leaf)
                )
            }

            val buffer = buffers.tagbuf1
            val ctx = buffers.bctx1
            ctx.reset(buffer)

            var inum = 0
            var lastEncodedInum = 0

            var hasdata = false
            while (true) {
                val delta = bc.decodeVarBits()
                if (!hasdata) {
                    if (delta == 0) {
                        return null
                    }
                }
                if (delta == 0) {
                    ctx.encodeVarBits(0)
                    break
                }
                inum += delta

                val data = bc.decodeVarBits()

                if (validator == null || validator.isLookupIdxUsed(inum)) {
                    hasdata = true
                    ctx.encodeVarBits(inum - lastEncodedInum)
                    ctx.encodeVarBits(data)
                    lastEncodedInum = inum
                }
            }

            val len = ctx.closeAndGetEncodedLength()
            val res = if (validator == null) {
                ByteArray(len).also { buffer.copyInto(it, 0, 0, len) }
            } else {
                validator.unify(buffer, 0, len)
            }
            return leaf(res)
        }
    }

    public class TagValueSet(
        private val id: Int
    ) {
        public var data: ByteArray? = null
        public var frequency: Int = 0
        public var code: Int = 0
        public var range: Int = 0
        public var child1: TagValueSet? = null
        public var child2: TagValueSet? = null

        public fun encode(bc: BitCoderContext, range: Int, code: Int) {
            this.range = range
            this.code = code
            val left = child1
            val right = child2
            val isNode = left != null
            bc.encodeBit(isNode)
            if (isNode) {
                requireNotNull(left).encode(bc, range shl 1, code)
                requireNotNull(right).encode(bc, range shl 1, code + range)
            } else {
                val encodedData = data
                if (encodedData == null) {
                    bc.encodeVarBits(0)
                    return
                }
                val src = BitCoderContext(encodedData)
                while (true) {
                    val delta = src.decodeVarBits()
                    bc.encodeVarBits(delta)
                    if (delta == 0) {
                        break
                    }
                    val data = src.decodeVarBits()
                    bc.encodeVarBits(data)
                }
            }
        }

        override fun equals(other: Any?): Boolean {
            if (other !is TagValueSet) return false
            val thisData = data
            val otherData = other.data
            if (thisData == null) return otherData == null
            if (otherData == null || thisData.size != otherData.size) return false
            return thisData.contentEquals(otherData)
        }

        override fun hashCode(): Int {
            return data?.contentHashCode() ?: 0
        }

        public companion object {
            public val frequencyComparator: Comparator<TagValueSet> = Comparator { a, b ->
                when {
                    a.frequency < b.frequency -> -1
                    a.frequency > b.frequency -> 1
                    // to avoid ordering instability, decide on the id if frequency is equal
                    a.id < b.id -> -1
                    a.id > b.id -> 1
                    a !== b -> {
                        throw DataCorruptionException("identity corruption!")
                    }

                    else -> 0
                }
            }
        }
    }
}
