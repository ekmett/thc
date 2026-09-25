// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.TruffleObject
import com.oracle.truffle.api.interop.UnsupportedMessageException
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import java.math.BigDecimal
import java.math.BigInteger

/** The boxed scalar portion of a verified static foreign-export signature. */
internal class ManagedExportScalar private constructor(
    private val kind: Kind,
    private val bits: Int,
    private val layout: DataLayout,
    private val falseLayout: DataLayout? = null
) {
    internal enum class Role { ARGUMENT, RESULT }
    private enum class Kind { SIGNED, UNSIGNED, FLOAT, DOUBLE, CHAR, BOOL, UNIT }
    private val minimum = if (kind == Kind.SIGNED && bits < 64) -(1L shl (bits - 1)) else
        if (kind == Kind.SIGNED) Long.MIN_VALUE else 0L
    private val maximum = when {
        kind == Kind.SIGNED && bits < 64 -> (1L shl (bits - 1)) - 1
        kind == Kind.UNSIGNED && bits < 64 -> (1L shl bits) - 1
        kind == Kind.CHAR -> 0x10ffffL
        else -> Long.MAX_VALUE
    }

    companion object {
        private val MAX_WORD64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        private data class Specification(val owner: String, val constructor: String, val rep: String,
                                         val kind: Kind, val bits: Int = 0)
        private val specifications = mapOf(
            "Int8" to Specification("GHC.Internal.Int", "I8#", "Int8Rep", Kind.SIGNED, 8),
            "Int16" to Specification("GHC.Internal.Int", "I16#", "Int16Rep", Kind.SIGNED, 16),
            "Int32" to Specification("GHC.Internal.Int", "I32#", "Int32Rep", Kind.SIGNED, 32),
            "Int64" to Specification("GHC.Internal.Int", "I64#", "Int64Rep", Kind.SIGNED, 64),
            "Word8" to Specification("GHC.Internal.Word", "W8#", "Word8Rep", Kind.UNSIGNED, 8),
            "Word16" to Specification("GHC.Internal.Word", "W16#", "Word16Rep", Kind.UNSIGNED, 16),
            "Word32" to Specification("GHC.Internal.Word", "W32#", "Word32Rep", Kind.UNSIGNED, 32),
            "Word64" to Specification("GHC.Internal.Word", "W64#", "Word64Rep", Kind.UNSIGNED, 64),
            "Int" to Specification("GHC.Internal.Types", "I#", "IntRep", Kind.SIGNED),
            "Word" to Specification("GHC.Internal.Types", "W#", "WordRep", Kind.UNSIGNED),
            "Float" to Specification("GHC.Internal.Types", "F#", "FloatRep", Kind.FLOAT),
            "Double" to Specification("GHC.Internal.Types", "D#", "DoubleRep", Kind.DOUBLE),
            "Char" to Specification("GHC.Internal.Types", "C#", "WordRep", Kind.CHAR),
            "Bool" to Specification("GHC.Internal.Types", "True", "", Kind.BOOL),
            "Unit" to Specification("GHC.Internal.Tuple", "()", "", Kind.UNIT))

        /** [layoutById] must resolve constructors from the same executable as the exported binder. */
        fun fromNormalizedType(type: Map<String, Any?>, role: Role, wordBits: Int,
                               layoutById: (String) -> DataLayout): ManagedExportScalar {
            if (wordBits != 32 && wordBits != 64) fault("Unsupported foreign-export target word width")
            if (type.keys != setOf("kind", "name", "arguments") || type["kind"] != "tycon" ||
                type["arguments"] != emptyList<Any>()) fault("Unsupported foreign-export scalar type")
            val name = type["name"] as? Map<*, *> ?: fault("Missing foreign-export type identity")
            if (name.keys != setOf("unit", "module", "occurrence", "namespace") ||
                name["unit"] != "ghc-internal" || name["namespace"] != "type")
                fault("Unsupported foreign-export type identity")
            val occurrence = name["occurrence"] as? String ?: fault("Missing foreign-export type occurrence")
            val spec = specifications[occurrence] ?: fault("Unsupported foreign-export scalar: $occurrence")
            if (name["module"] != spec.owner) fault("Foreign-export scalar owner mismatch: $occurrence")
            if (spec.kind == Kind.UNIT && role == Role.ARGUMENT)
                fault("GHC does not marshal a unit foreign-export argument")
            val id = "ghc-internal:${spec.owner}.${spec.constructor}"
            val layout = layoutById(id)
            if (layout.id != id || layout.name != spec.constructor ||
                layout.arity != (if (spec.rep.isEmpty()) 0 else 1) ||
                (spec.rep.isNotEmpty() && !layout.hasFieldRepresentation(0, spec.rep)))
                fault("Foreign-export constructor layout mismatch: $id")
            val falseLayout = if (spec.kind == Kind.BOOL) layoutById("ghc-internal:GHC.Internal.Types.False") else null
            if (falseLayout != null && (falseLayout.id != "ghc-internal:GHC.Internal.Types.False" ||
                    falseLayout.name != "False" || falseLayout.arity != 0))
                fault("Foreign-export Bool constructor layout mismatch")
            return ManagedExportScalar(spec.kind, if (spec.bits == 0) wordBits else spec.bits, layout, falseLayout)
        }
    }

    private fun checkedLong(number: Long): Long {
        if (number !in minimum..maximum) fault("Foreign-export integral argument is out of range")
        return number
    }

    private fun checkedBigInteger(number: BigInteger): Long {
        if (kind == Kind.UNSIGNED && bits == 64) {
            if (number.signum() < 0 || number > MAX_WORD64)
                fault("Foreign-export integral argument is out of range")
            return number.toLong() // Preserve the unsigned high bit in the primitive field.
        }
        val narrow = try { number.longValueExact() }
                     catch (_: ArithmeticException) { fault("Foreign-export integral argument is out of range") }
        return checkedLong(narrow)
    }

    private fun checkedInteger(value: Any?, interop: InteropLibrary): Long {
        if (value is BigInteger) return checkedBigInteger(value) // Raw Java BigInteger is not Truffle numeric.
        if (value == null || !interop.isNumber(value)) fault("Expected an exact integral foreign-export argument")
        try {
            if (interop.fitsInLong(value)) return checkedLong(interop.asLong(value))
            if (kind == Kind.UNSIGNED && bits == 64 && interop.fitsInBigInteger(value))
                return checkedBigInteger(interop.asBigInteger(value))
        } catch (_: UnsupportedMessageException) { /* The advertised conversion was withdrawn. */ }
        fault("Expected an exact integral foreign-export argument")
    }

    private fun codePoint(value: Any?, interop: InteropLibrary): Long {
        val point = if (value != null && interop.isString(value)) {
            val string = try { interop.asString(value) }
                         catch (_: UnsupportedMessageException) { fault("Expected one foreign-export character") }
            if (string.codePointCount(0, string.length) != 1) fault("Expected one foreign-export character")
            string.codePointAt(0).toLong()
        } else checkedInteger(value, interop)
        if (point > 0x10ffff) fault("Foreign-export character is out of range")
        return point
    }

    fun fromHost(value: Any?, interop: InteropLibrary = InteropLibrary.getUncached()): DataValue = when (kind) {
        Kind.SIGNED, Kind.UNSIGNED -> layout.createLong(checkedInteger(value, interop))
        Kind.CHAR -> layout.createLong(codePoint(value, interop))
        Kind.BOOL -> {
            if (value == null || !interop.isBoolean(value)) fault("Expected a foreign-export Boolean")
            val truth = try { interop.asBoolean(value) }
                        catch (_: UnsupportedMessageException) { fault("Expected a foreign-export Boolean") }
            (if (truth) layout else falseLayout!!).allocate()
        }
        Kind.FLOAT -> {
            if (value == null || !interop.isNumber(value) || !interop.fitsInFloat(value))
                fault("Expected a foreign-export Float")
            val number = try { interop.asFloat(value) }
                         catch (_: UnsupportedMessageException) { fault("Expected a foreign-export Float") }
            layout.allocate().also { layout.initializeFloat(it, 0, number) }
        }
        Kind.DOUBLE -> {
            if (value == null || !interop.isNumber(value) || !interop.fitsInDouble(value))
                fault("Expected a foreign-export Double")
            val number = try { interop.asDouble(value) }
                         catch (_: UnsupportedMessageException) { fault("Expected a foreign-export Double") }
            layout.allocate().also { layout.initializeDouble(it, 0, number) }
        }
        Kind.UNIT -> fault("GHC does not marshal a unit foreign-export argument")
    }

    /** The caller must force the lifted result at its resumable guest boundary first. */
    fun toHost(forcedValue: Any?): Any? {
        val value = forcedValue as? DataValue ?: fault("Foreign-export result is not a boxed scalar")
        if (kind == Kind.BOOL) {
            if (layout.matches(value)) return true
            if (falseLayout!!.matches(value)) return false
            fault("Foreign-export Boolean constructor mismatch")
        }
        if (!layout.matches(value)) fault("Foreign-export result constructor mismatch")
        return when (kind) {
            Kind.SIGNED -> checkedGuestSigned(layout.readLong(value, 0))
            Kind.UNSIGNED -> checkedGuestUnsigned(layout.readLong(value, 0))
            Kind.CHAR -> {
                val point = layout.readLong(value, 0)
                if (point !in 0L..0x10ffffL) fault("Foreign-export result character is out of range")
                String(Character.toChars(point.toInt()))
            }
            Kind.FLOAT -> layout.readFloat(value, 0)
            Kind.DOUBLE -> layout.readDouble(value, 0)
            Kind.UNIT -> ForeignExportUnit
            Kind.BOOL -> error("handled above")
        }
    }

    private fun checkedGuestSigned(value: Long): Any {
        if (value !in minimum..maximum) fault("Foreign-export signed result is out of range")
        return when (bits) { 8 -> value.toByte(); 16 -> value.toShort(); 32 -> value.toInt(); else -> value }
    }

    private fun checkedGuestUnsigned(value: Long): Any {
        if (bits < 64 && value !in minimum..maximum)
            fault("Foreign-export unsigned result is out of range")
        return when (bits) {
            8, 16 -> value.toInt()
            32 -> value
            else -> if (value >= 0) value else UnsignedWord64(value)
        }
    }
}

