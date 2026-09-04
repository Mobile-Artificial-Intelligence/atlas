package com.danemadsen.atlas.beerouter.map.generator

import androidx.collection.MutableLongSet
import com.danemadsen.atlas.beerouter.expressions.BExpressionContextWay
import com.danemadsen.atlas.beerouter.expressions.BExpressionMetaData
import java.io.DataInputStream
import java.io.EOFException
import java.io.File

public class RelationMerger : GeneratorBase() {
    private lateinit var routesets: MutableMap<String, MutableLongSet>
    private lateinit var routesetall: MutableLongSet
    private lateinit var expctxReport: BExpressionContextWay
    private lateinit var expctxCheck: BExpressionContextWay
    private var wayOutStream: DiffCoderDataOutputStream? = null

    public fun init(
        relationFileIn: File,
        lookupFile: File,
        reportProfile: File,
        checkProfile: File
    ) {
        val metaReport = BExpressionMetaData()
        expctxReport = BExpressionContextWay(metaReport)
        metaReport.readMetaData(lookupFile.readText())
        val metaCheck = BExpressionMetaData()
        expctxCheck = BExpressionContextWay(metaCheck)
        metaCheck.readMetaData(lookupFile.readText())
        expctxReport.parseProfile(reportProfile.readText(), "global")
        expctxCheck.parseProfile(checkProfile.readText(), "global")

        routesets = HashMap()
        routesetall = MutableLongSet()
        val dis: DataInputStream = createInStream(relationFileIn)
        try {
            while (true) {
                readId(dis)
                val route = dis.readUTF()
                val network = dis.readUTF()
                val state = dis.readUTF()
                val value = if (state == "proposed") 3 else 2
                val tagname = "route_${route}_$network"
                var routeset: MutableLongSet? = null
                if (expctxCheck.getLookupNameIdx(tagname) >= 0) {
                    val key = "${tagname}_$value"
                    routeset = routesets[key]
                    if (routeset == null) {
                        routeset = MutableLongSet()
                        routesets[key] = routeset
                    }
                }
                while (true) {
                    val wid = readId(dis)
                    if (wid == -1L) break
                    if (routeset != null && !routeset.contains(wid)) {
                        routeset.add(wid)
                        routesetall.add(wid)
                    }
                }
            }
        } catch (_: EOFException) {
            dis.close()
        }
    }

    public fun process(
        wayFileIn: File,
        wayFileOut: File,
        relationFileIn: File,
        lookupFile: File,
        reportProfile: File,
        checkProfile: File
    ) {
        init(relationFileIn, lookupFile, reportProfile, checkProfile)
        wayOutStream = createOutStream(wayFileOut)
        WayIterator(this, true).processFile(wayFileIn)
        wayOutStream?.close()
    }

    public override fun nextWay(data: WayData) {
        if (routesetall.contains(data.wid)) {
            var ok = true
            expctxReport.evaluate(false, requireNotNull(data.description))
            val warn = expctxReport.costfactor >= 10000f
            if (warn) {
                expctxCheck.evaluate(false, requireNotNull(data.description))
                ok = expctxCheck.costfactor < 10000f
                println(
                    "** relation access conflict for wid = ${data.wid} tags:${
                        expctxReport.getKeyValueDescription(
                            false,
                            requireNotNull(data.description)
                        )
                    } (ok=$ok)"
                )
            }
            if (ok) {
                expctxReport.decode(requireNotNull(data.description))
                for ((key, routeset) in routesets) {
                    if (routeset.contains(data.wid)) {
                        val sepIdx = key.lastIndexOf('_')
                        val tagname = key.substring(0, sepIdx)
                        val value = key.substring(sepIdx + 1).toInt()
                        expctxReport.addSmallestLookupValue(tagname, value)
                    }
                }
                data.description = expctxReport.encode()
            }
        }
        wayOutStream?.let { data.writeTo(it) }
    }
}
