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
import thc.*
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class DoubleArrayNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val names = listOf("unboxedDoubleAccum", "unboxedDoubleST", "moveDoubleBits", "indexDoubleBits")
    private val operations = listOf(ByteArrayOp.READ_DOUBLE, ByteArrayOp.WRITE_DOUBLE, ByteArrayOp.INDEX_DOUBLE)
    private fun manifest() = Json.parse(File(root, "build/double-arrays/manifest.json").readText()) as Map<String, Any?>
    private fun merged(paths: List<String>) = CoreModules.merge(paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> })
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target, "initial installation")
    }
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val targets = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val root = target.rootNode
            // Bytecode DSL operation caches are not ordinary @Children fields.
            // Its public instruction API exposes the actual adopted cached nodes.
            val nodes = if (root is BytecodeRoot) listOf(root) + root.bytecodeNode.instructions
                .flatMap { it.arguments }.filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }
                .mapNotNull { it.asCachedNode() }
            else listOf(root)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            targets.add(target) // Install callees before their callers.
        }
        visit(entry)
        return targets
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
    }
    private fun model(name: String, seed: Long): Long {
        require(name in names)
        if (name == "moveDoubleBits" || name == "indexDoubleBits") {
            require(!signalingNaN(seed)) { "Signaling NaN movement is outside the evidence domain" }
            return seed
        }
        // Fixed-point quarters keep the public arithmetic independent of host FP.
        val x = (seed and 65535L) - 32768L
        val cells = LongArray(8) { x * 4 }
        if (name == "unboxedDoubleAccum") {
            for ((index, quarters) in listOf(0 to 13L, 3 to 22L, 0 to -8L, 7 to (2*x)))
                cells[index] += quarters
        } else {
            check(name == "unboxedDoubleST")
            val before = cells[0]
            cells[3] = before + 1
            val after = cells[3]
            cells[7] = 3*after - 4*x + 2
        }
        return cells[0]*7 + cells[3]*11 + cells[7]*13
    }
    private fun signalingNaN(bits: Long) = bits and 0x7ff0000000000000L == 0x7ff0000000000000L &&
        bits and 0x000fffffffffffffL != 0L && bits and 0x0008000000000000L == 0L
    private val magnitudes = listOf(0L, 1L, 2L, 3L, 0x000fffffffffffffL, 0x0010000000000000L,
        0x3fefffffffffffffL, 0x3ff0000000000000L, 0x3ff0000000000001L, 0x7fefffffffffffffL,
        0x7ff0000000000000L, 0x7ff8000000000000L, 0x7ff8000000001234L, 0x7fffffffffffffffL,
        0x5555555555555555L, 0x55aa55aa55aa55aaL, 0x0123456789abcdefL)
    private fun inputs(): List<Long> {
        val values = (-16L..16L).toMutableSet()
        values.addAll(listOf(Long.MIN_VALUE, Long.MIN_VALUE+1, Long.MAX_VALUE-1, Long.MAX_VALUE))
        for (bit in 0..63) for (delta in -1L..1L) for (sign in listOf(-1L, 1L))
            values.add(sign*((1L shl bit)+delta))
        for (bits in magnitudes) for (sign in listOf(0L, Long.MIN_VALUE)) values.add(bits or sign)
        for (bit in 0..50) for (sign in listOf(0L, Long.MIN_VALUE))
            values.add(0x7ff8000000000000L or (1L shl bit) or sign)
        return values.filterNot(::signalingNaN).sorted()
    }
    private fun expectedRows() = names.flatMap { name -> inputs().map { Triple(name, it, model(name, it)) } }
    private fun verifyRows(text: String): List<Triple<String, Long, Long>> {
        val lines = text.lineSequence().toList().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        val rows = lines.map { line ->
            val fields = line.split('\t')
            require(fields.size == 3) { "Double oracle requires name/input/result" }
            Triple(fields[0], fields[1].toLong(), fields[2].toLong())
        }
        require(rows == expectedRows()) { "Double oracle/model mismatch or incomplete, duplicate, reordered inputs" }
        return rows
    }
    @Test fun independentModelsAndBinary64DomainAreExact() {
        val values = inputs()
        assertEquals(506, values.size); assertEquals(values.distinct().sorted(), values)
        for (bits in magnitudes) for (sign in listOf(0L, Long.MIN_VALUE)) assertTrue((bits or sign) in values)
        for (bit in 0..63) for (delta in -1L..1L) for (sign in listOf(-1L, 1L)) {
            val bits = sign*((1L shl bit)+delta)
            if (!signalingNaN(bits)) assertTrue(bits in values)
        }
        for (bit in 0..50) for (sign in listOf(0L, Long.MIN_VALUE))
            assertTrue((0x7ff8000000000000L or (1L shl bit) or sign) in values)
        for (raw in values + listOf(0L, 65535L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val x = (raw and 65535)-32768
            assertEquals(150*x+277, model("unboxedDoubleAccum", raw))
            assertEquals(176*x+76, model("unboxedDoubleST", raw))
            for (name in names.take(2)) assertTrue(kotlin.math.abs(model(name, raw)) < (1L shl 24))
            assertFalse(signalingNaN(raw))
            for (name in names.drop(2)) assertEquals(raw, model(name, raw))
        }
        for (raw in listOf(0x7ff0000000000001L, 0x7ff7ffffffffffffL, 0xfff0000000001234UL.toLong())) {
            assertTrue(signalingNaN(raw))
            for (name in names.drop(2)) assertThrows(IllegalArgumentException::class.java) { model(name, raw) }
        }
        assertThrows(IllegalArgumentException::class.java) { model("unknown", 0) }
    }
    @Test fun independentOracleRejectsCorruptOrIncompleteRows() {
        val rows = expectedRows()
        fun text(values: List<Triple<String, Long, Long>>) = values.joinToString("\n", postfix="\n") { "${it.first}\t${it.second}\t${it.third}" }
        val valid = text(rows)
        assertEquals(2024, rows.size); assertEquals(rows, verifyRows(valid))
        val corrupt = listOf("", text(rows.drop(1)), text(rows+rows.first()), text(rows.asReversed()),
            text(rows.toMutableList().apply { this[0] = this[1] }),
            text(rows.toMutableList().apply { this[0] = this[0].copy(third=this[0].third+1) }),
            text(rows.toMutableList().apply { this[0] = this[0].copy(first="unknown") }),
            valid.replaceFirst("\t", " "), valid.replaceFirst("\t", "\textra\t"), valid+"\n",
            "moveDoubleBits\t9223372036854775808\t0\n", "moveDoubleBits\tnan\t0\n")
        for ((index, bad) in corrupt.withIndex()) assertThrows(IllegalArgumentException::class.java, { verifyRows(bad) }, "mutation $index")
    }
    @Test fun nativePublicArraysAndBitMovementWithInlining() = native(true)
    @Test fun nativePublicArraysAndBitMovementAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        val manifest = manifest()
        assertEquals(names, manifest["entries"])
        assertEquals(inputs(), manifest["inputs"])
        assertEquals(if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) "little" else "big", manifest["byteOrder"])
        assertEquals(64, (manifest["wordBits"] as Number).toInt())
        assertEquals(true, manifest["signalingNaNsExcluded"])
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale Double-array fixture: $path; rerun prepare-double-arrays.py")
        }
        val rows = verifyRows(File(root, "build/double-arrays/oracle.tsv").readText()).groupBy { it.first }
        assertEquals(names.toSet(), rows.keys)
        assertEquals(2024L, manifest["nativeRows"])
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.values.sumOf { it.size })
        val expectedCalls = mapOf("unboxedDoubleAccum" to 2L, "unboxedDoubleST" to 2L, "moveDoubleBits" to 3L, "indexDoubleBits" to 3L)
        assertEquals(expectedCalls, (manifest["expectedGuestCallsByEntry"] as Map<String, Number>).mapValues { it.value.toLong() })
        val stages = manifest["stages"] as Map<String, List<String>>
        assertEquals(setOf("pre", "post"), stages.keys)
        for ((stage, paths) in stages) {
            val module = merged(paths)
            for (name in names) {
                val cases = rows.getValue(name).map { it.second to it.third }
                assertEquals(cases.size, cases.map { it.first }.toSet().size)
                assertEquals(inputs(), cases.map { it.first })
                for ((input, native) in cases) {
                    assertEquals(model(name, input), native, "Native $name($input)")
                    val exponent = input and 0x7ff0000000000000L
                    val fraction = input and 0x000fffffffffffffL
                    assertFalse(exponent == 0x7ff0000000000000L && fraction != 0L && (input and 0x0008000000000000L) == 0L)
                }
                for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val linked = CoreModules.reachable(module, name)
                        val bindings = linked["bindings"] as List<Map<String, Any?>>
                        val program = program(language, linked + ("instrument" to true), backend)
                        val entry = program.entryTarget(bindings.single { it["name"] == name }["id"] as String)
                        fun lambdaLabel(expression: List<*>): String {
                            assertEquals("lam", expression[0])
                            val formals = expression[1] as List<Map<String, Any?>>
                            return "lambda ${formals.joinToString { it["name"].toString() }}"
                        }
                        val rootExpression = bindings.single { it["name"] == name }["expr"] as List<*>
                        val stateCall = rootExpression[2] as List<*>
                        val expectedLabels = bindings.map { lambdaLabel(it["expr"] as List<*>) }.toSet() +
                            lambdaLabel(stateCall[1] as List<*>)
                        var targets = emptyList<RootCallTarget>()
                        fun count() = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        fun check(compiled: Boolean) {
                            for ((input, native) in cases) {
                                val label = "$stage/$backend/$name/$input/inlining=$inlining"
                                val before = count()
                                assertEquals(native, Calls.target(entry, arrayOf(0L, input)), label)
                                if (compiled) {
                                    assertEquals(expectedCalls.getValue(name), count()-before, "$label exact compiled entries")
                                    val active = activeTargets(entry)
                                    assertEquals(targets.size, active.size, "$label active target count")
                                    assertTrue(active.all { target -> targets.any { it === target } }, "$label active target identities")
                                    targets.forEach { valid(it, label) }
                                }
                                released(language)
                            }
                        }
                        check(false)
                        targets = activeTargets(entry)
                        assertEquals(expectedCalls.getValue(name).toInt(), targets.size, "$stage/$backend/$name active guest roots")
                        assertEquals(expectedLabels, targets.map { it.rootNode.name }.toSet(), "$stage/$backend/$name guest root labels")
                        targets.forEach(::compile)
                        val allocations = language.handoffState.get().results.allocations
                        check(true)
                        assertEquals(allocations, language.handoffState.get().results.allocations, "Pooled results reused")
                        for (counter in listOf("unsupportedTraps", "blackholes"))
                            assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong(), counter)
                    } finally { context.leave() }
                }
            }
        }
    }

    private fun applications(value: Any?): List<MutableList<Any?>> = when (value) {
        is List<*> -> (if (value.firstOrNull() == "app") listOf(value as MutableList<Any?>) else emptyList()) + value.flatMap(::applications)
        is Map<*, *> -> value.values.flatMap(::applications)
        else -> emptyList()
    }
    private fun paths() = (manifest()["stages"] as Map<String, List<String>>).getValue("pre")
    private fun owner(operation: ByteArrayOp) = if (operation == ByteArrayOp.INDEX_DOUBLE) "indexDoubleBits" else "moveDoubleBits"

    @Test fun exactDoubleStateShapesAndSaturationAreRequiredInBothLoadModes() {
        val paths = paths()
        for (backend in listOf("ast", "bytecode")) context(true).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (operation in operations) for (mutation in 0..9) for (diagnostic in listOf(false, true)) {
                    val module = CoreModules.reachable(merged(paths), owner(operation))
                    val app = applications(module).first { (it[1] as List<*>).take(2) == listOf("prim", operation.primitive) }
                    val args = app[2] as MutableList<Any?>
                    val flags = app[3] as MutableList<Any?>
                    val metadata = CoreRepresentations.metadata(app) as MutableMap<String, Any?>
                    fun wrong(kind: String, rep: String) = mapOf("kind" to kind, "primReps" to listOf(rep), "evaluated" to true)
                    when (mutation) {
                        0 -> { args.removeAt(args.lastIndex); flags.removeAt(flags.lastIndex); metadata.remove("callDemand") }
                        1 -> { args.add(args[0]); flags.add(false); metadata.remove("callDemand") }
                        2 -> metadata.remove("rep")
                        3 -> metadata["rep"] = wrong("float", "FloatRep")
                        4 -> flags[0] = true
                        5 -> (CoreRepresentations.metadata(args[0] as List<Any?>)!!["rep"] as MutableMap<String, Any?>)["kind"] = "unknown"
                        6 -> (CoreRepresentations.metadata(args[1] as List<Any?>)!!["rep"] as MutableMap<String, Any?>)["primReps"] = listOf("Int64Rep")
                        7 -> if (operation == ByteArrayOp.WRITE_DOUBLE) {
                            (CoreRepresentations.metadata(args[2] as List<Any?>)!! as MutableMap<String, Any?>)["rep"] = wrong("long", "IntRep")
                        } else metadata["rep"] = wrong("long", "IntRep")
                        8 -> if (operation == ByteArrayOp.READ_DOUBLE) {
                            val proof = metadata["rep"] as MutableMap<String, Any?>
                            (proof["components"] as MutableList<Any?>)[0] = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
                                "components" to emptyList<Any?>(), "primReps" to emptyList<String>(), "evaluated" to true)
                        } else metadata["rep"] = mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "evaluated" to true)
                        9 -> if (operation == ByteArrayOp.READ_DOUBLE) {
                            val proof = metadata["rep"] as MutableMap<String, Any?>
                            (proof["components"] as MutableList<Any?>)[1] = wrong("float", "FloatRep")
                            proof["primReps"] = listOf("FloatRep")
                        } else flags[1] = true
                    }
                    assertThrows(RuntimeFault::class.java, {
                        program(language, module + ("diagnosticUnsupported" to diagnostic), backend)
                    }, "$backend/$operation/mutation$mutation/$diagnostic")
                }
                for (operation in operations) {
                    val module = CoreModules.reachable(merged(paths), owner(operation))
                    val app = applications(module).first { (it[1] as List<*>).take(2) == listOf("prim", operation.primitive) }
                    val primitive = (app[1] as List<*>).toList(); app.clear(); app.addAll(primitive)
                    assertThrows(UnsupportedCore::class.java) { program(language, module, backend) }
                }
            } finally { context.leave() }
        }
    }

    @Test fun invalidElementOffsetsAreGuardedOnBothBackendsWithoutNativeUndefinedAccesses() {
        val paths = paths()
        for (backend in listOf("ast", "bytecode")) context(true).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (operation in operations) for (index in listOf(Long.MIN_VALUE, -1L, 1L, 1L shl 32, 1L shl 61, Long.MAX_VALUE)) {
                    val name = owner(operation)
                    val module = CoreModules.reachable(merged(paths), name)
                    val app = applications(module).first { (it[1] as List<*>).take(2) == listOf("prim", operation.primitive) }
                    val args = app[2] as MutableList<Any?>
                    args[1] = listOf("lit", "int", index.toString(), CoreRepresentations.metadata(args[1] as List<Any?>))
                    val program = program(language, module, backend)
                    val function = context.asValue(EntryValue(program, name, 1))
                    val failure = assertThrows(PolyglotException::class.java) { function.execute(5L) }
                    assertTrue(failure.message.orEmpty().contains("ByteArray# Double index"), "$backend/$operation/$index: $failure")
                    released(language)
                    assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                }
            } finally { context.leave() }
        }
    }
}
