// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class PinnedAddressTest {
    private fun literal(value: Any?) = object : Expr() { override fun execute(frame: VirtualFrame): Any? = value }
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun context(inlining: Boolean = true) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", inlining.toString()).build()
    private fun manifest() = Json.parse(File(root, "build/pinned-addresses/manifest.json").readText()) as Map<String, Any?>
    private fun merged(paths: List<String>) = CoreModules.merge(paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> })
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget) { target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true); valid(target, "installed") }
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val node = target.rootNode
            val roots = if (node is BytecodeRoot) listOf(node) + node.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(node)
            for (call in roots.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            result.add(target)
        }
        visit(entry); return result
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.results.depth)
        assertEquals(0, state.arguments.retainedReferences()); assertEquals(0, state.results.retainedReferences())
    }
    @Test fun nativePinnedAddressesAndLazyKeepAliveWithInlining() = native(true)
    @Test fun nativePinnedAddressesAndLazyKeepAliveAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        val manifest = manifest()
        assertEquals(true, manifest["strictAccepted"]); assertEquals("full", manifest["mode"])
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, hash, "Stale pinned-address input $path")
        }
        val entries = manifest["entries"] as Map<String, Number>
        val guestCalls = manifest["expectedGuestCallsByEntry"] as Map<String, Number>
        val rows = File(root, "build/pinned-addresses/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals((entries.keys + (manifest["publicFrontiers"] as Map<*, *>).keys).toSet(), rows.keys)
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.values.sumOf { it.size })
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) for ((name, arity) in entries) {
            val cases = rows.getValue(name)
            val audit = Json.parse(File(root, "build/pinned-addresses/$stage/$name.audit.json").readText()) as Map<*, *>
            assertEquals(true, audit["accepted"])
            for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program = program(language, CoreModules.reachable(merged(paths), name) + ("instrument" to true), backend)
                    val function = context.asValue(EntryValue(program, name, arity.toInt()))
                    val host = program.hostEntryTarget(arity.toInt()); val original = program.entryTarget(name)
                    val label = "$stage/$backend/$name/inlining=$inlining"
                    fun check(row: List<String>) {
                        val arguments = row.subList(1, row.lastIndex).map { it.toLong() }.toTypedArray()
                        assertEquals(row.last().toLong(), function.execute(*arguments).asLong(), "$label/$row")
                        released(language)
                    }
                    cases.forEach(::check)
                    val targets = activeTargets(host)
                    assertEquals(guestCalls.getValue(name).toInt() + 1, targets.size, "$label concrete guest roots plus host")
                    targets.filter { it !== host }.forEach(::compile)
                    assertTrue(function.invokeMember("compile").asBoolean())
                    for (row in cases.asReversed()) {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        check(row)
                        assertEquals(before + guestCalls.getValue(name).toLong(), (program.diagnostics().getValue("compiledEntries") as Number).toLong(), "$label exact compiled guest entries")
                        assertEquals(targets, activeTargets(host), "$label active identities")
                        valid(original, label); targets.forEach { valid(it, label) }
                    }
                    for (counter in listOf("unsupportedTraps", "blackholes")) assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong(), label)
                    println("PinnedAddress PASS $label rows=${cases.size}")
                } finally { context.leave() }
            }
        }
    }
    @Test fun allocationChecksFullWidthSizeAndPowerOfTwoAlignment() {
        for (size in listOf(0L, 1L, 16L, 129L)) for (alignment in listOf(1L, 2L, 8L, 64L, 1L shl 40, 1L shl 62))
            assertEquals(size, PinnedMemory.allocate(size, alignment).size)
        for (size in listOf(Long.MIN_VALUE, -1L, Int.MAX_VALUE.toLong() + 1, 1L shl 32, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { PinnedMemory.allocate(size, 8) }
        for (alignment in listOf(Long.MIN_VALUE, -1L, 0L, 3L, 7L, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { PinnedMemory.allocate(0, alignment) }
    }
    @Test fun statePrecedesMemoryEffectsAndFailedReadsDoNotPublish() {
        val descriptor = FrameDescriptor.newBuilder().apply { addSlot(FrameSlotKind.Object, null, null) }.build()
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
        for (operation in listOf(PinnedMemoryOp.NEW, PinnedMemoryOp.NEW_ALIGNED, PinnedMemoryOp.READ, PinnedMemoryOp.WRITE)) for (fail in listOf(false, true)) {
            val array = byteArrayOf(11, 22); val address = ManagedAddress.fromByteArray(array)
            val sentinel = Any(); FrameAccess.write(frame, 0, sentinel)
            val events = mutableListOf<String>()
            fun operand(name: String, value: Any?) = object : Expr() { override fun execute(frame: VirtualFrame): Any? { events.add(name); return value } }
            val state = object : Expr() { override fun execute(frame: VirtualFrame): Any {
                events.add("state"); assertSame(sentinel, FrameAccess.read(frame, 0)); assertArrayEquals(byteArrayOf(11, 22), array)
                if (fail) throw RuntimeFault("State failed"); return Unit
            } }
            val operands = when (operation) {
                PinnedMemoryOp.NEW -> arrayOf(operand("size", 2L), state)
                PinnedMemoryOp.NEW_ALIGNED -> arrayOf(operand("size", 2L), operand("alignment", 8L), state)
                PinnedMemoryOp.READ -> arrayOf(operand("address", address), operand("offset", 1L), state)
                else -> arrayOf(operand("address", address), operand("offset", 1L), operand("value", 511L), state)
            }
            val expression = PinnedMemoryExpression(operation, CoreRepresentation.UNKNOWN, operands)
            fun run() { if (operation.tuple) expression.executeTuple(frame, intArrayOf(0), 0) else expression.execute(frame) }
            if (fail) { assertThrows(RuntimeFault::class.java) { run() }; assertSame(sentinel, FrameAccess.read(frame, 0)); assertArrayEquals(byteArrayOf(11, 22), array) }
            else {
                run()
                when (operation) {
                    PinnedMemoryOp.NEW, PinnedMemoryOp.NEW_ALIGNED -> assertEquals(2L, (FrameAccess.read(frame, 0) as ManagedAllocation).size)
                    PinnedMemoryOp.READ -> assertEquals(22L, FrameAccess.read(frame, 0))
                    else -> assertArrayEquals(byteArrayOf(11, -1), array)
                }
            }
            assertEquals(when (operation) {
                PinnedMemoryOp.NEW -> listOf("size", "state")
                PinnedMemoryOp.NEW_ALIGNED -> listOf("size", "alignment", "state")
                PinnedMemoryOp.READ -> listOf("address", "offset", "state")
                else -> listOf("address", "offset", "value", "state")
            }, events)
        }
        for (index in listOf(-1L, Long.MIN_VALUE, 2L, 1L shl 32, Long.MAX_VALUE)) {
            val sentinel = Any(); FrameAccess.write(frame, 0, sentinel)
            val expression = PinnedMemoryExpression(PinnedMemoryOp.READ, CoreRepresentation.UNKNOWN,
                arrayOf(literal(ManagedAddress.fromByteArray(byteArrayOf(3, 4))), literal(index), literal(Unit)))
            assertThrows(RuntimeFault::class.java) { expression.executeTuple(frame, intArrayOf(0), 0) }
            assertSame(sentinel, FrameAccess.read(frame, 0))
        }
    }
    @Test fun keepAliveEvaluatesKeptReferenceAndStateThenRunsActionExactlyOnce() {
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameDescriptor.newBuilder().build())
        for (failure in listOf("none", "state", "action")) {
            val events = mutableListOf<String>(); val kept = Any()
            fun expression(name: String, value: Any?) = object : Expr() { override fun execute(frame: VirtualFrame): Any? {
                events.add(name); if (failure == name) throw RuntimeFault(name); return value
            } }
            val node = KeepAliveExpression(expression("kept", kept), expression("state", Unit), expression("action", 77L), CoreRepresentation.UNKNOWN)
            if (failure == "none") assertEquals(77L, node.executeLong(frame)) else assertThrows(RuntimeFault::class.java) { node.executeLong(frame) }
            assertEquals(if (failure == "state") listOf("kept", "state") else listOf("kept", "state", "action"), events)
        }
    }
}
