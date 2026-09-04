package com.danemadsen.atlas.beerouter.map.generator

import androidx.collection.MutableLongList
import com.google.protobuf.InvalidProtocolBufferException
import org.openstreetmap.osmosis.osmbinary.Fileformat
import org.openstreetmap.osmosis.osmbinary.Osmformat
import java.io.IOException
import java.util.Arrays
import java.util.zip.DataFormatException
import java.util.zip.Inflater

public class BPbfBlobDecoder(
    private val blobType: String,
    private val rawBlob: ByteArray,
    private val parser: OsmParser,
) {
    private var fromWid: MutableLongList? = null
    private var toWid: MutableLongList? = null
    private var viaNid: MutableLongList? = null

    public fun process() {
        when (blobType) {
            "OSMHeader" -> processOsmHeader(readBlobContent())
            "OSMData" -> processOsmPrimitives(readBlobContent())
            else -> println("Skipping unrecognised blob type $blobType")
        }
    }

    @Throws(IOException::class)
    private fun readBlobContent(): ByteArray {
        val blob = Fileformat.Blob.parseFrom(rawBlob)
        return when {
            blob.hasRaw() -> blob.raw.toByteArray()
            blob.hasZlibData() -> {
                val inflater = Inflater()
                inflater.setInput(blob.zlibData.toByteArray())
                val blobData = ByteArray(blob.rawSize)
                try {
                    inflater.inflate(blobData)
                } catch (e: DataFormatException) {
                    throw RuntimeException("Unable to decompress PBF blob.", e)
                }
                if (!inflater.finished()) {
                    throw RuntimeException("PBF blob contains incomplete compressed data.")
                }
                blobData
            }

            else -> throw RuntimeException("PBF blob uses unsupported compression, only raw or zlib may be used.")
        }
    }

    @Throws(InvalidProtocolBufferException::class)
    private fun processOsmHeader(data: ByteArray) {
        val header = Osmformat.HeaderBlock.parseFrom(data)
        val supportedFeatures = Arrays.asList("OsmSchema-V0.6", "DenseNodes")
        val unsupportedFeatures = ArrayList<String>()
        for (feature in header.requiredFeaturesList) {
            if (!supportedFeatures.contains(feature)) {
                unsupportedFeatures.add(feature)
            }
        }
        if (unsupportedFeatures.isNotEmpty()) {
            throw RuntimeException("PBF file contains unsupported features $unsupportedFeatures")
        }
    }

    private fun buildTags(
        keys: List<Int>,
        values: List<Int>,
        fieldDecoder: BPbfFieldDecoder
    ): Map<String, String>? {
        if (keys.isEmpty()) return null
        val tags = HashMap<String, String>()
        for (i in keys.indices) {
            tags[fieldDecoder.decodeString(keys[i])] = fieldDecoder.decodeString(values[i])
        }
        return tags
    }

    private fun processNodes(nodes: List<Osmformat.Node>, fieldDecoder: BPbfFieldDecoder) {
        for (node in nodes) {
            val tags = buildTags(node.keysList, node.valsList, fieldDecoder)
            parser.addNode(
                node.id,
                tags,
                fieldDecoder.decodeLatitude(node.lat),
                fieldDecoder.decodeLongitude(node.lon),
            )
        }
    }

    private fun processNodes(nodes: Osmformat.DenseNodes, fieldDecoder: BPbfFieldDecoder) {
        val keysValuesIterator = nodes.keysValsList.iterator()
        var nodeId = 0L
        var latitude = 0L
        var longitude = 0L
        for (i in nodes.idList.indices) {
            nodeId += nodes.idList[i]
            latitude += nodes.latList[i]
            longitude += nodes.lonList[i]
            var tags: MutableMap<String, String>? = null
            while (keysValuesIterator.hasNext()) {
                val keyIndex = keysValuesIterator.next()
                if (keyIndex == 0) break
                val valueIndex = keysValuesIterator.next()
                if (tags == null) {
                    tags = HashMap()
                }
                tags[fieldDecoder.decodeString(keyIndex)] = fieldDecoder.decodeString(valueIndex)
            }
            parser.addNode(
                nodeId,
                tags,
                latitude.toDouble() / 10_000_000.0,
                longitude.toDouble() / 10_000_000.0
            )
        }
    }

    private fun processWays(ways: List<Osmformat.Way>, fieldDecoder: BPbfFieldDecoder) {
        for (way in ways) {
            val tags = buildTags(way.keysList, way.valsList, fieldDecoder)
            val wayNodes = MutableLongList(16)
            var nodeId = 0L
            for (nodeIdOffset in way.refsList) {
                nodeId += nodeIdOffset
                wayNodes.add(nodeId)
            }
            parser.addWay(way.id, tags, wayNodes)
        }
    }

    private fun addLong(list: MutableLongList?, value: Long): MutableLongList {
        val actual = list ?: MutableLongList(1)
        actual.add(value)
        return actual
    }

    private fun buildRelationMembers(
        memberIds: List<Long>,
        memberRoles: List<Int>,
        memberTypes: List<Osmformat.Relation.MemberType>,
        fieldDecoder: BPbfFieldDecoder,
    ): MutableLongList {
        val wayIds = MutableLongList(16)
        fromWid = null
        toWid = null
        viaNid = null
        var refId = 0L
        for (i in memberIds.indices) {
            refId += memberIds[i]
            val role = fieldDecoder.decodeString(memberRoles[i])
            when (memberTypes[i]) {
                Osmformat.Relation.MemberType.WAY -> {
                    wayIds.add(refId)
                    if (role == "from") fromWid = addLong(fromWid, refId)
                    if (role == "to") toWid = addLong(toWid, refId)
                }

                Osmformat.Relation.MemberType.NODE -> {
                    if (role == "via") viaNid = addLong(viaNid, refId)
                }

                else -> Unit
            }
        }
        return wayIds
    }

    private fun processRelations(
        relations: List<Osmformat.Relation>,
        fieldDecoder: BPbfFieldDecoder
    ) {
        for (relation in relations) {
            val tags = buildTags(relation.keysList, relation.valsList, fieldDecoder)
            val wayIds = buildRelationMembers(
                relation.memidsList,
                relation.rolesSidList,
                relation.typesList,
                fieldDecoder
            )
            parser.addRelation(relation.id, tags, wayIds, fromWid, toWid, viaNid)
        }
    }

    @Throws(InvalidProtocolBufferException::class)
    private fun processOsmPrimitives(data: ByteArray) {
        val block = Osmformat.PrimitiveBlock.parseFrom(data)
        val fieldDecoder = BPbfFieldDecoder(block)
        for (primitiveGroup in block.primitivegroupList) {
            processNodes(primitiveGroup.dense, fieldDecoder)
            processNodes(primitiveGroup.nodesList, fieldDecoder)
            processWays(primitiveGroup.waysList, fieldDecoder)
            processRelations(primitiveGroup.relationsList, fieldDecoder)
        }
    }
}
