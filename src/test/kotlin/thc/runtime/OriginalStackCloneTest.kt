// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json
import thc.Language
import java.util.Collections
import java.util.IdentityHashMap

/** Original exported worker + synthetic consumer/corruption controls, not native decoding. */
class OriginalStackCloneTest {
    private fun original() = Json.parse(javaClass.getResource("/core/original-stack-clone.json")!!.readText()) as Map<String, Any?>
    private fun binding(module: Map<String, Any?>) = (module["bindings"] as List<Map<String, Any?>>).single()
    private fun lambda(module: Map<String, Any?>) = binding(module)["expr"] as List<Any?>
    private fun cloneCall(module: Map<String, Any?>) = (lambda(module)[2] as List<Any?>)[1] as List<Any?>
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
    private val boxed = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val consumerId = "consumer-unit:Snapshot.Consumer.capture"

    /** The worker stays unchanged. The synthetic consumer makes its tuple result host-visible. */
    private fun consumer(source: Map<String, Any?>, movedBody: Boolean = false): Map<String, Any?> {
        val worker = binding(source)
        val lam = lambda(source)
        val formals = lam[1] as List<Map<String, Any?>>
        val tuple = (lam[3] as Map<String, Any?>).getValue("resultRep")
        val call = if (movedBody) lam[2] else listOf("app", listOf("var", worker["id"], mapOf("rep" to closure)),
            listOf(listOf("var", formals.single()["id"], mapOf("rep" to state))), listOf(false), false, false, mapOf("rep" to tuple))
        val body = listOf("case", call, "result-pair", listOf(listOf("data", "ghc-internal:GHC.Internal.Types.(#,#)",
            listOf("result-state", "result-snapshot"), listOf("var", "result-snapshot", mapOf("rep" to boxed)),
            mapOf("binders" to listOf(mapOf("id" to "result-state", "lifted" to false, "rep" to state),
                mapOf("id" to "result-snapshot", "lifted" to true, "rep" to boxed))))),
            mapOf("rep" to boxed, "binder" to mapOf("id" to "result-pair", "lifted" to false, "rep" to tuple)))
        val entry = mapOf("id" to consumerId, "name" to "capture", "arity" to 1, "lifted" to true, "rep" to closure,
            "expr" to listOf("lam", formals, body, mapOf("rep" to closure, "resultRep" to boxed)))
        return source + mapOf("instrument" to true, "bindings" to if (movedBody) listOf(entry) else listOf(worker, entry),
            "bindingOrigins" to mapOf(worker["id"] to mapOf("unit" to "ghc-internal", "module" to "GHC.Internal.Stack.CloneStack"),
                consumerId to mapOf("unit" to "consumer-unit", "module" to "Snapshot.Consumer")))
    }
    private fun context(inlining: Boolean = false) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun load(language: Language, backend: String, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val targets = mutableListOf<RootCallTarget>()
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val root = target.rootNode
            val nodes = if (root is BytecodeRoot) listOf(root) + root.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(root)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val next = call.currentCallTarget as? RootCallTarget ?: continue
                if (next.rootNode is GuestRoot) visit(next)
            }
            targets += target
        }
        visit(entry)
        return targets
    }
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun released(language: Language) {
        val handoff = language.handoffState.get()
        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
    }

    @Test fun originalWorkerCapturesTheActualTopOperationBeforeAndAfterCompilation() {
        for (backend in listOf("ast", "bytecode")) for (inlining in listOf(false, true)) for (moved in listOf(false, true)) {
            val retained = mutableListOf<ManagedStackSnapshot>()
            val rendered = mutableListOf<List<String>>()
            context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program = load(language, backend, consumer(original(), moved))
                    val entry = program.entryTarget(consumerId)
                    fun invoke(token: Any? = Unit): ManagedStackSnapshot {
                        val value = Calls.target(entry, arrayOf(0L, token)) as DataValue
                        assertEquals("StackSnapshot", value.layout.name)
                        return value.layout.read(value, 0) as ManagedStackSnapshot
                    }
                    fun check(snapshot: ManagedStackSnapshot) {
                        val top = snapshot.frames.first()
                        assertEquals(if (moved) consumerId else binding(original())["id"], top.coreIdentity?.bindingId)
                        assertEquals(178, top.location?.startLine, "$backend/$inlining/moved=$moved")
                        assertEquals(21, top.location?.startColumn)
                        assertTrue((top.location?.path ?: top.location?.name).orEmpty().endsWith("/GHC/Internal/Stack/CloneStack.hs"))
                        assertEquals(if (backend == "ast") ManagedStackLocationKind.CURRENT_NODE else ManagedStackLocationKind.BYTECODE, top.locationKind)
                        assertEquals(if (moved) 1 else 2, snapshot.frames.size)
                        if (!moved) assertEquals(consumerId, snapshot.frames[1].coreIdentity?.bindingId)
                        released(language)
                    }
                    retained += invoke().also(::check)
                    assertEquals(0L, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                    repeat(3) { check(invoke()) }
                    val targets = activeTargets(entry)
                    assertEquals(if (moved) 1 else 2, targets.size)
                    targets.forEach { it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true); valid(it) }
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    retained += invoke().also(::check)
                    assertEquals(before + targets.size, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                    targets.forEach(::valid)
                    rendered += retained.map { it.renderLines() }
                    assertThrows(RuntimeFault::class.java) { invoke(7L) }
                    released(language)
                    for (counter in listOf("unsupportedTraps", "blackholes")) assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong())
                } finally { context.leave() }
            }
            retained.forEachIndexed { index, snapshot -> assertEquals(rendered[index], snapshot.renderLines(), "after context close") }
        }
    }

    @Test fun rawContractRejectsMalformedDeclarationsOccurrencesAndTupleProofs() {
        val call = cloneCall(original())
        val meta = call[6] as Map<String, Any?>
        val descriptor = meta.getValue("foreignCall") as Map<String, Any?>
        val target = descriptor.getValue("target") as Map<String, Any?>
        val result = meta.getValue("rep") as Map<String, Any?>
        assertTrue(CoreStackForeign.validate(meta, listOf(state), listOf(false)))
        for ((key, value) in listOf("schema" to 1.0, "schema" to 2L, "arity" to 0L, "suppliedArity" to 2L,
                "convention" to "ccall", "safety" to "unsafe", "extra" to true))
            assertThrows(RuntimeFault::class.java) { CoreStackForeign.validate(meta + ("foreignCall" to (descriptor + (key to value))), listOf(state), listOf(false)) }
        for ((key, value) in listOf("unit" to null, "unit" to "main", "kind" to "dynamic", "isFunction" to false, "extra" to true))
            assertThrows(RuntimeFault::class.java) { CoreStackForeign.validate(meta + ("foreignCall" to (descriptor + ("target" to (target + (key to value))))), listOf(state), listOf(false)) }
        val wrongStates = listOf(null, emptyMap(), state + ("kind" to "unknown"), state + ("primReps" to listOf("IntRep")),
            state + ("evaluated" to 1L), state + mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple", "components" to emptyList<Any?>()),
            boxed, state + ("vector" to emptyMap<String, Any?>()))
        for (bad in wrongStates) {
            assertThrows(RuntimeFault::class.java) { CoreStackForeign.validate(meta, listOf(bad), listOf(false)) }
            assertThrows(RuntimeFault::class.java) { CoreStackForeign.validate(meta + ("foreignCall" to (descriptor + ("argumentReps" to listOf(bad)))), listOf(state), listOf(false)) }
        }
        for (flags in listOf(emptyList(), listOf(true), listOf(0L), listOf(false, false)))
            assertThrows(RuntimeFault::class.java) { CoreStackForeign.validate(meta, listOf(state), flags) }
        for (args in listOf(emptyList(), listOf(state, state)))
            assertThrows(RuntimeFault::class.java) { CoreStackForeign.validate(meta, args, listOf(false)) }
        val components = result["components"] as List<Map<String, Any?>>
        for (bad in listOf(result + ("primReps" to emptyList<String>()), result - "aggregate", result + ("extra" to true),
                result + ("components" to components.reversed()), result + ("components" to listOf(components[0], boxed)),
                result + ("components" to listOf(components[0], components[1] + ("evaluated" to false))))) {
            assertThrows(RuntimeFault::class.java) { CoreStackForeign.validate(meta + ("rep" to bad), listOf(state), listOf(false)) }
            assertThrows(RuntimeFault::class.java) { CoreStackForeign.validate(meta + ("foreignCall" to (descriptor + ("resultRep" to bad))), listOf(state), listOf(false)) }
        }
        assertFalse(CoreStackForeign.validate(meta - "foreignCall", listOf(state), listOf(false)))
        assertFalse(CoreStackForeign.validate(meta + ("foreignCall" to (descriptor + ("target" to (target + ("symbol" to "stg_decodeStackzh"))))), listOf(state), listOf(false)))
    }

    @Test fun bothLoadersRejectShadowedHeadsAndStoredStateRelabeling() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (replacement in listOf(null, "", 7L, binding(original())["id"],
                        ((lambda(original())[1] as List<Map<String, Any?>>).single())["id"])) {
                    val source = original()
                    val head = cloneCall(source)[1] as MutableList<Any?>
                    head[1] = replacement
                    assertThrows(RuntimeFault::class.java) { load(language, backend, consumer(source)) }
                }
                for (bad in listOf(boxed, state + mapOf("kind" to "unknown", "primReps" to listOf("IntRep")),
                        state + mapOf("kind" to "unknown", "primReps" to listOf("BoxedRep Nothing")),
                        state + mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple", "components" to emptyList<Any?>()))) {
                    val source = original()
                    val formal = (lambda(source)[1] as List<MutableMap<String, Any?>>).single()
                    formal["rep"] = bad
                    assertThrows(RuntimeFault::class.java) { load(language, backend, consumer(source)) }
                }
            } finally { context.leave() }
        }
    }
}
