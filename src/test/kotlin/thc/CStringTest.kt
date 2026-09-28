// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import thc.Main.executionContext

import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import thc.runtime.BytecodeProgram
import thc.runtime.BytecodeRoot
import thc.runtime.Calls
import thc.runtime.ExecutableProgram
import thc.runtime.Program
import thc.runtime.ManagedAddress
import thc.runtime.ManagedAddressRead
import thc.runtime.RuntimeFault
import java.io.File

class CStringTest {
    @Test fun statelessAddressReadsPreserveCarriersAndRejectCoercions() {
        val address = ManagedAddress.fromHex("0000")
        assertEquals(0L, BytecodeRoot.AddressIndexManagedScalar.index(ManagedAddressRead.CHAR, address, 0L))
        for (operation in listOf(ManagedAddressRead.INT16, ManagedAddressRead.WORD16))
            assertEquals(0, BytecodeRoot.AddressIndexManagedScalar.index(operation, address, 0L))
        for (signed in listOf(false, true)) {
            assertEquals(0, BytecodeRoot.AddressIndexByte.index(signed, address, 0L))
            for (invalid in listOf(0, 0.0, null)) assertThrows(RuntimeFault::class.java) {
                BytecodeRoot.AddressIndexByte.index(signed, address, invalid)
            }
            assertThrows(RuntimeFault::class.java) { BytecodeRoot.AddressIndexByte.index(signed, 0L, 0L) }
            assertThrows(RuntimeFault::class.java) { BytecodeRoot.AddressIndexByte.index(signed, address, -1L) }
        }
        for (operation in listOf(ManagedAddressRead.CHAR, ManagedAddressRead.INT16, ManagedAddressRead.WORD16)) {
            for (invalid in listOf(0, 0.0, null)) assertThrows(RuntimeFault::class.java) {
                BytecodeRoot.AddressIndexManagedScalar.index(operation, address, invalid)
            }
            assertThrows(RuntimeFault::class.java) { BytecodeRoot.AddressIndexManagedScalar.index(operation, 0L, 0L) }
            assertThrows(RuntimeFault::class.java) { BytecodeRoot.AddressIndexManagedScalar.index(operation, address, -1L) }
        }
    }

