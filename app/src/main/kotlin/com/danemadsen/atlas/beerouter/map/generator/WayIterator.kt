package com.danemadsen.atlas.beerouter.map.generator

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream

public class WayIterator(
    private val listener: WayListener,
    private val deleteAfterReading: Boolean,
    private val descendingSize: Boolean = false
) : GeneratorBase() {
    public fun processDir(indir: File, inSuffix: String) {
        if (!indir.isDirectory) {
            throw IllegalArgumentException("not a directory: $indir")
        }
        val files = sortBySizeAsc(requireNotNull(indir.listFiles()))
        for (i in files.indices) {
            val wayfile = if (descendingSize) files[files.size - 1 - i] else files[i]
            if (wayfile.name.endsWith(inSuffix)) {
                processFile(wayfile)
            }
        }
    }

    public fun processFile(wayfile: File) {
        println("*** WayIterator reading: $wayfile")
        if (!listener.wayFileStart(wayfile)) {
            return
        }
        val di = DataInputStream(BufferedInputStream(FileInputStream(wayfile)))
        try {
            while (true) {
                listener.nextWay(WayData(di))
            }
        } catch (_: EOFException) {
            di.close()
        }
        listener.wayFileEnd(wayfile)
        if (deleteAfterReading) {
            wayfile.delete()
        }
    }
}
