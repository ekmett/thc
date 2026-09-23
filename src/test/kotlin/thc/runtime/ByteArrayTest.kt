@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest

class ByteArrayTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val names = listOf("shortBytes", "orderedBytes")
    private fun manifest() = Json.parse(File(root, "build/bytearray/manifest.json").readText()) as Map<String, Any?>
    private fun merged(paths: List<String>) = CoreModules.merge(paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> })
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun mathematical(name: String, seed: Long): Long {
        val x = BigInteger.valueOf(seed)
        fun byte(value: BigInteger) = value.mod(BigInteger.valueOf(256))
        if (name == "orderedBytes") return (BigInteger.valueOf(3) + byte(x) +
            byte(x + BigInteger.valueOf(17)) * BigInteger.valueOf(257) +
            byte(x + BigInteger.TWO) * BigInteger.valueOf(65537) +
            byte(x + BigInteger.valueOf(71)) * BigInteger.valueOf(16777259)).toLong()
        val size = x.abs().mod(BigInteger.valueOf(33)).toInt()
        var value = BigInteger.ZERO
        for (index in 0 until size) value = value * BigInteger.valueOf(33) + byte(x + BigInteger.valueOf(17L * index))
        return (value + BigInteger.valueOf(size.toLong())).toLong()
    }
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target), label)

    @Test fun installedShortByteStringAndOrderedWritesMatchNativeAndIndependentModel() {
        val manifest = manifest()
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale ByteArray fixture: $path; rerun prepare-bytearray.py")
        }
        val rows = File(root, "build/bytearray/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(names.toSet(), rows.keys)
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.values.sumOf { it.size })
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) {
            val module = merged(paths)
            for (name in names) {
                val cases = rows.getValue(name).map { it[1].toLong() to it[2].toLong() }
                cases.forEach { (input, native) -> assertEquals(mathematical(name, input), native, "Native $name($input)") }
                for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val label = "$stage/$backend/$name"
                        val program = program(language, CoreModules.reachable(module, name) + ("instrument" to true), backend)
                        val host = program.hostEntryTarget(1)
                        val function = context.asValue(EntryValue(program, name, 1))
                        fun check(row: Pair<Long, Long>) = assertEquals(row.second, function.execute(row.first).asLong(), "$label(${row.first})")
                        fun compiled() = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        cases.forEach(::check)
                        assertTrue(function.invokeMember("compile").asBoolean(), "$label installation")
                        val original = program.entryTarget(name)
                        val active = NodeUtil.findAllNodeInstances(host.rootNode, DirectCallNode::class.java)
                            .filter { it.callTarget === original }.map { it.currentCallTarget as RootCallTarget }
                            .ifEmpty { listOf(original) }
                        for (row in cases.asReversed()) {
                            val before = compiled()
                            check(row)
                            assertTrue(compiled() > before, "$label(${row.first}) must enter installed guest code")
                            valid(host, "$label host remains installed")
                            active.forEach { valid(it, "$label active target remains installed") }
                        }
                        for (counter in listOf("unsupportedTraps", "blackholes"))
                            assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong(), "$label/$counter")
                        assertEquals(0, language.handoffState.get().results.depth, "$label releases tuple results")
                        if (name == "orderedBytes") assertEquals(0L, language.handoffState.get().results.allocations,
                            "$label saturated primitives write directly into locals")
                    } finally { context.leave() }
                }
            }
        }
    }

    @Test fun managedStorageHasExactSizeUnsignedBytesIdentityAndGuardedDomain() {
        for (size in listOf(0L, 1L, 7L, 8L, 9L, 256L)) {
            val array = ManagedByteArray.allocate(size)
            assertEquals(ByteArray::class.java, array.javaClass)
            assertEquals(java.lang.Byte.TYPE, array.javaClass.componentType)
            assertEquals(size, ManagedByteArray.size(array))
            assertSame(array, ManagedByteArray.freeze(array))
            assertNotSame(array, ManagedByteArray.allocate(size))
            for (index in 0 until size) ManagedByteArray.write(array, index, index - 128)
            for (index in 0 until size) assertEquals((index - 128) and 255, ManagedByteArray.read(array, index))
            for (index in listOf(Long.MIN_VALUE, -1L, size, Long.MAX_VALUE)) {
                assertThrows(RuntimeFault::class.java) { ManagedByteArray.read(array, index) }
                assertThrows(RuntimeFault::class.java) { ManagedByteArray.write(array, index, 1) }
            }
        }
        for (size in listOf(Long.MIN_VALUE, -1L, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { ManagedByteArray.allocate(size) }
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.require(Any()) }
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.requireState(0L) }
    }

    @Test fun effectsEvaluateTheStateOperandBeforeWritingAndFailWithoutPublishing() {
        val builder = FrameDescriptor.newBuilder()
        val slot = builder.addSlot(FrameSlotKind.Object, "destination", null)
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), builder.build())
        val events = mutableListOf<String>()
        fun operand(name: String, action: () -> Any?) = object : Expr() {
            override fun execute(frame: VirtualFrame): Any? { events.add(name); return action() }
        }
        val array = ManagedByteArray.allocate(1)
        ManagedByteArray.write(array, 0, 7)
        val write = byteArrayExpression(ByteArrayOp.WRITE, CoreRepresentation.UNKNOWN, arrayOf(
            operand("array") { array }, operand("index") { 0L }, operand("byte") { 129L },
            operand("state") { assertEquals(7L, ManagedByteArray.read(array, 0)); Unit }))
        assertSame(Unit, write.execute(frame))
        assertEquals(listOf("array", "index", "byte", "state"), events)
        assertEquals(129L, ManagedByteArray.read(array, 0))
        val marker = Any()
        frame.setObject(slot, marker)
        val failing = byteArrayExpression(ByteArrayOp.NEW, CoreRepresentation.UNKNOWN, arrayOf(
            operand("size") { 1L }, operand("bad-state") { throw RuntimeFault("state failed") }))
        assertThrows(RuntimeFault::class.java) { failing.executeTuple(frame, intArrayOf(slot), 0) }
        assertSame(marker, frame.getObject(slot))
    }

    private fun applications(value: Any?): List<MutableList<Any?>> = when (value) {
        is List<*> -> (if (value.firstOrNull() == "app") listOf(value as MutableList<Any?>) else emptyList()) + value.flatMap(::applications)
        is Map<*, *> -> value.values.flatMap(::applications)
        else -> emptyList()
    }

    @Test fun invalidSizesAndIndicesAreGuardedOnBothBackendsWithoutNativeUndefinedInputs() {
        val paths = (manifest()["stages"] as Map<String, List<String>>).getValue("pre")
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for ((primitive, operand, values) in listOf(
                    Triple("newByteArray#", 0, listOf(-1L, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE)),
                    Triple("writeWord8Array#", 1, listOf(-1L, 3L, Long.MAX_VALUE)),
                    Triple("indexWord8Array#", 1, listOf(-1L, 3L, Long.MAX_VALUE)))) {
                    for (value in values) {
                        val module = CoreModules.reachable(merged(paths), "orderedBytes")
                        val app = applications(module).first { (it[1] as List<*>).take(2) == listOf("prim", primitive) }
                        val args = app[2] as MutableList<Any?>
                        val old = args[operand] as List<Any?>
                        args[operand] = listOf("lit", "int", value.toString(), CoreRepresentations.metadata(old))
                        val program = program(language, module, backend)
                        val function = context.asValue(EntryValue(program, "orderedBytes", 1))
                        val failure = assertThrows(PolyglotException::class.java) { function.execute(5L) }
                        assertTrue(failure.message.orEmpty().contains("ByteArray#"), "$backend/$primitive/$value: $failure")
                        assertEquals(0, language.handoffState.get().results.depth)
                        assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    }
                }
                val good = context.asValue(EntryValue(program(language,
                    CoreModules.reachable(merged(paths), "orderedBytes"), backend), "orderedBytes", 1))
                assertEquals(mathematical("orderedBytes", 5), good.execute(5L).asLong())
            } finally { context.leave() }
        }
    }

    @Test fun exactShapesArityAndSaturationAreRequiredInBothLoadModes() {
        val paths = (manifest()["stages"] as Map<String, List<String>>).getValue("pre")
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (operation in ByteArrayOp.entries) for (mutation in 0..6) for (diagnostic in listOf(false, true)) {
                    val module = CoreModules.reachable(merged(paths), "orderedBytes")
                    val app = applications(module).first { (it[1] as List<*>).take(2) == listOf("prim", operation.primitive) }
                    val args = app[2] as MutableList<Any?>
                    val flags = app[3] as MutableList<Any?>
                    val metadata = CoreRepresentations.metadata(app) as MutableMap<String, Any?>
                    when (mutation) {
                        0 -> { args.removeAt(args.lastIndex); flags.removeAt(flags.lastIndex); metadata.remove("callDemand") }
                        1 -> { args.add(args[0]); flags.add(false); metadata.remove("callDemand") }
                        2 -> metadata.remove("rep")
                        3 -> { val proof = metadata["rep"] as MutableMap<String, Any?>
                            proof["primReps"] = listOf("WordRep"); proof["kind"] = "long"
                            proof.remove("aggregate"); proof.remove("components") }
                        4 -> flags[0] = true
                        5 -> { val proof = CoreRepresentations.metadata(args[0] as List<Any?>)!!["rep"] as MutableMap<String, Any?>
                            proof["kind"] = "unknown" }
                        6 -> if (operation.tuple) {
                            val proof = metadata["rep"] as MutableMap<String, Any?>
                            val children = proof["components"] as MutableList<Any?>
                            children[0] = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
                                "components" to emptyList<Any?>(), "primReps" to emptyList<String>(), "evaluated" to true)
                        } else {
                            val proof = CoreRepresentations.metadata(args[0] as List<Any?>)!!["rep"] as MutableMap<String, Any?>
                            proof["primReps"] = listOf("BoxedRep (Just Lifted)")
                        }
                    }
                    assertThrows(RuntimeFault::class.java, {
                        program(language, module + ("diagnosticUnsupported" to diagnostic), backend)
                    }, "$backend/${operation.primitive}/mutation$mutation/$diagnostic")
                }
                for (operation in ByteArrayOp.entries) {
                    val module = CoreModules.reachable(merged(paths), "orderedBytes")
                    val app = applications(module).first { (it[1] as List<*>).take(2) == listOf("prim", operation.primitive) }
                    val primitive = (app[1] as List<*>).toList(); app.clear(); app.addAll(primitive)
                    assertThrows(UnsupportedCore::class.java) { program(language, module, backend) }
                }
            } finally { context.leave() }
        }
    }

    @Test fun lexicalLiftedReferenceCannotBecomeByteArrayByRelabellingAnOccurrence() {
        val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val unlifted = mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Unlifted)"), "evaluated" to true)
        val lifted = unlifted + ("primReps" to listOf("BoxedRep (Just Lifted)"))
        val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
        val body = listOf("app", listOf("prim", "sizeofByteArray#"),
            listOf(listOf("var", "array", mapOf("rep" to unlifted))), listOf(false), false, false, mapOf("rep" to long))
        val lambda = listOf("lam", listOf(mapOf("id" to "array", "name" to "array", "lifted" to false, "rep" to lifted)),
            body, mapOf("rep" to closure, "resultRep" to long))
        val module = mapOf("schema" to 1, "ghc" to "9.14.1", "constructors" to emptyList<Any?>(),
            "bindings" to listOf(mapOf("id" to "root", "name" to "root", "lifted" to true, "arity" to 1, "expr" to lambda)))
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (diagnostic in listOf(false, true)) {
                    val failure = assertThrows(RuntimeFault::class.java) { program(language,
                        module + ("diagnosticUnsupported" to diagnostic), backend) }
                    assertTrue(failure.message.orEmpty().contains("Conflicting Core boxed levity proofs"), failure.message)
                }
            } finally { context.leave() }
        }
    }
}
