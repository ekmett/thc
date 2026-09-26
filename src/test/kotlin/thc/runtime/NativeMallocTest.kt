// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.interop.InteropLibrary
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json
import thc.Language
import java.io.File
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Genuine declaration certificates in explicitly synthetic scalar consumers. */
class NativeMallocTest {
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private fun declarations() = Json.parse(javaClass.getResource("/core/original-malloc-descriptors.json")!!.readText())
        as List<Map<String, Any?>>
    private fun symbol(call: Map<String, Any?>) = (call.getValue("target") as Map<String, Any?>).getValue("symbol") as String
    private fun module(calls: List<Map<String, Any?>> = declarations(),
        mutate: (MutableMap<String, Any?>) -> Unit = {}): Map<String, Any?> {
        val bindings = calls.map { declaration ->
            val name = symbol(declaration)
            val tuple = declaration.getValue("resultRep") as Map<String, Any?>
            val components = tuple.getValue("components") as List<Map<String, Any?>>
            val formals = (declaration.getValue("argumentReps") as List<Map<String, Any?>>).mapIndexed { i, rep ->
                mapOf("id" to "$name-arg-$i", "lifted" to false, "rep" to (rep + ("evaluated" to true)))
            }
            val call = listOf("app", listOf("var", "$name-synthetic-fcall-id", mapOf("rep" to closure)),
                formals.map { listOf("var", it["id"], mapOf("rep" to it["rep"])) }, formals.map { false },
                false, false, mapOf("rep" to tuple, "foreignCall" to declaration))
            val ids = components.indices.map { "$name-field-$it" }
            val output = if (components.size == 1) long else components.last()
            val result = if (components.size == 1) listOf("lit", "int", "23", mapOf("rep" to long))
                else listOf("var", ids.last(), mapOf("rep" to output))
            val ctor = if (components.size == 1) "tuple1" else "tuple2"
            val body = listOf("case", call, "$name-tuple", listOf(listOf("data", ctor, ids, result,
                mapOf("binders" to components.mapIndexed { i, rep -> mapOf("id" to ids[i], "lifted" to false, "rep" to rep) }))),
                mapOf("rep" to output, "binder" to mapOf("id" to "$name-tuple", "lifted" to false, "rep" to (tuple + ("evaluated" to true)))))
            mutableMapOf<String, Any?>("id" to name, "name" to name, "arity" to formals.size, "lifted" to true,
                "rep" to closure, "expr" to listOf("lam", formals, body, mapOf("rep" to closure, "resultRep" to output))).also(mutate)
        }
        return mapOf("schema" to 1, "module" to "SyntheticMallocConsumers", "unit" to "test", "ghc" to "9.14.1",
            "instrument" to true, "bindings" to bindings + memoryBindings(), "constructors" to listOf(
                mapOf("id" to "tuple1", "name" to "Solo#", "kind" to "unboxed-tuple", "arity" to 1, "tag" to 1),
                mapOf("id" to "tuple2", "name" to "(#,#)", "kind" to "unboxed-tuple", "arity" to 2, "tag" to 1)))
    }
    private fun memoryBindings(): List<Map<String, Any?>> {
        fun rep(primitive: String?) = mapOf("kind" to when (primitive) {
            null -> "void"; "AddrRep" -> "address"; else -> "long"
        }, "primReps" to listOfNotNull(primitive), "evaluated" to true)
        fun binding(name: String, primitive: String, arguments: List<String?>, output: String?): Map<String, Any?> {
            val formals = arguments.mapIndexed { i, arg -> mapOf("id" to "$name-$i", "lifted" to false, "rep" to rep(arg)) }
            val result = rep(output)
            val call = listOf("app", listOf("prim", primitive), formals.map {
                listOf("var", it["id"], mapOf("rep" to it["rep"])) }, List(arguments.size) { false }, false, false,
                mapOf("rep" to result))
            val body = if (output != null) call else listOf("case", call, "$name-state",
                listOf(listOf("default", null, emptyList<String>(), listOf("lit", "int", "23", mapOf("rep" to long)))),
                mapOf("rep" to long, "binder" to mapOf("id" to "$name-state", "lifted" to false, "rep" to result)))
            return mapOf("id" to name, "name" to name, "arity" to arguments.size, "lifted" to true, "rep" to closure,
                "expr" to listOf("lam", formals, body, mapOf("rep" to closure, "resultRep" to if (output == null) long else result)))
        }
        return listOf(
            binding("store", "writeWord64OffAddr#", listOf("AddrRep", "IntRep", "Word64Rep", null), null),
            binding("load", "indexWord64OffAddr#", listOf("AddrRep", "IntRep"), "Word64Rep"),
            binding("copy", "copyAddrToAddrNonOverlapping#", listOf("AddrRep", "AddrRep", "IntRep", null), null))
    }
    private fun load(language: Language, backend: String, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun released(language: Language) {
        val handoff = language.handoffState.get()
        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
    }

    private fun supported() = assumeTrue(System.getProperty("os.name") == "Linux" &&
        System.getProperty("os.arch") in setOf("amd64", "x86_64"))
    private fun <T> inside(body: (Language) -> T): T {
        supported()
        return context(false).use { context ->
            context.initialize("thc"); context.enter()
            try { body(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }
    }

    @Test fun reallocPreservesPrefixAndInvalidatesOldAliasesOnBothInstalledBackends() {
        // Synthetic consumer; EnvironmentFullCore audits and executes the real
        // installed GHC realloc declaration through System.Environment.setEnv.
        val originals = declarations()
        val declaration = originals.first() + mapOf("target" to
            ((originals.first().getValue("target") as Map<String, Any?>) + ("symbol" to "realloc")),
            "arity" to 3, "suppliedArity" to 3, "argumentReps" to
                listOf((originals.last().getValue("argumentReps") as List<*>).first()) +
                (originals.first().getValue("argumentReps") as List<*>))
        for (backend in listOf("ast", "bytecode")) inside { language ->
            val registry = Language.currentState().nativeAllocations
            val program = load(language, backend, module(listOf(declaration)))
            val target = program.entryTarget("realloc")
            var compiled = false
            fun resize(address: ManagedAddress, size: Long): ManagedAddress {
                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                val result = Calls.target(target, arrayOf(0L, address, size, Unit)) as ManagedAddress
                assertEquals(before + if (compiled) 1 else 0,
                    (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                if (compiled) valid(target)
                released(language)
                return result
            }
            fun exercise() {
                val original = resize(ManagedAddress.nullAddress(), 8)
                for (index in 0L until 8) original.writeWord8(index, index + 17)
                val grown = resize(original, 32)
                assertThrows(RuntimeFault::class.java) { original.readWord8(0) }
                assertEquals((17L until 25).toList(), (0L until 8).map(grown::readWord8))
                val shrunk = resize(grown, 3)
                assertThrows(RuntimeFault::class.java) { grown.readWord8(0) }
                assertEquals(listOf(17L, 18L, 19L), (0L until 3).map(shrunk::readWord8))
                assertSame(ManagedAddress.nullAddress(), resize(shrunk, Long.MAX_VALUE))
                assertEquals(18L, shrunk.readWord8(1))
                assertEquals(1, registry.liveCount())
                assertSame(ManagedAddress.nullAddress(), resize(shrunk, 0))
                assertThrows(RuntimeFault::class.java) { shrunk.readWord8(0) }
                assertEquals(0, registry.liveCount())
            }
            exercise()
            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
            valid(target); compiled = true
            exercise()
            val live = registry.malloc(8)
            for (bad in listOf(live.plus(1), ManagedAddress.fromHex("41"), ManagedAddress.unownedNumeric(live.toNativeBits())))
                assertThrows(RuntimeFault::class.java) { registry.realloc(bad, 16) }
            assertThrows(RuntimeFault::class.java) { registry.realloc(live, -1) }
            live.withNativeBorrow { assertThrows(RuntimeFault::class.java) { registry.realloc(live, 16) } }
            registry.free(live)
        }
    }

    @Test fun originalDeclarationsExecuteOnFirstInstalledAstAndBytecodeEntries() {
        supported()
        // Descriptors are unmodified core/122.json from the retained original
        // ghc-internal module, SHA256 8606e3a7f8c0089f071cf3fd9bc2e6c8374c9ff44433ce32ae3b043db45a175a.
        for (backend in listOf("ast", "bytecode")) inside { language ->
            val root = File(System.getProperty("thc.projectRoot"))
            val manifest = Json.parse(File(root, "build/native-malloc/manifest.json").readText()) as Map<String, Any?>
            assertEquals("9.14.1", manifest["ghc"])
            OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/NativeMallocNative.hs",
                "test/haskell-fixtures/NativeAddressFixtures.hs", "src/test/resources/core/original-malloc-descriptors.json"))
            OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("build/native-malloc/oracle.txt"), "build/native-malloc/")
            val oracle = File(root, "build/native-malloc/oracle.txt")
                .readLines().filter(String::isNotBlank).map { it.split(' ').map(String::toLong) }
            assertEquals(listOf(0L, 1L, 2L, 197L), oracle.map { it[0] })
            val program = load(language, backend, module())
            val targets = listOf("malloc", "free", "store", "load", "copy").associateWith(program::entryTarget)
            val registry = Language.currentState().nativeAllocations
            var compiled = false
            fun call(name: String, vararg values: Any): Any? {
                val target = targets.getValue(name)
                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                return Calls.target(target, arrayOf(0L, *values)).also {
                    if (compiled) {
                        assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                        valid(target)
                    }
                    released(language)
                }
            }
            fun exercise(seed: Long) {
                val base = call("malloc", 24L, Unit) as ManagedAddress
                assertNotSame(ManagedAddress.nullAddress(), base)
                for (offset in 0L until 24L) base.writeWord8(offset, 0)
                val alias = base.plus(7)
                alias.writeWord8(0, seed)
                assertEquals(seed and 255, base.readWord8(7))
                assertEquals(23L, call("store", base, 1L, seed * 257, Unit))
                assertEquals(seed * 257, call("load", base, 1L))
                val copy = call("malloc", 24L, Unit) as ManagedAddress
                assertEquals(23L, call("copy", base, copy, 24L, Unit))
                assertEquals(base.readWord8(7), copy.readWord8(7))
                assertEquals(oracle.single { it[0] == seed }, listOf(seed, base.readWord8(7),
                    ManagedAddressRead.WORD64.read(base, 1), copy.readWord8(7)))
                assertEquals(23L, call("free", copy, Unit))
                assertEquals(23L, call("free", base, Unit))
                assertThrows(RuntimeFault::class.java) { alias.readWord8(0) }
                assertEquals(0, registry.liveCount())
            }
            repeat(3) { exercise(it.toLong()) }
            targets.values.forEach { it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true); valid(it) }
            compiled = true
            exercise(197)
            assertEquals(23L, call("free", ManagedAddress.nullAddress(), Unit))
            compiled = false
            assertThrows(RuntimeFault::class.java) { Calls.target(targets.getValue("malloc"), arrayOf(0L, 8L, 7L)) }
            assertEquals(0, registry.liveCount())
        }
    }

    @Test fun nativeWritesAndAllAliasesShareLiveStorageWithCheckedOwnership() = inside { _ ->
        val registry = Language.currentState().nativeAllocations
        val base = registry.malloc(64)
        val alias = base.plus(8)
        val linker = Linker.nativeLinker()
        val memset = linker.downcallHandle(linker.defaultLookup().find("memset").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG))
        base.withNativeSegment { segment -> memset.invokeWithArguments(segment, 0, 64L) }
        alias.withNativeSegment { segment -> memset.invokeWithArguments(segment, 0xa5, 8L) }
        assertEquals(List(8) { 0xa5L }, (8L until 16L).map(base::readWord8))
        alias.writeNativeScalar(0, 8, 0x102030405060708L)
        alias.withNativeSegment { assertEquals(0x102030405060708L, it.get(ValueLayout.JAVA_LONG_UNALIGNED, 0)) }
        val pinned = ManagedAddress.fromAllocation(ManagedAllocation.mutable(64, 8))
        base.copyNonOverlappingTo(pinned, 64)
        pinned.writeWord8(9, 37)
        pinned.copyNonOverlappingTo(base, 64)
        assertEquals(37L, alias.readWord8(1))
        assertEquals(base.toNativeBits() + 8, alias.toNativeBits())
        assertTrue(base.plus(64).plus(-64).sameLocation(base))
        for (bad in listOf(alias, base.plus(64), pinned, ManagedAddress.fromHex("41"),
            ManagedAddress.unownedNumeric(base.toNativeBits()))) {
            assertThrows(RuntimeFault::class.java) { registry.free(bad) }
            assertEquals(1, registry.liveCount())
        }
        assertThrows(RuntimeFault::class.java) { base.plus(Long.MAX_VALUE) }
        assertThrows(RuntimeFault::class.java) { alias.plus(Long.MIN_VALUE) }
        assertThrows(RuntimeFault::class.java) { base.writeNativeScalar(Long.MAX_VALUE, 8, 0) }
        assertThrows(RuntimeFault::class.java) { base.plus(64).readWord8(0) }
        assertThrows(RuntimeFault::class.java) { base.rawBacking() }
        assertThrows(RuntimeFault::class.java) { base.cbitsBacking() }
        assertThrows(RuntimeFault::class.java) { base.writeAddressElementIndex(0, pinned) }
        registry.free(base)
        assertThrows(RuntimeFault::class.java) { registry.free(base) }
        assertThrows(RuntimeFault::class.java) { alias.toNativeBits() }
        assertThrows(RuntimeFault::class.java) { alias.writeWord8(0, 1) }
        val zero = registry.malloc(0)
        if (zero !== ManagedAddress.nullAddress()) {
            zero.requireRange(0, 0)
            assertThrows(RuntimeFault::class.java) { zero.readWord8(0) }
        }
        registry.free(zero)
        assertThrows(RuntimeFault::class.java) { registry.malloc(Long.MIN_VALUE) }
        assertSame(ManagedAddress.nullAddress(), registry.malloc(Long.MAX_VALUE))
        assertTrue(Language.currentState().stdio.errno() > 0, "Actual libc allocation failure must preserve errno")
        assertEquals(0, registry.liveCount())
    }

