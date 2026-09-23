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
    private fun model(name: String, seed: Long): Long {
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
            val shift = (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) position else 3-position)*8
            (value ushr shift) and 255
        }
        bytes[3] = (seed+101) and 255
        bytes[4] = (seed+37) and 255
        fun element(offset: Int): Long {
            var value = 0L
            for (byte in 0..3) {
                val shift = (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) byte else 3-byte)*8
                value = value or (bytes[offset+byte] shl shift)
            }
            return decode(value)
        }
        return 3*decode(bits) + 16*element(0) + 20*element(4) +
            17*bytes[0] + 19*bytes[3] + 23*bytes[4] + 29*bytes[7]
    }
    @Test fun nativePublicArraysAndByteAliasesWithInlining() = native(true)
    @Test fun nativePublicArraysAndByteAliasesAcrossResidualCalls() = native(false)
    @Test fun genuineNoinlineNarrowLiteralsRefineUnknownProofsInCompiledCode() {
        val manifest = manifest()
        val entries = listOf("noinlineInt32Literal", "noinlineWord32Literal")
        assertEquals(entries, manifest["literalEntries"])
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale literal fixture: $path")
        }
        val rows = File(root, "build/int32-arrays/literal-oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(entries.toSet(), rows.keys)
        assertEquals(14, rows.values.sumOf { it.size })
        assertEquals(14, (manifest["literalNativeRows"] as Number).toInt())
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) for (name in entries) {
            val cases = rows.getValue(name).map { it[1].toLong() to it[2].toLong() }
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
        assertEquals(names.toSet(), (manifest["entries"] as List<String>).toSet())
        assertEquals(if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) "little" else "big", manifest["byteOrder"])
        assertEquals(64, (manifest["wordBits"] as Number).toInt())
        assertEquals(32, (manifest["elementBits"] as Number).toInt())
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale 32-bit-array fixture: $path; rerun prepare-int32-arrays.py")
        }
        val rows = File(root, "build/int32-arrays/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(names.toSet(), rows.keys)
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.values.sumOf { it.size })
        val expectedCalls = names.associateWith { 2L }
        assertEquals(expectedCalls, (manifest["expectedGuestCallsByEntry"] as Map<String, Number>).mapValues { it.value.toLong() })
        val stages = manifest["stages"] as Map<String, List<String>>
        assertEquals(setOf("pre", "post"), stages.keys)
        for ((stage, paths) in stages) {
            val module = merged(paths)
            for (name in names) {
                val cases = rows.getValue(name).map { it[1].toLong() to it[2].toLong() }
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
