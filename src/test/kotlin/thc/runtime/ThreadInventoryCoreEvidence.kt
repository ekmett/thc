// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.NodeUtil
import org.junit.jupiter.api.Assertions.*
import thc.Language
import java.util.Collections
import java.util.IdentityHashMap

/** Counts come from genuine Core and the independently retained snapshot population,
 * never from discovered targets, compilation counters, or an interpreted trial. */
internal class ThreadInventoryCoreEvidence(module: Map<String, Any?>, private val name: String) {
    private val evidence = ArrayCoreEvidence(module, name)
    private val scans = when (name) {
        "selfInventory" -> 1
        "forkSnapshot" -> 3
        "boundQuery", "snapshotSize" -> 0
        else -> error("No source call-count proof for $name")
    }
    private val once = if (name == "forkSnapshot") 3 else 2
    val labels: Set<String>

    init {
        val root = evidence.root["expr"] as List<Any?>
        val lambdas = evidence.guestLambdas(root)
        assertEquals(once, lambdas.size, "$name public, immediate state, and optional child roots")
        assertEquals("lam", root[0])
        val immediate = root[2] as List<*>
        assertEquals("app", immediate[0])
        assertSame(lambdas[1], immediate[1], "$name retains its immediately applied runRW# lambda")
        assertEquals(listOf("Int#"), (root[1] as List<Map<*, *>>).map { it["type"] })
        for (lambda in lambdas.drop(1))
            assertEquals(listOf("State# RealWorld"), (lambda[1] as List<Map<*, *>>).map { it["type"] })

        val originalRoots = lambdas.toMutableList()
        if (scans == 0) {
            assertEquals(1, evidence.bindings.size)
            assertEquals(emptyList<String>(), evidence.globalReferences(root))
        } else {
            assertEquals(setOf(name, "occurrences"), evidence.bindings.map { it["name"] }.toSet())
            val recursive = evidence.bindings.single { it["name"] == "occurrences" }
            val id = recursive["id"] as String
            val loop = recursive["expr"] as List<Any?>
            assertEquals("lam", loop[0])
            assertEquals(1, evidence.guestLambdas(loop).size)
            originalRoots.add(loop)
            val formals = loop[1] as List<Map<String, Any?>>
            assertEquals(listOf("ThreadId#", "Array# ThreadId#", "Int#"), formals.map { it["type"] })
            val wanted = formals[0]["id"]
            val array = formals[1]["id"]
            val index = formals[2]["id"]
            fun variable(value: Any?, expected: Any?) {
                assertEquals(listOf("var", expected), (value as List<*>).take(2))
            }
            fun application(value: Any?, operator: String): List<*> {
                val expression = value as List<*>
                assertEquals("app", expression[0])
                assertEquals(listOf("prim", operator), (expression[1] as List<*>).take(2))
                return expression[2] as List<*>
            }
            fun literal(value: Any?, expected: String) {
                assertEquals(listOf("lit", "int", expected), (value as List<*>).take(3))
            }
            val body = loop[2] as List<*>
            assertEquals("case", body[0])
            val condition = application(body[1], "<#")
            variable(condition[0], index)
            variable(application(condition[1], "sizeofArray#").single(), array)
            val alternatives = body[3] as List<List<*>>
            assertEquals(2, alternatives.size)
            val stop = alternatives.single { it[0] == "lit" }
            assertEquals(listOf("int", "0"), stop[1])
            literal(stop[3], "0")
            val next = alternatives.single { it[0] == "default" }[3] as List<*>
            assertEquals("case", next[0])
            val read = application(next[1], "indexArray#")
            variable(read[0], array); variable(read[1], index)
            val recursiveCall = evidence.nodes(loop).single {
                it.firstOrNull() == "app" && (it.getOrNull(1) as? List<*>)?.take(2) == listOf("var", id)
            }
            val arguments = recursiveCall[2] as List<*>
            variable(arguments[0], wanted); variable(arguments[1], array)
            val increment = application(arguments[2], "+#")
            variable(increment[0], index); literal(increment[1], "1")
            assertEquals(listOf(id), evidence.globalReferences(loop), "Exactly one recursive call")

            val listing = evidence.nodes(root).single {
                it.firstOrNull() == "case" && ((it.getOrNull(1) as? List<*>)?.getOrNull(1) as? List<*>)?.take(2) ==
                    listOf("prim", "listThreads#")
            }
            val snapshot = ((((listing[3] as List<*>).single() as List<*>)[2]) as List<*>)[1]
            val calls = evidence.nodes(root).filter {
                it.firstOrNull() == "app" && (it.getOrNull(1) as? List<*>)?.take(2) == listOf("var", id)
            }
            assertEquals(scans, calls.size)
            assertEquals(List(scans) { id }, evidence.globalReferences(root))
            for (call in calls) {
                val inputs = call[2] as List<*>
                assertEquals(3, inputs.size)
                variable(inputs[1], snapshot); literal(inputs[2], "0")
            }
        }
        labels = originalRoots.map { lambda ->
            "lambda ${(lambda[1] as List<Map<String, Any?>>).joinToString { it["name"].toString() }}"
        }.toSet()
        assertEquals(once + if (scans == 0) 0 else 1, labels.size, "$name distinct source roots")
    }

