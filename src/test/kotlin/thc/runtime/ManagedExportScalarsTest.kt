// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.interop.InteropLibrary
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import java.math.BigInteger

class ManagedExportScalarsTest {
    private fun inContext(action: (Language) -> Unit) {
        Context.newBuilder("thc").allowExperimentalOptions(true).build().use { context ->
            context.initialize("thc")
            context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }
    }

    private fun normalized(module: String, occurrence: String): Map<String, Any?> = mapOf(
        "kind" to "tycon", "name" to mapOf("unit" to "ghc-internal", "module" to module,
            "occurrence" to occurrence, "namespace" to "type"), "arguments" to emptyList<Any>())

    private fun layouts(language: Language): Map<String, DataLayout> = listOf(
        Triple("GHC.Internal.Int.I8#", "I8#", "Int8Rep"),
        Triple("GHC.Internal.Int.I16#", "I16#", "Int16Rep"),
        Triple("GHC.Internal.Int.I32#", "I32#", "Int32Rep"),
        Triple("GHC.Internal.Int.I64#", "I64#", "Int64Rep"),
        Triple("GHC.Internal.Word.W8#", "W8#", "Word8Rep"),
        Triple("GHC.Internal.Word.W16#", "W16#", "Word16Rep"),
        Triple("GHC.Internal.Word.W32#", "W32#", "Word32Rep"),
        Triple("GHC.Internal.Word.W64#", "W64#", "Word64Rep"),
        Triple("GHC.Internal.Types.I#", "I#", "IntRep"),
        Triple("GHC.Internal.Types.W#", "W#", "WordRep"),
        Triple("GHC.Internal.Types.F#", "F#", "FloatRep"),
        Triple("GHC.Internal.Types.D#", "D#", "DoubleRep"),
        Triple("GHC.Internal.Types.C#", "C#", "WordRep"),
        Triple("GHC.Internal.Types.True", "True", ""),
        Triple("GHC.Internal.Types.False", "False", ""),
        Triple("GHC.Internal.Tuple.()", "()", "")
    ).associate { (id, name, rep) ->
        val fields = if (rep.isEmpty()) emptyArray() else arrayOf(rep)
        val full = "ghc-internal:$id"
        full to DataLayout(language, full, name, fields)
    }

    private fun codec(layouts: Map<String, DataLayout>, module: String, occurrence: String,
                      role: ManagedExportScalar.Role = ManagedExportScalar.Role.ARGUMENT,
                      wordBits: Int = 64) = ManagedExportScalar.fromNormalizedType(
        normalized(module, occurrence), role, wordBits) { layouts[it] ?: error("Missing test layout: $it") }

