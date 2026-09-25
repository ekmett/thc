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
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

/** Genuine exported Core, native GHC, and an independent scalar byte oracle. */
class SimdInt32ByteArrayTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/simd-int32x4-bytearray")
    private fun context(inlining: Boolean) = Context.newBuilder("thc")
        .allowExperimentalOptions(true).option("compiler.Inlining", inlining.toString())
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw")
        .build()
    private fun withLanguage(inlining: Boolean, action: (Language) -> Unit) = context(inlining).use { context ->
        context.initialize("thc"); context.enter()
        try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun validateMode(provenance: Map<String, Any?>, architecture: String = System.getProperty("os.arch")) {
        val fields = listOf("stages", "modelRows", "modelByteOrder", "nativeRows", "nativeByteOrder", "modelMatched")
        assertTrue(provenance.keys.containsAll(fields), "Missing explicit Int32X4 preparation mode fields")
        fun rows(field: String) {
            val count = provenance[field] as? Number
            assertTrue(count != null && count.toLong() == 9666L && count.toDouble() == 9666.0, "$field must be exactly 9666")
        }
        rows("modelRows")
        assertEquals("little", provenance["modelByteOrder"], "Int32X4 model byte order")
        // Use the executing host, not a producer architecture supplied by the manifest.
        if (architecture.lowercase() in listOf("arm64", "aarch64")) {
            assertEquals(listOf("pre"), provenance["stages"], "ARM64 export-only Core stages")
            for (field in listOf("nativeRows", "nativeByteOrder", "modelMatched"))
                assertNull(provenance[field], "ARM64 export-only $field must be explicitly null")
        } else {
            assertEquals(listOf("pre", "post"), provenance["stages"], "Native Core stages")
            rows("nativeRows")
            assertEquals("little", provenance["nativeByteOrder"], "Native byte order")
            assertEquals(true, provenance["modelMatched"], "Native/model agreement")
        }
    }
    private fun provenance(): Map<String, Any?> {
        val provenance = Json.parse(File(directory, "provenance.json").readText()) as Map<String, Any?>
        validateMode(provenance)
        assertEquals(ByteOrder.LITTLE_ENDIAN, ByteOrder.nativeOrder(), "Int32X4 bounded corpus requires a little-endian host")
        assertEquals(true, provenance["positiveAuditsAccepted"])
        for (file in (provenance["sources"] as List<Map<String, String>>) + (provenance["artifacts"] as List<Map<String, String>>)) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, file.getValue("path")).readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(file["sha256"], hash, "Stale Int32X4 memory source/artifact: ${file["path"]}")
        }
        return provenance
    }
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val targets = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val node = target.rootNode
            val nodes = if (node is BytecodeRoot) listOf(node) + node.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(node)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            targets.add(target)
        }
        visit(entry); return targets
    }
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)

    @Test fun nativeMemoryCoreHasExactCompiledEntriesWithInlining() = nativeCore(true)
    @Test fun nativeMemoryCoreHasExactCompiledEntriesWithoutInlining() = nativeCore(false)
    @Test fun preparationModeRejectsCorruptionAndNativeDowngrades() {
        val native = mapOf<String, Any?>("stages" to listOf("pre", "post"), "modelRows" to 9666L,
            "modelByteOrder" to "little", "nativeRows" to 9666L, "nativeByteOrder" to "little", "modelMatched" to true)
        val exported = native + mapOf("stages" to listOf("pre"), "nativeRows" to null,
            "nativeByteOrder" to null, "modelMatched" to null)
        val nativeCorruptions = listOf(
            "stages" to emptyList<String>(), "stages" to listOf("pre"), "stages" to listOf("post", "pre"),
            "stages" to listOf("pre", "post", "post"), "nativeRows" to null, "nativeRows" to 9665L,
            "nativeRows" to 9666.5, "nativeRows" to "9666", "nativeByteOrder" to null,
            "nativeByteOrder" to "big", "modelMatched" to null, "modelMatched" to false)
        val exportCorruptions = listOf("stages" to emptyList<String>(), "stages" to listOf("pre", "post"),
            "stages" to listOf("pre", "pre"), "nativeRows" to 0L, "nativeRows" to 9666L,
            "nativeByteOrder" to "little", "modelMatched" to true, "modelMatched" to false)
        val commonCorruptions = listOf("modelRows" to 9665L, "modelRows" to 9666.5,
            "modelRows" to "9666", "modelByteOrder" to "big")
        for (architecture in listOf("amd64", "x86_64", "riscv64", "arm64", "aarch64")) {
            val arm = architecture in listOf("arm64", "aarch64")
            val accepted = if (arm) exported else native
            validateMode(accepted, architecture)
            assertThrows(AssertionError::class.java, { validateMode(if (arm) native else exported, architecture) },
                "$architecture cannot silently change preparation modes")
            for ((field, value) in commonCorruptions + if (arm) exportCorruptions else nativeCorruptions)
                assertThrows(AssertionError::class.java, { validateMode(accepted + (field to value), architecture) },
                    "$architecture/$field=$value")
            for (field in accepted.keys)
                assertThrows(AssertionError::class.java, { validateMode(accepted - field, architecture) },
                    "$architecture/missing $field")
        }
        assertThrows(AssertionError::class.java) {
            validateMode(exported + ("toolchain" to mapOf("architecture" to "aarch64")), "amd64")
        }
    }
    @Test fun genuineVectorCallBindingsLoadBeforePublicHostAdmission() {
        val provenance = provenance()
        for (stage in provenance["stages"] as List<String>) for (backend in listOf("ast", "bytecode")) withLanguage(true) { language ->
            val module = Json.parse(File(directory, "$stage-core/SimdInt32X4ByteArray.json").readText()) as Map<String, Any?>
            for (name in listOf("vectorArgument", "readVectorEscape")) {
                val linked = CoreModules.reachable(module, name)
                assertNotNull(if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked),
                    "$stage/$backend/$name")
            }
            val directRead = CoreModules.reachable(module, "readTupleEscape")
            val error = assertThrows(UnsupportedCore::class.java) {
                if (backend == "ast") Program(language, directRead) else BytecodeProgram(language, directRead)
            }
            assertEquals("Vector ByteArray read requires an immediate exact case", error.message)
        }
    }
    @Test fun publicHostTupleResultsRejectAtLoadOrTrapBeforeArgumentNormalization() {
        val provenance = provenance()
        for (stage in provenance["stages"] as List<String>) for (backend in listOf("ast", "bytecode")) context(true).use { context ->
            val module = Json.parse(File(directory, "$stage-core/SimdInt32X4ByteArray.json").readText()) as Map<String, Any?>
            for (family in listOf("vector", "scalar")) for ((operation, arity) in listOf("Read" to 3, "Write" to 7)) {
                val name = "$family${operation}Worker"
                for (diagnostic in listOf(false, true)) {
                    val label = "$stage/$backend/$name/diagnostic=$diagnostic"
                    val request = Json.stringify(mapOf("entry" to name, "backend" to backend,
                        "diagnosticUnsupported" to diagnostic, "modules" to listOf(module)))
                    fun checkFault(error: PolyglotException) {
                        val message = error.message.orEmpty()
                        assertTrue(message.contains("unboxed-tuple (host result)"), "$label: $message")
                        assertEquals(diagnostic, message.contains("Diagnostic unsupported path reached:"), label)
                    }
                    if (!diagnostic) {
                        checkFault(assertThrows(PolyglotException::class.java, { context.eval("thc", request) }, label))
                    } else {
                        val entry = context.eval("thc", request)
                        assertTrue(entry.canExecute(), label)
                        // The deferred host-result fault must precede guest carrier checks,
                        // host argument normalization, and even the host arity check.
                        val arguments = listOf(Array<Any>(arity) { 0L },
                            Array<Any>(arity) { if (it == 0) "invalid host argument" else 0L }, emptyArray<Any>())
                        for (input in arguments)
                            checkFault(assertThrows(PolyglotException::class.java, { entry.execute(*input) }, label))
                    }
                }
            }
        }
    }
    private fun nativeCore(inlining: Boolean) {
        val provenance = provenance()
        val stages = provenance["stages"] as List<String>
        fun rows(filename: String): Map<Pair<String, List<Long>>, Long> {
            val lines = File(directory, filename).readLines()
            val result = lines.associate { row ->
                val parts = row.split('\t'); (parts.first() to parts.drop(1).dropLast(1).map(String::toLong)) to parts.last().toLong()
            }
            assertEquals(lines.size, result.size, "Duplicate corpus rows")
            return result
        }
        val expected = rows("expected.tsv")
        assertEquals(9666, expected.size)
        if (provenance["nativeRows"] != null) {
            assertEquals(expected, rows("oracle.tsv"))
            assertEquals(expected.size.toLong(), (provenance["nativeRows"] as Number).toLong())
        }
        val entries = provenance["entries"] as List<Map<String, Any?>>
        val declared = entries.flatMap { entry ->
            (entry["cases"] as List<List<Number>>).map { entry["name"] to it.map(Number::toLong) }
        }
        assertEquals(declared.size, declared.toSet().size)
        assertEquals(declared.toSet(), expected.keys)
        val counts = provenance["expectedGuestCallsByEntry"] as Map<String, Number>
        for (stage in stages) for (backend in listOf("ast", "bytecode")) withLanguage(inlining) { language ->
            val module = Json.parse(File(directory, "$stage-core/SimdInt32X4ByteArray.json").readText()) as Map<String, Any?>
            for (entry in entries) {
                val name = entry["name"] as String; val arity = (entry["arity"] as Number).toInt()
                val cases = (entry["cases"] as List<List<Number>>).map { row -> row.map(Number::toLong) }
                assertTrue(cases.isNotEmpty()); assertTrue(cases.all { it.size == arity })
                val linked = CoreModules.reachable(module, name) + ("instrument" to true)
                val p: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                val host = p.hostEntryTarget(arity); val closure = p.entryValue(name); val target = p.entryTarget(name)
                val callCount = counts.getValue(name).toLong()
                // The outer scalar root calls a runRW lambda, and worker wrappers
                // additionally call their one opaque worker. Counts come from Core.
                assertEquals(if (name in listOf("vectorUnitCase", "scalarUnitCase")) 2L else 3L, callCount)
                assertEquals(callCount, (provenance["checkedGuestCallsByStage"] as Map<String, Number>).getValue("$stage/$name").toLong())
                var targets = emptyList<RootCallTarget>()
                fun check(compiled: Boolean) {
                    for (input in cases) {
                        val label = "$stage/$backend/$name/$input/inlining=$inlining"
                        val before = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(expected.getValue(name to input), Calls.target(host, arrayOf(closure, input.toTypedArray())), label)
                        if (compiled) {
                            assertEquals(before + callCount, (p.diagnostics().getValue("compiledEntries") as Number).toLong(), "$label compiled guest entries")
                            val active = activeTargets(target)
                            assertEquals(targets.size, active.size, "$label active target count")
                            assertTrue(active.all { candidate -> targets.any { it === candidate } }, "$label active identities")
                            targets.forEach { valid(it, label) }
                            val calls = NodeUtil.findAllNodeInstances(host.rootNode, DirectCallNode::class.java).filter { it.callTarget === target }
                            assertTrue(calls.isNotEmpty(), "$label selected entry")
                            calls.forEach { assertSame(target, it.currentCallTarget, "$label selected identity") }
                        }
                        val state = language.handoffState.get()
                        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
                        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
                    }
                }
                check(false)
                targets = activeTargets(target)
                assertEquals(callCount.toInt(), targets.size, "$stage/$backend/$name guest roots")
                for (t in targets) {
                    t.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(t, true)
                    valid(t, "initial installation")
                }
                check(true)
                for (counter in listOf("unsupportedTraps", "blackholes"))
                    assertEquals(0L, (p.diagnostics().getValue(counter) as Number).toLong(), counter)
            }
        }
    }
}
