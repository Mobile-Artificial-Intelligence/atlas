/**
 * fast data-reading from a byte-array
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.util

public open class ByteDataReader(
    byteArray: ByteArray = ByteArray(0),
    offset: Int = 0
) {
    protected var ab: ByteArray = byteArray
    protected var aboffset: Int = offset
    protected var aboffsetEnd: Int = byteArray.size

    public fun reset(byteArray: ByteArray) {
        ab = byteArray
        aboffset = 0
        aboffsetEnd = ab.size
    }


    /**
     * @throws IndexOutOfBoundsException if fewer than 4 bytes remain
     */
    public fun readInt(): Int {
        val i3 = ab[aboffset++].toInt() and 0xff
        val i2 = ab[aboffset++].toInt() and 0xff
        val i1 = ab[aboffset++].toInt() and 0xff
        val i0 = ab[aboffset++].toInt() and 0xff
        return (i3 shl 24) + (i2 shl 16) + (i1 shl 8) + i0
    }

    /**
     * @throws IndexOutOfBoundsException if fewer than 8 bytes remain
     */
    public fun readLong(): Long {
        val i7 = (ab[aboffset++].toInt() and 0xff).toLong()
        val i6 = (ab[aboffset++].toInt() and 0xff).toLong()
        val i5 = (ab[aboffset++].toInt() and 0xff).toLong()
        val i4 = (ab[aboffset++].toInt() and 0xff).toLong()
        val i3 = (ab[aboffset++].toInt() and 0xff).toLong()
        val i2 = (ab[aboffset++].toInt() and 0xff).toLong()
        val i1 = (ab[aboffset++].toInt() and 0xff).toLong()
        val i0 = (ab[aboffset++].toInt() and 0xff).toLong()
        return (i7 shl 56) + (i6 shl 48) + (i5 shl 40) + (i4 shl 32) + (i3 shl 24) + (i2 shl 16) + (i1 shl 8) + i0
    }

    /**
     * @throws IndexOutOfBoundsException if no bytes remain
     */
    public fun readBoolean(): Boolean {
        val i0 = ab[aboffset++].toInt() and 0xff
        return i0 != 0
    }

    /**
     * @throws IndexOutOfBoundsException if no bytes remain
     */
    public fun readByte(): Byte {
        return (ab[aboffset++].toInt() and 0xff).toByte()
    }

    /**
     * @throws IndexOutOfBoundsException if fewer than 2 bytes remain
     */
    public fun readShort(): Short {
        val i1 = ab[aboffset++].toInt() and 0xff
        val i0 = ab[aboffset++].toInt() and 0xff
        return ((i1 shl 8) or i0).toShort()
    }

    public val endPointer: Int
        /**
         * Read a size value and return a pointer to the end of a data section of that size
         *
         * @return the pointer to the first byte after that section
         * @throws IndexOutOfBoundsException if reading the size exceeds the buffer
         */
        get() {
            val size = readVarLengthUnsigned()
            return aboffset + size
        }

    /**
     * @throws IndexOutOfBoundsException if [endPointer] exceeds the buffer bounds
     */
    public fun readDataUntil(endPointer: Int): ByteArray? {
        val size = endPointer - aboffset
        if (size == 0) {
            return null
        }
        val data = ByteArray(size)
        readFully(data)
        return data
    }

    /**
     * @throws IndexOutOfBoundsException if reading the length or payload exceeds the buffer
     */
    public fun readVarBytes(): ByteArray? {
        val len = readVarLengthUnsigned()
        if (len == 0) {
            return null
        }
        val bytes = ByteArray(len)
        readFully(bytes)
        return bytes
    }

    /**
     * @throws IndexOutOfBoundsException if reading the var-length exceeds the buffer
     */
    public fun readVarLengthSigned(): Int {
        val v = readVarLengthUnsigned()
        return if ((v and 1) == 0) v shr 1 else -(v shr 1)
    }

    /**
     * @throws IndexOutOfBoundsException if reading the var-length exceeds the buffer
     */
    public fun readVarLengthUnsigned(): Int {
        var b: Byte
        var v = (ab[aboffset++].also { b = it }).toInt() and 0x7f
        if (b >= 0) return v
        v = v or (((ab[aboffset++].also { b = it }).toInt() and 0x7f) shl 7)
        if (b >= 0) return v
        v = v or (((ab[aboffset++].also { b = it }).toInt() and 0x7f) shl 14)
        if (b >= 0) return v
        v = v or (((ab[aboffset++].also { b = it }).toInt() and 0x7f) shl 21)
        if (b >= 0) return v
        v = v or (((ab[aboffset++].also { b = it }).toInt() and 0xf) shl 28)
        return v
    }

    /**
     * @throws IndexOutOfBoundsException if [ta] cannot be fully read from the remaining buffer
     */
    public fun readFully(ta: ByteArray) {
        ab.copyInto(ta, 0, aboffset, aboffset + ta.size)
        aboffset += ta.size
    }

    public fun hasMoreData(): Boolean {
        return aboffset < aboffsetEnd
    }

    override fun toString(): String {
        val sb = StringBuilder("[")
        for (i in ab.indices) sb.append(if (i == 0) " " else ", ")
            .append(ab[i].toInt().toString())
        sb.append(" ]")
        return sb.toString()
    }
}
