// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import thc.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

@Timeout(90)
class ClosureInspectionTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), target.rootNode.name)
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = ArrayList<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() }
                else listOf(body)
            nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }.forEach {
                val next = it.currentCallTarget as? RootCallTarget
                if (next?.rootNode is GuestRoot) visit(next)
            }
            result += target
        }
        visit(entry)
        return result
    }
    private fun fixture(): Map<String, Any?> {
        val receipt = Json.parse(File(root, "build/closure-inspection/manifest.json").readText()) as Map<String, Any?>
        assertEquals("9.14.1", receipt["ghc"])
        assertEquals(35L, receipt["nativeRows"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in receipt[kind] as Map<String, String>) {
                val digest = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, digest, "Stale fixture: $path")
            }
        return receipt
    }
    @Test fun originalCoreMatchesNativeWithoutEnteringInspectedPayloads() {
        val receipt = fixture()
        val module = Json.parse(File(root, "build/closure-inspection/core/ClosureInspectionAudit.json").readText()) as Map<String, Any?>
        val rows = File(root, "build/closure-inspection/oracle.tsv").readLines().map { it.split('\t') }
        assertEquals(35, rows.size)
        for (backend in listOf("ast", "bytecode")) for (name in receipt["entries"] as List<String>) {
            context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val source = CoreModules.reachable(module, listOf(name), true) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, source) else BytecodeProgram(language, source)
                    val selected = rows.filter { it[0] == name }
                    fun check(row: List<String>) {
                        val input = row[1].toLong()
                        val model = when (name) {
                            "payload" -> input
                            "sizeConsistent", "notStack" -> 0L
                            "pointerCount" -> 2L
                            "noCCS" -> 1L
                            "noProvenance" -> 123L
                            "cleared" -> input + 1
                            else -> error(name)
                        }
                        assertEquals(model, row[2].toLong(), "native/$name/$input")
                        assertEquals(model, Calls.target(program.entryTarget(name), arrayOf(0L, input)), "$backend/$name/$input")
                        val state = language.handoffState.get()
                        assertEquals(0, state.arguments.depth); assertEquals(0, state.results.depth)
                        assertEquals(0, state.arguments.retainedReferences()); assertEquals(0, state.results.retainedReferences())
                    }
                    selected.forEach(::check)
                    val entry = program.entryTarget(name)
                    val installed = targets(entry)
                    val runtime = Truffle.getRuntime()
                    val bypass = runtime.javaClass.getMethod("bypassedInstalledCode",
                        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
                    for (target in installed) {
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        valid(target); bypass.invoke(runtime, target); valid(target)
                    }
                    // Source calls: entry, plus runRW lambda for three IO cases,
                    // plus clearCCS's actual State action for the clear case.
                    val entries = when (name) { "noCCS", "noProvenance" -> 2; "cleared" -> 3; else -> 1 }
                    for (row in selected) {
                        val before = (program.diagnostics()["compiledEntries"] as Number).toLong()
                        check(row)
                        assertEquals(before + entries, (program.diagnostics()["compiledEntries"] as Number).toLong(), "$backend/$name/${row[1]}")
                        assertSame(entry, program.entryTarget(name)); installed.forEach(::valid)
                    }
                } finally { context.leave() }
            }
        }
    }

    @Test fun managedImagePreservesBitsPointersAndLazyThunkStates() {
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val never = object : GuestRoot(language, FrameLayout().build()) {
                    override fun execute(frame: VirtualFrame): Any = error("inspection entered a closure")
                    override fun bloom(frame: VirtualFrame): Long = 0
                }
                val thunk = Thunk(never.callTarget, null)
                val layout = DataLayout(language, "test:Inspection.Payload", "Payload",
                    arrayOf("IntRep", "FloatRep", "DoubleRep", "LiftedRep"))
                val value = layout.create(arrayOf(Long.MIN_VALUE, -0.0f, Double.fromBits(0x7ff8000000000055L), thunk))
                val image = ClosureInspection.image(value)
                val words = ByteBuffer.wrap(image.bytes).order(ByteOrder.nativeOrder())
                assertEquals(40, image.bytes.size); assertEquals(5L, ClosureInspection.size(value))
                assertEquals(Long.MIN_VALUE, words.getLong(8))
                assertEquals((-0.0f).toRawBits(), words.getInt(16))
                assertEquals(0x7ff8000000000055L, words.getLong(24))
                assertEquals(0L, words.getLong(32)); assertArrayEquals(arrayOf(thunk), image.pointers)
                assertEquals(0, thunk.state)
                val info = Language.currentState(null).closureInfo
                assertTrue(info.address(image.descriptor).sameLocation(info.address(image.descriptor)))
                assertFalse(info.address(image.descriptor).sameLocation(ClosureInfoTables().address(image.descriptor)))
                val fresh = ClosureInspection.image(value)
                image.bytes.fill(0); image.pointers[0] = null
                assertSame(thunk, fresh.pointers[0]); assertEquals(Long.MIN_VALUE,
                    ByteBuffer.wrap(fresh.bytes).order(ByteOrder.nativeOrder()).getLong(8))
                for (state in listOf(0, 1, 3, 4, 5)) {
                    thunk.state = state
                    assertEquals(8, ClosureInspection.image(thunk).bytes.size)
                    assertEquals(state, thunk.state)
                }
                thunk.value = value; thunk.state = 2
                assertSame(value, ClosureInspection.image(thunk).pointers.single())
            } finally { context.leave() }
        }
    }
}
