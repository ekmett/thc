package thc

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.BytecodeConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.BytecodeProgram
import thc.runtime.BytecodeRoot
import thc.runtime.Calls

/** Debugger attribution must preserve the actual lazy program and instruction stream. */
class BytecodeSourceNotesTest {
    private val text = "entry x =\n  let boxed = x + 1\n  in case boxed of y -> boxed + y\n"
    private fun metadata(span: String) = mapOf("source" to span, "sourceNotes" to listOf("entry", span).distinct())
    private fun variable(name: String) = listOf("var", name, metadata("demand"))
    private fun integer(value: Long) = listOf("lit", "int", value.toString(), metadata("rhs"))
    private fun add(a: List<Any?>, b: List<Any?>) = listOf("app", listOf("prim", "+#"), listOf(a, b), listOf(false, false), false, false, metadata("rhs"))
    private fun binder(id: String, lifted: Boolean) = mapOf("id" to id, "name" to id, "type" to "Synthetic", "lifted" to lifted, "coercion" to false)
    private fun span(id: String, substring: String): Map<String, Any?> {
        val start = text.indexOf(substring)
        val end = start + substring.length
        fun line(index: Int) = text.substring(0, index).count { it == '\n' } + 1
        fun column(index: Int) = index - text.lastIndexOf('\n', index - 1)
        return mapOf("id" to id, "file" to "fixture", "startLine" to line(start), "startColumn" to column(start),
            "endLine" to line(end), "endColumn" to column(end), "charIndex" to start, "charLength" to substring.length, "label" to id)
    }
    private fun module(enabled: Boolean, content: Boolean = true): Map<String, Any?> {
        val rhs = listOf("case", variable("x"), "ignored", listOf(listOf("default", null, emptyList<String>(), add(variable("x"), integer(1)))), metadata("rhs"))
        val binding = binder("boxed", true) + mapOf("expr" to rhs, "arity" to 0)
        val demand = listOf("case", variable("boxed"), "y", listOf(listOf("default", null, emptyList<String>(), add(variable("boxed"), variable("y")))), metadata("demand"))
        val body = listOf("let", false, listOf(binding), demand, metadata("entry"))
        val entry = binder("entry", true) + mapOf("arity" to 1, "source" to "entry",
            "expr" to listOf("lam", listOf(binder("x", false)), body, metadata("entry")))
        return mapOf("bindings" to listOf(entry), "constructors" to emptyList<Any>(), "sourceNotesEnabled" to enabled,
            "sourceFiles" to listOf(mapOf("id" to "fixture", "path" to "Synthetic.SourceNotes.hs", "content" to if (content) text else null)),
            "sourceSpans" to listOf(span("entry", text.dropLast(1)), span("rhs", "x + 1"), span("demand", "case boxed of y -> boxed + y")))
    }
    private fun withRuntime(action: (Language) -> Unit) {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }
    }
    private fun execute(program: BytecodeProgram, input: Long): Long =
        Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue("entry"), arrayOf<Any?>(input))) as Long

    @Test fun notesAttachToNativeBytecodeWithoutChangingInstructionsOrLocalWriteback() = withRuntime { language ->
        val off = BytecodeProgram(language, module(false))
        val on = BytecodeProgram(language, module(true))
        val offRoot = off.entryTarget("entry").rootNode as BytecodeRoot
        val onRoot = on.entryTarget("entry").rootNode as BytecodeRoot
        fun instructions(root: BytecodeRoot) = root.bytecodeNode.instructions.map { it.name }
        assertEquals(instructions(offRoot), instructions(onRoot), "Source notes must emit no executable opcode")
        assertTrue(instructions(onRoot).any { it.startsWith("c.ForceLocal") }, "Decorated local forcing must still write back")
        assertFalse(offRoot.bytecodeNode.hasSourceInformation())
        assertNull(offRoot.sourceSection)
        assertTrue(onRoot.bytecodeNode.hasSourceInformation())
        assertEquals(text.dropLast(1), onRoot.sourceSection.characters.toString())
        val locations = onRoot.bytecodeNode.instructions.flatMap {
            onRoot.bytecodeNode.getSourceLocations(it.bytecodeIndex)?.toList().orEmpty()
        }
        assertTrue(locations.any { it.characters.toString() == "case boxed of y -> boxed + y" })
        assertEquals(0, off.diagnostics()["sourceRootCount"])
        assertTrue((on.diagnostics()["sourceRootCount"] as Int) > 0)
        assertEquals(3, on.diagnostics()["sourceSpanCount"])
        for (input in listOf(0L, 3_000_000_000L, Long.MAX_VALUE)) {
            assertEquals((input + 1) * 2, execute(off, input))
            assertEquals((input + 1) * 2, execute(on, input))
        }
        assertEquals(true, EntryValue(on, "entry", 1).invokeMember("compile", emptyArray()))
        assertEquals(86L, execute(on, 42L))
        assertTrue((on.diagnostics()["compiledEntries"] as Long) > 0)
        // Source-only configuration must never enable instruction tracing.
        onRoot.rootNodes.update(BytecodeConfig.WITH_SOURCE)
        assertEquals(86L, execute(on, 42L))
        assertEquals(text.dropLast(1), onRoot.sourceSection.characters.toString())
        assertEquals(instructions(offRoot), instructions(onRoot))
    }

    @Test fun sourceSectionsPreserveLocalJoinBackedgesAndTheirOuterContinuation() = withRuntime { language ->
        fun apply(fn: List<Any?>, args: List<List<Any?>>): List<Any?> =
            listOf("app", fn, args, List(args.size) { false }, false, false, metadata("rhs"))
        val next = apply(variable("loop"), listOf(apply(listOf("prim", "-#"), listOf(variable("n"), integer(1))),
            add(variable("acc"), integer(1))))
        val worker = listOf("case", variable("n"), "choice", listOf(
            listOf("lit", listOf("int", "0"), emptyList<String>(), variable("acc")),
            listOf("default", null, emptyList<String>(), next)), metadata("demand"))
        val join = binder("loop", true) + mapOf("arity" to 2, "joinValueArity" to 2, "source" to "rhs",
            "expr" to listOf("lam", listOf(binder("n", false), binder("acc", false)), worker, metadata("rhs")))
        val region = listOf("let", true, listOf(join), apply(variable("loop"), listOf(variable("x"), integer(0))), metadata("entry"))
        val entry = binder("entry", true) + mapOf("arity" to 1,
            "expr" to listOf("lam", listOf(binder("x", false)), add(integer(17), region), metadata("entry")))
        val off = BytecodeProgram(language, module(false) + mapOf("bindings" to listOf(entry)))
        val on = BytecodeProgram(language, module(true) + mapOf("bindings" to listOf(entry)))
        val offRoot = off.entryTarget("entry").rootNode as BytecodeRoot
        val onRoot = on.entryTarget("entry").rootNode as BytecodeRoot
        assertEquals(offRoot.bytecodeNode.instructions.map { it.name }, onRoot.bytecodeNode.instructions.map { it.name })
        assertEquals(100_017L, execute(off, 100_000L))
        assertEquals(100_017L, execute(on, 100_000L))
        assertEquals(true, EntryValue(on, "entry", 1).invokeMember("compile", emptyArray()))
        assertEquals(100_018L, execute(on, 100_001L))
        assertEquals(0L, on.diagnostics()["trampolineIterations"])
        assertEquals(1, on.diagnostics()["localJoinCount"])
        assertNotNull(onRoot.sourceSection)
    }

    @Test fun bytecodeKeepsSourceLocationsWhenOriginalFileContentIsUnavailable() = withRuntime { language ->
        val program = BytecodeProgram(language, module(true, content = false))
        val root = program.entryTarget("entry").rootNode as BytecodeRoot
        val section = root.sourceSection
        assertNotNull(section)
        assertFalse(section.source.hasCharacters())
        assertEquals("Synthetic.SourceNotes.hs", section.source.name)
        assertEquals(1, section.startLine)
        assertEquals(3, section.endLine)
        assertTrue(root.bytecodeNode.hasSourceInformation())
        assertEquals(10L, execute(program, 4L))
    }
}
