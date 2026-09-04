package com.danemadsen.atlas.beerouter.map.generator

import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.geo.UNSET_ELEVATION

public class NodeData : GeneratorBase {
    public var nid: Long
    public var position: Position
    public var description: ByteArray? = null
    public var retainWithoutLinks: Boolean = false

    public constructor(id: Long, lon: Double, lat: Double) {
        nid = id
        position = Position(lon, lat)
    }

    public constructor(dis: DiffCoderDataInputStream) {
        nid = dis.readDiffed(0)
        val ilon = dis.readDiffed(1).toInt()
        val ilat = dis.readDiffed(2).toInt()
        val mode = dis.readByte().toInt()
        if ((mode and 1) != 0) {
            val dlen = dis.readShort().toInt()
            description = ByteArray(dlen)
            dis.readFully(description)
        }
        var selev = UNSET_ELEVATION
        if ((mode and 2) != 0) {
            selev = dis.readShort()
        }
        position = Position(ilon, ilat, selev)
        retainWithoutLinks = (mode and 4) != 0
    }

    public fun writeTo(dos: DiffCoderDataOutputStream) {
        dos.writeDiffed(nid, 0)
        dos.writeDiffed(position.longitude.toLong(), 1)
        dos.writeDiffed(position.latitude.toLong(), 2)
        val mode = (if (description == null) 0 else 1) or
                (if (position.altitude == UNSET_ELEVATION) 0 else 2) or
                (if (retainWithoutLinks) 4 else 0)
        dos.writeByte(mode)
        if ((mode and 1) != 0) {
            dos.writeShort(description!!.size)
            dos.write(description)
        }
        if ((mode and 2) != 0) {
            dos.writeShort(position.altitude.toInt())
        }
    }
}
