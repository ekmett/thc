// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.EntryValue
import thc.Language
import thc.executionContext

/** Explicit host compilation must follow the direct call's actual split target. */
class HostEntryCompilationTest {
    private fun module(): Map<String, Any?> {
        val body = listOf("app", listOf("prim", "+#"),
            listOf(listOf("var", "input"), listOf("lit", "int", "1")),
            listOf(false, false))
        val function = listOf("lam", listOf(mapOf("id" to "input", "name" to "input", "lifted" to false)), body)
        return mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.HostEntryCompilation",
            "instrument" to true, "constructors" to emptyList<Any>(),
            "bindings" to listOf(mapOf("id" to "entry", "name" to "entry", "arity" to 1,
                "lifted" to true, "expr" to function)))
    }

    private fun checkHostCompilation(backend: String) = executionContext().use { context ->
        context.initialize("thc")
        context.enter()
        try {
            val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
            val program: ExecutableProgram = when (backend) {
                "ast" -> Program(language, module())
                "bytecode" -> BytecodeProgram(language, module())
                else -> error("Unexpected backend: $backend")
            }
            val original = program.entryTarget("entry")
            val host = program.hostEntryTarget(1)
            assertFalse(host.rootNode.isCloningAllowed, "The host dispatch tree must remain stable")
            val function = context.asValue(EntryValue(program, "entry", 1))
            fun check(input: Long) = assertEquals(input + 1L, function.execute(input).asLong(), "$backend input $input")
            repeat(20) { check(it.toLong()) }

            val calls = NodeUtil.findAllNodeInstances(host.rootNode, DirectCallNode::class.java)
                .filter { it.callTarget === original }
            assertEquals(1, calls.size, "Find the warmed host call to the guest entry")
            val call = calls.single()
            assertTrue(call.isCallTargetCloningAllowed, "$backend entry must support real Truffle splitting")
            assertTrue(call.cloneCallTarget(), "Force a real split independently of heuristic thresholds")
            val active = call.currentCallTarget as RootCallTarget
            assertNotSame(original, active)
            assertSame(original, call.callTarget, "The direct node retains its original target identity")
            assertSame(original, program.entryTarget("entry"), "Splitting must not rewrite the closure target")

            // Train the clone before requesting compilation so the regression isolates
            // target selection from initially unseen DSL operations or entry profiles.
            repeat(20) { check(it.toLong()) }
            assertTrue(function.invokeMember("compile").asBoolean())
            val optimizingTarget = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
            assertEquals(true, optimizingTarget.getMethod("isValidLastTier").invoke(active),
                "Host compilation must install the active split guest target")
            assertEquals(true, optimizingTarget.getMethod("isValidLastTier").invoke(host),
                "Host compilation must also install the stable bridge used by the public executable value")
            assertSame(active, call.currentCallTarget)
            assertSame(original, program.entryTarget("entry"))

            fun compiledEntries() = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
            for (input in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 3_000_000_001L, -3_000_000_001L, 0L, 7L)) {
                val before = compiledEntries()
                check(input)
                assertTrue(compiledEntries() > before, "$backend input $input must enter installed guest code")
            }
            assertEquals(0L, program.diagnostics().getValue("unsupportedTraps"))
        } finally {
            context.leave()
        }
    }

    @Test fun astHostCompilationFollowsTheActiveSplit() = checkHostCompilation("ast")

    @Test fun bytecodeHostCompilationFollowsTheActiveSplit() = checkHostCompilation("bytecode")
}
