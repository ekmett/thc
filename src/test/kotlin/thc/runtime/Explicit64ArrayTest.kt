// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class Explicit64ArrayTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private data class Row(val bits: Long, val results: List<Long>)
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()

    @Test fun fullWidthStorageChecksBoundsAndPointerCellsBeforeMutation() {
        val raw = ByteArray(24)
        ManagedByteArray.writeIntGuest(raw, 1, Long.MIN_VALUE)
        assertEquals(Long.MIN_VALUE, ManagedByteArray.readIntGuest(raw, 1))
        val expected = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder())
            .putLong(8, Long.MIN_VALUE).array()
        assertArrayEquals(expected, raw)
        for (index in listOf(-1L, 3L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            assertThrows(RuntimeFault::class.java) { ManagedByteArray.readIntGuest(raw, index) }
            assertThrows(RuntimeFault::class.java) { ManagedByteArray.writeIntGuest(raw, index, 1) }
            assertArrayEquals(expected, raw)
        }
        val owner = PinnedMemory.allocate(32, 1)
        val base = ManagedAddress.fromAllocation(owner)
        val retained = base.plus(24)
        base.writeAddressElementIndex(0, retained)
        ManagedByteArray.writeIntGuest(owner, 2, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, ManagedByteArray.readIntGuest(owner, 2))
        assertSame(retained, base.readAddressElementIndex(0))
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.readIntGuest(owner, 0) }
        assertSame(retained, base.readAddressElementIndex(0))
        ManagedByteArray.writeIntGuest(owner, 0, -1)
        assertEquals(-1L, ManagedByteArray.readIntGuest(owner, 0))
        assertEquals(Long.MAX_VALUE, ManagedByteArray.readIntGuest(owner, 2))
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun originalGhcExplicit64ArrayCompositeMatchesNativeAndCompiledBackends() {
        val base = File(root, "build/explicit64-arrays")
        val manifest = Json.parse(File(base, "manifest.json").readText()) as Map<String, Any?>
        assertEquals(1L, manifest["schema"])
        assertEquals("9.14.1", manifest["ghc"])
        assertEquals(10L, manifest["nativeRows"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[kind] as Map<String, String>) {
                val digest = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, digest, "Stale explicit64 array " + kind + ": " + path)
            }
        val rows = File(base, "oracle.tsv").readLines().map { line ->
            val fields = line.split('\t')
            assertEquals(5, fields.size)
            Row(fields[0].toLong(), fields.drop(1).map(String::toLong))
        }
        assertEquals(10, rows.size)
        for (row in rows) {
            val memory = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder())
                .putLong(8, row.bits).putLong(16, row.bits)
            assertEquals(listOf(memory.getLong(8), memory.getLong(16),
                memory.getLong(8), memory.getLong(16)), row.results)
        }
        val required = mapOf("readInt64Array#" to 3L, "readWord64Array#" to 3L,
            "writeInt64Array#" to 4L, "writeWord64Array#" to 4L,
            "indexInt64Array#" to 2L, "indexWord64Array#" to 2L)
        for (stage in listOf("pre", "post")) {
            val directory = File(base, stage)
            val audit = Json.parse(File(directory, "audit.json").readText()) as Map<String, Any?>
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any>(), audit["missingGlobals"])
            val primitives = audit["primitives"] as List<Map<String, Any?>>
            for ((primitive, arity) in required) {
                val evidence = primitives.single { it["name"] == primitive }
                assertEquals(arity, evidence["expectedArity"])
                val uses = evidence["uses"] as List<Map<String, Any?>>
                assertEquals(1, uses.size)
                assertEquals("main:Explicit64ArrayAudit.explicit64ArrayBits", uses.single()["owner"])
                assertEquals(arity, uses.single()["arity"])
            }
            val module = Json.parse(File(directory, "core/Explicit64ArrayAudit.json").readText()) as Map<String, Any?>
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val entry = "explicit64ArrayBits"
                    val source = CoreModules.reachable(module, entry) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, source)
                        else BytecodeProgram(language, source)
                    val function = context.asValue(EntryValue(program, entry, 2))
                    fun check(row: Row, selectors: IntProgression) {
                        for (selector in selectors)
                            assertEquals(row.results[selector], function.execute(row.bits, selector).asLong(),
                                stage + "/" + backend + "/" + selector)
                        assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    }
                    rows.forEach { check(it, 0..3) }
                    assertTrue(function.invokeMember("compile").asBoolean(), stage + "/" + backend)
                    val host = program.hostEntryTarget(2)
                    val original = program.entryTarget(entry)
                    val guestCall = NodeUtil.findAllNodeInstances(host.rootNode, DirectCallNode::class.java)
                        .single { it.callTarget === original }
                    val active = guestCall.currentCallTarget as RootCallTarget
                    fun valid(target: RootCallTarget) =
                        target.javaClass.getMethod("isValidLastTier").invoke(target) == true
                    assertTrue(valid(host), stage + "/" + backend + " host installed")
                    assertTrue(valid(active), stage + "/" + backend + " active guest installed")
                    for (row in rows.asReversed()) for (selector in 3 downTo 0) {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(row.results[selector], function.execute(row.bits, selector).asLong(),
                            stage + "/" + backend + "/" + selector)
                        val after = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        // The public entry and the retained runRW# lambda each enter compiled code.
                        assertEquals(before + 2, after,
                            stage + "/" + backend + "/" + selector + " must enter both compiled roots")
                        assertTrue(valid(host), stage + "/" + backend + " host remains installed")
                        assertTrue(valid(active), stage + "/" + backend + " active guest remains installed")
                        assertSame(active, guestCall.currentCallTarget,
                            stage + "/" + backend + " active guest remains the call target")
                        assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    }
                } finally { context.leave() }
            }
        }
    }
}
