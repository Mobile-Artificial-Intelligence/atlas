package com.danemadsen.atlas.beerouter.map.generator

import androidx.collection.MutableLongObjectMap
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.zip.GZIPInputStream

public class DatabasePseudoTagProvider(filename: String?, jdbcurl: String?) {
    private var cntOsmWays: Long = 0
    private var cntWayModified: Long = 0
    private val pseudoTagsFound: MutableMap<String, Long> = HashMap()
    private var dbData: MutableLongObjectMap<Map<String, String>>? = null

    init {
        if (filename != null) doFileImport(filename)
        if (jdbcurl != null) doDatabaseImport(jdbcurl)
    }

    private fun doDatabaseImport(jdbcurl: String) {
        DriverManager.getConnection(jdbcurl).use { conn ->
            println("DatabasePseudoTagProvider reading from database: $jdbcurl")
            conn.autoCommit = false
            val mapUnifier: MutableMap<Map<String, String>, Map<String, String>> = HashMap()
            val data = MutableLongObjectMap<Map<String, String>>()
            conn.prepareStatement("SELECT * from all_tags").use { ps ->
                ps.fetchSize = 100
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val osmId = rs.getLong("losmid")
                        var row: MutableMap<String, String> = HashMap(5)
                        addDBTag(row, rs, "noise_class")
                        addDBTag(row, rs, "river_class")
                        addDBTag(row, rs, "forest_class")
                        addDBTag(row, rs, "town_class")
                        addDBTag(row, rs, "traffic_class")
                        row = mapUnifier.getOrPut(row) { row }.toMutableMap()
                        data[osmId] = row
                    }
                }
            }
            dbData = data
        }
    }

    private fun doFileImport(filename: String) {
        val input =
            if (filename.endsWith(".gz")) GZIPInputStream(FileInputStream(filename)) else FileInputStream(
                filename
            )
        BufferedReader(InputStreamReader(input)).use { br ->
            println("DatabasePseudoTagProvider reading from file: $filename")
            br.readLine()
            val mapUnifier: MutableMap<Map<String, String>, Map<String, String>> = HashMap()
            val data = MutableLongObjectMap<Map<String, String>>()
            while (true) {
                val line = br.readLine() ?: break
                val tokens = tokenize(line)
                val osmId = tokens[0].toLong()
                var row: MutableMap<String, String> = HashMap(5)
                addTag(row, tokens[1], "estimated_noise_class")
                addTag(row, tokens[2], "estimated_river_class")
                addTag(row, tokens[3], "estimated_forest_class")
                addTag(row, tokens[4], "estimated_town_class")
                addTag(row, tokens[5], "estimated_traffic_class")
                row = mapUnifier.getOrPut(row) { row }.toMutableMap()
                data[osmId] = row
            }
            dbData = data
        }
    }

    private fun tokenize(s: String): List<String> {
        val result = ArrayList<String>()
        val sb = StringBuilder()
        for (c in s) {
            if (c == ';') {
                result.add(sb.toString())
                sb.setLength(0)
            } else {
                sb.append(c)
            }
        }
        result.add(sb.toString())
        return result
    }

    public fun addTags(osmId: Long, map: MutableMap<String, String>?) {
        if (map == null || !map.containsKey("highway")) return
        cntOsmWays++
        val dbTags = dbData?.get(osmId) ?: return
        cntWayModified++
        for ((key, value) in dbTags) {
            map[key] = value
            pseudoTagsFound[key] = (pseudoTagsFound[key] ?: 0L) + 1L
        }
        if ((cntOsmWays % 1_000_000L) == 0L) {
            var out = "Osm Ways processed=$cntOsmWays way modifs=$cntWayModified"
            for ((key, value) in pseudoTagsFound) {
                out += " $key=$value"
            }
            println(out)
        }
    }

    private companion object {
        private fun addTag(row: MutableMap<String, String>, s: String, name: String) {
            if (s.isNotEmpty()) {
                row[name] = s
            }
        }

        private fun addDBTag(row: MutableMap<String, String>, rs: ResultSet, name: String) {
            val v = try {
                rs.getString(name)
            } catch (_: Exception) {
                null
            }
            if (v != null) {
                row["estimated_$name"] = v
            }
        }
    }
}
