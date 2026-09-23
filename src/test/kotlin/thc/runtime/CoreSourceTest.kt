@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.nodes.NodeUtil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Language
import thc.Json
import thc.executionContext
import java.io.File

class CoreSourceTest {
    private val text = "entry x = x + 1\n-- λ 😀\n"
    private val innerStart = text.indexOf("x + 1")
    private fun span(id: String, start: Int, length: Int) = mapOf(
        "id" to id, "file" to "Example.hs", "startLine" to 1, "startColumn" to start + 1,
        "endLine" to 1, "endColumn" to start + length + 1,
        "charIndex" to start, "charLength" to length, "label" to if (id == "outer") "entry" else "addition")
    private fun module(enabled: Boolean): Map<String, Any?> {
        val metadata = mapOf("source" to "inner", "sourceNotes" to listOf("outer", "inner"))
        val body = listOf("app", listOf("prim", "+#"), listOf(listOf("var", "input"), listOf("lit", "int", "1")),
            listOf(false, false), false, false, metadata)
        val function = listOf("lam", listOf(mapOf("id" to "input", "name" to "input", "lifted" to false)), body,
            mapOf("source" to "outer", "sourceNotes" to listOf("outer")))
        return mapOf("sourceNotesEnabled" to enabled,
            "sourceFiles" to listOf(mapOf("id" to "Example.hs", "path" to "Example.hs", "content" to text)),
            "sourceSpans" to listOf(span("outer", 0, text.indexOf('\n')), span("inner", innerStart, 5)),
            "bindings" to listOf(mapOf("id" to "entry", "name" to "entry", "lifted" to true,
                "source" to "outer", "expr" to function)))
    }
    private fun entered(action: (Language) -> Unit) {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }
    }
    private fun run(program: Program, input: Long): Any? =
        Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue("entry"), arrayOf<Any?>(input)))

    @Test fun sourceNotesAttachToTypedAstAndRootsWithoutAddingExecutionNodes() = entered { language ->
        val enabled = Program(language, module(true))
        val disabled = Program(language, module(false))
        val root = enabled.entryTarget("entry").rootNode as FunctionRoot
        val noSource = disabled.entryTarget("entry").rootNode as FunctionRoot
        assertEquals("x + 1", root.sourceSection!!.characters.toString())
        assertEquals(listOf("outer", "inner"), root.getCoreSourceNotes().map { it.id })
        val nodes = NodeUtil.findAllNodeInstances(root, Expr::class.java)
        val plainNodes = NodeUtil.findAllNodeInstances(noSource, Expr::class.java)
        assertTrue(nodes.isNotEmpty())
        assertEquals(plainNodes.map { it.javaClass }, nodes.map { it.javaClass }, "Source metadata must not insert executable wrappers")
        assertTrue(nodes.all { it.sourceSection != null }, "Synthetic typed nodes inherit the nearest enclosing span")
        assertNull(noSource.sourceSection)
        assertTrue(plainNodes.all { it.sourceSection == null })
        assertEquals(2, enabled.diagnostics()["sourceSpanCount"])
        assertTrue((enabled.diagnostics()["sourceRootCount"] as Number).toInt() > 0)
        assertEquals(0, disabled.diagnostics()["sourceSpanCount"])
        assertEquals(0, disabled.diagnostics()["sourceRootCount"])
        repeat(20) { assertEquals(it.toLong() + 1, run(enabled, it.toLong())); assertEquals(it.toLong() + 1, run(disabled, it.toLong())) }
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        for (program in listOf(enabled, disabled)) {
            type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(program.entryTarget("entry"), true)
            assertEquals(true, type.getMethod("isValidLastTier").invoke(program.entryTarget("entry")))
            for (input in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 3_000_000_001L)) assertEquals(input + 1, run(program, input))
        }
    }

    @Test fun realGhcSourceNotesReachTypedRootsAndLocalJoinNodes() = entered { language ->
        val project = File(System.getProperty("thc.projectRoot"))
        fun exported(name: String): Map<String, Any?> =
            Json.parse(File(project, "build/source-core/$name.json").readText()) as Map<String, Any?>
        val sourceModule = exported("SourceNotes")
        fun invoke(program: Program, name: String, n: Long): Long =
            Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue(name), arrayOf<Any?>(n))) as Long
        val compiler = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        for ((name, increment) in listOf("unicode" to 1L, "tabbed" to 2L, "missing" to 3L)) {
            val sources = Program(language, CoreModules.reachable(sourceModule, name))
            val target = sources.entryTarget(name)
            val root = target.rootNode as FunctionRoot
            val section = requireNotNull(root.sourceSection) { "$name must retain real GHC source notes" }
            assertTrue(root.getCoreSourceNotes().isNotEmpty())
            assertTrue(NodeUtil.findAllNodeInstances(root, Expr::class.java).all { it.sourceSection != null })
            if (name == "missing") {
                assertTrue(section.source.name.endsWith("missing-original-source.hs"))
                assertFalse(section.source.hasCharacters())
                assertTrue(section.startLine >= 200)
            } else {
                assertTrue(section.source.hasCharacters())
                assertTrue(section.source.characters.toString().contains("😀"))
            }
            repeat(20) { assertEquals(it.toLong() + increment, invoke(sources, name, it.toLong())) }
            compiler.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
            assertEquals(true, compiler.getMethod("isValidLastTier").invoke(target))
            assertEquals(Long.MAX_VALUE + increment, invoke(sources, name, Long.MAX_VALUE))
        }
        val joins = Program(language, CoreModules.reachable(exported("RepresentationAudit"), "joinLoop"))
        val root = joins.entryTarget("joinLoop").rootNode
        val regions = NodeUtil.findAllNodeInstances(root, LocalJoinRegion::class.java)
        assertTrue(regions.isNotEmpty(), "The fixture must contain an actual GHC join")
        assertTrue(regions.all { it.sourceSection != null }, "Local control-flow nodes inherit GHC source locations")
        assertEquals(2_001_000L, invoke(joins, "joinLoop", 2_000))
        val off = Program(language, CoreModules.reachable(sourceModule, "unicode") + ("sourceNotesEnabled" to false))
        assertNull(off.entryTarget("unicode").rootNode.sourceSection)
        assertEquals(0, off.diagnostics()["sourceSpanCount"])
        assertEquals(0, off.diagnostics()["sourceRootCount"])
    }

    @Test fun missingSourceContentRetainsLocationsAndOriginalExclusiveProvenance() {
        val module = mapOf("sourceFiles" to listOf(mapOf("id" to "gone", "path" to "Missing.hs", "content" to null)),
            "sourceSpans" to listOf(mapOf("id" to "span", "file" to "gone", "startLine" to 2, "startColumn" to 4,
                "endLine" to 3, "endColumn" to 1, "charIndex" to null, "charLength" to null, "label" to "missing")))
        val source = CoreSources(module)
        val location = source.expression(listOf("var", "x", mapOf("source" to "span")))!!
        assertFalse(location.section.source.hasCharacters())
        assertTrue(location.section.isAvailable)
        assertEquals(2, location.section.startLine)
        assertEquals(2, location.section.endLine, "Without the preceding line's text only the exact start point is representable")
        assertEquals(4, location.section.startColumn)
        assertEquals(4, location.section.endColumn)
        assertEquals(3, location.notes.single().endLine)
        assertEquals(1, location.notes.single().endColumn, "Original exclusive end must not be rewritten")
        assertNull(CoreSources(module + ("sourceNotesEnabled" to false)).expression(listOf("var", "x", mapOf("source" to "span"))))
    }

    @Test fun unverifiedCoordinatesNeverIndexAvailableSourceText() {
        val file = mapOf("id" to "available", "path" to "Available.hs", "content" to "short\n")
        fun location(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int): CoreSourceLocation {
            val span = mapOf("id" to "span", "file" to "available", "startLine" to startLine, "startColumn" to startColumn,
                "endLine" to endLine, "endColumn" to endColumn, "charIndex" to null, "charLength" to null)
            return CoreSources(mapOf("sourceFiles" to listOf(file), "sourceSpans" to listOf(span)))
                .expression(listOf("var", "x", mapOf("source" to "span")))!!
        }
        for (position in listOf(location(1000, 80, 1001, 1), location(1, 1, 1, 1))) {
            assertFalse(position.section.source.hasCharacters(), "Unverified GHC columns must use location-only source")
            assertTrue(position.section.isAvailable)
        }
        assertEquals(1000, location(1000, 80, 1001, 1).section.startLine)
        val bad = mapOf("id" to "bad", "file" to "available", "startLine" to 1, "startColumn" to 1,
            "endLine" to 1, "endColumn" to 2, "charIndex" to 0.5, "charLength" to 1)
        assertThrows(RuntimeFault::class.java) { CoreSources(mapOf("sourceFiles" to listOf(file), "sourceSpans" to listOf(bad))) }
    }

    @Test fun characterOffsetsUseUtf16WithoutReencodingUnicodeSource() {
        val content = "λ😀x\n"
        val source = CoreSources(mapOf("sourceFiles" to listOf(mapOf("id" to "unicode", "path" to "Unicode.hs", "content" to content)),
            "sourceSpans" to listOf(mapOf("id" to "emoji", "file" to "unicode", "startLine" to 1, "startColumn" to 2,
                "endLine" to 1, "endColumn" to 3, "charIndex" to 1, "charLength" to 2))))
        val location = source.expression(listOf("var", "x", mapOf("source" to "emoji")))!!
        assertEquals("😀", location.section.characters.toString())
        assertEquals(2, location.section.charLength)
        assertEquals(content, location.section.source.characters.toString())
    }
}
