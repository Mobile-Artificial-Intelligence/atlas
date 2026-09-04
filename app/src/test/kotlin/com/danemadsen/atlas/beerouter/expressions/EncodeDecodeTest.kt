package com.danemadsen.atlas.beerouter.expressions

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertTrue

class EncodeDecodeTest {
    @Test
    fun encodeDecodeTest() {
        val profileDir = Path(
            System.getProperty("user.dir"),
            "src", "main", "kotlin", "com", "danemadsen", "atlas", "beerouter", "profiles2",
        )
        val lookupContent = javaClass.getResource("/lookups_test.dat")!!.readText()

        val meta = BExpressionMetaData()
        val expctxWay = BExpressionContextWay(meta)
        meta.readMetaData(lookupContent)
        expctxWay.parseProfile(
            SystemFileSystem.source(Path(profileDir, "trekking.brf")).buffered().use { it.readString() },
            "global"
        )

        val tags = arrayOf(
            "highway=residential",
            "oneway=yes",
            "depth=1'6\"",
            "maxheight=5.1m",
            "maxdraft=~3 m - 4 m",
            "reversedirection=yes"
        )

        val lookupData = expctxWay.createNewLookupData()!!
        for (arg in tags) {
            val idx = arg.indexOf('=')
            require(idx >= 0) { "bad argument (should be <tag>=<value>): $arg" }
            val key = arg.substring(0, idx)
            val value = arg.substring(idx + 1)
            expctxWay.addLookupValue(key, value, lookupData)
        }
        val description = expctxWay.encode(lookupData)!!

        expctxWay.evaluate(true, description)

        println("description: " + expctxWay.getKeyValueDescription(true, description))

        val costfactor = expctxWay.costfactor
        assertTrue(kotlin.math.abs(costfactor - 5.15f) < 0.00001f, "costfactor mismatch")
    }
}
