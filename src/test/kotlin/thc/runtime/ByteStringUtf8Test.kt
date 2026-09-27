// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.util.HexFormat

class ByteStringUtf8Test {
    @org.junit.jupiter.api.BeforeEach fun supportedHost() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name") == "Linux" &&
            System.getProperty("os.arch") in setOf("amd64", "x86_64"))
    }
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/bytestring-utf8"
    private fun json(path: String) = Json.parse(File(root, path).readText())
    private fun module(stage: String) = CoreModules.merge(listOf("ByteStringUtf8Audit", "THC.InterfaceClosure")
        .map { json("$prefix/$stage/core/$it.json") as Map<String, Any?> })
    private fun rows(): List<Map<String, Any?>> {
        val manifest = json("$prefix/manifest.json") as Map<String, Any?>
        assertEquals(true, manifest["strictAccepted"])
        assertEquals(800L, manifest["nativeRows"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/ByteStringUtf8Audit.hs", "compiler/test-fixtures/ByteStringUtf8Native.hs",
            "test/haskell-fixtures/ByteStringUtf8Fixtures.hs", "scripts/core_original_foreign.py"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json") +
            listOf("pre", "post").flatMap { stage -> listOf(
                "$prefix/$stage/core/ByteStringUtf8Audit.json", "$prefix/$stage/core/THC.InterfaceClosure.json") }, "$prefix/")
        val rows = json("$prefix/oracle.json") as List<Map<String, Any?>>
        assertEquals(mapOf("validateUnsafe" to 400, "validateSafe" to 400), rows.groupingBy { it["entry"] }.eachCount())
        for (stage in listOf("pre", "post")) for (entry in listOf("validateUnsafe", "validateSafe")) {
            val audit = json("$prefix/$stage/$entry.audit.json") as Map<String, Any?>
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any?>(), audit["issues"])
            assertEquals(emptyList<Any?>(), audit["missingGlobals"])
        }
        return rows
    }
    private fun context() = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun <T> inside(block: (Language) -> T): T = context().use { context ->
        context.initialize("thc"); context.enter()
        try { block(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun load(language: Language, backend: String, source: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, source, true) else BytecodeProgram(language, source, true)
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target)
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        val runtime = Truffle.getRuntime()
        runtime.javaClass.getMethod("bypassedInstalledCode", type).invoke(runtime, target)
    }
    private fun address(values: Any?, kind: Int): ManagedAddress {
        val bytes = (values as List<Long>).map(Long::toByte).toByteArray()
        return when (kind) {
            0 -> ManagedAddress.fromByteArray(bytes)
            3 -> ManagedAddress.fromHex(HexFormat.of().formatHex(bytes))
            else -> ManagedAddress.fromAllocation(ManagedAllocation.mutable(bytes.size.toLong(), 8, pinned = kind == 2))
                .also { result -> bytes.forEachIndexed { index, byte -> result.writeWord8(index.toLong(), byte.toLong()) } }
        }
    }

    private fun model(bytes: List<Long>, offset: Int, length: Int): Long = try {
        java.nio.charset.StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes.map(Long::toByte).toByteArray(), offset, length))
        1L
    } catch (_: java.nio.charset.CharacterCodingException) { 0L }

    @Test fun originalSafeAndUnsafeCallsMatchNativeAndJvmOnFirstCompiledCalls() {
        val rows = rows()
        rows.forEach { row -> assertEquals(row["result"], model(row["bytes"] as List<Long>,
            (row["offset"] as Long).toInt(), (row["count"] as Long).toInt())) }
        for (stage in listOf("pre", "post")) {
            val source = module(stage)
            assertEquals(setOf(false, true), OriginalStdioChecks.foreignCalls(source).map { call ->
                val metadata = call[6] as Map<*, *>
                CoreByteStringUtf8Foreign.validate(metadata, (call[2] as List<List<Any?>>).map {
                    CoreRepresentations.metadata(it)?.get("rep") }, call[3] as List<*>, metadata["rep"])
            }.toSet())
            for (backend in listOf("ast", "bytecode")) inside { language ->
                for ((entry, examples) in rows.groupBy { it["entry"] as String }) {
                    val program = load(language, backend, CoreModules.reachable(source, entry) + ("instrument" to true))
                    val target = program.entryTarget(entry)
                    fun exercise(compiled: Boolean) {
                        for (kind in 0..3) for ((index, row) in examples.withIndex()) {
                            val base = address(row["bytes"], kind)
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            val result = Calls.target(target, arrayOf(0L, base.plus(row["offset"] as Long), row["count"]))
                            val label = "$stage/$backend/$entry/storage=$kind/$index/compiled=$compiled"
                            assertEquals(row["result"], result, label)
                            (row["bytes"] as List<Long>).forEachIndexed { i, byte ->
                                assertEquals(byte, base.readWord8(i.toLong()), "$label input is unchanged")
                            }
                            if (compiled) {
                                assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong(), label)
                                valid(target)
                            }
                            val handoff = language.handoffState.get()
                            assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                            assertEquals(0, handoff.results.retainedReferences())
                        }
                    }
                    exercise(false); compile(target); exercise(true)
                }
            }
        }
    }

    @Test fun boundsNullPointerCellsShrinksAndNativeOwnersAreChecked() = inside {
        val nullAddress = ManagedAddress.nullAddress()
        assertEquals(1L, ManagedByteStringUtf8.validate(nullAddress, 0))
        assertThrows(RuntimeFault::class.java) { ManagedByteStringUtf8.validate(nullAddress, 1) }
        val base = ManagedAddress.fromByteArray(byteArrayOf(65, 66, 67))
        for (count in listOf(-1L, 4L, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { ManagedByteStringUtf8.validate(base, count) }
        assertEquals(1L, ManagedByteStringUtf8.validate(base.plus(3), 0))
        assertThrows(RuntimeFault::class.java) { ManagedByteStringUtf8.validate(base.plus(3), 1) }
        val cells = ManagedAddress.fromAllocation(ManagedAllocation.mutable(16, 8))
        cells.writeAddressElementIndex(1, base)
        assertThrows(RuntimeFault::class.java) { ManagedByteStringUtf8.validate(cells, 16) }
        val allocation = ManagedAllocation.mutable(16, 8)
        val shrunk = ManagedAddress.fromAllocation(allocation).plus(9)
        allocation.shrink(8)
        assertThrows(RuntimeFault::class.java) { ManagedByteStringUtf8.validate(shrunk, 0) }
        val native = Language.currentState().nativeAllocations.malloc(8)
        repeat(8) { native.writeWord8(it.toLong(), 65) }
        assertEquals(1L, ManagedByteStringUtf8.validate(native.plus(1), 7))
        native.writeWord8(7, 255)
        assertEquals(0L, ManagedByteStringUtf8.validate(native.plus(1), 7))
        inside { assertThrows(RuntimeFault::class.java) { ManagedByteStringUtf8.validate(native, 0) } }
        Language.currentState().nativeAllocations.free(native)
        assertThrows(RuntimeFault::class.java) { ManagedByteStringUtf8.validate(native, 0) }
        Unit
    }

    @Test fun closedOriginalDescriptorRejectsOtherPackagesByteArraysAndUnsafeCarriers() {
        val source = module("pre")
        for (backend in listOf("ast", "bytecode")) inside { language ->
            for (original in OriginalStdioChecks.foreignCalls(source)) {
                val raw = OriginalStdioChecks.rawModule(original, source)
                for (variant in 0..8) {
                    val bad = Json.parse(Json.stringify(raw)) as Map<String, Any?>
                    val call = OriginalStdioChecks.foreignCalls(bad).single() as MutableList<Any?>
                    val descriptor = (call[6] as Map<*, *>)["foreignCall"] as MutableMap<String, Any?>
                    when (variant) {
                        0 -> descriptor["safety"] = "interruptible"
                        1 -> descriptor["convention"] = "capi"
                        2 -> descriptor["arity"] = 4L
                        3 -> (descriptor["target"] as MutableMap<String, Any?>)["unit"] = "ghc-internal"
                        4 -> (call[3] as MutableList<Any?>)[0] = true
                        5 -> (call[1] as MutableList<Any?>)[1] = "entry"
                        6 -> (descriptor["argumentReps"] as MutableList<Any?>)[1] = OriginalStdioFixtures.scalar("IntRep", false)
                        7 -> (descriptor["argumentReps"] as MutableList<Any?>)[0] = OriginalStdioFixtures.scalar("BoxedRep (Just Unlifted)", false)
                        8 -> (descriptor["target"] as MutableMap<String, Any?>)["unit"] = "bytestring-0.12.1.0-inplace"
                    }
                    assertThrows(RuntimeFault::class.java, { load(language, backend, bad) }, "$backend/$variant")
                }
                val target = load(language, backend, raw).entryTarget("entry")
                val bytes = ManagedAddress.fromByteArray(byteArrayOf(65))
                assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, bytes, 1L, 17L)) }
                assertEquals(1, Calls.target(target, arrayOf(0L, bytes, 1L, Unit)))
            }
        }
    }

    @Test fun shadowedJoinCannotClaimOriginalUtf8ForeignAuthority() {
        for (stage in listOf("pre", "post")) {
            val source = module(stage)
            for (backend in listOf("ast", "bytecode")) inside { language ->
                for (original in OriginalStdioChecks.foreignCalls(source)) {
                    fun shadowed(claimForeign: Boolean): Map<String, Any?> {
                        val raw = Json.parse(Json.stringify(OriginalStdioChecks.rawModule(original, source)))
                            as Map<String, Any?>
                        val entry = (raw["bindings"] as List<Map<String, Any?>>).single()
                        val lambda = entry["expr"] as List<Any?>
                        val body = lambda[2] as MutableList<Any?>
                        val call = body[1] as MutableList<Any?>
                        val metadata = call[6] as Map<String, Any?>
                        val tuple = metadata["rep"] as Map<String, Any?>
                        val fields = tuple["components"] as List<Map<String, Any?>>
                        val operands = call[2] as List<List<Any?>>
                        val closure = OriginalStdioFixtures.closure()
                        val formals = operands.mapIndexed { index, operand ->
                            mapOf("id" to "join$index", "lifted" to false,
                                "rep" to CoreRepresentations.metadata(operand)!!.getValue("rep"))
                        }
                        val pair = listOf("app", listOf("con", "T2", 2, mapOf("rep" to closure)),
                            listOf(listOf("var", "join2", mapOf("rep" to fields[0])),
                                listOf("lit", "int32", "37", mapOf("rep" to fields[1]))),
                            listOf(false, false), false, false, mapOf("rep" to (tuple + ("evaluated" to true))))
                        val join = mapOf("id" to (call[1] as List<*>)[1], "name" to "shadowedUtf8",
                            "lifted" to true, "rep" to closure,
                            "expr" to listOf("lam", formals, pair, mapOf("rep" to closure, "resultRep" to tuple)),
                            "joinValueArity" to 3L, "joinResultRep" to tuple, "info" to mapOf("joinArity" to 3L))
                        if (!claimForeign) call[6] = metadata - "foreignCall"
                        // The same saturated, tuple-returning lexical join is valid
                        // without a copied original foreign-call descriptor.
                        CoreJoins.validate(listOf(join), call, false)
                        body[1] = listOf("let", false, listOf(join), call, mapOf("rep" to tuple))
                        return raw
                    }
                    val ordinary = load(language, backend, shadowed(false)).entryTarget("entry")
                    assertEquals(37, Calls.target(ordinary, arrayOf(0L,
                        ManagedAddress.fromByteArray(byteArrayOf(-1)), 1L, Unit)),
                        "$stage/$backend ordinary lexical join keeps its result")
                    val failure = assertThrows(RuntimeFault::class.java) {
                        load(language, backend, shadowed(true))
                    }
                    assertTrue(failure.message.orEmpty().contains("unresolved original FCallId required"),
                        "$stage/$backend rejects the shadowed head specifically: " + failure.message)
                }
            }
        }
    }

    @Test fun nativeAuthorityIsRequiredEvenForEmptyInput() {
        Context.newBuilder("thc").allowNativeAccess(false).build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                assertThrows(RuntimeFault::class.java) {
                    ManagedByteStringUtf8.validate(ManagedAddress.nullAddress(), 0)
                }
            } finally { context.leave() }
        }
    }

    @Test fun safeAstReturnSavesCompletedResultWithoutReplayingAndUnsafeDoesNotPoll() = inside { language ->
        val state = Language.currentState()
        val identity = state.threads.enterCurrent()
        try {
            for (safe in listOf(false, true)) {
                val original = OriginalStdioChecks.foreignCalls(module("pre")).first { call ->
                    ((call[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["safety"] == if (safe) "safe" else "unsafe"
                }
                val proof = CoreRepresentations.parse((original[6] as Map<*, *>)["rep"])
                val layout = FrameLayout()
                val slots = intArrayOf(layout.bind("completed CInt"))
                val shape = TupleShape(proof, language)
                val bytes = ManagedAddress.fromByteArray(byteArrayOf(65))
                var pending: AsyncRequest? = null
                var evaluated = 0
                val source = object : Expr() {
                    override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): Any = bytes
                }
                val length = object : Expr() {
                    override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): Any = 1L
                }
                val enqueue = object : Expr() {
                    override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): Any {
                        evaluated++
                        pending = state.threads.send(identity, "after completed validation")
                        return Unit
                    }
                }
                val body = ByteStringUtf8Expression(safe, arrayOf(source, length, enqueue), proof)
                val root = FunctionRoot(language, layout.build(), "UTF-8 completion control", null,
                    intArrayOf(), intArrayOf(), intArrayOf(), body,
                    Metrics(false), emptyArray(), body.representation, body.coreSourceLocation,
                    booleanArrayOf(), null, shape, slots,
                    null, true, emptyArray(), false,
                    FunctionRootRole.FUNCTION, false)
                val result = Calls.target(root.callTarget, arrayOf(0L))
                val completed = if (safe) {
                    val saved = checkNotNull(SavedGuestContinuationKt.savedGuestContinuation(result))
                    assertSame(pending, saved.asyncRequest())
                    pending!!.acknowledge()
                    // A replay or a poll before native completion would now
                    // return zero. The saved completed result must remain one.
                    bytes.writeWord8(0, 255)
                    saved.continueWith(Unit)
                } else {
                    assertEquals(AsyncRequestState.PENDING, pending!!.state)
                    assertSame(pending, state.threads.poll(root))
                    pending!!.acknowledge()
                    result
                }
                assertEquals(1, shape.layout.getInt(ownedTupleResult(completed, shape), 0))
                assertEquals(1, evaluated)
                assertEquals(0, language.handoffState.get().results.depth)
                assertEquals(0, language.handoffState.get().results.retainedReferences())
            }
        } finally { state.threads.leaveCurrent() }
    }
}
