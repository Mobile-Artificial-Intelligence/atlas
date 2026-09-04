package com.danemadsen.atlas.beerouter.map.generator

import java.io.EOFException
import java.io.File

public class RelationStatistics : GeneratorBase() {
    public fun process(relationFileIn: File) {
        val relstats: MutableMap<String, LongArray> = HashMap()
        val dis = createInStream(relationFileIn)
        try {
            while (true) {
                readId(dis)
                val network = dis.readUTF()
                var waycount = 0
                while (true) {
                    val wid = readId(dis)
                    if (wid == -1L) break
                    waycount++
                }
                val stat = relstats.getOrPut(network) { LongArray(2) }
                stat[0]++
                stat[1] += waycount.toLong()
            }
        } catch (_: EOFException) {
            dis.close()
        }
        for ((network, stat) in relstats) {
            println("network: $network has ${stat[0]} relations with ${stat[1]} ways")
        }
    }
}
