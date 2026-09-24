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
import org.junit.jupiter.api.io.TempDir
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.HexFormat
import java.util.IdentityHashMap

/** Executes unchanged installed GHC wrappers copied into real consumer Core. */
class OriginalStdioNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/original-stdio")
    private val names = OriginalStdioChecks.names
    private fun json(file: File) = Json.parse(file.readText())
    private fun bytes(hex: String) = HexFormat.of().parseHex(hex)
    private fun manifest(): Map<String, Any?> {
        val manifest = json(File(directory, "manifest.json")) as Map<String, Any?>
        assertEquals(1L, manifest["schema"])
        assertEquals(false, manifest["installedArtifactsHashed"])
        assertEquals(false, manifest["runtimeVerified"])
        assertEquals("full", manifest["mode"])
        assertEquals("9.14.1", manifest["ghc"])
        assertEquals(true, manifest["strictAccepted"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/OriginalStdioAudit.hs", "compiler/test-fixtures/OriginalStdioAuditNative.hs",
            "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/OriginalStdioFixtures.hs",
            "scripts/prepare-original-stdio.sh", "thc.cabal",
            "scripts/audit-core.py", "scripts/core_original_stdio.py", "scripts/core-capabilities.json"))
        val required = mutableSetOf("build/original-stdio/oracle.json")
        for (stage in listOf("pre", "post")) {
            required.addAll(listOf("OriginalStdioAudit", "THC.InterfaceClosure").map { "build/original-stdio/$stage/core/$it.json" })
            required.addAll(names.map { "build/original-stdio/$stage/$it.audit.json" })
        }
        for (index in 0 until 144) {
            required.add("build/original-stdio/results/$index.txt")
            required.addAll(listOf("stdout", "stderr", "command.json").map { "build/original-stdio/logs/native-${index.toString().padStart(3, '0')}.$it" })
        }
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], required, "build/original-stdio/")
        assertEquals(names, manifest["entries"])
        assertEquals(144L, manifest["nativeRows"])
        val commands = manifest["commands"] as List<Map<String, Any?>>
        assertTrue(commands.isNotEmpty()); assertTrue(commands.all { it["exit"] == 0L })
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

    @Test fun nativeDomainAndRawObservationsHaveIndependentExpectations() {
        val rows = OriginalStdioChecks.rows(json(File(directory, "oracle.json")))
        assertEquals(144, rows.size); assertEquals(144, rows.distinct().size)
        for (name in names) for (field in listOf("stdoutHex", "stderrHex"))
            assertTrue(rows.any { it["entry"] == name && it[field] == OriginalStdioChecks.hex(OriginalStdioChecks.payload) })
        for ((index, row) in rows.withIndex()) {
            val prefix = "logs/native-${index.toString().padStart(3, '0')}"
            assertArrayEquals(bytes(row["stdoutHex"] as String), File(directory, "$prefix.stdout").readBytes())
            assertArrayEquals(bytes(row["stderrHex"] as String), File(directory, "$prefix.stderr").readBytes())
            assertEquals("${row["result"]}\n", File(directory, "results/$index.txt").readText())
        }
    }

    @Test fun missingDuplicateReorderedOrForgedNativeRowsReject() {
        val rows = OriginalStdioChecks.expectedRows()
        for (bad in listOf(rows.dropLast(1), rows + rows[0], rows.reversed()))
            assertThrows(AssertionError::class.java) { OriginalStdioChecks.rows(bad) }
        for ((field, value) in listOf("result" to 999L, "stdoutHex" to "ff", "stderrHex" to "ff", "entry" to "syntheticWrite",
            "arguments" to listOf(0L, 0L, 1L), "arguments" to listOf(1L, -1L, 1L),
            "arguments" to listOf(1L, 256L, 1L), "arguments" to listOf(1L, 0L, -1L),
            "arguments" to listOf(1L, 0L, true), "arguments" to listOf(1L, 0L), "result" to -1.0))
            assertThrows(AssertionError::class.java) { OriginalStdioChecks.rows(listOf(rows[0] + (field to value)) + rows.drop(1)) }
    }

    @Test fun originalConsumerStructureAndAuditMutationsReject() {
        fun module() = json(File(directory, "pre/core/OriginalStdioAudit.json")) as MutableMap<String, Any?>
        fun expression(module: Map<String, Any?>) = (module["bindings"] as List<Map<String, Any?>>)
            .first { it["name"] == names.first() }["expr"] as MutableList<Any?>
        val mutations: List<(MutableMap<String, Any?>) -> Unit> = listOf(
            { ((expression(it)[1] as List<MutableMap<String, Any?>>)[0])["rep"] = OriginalStdioFixtures.scalar("WordRep") },
            { val run = expression(it)[2] as MutableList<Any?>; run[3] = listOf(0L) },
            { val run = expression(it)[2] as List<*>; ((run[1] as List<*>)[1] as List<MutableMap<String, Any?>>)[0]["type"] = "State# Forged" },
            { val app = OriginalStdioChecks.foreignCalls(expression(it)).first() as MutableList<Any?>; app[1] = listOf("var", "unproved") },
            { val app = OriginalStdioChecks.foreignCalls(expression(it)).first(); ((app[6] as Map<*, *>)["foreignCall"] as MutableMap<String, Any?>)["safety"] = "safe" },
            { (it["bindings"] as MutableList<Any?>).add((it["bindings"] as List<*>).first()) })
        for (mutate in mutations) {
            val changed = module().also(mutate)
            val failure = assertThrows(Throwable::class.java) { OriginalStdioChecks.module(changed) }
            assertTrue(failure is AssertionError || failure is RuntimeFault, failure.toString())
        }
        val owner = "main:OriginalStdioAudit.originalWrite"
        val symbols = OriginalStdioChecks.module(module()).getValue(owner)
        val audit = json(File(directory, "pre/originalWrite.audit.json")) as Map<String, Any?>
        OriginalStdioChecks.audit(audit, owner, symbols)
        for ((key, value) in listOf("accepted" to false, "roots" to listOf("other"),
            "issues" to listOf(mapOf("code" to "unsupported-primitive")), "missingGlobals" to listOf(mapOf("id" to "other")),
            "reachableBindings" to listOf(mapOf("id" to owner), mapOf("id" to "helper")), "foreignCalls" to emptyList<Any?>()))
            assertThrows(AssertionError::class.java) { OriginalStdioChecks.audit(audit + (key to value), owner, symbols) }
    }

    @Test fun provenanceRejectsMissingTamperedAndEscapingInputs(@TempDir temporary: File) {
        val file = File(temporary, "source.hs").also { it.writeText("native source") }
        val hash = OriginalStdioChecks.hex(java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        val inputs = mapOf("source.hs" to hash)
        OriginalStdioChecks.hashes(temporary, inputs, inputs.keys)
        assertThrows(AssertionError::class.java) { OriginalStdioChecks.hashes(temporary, emptyMap<String, String>(), inputs.keys) }
        assertThrows(AssertionError::class.java) { OriginalStdioChecks.hashes(temporary, mapOf("../source.hs" to hash), emptySet()) }
        assertThrows(AssertionError::class.java) { OriginalStdioChecks.hashes(temporary, mapOf(file.path to hash), emptySet()) }
        file.appendText(" changed")
        assertThrows(AssertionError::class.java) { OriginalStdioChecks.hashes(temporary, inputs, inputs.keys) }
    }

    private fun native(inlining: Boolean) {
        val manifest = manifest()
        val oracle = OriginalStdioChecks.rows(json(File(directory, "oracle.json")))
        val rows = oracle.groupBy { it["entry"] as String }
        assertEquals(names.toSet(), rows.keys)
        val payload = bytes(manifest["payloadHex"] as String)
        assertArrayEquals(ByteArray(256) { it.toByte() }, payload)
        val stages = manifest["stages"] as Map<String, Map<String, Any?>>
        val audits = manifest["audits"] as Map<String, Map<String, String>>
        assertEquals(setOf("pre", "post"), stages.keys)
        assertEquals(stages.keys, audits.keys)
        for ((stage, settings) in stages) {
            val paths = settings["modules"] as List<String>
            assertEquals(listOf("OriginalStdioAudit", "THC.InterfaceClosure").map { "build/original-stdio/$stage/core/$it.json" }, paths)
            val module = CoreModules.merge(paths.map { json(File(root, it)) as Map<String, Any?> })
            val calls = OriginalStdioChecks.module(module)
            assertEquals(names.toSet(), audits.getValue(stage).keys)
            for (name in names) {
                val path = "build/original-stdio/$stage/$name.audit.json"
                assertEquals(path, audits.getValue(stage).getValue(name))
                val owner = "main:OriginalStdioAudit.$name"
                OriginalStdioChecks.audit(json(File(root, path)) as Map<String, Any?>, owner, calls.getValue(owner))
            }
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
                                val expected = row["result"]
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
