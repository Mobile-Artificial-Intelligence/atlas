package com.danemadsen.atlas.beerouter.map.generator

import androidx.collection.MutableLongList
import java.io.DataInputStream
import java.io.DataOutputStream

public class WayData : GeneratorBase {
    public var wid: Long
    public var description: ByteArray? = null
    public var nodes: MutableLongList

    public constructor(id: Long) {
        wid = id
        nodes = MutableLongList(16)
    }

    public constructor(id: Long, nodes: MutableLongList) {
        wid = id
        this.nodes = nodes
    }

    public constructor(di: DataInputStream) {
        nodes = MutableLongList(16)
        wid = readId(di)
        val dlen = di.readByte().toInt() and 0xff
        description = ByteArray(dlen)
        di.readFully(description)
        while (true) {
            val nid = readId(di)
            if (nid == -1L) {
                break
            }
            nodes.add(nid)
        }
    }

    public fun writeTo(dos: DataOutputStream) {
        writeId(dos, wid)
        dos.writeByte(requireNotNull(description).size)
        dos.write(description)
        for (i in 0 until nodes.size) {
            writeId(dos, nodes.get(i))
        }
        writeId(dos, -1)
    }
}
