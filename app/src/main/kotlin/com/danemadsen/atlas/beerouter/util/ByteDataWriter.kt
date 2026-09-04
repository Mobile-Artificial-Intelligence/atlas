/**
 * fast data-writing to a byte-array
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.util


public open class ByteDataWriter(byteArray: ByteArray = ByteArray(0)) : ByteDataReader(byteArray) {
    /**
     * @throws IndexOutOfBoundsException if fewer than 4 bytes can be written
     */
    public fun writeInt(v: Int) {
        ab[aboffset++] = ((v shr 24) and 0xff).toByte()
        ab[aboffset++] = ((v shr 16) and 0xff).toByte()
        ab[aboffset++] = ((v shr 8) and 0xff).toByte()
        ab[aboffset++] = ((v) and 0xff).toByte()
    }

    /**
     * @throws IndexOutOfBoundsException if fewer than 8 bytes can be written
     */
    public fun writeLong(v: Long) {
        ab[aboffset++] = ((v shr 56) and 0xffL).toByte()
        ab[aboffset++] = ((v shr 48) and 0xffL).toByte()
        ab[aboffset++] = ((v shr 40) and 0xffL).toByte()
        ab[aboffset++] = ((v shr 32) and 0xffL).toByte()
        ab[aboffset++] = ((v shr 24) and 0xffL).toByte()
        ab[aboffset++] = ((v shr 16) and 0xffL).toByte()
        ab[aboffset++] = ((v shr 8) and 0xffL).toByte()
        ab[aboffset++] = ((v) and 0xffL).toByte()
    }

    /**
     * @throws IndexOutOfBoundsException if no space remains
     */
    public fun writeBoolean(v: Boolean) {
        ab[aboffset++] = (if (v) 1 else 0).toByte()
    }

    /**
     * @throws IndexOutOfBoundsException if no space remains
     */
    public fun writeByte(v: Int) {
        ab[aboffset++] = ((v) and 0xff).toByte()
    }

    /**
     * @throws IndexOutOfBoundsException if fewer than 2 bytes can be written
     */
    public fun writeShort(v: Int) {
        ab[aboffset++] = ((v shr 8) and 0xff).toByte()
        ab[aboffset++] = ((v) and 0xff).toByte()
    }

    /**
     * @throws IndexOutOfBoundsException if [sa] does not fit in the remaining buffer
     */
    public fun write(sa: ByteArray) {
        sa.copyInto(ab, aboffset, 0, sa.size)
        aboffset += sa.size
    }

    /**
     * @throws IndexOutOfBoundsException if the specified range does not fit in the remaining buffer
     */
    public fun write(sa: ByteArray, offset: Int, len: Int) {
        sa.copyInto(ab, aboffset, offset, offset + len)
        aboffset += len
    }

    /**
     * @throws IndexOutOfBoundsException if the length or payload does not fit in the remaining buffer
     */
    public fun writeVarBytes(sa: ByteArray?) {
        if (sa == null) {
            writeVarLengthUnsigned(0)
        } else {
            val len = sa.size
            writeVarLengthUnsigned(len)
            write(sa, 0, len)
        }
    }

    /**
     * @throws IndexOutOfBoundsException if the encoded length or payload does not fit in the remaining buffer
     */
    public fun writeModeAndDesc(isReverse: Boolean, sa: ByteArray?) {
        val len = sa?.size ?: 0
        val sizecode = len shl 1 or (if (isReverse) 1 else 0)
        writeVarLengthUnsigned(sizecode)
        sa?.let { write(it, 0, len) }
    }


    public fun toByteArray(): ByteArray {
        val c = ByteArray(aboffset)
        ab.copyInto(c, 0, 0, aboffset)
        return c
    }


    /**
     * Just reserves a single byte and return it' offset.
     * Used in conjunction with injectVarLengthUnsigned
     * to efficiently write a size prefix
     *
     * @throws IndexOutOfBoundsException if no space remains
     * @return the offset of the placeholder
     */
    public fun writeSizePlaceHolder(): Int {
        return aboffset++
    }

    /**
     * @throws IndexOutOfBoundsException if shifting or rewriting the size exceeds the buffer
     */
    public fun injectSize(sizeoffset: Int) {
        var size = 0
        val datasize = aboffset - sizeoffset - 1
        var v = datasize
        do {
            v = v shr 7
            size++
        } while (v != 0)
        if (size > 1) { // doesn't fit -> shift the data after the placeholder
            ab.copyInto(ab, sizeoffset + size, sizeoffset + 1, sizeoffset + 1 + datasize)
        }
        aboffset = sizeoffset
        writeVarLengthUnsigned(datasize)
        aboffset = sizeoffset + size + datasize
    }

    /**
     * @throws IndexOutOfBoundsException if the encoded value does not fit in the remaining buffer
     */
    public fun writeVarLengthSigned(v: Int) {
        writeVarLengthUnsigned(if (v < 0) ((-v) shl 1) or 1 else v shl 1)
    }

    /**
     * @throws IndexOutOfBoundsException if the encoded value does not fit in the remaining buffer
     */
    public fun writeVarLengthUnsigned(v: Int) {
        var v = v
        var i7 = v and 0x7f
        if ((7.let { v = v ushr it; v }) == 0) {
            ab[aboffset++] = (i7).toByte()
            return
        }
        ab[aboffset++] = (i7 or 0x80).toByte()

        i7 = v and 0x7f
        if ((7.let { v = v ushr it; v }) == 0) {
            ab[aboffset++] = (i7).toByte()
            return
        }
        ab[aboffset++] = (i7 or 0x80).toByte()

        i7 = v and 0x7f
        if ((7.let { v = v ushr it; v }) == 0) {
            ab[aboffset++] = (i7).toByte()
            return
        }
        ab[aboffset++] = (i7 or 0x80).toByte()

        i7 = v and 0x7f
        if ((7.let { v = v ushr it; v }) == 0) {
            ab[aboffset++] = (i7).toByte()
            return
        }
        ab[aboffset++] = (i7 or 0x80).toByte()

        ab[aboffset++] = (v).toByte()
    }

    public fun writtenSize(): Int = aboffset
}
