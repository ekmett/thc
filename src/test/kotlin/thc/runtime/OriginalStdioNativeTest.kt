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
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat
import java.util.IdentityHashMap

/** Executes unchanged installed GHC wrappers copied into real consumer Core. */
class OriginalStdioNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/original-stdio")
    private val names = listOf("originalWrite", "originalSafeWrite", "originalWriteErrno", "originalSafeWriteErrno")
    private fun json(file: File) = Json.parse(file.readText())
    private fun bytes(hex: String) = HexFormat.of().parseHex(hex)
    private fun manifest(): Map<String, Any?> {
        val manifest = json(File(directory, "manifest.json")) as Map<String, Any?>
        assertEquals(false, manifest["installedArtifactsHashed"])
        assertEquals("full", manifest["mode"])
        assertEquals("9.14.1", manifest["ghc"])
        assertEquals(true, manifest["strictAccepted"])
        for (key in listOf("inputHashes", "artifactHashes")) {
            for ((path, expected) in manifest[key] as Map<String, String>) {
                val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes()))
                assertEquals(expected, actual, "Stale original stdio fixture: $path")
            }
        }
        assertEquals(names, manifest["entries"])
        assertEquals(144, (manifest["nativeRows"] as Number).toInt())
        assertEquals(144, (manifest["modelRows"] as Number).toInt())
        return manifest
    }
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
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
            result.add(target)
        }
        visit(entry)
        return result
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
        assertNull(state.pending)
    }

    @Test fun originalInlinedCallsMatchNativeWithInlining() = native(true)
    @Test fun originalInlinedCallsMatchNativeAcrossResidualCalls() = native(false)

    private fun native(inlining: Boolean) {
        val manifest = manifest()
        val oracle = json(File(directory, "oracle.json")) as List<Map<String, Any?>>
        assertEquals(oracle, json(File(directory, "expected.json")))
        assertEquals(144, oracle.size)
        val rows = oracle.groupBy { it["entry"] as String }
        assertEquals(names.toSet(), rows.keys)
        val payload = bytes(manifest["payloadHex"] as String)
        assertArrayEquals(ByteArray(256) { it.toByte() }, payload)
        val stages = manifest["stages"] as Map<String, Map<String, Any?>>
        assertEquals(setOf("pre", "post"), stages.keys)
        for ((stage, settings) in stages) {
            val proofs = settings["proofCopies"] as List<Map<String, Any?>>
            assertEquals(6, proofs.size)
            assertEquals(names.toSet(), proofs.map { it["entry"] as String }.toSet())
            val paths = settings["modules"] as List<String>
            val module = CoreModules.merge(paths.map { json(File(root, it)) as Map<String, Any?> })
            for (name in names) for (backend in listOf("ast", "bytecode")) {
                val output = ByteArrayOutputStream(); val errors = ByteArrayOutputStream()
                Context.newBuilder("thc").allowIO(IOAccess.NONE).out(output).err(errors).allowExperimentalOptions(true)
                    .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
                    .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
                    .build().use { context ->
                        context.initialize("thc"); context.enter()
                        try {
                            val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                            val linked = CoreModules.reachable(module, name) + ("instrument" to true)
                            val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                            val entry = program.entryTarget(name)
                            val address = ManagedAddress.fromByteArray(payload.copyOf())
                            val label = "$stage/$backend/$name/inlining=$inlining"
                            fun check(row: Map<String, Any?>) {
                                output.reset(); errors.reset()
                                val args = (row["arguments"] as List<Number>).map { it.toLong() }
                                assertEquals(3, args.size)
                                val (fd, offset, count) = args
                                val success = fd == 1L || fd == 2L
                                val expected = if (name.endsWith("Errno")) {
                                    if (success) -(count + 2) else StdioHostAbi.load().error(4)
                                } else if (success) count else -1L
                                assertEquals(expected, (row["result"] as Number).toLong(), "$label model/$args")
                                assertEquals(expected, Calls.target(entry, arrayOf(0L, fd, address, offset, count)), "$label/$args")
                                assertArrayEquals(bytes(row["stdoutHex"] as String), output.toByteArray(), "$label stdout/$args")
                                assertArrayEquals(bytes(row["stderrHex"] as String), errors.toByteArray(), "$label stderr/$args")
                                assertArrayEquals(payload, address.cbitsBacking(), "$label immutable input/$args")
                                released(language)
                            }
                            val cases = rows.getValue(name)
                            assertEquals(36, cases.size)
                            cases.forEach(::check)
                            val active = targets(entry)
                            for (target in active) {
                                target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                                valid(target, "$label installed")
                            }
                            for (row in cases) {
                                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                check(row)
                                val after = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                assertTrue(after > before, "$label entered compiled code")
                                assertEquals(active, targets(entry), "$label retained target identities")
                                active.forEach { valid(it, "$label first-installed target remains valid") }
                            }
                            for (counter in listOf("unsupportedTraps", "blackholes"))
                                assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong(), "$label/$counter")
                        } finally { context.leave() }
                    }
            }
        }
    }
}
