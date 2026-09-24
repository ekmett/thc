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
import java.lang.reflect.Method
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class FloatWordArrayNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val names = listOf("unboxedFloatAccum", "unboxedFloatST", "unboxedWordAccum", "unboxedWordST",
        "moveFloatBits", "indexFloatBits", "aliasWordBytes")
    private val operations = listOf(ByteArrayOp.READ_FLOAT, ByteArrayOp.WRITE_FLOAT, ByteArrayOp.INDEX_FLOAT,
        ByteArrayOp.READ_WORD, ByteArrayOp.WRITE_WORD, ByteArrayOp.INDEX_WORD)
    private fun manifest() = Json.parse(File(root, "build/float-word-arrays/manifest.json").readText()) as Map<String, Any?>
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
    private data class BoundaryProbe(val method: Any, val hasCompiledCode: Method)
    private data class SnapshotProbes(val callCount: Result<Method>, val boundary: Result<BoundaryProbe>)
    private fun snapshotProbes(entry: RootCallTarget): SnapshotProbes = SnapshotProbes(
        runCatching { entry.javaClass.getMethod("getCallCount") },
        runCatching {
            val jvmci = Class.forName("jdk.vm.ci.runtime.JVMCI").getMethod("getRuntime").invoke(null)
            val backend = Class.forName("jdk.vm.ci.runtime.JVMCIRuntime")
                .getMethod("getHostJVMCIBackend").invoke(jvmci)
            val metaAccess = Class.forName("jdk.vm.ci.runtime.JVMCIBackend")
                .getMethod("getMetaAccess").invoke(backend)
            val method = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                .getDeclaredMethod("callBoundary", Array<Any?>::class.java)
            val boundary = Class.forName("jdk.vm.ci.meta.MetaAccessProvider")
                .getMethod("lookupJavaMethod", java.lang.reflect.Executable::class.java).invoke(metaAccess, method)
            BoundaryProbe(boundary, Class.forName("jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod")
                .getMethod("hasCompiledCode"))
        })
    private data class TargetState(val target: RootCallTarget, val lastTierValid: Boolean, val callCount: Result<Int>)
    private data class CallState(val entry: TargetState, val active: List<TargetState>)
    private data class CallSnapshot(val targets: Result<CallState>, val boundaryHasCompiledCode: Result<Boolean>)
    private fun callState(entry: RootCallTarget, probes: SnapshotProbes): CallSnapshot {
        val targets = runCatching {
            fun state(target: RootCallTarget) = TargetState(target,
                target.javaClass.getMethod("isValidLastTier").invoke(target) as Boolean,
                probes.callCount.mapCatching { it.invoke(target) as Int })
            CallState(state(entry), activeTargets(entry).map(::state))
        }
        // The shared stub and guest code have separate lifetimes. An unavailable
        // JVMCI observation must not discard the independently captured targets.
        val boundary = probes.boundary.mapCatching { it.hasCompiledCode.invoke(it.method) as Boolean }
        return CallSnapshot(targets, boundary)
    }
    private fun unavailable(error: Throwable) =
        "unavailable(${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)})"
    private fun describe(state: CallSnapshot): String = state.targets.fold({ call ->
        fun target(value: TargetState) = "${value.target.rootNode.name.take(80)}@${System.identityHashCode(value.target).toString(16)}" +
            "(lastTierValid=${value.lastTierValid}, callCount=${value.callCount.fold({ it.toString() }, ::unavailable)})"
        "entry=${target(call.entry)} activeCount=${call.active.size} active=[" +
            call.active.take(8).joinToString { target(it) } +
            (if (call.active.size > 8) ", ..." else "") + "]"
    }, ::unavailable) + " boundaryHasCompiledCode=" +
        state.boundaryHasCompiledCode.fold({ it.toString() }, ::unavailable)
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
    }
    private fun model(name: String, seed: Long, byteOrder: ByteOrder = ByteOrder.nativeOrder()): Long {
        require(name in names)
        if (name == "moveFloatBits" || name == "indexFloatBits") {
            require(!signalingNaN(seed)) { "Signaling NaN movement is outside the evidence domain" }
            return seed and 0xffff_ffffL
        }
        if (name.startsWith("unboxedFloat")) {
            // Integer fixed-point quarters: no host floating arithmetic in the model.
            val x = (seed and 65535L) - 32768L
            val cells = LongArray(8) { x*4 }
            if (name == "unboxedFloatAccum") {
                for ((index, quarters) in listOf(0 to 13L, 3 to 22L, 0 to -8L, 7 to (2*x)))
                    cells[index] += quarters
            } else {
                check(name == "unboxedFloatST")
                val before = cells[0]
                cells[3] = before+1
                cells[7] = 3*cells[3]-4*x+2
            }
            return cells[0]*7 + cells[3]*11 + cells[7]*13
        }
        // BigInteger modulo arithmetic independently models full-width Word cells.
        val modulus = java.math.BigInteger.ONE.shiftLeft(64)
        fun word(value: Long) = java.math.BigInteger.valueOf(value).mod(modulus)
        if (name == "unboxedWordAccum" || name == "unboxedWordST") {
            val cells = Array(8) { word(seed) }
            if (name == "unboxedWordAccum") {
                for ((index, delta) in listOf(0 to word(3), 3 to word(5), 0 to word(-2), 7 to word(seed)))
                    cells[index] = (cells[index]+delta).mod(modulus)
            } else {
                cells[3] = (cells[0]+word(7)).mod(modulus)
                cells[7] = (cells[3]*word(3)-word(seed)).mod(modulus)
            }
            return (cells[0]*word(7)+cells[3]*word(11)+cells[7]*word(13)).mod(modulus).toLong()
        }
        check(name == "aliasWordBytes")
        val bytes = LongArray(16) { offset ->
            val value = if (offset < 8) seed else seed xor 0x55aa55aa55aa55aaL
            val position = offset % 8
            val shift = (if (byteOrder == ByteOrder.LITTLE_ENDIAN) position else 7-position)*8
            (value ushr shift) and 255
        }
        bytes[7] = (seed+101) and 255
        bytes[8] = (seed+37) and 255
        fun element(offset: Int): java.math.BigInteger {
            var value = java.math.BigInteger.ZERO
            for (byte in 0..7) {
                val shift = (if (byteOrder == ByteOrder.LITTLE_ENDIAN) byte else 7-byte)*8
                value = value.or(java.math.BigInteger.valueOf(bytes[offset+byte]).shiftLeft(shift))
            }
            return value
        }
        return (3.toBigInteger()*word(seed) + 16.toBigInteger()*element(0) + 20.toBigInteger()*element(8) +
            17.toBigInteger()*word(bytes[0]) + 19.toBigInteger()*word(bytes[7]) +
            23.toBigInteger()*word(bytes[8]) + 29.toBigInteger()*word(bytes[15])).mod(modulus).toLong()
    }
    private fun signalingNaN(bits: Long) = bits and 0x7f800000L == 0x7f800000L &&
        bits and 0x007fffffL != 0L && bits and 0x00400000L == 0L
    private fun wordInputs(): List<Long> {
        val values = (-16L..16L).toMutableSet()
        values.addAll(listOf(Long.MIN_VALUE, Long.MIN_VALUE+1, Long.MAX_VALUE-1, Long.MAX_VALUE))
        for (bit in 0..63) for (delta in -1L..1L) for (sign in listOf(-1L, 1L))
            values.add(sign*((1L shl bit)+delta))
        values.addAll(listOf(0x5555555555555555UL, 0xaaaaaaaaaaaaaaaaUL, 0x55aa55aa55aa55aaUL,
            0xaa55aa55aa55aa55UL, 0x0123456789abcdefUL, 0xfedcba9876543210UL, 0x8000000080000000UL,
            0xffffffff00000000UL, 0x800000007fffffffUL, 0x7fffffff80000000UL, 0xffffffff7fffffffUL,
            0x0000000100000001UL, 0x12345678abcdef01UL).map { it.toLong() })
        return values.sorted()
    }
    private val magnitudes = listOf(0L, 1L, 2L, 3L, 0x007fffffL, 0x00800000L, 0x3f7fffffL,
        0x3f800000L, 0x3f800001L, 0x7f7fffffL, 0x7f800000L, 0x7fc00000L, 0x7fc01234L, 0x7fffffffL)
    private fun movementInputs(): List<Long> {
        val values = wordInputs().toMutableSet()
        val bits = magnitudes.flatMap { bits -> listOf(bits, bits or 0x80000000L) }.toMutableSet()
        for (bit in 0..21) for (sign in listOf(0L, 0x80000000L)) bits.add(0x7fc00000L or (1L shl bit) or sign)
        for (value in bits) for (upper in listOf(0L, 0x1234567800000000L, -0x100000000L)) values.add(value or upper)
        return values.filterNot(::signalingNaN).sorted()
    }
    private fun inputsByEntry() = names.associateWith {
        if (it in listOf("moveFloatBits", "indexFloatBits")) movementInputs() else wordInputs()
    }
    private fun expectedRows() = inputsByEntry().flatMap { (name, values) -> values.map { Triple(name, it, model(name, it)) } }
    private fun verifyRows(text: String): List<Triple<String, Long, Long>> {
        val lines = text.lineSequence().toList().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        val rows = lines.map { line ->
            val fields = line.split('\t')
            require(fields.size == 3) { "Float/Word oracle requires name/input/result" }
            Triple(fields[0], fields[1].toLong(), fields[2].toLong())
        }
        require(rows == expectedRows()) { "Float/Word oracle/model mismatch or incomplete, duplicate, reordered inputs" }
        return rows
    }
    @Test fun independentDomainsPreserveFloatBitsAndAllMachineWords() {
        val words = wordInputs(); val movement = movementInputs()
        assertEquals(397, words.size); assertEquals(590, movement.size)
        assertEquals(words.distinct().sorted(), words); assertEquals(movement.distinct().sorted(), movement)
        for (bit in 0..63) for (delta in -1L..1L) for (sign in listOf(-1L, 1L))
            assertTrue(sign*((1L shl bit)+delta) in words)
        assertTrue(words.any(::signalingNaN))
        for (bits in magnitudes) for (sign in listOf(0L, 0x80000000L))
            for (upper in listOf(0L, 0x1234567800000000L, -0x100000000L)) assertTrue((bits or sign or upper) in movement)
        for (bit in 0..21) for (sign in listOf(0L, 0x80000000L))
            assertTrue((0x7fc00000L or (1L shl bit) or sign) in movement)
        for (raw in movement) {
            assertFalse(signalingNaN(raw))
            for (name in listOf("moveFloatBits", "indexFloatBits")) assertEquals(raw and 0xffffffffL, model(name, raw))
        }
        for (raw in listOf(0x7f800001L, 0x7fbfffffL, 0xff800123L)) {
            assertTrue(signalingNaN(raw))
            for (name in listOf("moveFloatBits", "indexFloatBits")) assertThrows(IllegalArgumentException::class.java) { model(name, raw) }
            // Integer storage and bounded public arithmetic do not exclude these words.
            for (name in names.filterNot { it in listOf("moveFloatBits", "indexFloatBits") }) model(name, raw)
        }
        assertThrows(IllegalArgumentException::class.java) { model("unknown", 0) }
    }
    @Test fun independentPublicArithmeticHasExactBinary32Intermediates() {
        // All quantities are quarter-integers. Reduce only the denominator's two
        // powers of two, then bound the numerator to binary32's 24-bit precision.
        fun exact(quarters: Long): Long {
            var numerator = kotlin.math.abs(quarters)
            repeat(2) { if (numerator % 2 == 0L) numerator /= 2 }
            assertTrue(numerator < (1L shl 24), "Non-exact binary32 quarter-integer $quarters")
            return quarters
        }
        for (raw in wordInputs() + listOf(0L, 65535L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val x = (raw and 65535)-32768
            val q = exact(x*4)
            val accum = listOf(exact(exact(q+13)-8), exact(q+22), exact(q+exact(q/2)))
            val after = exact(q+1)
            val st = listOf(q, after, exact(exact(exact(3*after)-q)+2))
            for ((name, cells) in listOf("unboxedFloatAccum" to accum, "unboxedFloatST" to st)) {
                val weighted = cells.zip(listOf(7, 11, 13)).map { (cell, weight) -> exact(cell*weight) }
                val answer = exact(4*exact(exact(weighted[0]+weighted[1])+weighted[2]))
                assertEquals(0L, answer%4); assertEquals(answer/4, model(name, raw))
            }
            assertEquals(150*x+277, model("unboxedFloatAccum", raw))
            assertEquals(176*x+76, model("unboxedFloatST", raw))
            assertEquals(44*raw+62, model("unboxedWordAccum", raw))
            assertEquals(44*raw+350, model("unboxedWordST", raw))
        }
    }
    @Test fun independentWordAliasingChecksBothByteOrders() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) for (raw in wordInputs()) {
            fun shift(index: Int) = 8*(if (order == ByteOrder.LITTLE_ENDIAN) index else 7-index)
            fun byte(value: Long, index: Int) = (value ushr shift(index)) and 255
            val first = (raw and (255L shl shift(7)).inv()) or (((raw+101) and 255) shl shift(7))
            val second = ((raw xor 0x55aa55aa55aa55aaL) and (255L shl shift(0)).inv()) or (((raw+37) and 255) shl shift(0))
            val expected = 3*raw+16*first+20*second+17*byte(first, 0)+19*byte(first, 7)+23*byte(second, 0)+29*byte(second, 7)
            assertEquals(expected, model("aliasWordBytes", raw, order))
        }
        assertNotEquals(model("aliasWordBytes", 0, ByteOrder.LITTLE_ENDIAN), model("aliasWordBytes", 0, ByteOrder.BIG_ENDIAN))
    }
    @Test fun independentOracleRejectsCorruptOrIncompleteRows() {
        val rows = expectedRows()
        fun text(values: List<Triple<String, Long, Long>>) = values.joinToString("\n", postfix="\n") { "${it.first}\t${it.second}\t${it.third}" }
        val valid = text(rows)
        assertEquals(3165, rows.size); assertEquals(rows, verifyRows(valid))
        val corrupt = listOf("", text(rows.drop(1)), text(rows+rows.first()), text(rows.asReversed()),
            text(rows.toMutableList().apply { this[0] = this[1] }),
            text(rows.toMutableList().apply { this[0] = this[0].copy(third=this[0].third+1) }),
            text(rows.toMutableList().apply { this[0] = this[0].copy(first="unknown") }),
            valid.replaceFirst("\t", " "), valid.replaceFirst("\t", "\textra\t"), valid+"\n",
            "moveFloatBits\t9223372036854775808\t0\n", "moveFloatBits\t0\tnan\n")
        for ((index, bad) in corrupt.withIndex()) assertThrows(IllegalArgumentException::class.java, { verifyRows(bad) }, "mutation $index")
    }
    @Test fun nativePublicArraysAndFloatMovementWithInlining() = native(true)
    @Test fun nativePublicArraysAndFloatMovementAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        val manifest = manifest()
        assertEquals(names, manifest["entries"])
        assertEquals(if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) "little" else "big", manifest["byteOrder"])
        assertEquals(64, (manifest["wordBits"] as Number).toInt())
        assertEquals(true, manifest["signalingNaNsExcluded"])
        assertEquals(setOf("moveFloatBits", "indexFloatBits"), (manifest["signalingNaNExclusionScope"] as List<String>).toSet())
        assertEquals(names.toSet(), (manifest["inputsByEntry"] as Map<String, *>).keys)
        assertEquals(inputsByEntry(), manifest["inputsByEntry"])
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale Float/Word-array fixture: $path; rerun prepare-float-word-arrays.py")
        }
        val rows = verifyRows(File(root, "build/float-word-arrays/oracle.tsv").readText()).groupBy { it.first }
        assertEquals(names.toSet(), rows.keys)
        assertEquals(3165L, manifest["nativeRows"])
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.values.sumOf { it.size })
        val expectedCalls = names.associateWith { if (it in listOf("moveFloatBits", "indexFloatBits")) 3L else 2L }
        assertEquals(expectedCalls, (manifest["expectedGuestCallsByEntry"] as Map<String, Number>).mapValues { it.value.toLong() })
        val stages = manifest["stages"] as Map<String, List<String>>
        assertEquals(setOf("pre", "post"), stages.keys)
        for ((stage, paths) in stages) {
            val module = merged(paths)
            for (name in names) {
                val cases = rows.getValue(name).map { it.second to it.third }
                val movement = name in listOf("moveFloatBits", "indexFloatBits")
                assertEquals(if (movement) 590 else 397, cases.size, "$name pinned input domain")
                assertEquals(cases.size, cases.map { it.first }.toSet().size)
                assertEquals(inputsByEntry().getValue(name), cases.map { it.first })
                for ((input, native) in cases) {
                    assertEquals(model(name, input), native, "Native $name($input)")
                    if (movement) {
                        val bits = input and 0xffff_ffffL
                        val exponent = bits and 0x7f80_0000L
                        val fraction = bits and 0x007f_ffffL
                        assertFalse(exponent == 0x7f80_0000L && fraction != 0L && (bits and 0x0040_0000L) == 0L)
                    }
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
                        // Resolve read-only metadata before the unchanged interpreted warmup.
                        val probes = snapshotProbes(entry)
                        var targets = emptyList<RootCallTarget>()
                        fun count() = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        fun check(compiled: Boolean) {
                            for ((input, native) in cases) {
                                val label = "$stage/$backend/$name/$input/inlining=$inlining"
                                // Read-only snapshots add no guest calls. Snapshot failures must
                                // not replace the result/counter assertions they help diagnose.
                                val beforeTargets = if (compiled) callState(entry, probes) else null
                                val before = count()
                                assertEquals(native, Calls.target(entry, arrayOf(0L, input)), label)
                                if (compiled) {
                                    val after = count()
                                    val afterTargets = callState(entry, probes)
                                    assertEquals(expectedCalls.getValue(name), after-before) {
                                        "$label exact compiled entries context@${System.identityHashCode(context).toString(16)}" +
                                            " handoff=${System.getProperty("thc.handoffSlabs", "false")}" +
                                            " countBefore=$before countAfter=$after" +
                                            " before={${describe(beforeTargets!!)}} after={${describe(afterTargets)}}"
                                    }
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
    private fun owner(operation: ByteArrayOp) = when (operation) {
        ByteArrayOp.INDEX_FLOAT -> "indexFloatBits"
        ByteArrayOp.READ_FLOAT, ByteArrayOp.WRITE_FLOAT -> "moveFloatBits"
        else -> "aliasWordBytes"
    }

    @Test fun exactFloatAndWordStateShapesAndSaturationAreRequiredInBothLoadModes() {
        val paths = paths()
        for (backend in listOf("ast", "bytecode")) context(true).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (operation in operations) for (mutation in 0..15) for (diagnostic in listOf(false, true)) {
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
                        3 -> metadata["rep"] = wrong("double", "DoubleRep")
                        4 -> flags[0] = true
                        5 -> (CoreRepresentations.metadata(args[0] as List<Any?>)!!["rep"] as MutableMap<String, Any?>)["kind"] = "unknown"
                        6 -> (CoreRepresentations.metadata(args[1] as List<Any?>)!!["rep"] as MutableMap<String, Any?>)["primReps"] = listOf("Int64Rep")
                        7 -> if (operation in listOf(ByteArrayOp.WRITE_FLOAT, ByteArrayOp.WRITE_WORD)) {
                            (CoreRepresentations.metadata(args[2] as List<Any?>)!! as MutableMap<String, Any?>)["rep"] = wrong("long", "IntRep")
                        } else metadata["rep"] = wrong("long", "IntRep")
                        8 -> if (operation in listOf(ByteArrayOp.READ_FLOAT, ByteArrayOp.READ_WORD)) {
                            val proof = metadata["rep"] as MutableMap<String, Any?>
                            (proof["components"] as MutableList<Any?>)[0] = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
                                "components" to emptyList<Any?>(), "primReps" to emptyList<String>(), "evaluated" to true)
                        } else metadata["rep"] = mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "evaluated" to true)
                        9 -> if (operation in listOf(ByteArrayOp.READ_FLOAT, ByteArrayOp.READ_WORD)) {
                            val proof = metadata["rep"] as MutableMap<String, Any?>
                            (proof["components"] as MutableList<Any?>)[1] = wrong("double", "DoubleRep")
                            proof["primReps"] = listOf("DoubleRep")
                        } else flags[1] = true
                        in 10..15 -> {
                            val rep = when (mutation) {
                                10 -> if (operation.primitive.contains("Float")) "Word32Rep" else "Word64Rep"
                                11 -> "Int32Rep"; 12 -> "Int64Rep"; 13 -> "IntRep"
                                14 -> if (operation.primitive.contains("Float")) "DoubleRep" else "Word32Rep"
                                else -> if (operation.primitive.contains("Float")) "FloatRep" else "WordRep"
                            }
                            val kind = if (mutation == 15) "unknown" else if (rep == "DoubleRep") "double" else "long"
                            val payload = wrong(kind, rep)
                            if (operation.primitive.startsWith("write")) {
                                (CoreRepresentations.metadata(args[2] as List<Any?>)!! as MutableMap<String, Any?>)["rep"] = payload
                            } else if (operation.tuple) {
                                val proof = metadata["rep"] as MutableMap<String, Any?>
                                (proof["components"] as MutableList<Any?>)[1] = payload
                                proof["primReps"] = listOf(rep)
                            } else metadata["rep"] = payload
                        }
                    }
                    if (diagnostic && mutation == 15 && operation.tuple) {
                        // An unknown tuple component is the existing unsupported
                        // aggregate frontier, not a supported scalar contract.
                        // Diagnostic mode may defer it, but must trap on demand.
                        val p = program(language, module + mapOf("diagnosticUnsupported" to true, "instrument" to true), backend)
                        val reason = "Unsupported Core aggregate representation: unboxed-tuple has unsupported fields"
                        assertTrue((p.diagnostics().getValue("deferredUnsupported") as List<*>).contains(reason))
                        assertEquals(0L, (p.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                        val failure = assertThrows(RuntimeFault::class.java) {
                            Calls.target(p.hostEntryTarget(1), arrayOf(p.entryValue(owner(operation)), arrayOf(5L)))
                        }
                        assertEquals("Diagnostic unsupported path reached: $reason", failure.message)
                        assertEquals(1L, (p.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                        released(language)
                    } else assertThrows(RuntimeFault::class.java, {
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
                for (operation in operations) for (index in listOf(Long.MIN_VALUE, -1L,
                    if (operation.primitive.contains("Float")) 1L else 2L, 1L shl 32, 1L shl 61, 1L shl 62, Long.MAX_VALUE)) {
                    val name = owner(operation)
                    val module = CoreModules.reachable(merged(paths), name)
                    val app = applications(module).first { (it[1] as List<*>).take(2) == listOf("prim", operation.primitive) }
                    val args = app[2] as MutableList<Any?>
                    args[1] = listOf("lit", "int", index.toString(), CoreRepresentations.metadata(args[1] as List<Any?>))
                    val program = program(language, module, backend)
                    val function = context.asValue(EntryValue(program, name, 1))
                    val failure = assertThrows(PolyglotException::class.java) { function.execute(5L) }
                    val storage = if (operation.primitive.contains("Float")) "Float" else "Int"
                    assertTrue(failure.message.orEmpty().contains("ByteArray# $storage index"), "$backend/$operation/$index: $failure")
                    released(language)
                    assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                }
            } finally { context.leave() }
        }
    }
}
