// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.NodeUtil
import com.oracle.truffle.api.source.Source
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/** Synthetic Core protocol tests, not native GHC snapshot or IPE equivalence. */
class ManagedStackSnapshotTest {
    private class Capture : Expr() {
        var snapshot: ManagedStackSnapshot? = null
        var differentNode: Node? = null
        var compiledEntries = 0
        override fun execute(frame: VirtualFrame): Any {
            if (CompilerDirectives.inCompiledCode()) compiledEntries++
            snapshot = ManagedStackSnapshot.capture(differentNode ?: this)
            return 7L
        }
    }
    private class Probe(language: Language?, location: CoreSourceLocation?) : GuestRoot(language, FrameLayout().build()) {
        @Child var body = Capture().also { it.located(location) }
        var label = "capture-probe"
        override fun execute(frame: VirtualFrame): Any = body.execute(frame)
        override fun bloom(frame: VirtualFrame) = 0L
        override fun getName() = label
    }

    private fun location(notes: List<CoreSourceNote> = emptyList()): CoreSourceLocation {
        val source = Source.newBuilder("thc", "", "Capture.hs").content(Source.CONTENT_NONE).build()
        return CoreSourceLocation(source.createSection(91, 7, 91, 11), notes)
    }
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()

    private fun module(enabled: Boolean = true): Map<String, Any?> {
        val text = "entry outerCallback =\n  worker outerCallback + 1\nworker innerCallback =\n  innerCallback 0 + 1\n"
        fun metadata(id: String, outer: String) = mapOf("source" to id, "sourceNotes" to listOf(outer, id).distinct())
        fun span(id: String, line: Int, length: Int) = mapOf("id" to id, "file" to "fixture",
            "startLine" to line, "startColumn" to 1, "endLine" to line, "endColumn" to length+1,
            "charIndex" to text.lineSequence().take(line-1).sumOf { it.length+1 }, "charLength" to length, "label" to id)
        fun function(id: String, formal: String, target: String, arg: List<Any?>, lifted: Boolean): Map<String, Any?> {
            val outer = "$id-root"; val site = "$id-call"
            val call = listOf("app", listOf("var", target), listOf(arg), listOf(lifted), false, false, metadata(site, outer))
            val add = listOf("app", listOf("prim", "+#"), listOf(listOf("var", "$id-result"), listOf("lit", "int", "1")),
                listOf(false, false), false, false, metadata(outer, outer))
            // Keep both calls non-tail so both guest frames are live during capture.
            val body = listOf("case", call, "$id-result", listOf(listOf("default", null, emptyList<String>(), add)), metadata(outer, outer))
            return mapOf("id" to id, "name" to id, "lifted" to true, "source" to outer,
                "expr" to listOf("lam", listOf(mapOf("id" to formal, "name" to formal, "lifted" to true)), body, metadata(outer, outer)))
        }
        return mapOf("sourceNotesEnabled" to enabled, "instrument" to true,
            "sourceFiles" to listOf(mapOf("id" to "fixture", "path" to "Snapshot.hs", "content" to text)),
            "sourceSpans" to listOf(span("entry-root", 1, 21), span("entry-call", 2, 24),
                span("worker-root", 3, 22), span("worker-call", 4, 21)),
            "bindings" to listOf(function("entry", "outerCallback", "worker", listOf("var", "outerCallback"), true),
                function("worker", "innerCallback", "innerCallback", listOf("lit", "int", "0"), false)))
    }

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
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target)
    }

    private fun detached(snapshot: ManagedStackSnapshot) {
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        fun visit(value: Any?) {
            if (value == null || value is String || value is Number || value is Boolean || value is Enum<*>) return
            if (!seen.add(value)) return
            if (value is List<*>) { value.forEach(::visit); return }
            assertTrue(value is ManagedStackSnapshot || value is ManagedStackFrame || value is ManagedStackSource || value is ManagedStackNote,
                "Snapshot must not retain ${value.javaClass.name}")
            for (field in value.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }) {
                assertTrue(Modifier.isFinal(field.modifiers), field.name)
                field.isAccessible = true
                visit(field.get(value))
            }
        }
        visit(snapshot)
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.frames as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.frames.first().sections as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.frames.first().notes as MutableList<*>).clear() }
    }

    @Test fun realAstAndBytecodeFramesKeepCurrentAndCallerLocationsAfterReturnAndCompilation() {
        for (backend in listOf("ast", "bytecode")) for (inlining in listOf(false, true)) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program: ExecutableProgram = if (backend == "ast") Program(language, module()) else BytecodeProgram(language, module())
                val probe = Probe(language, location())
                val callback = Closure(null, arity = 1, target = probe.callTarget)
                val entry = program.entryTarget("entry")
                fun invoke(): ManagedStackSnapshot {
                    assertEquals(9L, Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue("entry"), arrayOf<Any?>(callback))))
                    return requireNotNull(probe.body.snapshot)
                }
                fun check(snapshot: ManagedStackSnapshot) {
                    assertEquals(listOf("capture-probe", "lambda innerCallback", "lambda outerCallback"), snapshot.frames.map { it.functionName }, backend)
                    assertEquals(listOf(91, 4, 2), snapshot.frames.map { it.location?.startLine }, "$backend/$inlining callsite ownership")
                    assertEquals(7, snapshot.frames[0].location?.startColumn)
                    assertEquals(ManagedStackLocationKind.CURRENT_NODE, snapshot.frames[0].locationKind)
                    for (frame in snapshot.frames.drop(1)) {
                        assertEquals(if (backend == "ast") ManagedStackLocationKind.CALL_NODE else ManagedStackLocationKind.BYTECODE, frame.locationKind)
                        if (backend == "ast") assertTrue(frame.notes.any { it.id.endsWith("-call") })
                        else { assertTrue(frame.sections.size >= 2); assertTrue(frame.notes.isEmpty()) }
                    }
                    assertTrue(snapshot.renderLines().first().contains("Capture.hs:91:7"))
                    detached(snapshot)
                }
                val first = invoke(); check(first)
                val rendered = first.renderLines()
                repeat(5) { check(invoke()) }
                val targets = activeTargets(entry)
                assertEquals(3, targets.size)
                targets.forEach(::compile)
                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                val probeBefore = probe.body.compiledEntries
                check(invoke())
                assertEquals(2L, (program.diagnostics().getValue("compiledEntries") as Number).toLong()-before)
                assertEquals(probeBefore+1, probe.body.compiledEntries)
                targets.forEach(::valid)
                assertEquals(rendered, first.renderLines(), "Later capture must not mutate the returned snapshot")
                for (counter in listOf("unsupportedTraps", "blackholes")) assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong())
                val handoff = language.handoffState.get()
                assertEquals(0, handoff.results.depth); assertEquals(0, handoff.results.retainedReferences())
                assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.arguments.retainedReferences())
            } finally { context.leave() }
        }
    }

    @Test fun missingMetadataRetainsRealFramesWithoutInventingLocations() {
        for (backend in listOf("ast", "bytecode")) context(false).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program: ExecutableProgram = if (backend == "ast") Program(language, module(false)) else BytecodeProgram(language, module(false))
                val probe = Probe(language, null)
                assertEquals(9L, Calls.target(program.entryTarget("entry"), arrayOf(0L, Closure(null, arity = 1, target = probe.callTarget))))
                val snapshot = requireNotNull(probe.body.snapshot)
                assertEquals(3, snapshot.frames.size)
                assertTrue(snapshot.frames.all { it.location == null && it.sections.isEmpty() && it.notes.isEmpty() })
                assertTrue(snapshot.frames.all { it.locationKind == ManagedStackLocationKind.UNAVAILABLE })
                assertTrue(snapshot.renderLines().all { it.endsWith("(<source unavailable>)") })
                detached(snapshot)
            } finally { context.leave() }
        }
    }

    @Test fun copiedNotesRetainExclusiveEndsAndDoNotKeepMutableNodesOrLists() {
        val section = location().section
        val notes = arrayListOf(CoreSourceNote("original", section, "source label", 91, 7, 92, 1))
        val probe = Probe(null, location(notes))
        assertEquals(7L, Calls.target(probe.callTarget, arrayOf(0L)))
        val snapshot = requireNotNull(probe.body.snapshot)
        notes.clear(); probe.label = "changed"; probe.body.coreSourceLocation = null
        assertEquals("capture-probe", snapshot.frames.single().functionName)
        val note = snapshot.frames.single().notes.single()
        assertEquals("original", note.id); assertEquals("source label", note.label)
        assertEquals(92, note.endLine); assertEquals(1, note.endColumn)
        assertEquals(91, note.source.endLine); assertEquals(11, note.source.endColumn)
        detached(snapshot)
    }

    @Test fun absentInactiveAndWrongCurrentGuestNodesFailInsteadOfReturningEmptySnapshots() {
        assertThrows(RuntimeFault::class.java) { ManagedStackSnapshot.capture(object : Node() {}) }
        val inactive = Probe(null, location()); inactive.callTarget
        assertThrows(RuntimeFault::class.java) { ManagedStackSnapshot.capture(inactive.body) }
        val active = Probe(null, location())
        active.body.differentNode = inactive.body
        assertThrows(RuntimeFault::class.java) { Calls.target(active.callTarget, arrayOf(0L)) }
        assertNull(active.body.snapshot)
    }
}
