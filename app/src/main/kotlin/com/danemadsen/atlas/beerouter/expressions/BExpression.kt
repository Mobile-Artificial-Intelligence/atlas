package com.danemadsen.atlas.beerouter.expressions

import com.danemadsen.atlas.beerouter.router.exceptions.ExpressionParseException

internal class BExpression {
    private var typ: ExprType = ExprType.NUMBER
    private var op1: BExpression? = null
    private var op2: BExpression? = null
    private var op3: BExpression? = null
    private var numberValue = 0f
    private var variableIdx = 0
    private var lookupNameIdx = -1
    private var lookupValueIdxArray: IntArray = IntArray(0)
    private var doNotChange = false

    private fun markLookupIdxUsed(ctx: BExpressionContext): Int {
        var nodeCount = 1
        if (lookupNameIdx >= 0) {
            ctx.markLookupIdxUsed(lookupNameIdx)
        }
        nodeCount += op1?.markLookupIdxUsed(ctx) ?: 0
        nodeCount += op2?.markLookupIdxUsed(ctx) ?: 0
        nodeCount += op3?.markLookupIdxUsed(ctx) ?: 0
        return nodeCount
    }

    // Evaluate the expression
    fun evaluate(ctx: BExpressionContext?): Float {
        return when (typ) {
            ExprType.OR -> if (op1!!.evaluate(ctx) != 0f) 1f else (if (op2!!.evaluate(ctx) != 0f) 1f else 0f)
            ExprType.XOR -> if ((op1!!.evaluate(ctx) != 0f) xor (op2!!.evaluate(ctx) != 0f)) 1f else 0f
            ExprType.AND -> if (op1!!.evaluate(ctx) != 0f) (if (op2!!.evaluate(ctx) != 0f) 1f else 0f) else 0f
            ExprType.ADD -> op1!!.evaluate(ctx) + op2!!.evaluate(ctx)
            ExprType.SUB -> op1!!.evaluate(ctx) - op2!!.evaluate(ctx)
            ExprType.MULTIPLY -> op1!!.evaluate(ctx) * op2!!.evaluate(ctx)
            ExprType.DIVIDE -> divide(op1!!.evaluate(ctx), op2!!.evaluate(ctx))
            ExprType.MAX -> maxOf(op1!!.evaluate(ctx), op2!!.evaluate(ctx))
            ExprType.MIN -> minOf(op1!!.evaluate(ctx), op2!!.evaluate(ctx))
            ExprType.EQUAL -> if (op1!!.evaluate(ctx) == op2!!.evaluate(ctx)) 1f else 0f
            ExprType.GREATER -> if (op1!!.evaluate(ctx) > op2!!.evaluate(ctx)) 1f else 0f
            ExprType.LESSER -> if (op1!!.evaluate(ctx) < op2!!.evaluate(ctx)) 1f else 0f
            ExprType.SWITCH -> if (op1!!.evaluate(ctx) != 0f) op2!!.evaluate(ctx) else op3!!.evaluate(ctx)
            ExprType.ASSIGN -> ctx!!.assign(variableIdx, op1!!.evaluate(ctx))
            ExprType.LOOKUP -> ctx!!.getLookupMatch(lookupNameIdx, lookupValueIdxArray)
            ExprType.NUMBER -> numberValue
            ExprType.VARIABLE -> ctx!!.getVariableValue(variableIdx)
            ExprType.FOREIGN_VARIABLE -> ctx!!.getForeignVariableValue(variableIdx)
            ExprType.VARIABLE_GET -> ctx!!.getLookupValue(lookupNameIdx)
            ExprType.NOT -> if (op1!!.evaluate(ctx) == 0f) 1f else 0f
        }
    }

    // Try to collapse the expression if logically possible
    private fun tryCollapse(): BExpression? {
        return when (typ) {
            ExprType.OR -> if (op1!!.typ == ExprType.NUMBER)
                (if (op1!!.numberValue != 0f) op1 else op2)
            else
                (if (op2!!.typ == ExprType.NUMBER)
                    (if (op2!!.numberValue != 0f) op2 else op1)
                else
                    this)

            ExprType.AND -> if (op1!!.typ == ExprType.NUMBER)
                (if (op1!!.numberValue == 0f) op1 else op2)
            else
                (if (op2!!.typ == ExprType.NUMBER)
                    (if (op2!!.numberValue == 0f) op2 else op1)
                else
                    this)

            ExprType.ADD -> if (op1!!.typ == ExprType.NUMBER)
                (if (op1!!.numberValue == 0f) op2 else this)
            else
                (if (op2!!.typ == ExprType.NUMBER)
                    (if (op2!!.numberValue == 0f) op1 else this)
                else
                    this)

            ExprType.SWITCH -> if (op1!!.typ == ExprType.NUMBER) (if (op1!!.numberValue == 0f) op3 else op2) else this
            else -> this
        }
    }

