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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import thc.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Timeout(60)
class HintTraceTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val traceText = "[thc trace event] hint-trace-event\n[thc trace marker] hint-trace-marker\n" +
        "[thc trace binary] 41004200\n"
    private fun context(output: ByteArrayOutputStream) = Context.newBuilder("thc")
        .err(output).allowNativeAccess(true).allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun fixture(): Map<String, Any?> {
        val receipt = Json.parse(File(root, "build/hint-trace/manifest.json").readText()) as Map<String, Any?>
        assertEquals("9.14.1", receipt["ghc"])
        assertEquals(10L, receipt["nativeRows"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in receipt[kind] as Map<String, String>) {
                val digest = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, digest, "Stale fixture: $path")
            }
        return receipt
    }
    private fun module(stage: String) =
        Json.parse(File(root, "build/hint-trace/$stage/core/HintTraceAudit.json").readText()) as Map<String, Any?>
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun call(program: ExecutableProgram, name: String, vararg args: Any?): Any? =
        Calls.target(program.entryTarget(name), arrayOf(0L, *args))
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
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
            result.add(target)
        }
        visit(entry); return result
    }
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), target.rootNode.name)

    @Test fun allNineteenOriginalPrimopsMatchNativeResultsAndEmitTargetRecords() {
        val receipt = fixture()
        val rows = File(root, "build/hint-trace/oracle.tsv").readLines().map { it.split('\t') }
        assertEquals(10, rows.size)
        fun primitives(value: Any?): Set<String> = when (value) {
            is Map<*, *> -> value.values.flatMap { primitives(it) }.toSet()
            is List<*> -> if (value.firstOrNull() == "prim") setOf(value[1] as String)
                else value.flatMap { primitives(it) }.toSet()
            else -> emptySet()
        }
        for (stage in receipt["stages"] as List<String>) {
            val module = module(stage)
            assertTrue(primitives(module).containsAll(prefetchArities.keys + TraceOp.entries.map { it.primitive }))
            for (backend in listOf("ast", "bytecode")) {
                val output = ByteArrayOutputStream()
                context(output).use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val program = program(language, CoreModules.reachable(module, listOf("hints", "traces"), true) +
                            ("instrument" to true), backend)
                        fun check(row: List<String>) {
                            val (name, input, result) = row
                            assertEquals(input.toLong() + if (name == "hints") 1L else 19L, result.toLong())
                            output.reset()
                            assertEquals(result.toLong(), call(program, name, input.toLong()), "$stage/$backend/$name")
                            assertEquals(if (name == "traces") traceText else "", output.toString(Charsets.UTF_8))
                            val handoff = language.handoffState.get()
                            assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                            assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
                        }
                        rows.forEach(::check)
                        val active = listOf("hints", "traces").flatMap { targets(program.entryTarget(it)) }.distinct()
                        active.forEach { it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true); valid(it) }
                        for (row in rows) {
                            val before = (program.diagnostics()["compiledEntries"] as Number).toLong()
                            check(row)
                            // Each original function enters itself and its runRW lambda, exactly once.
                            assertEquals(before + 2, (program.diagnostics()["compiledEntries"] as Number).toLong())
                            active.forEach(::valid)
                        }
                    } finally { context.leave() }
                }
            }
        }
    }

    @Test fun traceBytesBoundsAndIgnoredAddressHintsHaveSensibleTargetSemantics() {
        fixture()
        for (backend in listOf("ast", "bytecode")) {
            val output = ByteArrayOutputStream()
            context(output).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program = program(language, CoreModules.reachable(module("pre"),
                        listOf("event", "marker", "binary", "addressHints"), true), backend)
                    val address = ManagedAddress.fromByteArray("λ\n\\\u0000ignored".toByteArray(Charsets.UTF_8))
                    assertEquals(23L, call(program, "event", address, 23L))
                    assertEquals("[thc trace event] λ\\x0a\\\\\n", output.toString(Charsets.UTF_8))
                    output.reset()
                    assertEquals(3L, call(program, "binary", ManagedAddress.fromByteArray(byteArrayOf(0,-1,10,88)), 3L))
                    assertEquals("[thc trace binary] 00ff0a\n", output.toString(Charsets.UTF_8))
                    output.reset()
                    assertEquals(0L, call(program, "binary", ManagedAddress.nullAddress(), 0L))
                    assertEquals(7L, call(program, "marker", ManagedAddress.fromByteArray(byteArrayOf(0)), 7L))
                    assertEquals("[thc trace binary] \n[thc trace marker] \n", output.toString(Charsets.UTF_8))
                    output.reset()
                    val bytes = ManagedAddress.fromByteArray(byteArrayOf(65,0))
                    for ((name, location, count) in listOf(
                        Triple("event", ManagedAddress.fromByteArray(byteArrayOf(65)), 0L),
                        Triple("event", ManagedAddress.unownedNumeric(1234), 0L),
                        Triple("binary", bytes, -1L), Triple("binary", bytes, 3L),
                        Triple("binary", bytes, Long.MAX_VALUE)))
                        assertThrows(RuntimeFault::class.java) { call(program, name, location, count) }
                    assertEquals(0, output.size(), "Invalid trace must not publish a partial record")
                    val pointerCell = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8))
                    pointerCell.writeAddressElementIndex(0, bytes)
                    assertThrows(RuntimeFault::class.java) { call(program, "binary", pointerCell, 8L) }
                    for (location in listOf(ManagedAddress.nullAddress(), ManagedAddress.unownedNumeric(1234), bytes.plus(2)))
                        for (offset in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE))
                            assertEquals(offset, call(program, "addressHints", location, offset))
                    assertEquals(0, output.size(), "Prefetch emits no diagnostic and dereferences nothing")
                } finally { context.leave() }
            }
        }
    }

    @Test fun nativeTraceLifetimesAndContextOutputIsolation() {
        assumeTrue(System.getProperty("os.name") == "Linux" &&
            System.getProperty("os.arch") in setOf("amd64", "x86_64"), "Existing native malloc provider ABI")
        val left = ByteArrayOutputStream(); val right = ByteArrayOutputStream()
        context(left).use { first -> context(right).use { second ->
            first.initialize("thc"); second.initialize("thc")
            first.enter()
            val address: ManagedAddress
            try {
                val allocations = Language.currentState().nativeAllocations
                address = allocations.malloc(4)
                address.writeWord8(0, 65); address.writeWord8(1, 0); address.writeWord8(2, 255); address.writeWord8(3, 10)
                RtsDiagnostics.trace(null, TraceOp.EVENT, address, 0)
                RtsDiagnostics.trace(null, TraceOp.BINARY, address.plus(1), 3)
            } finally { first.leave() }
            second.enter()
            try {
                assertThrows(RuntimeFault::class.java) { RtsDiagnostics.trace(null, TraceOp.EVENT, address, 0) }
                RtsDiagnostics.trace(null, TraceOp.MARKER, ManagedAddress.fromHex("42"), 0)
            } finally { second.leave() }
            first.enter()
            try {
                Language.currentState().nativeAllocations.free(address)
                assertThrows(RuntimeFault::class.java) { RtsDiagnostics.trace(null, TraceOp.BINARY, address, 1) }
            } finally { first.leave() }
            assertEquals("[thc trace event] A\n[thc trace binary] 00ff0a\n", left.toString(Charsets.UTF_8))
            assertEquals("[thc trace marker] B\n", right.toString(Charsets.UTF_8))
        } }
    }

    @Test fun concurrentRecordsDoNotInterleave() {
        val output = ByteArrayOutputStream()
        val workers = Executors.newFixedThreadPool(2)
        try { context(output).use { context ->
            context.initialize("thc")
            val jobs = (0..1).map { worker -> workers.submit {
                context.enter()
                try {
                    val text = ManagedAddress.fromByteArray(("worker-$worker" + '\u0000').toByteArray())
                    repeat(32) { RtsDiagnostics.trace(null, TraceOp.EVENT, text, 0) }
                } finally { context.leave() }
            } }
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            val lines = output.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
            assertEquals(64, lines.size)
            for (worker in 0..1) assertEquals(32, lines.count { it == "[thc trace event] worker-$worker" })
        } } finally { workers.shutdownNow() }
    }
}
