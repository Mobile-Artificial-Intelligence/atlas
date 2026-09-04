package com.danemadsen.atlas.graph

import com.danemadsen.atlas.beerouter.map.MapSource
import com.danemadsen.atlas.beerouter.map.RandomAccessReader
import java.io.File
import java.io.RandomAccessFile

/**
 * A [MapSource] over one directory of `.rd5` segments — the bridge the
 * build pipeline and tests use to open freshly minted graphs with the
 * stock engine (and the shape the app's runtime source will take).
 */
public class FileMapSource(private val segmentDir: File) : MapSource {

    override fun exists(fileName: String): Boolean = File(segmentDir, fileName).isFile

    override fun open(fileName: String): RandomAccessReader =
        FileRandomAccessReader(File(segmentDir, fileName))

    private class FileRandomAccessReader(file: File) : RandomAccessReader {
        private val delegate = RandomAccessFile(file, "r")
        override fun seek(position: Long) = delegate.seek(position)
        override fun readFully(buffer: ByteArray, offset: Int, length: Int) =
            delegate.readFully(buffer, offset, length)
        override fun length(): Long = delegate.length()
        override fun close() = delegate.close()
    }
}