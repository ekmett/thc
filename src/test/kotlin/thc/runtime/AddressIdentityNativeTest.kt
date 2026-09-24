// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File

class AddressIdentityNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val output = File(root, "build/addr-identity")

    private fun nodes(value: Any?): Sequence<List<Any?>> = when (value) {
        is List<*> -> sequenceOf(value) + value.asSequence().flatMap(::nodes)
        is Map<*, *> -> value.values.asSequence().flatMap(::nodes)
        else -> emptySequence()
    }

    @Test fun nativeNonProfilingResultsMatchBothCompiledBackendsAndCoreProofs() {
        val expected = output.resolve("oracle.txt").readLines().map(String::toLong)
        assertEquals(listOf(1L, 0L, 1L, 1L, 0L), expected)
        for (stage in listOf("pre", "post")) {
            val audit = Json.parse(output.resolve("$stage.audit.json").readText()) as Map<String, Any?>
            assertEquals(true, audit["accepted"], stage)
            assertEquals(emptyList<Any>(), audit["missingGlobals"], stage)
            assertEquals(emptyList<Any>(), audit["issues"], stage)
            val exported = Json.parse(output.resolve("$stage-core/AddressIdentityAudit.json").readText()) as Map<String, Any?>
            val module = CoreModules.reachable(CoreModules.merge(listOf(exported)), "probe")
            val calls = nodes(module["bindings"]).filter { it.firstOrNull() == "app" &&
                (it.getOrNull(1) as? List<*>)?.firstOrNull() == "prim" }.toList()
            assertEquals(setOf("getCurrentCCS#", "eqAddr#", "neAddr#"),
                calls.map { (it[1] as List<*>)[1] }.toSet(), "$stage retained primitives")
            val current = calls.single { (it[1] as List<*>)[1] == "getCurrentCCS#" }
            assertEquals(listOf(true, false), current[3])
            val result = (current.last() as Map<String, Any?>)["rep"] as Map<String, Any?>
            assertEquals(listOf("AddrRep"), result["primReps"])
            assertEquals(listOf("void", "address"),
                (result["components"] as List<Map<String, Any?>>).map { it["kind"] })
            assertTrue(nodes(module["bindings"]).any { it.take(3) == listOf("lit", "null-addr", "0") })
            for (backend in listOf("ast", "bytecode")) primopTestContext().use { context ->
                context.initialize("thc")
                context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program: ExecutableProgram = if (backend == "ast")
                        Program(language, module + ("instrument" to true))
                    else BytecodeProgram(language, module + ("instrument" to true))
                    val function = context.asValue(EntryValue(program, "probe", 1))
                    fun check() = expected.forEachIndexed { selector, answer ->
                        assertEquals(answer, function.execute(selector.toLong()).asLong(), "$stage/$backend/$selector")
                    }
                    check()
                    assertEquals(0L, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                    assertTrue(function.invokeMember("compile").asBoolean(), "$stage/$backend JIT installation")
                    check()
                    assertEquals(2L * expected.size,
                        (program.diagnostics().getValue("compiledEntries") as Number).toLong(),
                        "$stage/$backend each selector enters probe and its State lambda in installed code")
                    val target = program.entryTarget("probe")
                    assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                    assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                } finally { context.leave() }
            }
        }
    }

    @Test fun nullAndAllocationIdentityNeverBecomeHostPointers() {
        val nullAddress = ManagedAddress.nullAddress()
        assertSame(nullAddress, nullAddress.plus(0))
        assertTrue(nullAddress.sameLocation(ManagedAddress.nullAddress()))
        assertThrows(RuntimeFault::class.java) { nullAddress.plus(1) }
        assertThrows(RuntimeFault::class.java) { nullAddress.indexChar(0) }
        assertThrows(RuntimeFault::class.java) { nullAddress.utf8() }
        val bytes = byteArrayOf(1, 2)
        val first = ManagedAddress.fromByteArray(bytes)
        val alias = ManagedAddress.fromByteArray(bytes)
        assertTrue(first.sameLocation(alias))
        assertTrue(first.plus(1).sameLocation(alias.plus(1)))
        assertFalse(first.sameLocation(alias.plus(1)))
        assertFalse(first.sameLocation(ManagedAddress.fromByteArray(bytes.copyOf())))
        assertFalse(first.sameLocation(nullAddress))
        assertFalse(ManagedAddress.fromHex("41").sameLocation(ManagedAddress.fromHex("41")))
    }
}