/** Java null is not a valid receiver for Truffle interop libraries. */
@ExportLibrary(InteropLibrary::class)
internal object ForeignExportUnit : TruffleObject {
    @ExportMessage fun isNull() = true
}

/** A Word64 result remains a Truffle number throughout the public interop boundary. */
@ExportLibrary(InteropLibrary::class)
internal class UnsignedWord64(private val bits: Long) : TruffleObject {
    private val number: BigInteger = BigInteger.valueOf(bits).let {
        if (bits < 0) it.add(BigInteger.ONE.shiftLeft(64)) else it
    }
    @ExportMessage fun isNumber() = true
    @ExportMessage fun fitsInByte() = number <= BigInteger.valueOf(Byte.MAX_VALUE.toLong())
    @ExportMessage fun fitsInShort() = number <= BigInteger.valueOf(Short.MAX_VALUE.toLong())
    @ExportMessage fun fitsInInt() = number <= BigInteger.valueOf(Int.MAX_VALUE.toLong())
    @ExportMessage fun fitsInLong() = bits >= 0
    @ExportMessage fun fitsInBigInteger() = true
    private fun fitsFloating(value: Double) = value.isFinite() &&
        BigDecimal(value).toBigIntegerExact() == number
    @ExportMessage fun fitsInFloat() = fitsFloating(number.toFloat().toDouble())
    @ExportMessage fun fitsInDouble() = fitsFloating(number.toDouble())
    @ExportMessage fun asByte(): Byte = if (fitsInByte()) bits.toByte() else throw UnsupportedMessageException.create()
    @ExportMessage fun asShort(): Short = if (fitsInShort()) bits.toShort() else throw UnsupportedMessageException.create()
    @ExportMessage fun asInt(): Int = if (fitsInInt()) bits.toInt() else throw UnsupportedMessageException.create()
    @ExportMessage fun asLong(): Long = if (fitsInLong()) bits else throw UnsupportedMessageException.create()
    @ExportMessage fun asBigInteger(): BigInteger = number
    @ExportMessage fun asFloat(): Float = if (fitsInFloat()) number.toFloat() else throw UnsupportedMessageException.create()
    @ExportMessage fun asDouble(): Double = if (fitsInDouble()) number.toDouble() else throw UnsupportedMessageException.create()
}
