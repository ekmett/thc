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
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class ManagedAddressReadTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val nativeEntries = linkedMapOf("word32Read" to 4, "wordRead" to 8,
        "int32Read" to 4, "intRead" to 8)
    private val nativeSeeds = listOf(Long.MIN_VALUE, -4294967296L, -2147483649L,
        -2147483648L, -1L, 0L, 1L, 127L, 128L, 255L, 256L, 2147483647L,
        2147483648L, 4294967295L, Long.MAX_VALUE)
    private fun nativeModel(row: List<String>): Long {
        val (name, rawText, baseText, offsetText) = row
        val raw = rawText.toLong()
        val width = nativeEntries.getValue(name)
        val start = baseText.toInt() + offsetText.toInt() * width
        val bytes = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder())
        for (word in listOf(raw, raw xor 0x0123456789abcdefL, raw.inv(),
            raw xor 0xaaaaaaaaaaaaaaaaUL.toLong())) bytes.putLong(word)
        fun read(): Long = when (name) {
            "word32Read" -> bytes.getInt(start).toLong() and 0xffffffffL
            "wordRead", "intRead" -> bytes.getLong(start)
            "int32Read" -> bytes.getInt(start).toLong()
            else -> error("Unknown native address read $name")
        }
        val before = read()
        bytes.put(start, (raw + 173).toByte())
        return 3 * before + 5 * read()
    }
    private val operations = listOf(PinnedMemoryOp.READ_WORD16, PinnedMemoryOp.READ_INT16,
        PinnedMemoryOp.READ_WORD32, PinnedMemoryOp.READ_WORD,
        PinnedMemoryOp.READ_INT32, PinnedMemoryOp.READ_INT)
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target)
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.results.depth)
        assertEquals(0, state.arguments.retainedReferences()); assertEquals(0, state.results.retainedReferences())
    }

    @Test fun genuineGhcNativeOracleMatchesBothBackends() {
        val manifest = Json.parse(File(root, "build/managed-address-reads/manifest.json").readText()) as Map<String, Any?>
        assertEquals(true, manifest["strictAccepted"])
        assertEquals(8L, manifest["strictAudits"])
        assertEquals(64L, manifest["wordBits"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[kind] as Map<String, String>) {
                val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, actual, "Stale address-read evidence: $path")
            }
        val rows = File(root, "build/managed-address-reads/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(nativeEntries.keys, (manifest["entries"] as List<String>).toSet())
        assertEquals(nativeEntries.keys, rows.keys)
        assertEquals(1800, rows.values.sumOf { it.size })
        assertEquals(1800L, manifest["nativeRows"])
        val requests = nativeEntries.flatMap { (name, width) -> nativeSeeds.flatMap { raw ->
            (0..32 step 8).flatMap { base -> (0..32 - width step width).map { start ->
                listOf(name, raw.toString(), base.toString(), ((start - base) / width).toString())
            } }
        } }
        assertEquals(requests, File(root, "build/managed-address-reads/requests.tsv").readLines().map { it.split('\t') })
        val orderedRows = File(root, "build/managed-address-reads/oracle.tsv").readLines().map { it.split('\t') }
        for ((request, row) in requests.zip(orderedRows)) {
            assertEquals(request, row.take(4))
            assertEquals(nativeModel(request), row[4].toLong(), "Native address read $row")
        }
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) {
            val source = CoreModules.merge(paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> })
            val fixture = paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> }
                .single { it["module"] == "ManagedAddressReadAudit" }
            assertEquals(if (stage == "pre") "optimized-Core-before-Tidy" else
                "optimized-Core-after-Tidy-before-CorePrep", fixture["boundary"])
            for ((name, primitive) in nativeEntries.keys.zip(listOf("readWord32OffAddr#",
                "readWordOffAddr#", "readInt32OffAddr#", "readIntOffAddr#"))) {
                val report = Json.parse(File(root, "build/managed-address-reads/$stage-$name.audit.json").readText()) as Map<String, Any?>
                assertEquals(true, report["accepted"], "$stage/$name")
                assertTrue((report["primitives"] as List<Map<String, Any?>>).any { it["name"] == primitive }, "$stage/$name")
            }
            for ((name, cases) in rows) for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val runtime = program(language, CoreModules.reachable(source, name) + ("instrument" to true), backend)
                    val function = context.asValue(EntryValue(runtime, name, 3))
                    fun check(row: List<String>) {
                        assertEquals(row.last().toLong(), function.execute(*row.subList(1, 4).map { it.toLong() }.toTypedArray()).asLong(), "$stage/$backend/$row")
                        released(language)
                    }
                    cases.forEach(::check)
                    assertTrue(function.invokeMember("compile").asBoolean())
                    for (row in cases.asReversed()) {
                        val before = (runtime.diagnostics().getValue("compiledEntries") as Number).toLong()
                        check(row)
                        assertTrue((runtime.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                        valid(runtime.hostEntryTarget(3)); valid(runtime.entryTarget(name))
                    }
                    assertEquals(0L, (runtime.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    println("ManagedAddressRead PASS $stage/$backend/$name rows=${cases.size}")
                } finally { context.leave() }
            }
        }
    }

    private fun expected(operation: ManagedAddressRead, bytes: ByteArray, start: Int): Long {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        return when (operation) {
            ManagedAddressRead.WORD16 -> buffer.getShort(start).toLong() and 0xffffL
            ManagedAddressRead.INT16 -> buffer.getShort(start).toLong()
            ManagedAddressRead.WORD32, ManagedAddressRead.WIDE_CHAR ->
                buffer.getInt(start).toLong() and 0xffffffffL
            ManagedAddressRead.INT32 -> buffer.getInt(start).toLong()
            ManagedAddressRead.WORD, ManagedAddressRead.INT,
            ManagedAddressRead.WORD64, ManagedAddressRead.INT64 -> buffer.getLong(start)
        }
    }

    @Test fun fullWidthBoundsNegativeDerivedOffsetsAndOverflow() {
        val bytes = ByteArray(32) { (it * 37 + 129).toByte() }
        for (operation in ManagedAddressRead.entries) {
            val width = operation.width
            for (base in 0..bytes.size) {
                val address = ManagedAddress.fromByteArray(bytes).plus(base.toLong())
                for (offset in -9L..9L) {
                    val start = base + offset * width
                    if (start >= 0 && start + width <= bytes.size)
                        assertEquals(expected(operation, bytes, start.toInt()), operation.read(address, offset), "$operation/$base/$offset")
                    else assertThrows(RuntimeFault::class.java) { operation.read(address, offset) }
                }
                for (offset in listOf(Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE / width - 1,
                    Long.MAX_VALUE / width + 1, 1L shl 32, -(1L shl 32)))
                    assertThrows(RuntimeFault::class.java) { operation.read(address, offset) }
            }
            assertThrows(RuntimeFault::class.java) { operation.read(ManagedAddress.fromByteArray(ByteArray(width - 1)), 0) }
            assertThrows(RuntimeFault::class.java) { operation.read(ManagedAddress.fromByteArray(ByteArray(0)), 0) }
        }
        val allOnes = ManagedAddress.fromHex("ffffffffffffffff")
        assertEquals(65535L, ManagedAddressRead.WORD16.read(allOnes, 0))
        assertEquals(-1L, ManagedAddressRead.INT16.read(allOnes, 0))
        assertEquals(4294967295L, ManagedAddressRead.WORD32.read(allOnes, 0))
        assertEquals(4294967295L, ManagedAddressRead.WIDE_CHAR.read(allOnes, 0))
        assertEquals(-1L, ManagedAddressRead.INT32.read(allOnes, 0))
        assertEquals(-1L, ManagedAddressRead.WORD.read(allOnes, 0))
        assertEquals(-1L, ManagedAddressRead.INT.read(allOnes, 0))
    }

    @Test fun stateValidationPrecedesReadsAndFailedReadsDoNotPublish() {
        val descriptor = FrameDescriptor.newBuilder().apply { repeat(2) { addSlot(FrameSlotKind.Long, null, null) } }.build()
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
        for (operation in operations) for (mode in listOf("ok", "state-throws", "bad-state", "bounds")) {
            val bytes = ByteArray(16) { 127 }
            val events = mutableListOf<String>()
            FrameAccess.writeLong(frame, 0, 17L); FrameAccess.writeLong(frame, 1, 91L)
            fun operand(name: String, value: Any?) = object : Expr() {
                override fun execute(frame: VirtualFrame): Any? { events.add(name); return value }
            }
            val state = object : Expr() {
                override fun execute(frame: VirtualFrame): Any {
                    events.add("state")
                    assertEquals(91L, frame.getLong(1))
                    if (mode == "state-throws") throw RuntimeFault("failed state")
                    bytes[0] = 0 // A read before State would observe the old byte.
                    return if (mode == "bad-state") 1L else Unit
                }
            }
            val expression = PinnedMemoryExpression(operation, CoreRepresentation.UNKNOWN, arrayOf(
                operand("address", ManagedAddress.fromByteArray(bytes)), operand("offset", if (mode == "bounds") 17L else 0L), state))
            if (mode == "ok") {
                expression.executeTuple(frame, intArrayOf(0, 1), 1)
                assertEquals(expected(operation.addressRead!!, bytes, 0), frame.getLong(1))
            } else {
                assertThrows(RuntimeFault::class.java) { expression.executeTuple(frame, intArrayOf(0, 1), 1) }
                assertEquals(91L, frame.getLong(1))
            }
            assertEquals(17L, frame.getLong(0))
            assertEquals(listOf("address", "offset", "state"), events)
        }
    }

    private fun scalar(kind: String, vararg reps: String) = mapOf("kind" to kind, "primReps" to reps.toList(), "evaluated" to true)
    private val address = scalar("address", "AddrRep")
    private val integer = scalar("long", "IntRep")
    private val state = scalar("void")
    private val closure = scalar("closure", "BoxedRep (Just Lifted)")
    private fun synthetic(operation: PinnedMemoryOp): Map<String, Any?> {
        val payload = scalar("long", operation.addressRead!!.payload)
        val tuple = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple", "evaluated" to true,
            "primReps" to payload["primReps"], "components" to listOf(state, payload))
        val parameters = listOf(address, integer, state).mapIndexed { i, proof -> mapOf("id" to "p$i", "lifted" to false, "rep" to proof) }
        val call = listOf("app", listOf("prim", operation.primitive), parameters.map { listOf("var", it["id"], mapOf("rep" to it["rep"])) },
            listOf(false, false, false), false, false, mapOf("rep" to tuple))
        val fields = listOf(mapOf("id" to "s", "lifted" to false, "rep" to state), mapOf("id" to "n", "lifted" to false, "rep" to payload))
        val body = listOf("case", call, "pair", listOf(listOf("data", "T2", listOf("s", "n"),
            listOf("var", "n", mapOf("rep" to payload)), mapOf("binders" to fields))),
            mapOf("rep" to payload, "binder" to mapOf("id" to "pair", "lifted" to false, "rep" to tuple)))
        return mapOf("instrument" to true, "constructors" to listOf(mapOf("id" to "T2", "kind" to "unboxed-tuple", "arity" to 2, "tag" to 1)),
            "bindings" to listOf(mapOf("id" to "read", "name" to "read", "arity" to 3, "lifted" to true, "rep" to closure,
                "expr" to listOf("lam", parameters, body, mapOf("rep" to closure, "resultRep" to payload)))))
    }

    @Test fun compiledReadsObserveMutationsAndRejectWrongCarriersInBothBackends() {
        for (backend in listOf("ast", "bytecode")) for (operation in operations) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val runtime = program(language, synthetic(operation), backend)
                val target = runtime.entryTarget("read")
                fun call(value: Any?, offset: Any? = 0L, token: Any? = Unit) = Calls.target(target, arrayOf(0L, value, offset, token))
                val bytes = ByteArray(16)
                val derived = ManagedAddress.fromByteArray(bytes).plus(8)
                for (v in 0..255) { bytes[0] = v.toByte(); call(derived, -8L / operation.addressRead!!.width) }
                compile(target)
                for (v in 255 downTo 0) {
                    bytes.fill(v.toByte())
                    assertEquals(expected(operation.addressRead!!, bytes, 0), call(derived, -8L / operation.addressRead!!.width))
                    valid(target); released(language)
                }
                for (bad in listOf<Any?>(null, 0L, Any(), bytes)) assertThrows(RuntimeFault::class.java) { call(bad) }
                for (bad in listOf<Any?>(null, 0L, Any())) assertThrows(RuntimeFault::class.java) { call(derived, 0L, bad) }
                for (offset in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 1L shl 32,
                    -8L / operation.addressRead!!.width - 1))
                    assertThrows(RuntimeFault::class.java) { call(derived, offset) }
                assertEquals(expected(operation.addressRead!!, bytes, 8), call(derived))
                released(language)
            } finally { context.leave() }
        }
    }

    private fun applications(value: Any?): List<MutableList<Any?>> = when (value) {
        is List<*> -> (if (value.firstOrNull() == "app") listOf(value as MutableList<Any?>) else emptyList()) + value.flatMap(::applications)
        is Map<*, *> -> value.values.flatMap(::applications)
        else -> emptyList()
    }

    @Test fun bothLoadersRejectForgedArgumentsAndTuplePayloads() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (operation in operations) for (mutation in 0..7) for (diagnostic in listOf(false, true)) {
                    val module = Json.parse(Json.stringify(synthetic(operation))) as MutableMap<String, Any?>
                    val call = applications(module).single()
                    val args = call[2] as MutableList<MutableList<Any?>>
                    val flags = call[3] as MutableList<Any?>
                    val metadata = call.last() as MutableMap<String, Any?>
                    when (mutation) {
                        0, 1, 2 -> (args[mutation].last() as MutableMap<String, Any?>)["rep"] = scalar("long", "WordRep")
                        3 -> flags[0] = true
                        4 -> { args.removeAt(2); flags.removeAt(2) }
                        5 -> metadata["rep"] = integer
                        6 -> {
                            val tuple = metadata["rep"] as MutableMap<String, Any?>
                            (tuple["components"] as MutableList<Any?>)[1] = scalar("long", "Word8Rep")
                            tuple["primReps"] = listOf("Word8Rep")
                        }
                        7 -> {
                            val tuple = metadata["rep"] as MutableMap<String, Any?>
                            (tuple["components"] as MutableList<Any?>)[0] = integer
                            tuple["primReps"] = listOf("IntRep", operation.addressRead!!.payload)
                        }
                    }
                    assertThrows(RuntimeFault::class.java, { program(language, module + ("diagnosticUnsupported" to diagnostic), backend) }, "$backend/$operation/$mutation")
                }
            } finally { context.leave() }
        }
    }
}
