package com.danemadsen.atlas.beerouter.map.generator

import java.io.DataOutputStream
import java.io.OutputStream

public class DiffCoderDataOutputStream(os: OutputStream) : DataOutputStream(os) {
    private val lastValues: LongArray = LongArray(10)

    public fun writeDiffed(v: Long, idx: Int) {
        val d = v - lastValues[idx]
        lastValues[idx] = v
        writeSigned(d)
    }

    public fun writeSigned(v: Long) {
        writeUnsigned(if (v < 0) ((-v) shl 1) or 1 else v shl 1)
    }

    public fun writeUnsigned(v: Long) {
        var value = v
        do {
            var i7 = value and 0x7f
            value = value shr 7
            if (value != 0L) i7 = i7 or 0x80
            writeByte((i7 and 0xff).toInt())
        } while (value != 0L)
    }
}