    // Try to evaluate the expression if all operands are constant
    private fun tryEvaluateConstant(): BExpression {
        if (op1 != null && op1!!.typ == ExprType.NUMBER && (op2 == null || op2!!.typ == ExprType.NUMBER)
            && (op3 == null || op3!!.typ == ExprType.NUMBER)
        ) {
            val exp = BExpression()
            exp.typ = ExprType.NUMBER
            exp.numberValue = evaluate(null)
            return exp
        }
        return this
    }

    private fun divide(v1: Float, v2: Float): Float {
        require(v2 != 0f) { "div by zero" }
        return v1 / v2
    }

    override fun toString(): String = when (typ) {
        ExprType.NUMBER -> numberValue.toString()
        ExprType.VARIABLE -> "vidx=$variableIdx"
        else -> buildString {
            append("typ=$typ ops=(")
            op1?.let { append('[').append(it).append(']') }
            op2?.let { append('[').append(it).append(']') }
            op3?.let { append('[').append(it).append(']') }
            append(')')
        }
    }

    private enum class ExprType {
        OR, AND, NOT,
        ADD, MULTIPLY, DIVIDE, MAX, EQUAL, GREATER, MIN,
        SUB, LESSER, XOR,
        SWITCH, ASSIGN, LOOKUP, NUMBER, VARIABLE, FOREIGN_VARIABLE, VARIABLE_GET
    }

