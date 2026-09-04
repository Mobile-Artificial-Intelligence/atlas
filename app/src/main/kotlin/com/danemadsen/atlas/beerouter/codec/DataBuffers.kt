package com.danemadsen.atlas.beerouter.codec

import com.danemadsen.atlas.beerouter.util.BitCoderContext

/**
 * Container for some re-usable databuffers for the decoder
 */
public class DataBuffers(public val iobuffer: ByteArray = ByteArray(IO_BUFFER_SIZE)) {
    public val tagbuf1: ByteArray = ByteArray(TAG_BUFFER_SIZE)
    public val bctx1: BitCoderContext = BitCoderContext(tagbuf1)
    public val bbuf1: ByteArray = ByteArray(IO_BUFFER_SIZE)
    public val ibuf1: IntArray = IntArray(LARGE_INT_BUFFER_SIZE)
    public val ibuf2: IntArray = IntArray(SMALL_INT_BUFFER_SIZE)
    public val ibuf3: IntArray = IntArray(SMALL_INT_BUFFER_SIZE)
    public val alon: IntArray = IntArray(SMALL_INT_BUFFER_SIZE)
    public val alat: IntArray = IntArray(SMALL_INT_BUFFER_SIZE)
    public var objectBuffer1: Array<Any?> = arrayOfNulls(SMALL_INT_BUFFER_SIZE)

    private companion object {
        private const val IO_BUFFER_SIZE = 65636
        private const val TAG_BUFFER_SIZE = 256
        private const val LARGE_INT_BUFFER_SIZE = 4096
        private const val SMALL_INT_BUFFER_SIZE = 2048
    }
}
