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
class SimdFloatByteArrayTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/simd-floatx4-bytearray")
    private fun integer(value: Any?, label: String): Long {
        assertTrue(value is Int || value is Long, "$label must have an integral JSON carrier: $value")
        return (value as Number).toLong()
    }
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
        assertTrue(provenance.keys.containsAll(fields), "Missing explicit FloatX4 preparation mode fields")
        fun rows(field: String) {
            assertEquals(6720L, integer(provenance[field], field), "$field must be exactly 6720")
        }
        rows("modelRows")
        assertEquals("little", provenance["modelByteOrder"], "FloatX4 model byte order")
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
        assertEquals("floatx4-bytearray", provenance["vector"], "Exact Float memory provenance")
        assertEquals(ByteOrder.LITTLE_ENDIAN, ByteOrder.nativeOrder(), "FloatX4 bounded corpus requires a little-endian host")
        assertEquals(true, provenance["positiveAuditsAccepted"])
        for (file in (provenance["sources"] as List<Map<String, String>>) + (provenance["artifacts"] as List<Map<String, String>>)) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, file.getValue("path")).readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(file["sha256"], hash, "Stale FloatX4 memory source/artifact: ${file["path"]}")
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
        val native = mapOf<String, Any?>("stages" to listOf("pre", "post"), "modelRows" to 6720L,
            "modelByteOrder" to "little", "nativeRows" to 6720L, "nativeByteOrder" to "little", "modelMatched" to true)
        val exported = native + mapOf("stages" to listOf("pre"), "nativeRows" to null,
            "nativeByteOrder" to null, "modelMatched" to null)
        val nativeCorruptions = listOf(
            "stages" to emptyList<String>(), "stages" to listOf("pre"), "stages" to listOf("post", "pre"),
            "stages" to listOf("pre", "post", "post"), "nativeRows" to null, "nativeRows" to 6719L,
            "nativeRows" to 6720.0, "nativeRows" to 6720.5, "nativeRows" to true,
            "nativeRows" to "6720", "nativeByteOrder" to null,
            "nativeByteOrder" to "big", "modelMatched" to null, "modelMatched" to false)
        val exportCorruptions = listOf("stages" to emptyList<String>(), "stages" to listOf("pre", "post"),
            "stages" to listOf("pre", "pre"), "nativeRows" to 0L, "nativeRows" to 6720L,
            "nativeByteOrder" to "little", "modelMatched" to true, "modelMatched" to false)
        val commonCorruptions = listOf("modelRows" to 6719L, "modelRows" to 6720.0, "modelRows" to 6720.5, "modelRows" to true,
            "modelRows" to "6720", "modelByteOrder" to "big")
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
    @Test fun genuineExportedVectorBoundariesRemainRejected() {
        val provenance = provenance()
        for (stage in provenance["stages"] as List<String>) for (backend in listOf("ast", "bytecode")) withLanguage(true) { language ->
            val module = Json.parse(File(directory, "$stage-core/SimdFloatX4ByteArray.json").readText()) as Map<String, Any?>
            for (name in listOf("vectorArgument", "readTupleEscape", "readVectorEscape")) {
                val linked = CoreModules.reachable(module, name)
                assertThrows(RuntimeFault::class.java, {
                    if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                }, "$stage/$backend/$name")
            }
        }
    }
    @Test fun publicHostTupleResultsRejectAtLoadOrTrapBeforeArgumentNormalization() {
        val provenance = provenance()
        for (stage in provenance["stages"] as List<String>) for (backend in listOf("ast", "bytecode")) context(true).use { context ->
            val module = Json.parse(File(directory, "$stage-core/SimdFloatX4ByteArray.json").readText()) as Map<String, Any?>
            for (family in listOf("vector", "scalar")) for ((operation, arity) in listOf("Read" to 4, "Write" to 7)) {
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
    private val rawBits = listOf(0L, 0x80000000L, 1L, 0x80000001L, 0x007fffffL, 0x807fffffL,
        0x00800000L, 0x80800000L, 0x3f800000L, 0xbf800000L, 0x7f7fffffL, 0xff7fffffL,
        0x7f800000L, 0xff800000L, 0x7fc12345L, 0xffc54321L)
    private val finiteEdges = listOf(-65536L, -257L, -1L, 0L, 1L, 255L, 256L, 65535L)
    private val operations = listOf("Unit", "Index", "Read", "Write", "GraphIndex", "GraphStore")
    private fun operation(name: String): String = operations.single { name == "vector${it}Case" || name == "scalar${it}Case" }
    /** Independent exact input inventory; native-only sNaNs cannot enter this set. */
    private fun expectedCases(name: String): Set<List<Long>> {
        val op = operation(name)
        val offsetCount = if (name.startsWith("vector")) 4 else 13
        val patterns = if (op.startsWith("Graph")) {
            (0..3).flatMap { lane -> finiteEdges.map { value ->
                mutableListOf(-31L, 127L, -1024L, 4096L).also { it[lane] = value }
            } }
        } else rawBits.indices.map { index -> (0..3).map { lane -> rawBits[(index + 5 * lane) % rawBits.size] } }
        val inputs = patterns.mapIndexed { index, lanes -> listOf((index % offsetCount).toLong()) + lanes }
        val selectors = when (op) { "Unit" -> 8; "Index", "Read" -> 4; "Write", "GraphStore" -> 64; else -> 0 }
        return (if (selectors == 0) inputs else inputs.flatMap { row -> (0 until selectors).map { row + it.toLong() } }).toSet()
    }
    /** Explicit little-endian byte movement, no model/runtime carrier import. */
    private fun independentExpected(name: String, input: List<Long>): Long {
        val op = operation(name)
        val graph = op.startsWith("Graph")
        val address = (input[0] * if (name.startsWith("vector")) 16 else 4).toInt()
        val bytes = ByteArray(64)
        fun put(at: Int, value: Long) {
            for (byte in 0..3) bytes[at + byte] = (value ushr (8 * byte)).toByte()
        }
        fun word(at: Int): Long = (0..3).fold(0L) { bits, byte ->
            bits or ((bytes[at + byte].toLong() and 255L) shl (8 * byte))
        }
        for (index in 0..15) put(4 * index, 0x89abcdefL + index * 0x01030507L)
        val values = input.subList(1, 5)
        val bits = values.map { if (graph) java.lang.Float.floatToRawIntBits(it.toFloat()).toLong() and 0xffffffffL else it }
        for (lane in 0..3) put(address + 4 * lane, bits[lane])
        if (op == "Unit") {
            val selector = input[5].toInt()
            val before = word(address + 4 * (selector % 4))
            return before xor if (selector == 5) 0x80000000L else 0L
        }
        if (op == "Index" || op == "Read") return word(address + 4 * input[5].toInt())
        val weights = longArrayOf(3, 5, 7, 11)
        val score = values.indices.sumOf { values[it] * weights[it] }
        if (graph) {
            assertTrue(values.indices.sumOf { kotlin.math.abs(values[it] * weights[it]) } < (1L shl 24))
            var fp = values[0].toFloat() * weights[0].toFloat()
            for (lane in 1..3) fp += values[lane].toFloat() * weights[lane].toFloat()
            assertEquals(score, fp.toLong(), "Finite checksum Float32 exactness")
        }
        return if (op == "GraphIndex") score else score * 257 + (bytes[input[5].toInt()].toLong() and 255L)
    }
    private fun nativeCore(inlining: Boolean) {
        val provenance = provenance()
        val stages = provenance["stages"] as List<String>
        fun rows(filename: String): Map<Pair<String, List<Long>>, Long> {
            val lines = File(directory, filename).readLines()
            val result = lines.associate { row ->
                val parts = row.split('\t')
                val values = parts.drop(1).map { part -> part.toLong().also { assertEquals(part, it.toString(), "Canonical oracle integer") } }
                (parts.first() to values.dropLast(1)) to values.last()
            }
            assertEquals(lines.size, result.size, "Duplicate corpus rows")
            return result
        }
        val expected = rows("expected.tsv")
        assertEquals(6720, expected.size)
        for ((key, value) in expected) assertEquals(independentExpected(key.first, key.second), value, "Independent bytes: $key")
        if (provenance["nativeRows"] != null) {
            assertEquals(expected, rows("oracle.tsv"))
            assertEquals(expected.size.toLong(), integer(provenance["nativeRows"], "nativeRows"))
        }
        val entries = provenance["entries"] as List<Map<String, Any?>>
        val names = listOf("vector", "scalar").flatMap { family ->
            operations.map { "$family${it}Case" }
        }.toSet()
        assertEquals(12, entries.size); assertEquals(names, entries.map { it["name"] }.toSet())
        val declared = entries.flatMap { entry ->
            (entry["cases"] as List<List<Any?>>).map { row -> entry["name"] to row.map { integer(it, "declared input") } }
        }
        assertEquals(declared.size, declared.toSet().size)
        assertEquals(declared.toSet(), expected.keys)
        val counts = provenance["expectedGuestCallsByEntry"] as Map<String, Any?>
        for (stage in stages) for (backend in listOf("ast", "bytecode")) withLanguage(inlining) { language ->
            val module = Json.parse(File(directory, "$stage-core/SimdFloatX4ByteArray.json").readText()) as Map<String, Any?>
            for (entry in entries) {
                val name = entry["name"] as String
                val rawArity = integer(entry["arity"], "$name arity")
                assertTrue(rawArity in 1L..7L); val arity = rawArity.toInt()
                val cases = (entry["cases"] as List<List<Any?>>).map { row -> row.map { integer(it, "$name input") } }
                assertTrue(cases.isNotEmpty()); assertTrue(cases.all { it.size == arity })
                val offsets = if (name.startsWith("vector")) 0L..3L else 0L..12L
                val op = operation(name)
                assertEquals(if (op == "GraphIndex") 5 else 6, arity, "$name exact arity")
                val wanted = expectedCases(name)
                assertEquals(wanted.size, cases.size, "$name exact rows")
                assertEquals(wanted, cases.toSet(), "$name exact portable domain (sNaNs excluded)")
                assertEquals(offsets.toSet(), cases.map { it[0] }.toSet(), "$name safe offsets")
                val linked = CoreModules.reachable(module, name) + ("instrument" to true)
                val p: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                val host = p.hostEntryTarget(arity); val closure = p.entryValue(name); val target = p.entryTarget(name)
                val callCount = integer(counts.getValue(name), "$name guest count")
                // Counts are structural Core evidence, not guessed from global bindings:
                // the raw index worker additionally has its own retained runRW lambda.
                assertEquals(when (op) { "Unit" -> 2L; "Index" -> 4L; else -> 3L }, callCount)
                assertEquals(callCount, integer((provenance["checkedGuestCallsByStage"] as Map<String, Any?>).getValue("$stage/$name"), "$stage/$name checked count"))
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
