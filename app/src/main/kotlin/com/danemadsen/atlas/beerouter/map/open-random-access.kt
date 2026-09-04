package com.danemadsen.atlas.beerouter.map

import kotlinx.io.files.Path
import java.io.RandomAccessFile

internal fun openRandomAccess(path: Path): RandomAccessReader = AndroidRandomAccessReader(path)

private class AndroidRandomAccessReader(path: Path) : RandomAccessReader {
    private val raf = RandomAccessFile(path.toString(), "r")
    override fun seek(position: Long) = raf.seek(position)
    override fun readFully(buffer: ByteArray, offset: Int, length: Int) = raf.readFully(buffer, offset, length)
    override fun length(): Long = raf.length()
    override fun close() = raf.close()
}