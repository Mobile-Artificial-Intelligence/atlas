package com.danemadsen.atlas.beerouter.map

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

public interface RandomAccessReader {
    public fun seek(position: Long)
    public fun readFully(buffer: ByteArray, offset: Int, length: Int)
    public fun length(): Long
    public fun close()
}

public interface MapSource {
    public fun exists(fileName: String): Boolean
    public fun open(fileName: String): RandomAccessReader
}

public class DefaultMapSource(private val segmentDir: Path) : MapSource {
    private fun resolve(fileName: String): Path = Path(segmentDir, fileName)

    override fun exists(fileName: String): Boolean = SystemFileSystem.exists(resolve(fileName))

    override fun open(fileName: String): RandomAccessReader = openRandomAccess(resolve(fileName))
}
