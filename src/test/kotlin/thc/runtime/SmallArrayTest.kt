// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest

class SmallArrayTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun manifest() = Json.parse(File(root, "build/small-arrays/manifest.json").readText()) as Map<String, Any?>
    private fun module(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun program(language: Language, linked: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target)
    }
    private fun valid(target: RootCallTarget) =
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun call(program: ExecutableProgram, input: Long): Long =
        Calls.target(program.hostEntryTarget(1),
            arrayOf(program.entryValue("smallComposite"), arrayOf(input))) as Long

    private fun applications(value: Any?): List<List<Any?>> = when (value) {
        is List<*> -> (if (value.firstOrNull() == "app") listOf(value as List<Any?>) else emptyList()) +
            value.flatMap(::applications)
        is Map<*, *> -> value.values.flatMap(::applications)
        else -> emptyList()
    }

    @Test fun nativeCompositeUsesExactSmallArrayShapesAndRunsCompiledInBothBackends() {
        val manifest = manifest()
        for (field in listOf("inputHashes", "artifactHashes")) {
            for ((path, expected) in manifest[field] as Map<String, String>) {
                val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, actual, "Stale SmallArray evidence: $path")
            }
        }
        val rows = File(root, "build/small-arrays/oracle.tsv").readLines().map { it.split('\t') }
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.size)
        val cases = rows.map { row ->
            assertEquals(2, row.size)
            row[0].toLong() to row[1].toLong()
        }
        cases.forEach { (x, expected) -> assertEquals(68 * x + 702, expected, "native model $x") }
        val names = SmallArrayOp.entries.map { it.primitive }.toSet()
        for ((stage, path) in manifest["stages"] as Map<String, String>) {
            val auditPath = (manifest["audits"] as Map<String, String>).getValue(stage)
            assertEquals(true, (Json.parse(File(root, auditPath).readText()) as Map<*, *>)["accepted"])
            val linked = CoreModules.reachable(module(path), "smallComposite")
            val apps = applications(linked).filter { (it[1] as? List<*>)?.firstOrNull() == "prim" }
            val direct = apps.map { (it[1] as List<*>)[1] as String }
            assertTrue(direct.containsAll(names), "$stage missing SmallArray primitive")
            for (app in apps.filter { (it[1] as List<*>)[1] in names }) {
                val name = (app[1] as List<*>)[1] as String
                val operation = SmallArrayOp.named(name)!!
                val args = app[2] as List<Any?>
                operation.validate(args.map { CoreRepresentations.expression(it as List<Any?>) },
                    app[3] as List<*>, CoreRepresentations.expression(app))
                if (name == "indexSmallArray#") {
                    val result = CoreRepresentations.expression(app)
                    assertEquals(1, result.components!!.size, "$stage indexed element tuple")
                    assertEquals(listOf("BoxedRep (Just Lifted)"), result.components[0].primReps)
                }
            }
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val guest = program(language, linked, backend)
                    for ((x, expected) in cases) assertEquals(expected, call(guest, x), "$stage/$backend/interpreted/$x")
                    val target = guest.entryTarget("smallComposite")
                    compile(target)
                    for ((x, expected) in cases) {
                        val before = (guest.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(expected, call(guest, x), "$stage/$backend/compiled/$x")
                        assertTrue((guest.diagnostics().getValue("compiledEntries") as Number).toLong() > before,
                            "$stage/$backend/$x executed compiled guest code")
                        valid(target)
                    }
                    assertEquals(0L, (guest.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    assertEquals(0, language.handoffState.get().results.depth)
                } finally { context.leave() }
            }
        }
    }

    @Test fun smallStorageKeepsLiftedReferencesAndRejectsOrdinaryArrayAliases() {
        var entered = 0
        val bottom = Thunk(object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any? {
                entered++
                throw RuntimeFault("SmallArray initializer entered")
            }
        }.callTarget, null)
        val replacement = Any()
        val small = ManagedSmallArray.allocate(2, bottom)
        assertEquals(2L, ManagedSmallArray.size(small))
        assertSame(bottom, ManagedSmallArray.read(small, 0))
        assertSame(bottom, ManagedSmallArray.read(small, 1))
        ManagedSmallArray.write(small, 1, replacement)
        assertSame(bottom, ManagedSmallArray.read(small, 0))
        assertSame(replacement, ManagedSmallArray.read(ManagedSmallArray.freeze(small), 1))
        assertSame(small, ManagedSmallArray.freeze(small))
        val cloned = ManagedSmallArray.slice(small, 0, 2)
        assertNotSame(small, cloned)
        assertSame(bottom, ManagedSmallArray.read(cloned, 0))
        ManagedSmallArray.write(cloned, 1, bottom)
        assertSame(replacement, ManagedSmallArray.read(small, 1))
        val destination = ManagedSmallArray.allocate(2, null)
        ManagedSmallArray.copy(small, 0, destination, 0, 2, false)
        assertSame(bottom, ManagedSmallArray.read(destination, 0))
        assertSame(replacement, ManagedSmallArray.read(destination, 1))
        ManagedSmallArray.copy(destination, 0, destination, 1, 1, true)
        assertSame(bottom, ManagedSmallArray.read(destination, 1))
        assertThrows(RuntimeFault::class.java) { ManagedSmallArray.copy(small, 0, small, 0, 0, false) }
        for ((offset, count) in listOf(-1L to 0L, 0L to -1L, 2L to 1L,
            Long.MAX_VALUE to 0L, 0L to Long.MAX_VALUE)) {
            assertThrows(RuntimeFault::class.java) { ManagedSmallArray.slice(small, offset, count) }
            val before = destination.elements.copyOf()
            assertThrows(RuntimeFault::class.java) { ManagedSmallArray.copy(small, offset, destination, 0, count, false) }
            assertArrayEquals(before, destination.elements)
        }
        assertEquals(0, entered, "SmallArray storage must not enter lifted elements")
        assertThrows(RuntimeFault::class.java) { ManagedArray.require(small) }
        assertThrows(RuntimeFault::class.java) { ManagedSmallArray.require(ManagedArray.allocate(1, bottom)) }
        for (index in listOf(-1L, 2L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertThrows(RuntimeFault::class.java) { ManagedSmallArray.read(small, index) }
            assertThrows(RuntimeFault::class.java) { ManagedSmallArray.write(small, index, replacement) }
        }
        for (size in listOf(-1L, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { ManagedSmallArray.allocate(size, bottom) }
    }

    @Test fun transferChecksStateBeforeMutating() {
        val source = ManagedSmallArray.allocate(1, Any())
        val original = Any()
        val destination = ManagedSmallArray.allocate(1, original)
        val events = mutableListOf<String>()
        fun operand(label: String, value: Any?): Expr = object : Expr() {
            override fun execute(frame: VirtualFrame): Any? { events += label; return value }
        }
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameDescriptor.newBuilder().build())
        for (operation in listOf(SmallArrayOp.COPY, SmallArrayOp.COPY_MUTABLE)) {
            events.clear()
            val expr = smallArrayExpression(operation, CoreRepresentation.UNKNOWN, arrayOf(
                operand("source", source), operand("from", 0L), operand("destination", destination),
                operand("to", 0L), operand("count", 1L), operand("state", 9L)))
            assertThrows(RuntimeFault::class.java) { expr.execute(frame) }
            assertEquals(listOf("source", "from", "destination", "to", "count", "state"), events)
            assertSame(original, ManagedSmallArray.read(destination, 0))
        }
    }
}