    @ParameterizedTest
    @CsvSource("ast,false", "ast,true", "bytecode,false", "bytecode,true")
    fun characterAddressReadsKeepMachineCarriersDistinctFromNarrowBytes(backend: String, async: Boolean) {
        fun proof(rep: String) = mapOf("kind" to "long", "evaluated" to true, "primReps" to listOf(rep))
        for ((operation, rep) in listOf("indexCharOffAddr#" to "WordRep",
            "indexWord8OffAddr#" to "Word8Rep", "indexInt8OffAddr#" to "Int8Rep")) {
            for (byte in listOf(0, 127, 128, 255)) for (compiled in listOf(false, true)) {
                // Char# has GHC's WordRep, despite its one-byte memory access.
                // The result proof is also the typed function-return sink, without
                // unrelated first-write specialization of a synthetic case slot.
                val resultProof = proof(rep)
                val read = primitive(operation, listOf("lit", "string-bytes", "%02x".format(byte)), integer(0)) +
                    listOf(false, false, mapOf("rep" to resultProof))
                val binding = mapOf("id" to "entry", "name" to "entry", "arity" to 1, "lifted" to true,
                    "expr" to listOf("lam", listOf(mapOf("id" to "unused", "name" to "unused", "lifted" to false,
                        "rep" to proof("IntRep"))), read, mapOf("resultRep" to resultProof)))
                val raw = module(listOf(binding)) + ("instrument" to true)
                executionContext().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, raw, async)
                            else BytecodeProgram(language, raw, async)
                        val target = program.entryTarget("entry")
                        val targetClass = target.javaClass
                        if (compiled) {
                            targetClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                            assertEquals(true, targetClass.getMethod("isValidLastTier").invoke(target))
                            val runtime = Truffle.getRuntime()
                            runtime.javaClass.getMethod("bypassedInstalledCode",
                                Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target)
                        }
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        val expected: Any = if (rep == "WordRep") byte.toLong()
                            else if (rep == "Int8Rep") byte.toByte().toInt() else byte
                        assertEquals(expected, Calls.target(target, arrayOf(0L, 0L)),
                            "$backend/async=$async/$operation/$byte/compiled=$compiled")
                        if (compiled) {
                            assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                            assertSame(target, program.entryTarget("entry"))
                            assertEquals(true, targetClass.getMethod("isValidLastTier").invoke(target),
                                "The original target must remain installed after its first call")
                        }
                    } finally { context.leave() }
                }
            }
        }
    }

    @Test fun literalStoragePreservesUnsignedBytesEmbeddedNulsAndImplicitTerminator() {
        val address = ManagedAddress.fromHex("ff800041")
        assertEquals(255L, address.indexChar(0))
        assertEquals(128L, address.indexChar(1))
        assertEquals(0L, address.indexChar(2))
        assertEquals(65L, address.indexChar(3))
        assertEquals(0L, address.indexChar(4), "GHC static literals append one NUL byte")
        assertEquals(0L, ManagedAddress.fromHex("").indexChar(0))
        assertSame(address, address.plus(0))
        val shifted = address.plus(3)
        assertEquals(65L, shifted.indexChar(0))
        assertEquals(255L, shifted.indexChar(-3))
        assertEquals(128L, shifted.plus(-2).indexChar(0))
        assertEquals(255L, address.indexChar(0), "Offset arithmetic does not mutate the original address")
    }

    @Test fun literalMemoryAccessChecksBoundsWhileAddressArithmeticRetainsItsOrigin() {
        val address = ManagedAddress.fromHex("41")
        for (offset in listOf(-1L, 2L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertThrows(RuntimeFault::class.java) { address.indexChar(offset) }
        }
        for (offset in listOf(-1L, 3L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            // GHC permits sentinels outside the allocation; dereferencing one
            // remains invalid. This arithmetic does not manufacture raw memory.
            val sentinel = address.plus(offset)
            assertThrows(RuntimeFault::class.java) { sentinel.indexChar(0) }
        }
        assertTrue(address.plus(-1).plus(1).sameLocation(address))
        assertTrue(address.plus(3).plus(-3).sameLocation(address))
        val onePast = address.plus(2)
        assertEquals(0L, onePast.indexChar(-1))
        assertThrows(RuntimeFault::class.java) { onePast.indexChar(0) }
        assertThrows(RuntimeFault::class.java) { onePast.plus(Long.MAX_VALUE) }
        for (hex in listOf("0", "gg", "0z")) {
            assertThrows(RuntimeFault::class.java) { ManagedAddress.fromHex(hex) }
        }
    }

    private fun variable(id: String): List<Any?> = listOf("var", id)
    private fun integer(number: Long): List<Any?> = listOf("lit", "int", number.toString())
    private fun apply(function: List<Any?>, arguments: List<List<Any?>>, lifted: List<Boolean>): List<Any?> =
        listOf("app", function, arguments, lifted)
    private fun primitive(name: String, vararg arguments: List<Any?>): List<Any?> =
        apply(listOf("prim", name), arguments.toList(), List(arguments.size) { false })
    private fun binding(id: String, argument: String, lifted: Boolean, body: List<Any?>): Map<String, Any?> = mapOf(
        "id" to id, "name" to id, "arity" to 1, "lifted" to true, "type" to "Synthetic",
        "expr" to listOf("lam", listOf(mapOf("id" to argument, "name" to argument, "type" to "Synthetic",
            "lifted" to lifted, "coercion" to false)), body))
    private fun module(bindings: List<Map<String, Any?>>): Map<String, Any?> = mapOf(
        "schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.CString", "bindings" to bindings,
        "constructors" to emptyList<Any>())
    private fun request(modules: List<Map<String, Any?>>): String = Json.stringify(mapOf("entry" to "entry", "modules" to modules))
    @Suppress("UNCHECKED_CAST")
    private fun count(function: Value, key: String): Long =
        ((Json.parse(function.getMember("diagnostics").asString()) as Map<String, Any?>)[key] as Number).toLong()

    @Test fun literalPrimitivesExecuteInGuestCodeAndRejectNumericFakePointers() {
        val literal = listOf("lit", "string-bytes", "ff4100")
        val at = primitive("plusAddr#", literal, variable("offset"))
        val read = primitive("indexCharOffAddr#", at, integer(0))
        executionContext().use { context ->
            val function = context.eval("thc", request(listOf(module(listOf(binding("entry", "offset", false, read))))))
            val expected = listOf(255L, 65L, 0L, 0L)
            repeat(10) { for ((offset, value) in expected.withIndex()) assertEquals(value, function.execute(offset).asLong()) }
            assertTrue(function.invokeMember("compile").asBoolean())
            val before = count(function, "compiledEntries")
            for ((offset, value) in expected.withIndex()) assertEquals(value, function.execute(offset).asLong())
            assertTrue(count(function, "compiledEntries") > before)
            val bounds = assertThrows(PolyglotException::class.java) { function.execute(4) }
            assertTrue(bounds.message.orEmpty().contains("outside its backing storage"), bounds.message)
            val fakeRead = primitive("indexCharOffAddr#", variable("address"), integer(0))
            val fake = context.eval("thc", request(listOf(module(listOf(binding("entry", "address", false, fakeRead))))))
            val wrongType = assertThrows(PolyglotException::class.java) { fake.execute(0) }
            assertTrue(wrongType.message.orEmpty().contains("Expected a managed literal Addr#"), wrongType.message)
        }
    }

    @Test fun genuineGhcCStringDecoderConsumesLiteralBytesBeforeAndAfterCompilation() {
        val root = File(System.getProperty("thc.projectRoot"))
        @Suppress("UNCHECKED_CAST")
        val exported = Json.parse(File(root, "build/map/boot-core/GHC.Internal.CString.json").readText()) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val constructors = exported["constructors"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val bindings = exported["bindings"] as List<Map<String, Any?>>
        fun con(name: String) = constructors.single { it["name"] == name }["id"] as String
        val unpack = bindings.single { it["name"] == "unpackCString#" }["id"] as String
        val summed = primitive("+#", primitive("ord#", variable("char")),
            apply(variable("sumChars"), listOf(variable("tail")), listOf(true)))
        val unbox = listOf("case", variable("head"), "boxedChar", listOf(listOf("data", con("C#"), listOf("char"), summed)))
        val traverse = listOf("case", variable("list"), "spine", listOf(
            listOf("data", con("[]"), emptyList<String>(), integer(0)),
            listOf("data", con(":"), listOf("head", "tail"), unbox)))
        val address = primitive("plusAddr#", listOf("lit", "string-bytes", "41ff420043"), variable("offset"))
        val decoded = apply(variable(unpack), listOf(address), listOf(false))
        val driver = module(listOf(binding("sumChars", "list", true, traverse), binding("entry", "offset", false,
            apply(variable("sumChars"), listOf(decoded), listOf(true)))))
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            val function = context.eval("thc", Json.stringify(mapOf(
                "entry" to "entry", "modules" to listOf(exported, driver), "backend" to backend)))
            val expected = listOf(386L, 321L, 66L, 0L, 67L, 0L)
            repeat(8) { for ((offset, value) in expected.withIndex())
                assertEquals(value, function.execute(offset).asLong(), "$backend/$offset") }
            assertTrue(function.invokeMember("compile").asBoolean())
            val before = count(function, "compiledEntries")
            for ((offset, value) in expected.withIndex())
                assertEquals(value, function.execute(offset).asLong(), "$backend/$offset compiled")
            assertTrue(count(function, "compiledEntries") > before)
        }
    }
}
