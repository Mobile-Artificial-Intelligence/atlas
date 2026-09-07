package com.danemadsen.atlas.graph

import com.danemadsen.atlas.beerouter.map.MapSource
import com.danemadsen.atlas.beerouter.map.RandomAccessReader
import java.io.File
import java.io.RandomAccessFile

/**
 * A [MapSource] over SEVERAL segment directories — one per installed map
 * region (`graph/segments/<regionId>/`). The routing engine is single-map:
 * it asks for a bucket by file name and has no notion of regions, so the
 * multi-region view collapses to a name lookup across all region dirs.
 *
 * First hit wins: a 5° bucket that straddles two regions' bboxes is built
 * by whichever region's build ran first, and BOTH copies are equally valid
 * — they were cut from the same OSM planet data and the same shared
 * `lookups.dat` (extracted by [com.danemadsen.atlas.routing.GraphBuildCoordinator.ensureBuildAssets],
 * versioned with the app), so the `.rd5` content cannot differ between
 * regions. There is no "newer" or "authoritative" copy to prefer; picking
 * either is deterministic and safe, and `exists`/`open` agree on the same
 * first-hit rule so the engine can never see a bucket exist then fail to
 * open (or vice versa).
 */
class MultiDirMapSource(private val dirs: List<File>) : MapSource {

    init {
        require(dirs.isNotEmpty()) { "MultiDirMapSource needs at least one segment directory" }
    }

    override fun exists(fileName: String): Boolean =
        // isFile, not exists(): a stray directory named like a bucket must
        // not route, and open() would fail on it anyway.
        resolve(fileName) != null

    override fun open(fileName: String): RandomAccessReader {
        val file = resolve(fileName)
            ?: throw java.io.FileNotFoundException("$fileName in any of $dirs")
        return DirRandomAccessReader(file)
    }

    /** The first region dir that holds [fileName], or null. */
    private fun resolve(fileName: String): File? =
        dirs.firstNotNullOfOrNull { dir -> File(dir, fileName).takeIf { it.isFile } }

    private class DirRandomAccessReader(file: File) : RandomAccessReader {
        private val delegate = RandomAccessFile(file, "r")
        override fun seek(position: Long) = delegate.seek(position)
        override fun readFully(buffer: ByteArray, offset: Int, length: Int) =
            delegate.readFully(buffer, offset, length)
        override fun length(): Long = delegate.length()
        override fun close() = delegate.close()
    }
}