    @Test fun freeWaitsForBorrowAndDisposalInvalidatesSavedAliases() {
        supported()
        val context = context(false)
        val executor = Executors.newSingleThreadExecutor()
        context.initialize("thc"); context.enter()
        val registry = Language.currentState().nativeAllocations
        val base = registry.malloc(8)
        val borrow = base.nativeAllocation()!!.borrow()
        try {
            assertThrows(RuntimeFault::class.java) { registry.free(base) }
            val started = CountDownLatch(1)
            val freeing = executor.submit {
                context.enter()
                try { started.countDown(); registry.free(base) } finally { context.leave() }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { freeing.get(100, TimeUnit.MILLISECONDS) }
            borrow.segment().set(ValueLayout.JAVA_BYTE, 0, 73.toByte())
            borrow.close()
            freeing.get(5, TimeUnit.SECONDS)
            assertThrows(RuntimeFault::class.java) { base.readWord8(0) }
            val saved = registry.malloc(8).plus(3)
            Language.currentState().savedTermios.set(0, saved)
            assertSame(saved, Language.currentState().savedTermios.get(0))
            registry.close()
            assertThrows(RuntimeFault::class.java) { saved.readWord8(0) }
            assertThrows(RuntimeFault::class.java) { registry.malloc(1) }
        } finally {
            borrow.close(); context.leave(); executor.shutdownNow(); context.close()
        }
    }

    @Test fun termiosTransferCopiesBackWholeImagesOnSuccessAndNativeError(): Unit = inside { _ ->
        val registry = Language.currentState().nativeAllocations
        val size = TermiosImage.scalar(OriginalStdioOp.SIZEOF_TERMIOS, ManagedAddress.nullAddress(), 0).toInt()
        val base = registry.malloc(size.toLong() + 16)
        val address = base.plus(8)
        try {
            for (copyBack in listOf(false, true)) for (fails in listOf(false, true)) {
                for (i in 0 until size + 16) base.writeWord8(i.toLong(), 77)
                val failure = NativeFileException("tcgetattr test", 5)
                fun invoke() = TermiosImage.transfer(address, copyBack) { bytes ->
                    assertArrayEquals(ByteArray(size) { 77 }, bytes)
                    // Same-thread release must reject rather than deadlock.
                    assertThrows(RuntimeFault::class.java) { registry.free(base) }
                    bytes.indices.forEach { bytes[it] = (it * 17).toByte() }
                    if (fails) throw failure
                    37L
                }
                if (fails) assertSame(failure, assertThrows(NativeFileException::class.java) { invoke() })
                else assertEquals(37L, invoke())
                val expected = ByteArray(size + 16) { 77 }
                if (copyBack) for (i in 0 until size) expected[i + 8] = (i * 17).toByte()
                assertArrayEquals(expected, ByteArray(size + 16) { base.readWord8(it.toLong()).toByte() })
            }
            assertThrows(RuntimeFault::class.java) {
                TermiosImage.transfer(base.plus(17), true) { fail<Any>("Short image reached native operation") }
            }
        } finally { registry.free(base) }
    }

    @Test fun tcgetattrTransferHoldsNativeOwnerUntilErrorOrSuccessCopybackCompletes() {
        supported()
        context(false).use { context ->
            val executor = Executors.newSingleThreadExecutor()
            context.initialize("thc"); context.enter()
            try {
                val registry = Language.currentState().nativeAllocations
                val size = TermiosImage.scalar(OriginalStdioOp.SIZEOF_TERMIOS, ManagedAddress.nullAddress(), 0)
                for (fails in listOf(false, true)) {
                    val base = registry.malloc(size)
                    for (i in 0 until size) base.writeWord8(i, 19)
                    val startFree = CountDownLatch(1)
                    val freeingStarted = CountDownLatch(1)
                    val freeing = executor.submit {
                        context.enter()
                        try {
                            assertTrue(startFree.await(5, TimeUnit.SECONDS))
                            freeingStarted.countDown()
                            registry.free(base)
                        } finally { context.leave() }
                    }
                    val failure = NativeFileException("tcgetattr test", 5)
                    fun invoke() = TermiosImage.transfer(base, copyBack = true) { bytes ->
                        // Queue release while the same staging callback used by
                        // ManagedFiles.tcgetattr owns the complete native extent.
                        startFree.countDown()
                        assertTrue(freeingStarted.await(5, TimeUnit.SECONDS))
                        assertThrows(TimeoutException::class.java) { freeing.get(100, TimeUnit.MILLISECONDS) }
                        bytes.fill(73)
                        if (fails) throw failure
                        37L
                    }
                    try {
                        if (fails) assertSame(failure, assertThrows(NativeFileException::class.java) { invoke() })
                        else assertEquals(37L, invoke())
                        // Copyback's checked accesses would fail if the queued
                        // release won before the finally block completed.
                        freeing.get(5, TimeUnit.SECONDS)
                        assertThrows(RuntimeFault::class.java) { base.readWord8(0) }
                        assertEquals(0, registry.liveCount())
                    } finally { startFree.countDown() }
                }
            } finally { context.leave(); executor.shutdownNow() }
        }
    }

    @Test fun crossContextNativePermissionAndForgedDeclarationsFailClosed() {
        supported()
        context(false).use { first -> context(false).use { second ->
            first.initialize("thc"); second.initialize("thc")
            first.enter()
            val base = try { Language.currentState().nativeAllocations.malloc(8) } finally { first.leave() }
            second.enter()
            try {
                assertThrows(RuntimeFault::class.java) { Language.currentState().nativeAllocations.free(base) }
                assertThrows(RuntimeFault::class.java) { base.readWord8(0) }
                assertThrows(RuntimeFault::class.java) { base.plus(0) }
            } finally { second.leave() }
            first.enter()
            try { Language.currentState().nativeAllocations.free(base) } finally { first.leave() }
        } }
        Context.newBuilder("thc").build().use { context ->
            context.initialize("thc"); context.enter()
            try { assertThrows(RuntimeFault::class.java) { Language.currentState().nativeAllocations.malloc(8) } }
            finally { context.leave() }
        }
        inside { language ->
            for (backend in listOf("ast", "bytecode")) for (mutation in listOf("unit", "safety", "arity", "rep", "flag", "result")) {
                val malformed = Json.parse(Json.stringify(module())) as Map<String, Any?>
                fun change(value: Any?) {
                    when (value) {
                        is MutableMap<*, *> -> value.values.forEach(::change)
                        is List<*> -> {
                            if (value.firstOrNull() == "app") {
                                val metadata = value[6] as MutableMap<String, Any?>
                                val declaration = metadata["foreignCall"] as? MutableMap<String, Any?>
                                if (declaration != null) when (mutation) {
                                    "unit" -> (declaration["target"] as MutableMap<String, Any?>)["unit"] = "other"
                                    "safety" -> declaration["safety"] = "safe"
                                    "arity" -> declaration["arity"] = 3L
                                    "rep" -> ((declaration["argumentReps"] as List<MutableMap<String, Any?>>)[0])["primReps"] = listOf("IntRep")
                                    "flag" -> (value[3] as MutableList<Boolean>)[0] = true
                                    "result" -> (declaration["resultRep"] as MutableMap<String, Any?>)["components"] = emptyList<Any>()
                                }
                            }
                            value.forEach(::change)
                        }
                    }
                }
                change(malformed)
                assertThrows(RuntimeFault::class.java, { load(language, backend, malformed) }, "$backend/$mutation")
            }
        }
    }
}
