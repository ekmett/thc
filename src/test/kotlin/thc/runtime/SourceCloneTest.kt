// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

/** Target splitting must retain the original source provenance on the cloned executable tree. */
class SourceCloneTest {
    private val text = "entry x = x + 1\n"
    private val innerStart = text.indexOf("x + 1")
    private fun metadata(id: String) = mapOf("source" to id, "sourceNotes" to listOf("outer", id).distinct())
    private fun span(id: String, start: Int, length: Int) = mapOf(
        "id" to id, "file" to "fixture", "startLine" to 1, "startColumn" to start + 1,
        "endLine" to 1, "endColumn" to start + length + 1, "charIndex" to start, "charLength" to length,
        "label" to if (id == "outer") "entry" else "addition")
    private fun module(): Map<String, Any?> {
        val body = listOf("app", listOf("prim", "+#"),
            listOf(listOf("var", "input", metadata("inner")), listOf("lit", "int", "1", metadata("inner"))),
            listOf(false, false), false, false, metadata("inner"))
        val function = listOf("lam", listOf(mapOf("id" to "input", "name" to "input", "lifted" to false)), body, metadata("outer"))
        return mapOf("sourceNotesEnabled" to true, "instrument" to true,
            "sourceFiles" to listOf(mapOf("id" to "fixture", "path" to "SourceClone.hs", "content" to text)),
            "sourceSpans" to listOf(span("outer", 0, text.length - 1), span("inner", innerStart, 5)),
            "bindings" to listOf(mapOf("id" to "entry", "name" to "entry", "lifted" to true,
                "source" to "outer", "expr" to function)))
    }

    private class CloneCaller(target: RootCallTarget) : RootNode(null) {
        @Child private var call = DirectCallNode.create(target)
        override fun execute(frame: VirtualFrame): Any? = Calls.direct(call, frame.arguments)
        fun cloneTarget(): RootCallTarget {
            callTarget // Adopt the direct call before requesting an actual Truffle split.
            assertTrue(call.isCallTargetCloningAllowed)
            assertTrue(call.cloneCallTarget(), "The test must exercise a real cloned target")
            return call.clonedCallTarget as RootCallTarget
        }
    }

    private fun assertLocations(original: RootNode, cloned: RootNode) {
        assertNotNull(original.sourceSection)
        assertEquals(original.sourceSection, cloned.sourceSection)
        assertEquals("x + 1", cloned.sourceSection!!.characters.toString())
        if (original is FunctionRoot && cloned is FunctionRoot) {
            assertEquals(listOf("outer", "inner"), cloned.getCoreSourceNotes().map { it.id })
            assertEquals(original.getCoreSourceNotes(), cloned.getCoreSourceNotes())
            val before = NodeUtil.findAllNodeInstances(original, Expr::class.java)
            val after = NodeUtil.findAllNodeInstances(cloned, Expr::class.java)
            assertEquals(before.size, after.size)
            assertTrue(after.isNotEmpty())
            before.zip(after).forEach { (a, b) ->
                assertNotSame(a, b, "The child nodes must belong to the cloned tree")
                assertEquals(a.javaClass, b.javaClass)
                assertNotNull(b.sourceSection)
                assertEquals(a.sourceSection, b.sourceSection)
                assertEquals(a.coreSourceLocation?.notes, b.coreSourceLocation?.notes)
            }
        } else {
            assertTrue(original is BytecodeRoot && cloned is BytecodeRoot)
            fun locations(root: BytecodeRoot) = root.bytecodeNode.instructions.map {
                it.bytecodeIndex to root.bytecodeNode.getSourceLocations(it.bytecodeIndex)?.toList().orEmpty()
            }
            val originalPositions = locations(original as BytecodeRoot)
            val clonedPositions = locations(cloned as BytecodeRoot)
            assertTrue(cloned.bytecodeNode.hasSourceInformation())
            assertTrue(clonedPositions.any { it.second.isNotEmpty() })
            assertEquals(originalPositions, clonedPositions, "Every cloned bytecode location must retain its source stack")
        }
    }

    @Test fun actualClonedTargetsRetainRootAndChildLocationsBeforeAndAfterCompilation() {
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program: ExecutableProgram = if (backend == "ast") Program(language, module()) else BytecodeProgram(language, module())
                val original = program.entryTarget("entry")
                val caller = CloneCaller(original)
                val cloned = caller.cloneTarget()
                assertNotSame(original, cloned)
                assertNotSame(original.rootNode, cloned.rootNode)
                assertTrue((cloned.rootNode as GuestRoot).isSelf(original))
                assertLocations(original.rootNode, cloned.rootNode)
                fun check(input: Long) {
                    assertEquals(input + 1, Calls.target(original, arrayOf(0L, input)), "$backend original")
                    assertEquals(input + 1, Calls.target(caller.callTarget, arrayOf(0L, input)), "$backend clone")
                }
                repeat(20) { check(it.toLong()) }
                val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(cloned, true)
                assertEquals(true, type.getMethod("isValidLastTier").invoke(cloned))
                for (input in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 3_000_000_001L)) check(input)
                assertLocations(original.rootNode, cloned.rootNode)
                assertTrue((program.diagnostics()["compiledEntries"] as Long) > 0)
            } finally { context.leave() }
        }
    }
}
