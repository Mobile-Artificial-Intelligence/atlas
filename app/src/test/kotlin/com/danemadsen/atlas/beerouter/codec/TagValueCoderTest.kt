package com.danemadsen.atlas.beerouter.codec

import com.danemadsen.atlas.beerouter.util.BitCoderContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TagValueCoderTest {
    @Test
    fun emptyTagLeavesDoNotCallValidator() {
        val encoded = encodeDictionaryAndValues(null)
        var accessTypeCalls = 0
        val decoder = TagValueCoder(BitCoderContext(encoded), DataBuffers(), validator = object : TagValueValidator {
            override fun accessType(tagValueSet: ByteArray?): Int {
                accessTypeCalls++
                return 2
            }

            override fun unify(ab: ByteArray, offset: Int, len: Int): ByteArray? =
                ab.copyOfRange(offset, offset + len)

            override fun isLookupIdxUsed(idx: Int): Boolean = true

            override fun setDecodeForbidden(decodeForbidden: Boolean) {}

            override fun checkStartWay(ab: ByteArray?): Boolean = true
        })

        assertNull(decoder.decodeTagValueSet())
        assertEquals(0, accessTypeCalls)
    }

    @Test
    fun decodeTagValueDataReadsRawTagLeaves() {
        val first = encodedTagSet(1 to 23)
        val second = encodedTagSet(2 to 42)
        val encoded = encodeDictionaryAndValues(first, second)

        val readContext = BitCoderContext(encoded)
        val decoder = TagValueCoder.rawDataDecoder(readContext, DataBuffers(), null)

        assertContentEquals(first, decoder.decodeTagValueData())
        assertContentEquals(second, decoder.decodeTagValueData())
    }

    private fun encodedTagSet(vararg pairs: Pair<Int, Int>): ByteArray {
        val buffer = ByteArray(32)
        val context = BitCoderContext(buffer)
        var lastLookupIndex = 0
        for ((lookupIndex, value) in pairs) {
            context.encodeVarBits(lookupIndex - lastLookupIndex)
            context.encodeVarBits(value)
            lastLookupIndex = lookupIndex
        }
        context.encodeVarBits(0)
        return buffer.copyOf(context.closeAndGetEncodedLength())
    }

    private fun encodeDictionaryAndValues(vararg values: ByteArray?): ByteArray {
        val buffer = ByteArray(256)
        val encoder = TagValueCoder()
        var length = 0
        repeat(3) {
            val context = BitCoderContext(buffer)
            encoder.encodeDictionary(context)
            for (value in values) {
                encoder.encodeTagValueSet(value)
            }
            length = context.closeAndGetEncodedLength()
        }
        return buffer.copyOf(length)
    }
}
