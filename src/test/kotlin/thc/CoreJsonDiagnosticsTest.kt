// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import com.oracle.truffle.api.TruffleLanguage
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.BytecodeProgram
import thc.runtime.Calls
import thc.runtime.ExecutableProgram
import thc.runtime.Program

class CoreJsonDiagnosticsTest {
    private fun source(value: Any?) = CoreJsonIndex.fromBytes(Json.stringify(value).toByteArray())
    private fun binding(id: String, expression: Any?) = mapOf("id" to id, "name" to id,
        "arity" to 1, "type" to "Int# -> Int#", "lifted" to true,
        "expr" to listOf("lam", listOf(mapOf("id" to "x", "name" to "x", "type" to "Int#",
            "lifted" to false, "coercion" to false)), expression))
    private fun count(program: ExecutableProgram, key: String) = (program.diagnostics().getValue(key) as Number).toLong()

    @Test fun counterHandlesHaveOnlyScalarFieldsAndKeepLazyHashAndCloseTotals() {
        for (type in listOf(CoreJsonIndex.Counters::class.java, CoreJsonBindings.Counters::class.java)) {
            assertTrue(type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.all {
                it.type.isPrimitive || it.type == Long::class.javaObjectType
            }, "Detached handles must not capture source, adapter, arrays, maps or callbacks: $type")
        }
        val input = source(mapOf("items" to listOf("a", "b")))
        val totals = input.counters
        val before = totals.statistics()
        assertEquals(0L, before.sourceHashBytesScanned)
        assertEquals(0L, before.sourceIdentityBytes)
        input.sha256()
        val hashed = totals.statistics()
        assertEquals(before.sourceByteSize.toLong(), hashed.sourceHashBytesScanned)
        assertEquals(32L, hashed.sourceIdentityBytes)
        assertEquals(before.indexByteSize + 32, hashed.indexByteSize)
        input.sha256()
        assertEquals(hashed, totals.statistics(), "second digest access adds no work or identity allocation")
        input.root.member("items")!!.elements()[1].decode()
        val demanded = totals.statistics()
        assertEquals(1L, demanded.decodedSpanCount)
        assertTrue(demanded.decodedByteCount > 0)
        assertTrue(demanded.navigationByteReads > before.navigationByteReads)
        assertTrue(demanded.regeneratedSourceBytes > before.regeneratedSourceBytes)
        assertEquals(demanded, input.statistics())
        input.close()
        assertThrows(IllegalStateException::class.java) { input.statistics() }
        assertEquals(demanded, totals.statistics(), "admission/work totals survive storage disposal")
    }

    private data class Prepared(val program: ExecutableProgram, val entry: Any?,
        val unused: List<WeakReference<*>>, val liveSource: WeakReference<CoreJsonIndex>,
        val admittedBytes: Long, val admittedIndexBytes: Long)

    /** Separate stack frame: only the selected Program and weak ownership probes escape. */
    private fun prepare(language: Language, backend: String, sharedAdapter: Boolean): Prepared {
        val live = source(mapOf("schema" to 1, "ghc" to "9.14.1", "unit" to "test", "module" to "Live",
            "constructors" to emptyList<Any>(), "bindings" to listOf(
            binding("test:Live.entry", listOf("app", listOf("var", "test:Live.cold"), listOf(listOf("var", "x")), listOf(false))),
            binding("test:Live.cold", listOf("app", listOf("prim", "+#"), listOf(listOf("var", "x"),
                listOf("lit", "int", "3")), listOf(false, false))))))
        // No source/constructor/foreign metadata legitimately retains this module.
        val unused = source(mapOf("schema" to 1, "ghc" to "9.14.1", "unit" to "test", "module" to "Unused",
            "constructors" to emptyList<Any>(), "bindings" to listOf(
            binding("test:Unused.never", listOf("unsupported", "unreachable ".repeat(1024))))))
        val adapter = CoreJsonBindings(false)
        val unusedAdapter = if (sharedAdapter) adapter else CoreJsonBindings(false)
        val totals = CoreJsonLoadingStatistics()
        totals.include(live, adapter)
        totals.include(unused, unusedAdapter)
        val merged = CoreModules.merge(listOf(adapter.module(live.root), unusedAdapter.module(unused.root)))
        val linked = CoreModules.reachable(merged, "test:Live.entry", strictLink = true) + mapOf(
            "instrument" to true, "sourceNotesEnabled" to false, "coreLoadingStatistics" to totals)
        val program: ExecutableProgram = if (backend == "ast") Program(language, linked, false)
            else BytecodeProgram(language, linked, false)
        val entry = program.entryValue("test:Live.entry")
        val discarded = listOf(WeakReference(unused)) +
            if (sharedAdapter) emptyList() else listOf(WeakReference(unusedAdapter))
        return Prepared(program, entry, discarded, WeakReference(live),
            live.statistics().sourceByteSize.toLong() + unused.statistics().sourceByteSize,
            live.statistics().indexByteSize + unused.statistics().indexByteSize)
    }

    private fun reclaimed(references: List<WeakReference<*>>) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            System.gc()
            if (references.all { it.get() == null }) return
            assertTrue(System.nanoTime() < deadline, "Diagnostics retained an unused module source/adapter")
            Thread.sleep(10)
        }
    }

    @Test fun unusedModuleIsCollectibleWhileColdCalleeDemandUpdatesCumulativeDiagnosticsOnce() {
        for (backend in listOf("ast", "bytecode")) for (sharedAdapter in listOf(false, true)) {
            executionContext().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val prepared = prepare(language, backend, sharedAdapter)
                    val program = prepared.program
                    assertEquals(3L, count(program, "jsonBindingHeaders"), "one shared adapter is counted once")
                    assertEquals(1L, count(program, "jsonBodyMaterializations"))
                    assertEquals(1L, count(program, "loweredRootCount"))
                    val before = program.diagnostics().filterKeys { it.startsWith("json") }
                    reclaimed(prepared.unused)
                    assertNotNull(prepared.liveSource.get(), "reachable cold spans still own their immutable source")
                    assertEquals(before, program.diagnostics().filterKeys { it.startsWith("json") })
                    assertEquals(prepared.admittedBytes, count(program, "jsonSourceBytes"))
                    assertEquals(prepared.admittedIndexBytes, count(program, "jsonIndexPrimitiveBytes"))
                    fun call(): Any? = Calls.target(program.hostEntryTarget(1), arrayOf(prepared.entry, arrayOf(7L)))
                    assertEquals(10L, call())
                    assertEquals(2L, count(program, "jsonBodyMaterializations"))
                    assertEquals(2L, count(program, "loweredRootCount"))
                    for (key in listOf("jsonDecodedSpanCount", "jsonDecodedByteCount", "jsonNavigationByteReads",
                            "jsonRegeneratedSourceBytes", "jsonExpressionViews", "jsonScalarDecodes")) {
                        assertTrue(count(program, key) > (before.getValue(key) as Number).toLong(), key)
                    }
                    val after = program.diagnostics().filterKeys { it.startsWith("json") }
                    assertEquals(10L, call())
                    assertEquals(after, program.diagnostics().filterKeys { it.startsWith("json") },
                        "cached callee does not decode/materialize again")
                    Reference.reachabilityFence(prepared)
                } finally { context.leave() }
            }
        }
    }
}