    @Test fun signedAndUnsignedWidthsCheckIngressAndBoxedResult() = inContext { language ->
        val layouts = layouts(language)
        val cases = listOf(
            Triple("GHC.Internal.Int", "Int8", BigInteger.valueOf(-128)),
            Triple("GHC.Internal.Int", "Int16", BigInteger.valueOf(32767)),
            Triple("GHC.Internal.Int", "Int32", BigInteger.valueOf(Int.MIN_VALUE.toLong())),
            Triple("GHC.Internal.Int", "Int64", BigInteger.valueOf(Long.MIN_VALUE)),
            Triple("GHC.Internal.Word", "Word8", BigInteger.valueOf(255)),
            Triple("GHC.Internal.Word", "Word16", BigInteger.valueOf(65535)),
            Triple("GHC.Internal.Word", "Word32", BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE)))
        for ((module, occurrence, input) in cases) {
            val scalar = codec(layouts, module, occurrence)
            val boxed = scalar.fromHost(input)
            val answer = scalar.toHost(boxed) as Number
            assertEquals(input, BigInteger.valueOf(answer.toLong()), occurrence)
            val outside = when (occurrence) {
                "Int8", "Int32", "Int64" -> input.subtract(BigInteger.ONE)
                "Int16" -> input.add(BigInteger.ONE)
                else -> input.add(BigInteger.ONE)
            }
            assertThrows(RuntimeFault::class.java) { scalar.fromHost(outside) }
            if (module == "GHC.Internal.Word")
                assertThrows(RuntimeFault::class.java) { scalar.fromHost(-1) }
            assertThrows(RuntimeFault::class.java) { scalar.fromHost(1.5) }
            assertThrows(RuntimeFault::class.java) {
                scalar.toHost(layouts.getValue("ghc-internal:GHC.Internal.Types.I#").createLong(1))
            }
        }
        for (bits in listOf(32, 64)) {
            val signed = codec(layouts, "GHC.Internal.Types", "Int", wordBits = bits)
            val unsigned = codec(layouts, "GHC.Internal.Types", "Word", wordBits = bits)
            assertThrows(RuntimeFault::class.java) { signed.fromHost(BigInteger.ONE.shiftLeft(bits - 1)) }
            assertThrows(RuntimeFault::class.java) { unsigned.fromHost(BigInteger.ONE.shiftLeft(bits)) }
        }
    }

    @Test fun word64UpperHalfIsInteropBigIntegerNeverNegativeHostLong() = inContext { language ->
        val scalar = codec(layouts(language), "GHC.Internal.Word", "Word64")
        val interop = InteropLibrary.getUncached()
        val maximum = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        for (number in listOf(BigInteger.ZERO, BigInteger.ONE.shiftLeft(63), maximum)) {
            val boxed = scalar.fromHost(number)
            val result = scalar.toHost(boxed)!!
            assertTrue(interop.isNumber(result))
            assertTrue(interop.fitsInBigInteger(result))
            assertEquals(number, interop.asBigInteger(result))
            val publicValue = Context.getCurrent().asValue(result)
            assertTrue(publicValue.isNumber)
            assertEquals(number, publicValue.asBigInteger())
            assertEquals(number <= BigInteger.valueOf(Long.MAX_VALUE), interop.fitsInLong(result))
            if (number > BigInteger.valueOf(Long.MAX_VALUE))
                assertThrows(com.oracle.truffle.api.interop.UnsupportedMessageException::class.java) {
                    interop.asLong(result)
                }
        }
        assertThrows(RuntimeFault::class.java) { scalar.fromHost(BigInteger.ONE.shiftLeft(64)) }
        assertThrows(RuntimeFault::class.java) { scalar.fromHost(BigInteger.valueOf(-1)) }
    }

    @Test fun floatsBoolCharAndUnitRetainExactBoxedConventions() = inContext { language ->
        val layouts = layouts(language)
        val floating = codec(layouts, "GHC.Internal.Types", "Float")
        val double = codec(layouts, "GHC.Internal.Types", "Double")
        for (number in listOf(-0.0f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val answer = floating.toHost(floating.fromHost(number)) as Float
            assertEquals(number.toRawBits(), answer.toRawBits())
        }
        for (number in listOf(-0.0, Double.NaN, Double.NEGATIVE_INFINITY)) {
            val answer = double.toHost(double.fromHost(number)) as Double
            assertEquals(number.toRawBits(), answer.toRawBits())
        }
        val boolean = codec(layouts, "GHC.Internal.Types", "Bool")
        assertEquals(true, boolean.toHost(boolean.fromHost(true)))
        assertEquals(false, boolean.toHost(boolean.fromHost(false)))
        assertThrows(RuntimeFault::class.java) { boolean.fromHost(1) }
        val character = codec(layouts, "GHC.Internal.Types", "Char")
        assertEquals("😀", character.toHost(character.fromHost("😀")))
        assertEquals("A", character.toHost(character.fromHost(65)))
        for (invalid in listOf("", "AB", "\ud800", 0x110000, 0xdfff))
            assertThrows(RuntimeFault::class.java) { character.fromHost(invalid) }
        val unit = codec(layouts, "GHC.Internal.Tuple", "Unit", ManagedExportScalar.Role.RESULT)
        assertNull(unit.toHost(layouts.getValue("ghc-internal:GHC.Internal.Tuple.()").allocate()))
        assertThrows(RuntimeFault::class.java) {
            codec(layouts, "GHC.Internal.Tuple", "Unit", ManagedExportScalar.Role.ARGUMENT)
        }
    }

    @Test fun exactTypeAndProgramOwnedLayoutProofsRejectLookalikes() = inContext { language ->
        val layouts = layouts(language)
        val type = normalized("GHC.Internal.Int", "Int8")
        assertThrows(RuntimeFault::class.java) {
            ManagedExportScalar.fromNormalizedType(type + ("arguments" to listOf(type)),
                ManagedExportScalar.Role.ARGUMENT, 64, layouts::getValue)
        }
        assertThrows(RuntimeFault::class.java) {
            ManagedExportScalar.fromNormalizedType(normalized("GHC.Internal.Word", "Int8"),
                ManagedExportScalar.Role.ARGUMENT, 64, layouts::getValue)
        }
        val wrong = layouts + ("ghc-internal:GHC.Internal.Int.I8#" to
            DataLayout(language, "ghc-internal:GHC.Internal.Int.I8#", "I8#", arrayOf("Word8Rep")))
        assertThrows(RuntimeFault::class.java) { codec(wrong, "GHC.Internal.Int", "Int8") }
        val anotherProgram = layouts(language)
        val scalar = codec(layouts, "GHC.Internal.Int", "Int8")
        assertThrows(RuntimeFault::class.java) {
            scalar.toHost(anotherProgram.getValue("ghc-internal:GHC.Internal.Int.I8#").createLong(7))
        }
    }
}
