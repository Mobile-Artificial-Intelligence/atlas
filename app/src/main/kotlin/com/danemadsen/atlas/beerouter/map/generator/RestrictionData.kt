package com.danemadsen.atlas.beerouter.map.generator

import com.danemadsen.atlas.beerouter.geo.CheapAngleMeter
import com.danemadsen.atlas.beerouter.geo.Position
import java.io.BufferedWriter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileWriter
import java.io.IOException
import java.util.TreeSet

public class RestrictionData() : GeneratorBase() {
    public var restrictionKey: String = ""
    public var restriction: String = ""
    public var exceptions: Short = 0
    public var fromWid: Long = 0
    public var toWid: Long = 0
    public var viaNid: Long = 0
    public var next: RestrictionData? = null
    public var viaPosition: Position = Position.ZERO
    public var fromPosition: Position = Position.ZERO
    public var toPosition: Position = Position.ZERO
    public var badWayMatch: Boolean = false

    public constructor(di: DataInputStream) : this() {
        restrictionKey = unifyName(di.readUTF())
        restriction = unifyName(di.readUTF())
        exceptions = di.readShort()
        fromWid = readId(di)
        toWid = readId(di)
        viaNid = readId(di)
    }

    public fun isPositive(): Boolean = restriction.startsWith("only_")

    public fun isValid(): Boolean {
        var valid = fromPosition.longitude != 0 && toPosition.longitude != 0 &&
                (restriction.startsWith("only_") || restriction.startsWith("no_"))
        valid = valid && !restriction.contains("on_red")
        if (!valid || badWayMatch || !checkGeometry()) {
            synchronized(badTRs) {
                badTRs.add(viaPosition.id)
            }
        }
        return valid && restrictionKey == "restriction"
    }

    private fun checkGeometry(): Boolean {
        val a = CheapAngleMeter.measureAngle(
            fromPosition.longitude, fromPosition.latitude,
            viaPosition.longitude, viaPosition.latitude,
            toPosition.longitude, toPosition.latitude
        )
        var t = when {
            restriction.startsWith("only_") -> restriction.substring("only_".length)
            restriction.startsWith("no_") -> restriction.substring("no_".length)
            else -> throw RuntimeException("ups")
        }
        if (restrictionKey.endsWith(":conditional")) {
            val idx = t.indexOf('@')
            if (idx >= 0) {
                t = t.substring(0, idx).trim()
            }
        }
        return when (t) {
            "left_turn" -> a < -5.0 && a > -175.0
            "right_turn" -> a > 5.0 && a < 175.0
            "straight_on" -> a > -85.0 && a < 85.0
            "u_turn" -> a < -95.0 || a > 95.0
            else -> t == "entry" || t == "exit"
        }
    }

    public fun writeTo(dos: DataOutputStream) {
        dos.writeUTF(restrictionKey)
        dos.writeUTF(restriction)
        dos.writeShort(exceptions.toInt())
        writeId(dos, fromWid)
        writeId(dos, toWid)
        writeId(dos, viaNid)
    }

    public companion object {
        private val names: MutableMap<String, String> = HashMap()
        private val badTRs: MutableSet<Long> = TreeSet()

        private fun unifyName(name: String): String =
            synchronized(names) { names.getOrPut(name) { name } }

        public fun dumpBadTRs() {
            try {
                BufferedWriter(FileWriter("badtrs.txt")).use { bw ->
                    for (id in badTRs) {
                        bw.write("$id 26\n")
                    }
                }
            } catch (ioe: IOException) {
                throw RuntimeException(ioe)
            }
        }
    }
}
