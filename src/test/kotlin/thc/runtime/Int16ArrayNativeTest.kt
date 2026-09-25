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
import java.math.BigInteger
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class Int16ArrayNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val names = listOf("unboxedInt16Accum", "unboxedInt16ST", "unboxedWord16Accum", "unboxedWord16ST", "aliasInt16Bytes", "aliasWord16Bytes")
    private val operations = listOf(ByteArrayOp.READ_INT16, ByteArrayOp.WRITE_INT16, ByteArrayOp.INDEX_INT16,
        ByteArrayOp.READ_WORD16, ByteArrayOp.WRITE_WORD16, ByteArrayOp.INDEX_WORD16)
    private fun manifest() = Json.parse(File(root, "build/int16-arrays/manifest.json").readText()) as Map<String, Any?>
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
    private fun model(name: String, seed: Long, byteOrder: ByteOrder = ByteOrder.nativeOrder()): Long {
        require(name in names)
        val unsigned = name.contains("Word16")
        fun decode(value: Long): Long {
            val bits = value and 0xffffL
            return if (!unsigned && bits >= 0x8000L) bits - 0x1_0000L else bits
        }
        val bits = seed and 0xffffL
        if (name.endsWith("Accum") || name.endsWith("ST")) {
            val cells = LongArray(8) { decode(bits) }
            if (name.endsWith("Accum")) {
                for ((index, delta) in listOf(0 to 3L, 3 to 5L, 0 to -2L, 7 to bits))
                    cells[index] = decode(cells[index]+delta)
            } else {
                val before = cells[0]
                cells[3] = decode(before+7)
                val after = cells[3]
                cells[7] = decode(3*after-decode(bits))
            }
            return 7*cells[0] + 11*cells[3] + 13*cells[7]
        }
        check(name in listOf("aliasInt16Bytes", "aliasWord16Bytes"))
        val bytes = LongArray(4) { offset ->
            val value = if (offset < 2) bits else bits xor 0x55aaL
            val position = offset % 2
            val shift = (if (byteOrder == ByteOrder.LITTLE_ENDIAN) position else 1-position)*8
            (value ushr shift) and 255
        }
        bytes[1] = (seed+101) and 255
        bytes[2] = (seed+37) and 255
        fun element(offset: Int): Long {
            var value = 0L
            for (byte in 0..1) {
                val shift = (if (byteOrder == ByteOrder.LITTLE_ENDIAN) byte else 1-byte)*8
                value = value or (bytes[offset+byte] shl shift)
            }
            return decode(value)
        }
        return 3*decode(bits) + 16*element(0) + 20*element(2) +
            17*bytes[0] + 19*bytes[1] + 23*bytes[2] + 29*bytes[3]
    }
    // Independently pinned input inventory; the producer/manifest cannot silently shrink the domain.
    private val inputs = buildSet {
        addAll(-16L..16L); addAll(listOf(Long.MIN_VALUE, Long.MIN_VALUE+1, Long.MAX_VALUE-1, Long.MAX_VALUE))
        for (bit in 0..63) for (delta in -1L..1L) for (sign in listOf(-1L, 1L)) add(sign*((1L shl bit)+delta))
        addAll(listOf(0x5555555555555555UL, 0xaaaaaaaaaaaaaaaaUL, 0x55aa55aa55aa55aaUL, 0xaa55aa55aa55aa55UL,
            0x0123456789abcdefUL, 0xfedcba9876543210UL, 0x8000000080000000UL, 0xffffffff00000000UL,
            0x800000007fffffffUL, 0x7fffffff80000000UL, 0xffffffff7fffffffUL, 0x0000000100000001UL,
            0x12345678abcdef01UL, 0x80008000UL, 0xffff0000UL, 0x80007fffUL, 0x7fff8000UL, 0xffff7fffUL,
            0x00010001UL, 0x12345678abcd8000UL).map { it.toLong() })
    }.sorted()
    private val literalValues = mapOf("noinlineInt16Literal" to -32768L, "noinlineWord16Literal" to 65535L)
    private val literalInputs = listOf(Long.MIN_VALUE, -32768L, -1L, 0L, 1L, 32767L, Long.MAX_VALUE)
    private fun literalModel(name: String, raw: Long) = raw+literalValues.getValue(name)
    private fun checkedRows(text: String, literals: Boolean = false): Map<String, List<Pair<Long, Long>>> {
        val entries = if (literals) literalValues.keys.toList() else names
        val values = if (literals) literalInputs else inputs
        val split = text.lineSequence().toList()
        val lines = if (split.lastOrNull() == "") split.dropLast(1) else split
        require(lines.size == entries.size*values.size) { "Int16 oracle row count mismatch" }
        val rows = entries.associateWith { mutableListOf<Pair<Long, Long>>() }
        for ((index, line) in lines.withIndex()) {
            val fields = line.split('\t'); require(fields.size == 3) { "Int16 oracle columns at row $index" }
            val name = entries[index/values.size]; val raw = values[index%values.size]
            require(fields[0] == name && fields[1].toLongOrNull() == raw) { "Int16 oracle inventory/order at row $index" }
            val answer = fields[2].toLongOrNull()
            require(answer == if (literals) literalModel(name, raw) else model(name, raw)) { "Int16 native/model mismatch at row $index" }
            rows.getValue(name).add(raw to answer)
        }
        return rows
    }
    @Test fun independentInventoryAndNarrowingCoverFullWidthBoundaries() {
        assertEquals(403, inputs.size); assertEquals(2418, names.size*inputs.size)
        assertEquals(inputs, inputs.distinct().sorted())
        for (bit in 0..63) for (delta in -1L..1L) for (sign in listOf(-1L, 1L))
            assertTrue(sign*((1L shl bit)+delta) in inputs)
        for (name in names) for (raw in listOf(0L, 1L, 0x7fffL, 0x8000L, 0xffffL))
            for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
                assertEquals(model(name, raw, order), model(name, raw+65536L, order))
                assertEquals(model(name, raw, order), model(name, raw-65536L, order))
            }
        for ((signed, unsigned) in listOf("unboxedInt16Accum" to "unboxedWord16Accum", "unboxedInt16ST" to "unboxedWord16ST",
            "aliasInt16Bytes" to "aliasWord16Bytes"))
            assertNotEquals(model(signed, 0x8000L), model(unsigned, 0x8000L))
    }
    @Test fun independentModelMatchesClosedFormCellsAndBothEndianAliasMasks() {
        for (raw in inputs) for (unsigned in listOf(false, true)) {
            fun wide(value: Long) = if (unsigned) value and 65535L else value.toShort().toLong()
            val kind = if (unsigned) "Word16" else "Int16"
            assertEquals(7*wide(raw+1)+11*wide(raw+5)+13*wide(2*raw), model("unboxed${kind}Accum", raw))
            assertEquals(7*wide(raw)+11*wide(raw+7)+13*wide(2*raw+21), model("unboxed${kind}ST", raw))
            for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
                val lowFirst = order == ByteOrder.LITTLE_ENDIAN
                val shiftA = if (lowFirst) 8 else 0; val shiftB = 8-shiftA
                val before = raw and 65535L; val other = (raw xor 0x55aaL) and 65535L
                val a = (before and (255L shl shiftA).inv()) or (((raw+101L) and 255L) shl shiftA)
                val b = (other and (255L shl shiftB).inv()) or (((raw+37L) and 255L) shl shiftB)
                val byte0 = if (lowFirst) a and 255L else a ushr 8
                val byte3 = if (lowFirst) b ushr 8 else b and 255L
                assertEquals(3*wide(before)+16*wide(a)+20*wide(b)+17*byte0+19*((raw+101L) and 255L)+
                    23*((raw+37L) and 255L)+29*byte3, model("alias${kind}Bytes", raw, order), "$kind/$raw/$order")
            }
        }
        assertNotEquals(model("aliasInt16Bytes", 0L, ByteOrder.LITTLE_ENDIAN), model("aliasInt16Bytes", 0L, ByteOrder.BIG_ENDIAN))
    }
    @Test fun literalModelWrapsOnlyTheMachineResult() {
        for ((name, value) in literalValues) for (raw in literalInputs)
            assertEquals(BigInteger.valueOf(raw).add(BigInteger.valueOf(value)).toLong(), literalModel(name, raw))
        assertEquals(Long.MAX_VALUE-32767L, literalModel("noinlineInt16Literal", Long.MIN_VALUE))
        assertEquals(Long.MIN_VALUE+65534L, literalModel("noinlineWord16Literal", Long.MAX_VALUE))
        assertEquals(-32768L, literalModel("noinlineInt16Literal", 0L))
        assertEquals(65535L, literalModel("noinlineWord16Literal", 0L))
    }
    @Test fun exactOraclesRejectMissingDuplicateReorderedMalformedAndWrongRows() {
        for (literals in listOf(false, true)) {
            val entries = if (literals) literalValues.keys.toList() else names
            val values = if (literals) literalInputs else inputs
            fun answer(name: String, raw: Long) = if (literals) literalModel(name, raw) else model(name, raw)
            val lines = entries.flatMap { name -> values.map { "$name\t$it\t${answer(name, it)}" } }
            fun text(rows: List<String>) = rows.joinToString("\n", postfix="\n")
            val expected = entries.associateWith { name -> values.map { it to answer(name, it) } }
            assertEquals(expected, checkedRows(text(lines), literals))
            assertEquals(expected, checkedRows(lines.joinToString("\n"), literals))
            fun reject(rows: List<String>) { assertThrows(IllegalArgumentException::class.java) { checkedRows(text(rows), literals) } }
            reject(emptyList()); reject(lines.drop(1)); reject(lines+lines.first()); reject(lines.reversed())
            reject(lines.toMutableList().apply { this[1]=first() })
            reject(lines.toMutableList().apply { Collections.swap(this, 0, 1) })
            reject(lines.drop(values.size)+lines.take(values.size))
            for (nameIndex in entries.indices) reject(lines.toMutableList().apply {
                val index = nameIndex*values.size
                this[index] = this[index].substringBeforeLast('\t')+"\t${answer(entries[nameIndex], values.first()) xor 1L}"
            })
            for (bad in listOf("unknown\t0\t0", "", "${entries.first()}\t0", lines.first()+"\t0",
                "${entries.first()}\tbad\t0", "${entries.first()}\t9223372036854775808\t0",
                "${entries.first()}\t${values.first()}\t", "${entries.first()}\t${values.first()}\t1.5",
                "${entries.first()}\t${values.first()}\t9223372036854775808")) reject(listOf(bad)+lines.drop(1))
            reject(lines+"")
        }
    }
    private fun exactAliasPrimitives(name: String): Map<String, Int> {
        require(name in listOf("aliasInt16Bytes", "aliasWord16Bytes"))
        val kind = if (name.contains("Word16")) "Word16" else "Int16"
        return mapOf("newByteArray#" to 1, "unsafeFreezeByteArray#" to 1, "write${kind}Array#" to 2,
            "read${kind}Array#" to 3, "index${kind}Array#" to 2, "writeWord8Array#" to 2, "indexWord8Array#" to 4,
            "*#" to 9, "+#" to 10, "xorI#" to 1, "word8ToWord#" to 4, "wordToWord8#" to 2) +
            if (kind == "Int16") mapOf("int2Word#" to 2, "word2Int#" to 4, "intToInt16#" to 2, "int16ToInt#" to 5)
            else mapOf("int2Word#" to 4, "word2Int#" to 9, "wordToWord16#" to 2, "word16ToWord#" to 5)
    }
    private fun requiredPrimitives(name: String): Set<String> {
        require(name in names)
        if (name.startsWith("alias")) return exactAliasPrimitives(name).keys
        val kind = if (name.contains("Word16")) "Word16" else "Int16"
        val conversions = if (kind == "Int16") setOf("intToInt16#", "int16ToInt#")
            else setOf("wordToWord16#", "word16ToWord#", "int2Word#", "word2Int#")
        return setOf("newByteArray#", "unsafeFreezeByteArray#", "read${kind}Array#", "write${kind}Array#",
            "index${kind}Array#", "plus$kind#") + conversions +
            if (name.endsWith("ST")) setOf("sub$kind#", "times$kind#") else emptySet()
    }
    private fun checkedCalls(module: Map<String, Any?>, name: String): Long {
        val evidence = ArrayCoreEvidence(module, name)
        require(evidence.primitiveCounts.keys.containsAll(requiredPrimitives(name))) { "$name missing required primitive" }
        if (name.startsWith("alias")) require(evidence.primitiveCounts == exactAliasPrimitives(name)) { "$name exact alias primitive counts changed" }
        return evidence.immediateStateCalls().toLong()
    }
    private val unknownLiteralProof = mapOf("kind" to "unknown", "primReps" to null, "evaluated" to false)
    private fun literalCalls(module: Map<String, Any?>, name: String, unknownProof: Boolean = false): Long {
        val value = literalValues.getValue(name)
        val signed = name == "noinlineInt16Literal"
        val kind = if (signed) "int16" else "word16"
        val payload = if (signed) "Int16Rep" else "Word16Rep"
        val workerName = if (signed) "literalInt16Worker" else "literalWord16Worker"
        val evidence = ArrayCoreEvidence(module, name)
        val workers = evidence.bindings.filter { it["name"] == workerName }
        require(workers.size == 1) { "$name required literal worker disappeared" }
        val worker = workers.single(); val root = evidence.root
        require(evidence.bindings.map { it["id"] }.toSet() == setOf(root["id"], worker["id"])) { "$name literal closure changed" }
        for ((binding, reps) in listOf(root to listOf("IntRep"), worker to listOf("IntRep", payload))) {
            val expr = binding["expr"] as List<*>
            require(expr.firstOrNull() == "lam") { "$name expected literal lambda" }
            val formals = expr[1] as List<Map<String, Any?>>
            require(formals.size == reps.size && formals.zip(reps).all { (formal, rep) ->
                formal["rep"] == mapOf("kind" to "long", "primReps" to listOf(rep), "evaluated" to true)
            }) { "$name literal formal shape changed" }
            require(evidence.guestLambdas(expr).size == 1) { "$name extra literal guest lambda" }
            require(evidence.globalReferences(expr) == if (binding === root) listOf(worker["id"]) else emptyList<String>()) {
                "$name unexpected literal global call"
            }
        }
        val rootExpr = root["expr"] as List<Any?>
        val call = rootExpr[2] as List<*>
        require(call.size >= 6 && call[0] == "app" && (call[1] as? List<*>)?.take(2) == listOf("var", worker["id"]) &&
            call[3] == listOf(false, false) && call[4] == false && call[5] == false) { "$name literal worker must be called directly" }
        val args = call[2] as List<List<Any?>>
        require(args.size == 2) { "$name literal worker must be saturated" }
        val formal = (rootExpr[1] as List<Map<String, Any?>>).single()
        require(args[0].take(2) == listOf("var", formal["id"])) { "$name lost dynamic input" }
        val expectedProof = if (unknownProof) unknownLiteralProof
            else mapOf("kind" to "long", "primReps" to listOf(payload), "evaluated" to true)
        require(args[1].take(3) == listOf("lit", kind, value.toString()) &&
            CoreRepresentations.metadata(args[1])?.get("rep") == expectedProof) { "$name literal intrinsic proof changed" }
        val expected = if (signed) mapOf("+#" to 1, "int16ToInt#" to 1)
            else mapOf("+#" to 1, "word16ToWord#" to 1, "word2Int#" to 1)
        require(evidence.primitiveCounts == expected) { "$name literal worker primitives changed" }
        return evidence.bindings.sumOf { evidence.guestLambdas(it["expr"]).size }.toLong()
    }
    private fun literalModule(paths: List<String>, name: String, unknownProof: Boolean): Map<String, Any?> {
        val module = merged(paths)
        assertEquals(2L, literalCalls(module, name))
        if (unknownProof) {
            // Keep the genuine export exact. Separately project the older erased
            // argument proof to exercise intrinsic refinement across a worker call.
            val rootExpr = ArrayCoreEvidence(module, name).root["expr"] as List<*>
            val call = rootExpr[2] as List<*>
            val literal = (call[2] as List<*>)[1] as List<Any?>
            (CoreRepresentations.metadata(literal) as MutableMap<String, Any?>)["rep"] = unknownLiteralProof
            assertEquals(2L, literalCalls(module, name, unknownProof = true))
        }
        return module
    }
    @Test fun genuineCoreMustRetainRequiredAndExactAliasPrimitives() {
        for ((_, paths) in manifest()["stages"] as Map<String, List<String>>) for (name in names) {
            val module = merged(paths)
            assertEquals(2L, checkedCalls(module, name))
            for (primitive in requiredPrimitives(name)) {
                val changed = Json.parse(Json.stringify(module)) as Map<String, Any?>
                val nodes = ArrayCoreEvidence(changed, name).nodes(changed)
                    .filter { it.take(2) == listOf("prim", primitive) }
                assertTrue(nodes.isNotEmpty(), "$name/$primitive")
                for (node in nodes) (node as MutableList<Any?>)[1] = "missingArrayPrimitive#"
                assertThrows(IllegalArgumentException::class.java, { checkedCalls(changed, name) }, "$name/$primitive")
            }
            if (name.startsWith("alias")) for (replacement in listOf("+#", "readIntArray#")) {
                val changed = Json.parse(Json.stringify(module)) as Map<String, Any?>
                val primitive = ArrayCoreEvidence(changed, name).nodes(CoreModules.reachable(changed, name))
                    .first { it.take(2) == listOf("prim", "*#") } as MutableList<Any?>
                primitive[1] = replacement
                assertThrows(IllegalArgumentException::class.java, { checkedCalls(changed, name) }, "$name/$replacement")
            }
        }
    }
    @Test fun genuineLiteralProofAndDirectWorkerControlsRejectMutations() {
        for ((stage, paths) in manifest()["stages"] as Map<String, List<String>>) for (name in literalValues.keys) {
            val module = merged(paths)
            assertEquals(2L, literalCalls(module, name))
            for (mutation in listOf("conditional", "arity", "dynamic", "kind", "value", "wrong-proof", "unknown-proof", "wrong-formal",
                "extra-lambda", "worker", "flags", "extra-global", "primitives")) {
                val changed = Json.parse(Json.stringify(module)) as Map<String, Any?>
                val evidence = ArrayCoreEvidence(changed, name)
                val rootExpr = evidence.root["expr"] as MutableList<Any?>
                val worker = evidence.bindings.single { it["id"] != evidence.root["id"] } as MutableMap<String, Any?>
                val workerExpr = worker["expr"] as MutableList<Any?>
                val call = rootExpr[2] as MutableList<Any?>; val args = call[2] as MutableList<Any?>
                val literal = args[1] as MutableList<Any?>
                when (mutation) {
                    "conditional" -> rootExpr[2] = listOf("case", listOf("lit", "int", "0"), "v", listOf(listOf("default", null, emptyList<Any?>(), call)))
                    "arity" -> args.removeAt(1)
                    "dynamic" -> args[0] = listOf("lit", "int", "0")
                    "kind" -> literal[1] = if (name == "noinlineInt16Literal") "int" else "word"
                    "value" -> literal[2] = "1"
                    "wrong-proof" -> (CoreRepresentations.metadata(literal) as MutableMap<String, Any?>)["rep"] =
                        mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
                    "unknown-proof" -> (CoreRepresentations.metadata(literal) as MutableMap<String, Any?>)["rep"] = unknownLiteralProof
                    "wrong-formal" -> ((workerExpr[1] as List<*>)[1] as MutableMap<String, Any?>)["rep"] =
                        mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
                    "extra-lambda" -> workerExpr[2] = listOf("lam", emptyList<Any?>(), workerExpr[2])
                    "worker" -> worker["name"] = "wrongWorker"
                    "flags" -> call[3] = listOf(true, false)
                    "extra-global" -> workerExpr[2] = listOf("var", evidence.root["id"])
                    "primitives" -> (evidence.nodes(workerExpr).first { it.take(2) == listOf("prim", "+#") } as MutableList<Any?>)[1] = "-#"
                }
                assertThrows(IllegalArgumentException::class.java, { literalCalls(changed, name) }, "$stage/$name/$mutation")
            }
        }
    }
    @Test fun nativePublicArraysAndByteAliasesWithInlining() = native(true)
    @Test fun nativePublicArraysAndByteAliasesAcrossResidualCalls() = native(false)
    @Test fun genuineNoinlineNarrowLiteralsAndUnknownProofControlsInCompiledCode() {
        val manifest = manifest()
        val entries = literalValues.keys.toList()
        assertEquals(entries, manifest["literalEntries"])
        assertEquals(literalInputs, manifest["literalInputs"])
        assertEquals(setOf("pre", "post"), (manifest["stages"] as Map<*, *>).keys)
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale literal fixture: $path")
        }
        val rows = checkedRows(File(root, "build/int16-arrays/literal-oracle.tsv").readText(), literals=true)
        assertEquals(14L, manifest["literalNativeRows"])
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) for (name in entries) {
            val cases = rows.getValue(name)
            val expectedCalls = literalCalls(merged(paths), name)
            assertEquals((manifest["literalInputs"] as List<Number>).map { it.toLong() }, cases.map { it.first })
            for ((input, answer) in cases) assertEquals(input + if (name == entries[0]) -32768L else 65535L, answer)
            for (unknownProof in listOf(false, true)) for (backend in listOf("ast", "bytecode"))
                for (inlining in listOf(false, true)) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val linked = CoreModules.reachable(literalModule(paths, name, unknownProof), name)
                    val bindings = linked["bindings"] as List<Map<String, Any?>>
                    val program = program(language, linked + ("instrument" to true), backend)
                    val entry = program.entryTarget(bindings.single { it["name"] == name }["id"] as String)
                    fun count() = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    for ((input, answer) in cases) {
                        assertEquals(answer, Calls.target(entry, arrayOf(0L, input)))
                        released(language)
                    }
                    val targets = activeTargets(entry)
                    assertEquals(expectedCalls.toInt(), targets.size, "$stage/$backend/$name entry and opaque worker")
                    val labels = bindings.map {
                        val expression = it["expr"] as List<*>
                        assertEquals("lam", expression[0])
                        val formals = expression[1] as List<Map<String, Any?>>
                        "lambda ${formals.joinToString { formal -> formal["name"].toString() }}"
                    }.toSet()
                    assertEquals(labels, targets.map { it.rootNode.name }.toSet(), "$stage/$backend/$name guest labels")
                    targets.forEach(::compile)
                    for ((input, answer) in cases) {
                        val label = "$stage/$backend/$name/$input/inlining=$inlining/unknownProof=$unknownProof"
                        val before = count()
                        assertEquals(answer, Calls.target(entry, arrayOf(0L, input)), label)
                        assertEquals(expectedCalls, count()-before, "$label exact compiled entries")
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
        assertEquals(inputs, manifest["inputs"])
        assertEquals((names.size*inputs.size).toLong(), manifest["nativeRows"])
        assertEquals(if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) "little" else "big", manifest["byteOrder"])
        assertEquals(64, (manifest["wordBits"] as Number).toInt())
        assertEquals(16, (manifest["elementBits"] as Number).toInt())
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale 16-bit-array fixture: $path; regenerate the 16-bit-array fixtures")
        }
        val rows = checkedRows(File(root, "build/int16-arrays/oracle.tsv").readText())
        val stages = manifest["stages"] as Map<String, List<String>>
        assertEquals(setOf("pre", "post"), stages.keys)
        for ((stage, paths) in stages) {
            val module = merged(paths)
            for (name in names) {
                val cases = rows.getValue(name)
                val expectedCalls = checkedCalls(module, name)
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
                                    assertEquals(expectedCalls, count()-before, "$label exact compiled entries")
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
                        assertEquals(expectedCalls.toInt(), targets.size, "$stage/$backend/$name active guest roots")
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
    private fun owner(operation: ByteArrayOp) = if (operation.primitive.contains("Word16")) "aliasWord16Bytes" else "aliasInt16Bytes"

    @Test fun exactNarrowStateShapesAndSaturationAreRequiredInBothLoadModes() {
        val paths = paths()
        for (backend in listOf("ast", "bytecode")) context(true).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (operation in operations) for (mutation in 0..18) for (diagnostic in listOf(false, true)) {
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
                        7 -> if (operation in listOf(ByteArrayOp.WRITE_INT16, ByteArrayOp.WRITE_WORD16)) {
                            (CoreRepresentations.metadata(args[2] as List<Any?>)!! as MutableMap<String, Any?>)["rep"] = wrong("long", "IntRep")
                        } else metadata["rep"] = wrong("long", "IntRep")
                        8 -> if (operation in listOf(ByteArrayOp.READ_INT16, ByteArrayOp.READ_WORD16)) {
                            val proof = metadata["rep"] as MutableMap<String, Any?>
                            (proof["components"] as MutableList<Any?>)[0] = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
                                "components" to emptyList<Any?>(), "primReps" to emptyList<String>(), "evaluated" to true)
                        } else metadata["rep"] = mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "evaluated" to true)
                        9 -> if (operation in listOf(ByteArrayOp.READ_INT16, ByteArrayOp.READ_WORD16)) {
                            val proof = metadata["rep"] as MutableMap<String, Any?>
                            (proof["components"] as MutableList<Any?>)[1] = wrong("float", "FloatRep")
                            proof["primReps"] = listOf("FloatRep")
                        } else flags[1] = true
                        in 10..18 -> {
                            val rep = when (mutation) {
                                10 -> if (operation.primitive.contains("Word16")) "Int16Rep" else "Word16Rep"
                                11 -> "Int64Rep"; 12 -> "Word64Rep"; 13 -> "WordRep"
                                14 -> "Int8Rep"; 15 -> "Word8Rep"; 16 -> "Int32Rep"; 17 -> "Word32Rep"
                                else -> if (operation.primitive.contains("Word16")) "Word16Rep" else "Int16Rep"
                            }
                            val payload = wrong(if (mutation == 18) "unknown" else "long", rep)
                            if (operation.primitive.startsWith("write")) {
                                (CoreRepresentations.metadata(args[2] as List<Any?>)!! as MutableMap<String, Any?>)["rep"] = payload
                            } else if (operation.tuple) {
                                val proof = metadata["rep"] as MutableMap<String, Any?>
                                (proof["components"] as MutableList<Any?>)[1] = payload
                                proof["primReps"] = listOf(rep)
                            } else metadata["rep"] = payload
                        }
                    }
                    if (diagnostic && mutation == 18 && operation.tuple) {
                        // Unknown tuple leaves retain the existing diagnostic frontier.
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
                for (operation in operations) for (index in listOf(Long.MIN_VALUE, -1L, 2L, 1L shl 32, 1L shl 62, Long.MAX_VALUE)) {
                    val name = owner(operation)
                    val module = CoreModules.reachable(merged(paths), name)
                    val app = applications(module).first { (it[1] as List<*>).take(2) == listOf("prim", operation.primitive) }
                    val args = app[2] as MutableList<Any?>
                    args[1] = listOf("lit", "int", index.toString(), CoreRepresentations.metadata(args[1] as List<Any?>))
                    val program = program(language, module, backend)
                    val function = context.asValue(EntryValue(program, name, 1))
                    val failure = assertThrows(PolyglotException::class.java) { function.execute(5L) }
                    assertTrue(failure.message.orEmpty().contains("ByteArray# 16-bit index"), "$backend/$operation/$index: $failure")
                    released(language)
                    assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                }
            } finally { context.leave() }
        }
    }
}