    companion object {
        // Parse the expression and all subexpression
        /**
         * Parse the expression and all subexpressions.
         *
         * @param ctx the expression context
         * @param level the nesting level
         * @return the parsed expression, or null if nothing to parse
         * @throws ExpressionParseException if parsing fails or unexpected end of file is encountered
         * @throws IllegalArgumentException if expression syntax is invalid
         */
        @Throws(Exception::class)
        fun parse(ctx: BExpressionContext, level: Int): BExpression? {
            return parse(ctx, level, null)
        }

        @Throws(Exception::class)
        private fun parse(
            ctx: BExpressionContext,
            level: Int,
            optionalToken: String?
        ): BExpression? {
            var e: BExpression? = parseRaw(ctx, level, optionalToken)
            if (e == null) {
                return null
            }

            if (e.typ == ExprType.ASSIGN) {
                // manage assigned an injected values
                val assignedBefore = ctx.lastAssignedExpression!![e.variableIdx]
                if (assignedBefore != null && assignedBefore.doNotChange) {
                    e.op1 = assignedBefore // was injected as key-value
                    e.op1!!.doNotChange = false // protect just once, can be changed in second assignment
                }
                ctx.lastAssignedExpression!![e.variableIdx] = e.op1
            } else if (!ctx.skipConstantExpressionOptimizations) {
                // try to simplify the expression
                if (e.typ == ExprType.VARIABLE) {
                    val ae = ctx.lastAssignedExpression!![e.variableIdx]
                    if (ae != null && ae.typ == ExprType.NUMBER) {
                        e = ae
                    }
                } else {
                    val eCollapsed = e.tryCollapse()
                    if (e != eCollapsed) {
                        e = eCollapsed
                    }
                    val eEvaluated = e!!.tryEvaluateConstant()
                    if (e != eEvaluated) {
                        e = eEvaluated
                    }
                }
            }
            if (level == 0) {
                val nodeCount = e.markLookupIdxUsed(ctx)
                ctx.expressionNodeCount += nodeCount
            }
            return e
        }

        @Throws(Exception::class)
        private fun parseRaw(
            ctx: BExpressionContext,
            level: Int,
            optionalToken: String?
        ): BExpression? {
            var brackets = false
            var operator = ctx.parseToken()
            if (optionalToken != null && optionalToken == operator) {
                operator = ctx.parseToken()
            }
            if ("(" == operator) {
                brackets = true
                operator = ctx.parseToken()
            }

            if (operator == null) {
                if (level == 0) return null
                else throw ExpressionParseException("unexpected end of file")
            }

            if (level == 0) {
                require("assign" == operator) { "operator $operator is invalid on toplevel (only 'assign' allowed)" }
            }

            val exp = BExpression()
            var nops = 3

            var ifThenElse = false

            if ("switch" == operator) {
                exp.typ = ExprType.SWITCH
            } else if ("if" == operator) {
                exp.typ = ExprType.SWITCH
                ifThenElse = true
            } else {
                nops = 2 // check binary expressions

                if ("or" == operator) {
                    exp.typ = ExprType.OR
                } else if ("and" == operator) {
                    exp.typ = ExprType.AND
                } else if ("multiply" == operator) {
                    exp.typ = ExprType.MULTIPLY
                } else if ("divide" == operator) {
                    exp.typ = ExprType.DIVIDE
                } else if ("add" == operator) {
                    exp.typ = ExprType.ADD
                } else if ("max" == operator) {
                    exp.typ = ExprType.MAX
                } else if ("min" == operator) {
                    exp.typ = ExprType.MIN
                } else if ("equal" == operator) {
                    exp.typ = ExprType.EQUAL
                } else if ("greater" == operator) {
                    exp.typ = ExprType.GREATER
                } else if ("sub" == operator) {
                    exp.typ = ExprType.SUB
                } else if ("lesser" == operator) {
                    exp.typ = ExprType.LESSER
                } else if ("xor" == operator) {
                    exp.typ = ExprType.XOR
                } else {
                    nops = 1 // check unary expressions
                    if ("assign" == operator) {
                        require(level <= 0) { "assign operator within expression" }
                        exp.typ = ExprType.ASSIGN
                        val variable = ctx.parseToken()
                        requireNotNull(variable) { "unexpected end of file" }
                        require(variable.indexOf('=') < 0) { "variable name cannot contain '=': $variable" }
                        require(variable.indexOf(':') < 0) { "cannot assign context-prefixed variable: $variable" }
                        exp.variableIdx = ctx.getVariableIdx(variable, true)
                        require(exp.variableIdx >= ctx.minWriteIdx) { "cannot assign to readonly variable $variable" }
                    } else if ("not" == operator) {
                        exp.typ = ExprType.NOT
                    } else {
                        nops = 0 // check elementary expressions
                        var idx = operator.indexOf('=')
                        if (idx >= 0) {
                            exp.typ = ExprType.LOOKUP
                            val name = operator.take(idx)
                            val values = operator.substring(idx + 1)

                            exp.lookupNameIdx = ctx.getLookupNameIdx(name)
                            require(exp.lookupNameIdx >= 0) { "unknown lookup name: $name" }
                            val tokens = values.split("|").filter { it.isNotEmpty() }
                            val nt = tokens.size
                            val nt2 = if (nt == 0) 1 else nt
                            exp.lookupValueIdxArray = IntArray(nt2)
                            for (ti in 0..<nt2) {
                                val value = if (ti < nt) tokens[ti] else ""
                                exp.lookupValueIdxArray[ti] =
                                    ctx.getLookupValueIdx(exp.lookupNameIdx, value)
                                require(exp.lookupValueIdxArray[ti] >= 0) { "unknown lookup value: $value" }
                            }
                        } else if ((operator.indexOf(':').also { idx = it }) >= 0) {
                            if (operator.startsWith("v:")) {
                                val name = operator.substring(2)
                                exp.typ = ExprType.VARIABLE_GET
                                exp.lookupNameIdx = ctx.getLookupNameIdx(name)
                            } else {
                                val context = operator.take(idx)
                                val varname = operator.substring(idx + 1)
                                exp.typ = ExprType.FOREIGN_VARIABLE
                                exp.variableIdx = ctx.getForeignVariableIdx(context, varname)
                            }
                        } else if ((ctx.getVariableIdx(operator, false).also { idx = it }) >= 0) {
                            exp.typ = ExprType.VARIABLE
                            exp.variableIdx = idx
                        } else if ("true" == operator) {
                            exp.numberValue = 1f
                            exp.typ = ExprType.NUMBER
                        } else if ("false" == operator) {
                            exp.numberValue = 0f
                            exp.typ = ExprType.NUMBER
                        } else {
                            try {
                                exp.numberValue = operator.toFloat()
                                exp.typ = ExprType.NUMBER
                            } catch (nfe: NumberFormatException) {
                                throw IllegalArgumentException("unknown expression: $operator", nfe)
                            }
                        }
                    }
                }
            }
            // parse operands
            if (nops > 0) {
                exp.op1 = parse(ctx, level + 1, if (exp.typ == ExprType.ASSIGN) "=" else null)
            }
            if (nops > 1) {
                if (ifThenElse) checkExpectedToken(ctx, "then")
                exp.op2 = parse(ctx, level + 1, null)
            }
            if (nops > 2) {
                if (ifThenElse) checkExpectedToken(ctx, "else")
                exp.op3 = parse(ctx, level + 1, null)
            }
            if (brackets) {
                checkExpectedToken(ctx, ")")
            }
            return exp
        }

        @Throws(Exception::class)
        private fun checkExpectedToken(ctx: BExpressionContext, expected: String) {
            val token = ctx.parseToken()
            require(expected == token) { "unexpected token: $token, expected: $expected" }
        }

        fun createAssignExpressionFromKeyValue(
            ctx: BExpressionContext,
            key: String?,
            value: String
        ): BExpression {
            val e = BExpression()
            e.typ = ExprType.ASSIGN
            e.variableIdx = ctx.getVariableIdx(key, true)
            val op = BExpression()
            op.typ = ExprType.NUMBER
            op.numberValue = value.toFloat()
            op.doNotChange = true
            e.op1 = op
            ctx.lastAssignedExpression!![e.variableIdx] = op
            return e
        }
    }
}
