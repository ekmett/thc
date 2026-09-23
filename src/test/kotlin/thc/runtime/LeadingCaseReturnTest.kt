package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

private typealias LeadingCore = List<Any?>

class LeadingCaseReturnTest {
    private val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val data = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val closure = data + ("kind" to "closure")
    private fun variable(id: String, source: String? = null): LeadingCore =
        if (source == null) listOf("var", id) else listOf("var", id, mapOf("source" to source))
    private fun integer(n: Long): LeadingCore = listOf("lit", "int", n.toString())
    private fun parameter(id: String, rep: Map<String, Any> = long) = mapOf(
        "id" to id, "name" to id, "lifted" to (rep !== long), "coercion" to false, "rep" to rep)
    private fun lambda(args: List<Map<String, Any>>, body: LeadingCore, strict: List<Boolean> = List(args.size) { false },
                       result: Map<String, Any> = long): LeadingCore = listOf("lam", args, body,
        mapOf("rep" to closure, "resultRep" to result, "entryStrict" to strict))
    private fun binding(id: String, rhs: LeadingCore): Map<String, Any?> =
        mapOf("id" to id, "name" to id, "lifted" to true, "expr" to rhs)
    private fun apply(fn: LeadingCore, args: List<LeadingCore>, lifted: List<Boolean> = List(args.size) { false }): LeadingCore =
        listOf("app", fn, args, lifted, false, false)
    private fun plus(a: LeadingCore, b: LeadingCore) = apply(listOf("prim", "+#"), listOf(a, b))
    private fun constructor(id: String, reps: List<List<String>> = emptyList()) = mapOf(
        "id" to id, "name" to id, "arity" to reps.size, "kind" to "boxed", "fieldReps" to reps,
        "strictFields" to List(reps.size) { false }, "fieldLifted" to List(reps.size) { false })
    private fun worker(extra: Boolean = false, strictTree: Boolean = false, captured: Boolean = false,
                       coldBody: LeadingCore? = null): LeadingCore {
        val payload = if (captured) plus(variable("payload"), variable("captured")) else variable("payload")
        val body = listOf("case", variable("tree"), "treeCase", listOf(
            listOf("data", "Stop", emptyList<String>(), variable("acc", "arm")),
            listOf("data", "Next", listOf("payload"), coldBody ?: plus(variable("acc"), payload),
                mapOf("binders" to listOf(parameter("payload")))),
            listOf("default", null, emptyList<String>(), integer(-1))),
            mapOf("rep" to long, "binder" to parameter("treeCase", data), "source" to "case", "sourceNotes" to listOf("case")))
        return lambda(listOf(parameter("acc"), parameter("tree", data)) +
            if (extra) listOf(parameter("unused", data)) else emptyList(), body,
            listOf(false, strictTree) + if (extra) listOf(true) else emptyList())
    }
    private fun driver(extra: Boolean = false, tail: Boolean = false): LeadingCore {
        val call = apply(variable("fn"), listOf(variable("acc"), variable("tree")) +
            if (extra) listOf(variable("unused")) else emptyList(),
            listOf(false, true) + if (extra) listOf(true) else emptyList())
        return lambda(listOf(parameter("fn", closure), parameter("acc"), parameter("tree", data)) +
            if (extra) listOf(parameter("unused", data)) else emptyList(), if (tail) call else plus(call, integer(1)))
    }
    private fun module(bindings: List<Map<String, Any?>>, notes: Boolean, diagnostic: Boolean = false): Map<String, Any?> = mapOf(
        "bindings" to (listOf(binding("stop", listOf("con", "Stop", 0)), binding("other", listOf("con", "Other", 0)),
            binding("next", apply(listOf("con", "Next", 1), listOf(integer(7))))) + bindings),
        "constructors" to listOf(constructor("Stop"), constructor("Other"), constructor("Next", listOf(listOf("IntRep"))),
            mapOf("id" to "Unsupported", "name" to "Unsupported", "arity" to 0, "kind" to "unboxed-tuple", "fieldReps" to emptyList<Any>())),
        "instrument" to true, "diagnosticUnsupported" to diagnostic,
        "sourceNotesEnabled" to notes,
        "sourceFiles" to listOf(mapOf("id" to "Case.hs", "path" to "Case.hs", "content" to "case tree of Stop -> acc \n")),
        "sourceSpans" to listOf(
            mapOf("id" to "case", "file" to "Case.hs", "startLine" to 1, "startColumn" to 1, "endLine" to 1, "endColumn" to 25,
                "charIndex" to 0, "charLength" to 24),
            mapOf("id" to "arm", "file" to "Case.hs", "startLine" to 1, "startColumn" to 22, "endLine" to 1, "endColumn" to 25,
                "charIndex" to 21, "charLength" to 3)))
    private fun each(enabled: Boolean = true, notes: Boolean = true, diagnostic: Boolean = false,
                     bindings: List<Map<String, Any?>>, action: (String, ExecutableProgram) -> Unit) {
        val previous = System.getProperty(LEADING_CASE_RETURN_PROPERTY)
        System.setProperty(LEADING_CASE_RETURN_PROPERTY, enabled.toString())
        try {
            executionContext().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    for (backend in listOf("ast", "bytecode")) {
                        val m = module(bindings, notes, diagnostic)
                        action(backend, if (backend == "ast") Program(language, m) else BytecodeProgram(language, m))
                    }
                } finally { context.leave() }
            }
        } finally { if (previous == null) System.clearProperty(LEADING_CASE_RETURN_PROPERTY) else System.setProperty(LEADING_CASE_RETURN_PROPERTY, previous) }
    }
    private fun run(p: ExecutableProgram, name: String, vararg args: Any?): Any? =
        Calls.target(p.hostEntryTarget(args.size), arrayOf(p.entryValue(name), arrayOf(*args)))
    private fun count(p: ExecutableProgram, name: String = "leadingCaseReturns") = (p.diagnostics().getValue(name) as Number).toLong()
    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
    }
    private class CloneCaller(target: RootCallTarget) : RootNode(null) {
        @Child private var call = DirectCallNode.create(target)
        override fun execute(frame: VirtualFrame): Any? = Calls.direct(call, frame.arguments)
        fun cloneTarget(): RootCallTarget {
            callTarget
            assertTrue(call.cloneCallTarget())
            return call.clonedCallTarget as RootCallTarget
        }
    }
    private fun leadingNodes(root: RootNode): List<LeadingCaseReturnNode> {
        if (root !is BytecodeRoot) return NodeUtil.findAllNodeInstances(root, LeadingCaseReturnNode::class.java)
        // Bytecode DSL cached operation nodes are not ordinary @Children entries.
        return root.bytecodeNode.instructions.flatMap { it.arguments }
            .filter { it.descriptor.kind == Instruction.Argument.Kind.NODE_PROFILE }
            .mapNotNull { it.asCachedNode() }.distinct()
            .flatMap { NodeUtil.findAllNodeInstances(it, LeadingCaseReturnNode::class.java) }
    }
    private fun thunk(value: Any? = null, failure: Boolean = false) = Thunk(object : RootNode(null) {
        override fun execute(frame: VirtualFrame): Any? = if (failure) throw RuntimeFault("forced unused argument") else value
    }.callTarget, null)

    @Test fun onlyCompiledDirectCallsShortcutAndPreservePendingWorkAndFullWidthResults() {
        for (enabled in listOf(false, true)) each(enabled, bindings = listOf(binding("worker", worker()), binding("driver", driver()),
            binding("tailDriver", driver(tail = true)))) { backend, p ->
            val fn = p.entryValue("worker"); val stop = p.entryValue("stop"); val next = p.entryValue("next")
            assertEquals(enabled, (p.entryTarget("worker").rootNode as GuestRoot).leadingCaseReturn != null)
            repeat(25) { assertEquals(12L, run(p, "driver", fn, 11L, stop)); assertEquals(19L, run(p, "driver", fn, 11L, next)) }
            repeat(10) { assertEquals(11L, run(p, "tailDriver", fn, 11L, stop)) }
            assertEquals(0L, count(p), "$backend interpreter must retain ordinary calls")
            compile(p.entryTarget("driver")); compile(p.entryTarget("tailDriver"))
            val before = count(p)
            for (value in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 3_000_000_017L)) {
                assertEquals(value + 1, run(p, "driver", fn, value, stop), backend)
                assertEquals(value, run(p, "tailDriver", fn, value, stop), backend)
            }
            assertEquals(if (enabled) before + 6 else before, count(p), backend)
            val hits = count(p)
            assertEquals(19L, run(p, "driver", fn, 11L, next), backend)
            assertEquals(0L, run(p, "driver", fn, 11L, p.entryValue("other")), backend)
            assertEquals(hits, count(p), "$backend nonmatching layouts must execute the original case")
        }
    }

    @Test fun unusedStrictFormalsAreForcedBeforeShortcutAndPapPrefixesOnlyAtSaturation() = each(bindings = listOf(
        binding("worker", worker(extra = true, strictTree = true)), binding("driver", driver(extra = true)),
        binding("saturate", lambda(listOf(parameter("fn", closure), parameter("unused", data)),
            plus(apply(variable("fn"), listOf(variable("unused")), listOf(true)), integer(1)))))) { backend, p ->
        val fn = p.entryValue("worker") as Closure; val stop = p.entryValue("stop")
        repeat(25) {
            assertEquals(12L, run(p, "driver", fn, 11L, stop, stop))
            assertEquals(12L, run(p, "driver", fn, 11L, stop, thunk(stop)))
        }
        compile(p.entryTarget("driver"))
        val delayed = thunk(stop); val before = count(p, "thunkEvaluations")
        assertEquals(12L, run(p, "driver", fn, 11L, stop, delayed), backend)
        assertEquals(before + 1, count(p, "thunkEvaluations")); assertEquals(2, delayed.state)
        val hits = count(p)
        assertThrows(RuntimeFault::class.java) { run(p, "driver", fn, 11L, stop, thunk(failure = true)) }
        assertEquals(hits, count(p), "A throwing strict argument prevents the shortcut")
        val prefix = thunk(stop)
        val pap = fn.pap(arrayOf(31L, prefix))
        assertEquals(0, prefix.state, "PAP allocation must remain lazy")
        repeat(25) {
            assertEquals(32L, run(p, "saturate", pap, stop))
            assertEquals(32L, run(p, "saturate", fn.pap(arrayOf(31L, thunk(stop))), thunk(stop)))
        }
        compile(p.entryTarget("saturate"))
        val fresh = thunk(stop); val freshPap = fn.pap(arrayOf(47L, fresh)); val freshUnused = thunk(stop)
        val papHits = count(p)
        assertEquals(48L, run(p, "saturate", freshPap, freshUnused), backend)
        assertEquals(papHits + 1, count(p), "Both prefix and suffix are forced before a saturated PAP hit")
        assertEquals(2, fresh.state); assertEquals(2, freshUnused.state)
    }

    @Test fun unmarkedThunkScrutineeFallsBackWhileMarkedScrutineeCanReturnAfterForcing() {
        for (strict in listOf(false, true)) each(bindings = listOf(binding("worker", worker(strictTree = strict)), binding("driver", driver()))) { backend, p ->
            val fn = p.entryValue("worker"); val stop = p.entryValue("stop")
            repeat(25) {
                assertEquals(12L, run(p, "driver", fn, 11L, stop))
                assertEquals(12L, run(p, "driver", fn, 11L, thunk(stop)))
            }
            compile(p.entryTarget("driver"))
            val delayed = thunk(stop); val before = count(p); val evaluations = count(p, "thunkEvaluations")
            assertEquals(12L, run(p, "driver", fn, 11L, delayed), backend)
            assertEquals(2, delayed.state); assertEquals(evaluations + 1, count(p, "thunkEvaluations"))
            assertEquals(before + if (strict) 1 else 0, count(p), backend)
        }
    }

    @Test fun capturedEnvironmentOffsetAndOverapplicationRemainCorrect() = each(bindings = listOf(
        binding("factory", lambda(listOf(parameter("captured")), worker(captured = true), result = closure)),
        binding("worker", worker()), binding("driver", driver()), binding("over", lambda(listOf(parameter("fn", closure), parameter("acc"), parameter("tree", data)),
            apply(variable("fn"), listOf(variable("acc"), variable("tree"), integer(1)), listOf(false, true, false)))))) { backend, p ->
        val fn = run(p, "factory", 100L) as Closure; val stop = p.entryValue("stop"); val next = p.entryValue("next")
        assertNotNull(fn.environment)
        assertEquals(2, (fn.target.rootNode as GuestRoot).entryArgumentOffset)
        assertNull((fn.target.rootNode as GuestRoot).leadingCaseReturn, "Captured entry validation stays on the normal path")
        repeat(25) { assertEquals(12L, run(p, "driver", fn, 11L, stop)); assertEquals(119L, run(p, "driver", fn, 11L, next)) }
        compile(p.entryTarget("driver"))
        val before = count(p)
        assertEquals(Long.MAX_VALUE, run(p, "driver", fn, Long.MAX_VALUE - 1, stop), backend)
        assertEquals(before, count(p))
        // Returning an Int# does not make overapplication valid, even on the trivial arm.
        val eligible = p.entryValue("worker")
        repeat(20) { assertThrows(RuntimeFault::class.java) { run(p, "over", eligible, 11L, stop) } }
        compile(p.entryTarget("over"))
        // Throwing code may transfer to the interpreter before this compiled-only optimization.
        // The continuation must still reject overapplication in either execution tier.
        assertThrows(RuntimeFault::class.java) { run(p, "over", eligible, 11L, stop) }
    }

    @Test fun diagnosticReplacementCannotAcquireARecipeButAnUnsupportedColdBodyCan() {
        val unsupportedCase = listOf("case", variable("tree"), "treeCase", listOf(
            listOf("data", "Stop", emptyList<String>(), variable("acc")),
            listOf("data", "Unsupported", emptyList<String>(), integer(0))), mapOf("rep" to long))
        each(diagnostic = true, bindings = listOf(binding("broken", lambda(listOf(parameter("acc"), parameter("tree", data)), unsupportedCase)),
            binding("cold", worker(coldBody = listOf("unsupported", "cold"))), binding("driver", driver()))) { backend, p ->
            assertNull((p.entryTarget("broken").rootNode as GuestRoot).leadingCaseReturn, backend)
            assertThrows(RuntimeFault::class.java) { run(p, "driver", p.entryValue("broken"), 11L, p.entryValue("stop")) }
            val fn = p.entryValue("cold"); val stop = p.entryValue("stop")
            assertNotNull((p.entryTarget("cold").rootNode as GuestRoot).leadingCaseReturn)
            repeat(25) { assertEquals(12L, run(p, "driver", fn, 11L, stop)) }
            compile(p.entryTarget("driver")); val before = count(p)
            assertEquals(12L, run(p, "driver", fn, 11L, stop)); assertEquals(before + 1, count(p))
            assertThrows(RuntimeFault::class.java) { run(p, "driver", fn, 11L, p.entryValue("next")) }
        }
    }

    @Test fun otherUsedFormalValidationAndUnprovenResultsCannotBeSkipped() {
        val base = worker(extra = true)
        @Suppress("UNCHECKED_CAST")
        val arms = ((base[2] as List<Any?>)[3] as List<List<Any?>>).toMutableList()
        arms[1] = arms[1].toMutableList().also { it[3] = plus(variable("acc"), variable("unused")) }
        val body = (base[2] as List<Any?>).toMutableList().also { it[3] = arms }
        val used = lambda(listOf(parameter("acc"), parameter("tree", data), parameter("unused")), body)
        val legacy = lambda(listOf(parameter("acc") - "rep", parameter("tree", data)), worker()[2] as List<Any?>)
        each(bindings = listOf(binding("used", used), binding("legacy", legacy), binding("driver", driver(extra = true)))) { backend, p ->
            assertNull((p.entryTarget("used").rootNode as GuestRoot).leadingCaseReturn, backend)
            assertNull((p.entryTarget("legacy").rootNode as GuestRoot).leadingCaseReturn, backend)
            val fn = p.entryValue("used"); val stop = p.entryValue("stop")
            repeat(25) { assertEquals(12L, run(p, "driver", fn, 11L, stop, 3L)) }
            compile(p.entryTarget("driver"))
            val expected = if (backend == "ast") RuntimeFault::class.java else ClassCastException::class.java
            assertThrows(expected) { run(p, "driver", fn, 11L, stop, "invalid primitive carrier") }
            assertEquals(0L, count(p))
        }
    }

    @Test fun sourceNotesAndClonedRecipesPreserveAttributionWithoutAddingRootEntries() {
        for (notes in listOf(false, true)) each(notes = notes, bindings = listOf(binding("worker", worker()), binding("driver", driver()))) { backend, p ->
            val fn = p.entryValue("worker") as Closure; val stop = p.entryValue("stop")
            val root = fn.target.rootNode as GuestRoot
            val recipe = requireNotNull(root.leadingCaseReturn)
            assertEquals(if (notes) "acc" else null, recipe.source?.section?.characters?.toString(), backend)
            if (notes) assertEquals(listOf("case", "arm"), recipe.source!!.notes.map { it.id })
            val workerCaller = CloneCaller(fn.target)
            val clonedWorker = workerCaller.cloneTarget()
            assertSame(recipe, (clonedWorker.rootNode as GuestRoot).leadingCaseReturn)
            assertEquals(11L, Calls.target(workerCaller.callTarget, arrayOf(0L, 11L, stop)))
            repeat(25) { assertEquals(12L, run(p, "driver", fn, 11L, stop)) }
            // Truffle clones belong to their originating DirectCallNode; do not create
            // a new DirectCallNode targeting an already-cloned target.
            val driverCaller = CloneCaller(p.entryTarget("driver"))
            val clonedDriver = driverCaller.cloneTarget()
            repeat(25) { assertEquals(12L, Calls.target(driverCaller.callTarget, arrayOf(0L, fn, 11L, stop))) }
            val originalChildren = leadingNodes(p.entryTarget("driver").rootNode)
            val children = leadingNodes(clonedDriver.rootNode)
            assertTrue(children.isNotEmpty(), backend)
            assertNotSame(originalChildren.single(), children.single())
            assertEquals(if (notes) "acc" else null, children.single().sourceSection?.characters?.toString())
            assertEquals(originalChildren.single().getCoreSourceNotes(), children.single().getCoreSourceNotes())
            compile(clonedDriver); val hits = count(p); val entries = count(p, "compiledEntries")
            assertEquals(12L, Calls.target(driverCaller.callTarget, arrayOf(0L, fn, 11L, stop)))
            assertEquals(hits + 1, count(p))
            assertEquals(entries + 1, count(p, "compiledEntries"), "Only the caller actually enters a guest root")
        }
    }
}
