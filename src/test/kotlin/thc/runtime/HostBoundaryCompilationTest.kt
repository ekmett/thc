// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import thc.EntryValue
import thc.Language
import thc.executionContext

/** A valid guest target does not imply that HotSpot's shared entry stub survived. */
@Isolated("Retires the JVM-wide Truffle call-boundary stub")
@Execution(ExecutionMode.SAME_THREAD)
class HostBoundaryCompilationTest {
    private fun module(): Map<String, Any?> = mapOf(
        "schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.HostBoundaryCompilation",
        "instrument" to true, "constructors" to emptyList<Any>(),
        "bindings" to listOf(mapOf("id" to "entry", "name" to "entry", "arity" to 1,
            "lifted" to true, "expr" to listOf("lam", listOf(mapOf("id" to "input", "lifted" to false)),
                listOf("app", listOf("prim", "+#"), listOf(listOf("var", "input"), listOf("lit", "int", "1")),
                    listOf(false, false))))))

    private fun boundaryMethod(): Any {
        val jvmci = Class.forName("jdk.vm.ci.runtime.JVMCI").getMethod("getRuntime").invoke(null)
        val backend = Class.forName("jdk.vm.ci.runtime.JVMCIRuntime")
            .getMethod("getHostJVMCIBackend").invoke(jvmci)
        val metaAccess = Class.forName("jdk.vm.ci.runtime.JVMCIBackend")
            .getMethod("getMetaAccess").invoke(backend)
        val method = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
            .getDeclaredMethod("callBoundary", Array<Any?>::class.java)
        return Class.forName("jdk.vm.ci.meta.MetaAccessProvider")
            .getMethod("lookupJavaMethod", java.lang.reflect.Executable::class.java).invoke(metaAccess, method)
    }

    private fun checkBoundaryCompilation(backend: String) = executionContext().use { context ->
        context.initialize("thc")
        context.enter()
        try {
            val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
            val program: ExecutableProgram = when (backend) {
                "ast" -> Program(language, module())
                "bytecode" -> BytecodeProgram(language, module())
                else -> error("Unexpected backend: $backend")
            }
            val guest = program.entryTarget("entry")
            val host = program.hostEntryTarget(1)
            val function = context.asValue(EntryValue(program, "entry", 1))
            repeat(40) { assertEquals(it.toLong() + 1, function.execute(it.toLong()).asLong()) }
            assertTrue(function.invokeMember("compile").asBoolean())

            val targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
            val runtime = Truffle.getRuntime()
            val repair = runtime.javaClass.getMethod("bypassedInstalledCode", targetClass)
            val boundary = boundaryMethod()
            val hasCode = Class.forName("jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod").getMethod("hasCompiledCode")
            val reprofile = Class.forName("jdk.vm.ci.meta.ResolvedJavaMethod").getMethod("reprofile")
            fun validTargets() {
                assertEquals(true, targetClass.getMethod("isValidLastTier").invoke(guest))
                assertEquals(true, targetClass.getMethod("isValidLastTier").invoke(host))
            }
            fun entries() = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
            fun calls() = listOf(guest, host).map { targetClass.getMethod("getCallCount").invoke(it) }

            // Establish the control state, then retire only the shared boundary nmethod.
            // Always restore it: the JVM-wide stub is shared by the remaining tests.
            repair.invoke(runtime, host)
            try {
                assertEquals(true, hasCode.invoke(boundary))
                validTargets()
                reprofile.invoke(boundary)
                assertEquals(false, hasCode.invoke(boundary), "Retire the boundary without executing another guest call")
                validTargets()
                val beforeCompile = entries()
                val callsBeforeCompile = calls()
                assertTrue(function.invokeMember("compile").asBoolean())
                val restoredBeforeCall = hasCode.invoke(boundary)
                validTargets()
                assertEquals(beforeCompile, entries(), "Compilation must not execute compiled guest code")
                assertEquals(callsBeforeCompile, calls(), "Compilation must not settle interpreted or first-tier calls")

                val beforeCall = entries()
                assertEquals(3_000_000_002L, function.execute(3_000_000_001L).asLong())
                val delta = entries() - beforeCall
                println("HOST_BOUNDARY $backend restoredBeforeCall=$restoredBeforeCall firstCompiledDelta=$delta")
                assertAll(
                    { assertEquals(true, restoredBeforeCall, "Explicit compilation must restore the shared stub before the first public call") },
                    { assertTrue(delta > 0, "The first public call must enter installed guest code") })
            } finally {
                repair.invoke(runtime, host)
                assertEquals(true, hasCode.invoke(boundary), "Do not leave subsequent tests with a retired boundary")
            }
        } finally {
            context.leave()
        }
    }

    @Test fun astCompilationRestoresTheRetiredBoundary() = checkBoundaryCompilation("ast")

    @Test fun bytecodeCompilationRestoresTheRetiredBoundary() = checkBoundaryCompilation("bytecode")
}
