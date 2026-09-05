package com.danemadsen.atlas.pmtiles

/**
 * One serialized directory entry. A run length of 0 marks a leaf-directory
 * pointer; otherwise the entry addresses [tileId, tileId + runLength) tiles
 * stored in the tile-data section (relative to [PmtilesEntry.offset]).
 */
class PmtilesEntry(
    val tileId: Long,
    val runLength: Int,
    val length: Int,
    val offset: Long,
) {
    val isLeafPointer: Boolean get() = runLength == 0
}

/** Decode for the PMTiles v3 directory format:
 *  entry count (varint), delta-encoded tile ids (varints), run lengths
 *  (varints), lengths (varints), then offsets as `offset + 1` — or 0 when an
 *  entry is contiguous with the previous one. */
object PmtilesDirectory {

    fun decode(bytes: ByteArray): List<PmtilesEntry> {
        val cursor = VarintCursor(bytes)
        val count = cursor.readUint32().toInt()
        require(count > 0) { "directory entry count must be > 0" }

        val tileIds = LongArray(count)
        run {
            var last = 0L
            for (i in 0 until count) {
                last += cursor.readUint64()
                tileIds[i] = last
            }
        }
        val runLengths = IntArray(count) { cursor.readUint32().toInt() }
        val lengths = IntArray(count) { cursor.readUint32().toInt() }

        val entries = ArrayList<PmtilesEntry>(count)
        var position = 0L
        for (i in 0 until count) {
            val raw = cursor.readUint64()
            position = if (raw == 0L && i > 0) {
                entries[i - 1].offset + entries[i - 1].length
            } else {
                raw - 1
            }
            entries.add(PmtilesEntry(tileIds[i], runLengths[i], lengths[i], position))
        }
        return entries
    }

    /**
     * Find the entry covering [tileId]: the last entry whose tile id is
     * <= [tileId]. Returns null when the id falls into a gap.
     */
    fun findEntry(entries: List<PmtilesEntry>, tileId: Long): PmtilesEntry? {
        var low = 0
        var high = entries.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val entry = entries[mid]
            when {
                entry.tileId > tileId -> high = mid - 1
                entry.tileId < tileId -> low = mid + 1
                else -> return entry
            }
        }
        // low - 1 is the last entry with tileId <= requested.
        val candidate = entries.getOrNull(low - 1) ?: return null
        if (candidate.isLeafPointer) return candidate
        return if (tileId < candidate.tileId + candidate.runLength) candidate else null
    }
}

/** Protobuf-style unsigned varint reader over a byte buffer. */
class VarintCursor(
    private val buffer: ByteArray,
    private var position: Int = 0,
) {
    fun readUint32(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            checkHasBytes()
            val b = buffer[position++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift <= 35) { "varint too long" }
        }
    }

    fun readUint64(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            checkHasBytes()
            val b = buffer[position++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift <= 63) { "varint too long" }
        }
    }

    val hasBytes: Boolean get() = position < buffer.size

    private fun checkHasBytes() {
        require(position < buffer.size) { "varint buffer underrun" }
    }
}