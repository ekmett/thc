// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import thc.PrimopTestContext.primopTestContext
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
            val evidence = ArrayCoreEvidence(module, "probe")
            val expression = evidence.root["expr"]
            assertEquals(2, evidence.guestLambdas(expression).size, "$stage exported lambda inventory")
            // eb6aa293 lowers this exact immediate State# application in-frame.
            // Keep its source proof separate from the executed guest-root count.
            val lowered = evidence.loweredStateLambdas(expression)
            assertEquals(1, lowered.size, "$stage lowered root inventory")
            assertSame(expression, lowered.single(), "$stage public probe remains the root")
            val expectedEntries = lowered.size.toLong()
            val dummy = evidence.bindings.single { it["name"] == "bottomDummy" }
            assertEquals(listOf("var", dummy["id"]), (dummy["expr"] as List<*>).take(2),
                "$stage original nonterminating dummy")
            val literal = evidence.bindings.single { (it["expr"] as List<*>).take(3) ==
                listOf("lit", "string-bytes", "41") }
            assertEquals(3, evidence.bindings.size, "$stage no additional helper closures")
            assertEquals(setOf(dummy["id"], literal["id"]), evidence.globalReferences(expression).toSet(),
                "$stage original lazy dummy and static address")
            val calls = nodes(module["bindings"]).filter { it.firstOrNull() == "app" &&
                (it.getOrNull(1) as? List<*>)?.firstOrNull() == "prim" }.toList()
            assertEquals(setOf("getCurrentCCS#", "eqAddr#", "neAddr#"),
                calls.map { (it[1] as List<*>)[1] }.toSet(), "$stage retained primitives")
            val current = calls.single { (it[1] as List<*>)[1] == "getCurrentCCS#" }
            assertEquals(listOf(true, false), current[3])
            val dummyArgument = (current[2] as List<List<Any?>>).first()
            assertEquals(listOf("var", dummy["id"]), dummyArgument.take(2),
                "$stage getCurrentCCS# keeps the original dummy")
            assertFalse(CoreRepresentations.expression(dummyArgument).evaluated,
                "$stage getCurrentCCS# dummy remains lazy")
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
                    fun check(selector: Int, answer: Long) =
                        assertEquals(answer, function.execute(selector.toLong()).asLong(), "$stage/$backend/$selector")
                    fun counter(name: String) = (program.diagnostics().getValue(name) as Number).toLong()
                    expected.forEachIndexed(::check)
                    assertEquals(0L, counter("compiledEntries"))
                    assertTrue(function.invokeMember("compile").asBoolean(), "$stage/$backend JIT installation")
                    val target = program.entryTarget("probe")
                    fun valid() = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target),
                        "$stage/$backend installed probe remains valid")
                    valid()
                    assertEquals(0L, counter("compiledEntries"), "$stage/$backend installation never invokes probe")
                    expected.forEachIndexed { selector, answer ->
                        val before = counter("compiledEntries")
                        check(selector, answer)
                        assertEquals(before + expectedEntries, counter("compiledEntries"),
                            "$stage/$backend/$selector exact source-derived compiled entries")
                        assertSame(target, program.entryTarget("probe"), "$stage/$backend retained probe target")
                        valid()
                        assertEquals(0L, counter("unsupportedTraps"))
                        val pools = language.handoffState.get()
                        assertEquals(0, pools.arguments.depth); assertEquals(0, pools.results.depth)
                        assertEquals(0, pools.arguments.retainedReferences())
                        assertEquals(0, pools.results.retainedReferences())
                    }
                    assertEquals(expectedEntries * expected.size, counter("compiledEntries"),
                        "$stage/$backend every selector enters the lowered probe root in installed code")
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
