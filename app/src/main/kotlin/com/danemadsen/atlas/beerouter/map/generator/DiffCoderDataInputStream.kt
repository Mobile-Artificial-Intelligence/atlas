package com.danemadsen.atlas.beerouter.map.generator

import java.io.DataInputStream
import java.io.InputStream

public class DiffCoderDataInputStream(input: InputStream) : DataInputStream(input) {
    private val lastValues: LongArray = LongArray(10)

    public fun readDiffed(idx: Int): Long {
        val d = readSigned()
        val v = lastValues[idx] + d
        lastValues[idx] = v
        return v
    }

    public fun readSigned(): Long {
        val v = readUnsigned()
        return if ((v and 1L) == 0L) v shr 1 else -(v shr 1)
    }

    public fun readUnsigned(): Long {
        var v = 0L
        var shift = 0
        while (true) {
            val i7 = readByte().toLong() and 0xffL
            v = v or ((i7 and 0x7fL) shl shift)
            if ((i7 and 0x80L) == 0L) {
                return v
            }
            shift += 7
        }
    }
}
