// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.math.BigInteger

/** Mathematical reference for the scalar primop families exercised against native GHC. */
internal object ScalarPrimopModel {
    private val one = BigInteger.ONE

    private fun modulus(width: Int): BigInteger {
        require(width in 1..64)
        return one.shiftLeft(width)
    }

    private fun unsigned(value: Long, width: Int): BigInteger = BigInteger.valueOf(value).mod(modulus(width))

    private fun signed(value: BigInteger, width: Int): BigInteger {
        val residue = value.mod(modulus(width))
        return if (residue.testBit(width - 1)) residue - modulus(width) else residue
    }

    /** Bit-walk oracle independent of the JVM's compress/expand operations. */
    private fun depositOrExtract(deposit: Boolean, width: Int, source: BigInteger, mask: BigInteger): BigInteger {
        var result = BigInteger.ZERO
        var packedBit = 0
        for (position in 0 until width) if (mask.testBit(position)) {
            if (deposit) {
                if (source.testBit(packedBit)) result = result.setBit(position)
            } else if (source.testBit(position)) result = result.setBit(packedBit)
            packedBit++
        }
        return result
    }

    /** All arithmetic is unbounded until the final GHC-width truncation. */
    fun scalar(operation: String, width: Int, isUnsigned: Boolean, left: Long, right: Long): Long {
        val x = if (isUnsigned) unsigned(left, width) else signed(BigInteger.valueOf(left), width)
        val y = if (isUnsigned) unsigned(right, width) else signed(BigInteger.valueOf(right), width)
        fun bit(value: Boolean) = if (value) one else BigInteger.ZERO
        val result = when (operation) {
            "identity" -> x
            "negate" -> -x
            "plus" -> x + y
            "sub" -> x - y
            "times" -> x * y
            "quot" -> x / y
            "rem" -> x % y
            "eq" -> bit(x == y)
            "ne" -> bit(x != y)
            "lt" -> bit(x < y)
            "le" -> bit(x <= y)
            "gt" -> bit(x > y)
            "ge" -> bit(x >= y)
            "and" -> x.and(y)
            "or" -> x.or(y)
            "xor" -> x.xor(y)
            "not" -> x.not()
            "shiftL", "uncheckedShiftL" -> x.shiftLeft(right.toInt())
            "shiftRA" -> x.shiftRight(right.toInt())
            "shiftRL", "uncheckedShiftRL" -> unsigned(left, width).shiftRight(right.toInt())
            "pdep" -> depositOrExtract(true, width, x, y)
            "pext" -> depositOrExtract(false, width, x, y)
            else -> error("Unknown scalar primop operation: $operation")
        }
        return (if (isUnsigned) result.mod(modulus(width)) else signed(result, width)).toLong()
    }

    fun explicit64(operation: String, isUnsigned: Boolean, left: Long, right: Long): Long =
        when (operation) {
            "literals" -> when (left) {
                0L -> 0L
                1L -> Long.MAX_VALUE
                2L -> -1L
                else -> Long.MIN_VALUE
            }
            "case" -> when (left) {
                0L -> 11L
                Long.MIN_VALUE -> 13L
                -1L -> 17L
                else -> 19L
            }
            else -> scalar(operation, 64, isUnsigned, left, right)
        }

    /** Build results from individual bits, independent of the JVM bit-count/reversal helpers. */
    fun bit(operation: String, width: Int, carrier: Long): Long {
        val input = unsigned(carrier, width)
        val bits = List(width) { input.testBit(it) }
        return when (operation) {
            "narrowWord" -> input.toLong()
            "popCnt" -> bits.count { it }.toLong()
            "clz" -> bits.asReversed().takeWhile { !it }.size.toLong()
            "ctz" -> bits.takeWhile { !it }.size.toLong()
            "bitReverse", "byteSwap" -> {
                var result = BigInteger.ZERO
                for (bit in bits.indices) if (bits[bit]) {
                    val destination = if (operation == "bitReverse") width - 1 - bit
                        else width - 8 - 8 * (bit / 8) + bit % 8
                    result += one.shiftLeft(destination)
                }
                result.toLong()
            }
            else -> error("Unknown bit primop operation: $operation")
        }
    }
}
