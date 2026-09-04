package com.danemadsen.atlas.beerouter.map

import com.danemadsen.atlas.beerouter.codec.DataBuffers
import com.danemadsen.atlas.beerouter.codec.StatCoderContext
import com.danemadsen.atlas.beerouter.codec.TagValueValidator
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DirectWeaverTest {
    @Test
    fun clearsReusableNodeBufferWhenDecodeFails() {
        val dataBuffers = DataBuffers()
        val staleNode = OsmNode(0, 0).apply { setHollow() }
        val hollowNodes = OsmNodesMap().apply { put(staleNode) }
        dataBuffers.objectBuffer1[0] = OsmNode(1, 1)

        assertFailsWith<IndexOutOfBoundsException> {
            DirectWeaver(
                bc = StatCoderContext(invalidOneNodeInternalLinkCell()),
                dataBuffers = dataBuffers,
                lonIdx = 0,
                latIdx = 0,
                divisor = 1,
                wayValidator = permissiveValidator,
                waypointMatcher = null,
                hollowNodes = hollowNodes,
            )
        }

        assertNull(dataBuffers.objectBuffer1[0])
    }

    @Test
    fun clearsReusableNodeBufferWhenDecodeFailsBeforeNodeCount() {
        val dataBuffers = DataBuffers()
        dataBuffers.objectBuffer1[0] = OsmNode(1, 1)

        assertFailsWith<IndexOutOfBoundsException> {
            DirectWeaver(
                bc = StatCoderContext(ByteArray(0)),
                dataBuffers = dataBuffers,
                lonIdx = 0,
                latIdx = 0,
                divisor = 1,
                wayValidator = permissiveValidator,
                waypointMatcher = null,
                hollowNodes = OsmNodesMap(),
            )
        }

        assertNull(dataBuffers.objectBuffer1[0])
    }

    private fun invalidOneNodeInternalLinkCell(): ByteArray {
        val buffer = ByteArray(128)
        val bc = StatCoderContext(buffer)
        encodeNullTagTree(bc) // way tags
        encodeNullTagTree(bc) // node tags
        repeat(5) { bc.encodeVarBits(0) } // noisy-bit dictionaries
        bc.encodeNoisyNumber(1, 5) // one node
        bc.encodeSortedArray(intArrayOf(0), 0, 1, 29, 0)
        bc.encodeNoisyNumber(0, 10) // netdatasize
        bc.encodeVarBits(0) // no node features
        bc.encodeNoisyDiff(0, 0) // elevation
        bc.encodeNoisyNumber(1, 1) // one link
        bc.encodeNoisyDiff(10, 0) // invalid internal target index
        return buffer.copyOf(bc.closeAndGetEncodedLength())
    }

    private fun encodeNullTagTree(bc: StatCoderContext) {
        bc.encodeBit(false)
        bc.encodeVarBits(0)
    }

    private val permissiveValidator = object : TagValueValidator {
        override fun accessType(tagValueSet: ByteArray?): Int = 2
        override fun unify(ab: ByteArray, offset: Int, len: Int): ByteArray = ab.copyOfRange(offset, offset + len)
        override fun isLookupIdxUsed(idx: Int): Boolean = true
        override fun setDecodeForbidden(decodeForbidden: Boolean) = Unit
        override fun checkStartWay(ab: ByteArray?): Boolean = true
    }
}
