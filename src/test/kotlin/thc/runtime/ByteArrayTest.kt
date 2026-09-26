// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

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

class ByteArrayTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val names = listOf("shortBytes", "orderedBytes", "shortUncons", "copiedBytes")
    private val byteOperations = listOf(ByteArrayOp.NEW, ByteArrayOp.WRITE, ByteArrayOp.COPY, ByteArrayOp.FREEZE, ByteArrayOp.SIZE, ByteArrayOp.INDEX)
    private fun manifest() = Json.parse(File(root, "build/bytearray/manifest.json").readText()) as Map<String, Any?>
    private fun merged(paths: List<String>) = CoreModules.merge(paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> })
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun mathematical(name: String, seed: Long): Long {
        require(name in names) { "Unknown byte-array entry" }
        val x = BigInteger.valueOf(seed)
        fun byte(value: BigInteger) = value.mod(BigInteger.valueOf(256))
        if (name == "orderedBytes") return (BigInteger.valueOf(3) + byte(x) +
            byte(x + BigInteger.valueOf(17)) * BigInteger.valueOf(257) +
            byte(x + BigInteger.TWO) * BigInteger.valueOf(65537) +
            byte(x + BigInteger.valueOf(71)) * BigInteger.valueOf(16777259)).toLong()
        if (name == "copiedBytes") {
            val source = listOf(byte(x), byte(x + BigInteger.valueOf(17)), BigInteger.ZERO, BigInteger.valueOf(255))
            val destination = mutableListOf(11L, 22L, 33L, 44L, 55L, 66L).map { BigInteger.valueOf(it) }.toMutableList()
            val key = x.mod(BigInteger.valueOf(1024)).toInt()
            val start = key % 5; val end = key / 5 % 7
            val count = minOf(key / 35 % 5, 4 - start, 6 - end)
            repeat(count) { destination[end + it] = source[start + it] }
            return (BigInteger.TEN + (source + destination).mapIndexed { i, b -> b * BigInteger.valueOf(257).pow(i) }
                .fold(BigInteger.ZERO, BigInteger::add)).toLong()
        }
        val size = x.abs().mod(BigInteger.valueOf(33)).toInt()
        if (name == "shortUncons") return (0 until size).map { i ->
            byte(x + BigInteger.valueOf(17L * i)) * BigInteger.valueOf(33).pow(i)
        }.fold(BigInteger.ZERO, BigInteger::add).toLong()
        var value = BigInteger.ZERO
        for (index in 0 until size) value = value * BigInteger.valueOf(33) + byte(x + BigInteger.valueOf(17L * index))
        return (value + BigInteger.valueOf(size.toLong())).toLong()
    }
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target), label)

    @Test fun installedShortByteStringAndArrayEffectsMatchNativeAndIndependentModel() {
        val manifest = manifest()
        ByteArrayFixtureEvidence.verify(root, "bytearray", manifest)
        val rows = checkedRows(File(root, "build/bytearray/oracle.tsv").readText()).groupBy { it[0] }
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
                        if (name in listOf("orderedBytes", "copiedBytes")) assertEquals(0L, language.handoffState.get().results.allocations,
                            "$label saturated primitives write directly into locals")
                    } finally { context.leave() }
                }
            }
        }
    }

    private val inputs = ((-512L..512L).toList() + listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE)).sorted()
    private fun checkedRows(text: String): List<List<String>> {
        val lines = text.lineSequence().toList().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        require(lines.size == 4116)
        return lines.mapIndexed { index, line ->
            val fields = line.split('\t'); require(fields.size == 3)
            val name = names[index / inputs.size]; val raw = inputs[index % inputs.size]
            require(fields[0] == name && fields[1].toLongOrNull() == raw && fields[2].toLongOrNull() == mathematical(name, raw)) {
                "Missing, duplicate, reordered or mismatched byte-array row $index"
            }
            fields
        }
    }
    @Test fun originalSourceEvidenceAndCompleteCorpusFailClosed() {
        ByteArrayFixtureEvidence.rejectionControls(root, "bytearray")
        val text = File(root, "build/bytearray/oracle.tsv").readText(); checkedRows(text)
        val lines = text.lines().filter { it.isNotEmpty() }
        for (bad in listOf(lines.drop(1), lines + lines.first(), lines.reversed(), listOf(lines[1]) + lines.drop(1),
            listOf("unknown\t0\t0") + lines.drop(1), listOf("shortBytes\t9223372036854775808\t0") + lines.drop(1),
            listOf(lines.first().substringBeforeLast('\t') + "\t999") + lines.drop(1), lines + ""))
            assertThrows(IllegalArgumentException::class.java) { checkedRows(bad.joinToString("\n", postfix = "\n")) }
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

    @Test fun ownedGuestStorageRejectsFullWidthInvalidSizesAndIndicesBeforeWriting() {
        // Guest arrays use an owner so shrink preserves aliases. Exercise that
        // path separately from the raw host ByteArray controls above.
        for (size in listOf(Long.MIN_VALUE, -1L, Int.MAX_VALUE.toLong() + 1, 1L shl 32, Long.MAX_VALUE)) {
            val failure = assertThrows(RuntimeFault::class.java) { ManagedByteArray.allocateGuest(size) }
            assertEquals("Managed allocation size outside JVM domain", failure.message, "size=$size")
        }
        for (size in listOf(0L, 1L, 3L)) {
            val array = ManagedByteArray.allocateGuest(size)
            for (index in 0 until size) ManagedByteArray.writeGuest(array, index, 129 + index)
            val expected = (0 until size).map { 129 + it }
            for (index in listOf(Long.MIN_VALUE, -1L, size, 1L shl 32, Long.MAX_VALUE)) {
                val read = assertThrows(RuntimeFault::class.java) { ManagedByteArray.readGuest(array, index, true) }
                val write = assertThrows(RuntimeFault::class.java) { ManagedByteArray.writeGuest(array, index, 7) }
                for (failure in listOf(read, write))
                    assertEquals("Managed allocation range outside its backing storage", failure.message, "size=$size/index=$index")
                assertEquals(size, ManagedByteArray.sizeGuest(array))
                assertEquals(expected, (0 until size).map { ManagedByteArray.readGuest(array, it, true) })
            }
        }
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

    @Test fun copyRangesMatchAnIndependentModelAndRejectInvalidDomainsWithoutWriting() {
        for (sourceSize in 0..6) for (destinationSize in 0..6) {
            val source = ByteArray(sourceSize) { (it * 49 + 128).toByte() }
            for (from in 0..sourceSize) for (to in 0..destinationSize)
                for (count in 0..minOf(sourceSize - from, destinationSize - to)) {
                    val destination = ByteArray(destinationSize) { (it + 17).toByte() }
                    val expected = destination.copyOf()
                    repeat(count) { expected[to + it] = source[from + it] }
                    ManagedByteArray.copy(source, from.toLong(), destination, to.toLong(), count.toLong())
                    assertArrayEquals(expected, destination)
                    assertArrayEquals(ByteArray(sourceSize) { (it * 49 + 128).toByte() }, source)
                }
        }
        val source = byteArrayOf(0, -1, 17, 33)
        val destination = byteArrayOf(1, 2, 3, 4, 5, 6)
        for (range in listOf(
            listOf(-1L, 0L, 1L), listOf(5L, 0L, 0L), listOf(Long.MAX_VALUE, 0L, 1L),
            listOf(0L, -1L, 1L), listOf(0L, 7L, 0L), listOf(0L, Long.MAX_VALUE, 1L),
            listOf(0L, 0L, -1L), listOf(0L, 0L, 5L), listOf(0L, 3L, 4L),
            listOf(1L, 1L, Long.MAX_VALUE), listOf(0L, 0L, Long.MIN_VALUE),
            listOf(Int.MAX_VALUE.toLong() + 1, 0L, 0L), listOf(0L, Int.MAX_VALUE.toLong() + 1, 0L))) {
            val before = destination.copyOf()
            assertThrows(RuntimeFault::class.java) { ManagedByteArray.copy(source, range[0], destination, range[1], range[2]) }
            assertArrayEquals(before, destination, "No partial write for $range")
        }
        // GHC forbids the same array in different states, even disjoint/empty ranges.
        for (count in listOf(0L, 1L)) assertThrows(RuntimeFault::class.java) {
            ManagedByteArray.copy(source, 0, ManagedByteArray.freeze(source), 2, count)
        }
        val owned = ManagedByteArray.allocateGuest(4)
        for (index in 0L..3L) ManagedByteArray.writeGuest(owned, index, 17L + index)
        for (count in listOf(0L, 1L)) {
            val failure = assertThrows(RuntimeFault::class.java) {
                ManagedByteArray.copyGuest(owned, 0, ManagedByteArray.freezeGuest(owned), 2, count, false)
            }
            assertEquals("copyByteArray# requires distinct source and destination arrays", failure.message)
            assertEquals(listOf(17L, 18L, 19L, 20L), (0L..3L).map { ManagedByteArray.readGuest(owned, it, true) })
        }
    }

    @Test fun copyEvaluatesAllSixOperandsBeforeTheEffectAndStateFailureDoesNotWrite() {
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameDescriptor.newBuilder().build())
        val source = byteArrayOf(0, -1)
        val destination = byteArrayOf(7, 8, 9)
        val events = mutableListOf<String>()
        fun operand(name: String, action: () -> Any?) = object : Expr() {
            override fun execute(frame: VirtualFrame): Any? { events.add(name); return action() }
        }
        fun expression(fail: Boolean) = byteArrayExpression(ByteArrayOp.COPY, CoreRepresentation.UNKNOWN, arrayOf(
            operand("source") { source }, operand("sourceOffset") { 0L },
            operand("destination") { destination }, operand("destinationOffset") { 1L },
            operand("count") { 2L }, operand("state") {
                assertArrayEquals(byteArrayOf(7, 8, 9), destination)
                if (fail) throw RuntimeFault("state failed") else Unit
            }))
        assertThrows(RuntimeFault::class.java) { expression(true).execute(frame) }
        assertArrayEquals(byteArrayOf(7, 8, 9), destination)
        assertEquals(listOf("source", "sourceOffset", "destination", "destinationOffset", "count", "state"), events)
        events.clear()
        assertSame(Unit, expression(false).execute(frame))
        assertArrayEquals(byteArrayOf(7, 0, -1), destination)
        assertEquals(listOf("source", "sourceOffset", "destination", "destinationOffset", "count", "state"), events)
    }

    @Test fun copiedRangesAndAllOperandProofsAreCheckedInBothBackends() {
        val paths = (manifest()["stages"] as Map<String, List<String>>).getValue("pre")
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                fun fresh() = CoreModules.reachable(merged(paths), "copiedBytes")
                fun copy(module: Map<String, Any?>) = applications(module).first {
                    (it[1] as List<*>).take(2) == listOf("prim", "copyByteArray#")
                }
                for ((argument, values) in listOf(
                    1 to listOf(-1L, 5L, Long.MAX_VALUE), 3 to listOf(-1L, 7L, Long.MAX_VALUE),
                    4 to listOf(-1L, 5L, Long.MAX_VALUE))) for (value in values) {
                    val module = fresh()
                    val args = copy(module)[2] as MutableList<Any?>
                    args[argument] = listOf("lit", "int", value.toString(), CoreRepresentations.metadata(args[argument] as List<Any?>))
                    val function = context.asValue(EntryValue(program(language, module, backend), "copiedBytes", 1))
                    val failure = assertThrows(PolyglotException::class.java) { function.execute(0L) }
                    assertTrue(failure.message.orEmpty().contains("ByteArray# copy range"), "$backend/$argument/$value: $failure")
                }
                for (count in listOf(0L, 1L)) for (alias in listOf(false, true)) {
                    val aliasModule = fresh()
                    val aliasArgs = copy(aliasModule)[2] as MutableList<Any?>
                    // Either copy in the fixture may be visited first. Keep both
                    // ranges within the four-byte source when aliasing its destination.
                    for ((argument, value) in listOf(1 to 0L, 3 to 2L, 4 to count)) {
                        aliasArgs[argument] = listOf("lit", "int", value.toString(),
                            CoreRepresentations.metadata(aliasArgs[argument] as List<Any?>))
                    }
                    if (alias) aliasArgs[2] = aliasArgs[0]
                    val function = context.asValue(EntryValue(program(language, aliasModule, backend), "copiedBytes", 1))
                    if (alias) {
                        val failure = assertThrows(PolyglotException::class.java) { function.execute(0L) }
                        assertTrue(failure.message.orEmpty().contains("requires distinct source and destination"), "$backend/$count: $failure")
                    } else {
                        val expected = BigInteger.valueOf(mathematical("copiedBytes", 0L)) -
                            BigInteger.valueOf(33L * count) * BigInteger.valueOf(257).pow(6)
                        assertEquals(expected.toLong(), function.execute(0L).asLong(), "$backend/$count distinct arrays")
                    }
                }
                for (diagnostic in listOf(false, true)) {
                    val module = fresh()
                    val proof = CoreRepresentations.metadata(copy(module))!!["rep"] as MutableMap<String, Any?>
                    proof["kind"] = "unknown"; proof["aggregate"] = "unboxed-tuple"; proof["components"] = emptyList<Any?>()
                    assertThrows(RuntimeFault::class.java) { program(language, module + ("diagnosticUnsupported" to diagnostic), backend) }
                }
                for (argument in 0..5) for (diagnostic in listOf(false, true)) {
                    val module = fresh()
                    val args = copy(module)[2] as MutableList<Any?>
                    val proof = CoreRepresentations.metadata(args[argument] as List<Any?>)!!["rep"] as MutableMap<String, Any?>
                    proof["primReps"] = if (argument in listOf(0, 2)) listOf("BoxedRep (Just Lifted)") else listOf("DoubleRep")
                    if (argument !in listOf(0, 2)) proof["kind"] = "double"
                    assertThrows(RuntimeFault::class.java, {
                        program(language, module + ("diagnosticUnsupported" to diagnostic), backend)
                    }, "$backend/copy argument $argument/$diagnostic")
                }
            } finally { context.leave() }
        }
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
                        val guard = if (primitive == "newByteArray#") "Managed allocation size outside JVM domain"
                            else "Managed allocation range outside its backing storage"
                        assertEquals("${RuntimeFault::class.java.name}: $guard", failure.message, "$backend/$primitive/$value")
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
                for (operation in byteOperations) for (mutation in 0..6) for (diagnostic in listOf(false, true)) {
                    val module = CoreModules.reachable(merged(paths), if (operation == ByteArrayOp.COPY) "copiedBytes" else "orderedBytes")
                    val app = applications(module).first { (it[1] as List<*>).take(2) == listOf("prim", operation.primitive) }
                    val args = app[2] as MutableList<Any?>
                    val flags = app[3] as MutableList<Any?>
                    val metadata = CoreRepresentations.metadata(app) as MutableMap<String, Any?>
                    when (mutation) {
                        0 -> { args.removeAt(args.lastIndex); flags.removeAt(flags.lastIndex); metadata.remove("callDemand") }
                        1 -> { args.add(args[0]); flags.add(false); metadata.remove("callDemand") }
                        2 -> metadata.remove("rep")
                        3 -> { val proof = metadata["rep"] as MutableMap<String, Any?>
                            proof["primReps"] = listOf("DoubleRep"); proof["kind"] = "double"
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
                for (operation in byteOperations) {
                    val module = CoreModules.reachable(merged(paths), if (operation == ByteArrayOp.COPY) "copiedBytes" else "orderedBytes")
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
