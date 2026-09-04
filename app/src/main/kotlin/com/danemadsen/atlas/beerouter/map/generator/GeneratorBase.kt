package com.danemadsen.atlas.beerouter.map.generator

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

public abstract class GeneratorBase : WayListener, NodeListener, RelationListener {
    private var tileOutStreams: Array<DiffCoderDataOutputStream?>? = null
    protected var outTileDir: File? = null
    protected var tags: MutableMap<String, String>? = null

    public fun putTag(key: String, value: String) {
        if (tags == null) {
            tags = HashMap()
        }
        tags!![key] = value
    }

    public fun getTag(key: String): String? = tags?.get(key)

    public fun getTagsOrNull(): MutableMap<String, String>? = tags

    public fun setTags(tags: HashMap<String, String>?) {
        this.tags = tags
    }

    protected fun fileFromTemplate(template: File, dir: File, suffix: String): File {
        var filename = template.name
        filename = filename.substring(0, filename.length - 3) + suffix
        return File(dir, filename)
    }

    protected fun createInStream(inFile: File): DataInputStream =
        DataInputStream(BufferedInputStream(FileInputStream(inFile)))

    protected fun createOutStream(outFile: File): DiffCoderDataOutputStream =
        DiffCoderDataOutputStream(BufferedOutputStream(FileOutputStream(outFile)))

    protected fun getOutStreamForTile(tileIndex: Int): DiffCoderDataOutputStream {
        if (tileOutStreams == null) {
            tileOutStreams = arrayOfNulls(64)
        }
        if (tileOutStreams!![tileIndex] == null) {
            tileOutStreams!![tileIndex] =
                createOutStream(File(requireNotNull(outTileDir), getNameForTile(tileIndex)))
        }
        return tileOutStreams!![tileIndex]!!
    }

    protected open fun getNameForTile(tileIndex: Int): String =
        throw IllegalArgumentException("getNameForTile not implemented")

    protected fun closeTileOutStreams() {
        val streams = tileOutStreams ?: return
        for (i in streams.indices) {
            streams[i]?.close()
            streams[i] = null
        }
    }

    public override fun nodeFileStart(nodefile: File?) {}
    public override fun nextNode(data: NodeData) {}
    public override fun nodeFileEnd(nodefile: File?) {}
    public override fun wayFileStart(wayfile: File): Boolean = true
    public override fun nextWay(data: WayData) {}
    public override fun wayFileEnd(wayfile: File) {}
    public override fun nextRelation(data: RelationData) {}
    public override fun nextRestriction(
        data: RelationData,
        fromWid: Long,
        toWid: Long,
        viaNid: Long
    ) {
    }

    public companion object {
        public fun readId(`is`: DataInputStream): Long {
            val offset = `is`.readByte().toInt()
            if (offset == 32) {
                return -1
            }
            var i = `is`.readInt().toLong()
            i = i shl 5
            return i or offset.toLong()
        }

        public fun writeId(output: DataOutputStream, id: Long) {
            if (id == -1L) {
                output.writeByte(32)
                return
            }
            val offset = (id and 0x1f).toInt()
            val i = (id shr 5).toInt()
            output.writeByte(offset)
            output.writeInt(i)
        }

        public fun sortBySizeAsc(files: Array<File>): Array<File> {
            val sizes = LongArray(files.size) { idx -> files[idx].length() }
            return Array(files.size) { nf ->
                var idx = -1
                var min = -1L
                for (i in files.indices) {
                    if (sizes[i] != -1L && (idx == -1 || sizes[i] < min)) {
                        min = sizes[i]
                        idx = i
                    }
                }
                sizes[idx] = -1
                files[idx]
            }
        }
    }
}
