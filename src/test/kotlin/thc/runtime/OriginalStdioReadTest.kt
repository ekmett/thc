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
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.IdentityHashMap

/** Real GHC c_read/c_safe_read wrappers, native bytes, and compiled typed paths. */
class OriginalStdioReadTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/original-stdio-read")
    private val names = listOf("originalRead", "originalSafeRead", "originalReadErrno", "originalSafeReadErrno")
    private val payload = byteArrayOf(0, 1, 127, -128, -1, 65, -61, -87)
    private val cases = listOf(
        listOf(-1L, 0L, 0L, 0L), listOf(-1L, 3L, 4L, 0L),
        listOf(1L, 3L, 4L, 0L), listOf(2L, 3L, 0L, 0L),
        listOf(0L, 16L, 0L, 0L), listOf(0L, 2L, 4L, 0L),
        listOf(0L, 1L, 12L, 0L), listOf(0L, 0L, 5L, 4L),
        listOf(0L, 5L, 4L, 8L), listOf(0L, 3L, 6L, 7L))
    private fun json(path: File) = Json.parse(path.readText())
    private fun hex(value: ByteArray) = OriginalStdioChecks.hex(value)

    private fun expected(name: String, args: List<Long>): Pair<Long, ByteArray> {
        val (fd, offset, count, start) = args
        val buffer = ByteArray(16) { 0xa5.toByte() }
        val copied = if (fd == 0L) minOf(count, (payload.size - start).toLong()).toInt() else 0
        if (copied > 0) payload.copyInto(buffer, offset.toInt(), start.toInt(), start.toInt() + copied)
        val result = if (fd != 0L) -1L else copied.toLong()
        return (if (name.endsWith("Errno")) {
            if (result < 0) StdioHostAbi.load().error(4) else -result - 2
        } else result) to buffer
    }

    private fun rows(): List<Map<String, Any?>> {
        val raw = json(File(directory, "oracle.json")) as List<Map<String, Any?>>
        val expectedKeys = names.flatMap { name -> cases.map { name to it } }
        assertEquals(expectedKeys.size, raw.size)
        for ((index, row) in raw.withIndex()) {
            val (name, args) = expectedKeys[index]
            assertEquals(name, row["entry"])
            assertEquals(args, row["arguments"])
            val (result, buffer) = expected(name, args)
            assertEquals(result, row["result"], "$name/$args")
            assertEquals(hex(buffer), row["bufferHex"], "$name/$args buffer")
            assertEquals("", row["stdoutHex"]); assertEquals("", row["stderrHex"])
            assertEquals("$result\n${hex(buffer)}\n", File(directory, "results/$index.txt").readText())
        }
        return raw
    }

    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val found = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() }
                else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val callee = call.currentCallTarget as? RootCallTarget ?: continue
                if (callee.rootNode is GuestRoot) visit(callee)
            }
            found.add(target)
        }
        visit(entry)
        return found
    }

    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)

    @Test fun originalReadMatchesNativeWithInlining() = native(true)
    @Test fun originalReadMatchesNativeAcrossResidualCalls() = native(false)

    private fun native(inlining: Boolean) {
        val manifest = json(File(directory, "manifest.json")) as Map<String, Any?>
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"])
        assertEquals(names, manifest["entries"]); assertEquals(40L, manifest["nativeRows"])
        assertEquals(hex(payload), manifest["payloadHex"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/OriginalStdioReadAudit.hs", "compiler/test-fixtures/OriginalStdioReadNative.hs",
            "test/haskell-fixtures/OriginalStdioFixtures.hs", "test/haskell-fixtures/Main.hs",
            "scripts/core-capabilities.json", "thc.cabal"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf(
            "build/original-stdio-read/input.bin", "build/original-stdio-read/oracle.json"),
            "build/original-stdio-read/")
        assertArrayEquals(payload, File(directory, "input.bin").readBytes())
        val oracle = rows().groupBy { it["entry"] as String }
        assertEquals(names.toSet(), oracle.keys)
        val stages = manifest["stages"] as Map<String, List<String>>
        val audits = manifest["audits"] as Map<String, Map<String, String>>
        assertEquals(setOf("pre", "post"), stages.keys)
        for ((stage, paths) in stages) {
            assertEquals(listOf("OriginalStdioReadAudit", "THC.InterfaceClosure").map { "build/original-stdio-read/$stage/core/$it.json" }, paths)
            val module = CoreModules.merge(paths.map { json(File(root, it)) as Map<String, Any?> })
            val bindings = module["bindings"] as List<Map<String, Any?>>
            assertEquals(names.toSet(), bindings.mapNotNull { it["name"] as? String }.toSet().intersect(names.toSet()))
            val known = bindings.map { it["id"] }.toSet()
            assertEquals(names.toSet(), audits.getValue(stage).keys)
            for (name in names) {
                val owner = "main:OriginalStdioReadAudit.$name"
                val body = bindings.single { it["id"] == owner }["expr"]
                val symbols = OriginalStdioChecks.foreignCalls(body).map { app ->
                    val head = app[1] as List<Any?>
                    CoreOriginalStdio.validateHead(head, head[1] in known)
                    val operation = CoreOriginalStdio.validate(app[6], (app[2] as List<*>).map {
                        (it as List<*>).last().let { meta -> (meta as Map<*, *>)["rep"] }
                    }, app[3] as List<*>, (app[6] as Map<*, *>)["rep"])
                    assertNotNull(operation)
                    operation!!.symbol
                }
                val read = if (name.contains("Safe")) OriginalStdioOp.READ_SAFE else OriginalStdioOp.READ_UNSAFE
                assertEquals(listOf(read.symbol) + if (name.endsWith("Errno")) listOf(OriginalStdioOp.ERRNO.symbol) else emptyList(), symbols)
                val path = "build/original-stdio-read/$stage/$name.audit.json"
                assertEquals(path, audits.getValue(stage).getValue(name))
                OriginalStdioChecks.audit(json(File(root, path)) as Map<String, Any?>, owner, symbols)
            }
            for (name in names) for (backend in listOf("ast", "bytecode")) {
                val input = ByteArrayInputStream(payload)
                Context.newBuilder("thc").allowIO(IOAccess.NONE).`in`(input).allowExperimentalOptions(true)
                    .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
                    .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
                    .build().use { context ->
                        context.initialize("thc"); context.enter()
                        try {
                            val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                            val linked = CoreModules.reachable(module, name) + ("instrument" to true)
                            val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                            val entry = program.entryTarget(name)
                            val label = "$stage/$backend/$name/inlining=$inlining"
                            fun check(row: Map<String, Any?>) {
                                val (fd, offset, count, start) = row["arguments"] as List<Long>
                                input.reset(); assertEquals(start, input.skip(start))
                                val buffer = ByteArray(16) { 0xa5.toByte() }
                                val address = ManagedAddress.fromByteArray(buffer)
                                val expected = row["result"] as Long
                                assertEquals(expected, Calls.target(entry, arrayOf(0L, fd, address, offset, count)), "$label/$row")
                                assertEquals(row["bufferHex"], hex(address.cbitsBacking()), "$label buffer/$row")
                                assertEquals(0, language.handoffState.get().arguments.depth)
                                assertEquals(0, language.handoffState.get().results.depth)
                            }
                            val cases = oracle.getValue(name)
                            cases.forEach(::check)
                            val active = targets(entry)
                            active.forEach {
                                it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true)
                                valid(it, "$label installed")
                            }
                            for (row in cases) {
                                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                check(row)
                                val after = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                assertTrue(after > before, "$label entered compiled code")
                                assertEquals(active, targets(entry), "$label retained target identities")
                                active.forEach { valid(it, "$label target remains valid") }
                            }
                            assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                        } finally { context.leave() }
                    }
            }
        }
    }

    /** A provider that returns zero for a nonempty request must not impersonate EOF. */
    @Test fun zeroProgressInputBecomesEioWithoutChangingDestination() {
        val zero = object : InputStream() {
            override fun read(): Int = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
        }
        Context.newBuilder("thc").allowIO(IOAccess.NONE).`in`(zero).build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val stdio = Language.currentState().stdio
                val address = ManagedAddress.fromByteArray(ByteArray(16) { 0xa5.toByte() })
                assertEquals(-1L, stdio.read(0L, address, 4L))
                assertEquals(StdioHostAbi.load().error(6), stdio.errno())
                assertEquals(ByteArray(16) { 0xa5.toByte() }.toList(), address.cbitsBacking().toList())
            } finally { context.leave() }
        }
    }
}
