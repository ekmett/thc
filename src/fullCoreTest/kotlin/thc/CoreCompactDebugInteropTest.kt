// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import com.oracle.truffle.api.nodes.NodeUtil
import com.oracle.truffle.api.source.SourceSection
import java.nio.file.Files
import java.nio.file.Path
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.BytecodeRoot
import thc.runtime.CoreSourceLocation
import thc.runtime.CoreSources
import thc.runtime.Expr

/** Genuine producer bytes and their unchanged original JSON, not model-generated debug tables. */
class CoreCompactDebugInteropTest {
    private val manifest = Path.of(System.getProperty("thc.compactInteropDebugManifest"))
    private fun directory(path: Path) = CoreUnitDirectory.read(Json.parse(Files.readString(path)) as Map<*, *>)!!
    private fun section(value: SourceSection?): List<Any?>? = value?.let {
        listOf(it.source.name, it.isAvailable, if (it.hasLines()) it.startLine else null,
            if (it.hasColumns()) it.startColumn else null, if (it.hasLines()) it.endLine else null,
            if (it.hasColumns()) it.endColumn else null, if (it.hasCharIndex()) it.charIndex else null,
            if (it.hasCharIndex()) it.charLength else null,
            if (it.source.hasCharacters() && it.isAvailable) it.characters.toString() else null)
    }
    private fun location(expected: CoreSourceLocation?, actual: CoreSourceLocation?) {
        assertEquals(section(expected?.section), section(actual?.section))
        fun notes(value: CoreSourceLocation?) = value?.notes.orEmpty().map {
            listOf(it.id, it.label, it.startLine, it.startColumn, it.endLine, it.endColumn, section(it.section))
        }
        assertEquals(notes(expected), notes(actual))
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun selectedGenuineOccurrencesKeepOriginalOrderedNotesAndDisplayNames() {
        val compact = directory(manifest)
        val reference = directory(Path.of(System.getProperty("thc.compactInteropReference")))
        val module = compact.modules.single { it.name == "SourceNotes" }
        val artifact = (module.storage as CoreUnitDirectory.CompactStorage).artifact
        reference.open(false).use { originals -> CoreCompactFile(artifact.path, artifact.sha256).use { file ->
            val source = CoreSources(originals.metadata(reference.modules.single { it.name == "SourceNotes" }))
            val records = CoreCompactRecords(file, artifact.sha256)
            val decodedSource = CoreSources(emptyMap<String, Any?>())
            fun compareBinding(old: Map<String, Any?>, value: Map<String, Any?>, inherited: CoreSourceLocation?) =
                source.binding(old, inherited).also { location(it, decodedSource.binding(value)) }
            lateinit var expression: (List<Any?>, List<Any?>, CoreSourceLocation?) -> Unit
            lateinit var binding: (Map<String, Any?>, Map<String, Any?>, CoreSourceLocation?) -> Unit
            binding = { old, value, inherited ->
                val current = compareBinding(old, value, inherited)
                val origin = value["compactOrigin"] as CoreCompactRecords.Origin
                val slot = (value["id"] as String).takeIf { it.startsWith("\u0000compact-local:") }
                    ?.substringAfter(':')?.toLong()?.plus(1) ?: 0L
                assertEquals(old["name"], origin.debug!!.name(origin.bindingOffset, slot))
                expression(old["expr"] as List<Any?>, value["expr"] as List<Any?>, current)
            }
            expression = { old, value, inherited ->
                assertEquals(old[0], value[0])
                val current = source.expression(old, inherited)
                val actual = decodedSource.expression(value)
                assertNotNull(actual?.compactOrigin)
                location(current, actual)
                when (old[0]) {
                    "lam" -> {
                        (old[1] as List<Map<String, Any?>>).zip(value[1] as List<Map<String, Any?>>).forEach { (a, b) ->
                            compareBinding(a, b, current)
                            val origin = b["compactOrigin"] as CoreCompactRecords.Origin
                            val slot = (b["id"] as String).substringAfter(':').toLong() + 1
                            assertEquals(a["name"], origin.debug!!.name(origin.bindingOffset, slot))
                        }
                        expression(old[2] as List<Any?>, value[2] as List<Any?>, current)
                    }
                    "app" -> {
                        expression(old[1] as List<Any?>, value[1] as List<Any?>, current)
                        (old[2] as List<List<Any?>>).zip(value[2] as List<List<Any?>>).forEach { (a, b) -> expression(a, b, current) }
                    }
                    "let" -> {
                        (old[2] as List<Map<String, Any?>>).zip(value[2] as List<Map<String, Any?>>).forEach { (a, b) -> binding(a, b, current) }
                        expression(old[3] as List<Any?>, value[3] as List<Any?>, current)
                    }
                    "case" -> {
                        expression(old[1] as List<Any?>, value[1] as List<Any?>, current)
                        (old[3] as List<List<Any?>>).zip(value[3] as List<List<Any?>>).forEach { (a, b) ->
                            expression(a[3] as List<Any?>, b[3] as List<Any?>, current)
                        }
                    }
                }
            }
            for (name in listOf("unicode", "tabbed", "missing")) {
                val id = "main:SourceNotes.$name"
                val value = records.binding(file.lookup(id)!!)
                if (name == "unicode") assertEquals(0L, file.counters.statistics().debugBytesRead)
                binding(originals.binding(id)!!, value, null)
            }
            assertTrue(file.counters.statistics().debugBytesRead > 0)
            assertEquals(0L, file.counters.statistics().hashBytesRead)
        } }
    }

    @Test fun publicDebugRequestStaysColdUntilExplicitSourceDemandAndRetainsOrigins() {
        for (backend in listOf("ast", "bytecode")) for ((name, delta) in listOf("unicode" to 1L, "tabbed" to 2L, "missing" to 3L))
            Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build().use { context ->
                val id = "main:SourceNotes.$name"
                val entry = context.eval("thc", CoreModules.request(listOf("@$manifest"), id,
                    backend = backend, sourceNotesEnabled = true, asyncExceptions = false))
                context.enter()
                try {
                    val program = Language.currentState().coreUnitPrograms.single()
                    val target = program.entryTarget(id)
                    fun reads() = (program.diagnostics().getValue("coreCompactDebugBytesRead") as Number).toLong()
                    assertEquals(0L, reads(), "Default construction must not resolve a debug argument")
                    assertEquals(40 + delta, entry.execute(40).asLong())
                    assertEquals(0L, reads(), "Ordinary execution must leave debug tables cold")
                    val root = target.rootNode
                    if (root is BytecodeRoot) {
                        fun instructions() = root.bytecodeNode.instructions.map { it.name to it.arguments.map(Any::toString) }
                        val code = instructions()
                        assertFalse(root.bytecodeNode.hasSourceInformation())
                        root.bytecodeNode.ensureSourceInformation()
                        assertTrue(root.bytecodeNode.hasSourceInformation())
                        assertEquals(code, instructions())
                    } else {
                        val nodes = NodeUtil.findAllNodeInstances(root, Expr::class.java)
                        val node = nodes.first { it.coreSourceLocation?.compactOrigin != null }
                        val copied = NodeUtil.cloneNode(node)
                        assertSame(node.coreSourceLocation!!.compactOrigin, copied.coreSourceLocation!!.compactOrigin)
                        assertEquals(section(node.sourceSection), section(copied.sourceSection))
                    }
                    assertNotNull(root.sourceSection)
                    assertTrue(reads() > 0)
                    val after = reads()
                    assertSame(target, program.entryTarget(id))
                    assertEquals(77 + delta, entry.execute(77).asLong())
                    if (root is BytecodeRoot) root.bytecodeNode.ensureSourceInformation()
                    assertEquals(after, reads(), "Resolved debug data and replay must be reused")
                } finally { context.leave() }
            }
    }
}
