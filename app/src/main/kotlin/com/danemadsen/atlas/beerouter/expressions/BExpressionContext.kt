package com.danemadsen.atlas.beerouter.expressions

import androidx.collection.LruCache
import com.danemadsen.atlas.beerouter.util.BitCoderContext
import com.danemadsen.atlas.beerouter.util.Crc32.crc
import com.danemadsen.atlas.beerouter.util.IByteArrayUnifier
import kotlin.math.abs
import kotlin.random.Random
import com.danemadsen.atlas.beerouter.router.exceptions.CacheStateException
import com.danemadsen.atlas.beerouter.router.exceptions.ExpressionParseException

public abstract class BExpressionContext protected constructor(
    context: String?,
    hashSize: Int,
    meta: BExpressionMetaData
) : IByteArrayUnifier {
    private var context: String?
    private var _inOurContext = false
    private var _content: String = ""
    private var _contentPos: Int = 0
    private var _readerDone = false

    public var _modelClass: String? = null

    private val lookupNumbers: MutableMap<String?, Int?> = HashMap()
    private val lookupValues: MutableList<Array<BExpressionLookupValue?>?> = mutableListOf()
    private val lookupNames: MutableList<String?> = mutableListOf()
    private val lookupHistograms: MutableList<IntArray> = mutableListOf()
    private lateinit var lookupIdxUsed: BooleanArray

    private var lookupDataFrozen = false

    private var lookupData = IntArray(0)

    private val abBuf = ByteArray(256)
    private val ctxEndode = BitCoderContext(abBuf)
    private val ctxDecode = BitCoderContext(ByteArray(0))

    private val variableNumbers: MutableMap<String?, Int?> = HashMap()

    internal var lastAssignedExpression: MutableList<BExpression?>? = mutableListOf()

    public var skipConstantExpressionOptimizations: Boolean = false

    public var expressionNodeCount: Int = 0

    private var variableData: FloatArray? = null

    private var cache: LruCache<ByteArrayKeyBase, CacheNode>? = null
    private var identityCache: LruCache<ByteArray, CacheNode>? = null

    private val probeVarSet = VarWrapper()
    private val byteArrayProbeKey = ProbeByteArrayKey()

    private var resultVarCache: LruCache<FloatArrayKey, VarWrapper>? = null
    private var expressionList: MutableList<BExpression?>? = null

    public var minWriteIdx: Int = 0
        private set

    // build-in variable indexes for fast access
    private lateinit var buildInVariableIdx: IntArray
    private var nBuildInVars = 0

    private var currentVars: FloatArray? = null
    private var currentVarOffset = 0

    private var foreignContext: BExpressionContext? = null

    public var noStartWays: IntArray = IntArray(0)

    protected fun setInverseVars() {
        currentVarOffset = nBuildInVars
    }

    public abstract val buildInVariableNames: Array<String?>

    public fun getBuildInVariable(idx: Int): Float {
        return currentVars!![idx + currentVarOffset]
    }

    private var linenr = 0

    public var meta: BExpressionMetaData
    private var lookupDataValid = false

    protected constructor(context: String?, meta: BExpressionMetaData) : this(context, 4096, meta)

    /**
     * encode internal lookup data to a byte array
     *
     * @throws IllegalArgumentException if lookup data is not valid for encoding
     */
    public fun encode(): ByteArray? {
        require(lookupDataValid) { "internal error: encoding undefined data?" }
        return encode(lookupData)
    }

    /**
     * encode a given lookup data array to a byte array
     *
     * @param ld the lookup data array
     * @return the encoded byte array
     * @throws IllegalStateException if encoding verification fails
     */
    public fun encode(ld: IntArray): ByteArray? {
        val ctx = ctxEndode
        ctx.reset()

        var skippedTags = 0
        var nonNullTags = 0

        for (inum in 1..<lookupValues.size) {
            val d = ld[inum]
            if (d == 0) {
                skippedTags++
                continue
            }
            ctx.encodeVarBits(skippedTags + 1)
            nonNullTags++
            skippedTags = 0

            val dd = if (d < 2) 7 else (if (d < 9) d - 2 else d - 1)
            ctx.encodeVarBits(dd)
        }
        ctx.encodeVarBits(0)

        if (nonNullTags == 0) return null

        val len = ctx.closeAndGetEncodedLength()
        val ab = ByteArray(len)
        abBuf.copyInto(ab, 0, 0, len)

        // crosscheck: decode and compare
        val ld2 = IntArray(lookupValues.size)
        decode(ld2, false, ab)
        for (inum in 1..<lookupValues.size) {
            if (ld2[inum] != ld[inum]) throw CacheStateException(
                "assertion failed encoding inum=" + inum + " val=" + ld[inum] + " " + getKeyValueDescription(
                    false,
                    ab
                )
            )
        }

        return ab
    }

    /**
     * decode byte array to internal lookup data
     *
     * @param ab the byte array to decode
     */
    public fun decode(ab: ByteArray) {
        decode(lookupData, false, ab)
        lookupDataValid = true
    }

    /**
     * decode a byte-array into a lookup data array
     *
     * @param ld the lookup data array
     * @param inverseDirection whether to decode in inverse direction
     * @param ab the byte array to decode
     */
    public fun decode(ld: IntArray, inverseDirection: Boolean, ab: ByteArray) {
        val ctx = ctxDecode
        ctx.reset(ab)

        ld[0] = if (inverseDirection) 2 else 0

        var inum = 1
        while (true) {
            var delta = ctx.decodeVarBits()
            if (delta == 0) break
            if (inum + delta > ld.size) break

            while (delta-- > 1) ld[inum++] = 0
            val dd = ctx.decodeVarBits()
            var d = if (dd == 7) 1 else (if (dd < 7) dd + 2 else dd + 1)
            if (d >= lookupValues[inum]!!.size && d < 1000) d = 1

            ld[inum++] = d
        }
        while (inum < ld.size) ld[inum++] = 0
    }

    /**
     * Get a description of the key-value pairs in the given byte array.
     *
     * @param inverseDirection whether to decode in inverse direction
     * @param ab the byte array to decode
     * @return a string description of the key-value pairs
     */
    public fun getKeyValueDescription(inverseDirection: Boolean, ab: ByteArray): String {
        decode(lookupData, inverseDirection, ab)
        return lookupValues.indices.mapNotNull { inum ->
            val va = lookupValues[inum]!!
            val `val` = lookupData[inum]
            val value = if (`val` >= 1000) ((`val` - 1000) / 100f).toString() else va[`val`].toString()
            if (value.isNotEmpty()) "${lookupNames[inum]}=$value" else null
        }.joinToString(" ")
    }

    /**
     * Get a map of key-value pairs from the given byte array.
     *
     * @param inverseDirection whether to decode in inverse direction
     * @param ab the byte array to decode
     * @return a map of key-value pairs
     */
    public fun getMap(inverseDirection: Boolean, ab: ByteArray): Map<String, String> {
        val res: MutableMap<String, String> = mutableMapOf()
        decode(lookupData, inverseDirection, ab)
        for (inum in lookupValues.indices) {
            val va = lookupValues[inum]!!
            val `val` = lookupData[inum]
            val value: String? =
                if (`val` >= 1000) ((`val` - 1000) / 100f).toString() else va[`val`].toString()
            if (value != null && value.isNotEmpty()) {
                res[lookupNames[inum]!!] = value
            }
        }
        return res
    }

    /**
     * Get the lookup key index for the given name.
     *
     * @param name the lookup name
     * @return the lookup key index, or -1 if not found
     */
    public fun getLookupKey(name: String?): Int {
        return try {
            lookupNumbers[name]!!
        } catch (_: NullPointerException) {
            -1
        }
    }

    /**
     * Get the lookup value for the given key.
     *
     * @param key the lookup key index
     * @return the lookup value as a float, or NaN if not found
     */
    public fun getLookupValue(key: Int): Float {
        val `val` = lookupData[key]
        if (`val` == 0) return Float.NaN
        return (`val` - 1000) / 100f
    }

    /**
     * Get the lookup value for the given key from a byte array.
     *
     * @param inverseDirection whether to decode in inverse direction
     * @param ab the byte array to decode
     * @param key the lookup key index
     * @return the lookup value as a float, or NaN if not found
     */
    public fun getLookupValue(inverseDirection: Boolean, ab: ByteArray, key: Int): Float {
        decode(lookupData, inverseDirection, ab)
        val `val` = lookupData[key]
        if (`val` == 0) return Float.NaN
        return (`val` - 1000) / 100f
    }

    private var parsedLines = 0
    private var fixTagsWritten = false

    public fun parseMetaLine(line: String) {
        parsedLines++
        val tokens = parseMetaTokens(line) ?: return
        var name = tokens.name
        val value = tokens.value
        val idx = name.indexOf(';')
        if (idx >= 0) name = name.take(idx)

        if (!fixTagsWritten) {
            fixTagsWritten = true
            if ("way" == context) addLookupValue("reversedirection", "yes", null)
            else if ("node" == context) addLookupValue("nodeaccessgranted", "yes", null)
        }
        if ("reversedirection" == name) return
        if ("nodeaccessgranted" == name) return

        val newValue = addLookupValue(name, value, null)

        // add aliases
        tokens.aliases?.forEach { alias ->
            newValue?.addAlias(alias)
        }
    }

    public fun finishMetaParsing() {
        require(!(parsedLines == 0 && "global" != context)) { "lookup table does not contain data for context $context (old version?)" }

        lookupDataFrozen = true
        lookupIdxUsed = BooleanArray(lookupValues.size)
    }

    /**
     * Evaluate the expressions with the given lookup data.
     *
     * @param lookupData2 the lookup data array
     * @throws IllegalStateException if the expression list is not initialized
     */
    public fun evaluate(lookupData2: IntArray) {
        lookupData = lookupData2
        evaluate()
    }

    private fun evaluate() {
        val expressions = requireNotNull(expressionList)
        val n = expressions.size
        for (expidx in 0..<n) {
            requireNotNull(expressions[expidx]).evaluate(this)
        }
    }

    private var lastCacheNode: CacheNode = CacheNode()

    private sealed class ByteArrayKeyBase {
        abstract val hash: Int

        abstract fun contentEqualsStored(bytes: ByteArray): Boolean

        override fun hashCode(): Int = hash
    }

    private class StoredByteArrayKey(
        override val hash: Int,
        val bytes: ByteArray,
    ) : ByteArrayKeyBase() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            val o = other as? ByteArrayKeyBase ?: return false
            return hash == o.hash && o.contentEqualsStored(bytes)
        }

        override fun contentEqualsStored(bytes: ByteArray): Boolean = this.bytes.contentEquals(bytes)
    }

    private class ProbeByteArrayKey : ByteArrayKeyBase() {
        override var hash: Int = 0
        private var bytes: ByteArray = ByteArray(0)
        private var offset: Int = 0
        private var len: Int = 0

        fun reset(hash: Int, bytes: ByteArray, offset: Int, len: Int): ProbeByteArrayKey {
            this.hash = hash
            this.bytes = bytes
            this.offset = offset
            this.len = len
            return this
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            val stored = other as? StoredByteArrayKey ?: return false
            if (hash != stored.hash || len != stored.bytes.size) return false
            for (i in 0..<len) {
                if (bytes[offset + i] != stored.bytes[i]) return false
            }
            return true
        }

        override fun contentEqualsStored(bytes: ByteArray): Boolean {
            if (len != bytes.size) return false
            for (i in 0..<len) {
                if (this.bytes[offset + i] != bytes[i]) return false
            }
            return true
        }
    }

    private data class FloatArrayKey(val hash: Int, val values: FloatArray) {
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            val o = other as? FloatArrayKey ?: return false
            return hash == o.hash && values.contentEquals(o.values)
        }
    }

    override fun unify(ab: ByteArray, offset: Int, len: Int): ByteArray? {
        val hash = crc(ab, offset, len)
        val cache = requireNotNull(cache)
        val cn = cache[byteArrayProbeKey.reset(hash, ab, offset, len)]
        if (cn != null) {
            lastCacheNode = cn
            return cn.ab
        }
        val probe = ByteArray(len)
        ab.copyInto(probe, 0, offset, offset + len)
        return probe
    }

    public fun evaluate(inverseDirection: Boolean, ab: ByteArray) {
        lookupDataValid = false

        if (cache == null) {
            decode(lookupData, inverseDirection, ab)
            if (currentVars == null || requireNotNull(currentVars).size != nBuildInVars) {
                currentVars = FloatArray(nBuildInVars)
            }
            evaluateInto(requireNotNull(currentVars), 0)
            currentVarOffset = 0
            return
        }

        val cache = requireNotNull(cache)
        val identityCache = requireNotNull(identityCache)
        val resultVarCache = requireNotNull(resultVarCache)

        val cn: CacheNode = if (lastCacheNode.ab === ab) {
            lastCacheNode
        } else {
            val identityHit = identityCache[ab]
            if (identityHit != null) {
                identityHit
            } else {
                val hash = crc(ab, 0, ab.size)
                val key = StoredByteArrayKey(hash, ab)
                val existing = cache[key]

                if (existing == null) {
                    val cn = CacheNode(ab = ab, hash = hash)
                    cache.put(key, cn)

                    if (probeVarSet.vars == null) {
                        probeVarSet.vars = FloatArray(2 * nBuildInVars)
                    }
                    val probeVars = requireNotNull(probeVarSet.vars)

                    decode(lookupData, false, ab)
                    evaluateInto(probeVars, 0)

                    lookupData[0] = 2
                    evaluateInto(probeVars, nBuildInVars)

                    probeVarSet.hash = probeVars.contentHashCode()

                    val probeVarKey = FloatArrayKey(probeVarSet.hash, probeVars)
                    var vw = resultVarCache[probeVarKey]

                    if (vw == null) {
                        vw = VarWrapper(vars = probeVars, hash = probeVarSet.hash)
                        probeVarSet.vars = null
                        resultVarCache.put(FloatArrayKey(vw.hash, requireNotNull(vw.vars)), vw)
                    }
                    cn.vars = vw.vars
                    identityCache.put(ab, cn)
                    cn
                } else {
                    identityCache.put(ab, existing)
                    existing
                }
            }
        }
        // Update for fast identity check on the next call with the same ab
        lastCacheNode = cn

        currentVars = cn.vars
        currentVarOffset = if (inverseDirection) nBuildInVars else 0
    }

    private fun evaluateInto(vars: FloatArray, offset: Int) {
        evaluate()
        val variableData = requireNotNull(variableData)
        for (vi in 0..<nBuildInVars) {
            val idx = buildInVariableIdx[vi]
            vars[vi + offset] = if (idx == -1) 0f else variableData[idx]
        }
    }

    /**
     * @return a new lookupData array, or null if no metadata defined
     * @throws IllegalStateException if lookup data is not frozen
     */
    public fun createNewLookupData(): IntArray? {
        if (lookupDataFrozen) {
            return IntArray(lookupValues.size)
        }
        return null
    }

    /**
     * generate random values for regression testing
     *
     * @param rnd the random number generator
     * @return a new lookup data array with random values
     * @throws IllegalStateException if lookup data is not frozen
     */
    public fun generateRandomValues(rnd: Random): IntArray {
        val data = createNewLookupData()!!
        data[0] = 2 * rnd.nextInt(2)
        for (inum in 1..<data.size) {
            val nvalues = lookupValues[inum]!!.size
            data[inum] = 0
            if (inum > 1 && rnd.nextInt(10) > 0) continue

            data[inum] = rnd.nextInt(nvalues)
        }
        lookupDataValid = true
        return data
    }

    /**
     * Assert that all variables are equal to the given context.
     *
     * @param other the other expression context
     * @throws IllegalStateException if variable counts or values mismatch
     */
    public fun assertAllVariablesEqual(other: BExpressionContext) {
        val variableData = requireNotNull(variableData)
        val otherVariableData = requireNotNull(other.variableData)
        val nv = variableData.size
        val nv2 = otherVariableData.size
        if (nv != nv2) throw CacheStateException("mismatch in variable-count: $nv<->$nv2")
        for (i in 0..<nv) {
            if (variableData[i] != otherVariableData[i]) {
                throw CacheStateException(
                    "mismatch in variable " + variableName(i) + " " + variableData[i] + "<->" + otherVariableData[i]
                            + "\ntags = " + getKeyValueDescription(false, requireNotNull(encode()))
                )
            }
        }
    }

    /**
     * Get the variable name for the given index.
     *
     * @param idx the variable index
     * @return the variable name
     * @throws IllegalStateException if no variable exists for the given index
     */
    public fun variableName(idx: Int): String? {
        for (e in variableNumbers.entries) {
            if (e.value == idx) {
                return e.key
            }
        }
        throw CacheStateException("no variable for index$idx")
    }

    /**
     * add a new lookup-value for the given name to the given lookupData array.
     *
     * @param name the lookup name
     * @param value the lookup value
     * @param lookupData2 the lookup data array, or null
     * @return the new lookup value, or null if not added
     * @throws NumberFormatException if value parsing fails for numeric conversions
     */
    public fun addLookupValue(
        name: String?,
        value: String,
        lookupData2: IntArray?
    ): BExpressionLookupValue? {
        var value = value
        var newValue: BExpressionLookupValue? = null
        var num = lookupNumbers[name]
        if (num == null) {
            if (lookupData2 != null) {
                return newValue
            }

            num = lookupValues.size
            lookupNumbers[name] = num
            lookupNames.add(name)
            lookupValues.add(
                arrayOf(
                    BExpressionLookupValue(""),
                    BExpressionLookupValue("unknown")
                )
            )
            lookupHistograms.add(IntArray(2))
            val ndata = IntArray(lookupData.size + 1)
            lookupData.copyInto(ndata, 0, 0, lookupData.size)
            lookupData = ndata
        }

        var values = lookupValues[num]
        var histo = lookupHistograms[num]
        var i = 0
        var bFoundAsterix = false
        while (i < values!!.size) {
            val v = values[i]
            if (v!!.value == "*") bFoundAsterix = true
            if (v.matches(value)) break
            i++
        }
        if (i == values.size) {
            if (lookupData2 != null) {
                lookupData2[num] = 1
                if (bFoundAsterix) {
                    val org: String = value
                    try {
                        value = value.replace(",", ".")
                        value = value.replace(">", "")
                        value = value.replace("_", "")
                        value = value.replace(" ", "")
                        value = value.replace("~", "")
                        value = value.replace(8217.toChar(), '\'')
                        value = value.replace(8221.toChar(), '"')
                        if (value.indexOf("-") == 0) value = value.substring(1)
                        if (value.contains("-")) {
                            val tmp = value.substring(value.indexOf("-") + 1)
                                .replace("[0-9.,-]".toRegex(), "")
                            value = value.take(value.indexOf("-"))
                            if (value.matches("\\d+(\\.\\d+)?".toRegex())) value += tmp
                        }
                        value = value.lowercase()

                        if (value.contains("ft")) {
                            var feet = 0f
                            var inch: Int
                            val sa = value.split("ft").dropLastWhile { it.isEmpty() }.toTypedArray()
                            if (sa.isNotEmpty()) feet = sa[0].toFloat()
                            if (sa.size == 2) {
                                value = sa[1]
                                if (value.indexOf("in") > 0) value = value.substringBefore("in")
                                inch = value.toInt()
                                feet += inch / 12f
                            }
                            value = formatOneDecimal(feet * 0.3048f)
                        } else if (value.contains("'")) {
                            var feet = 0f
                            var inch: Int
                            val sa = value.split("'").dropLastWhile { it.isEmpty() }.toTypedArray()
                            if (sa.isNotEmpty()) feet = sa[0].toFloat()
                            if (sa.size == 2) {
                                value = sa[1]
                                if (value.indexOf("''") > 0) value = value.substringBefore("''")
                                if (value.indexOf("\"") > 0) value = value.substringBefore("\"")
                                inch = value.toInt()
                                feet += inch / 12f
                            }
                            value = formatOneDecimal(feet * 0.3048f)
                        } else if (value.contains("in") || value.contains("\"")) {
                            if (value.indexOf("in") > 0) value = value.substringBefore("in")
                            if (value.indexOf("\"") > 0) value = value.substringBefore("\"")
                            val inch: Float = value.toFloat()
                            value = formatOneDecimal(inch * 0.0254f)
                        } else if (value.contains("feet") || value.contains("foot")) {
                            val s = value.substringBefore("f")
                            val feet = s.toFloat()
                            value = formatOneDecimal(feet * 0.3048f)
                        } else if (value.contains("fathom") || value.contains("fm")) {
                            val s = value.substringBefore("f")
                            val fathom = s.toFloat()
                            value = formatOneDecimal(fathom * 1.8288f)
                        } else if (value.contains("cm")) {
                            val sa = value.split("cm").dropLastWhile { it.isEmpty() }.toTypedArray()
                            if (sa.isNotEmpty()) value = sa[0]
                            val cm = value.toFloat()
                            value = formatOneDecimal(cm / 100f)
                        } else if (value.contains("meter")) {
                            value = value.substringBefore("m")
                        } else if (value.contains("mph")) {
                            val sa =
                                value.split("mph").dropLastWhile { it.isEmpty() }.toTypedArray()
                            if (sa.isNotEmpty()) value = sa[0]
                            val mph = value.toFloat()
                            value = formatOneDecimal(mph * 1.609344f)
                        } else if (value.contains("knot")) {
                            val sa =
                                value.split("knot").dropLastWhile { it.isEmpty() }.toTypedArray()
                            if (sa.isNotEmpty()) value = sa[0]
                            val nm = value.toFloat()
                            value = formatOneDecimal(nm * 1.852f)
                        } else if (value.contains("kmh") || value.contains("km/h") || value.contains(
                                "kph"
                            )
                        ) {
                            val sa = value.split("k").dropLastWhile { it.isEmpty() }.toTypedArray()
                            if (sa.size > 1) value = sa[0]
                        } else if (value.contains("m")) {
                            value = value.substringBefore("m")
                        } else if (value.contains("(")) {
                            value = value.substringBefore("(")
                        }
                        lookupData2[num] = 1000 + (abs(value.toFloat()) * 100f).toInt()
                    } catch (e: NumberFormatException) {
                        println("error for $name  $org trans $value ${e.message}")
                        lookupData2[num] = 0
                    }
                }
                return null
            }

            if (i == 500) {
                return null
            }
            val nvalues: Array<BExpressionLookupValue?> = arrayOfNulls(values.size + 1)
            val nhisto = IntArray(values.size + 1)
            values.copyInto(nvalues, 0, 0, values.size)
            histo.copyInto(nhisto, 0, 0, histo.size)
            values = nvalues
            histo = nhisto
            newValue = BExpressionLookupValue(value)
            values[i] = newValue
            lookupHistograms[num] = histo
            lookupValues[num] = values
        }

        histo[i]++

        if (lookupData2 != null) lookupData2[num] = i
        else lookupData[num] = i
        return newValue
    }

    /**
     * add a value-index to internal array
     *
     * @param name the lookup name
     * @param valueIndex the value index
     * @throws IllegalArgumentException if the value index is out of range
     */
    public fun addLookupValue(name: String?, valueIndex: Int) {
        val num = lookupNumbers[name] ?: return

        val nvalues = lookupValues[num]!!.size
        require(valueIndex in 0..<nvalues) { "value index out of range for name $name: $valueIndex" }
        lookupData[num] = valueIndex
    }

    /**
     * special hack for yes/proposed relations
     *
     * @param name the lookup name
     * @param valueIndex the value index
     * @throws IllegalArgumentException if the value index is out of range
     */
    public fun addSmallestLookupValue(name: String?, valueIndex: Int) {
        var valueIndex = valueIndex
        val num = lookupNumbers[name] ?: return

        val nvalues = lookupValues[num]!!.size
        val oldValueIndex = lookupData[num]
        if (oldValueIndex in 2..<valueIndex) {
            return
        }
        if (valueIndex >= nvalues) {
            valueIndex = nvalues - 1
        }
        require(valueIndex >= 0) { "value index out of range for name $name: $valueIndex" }
        lookupData[num] = valueIndex
    }

    /**
     * Get a boolean lookup value.
     *
     * @param name the lookup name
     * @return true if the lookup value equals 2
     */
    public fun getBooleanLookupValue(name: String?): Boolean {
        val num = lookupNumbers[name]
        return num != null && lookupData[num] == 2
    }

    /**
     * Get the output variable index for the given name.
     *
     * @param name the variable name
     * @param mustExist whether the variable must exist
     * @return the output variable index
     * @throws IllegalArgumentException if the variable does not exist and mustExist is true, or if accessing a global variable incorrectly
     */
    public fun getOutputVariableIndex(name: String?, mustExist: Boolean): Int {
        val idx = getVariableIdx(name, false)
        if (idx < 0) {
            require(!mustExist) { "unknown variable: $name" }
        } else require(idx >= minWriteIdx) { "bad access to global variable: $name" }
        for (i in 0..<nBuildInVars) {
            if (buildInVariableIdx[i] == idx) {
                return i
            }
        }
        val extended = IntArray(nBuildInVars + 1)
        buildInVariableIdx.copyInto(extended, 0, 0, nBuildInVars)
        extended[nBuildInVars] = idx
        buildInVariableIdx = extended
        return nBuildInVars++
    }

    /**
     * Set the foreign context for this expression context.
     *
     * @param foreignContext the foreign context
     */
    public fun setForeignContext(foreignContext: BExpressionContext) {
        this.foreignContext = foreignContext
    }

    /**
     * Get the value of a foreign variable.
     *
     * @param foreignIndex the foreign variable index
     * @return the foreign variable value
     * @throws NullPointerException if no foreign context is set
     */
    public fun getForeignVariableValue(foreignIndex: Int): Float {
        return foreignContext!!.getBuildInVariable(foreignIndex)
    }

    /**
     * Get the index of a foreign variable.
     *
     * @param context the foreign context name
     * @param name the variable name
     * @return the foreign variable index
     * @throws IllegalArgumentException if the foreign context is not set or mismatched
     */
    public fun getForeignVariableIdx(context: String, name: String?): Int {
        require(!(foreignContext == null || context != foreignContext!!.context)) { "unknown foreign context: $context" }
        return foreignContext!!.getOutputVariableIndex(name, true)
    }

    /**
     * Parse a profile from the given content.
     *
     * @param content the profile content
     * @param readOnlyContext the read-only context, or null
     * @param keyValues the key-value pairs to inject
     * @throws ExpressionParseException if parsing fails
     * @throws IllegalArgumentException if the profile is empty or invalid
     */
    public fun parseProfile(
        content: String,
        readOnlyContext: String?,
        keyValues: Map<String, String> = emptyMap()
    ) {
        try {
            if (readOnlyContext != null) {
                linenr = 1
                val realContext = context
                context = readOnlyContext
                expressionList = _parseContent(content, keyValues)
                variableData = FloatArray(variableNumbers.size)
                evaluate(lookupData)
                context = realContext
            }
            linenr = 1
            minWriteIdx = variableData?.size ?: 0
            expressionList = _parseContent(content, emptyMap())
            lastAssignedExpression = null

            val varNames = this.buildInVariableNames
            nBuildInVars = varNames.size
            buildInVariableIdx = IntArray(nBuildInVars)
            for (vi in varNames.indices) {
                buildInVariableIdx[vi] = getVariableIdx(varNames[vi], false)
            }

            val readOnlyData = variableData
            variableData = FloatArray(variableNumbers.size)
            for (i in 0..<minWriteIdx) {
                requireNotNull(variableData)[i] = requireNotNull(readOnlyData)[i]
            }
        } catch (e: IllegalArgumentException) {
            throw ExpressionParseException("ParseException at line $linenr: ${e.message}", e)
        } catch (e: Exception) {
            throw ExpressionParseException("ParseException at line $linenr", e)
        }
        require(requireNotNull(expressionList).isNotEmpty()) {
            "Profile does not contain expressions for context $context (old version?)"
        }
    }

    private fun _parseContent(
        content: String,
        keyValues: Map<String, String>
    ): MutableList<BExpression?> {
        _content = content
        _contentPos = 0
        _readerDone = false
        val result = mutableListOf<BExpression?>()

        for ((key, value) in keyValues) {
            result.add(BExpression.createAssignExpressionFromKeyValue(this, key, value))
        }

        while (true) {
            val exp: BExpression = BExpression.parse(this, 0) ?: break
            result.add(exp)
        }
        return result
    }

    /**
     * Set the value of a variable.
     *
     * @param name the variable name
     * @param value the value to set
     * @param create whether to create the variable if it does not exist
     */
    public fun setVariableValue(name: String?, value: Float, create: Boolean) {
        var num = variableNumbers[name]
        if (num != null) {
            requireNotNull(variableData)[num] = value
        } else if (create) {
            num = getVariableIdx(name, true)
            val readOnlyData = variableData
            val minWriteIdx = requireNotNull(readOnlyData).size
            variableData = FloatArray(variableNumbers.size)
            for (i in 0..<minWriteIdx) {
                requireNotNull(variableData)[i] = readOnlyData[i]
            }
            requireNotNull(variableData)[num] = value
        }
    }

    /**
     * Get the value of a variable, or a default value if not found.
     *
     * @param name the variable name
     * @param defaultValue the default value to return if not found
     * @return the variable value, or the default value
     */
    public fun getVariableValue(name: String?, defaultValue: Float): Float {
        val num = variableNumbers[name]
        return if (num == null) defaultValue else getVariableValue(num)
    }

    /**
     * Get the value of a variable by index.
     *
     * @param variableIdx the variable index
     * @return the variable value
     */
    public fun getVariableValue(variableIdx: Int): Float {
        return requireNotNull(variableData)[variableIdx]
    }

    /**
     * Get the index of a variable.
     *
     * @param name the variable name
     * @param create whether to create the variable if it does not exist
     * @return the variable index, or -1 if not found and create is false
     */
    public fun getVariableIdx(name: String?, create: Boolean): Int {
        var num = variableNumbers[name]
        if (num == null) {
            if (create) {
                num = variableNumbers.size
                variableNumbers[name] = num
                requireNotNull(lastAssignedExpression).add(null)
            } else {
                return -1
            }
        }
        return num
    }

    /**
     * Get the lookup match for the given name index and value index array.
     *
     * @param nameIdx the name index
     * @param valueIdxArray the value index array
     * @return 1.0f if a match is found, 0.0f otherwise
     */
    public fun getLookupMatch(nameIdx: Int, valueIdxArray: IntArray): Float {
        for (i in valueIdxArray.indices) {
            if (lookupData[nameIdx] == valueIdxArray[i]) {
                return 1.0f
            }
        }
        return 0.0f
    }

    /**
     * Get the lookup name index for the given name.
     *
     * @param name the lookup name
     * @return the lookup name index, or -1 if not found
     */
    public fun getLookupNameIdx(name: String?): Int {
        val num = lookupNumbers[name]
        return num ?: -1
    }

    /**
     * Mark a lookup index as used.
     *
     * @param idx the lookup index
     * @throws IndexOutOfBoundsException if the index is out of bounds
     */
    public fun markLookupIdxUsed(idx: Int) {
        lookupIdxUsed[idx] = true
    }

    /**
     * Check if a lookup index is used.
     *
     * @param idx the lookup index
     * @return true if the index is used
     */
    public fun isLookupIdxUsed(idx: Int): Boolean {
        return idx < lookupIdxUsed.size && lookupIdxUsed[idx]
    }

    /**
     * Mark all tags as used.
     */
    public fun setAllTagsUsed() {
        for (i in lookupIdxUsed.indices) {
            lookupIdxUsed[i] = true
        }
    }

    /**
     * Get a comma-separated list of used tag names.
     *
     * @return the used tag list
     */
    public fun usedTagList(): String = lookupValues.indices
        .filter { lookupIdxUsed[it] }
        .joinToString(",") { lookupNames[it] ?: "" }

    /**
     * Get the index of a lookup value for the given name index and value.
     *
     * @param nameIdx the name index
     * @param value the value string
     * @return the lookup value index, or -1 if not found
     */
    public fun getLookupValueIdx(nameIdx: Int, value: String?): Int {
        val values = lookupValues[nameIdx]!!
        for (i in values.indices) {
            if (values[i]!!.value == value) return i
        }
        return -1
    }

    /**
     * Parse the next token from the content.
     *
     * @return the parsed token, or null if end of content
     */
    public fun parseToken(): String? {
        while (true) {
            val token = _parseToken()
            if (token == null) return null
            if (token.startsWith(CONTEXT_TAG)) {
                _inOurContext = token.substring(CONTEXT_TAG.length) == context
            } else if (token.startsWith(MODEL_TAG)) {
                _modelClass = token.substring(MODEL_TAG.length).trim()
            } else if (_inOurContext) {
                return token
            }
        }
    }

    private fun _parseToken(): String? {
        if (_readerDone) {
            return null
        }

        val content = _content
        val length = content.length
        while (_contentPos < length) {
            val current = content[_contentPos]
            when {
                current == '\n' -> {
                    linenr++
                    _contentPos++
                }

                current.isWhitespace() -> _contentPos++

                current == '#' -> {
                    val commentStart = _contentPos
                    while (_contentPos < length) {
                        val commentChar = content[_contentPos++]
                        if (commentChar == '\n') {
                            linenr++
                            break
                        }
                        if (commentChar == '\r') {
                            break
                        }
                    }
                    processComment(content.substring(commentStart, _contentPos))
                }

                else -> {
                    val tokenStart = _contentPos
                    while (_contentPos < length && !content[_contentPos].isWhitespace()) {
                        _contentPos++
                    }
                    return content.substring(tokenStart, _contentPos)
                }
            }
        }

        _readerDone = true
        return null
    }

    private fun processComment(comment: String) {
        val num = variableNumbers["check_start_way"]
        if (num == null || noStartWays.isNotEmpty() || !comment.contains("noStartWay")) {
            return
        }

        var valueSection = comment.trim()
        val parts = valueSection.split('|')
        if (parts.size != 4) {
            return
        }

        valueSection = parts[3].substringAfter('=', "").trim()
        if (valueSection.isEmpty()) {
            return
        }

        for (entry in valueSection.split(';')) {
            if (entry.isEmpty()) continue
            val nameValue = entry.split(',', limit = 2)
            if (nameValue.size != 2) continue

            val name = nameValue[0]
            val value = nameValue[1]
            val nameIdx = getLookupNameIdx(name)
            if (nameIdx == -1) {
                break
            }
            val valueIdx = getLookupValueIdx(nameIdx, value)
            val tmp = IntArray(noStartWays.size + 2)
            if (noStartWays.isNotEmpty()) {
                noStartWays.copyInto(tmp, 0, 0, noStartWays.size)
            }
            noStartWays = tmp
            noStartWays[noStartWays.size - 2] = nameIdx
            noStartWays[noStartWays.size - 1] = valueIdx
        }
    }

    /**
     * Assign a value to a variable.
     *
     * @param variableIdx the variable index
     * @param value the value to assign
     * @return the assigned value
     */
    public fun assign(variableIdx: Int, value: Float): Float {
        requireNotNull(variableData)[variableIdx] = value
        return value
    }

    public var ld2: IntArray = IntArray(512)

    init {
        var hashSize = hashSize
        this.context = context
        this.meta = meta

        meta.registerListener(context, this)

        if (hashSize > 0) {
            cache = LruCache(hashSize)
            identityCache = LruCache(hashSize)
            resultVarCache = LruCache(4096)
        }
    }

    /**
     * Check if a start way is allowed.
     *
     * @param ab the byte array to check
     * @return true if the start way is allowed
     */
    public fun checkStartWay(ab: ByteArray?): Boolean {
        if (ab == null) return true
        ld2.fill(0)
        decode(ld2, false, ab)
        var i = 0
        while (i < noStartWays.size) {
            val key = noStartWays[i]
            val value = noStartWays[i + 1]
            if (ld2[key] == value) return false
            i += 2
        }
        return true
    }

    /**
     * Free the no-start-ways array.
     */
    public fun freeNoWays() {
        noStartWays = IntArray(0)
    }

    public companion object {
        private const val CONTEXT_TAG = "---context:"
        private const val MODEL_TAG = "---model:"

        private data class MetaTokens(
            val name: String,
            val value: String,
            val aliases: List<String>?
        )

        private fun parseMetaTokens(line: String): MetaTokens? {
            val length = line.length
            var index = 0
            var name: String? = null
            var value: String? = null
            var aliases: MutableList<String>? = null

            while (index < length) {
                while (index < length && line[index].isWhitespace()) {
                    index++
                }
                if (index >= length) break

                val start = index
                while (index < length && !line[index].isWhitespace()) {
                    index++
                }
                val token = line.substring(start, index)

                when {
                    name == null -> name = token
                    value == null -> value = token
                    else -> {
                        if (aliases == null) {
                            aliases = mutableListOf()
                        }
                        aliases.add(token)
                    }
                }
            }

            val parsedName = name ?: return null
            val parsedValue = value ?: return null
            return MetaTokens(parsedName, parsedValue, aliases)
        }

        /**
         * Format a float with exactly one decimal place using '.' as separator.
         */
        internal fun formatOneDecimal(value: Float): String {
            val rounded = kotlin.math.round(value * 10)
            val whole = rounded / 10
            val frac = abs(rounded % 10)
            return "$whole.$frac"
        }
    }
}
