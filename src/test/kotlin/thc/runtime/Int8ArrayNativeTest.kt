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
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class Int8ArrayNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val names = listOf("unboxedInt8Accum", "unboxedInt8ST", "unboxedWord8Accum", "unboxedWord8ST", "aliasBytes", "emptyBytes",
        "rawSignedRead", "rawUnsignedRead", "rawSignedIndex")
    private val operations = listOf(ByteArrayOp.READ_INT8, ByteArrayOp.WRITE_INT8, ByteArrayOp.INDEX_INT8,
        ByteArrayOp.READ_WORD8)
    private fun manifest() = Json.parse(File(root, "build/int8-arrays/manifest.json").readText()) as Map<String, Any?>
    private fun merged(paths: List<String>) = CoreModules.merge(paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> })
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .build()
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
    private fun model(name: String, raw: Long): Long {
        require(name in names)
        fun lane(x: Long, unsigned: Boolean = false): Long { val bits=x and 255L; return if(unsigned || bits<128) bits else bits-256 }
        if(name=="emptyBytes") return raw
        if(name.startsWith("raw")) return lane(raw, name=="rawUnsignedRead")
        if(name=="aliasBytes") { val a=(raw+101) and 255L;val b=(raw+37) and 255L
            return 3*lane(raw)+5*(raw and 255L)+20*lane(a)+34*b+17*lane(b)+19*a }
        val cells=if(name.endsWith("Accum")) listOf(raw+1,raw+5,2*raw) else listOf(raw,raw+7,2*raw+21)
        return cells.zip(listOf(7L,11L,13L)).sumOf { (x,w)->w*lane(x,name.contains("Word8")) }
    }

    // This inventory is independent of the producer and its manifest. Long arithmetic wraps at 64 bits.
    private val inputs = buildSet {
        addAll(-256L..255L)
        for (bit in 0..63) for (delta in -1L..1L) for (sign in listOf(-1L, 1L)) add(sign*((1L shl bit)+delta))
        addAll(listOf(0x5555555555555555UL, 0xaaaaaaaaaaaaaaaaUL, 0x0123456789abcdefUL, 0xfedcba9876543210UL).map { it.toLong() })
    }.sorted()
    private fun checkedRows(text: String): Map<String, List<Pair<Long, Long>>> {
        val split = text.lineSequence().toList()
        val lines = if (split.lastOrNull() == "") split.dropLast(1) else split
        require(lines.size == names.size*inputs.size) { "Int8 oracle row count mismatch" }
        val rows = names.associateWith { mutableListOf<Pair<Long, Long>>() }
        for ((index, line) in lines.withIndex()) {
            val fields = line.split('\t'); require(fields.size == 3) { "Int8 oracle columns at row $index" }
            val name = names[index/inputs.size]; val raw = inputs[index%inputs.size]
            require(fields[0] == name && fields[1].toLongOrNull() == raw) { "Int8 oracle inventory/order at row $index" }
            val answer = fields[2].toLongOrNull()
            require(answer == model(name, raw)) { "Int8 native/model mismatch at row $index" }
            rows.getValue(name).add(raw to answer)
        }
        return rows
    }
    @Test fun independentInventoryCoversEveryByteAndFullWidthBoundary() {
        assertEquals(846, inputs.size); assertEquals(7614, names.size*inputs.size)
        assertEquals(inputs, inputs.distinct().sorted())
        assertTrue(inputs.containsAll((-256L..255L).toList()))
        for (bit in 0..63) for (delta in -1L..1L) for (sign in listOf(-1L, 1L))
            assertTrue(sign*((1L shl bit)+delta) in inputs)
        for (name in names.filter { it != "emptyBytes" }) for (raw in 0L..255L) {
            assertEquals(model(name, raw), model(name, raw+256L), "$name/$raw")
            assertEquals(model(name, raw), model(name, raw-256L), "$name/$raw")
        }
        assertEquals(-128L, model("rawSignedRead", 128L)); assertEquals(-1L, model("rawSignedIndex", 255L))
        assertEquals(128L, model("rawUnsignedRead", 128L)); assertEquals(255L, model("rawUnsignedRead", -1L))
        assertNotEquals(model("unboxedInt8ST", 128L), model("unboxedWord8ST", 128L))
    }
    @Test fun independentModelMatchesSequentialCellsAndAliasSnapshots() {
        for (raw in inputs) {
            for (unsigned in listOf(false, true)) {
                fun wide(value: Byte) = if (unsigned) value.toLong() and 255L else value.toLong()
                val accum = ByteArray(8) { raw.toByte() }
                for ((index, value) in listOf(-3 to 3L, 0 to 5L, -3 to -2L, 4 to (raw and 255L)))
                    accum[index+3] = (accum[index+3]+value).toByte()
                val st = ByteArray(8) { raw.toByte() }
                st[3] = (st[0]+7).toByte(); st[7] = (3*st[3]-st[0]).toByte()
                for ((suffix, cells) in listOf("Accum" to accum, "ST" to st))
                    assertEquals(7*wide(cells[0])+11*wide(cells[3])+13*wide(cells[7]),
                        model("unboxed${if (unsigned) "Word8" else "Int8"}$suffix", raw))
            }
            val bytes = byteArrayOf(raw.toByte(), (raw xor 0x55L).toByte())
            val before = bytes[0].toLong(); val beforeUnsigned = before and 255L
            bytes[0] = (raw+101L).toByte(); bytes[1] = (raw+37L).toByte()
            val first = bytes[0].toLong(); val second = bytes[1].toLong()
            assertEquals(3*before+5*beforeUnsigned+7*first+11*(second and 255L)+13*first+17*second+
                19*(first and 255L)+23*(second and 255L), model("aliasBytes", raw))
            assertEquals(raw, model("emptyBytes", raw))
            assertEquals(raw.toByte().toLong(), model("rawSignedRead", raw))
            assertEquals(raw.toByte().toLong(), model("rawSignedIndex", raw))
            assertEquals(raw.toByte().toLong() and 255L, model("rawUnsignedRead", raw))
        }
    }
    @Test fun exactOracleRejectsMissingDuplicateReorderedMalformedAndWrongRows() {
        val lines = names.flatMap { name -> inputs.map { "$name\t$it\t${model(name, it)}" } }
        fun text(rows: List<String>) = rows.joinToString("\n", postfix="\n")
        val expected = names.associateWith { name -> inputs.map { it to model(name, it) } }
        assertEquals(expected, checkedRows(text(lines))); assertEquals(expected, checkedRows(lines.joinToString("\n")))
        fun reject(rows: List<String>) { assertThrows(IllegalArgumentException::class.java) { checkedRows(text(rows)) } }
        reject(emptyList()); reject(lines.drop(1)); reject(lines+lines.first()); reject(lines.reversed())
        reject(lines.toMutableList().apply { this[1]=first() })
        reject(lines.toMutableList().apply { Collections.swap(this, 0, 1) })
        reject(lines.drop(inputs.size)+lines.take(inputs.size))
        for (nameIndex in names.indices) reject(lines.toMutableList().apply {
            val index = nameIndex*inputs.size
            this[index] = this[index].substringBeforeLast('\t')+"\t${model(names[nameIndex], inputs.first()) xor 1L}"
        })
        for (bad in listOf("unknown\t0\t0", "", "unboxedInt8Accum\t0", lines.first()+"\t0",
            "unboxedInt8Accum\tbad\t0", "unboxedInt8Accum\t9223372036854775808\t0",
            "unboxedInt8Accum\t${inputs.first()}\t", "unboxedInt8Accum\t${inputs.first()}\t1.5",
            "unboxedInt8Accum\t${inputs.first()}\t9223372036854775808")) reject(listOf(bad)+lines.drop(1))
        reject(lines+"")
    }

    private fun requiredPrimitives(name: String): Set<String> = when (name) {
        "aliasBytes" -> setOf("newByteArray#", "unsafeFreezeByteArray#", "readInt8Array#", "writeInt8Array#",
            "indexInt8Array#", "readWord8Array#", "writeWord8Array#", "indexWord8Array#")
        "emptyBytes" -> setOf("newByteArray#", "unsafeFreezeByteArray#", "sizeofByteArray#")
        "rawSignedRead", "rawUnsignedRead", "rawSignedIndex" -> setOf("newByteArray#", "writeInt8Array#",
            when (name) { "rawSignedRead" -> "readInt8Array#"; "rawUnsignedRead" -> "readWord8Array#"; else -> "indexInt8Array#" }) +
            if (name == "rawSignedIndex") setOf("unsafeFreezeByteArray#") else emptySet()
        else -> {
            require(name in names)
            val kind = if (name.contains("Word8")) "Word8" else "Int8"
            setOf("newByteArray#", "unsafeFreezeByteArray#", "read${kind}Array#", "write${kind}Array#",
                "index${kind}Array#", "plus$kind#") + if (name.endsWith("ST")) setOf("sub$kind#", "times$kind#") else emptySet()
        }
    }
    private fun checkedCalls(module: Map<String, Any?>, name: String): Long {
        val evidence = ArrayCoreEvidence(module, name)
        require(evidence.primitiveCounts.keys.containsAll(requiredPrimitives(name))) { "$name missing required primitive" }
        return evidence.immediateStateCalls().toLong()
    }
    @Test fun genuineCoreMustRetainEveryRequiredPrimitive() {
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
        }
    }

    @Test fun nativePublicArraysAndByteAliasesWithInlining() = native(true)
    @Test fun nativePublicArraysAndByteAliasesAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        val manifest = manifest()
        assertEquals(names, manifest["entries"])
        assertEquals(inputs, manifest["inputs"])
        assertEquals((names.size*inputs.size).toLong(), manifest["nativeRows"])
        assertEquals(64, (manifest["wordBits"] as Number).toInt())
        assertEquals(8, (manifest["elementBits"] as Number).toInt())
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale 8-bit-array fixture: $path; rerun prepare-int8-arrays.py")
        }
        val rows = checkedRows(File(root, "build/int8-arrays/oracle.tsv").readText())
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
    private fun owner(@Suppress("UNUSED_PARAMETER") operation: ByteArrayOp) = "aliasBytes"

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
                        7 -> if (operation in listOf(ByteArrayOp.WRITE_INT8)) {
                            (CoreRepresentations.metadata(args[2] as List<Any?>)!! as MutableMap<String, Any?>)["rep"] = wrong("long", "IntRep")
                        } else metadata["rep"] = wrong("long", "IntRep")
                        8 -> if (operation in listOf(ByteArrayOp.READ_INT8, ByteArrayOp.READ_WORD8)) {
                            val proof = metadata["rep"] as MutableMap<String, Any?>
                            (proof["components"] as MutableList<Any?>)[0] = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
                                "components" to emptyList<Any?>(), "primReps" to emptyList<String>(), "evaluated" to true)
                        } else metadata["rep"] = mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "evaluated" to true)
                        9 -> if (operation in listOf(ByteArrayOp.READ_INT8, ByteArrayOp.READ_WORD8)) {
                            val proof = metadata["rep"] as MutableMap<String, Any?>
                            (proof["components"] as MutableList<Any?>)[1] = wrong("float", "FloatRep")
                            proof["primReps"] = listOf("FloatRep")
                        } else flags[1] = true
                        in 10..18 -> {
                            val rep = when (mutation) {
                                10 -> if (operation.primitive.contains("Word8")) "Int8Rep" else "Word8Rep"
                                11 -> "Int64Rep"; 12 -> "Word64Rep"; 13 -> "WordRep"
                                14 -> "Int16Rep"; 15 -> "Word16Rep"; 16 -> "Int32Rep"; 17 -> "Word32Rep"
                                else -> if (operation.primitive.contains("Word8")) "Word8Rep" else "Int8Rep"
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
                    assertTrue(failure.message.orEmpty().contains("ByteArray# index"), "$backend/$operation/$index: $failure")
                    released(language)
                    assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                }
            } finally { context.leave() }
        }
    }
}