    fun compiledCalls(population: Int): Long {
        require(population >= 1)
        // Each scan enters occurrences at indices 0..population, including its
        // terminal zero case. The fork child enters once, not on each resume.
        return once.toLong() + scans * (population.toLong() + 1)
    }

    fun assertRoots(targets: List<RootCallTarget>) {
        assertEquals(labels, targets.map { it.rootNode.name }.toSet(), "$name source-derived root labels")
        assertEquals(labels.size, targets.size, "$name exact original root inventory")
    }

    companion object {
        private val targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        fun valid(target: RootCallTarget) = targetClass.getMethod("isValidLastTier").invoke(target) == true
        fun rawCompile(target: RootCallTarget) {
            targetClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
            assertTrue(valid(target), "Installed ${target.rootNode.name}")
        }
        fun restoreBoundary(target: RootCallTarget) {
            val runtime = Truffle.getRuntime()
            runtime.javaClass.getMethod("bypassedInstalledCode", targetClass).invoke(runtime, target)
        }
        fun install(targets: List<RootCallTarget>) = targets.forEach { rawCompile(it); restoreBoundary(it) }
        fun interpretedCalls(targets: List<RootCallTarget>) = targets.map {
            targetClass.getMethod("getCallCount").invoke(it) as Int
        }
        fun released(language: Language) {
            val state = language.handoffState.get()
            assertEquals(0, state.arguments.depth); assertEquals(0, state.results.depth)
            assertEquals(0, state.arguments.retainedReferences()); assertEquals(0, state.results.retainedReferences())
            assertNull(state.pending)
        }
        fun joinCompletedForks(registry: GuestThreads) {
            // The Haskell done MVar precedes carrier teardown. Join only this
            // completed-child fixture's context-owned forks, never host threads.
            for (identity in registry.snapshot().filterIsInstance<GuestThreadId>().filter { it.forked }) {
                identity.carrier.get()?.let { carrier ->
                    carrier.join(5000)
                    assertFalse(carrier.isAlive, "Completed guest child must terminate before context close")
                }
                assertEquals(GuestThreadStatus.FINISHED, registry.status(identity))
            }
        }
        fun targets(entry: RootCallTarget): List<RootCallTarget> {
            val found = mutableListOf<RootCallTarget>()
            val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
            fun visit(target: RootCallTarget) {
                if (!seen.add(target)) return
                val root = target.rootNode
                val nodes = if (root is BytecodeRoot) {
                    val arguments = root.bytecodeNode.instructions.flatMap { it.arguments }
                    // The fork action is not a caller-owned DirectCallNode: its
                    // genuine source closure is handed to a fresh thread root.
                    arguments.filter { it.kind == Instruction.Argument.Kind.CONSTANT }
                        .mapNotNull { it.asConstant() as? BytecodeRoot.ClosureTemplate }
                        .forEach { visit(it.target) }
                    listOf(root) + arguments.filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }
                        .mapNotNull { it.asCachedNode() }
                } else listOf(root)
                for (node in nodes.flatMap { NodeUtil.findAllNodeInstances(it, Node::class.java) }) {
                    if (node.javaClass.simpleName in setOf("MakeClosure", "Delay")) {
                        val field = node.javaClass.getDeclaredField("target").also { it.isAccessible = true }
                        visit(field.get(node) as RootCallTarget)
                    }
                    if (node is DirectCallNode) {
                        val callee = node.currentCallTarget as? RootCallTarget ?: continue
                        if (callee.rootNode is GuestRoot) visit(callee)
                    }
                }
                found += target
            }
            visit(entry)
            return found
        }
    }
}
