package com.danemadsen.atlas.beerouter.util

public class ByteArrayUnifier(
    private val size: Int,
    private val validateImmutability: Boolean
) : IByteArrayUnifier {
    private val byteArrayCache: Array<ByteArray?> = arrayOfNulls(size)
    private val crcCrosscheck: IntArray = IntArray(size)

    /**
     * Unify a byte array in order to reuse instances when possible.
     * The byte arrays are assumed to be treated as immutable,
     * allowing the reuse
     *
     * @param ab the byte array to unify
     * @return the cached instance or the input instanced if not cached
     */
    public fun unify(ab: ByteArray): ByteArray {
        return unify(ab, 0, ab.size)
    }

    override fun unify(ab: ByteArray, offset: Int, len: Int): ByteArray {
        val crc = Crc32.crc(ab, offset, len)
        val idx = (crc and 0xfffffff) % size
        val abc = byteArrayCache[idx]
        if (abc != null && abc.size == len && (0 until len).all { ab[offset + it] == abc[it] }) {
            return abc
        }
        if (validateImmutability) {
            val abold = byteArrayCache[idx]
            if (abold != null) {
                val crcold = Crc32.crc(abold, 0, abold.size)
                require(crcold == crcCrosscheck[idx]) { "ByteArrayUnifier: immutablity validation failed!" }
            }
            crcCrosscheck[idx] = crc
        }
        val nab = ByteArray(len)
        ab.copyInto(nab, 0, offset, offset + len)
        byteArrayCache[idx] = nab
        return nab
    }
}
