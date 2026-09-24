package thc

import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.ManagedAddress
import thc.runtime.RuntimeFault
import java.io.File

class CStringTest {
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

    @Test fun literalAddressBoundsRejectOverflowAndForeignMemoryAccess() {
        val address = ManagedAddress.fromHex("41")
        for (offset in listOf(-1L, 2L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertThrows(RuntimeFault::class.java) { address.indexChar(offset) }
        }
        for (offset in listOf(-1L, 3L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertThrows(RuntimeFault::class.java) { address.plus(offset) }
        }
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
            assertTrue(wrongType.message.orEmpty().contains("Expected a managed Addr#"), wrongType.message)
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
        executionContext().use { context ->
            val function = context.eval("thc", request(listOf(exported, driver)))
            val expected = listOf(386L, 321L, 66L, 0L, 67L, 0L)
            repeat(8) { for ((offset, value) in expected.withIndex()) assertEquals(value, function.execute(offset).asLong()) }
            assertTrue(function.invokeMember("compile").asBoolean())
            val before = count(function, "compiledEntries")
            for ((offset, value) in expected.withIndex()) assertEquals(value, function.execute(offset).asLong())
            assertTrue(count(function, "compiledEntries") > before)
        }
    }
}
