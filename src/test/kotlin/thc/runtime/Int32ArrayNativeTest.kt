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

class Int32ArrayNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val names = listOf("unboxedInt32Accum", "unboxedInt32ST", "unboxedWord32Accum", "unboxedWord32ST", "aliasInt32Bytes", "aliasWord32Bytes")
    private val operations = listOf(ByteArrayOp.READ_INT32, ByteArrayOp.WRITE_INT32, ByteArrayOp.INDEX_INT32,
        ByteArrayOp.READ_WORD32, ByteArrayOp.WRITE_WORD32, ByteArrayOp.INDEX_WORD32)
    private fun manifest() = Json.parse(File(root, "build/int32-arrays/manifest.json").readText()) as Map<String, Any?>
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
    private val inputs = buildSet {
        addAll(-16L..16L)
        addAll(listOf(Long.MIN_VALUE, Long.MIN_VALUE+1, Long.MAX_VALUE-1, Long.MAX_VALUE))
        for (bit in 0..63) for (delta in -1L..1L) for (sign in listOf(-1L, 1L))
            add(sign*((1L shl bit)+delta))
        for (bits in listOf("5555555555555555", "aaaaaaaaaaaaaaaa", "55aa55aa55aa55aa",
                "aa55aa55aa55aa55", "0123456789abcdef", "fedcba9876543210", "8000000080000000",
                "ffffffff00000000", "800000007fffffff", "7fffffff80000000", "ffffffff7fffffff",
                "0000000100000001", "12345678abcdef01")) add(bits.toULong(16).toLong())
    }.sorted()
    private data class Row(val name: String, val input: Long, val answer: Long)
    private fun expectedRows(order: ByteOrder) = names.flatMap { name -> inputs.map { Row(name, it, model(name, it, order)) } }
    private fun parseRows(text: String): List<Row> {
        val lines = text.lineSequence().toList().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        return lines.map { line ->
            val fields = line.split('\t')
            require(fields.size == 3) { "Int32-array oracle requires name/input/result" }
            Row(fields[0], fields[1].toLong(), fields[2].toLong())
        }
    }
    private fun checkedRows(text: String, order: ByteOrder = ByteOrder.nativeOrder()): List<Row> {
        val rows = parseRows(text)
        require(rows == expectedRows(order)) { "Int32-array native/model mismatch or incomplete/reordered corpus" }
        return rows
    }
    private val literalNames = listOf("noinlineInt32Literal", "noinlineWord32Literal")
    private val literalInputs = listOf(Long.MIN_VALUE, -2147483648L, -1L, 0L, 1L, 2147483647L, Long.MAX_VALUE)
    private val literalRows = literalNames.flatMap { name -> literalInputs.map { Row(name, it,
        it+if (name == literalNames[0]) -2147483648L else 4294967295L) } }
    private fun checkedLiteralRows(text: String): List<Row> {
        val rows = parseRows(text)
        require(rows == literalRows) { "Noinline narrow-literal corpus mismatch" }
        return rows
    }
    private fun exactAlias(name: String): Map<String, Int> {
        if (!name.startsWith("alias")) return emptyMap()
        val kind = if (name.contains("Word32")) "Word32" else "Int32"
        return mapOf("newByteArray#" to 1, "unsafeFreezeByteArray#" to 1, "write${kind}Array#" to 2,
            "read${kind}Array#" to 3, "index${kind}Array#" to 2, "writeWord8Array#" to 2,
            "indexWord8Array#" to 4, "*#" to 9, "+#" to 10, "xorI#" to 1, "word8ToWord#" to 4, "wordToWord8#" to 2) +
            if (kind == "Int32") mapOf("int2Word#" to 2, "word2Int#" to 4, "intToInt32#" to 2, "int32ToInt#" to 5)
            else mapOf("int2Word#" to 4, "word2Int#" to 9, "wordToWord32#" to 2, "word32ToWord#" to 5)
    }
    private fun required(name: String): Set<String> {
        if (name.startsWith("alias")) return exactAlias(name).keys
        val kind = if (name.contains("Word32")) "Word32" else "Int32"
        val conversions = if (kind == "Int32") setOf("intToInt32#", "int32ToInt#")
            else setOf("wordToWord32#", "word32ToWord#", "int2Word#", "word2Int#")
        return setOf("newByteArray#", "unsafeFreezeByteArray#", "read${kind}Array#", "write${kind}Array#",
            "index${kind}Array#", "plus$kind#") + conversions +
            if (name.endsWith("ST")) setOf("sub$kind#", "times$kind#") else emptySet()
    }
    private fun checkedReport(name: String, report: Map<String, Any?>): Map<String, Int> {
        require(name in names && report["accepted"] == true) { "Int32-array strict audit failed: $name" }
        val counts = (report["primitives"] as List<Map<String, Any?>>).associate { it["name"] as String to (it["uses"] as List<*>).size }
        require(counts.keys.containsAll(required(name))) { "Missing Int32-array primitive: $name" }
        require(!name.startsWith("alias") || counts == exactAlias(name)) { "Int32 alias use counts changed: $name" }
        return counts
    }
    private fun model(name: String, seed: Long, order: ByteOrder = ByteOrder.nativeOrder()): Long {
        require(name in names) { "Unknown Int32-array entry: $name" }
        val unsigned = name.contains("Word32")
        fun decode(value: Long): Long {
            val bits = value and 0xffff_ffffL
            return if (!unsigned && bits >= 0x8000_0000L) bits - 0x1_0000_0000L else bits
        }
        val bits = seed and 0xffff_ffffL
        if (name.endsWith("Accum")) return 7*decode(bits+1) + 11*decode(bits+5) + 13*decode(2*bits)
        if (name.endsWith("ST")) return 7*decode(bits) + 11*decode(bits+7) + 13*decode(2*bits+21)
        check(name in listOf("aliasInt32Bytes", "aliasWord32Bytes"))
        val bytes = LongArray(8) { offset ->
            val value = if (offset < 4) bits else bits xor 0x55aa55aaL
            val position = offset % 4
            val shift = (if (order == ByteOrder.LITTLE_ENDIAN) position else 3-position)*8
            (value ushr shift) and 255
        }
        bytes[3] = (seed+101) and 255
        bytes[4] = (seed+37) and 255
        fun element(offset: Int): Long {
            var value = 0L
            for (byte in 0..3) {
                val shift = (if (order == ByteOrder.LITTLE_ENDIAN) byte else 3-byte)*8
                value = value or (bytes[offset+byte] shl shift)
            }
            return decode(value)
        }
        return 3*decode(bits) + 16*element(0) + 20*element(4) +
            17*bytes[0] + 19*bytes[3] + 23*bytes[4] + 29*bytes[7]
    }
    @Test fun independentModelsCoverNarrowCellUpdatesAndBothByteOrders() {
        assertEquals(397, inputs.size); assertEquals(inputs.sorted().distinct(), inputs)
        for (bit in 0..63) for (delta in -1L..1L) for (sign in listOf(-1L, 1L))
            assertTrue(sign*((1L shl bit)+delta) in inputs)
        for (unsigned in listOf(false, true)) {
            val kind = if (unsigned) "Word32" else "Int32"
            fun widen(bits: Long) = if (unsigned) bits and 0xffff_ffffL else bits.toInt().toLong()
            for (raw in inputs) {
                val bits = raw and 0xffff_ffffL
                val accum = LongArray(8) { bits }; val st = LongArray(8) { bits }
                for ((index, value) in listOf(-3 to 3L, 0 to 5L, -3 to -2L, 4 to bits))
                    accum[index+3] = (accum[index+3]+value) and 0xffff_ffffL
                st[3] = (st[0]+7) and 0xffff_ffffL
                st[7] = (3*st[3]-bits) and 0xffff_ffffL
                for ((suffix, cells) in listOf("Accum" to accum, "ST" to st))
                    assertEquals(7*widen(cells[0])+11*widen(cells[3])+13*widen(cells[7]), model("unboxed$kind$suffix", raw))
                for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
                    val little = order == ByteOrder.LITTLE_ENDIAN
                    val shiftA = if (little) 24 else 0; val shiftB = if (little) 0 else 24
                    val a = (bits and (255L shl shiftA).inv()) or (((raw+101) and 255) shl shiftA)
                    val b = ((bits xor 0x55aa55aaL) and (255L shl shiftB).inv()) or (((raw+37) and 255) shl shiftB)
                    val first = if (little) a and 255 else a ushr 24
                    val last = if (little) b ushr 24 else b and 255
                    assertEquals(3*widen(bits)+16*widen(a)+20*widen(b)+17*first+19*((raw+101) and 255)+23*((raw+37) and 255)+29*last,
                        model("alias${kind}Bytes", raw, order), "$kind/$raw/$order")
                }
            }
        }
        for (name in names) for (x in listOf(0L, 1L, 0x7fffffffL, 0x80000000L, 0xffffffffL))
            for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
                assertEquals(model(name, x, order), model(name, x+0x1_0000_0000L, order))
                assertEquals(model(name, x, order), model(name, x-0x1_0000_0000L, order))
            }
        for ((signed, unsigned) in listOf("unboxedInt32Accum" to "unboxedWord32Accum",
                "unboxedInt32ST" to "unboxedWord32ST", "aliasInt32Bytes" to "aliasWord32Bytes"))
            for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN))
                assertNotEquals(model(signed, 0x80000000L, order), model(unsigned, 0x80000000L, order))
        assertNotEquals(model("aliasInt32Bytes", 0, ByteOrder.LITTLE_ENDIAN), model("aliasInt32Bytes", 0, ByteOrder.BIG_ENDIAN))
        assertThrows(IllegalArgumentException::class.java) { model("unknownAccum", 0) }
    }

    @Test fun exactCorpusRejectsMissingDuplicateReorderedMalformedAndWrongRows() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val rows = expectedRows(order)
            fun text(rows: List<Row>) = rows.joinToString("\n", postfix="\n") { "${it.name}\t${it.input}\t${it.answer}" }
            val valid = text(rows); val first = rows.first()
            assertEquals(2382, rows.size); assertEquals(rows, checkedRows(valid, order))
            val bad = listOf("", text(rows.drop(1)), text(rows+first), text(rows.reversed()),
                text(rows.toMutableList().apply { this[1] = first }),
                text(listOf(first.copy(name="unknown"))+rows.drop(1)),
                text(listOf(first.copy(input=first.input+1))+rows.drop(1)),
                text(listOf(first.copy(answer=first.answer+1))+rows.drop(1)),
                valid.replaceFirst("\t", " "), valid.replaceFirst("\t", "\textra\t"),
                "unboxedInt32Accum\t9223372036854775808\t0\n", "unboxedInt32Accum\t0\tnot-an-int\n", valid+"\n")
            for ((index, corrupt) in bad.withIndex())
                assertThrows(IllegalArgumentException::class.java, { checkedRows(corrupt, order) }, "$order/mutation$index")
        }
        fun text(rows: List<Row>) = rows.joinToString("\n", postfix="\n") { "${it.name}\t${it.input}\t${it.answer}" }
        assertEquals(literalRows, checkedLiteralRows(text(literalRows)))
        for (corrupt in listOf(emptyList(), literalRows.drop(1), literalRows+literalRows.first(), literalRows.reversed(),
                literalRows.toMutableList().apply { this[0] = first().copy(answer=first().answer+1) },
                literalRows.toMutableList().apply { this[0] = first().copy(input=0) }))
            assertThrows(IllegalArgumentException::class.java) { checkedLiteralRows(text(corrupt)) }
    }

    @Test fun primitiveReportsRequireAcceptanceRequiredNamesAndExactAliasCounts() {
        for (name in names) {
            val counts = required(name).associateWith { 1 } + exactAlias(name)
            fun report(values: Map<String, Int>, accepted: Boolean = true) = mapOf("accepted" to accepted,
                "primitives" to values.map { (primitive, count) -> mapOf("name" to primitive, "uses" to List(count) { emptyMap<String, Any?>() }) })
            assertEquals(counts, checkedReport(name, report(counts)))
            assertThrows(IllegalArgumentException::class.java) { checkedReport(name, report(counts, false)) }
            for (primitive in required(name))
                assertThrows(IllegalArgumentException::class.java) { checkedReport(name, report(counts-primitive)) }
            for ((primitive, count) in exactAlias(name))
                assertThrows(IllegalArgumentException::class.java) { checkedReport(name, report(counts+(primitive to count-1))) }
            if (name.startsWith("alias"))
                assertThrows(IllegalArgumentException::class.java) { checkedReport(name, report(counts+("readIntArray#" to 1))) }
        }
    }

    @Test fun nativePublicArraysAndByteAliasesWithInlining() = native(true)
    @Test fun nativePublicArraysAndByteAliasesAcrossResidualCalls() = native(false)
    @Test fun genuineNoinlineNarrowLiteralsRefineUnknownProofsInCompiledCode() {
        val manifest = manifest()
        val entries = literalNames
        assertEquals(entries, manifest["literalEntries"])
        assertEquals(literalInputs, (manifest["literalInputs"] as List<Number>).map { it.toLong() })
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale literal fixture: $path")
        }
        val rows = checkedLiteralRows(File(root, "build/int32-arrays/literal-oracle.tsv").readText()).groupBy { it.name }
        assertEquals(entries.toSet(), rows.keys)
        assertEquals(14, rows.values.sumOf { it.size })
        assertEquals(14, (manifest["literalNativeRows"] as Number).toInt())
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) for (name in entries) {
            val cases = rows.getValue(name).map { it.input to it.answer }
            assertEquals((manifest["literalInputs"] as List<Number>).map { it.toLong() }, cases.map { it.first })
            for ((input, answer) in cases) assertEquals(input + if (name == entries[0]) -2147483648L else 4294967295L, answer)
            for (backend in listOf("ast", "bytecode")) for (inlining in listOf(false, true)) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val linked = CoreModules.reachable(merged(paths), name)
                    val bindings = linked["bindings"] as List<Map<String, Any?>>
                    val program = program(language, linked + ("instrument" to true), backend)
                    val entry = program.entryTarget(bindings.single { it["name"] == name }["id"] as String)
                    fun count() = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    for ((input, answer) in cases) assertEquals(answer, Calls.target(entry, arrayOf(0L, input)))
                    val targets = activeTargets(entry)
                    assertEquals(2, targets.size, "$stage/$backend/$name entry and opaque worker")
                    targets.forEach(::compile)
                    for ((input, answer) in cases) {
                        val label = "$stage/$backend/$name/$input/inlining=$inlining"
                        val before = count()
                        assertEquals(answer, Calls.target(entry, arrayOf(0L, input)), label)
                        assertEquals(2L, count()-before, "$label exact compiled entries")
                        val active = activeTargets(entry)
                        assertEquals(targets.size, active.size, "$label active target count")
                        assertTrue(active.all { current -> targets.any { it === current } }, "$label active identities")
                        targets.forEach { valid(it, label) }
                        released(language)
                    }
                    for (counter in listOf("unsupportedTraps", "blackholes"))
                        assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong(), counter)
                } finally { context.leave() }
            }
        }
    }
    private fun native(inlining: Boolean) {
        val manifest = manifest()
        assertEquals(names, manifest["entries"])
        assertEquals(inputs, (manifest["inputs"] as List<Number>).map { it.toLong() })
        assertEquals(names.size*inputs.size, (manifest["nativeRows"] as Number).toInt())
        assertEquals(if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) "little" else "big", manifest["byteOrder"])
        assertEquals(64, (manifest["wordBits"] as Number).toInt())
        assertEquals(32, (manifest["elementBits"] as Number).toInt())
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale 32-bit-array fixture: $path; rerun prepare-int32-arrays.py")
        }
        val rows = checkedRows(File(root, "build/int32-arrays/oracle.tsv").readText()).groupBy { it.name }
        assertEquals(names.toSet(), rows.keys)
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.values.sumOf { it.size })
        val expectedCalls = names.associateWith { 2L }
        assertEquals(expectedCalls, (manifest["expectedGuestCallsByEntry"] as Map<String, Number>).mapValues { it.value.toLong() })
        val stages = manifest["stages"] as Map<String, List<String>>
        assertEquals(setOf("pre", "post"), stages.keys)
        for ((stage, paths) in stages) {
            val module = merged(paths)
            for (name in names) {
                val report = Json.parse(File(root, "build/int32-arrays/$stage/$name.audit.json").readText()) as Map<String, Any?>
                val counts = checkedReport(name, report)
                assertEquals(counts, (manifest["primitiveCounts"] as Map<String, Map<String, Number>>).getValue("$stage/$name").mapValues { it.value.toInt() })
                val cases = rows.getValue(name).map { it.input to it.answer }
                assertEquals(cases.size, cases.map { it.first }.toSet().size)
                assertEquals((manifest["inputs"] as List<Number>).map { it.toLong() }.toSet(), cases.map { it.first }.toSet())
                for ((input, native) in cases) {
                    assertEquals(model(name, input), native, "Native $name($input)")
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
    private fun owner(operation: ByteArrayOp) = if (operation.primitive.contains("Word32")) "aliasWord32Bytes" else "aliasInt32Bytes"

    @Test fun exactNarrowStateShapesAndSaturationAreRequiredInBothLoadModes() {
        val paths = paths()
        for (backend in listOf("ast", "bytecode")) context(true).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (operation in operations) for (mutation in 0..13) for (diagnostic in listOf(false, true)) {
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
                        7 -> if (operation in listOf(ByteArrayOp.WRITE_INT32, ByteArrayOp.WRITE_WORD32)) {
                            (CoreRepresentations.metadata(args[2] as List<Any?>)!! as MutableMap<String, Any?>)["rep"] = wrong("long", "IntRep")
                        } else metadata["rep"] = wrong("long", "IntRep")
                        8 -> if (operation in listOf(ByteArrayOp.READ_INT32, ByteArrayOp.READ_WORD32)) {
                            val proof = metadata["rep"] as MutableMap<String, Any?>
                            (proof["components"] as MutableList<Any?>)[0] = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
                                "components" to emptyList<Any?>(), "primReps" to emptyList<String>(), "evaluated" to true)
                        } else metadata["rep"] = mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "evaluated" to true)
                        9 -> if (operation in listOf(ByteArrayOp.READ_INT32, ByteArrayOp.READ_WORD32)) {
                            val proof = metadata["rep"] as MutableMap<String, Any?>
                            (proof["components"] as MutableList<Any?>)[1] = wrong("float", "FloatRep")
                            proof["primReps"] = listOf("FloatRep")
                        } else flags[1] = true
                        in 10..13 -> {
                            val rep = when (mutation) {
                                10 -> if (operation.primitive.contains("Word32")) "Int32Rep" else "Word32Rep"
                                11 -> "Int64Rep"; 12 -> "Word64Rep"; else -> "WordRep"
                            }
                            val payload = wrong("long", rep)
                            if (operation.primitive.startsWith("write")) {
                                (CoreRepresentations.metadata(args[2] as List<Any?>)!! as MutableMap<String, Any?>)["rep"] = payload
                            } else if (operation.tuple) {
                                val proof = metadata["rep"] as MutableMap<String, Any?>
                                (proof["components"] as MutableList<Any?>)[1] = payload
                                proof["primReps"] = listOf(rep)
                            } else metadata["rep"] = payload
                        }
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
                for (operation in operations) for (index in listOf(Long.MIN_VALUE, -1L, 2L, 1L shl 32, 1L shl 62, Long.MAX_VALUE)) {
                    val name = owner(operation)
                    val module = CoreModules.reachable(merged(paths), name)
                    val app = applications(module).first { (it[1] as List<*>).take(2) == listOf("prim", operation.primitive) }
                    val args = app[2] as MutableList<Any?>
                    args[1] = listOf("lit", "int", index.toString(), CoreRepresentations.metadata(args[1] as List<Any?>))
                    val program = program(language, module, backend)
                    val function = context.asValue(EntryValue(program, name, 1))
                    val failure = assertThrows(PolyglotException::class.java) { function.execute(5L) }
                    assertTrue(failure.message.orEmpty().contains("ByteArray# 32-bit index"), "$backend/$operation/$index: $failure")
                    released(language)
                    assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                }
            } finally { context.leave() }
        }
    }
}
