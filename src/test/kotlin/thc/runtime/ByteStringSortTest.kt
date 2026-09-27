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
import thc.*
import java.io.File
import java.util.HexFormat

class ByteStringSortTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/bytestring-sort"
    private val nativeAvailable = System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64", "x86_64")
    private fun json(name: String) = Json.parse(File(root, "$prefix/$name").readText())
    private fun source(stage: String) = json("$stage.json") as Map<String, Any?>
    private fun program(language: Language, backend: String, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun <T> inside(action: (Language) -> T): T = Context.newBuilder("thc").allowNativeAccess(true)
        .withContextProfile(ContextProfile.SYNCHRONOUS_TEST).build().use { context ->
            context.initialize("thc"); context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun install(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target)
        val runtime = Truffle.getRuntime()
        runtime.javaClass.getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
            .invoke(runtime, target)
        valid(target)
    }
    private fun released(language: Language) {
        val handoff = language.handoffState.get()
        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
    }
    private fun bytes(address: ManagedAddress, count: Int) = ByteArray(count) { address.readWord8(it.toLong()).toByte() }
    private fun fixture(): List<Map<String, Any?>> {
        val manifest = json("manifest.json") as Map<String, Any?>
        assertEquals("9.14.1", manifest["ghc"])
        assertEquals(listOf("sortBytes"), manifest["entries"])
        assertTrue(CoreMemorySearchForeign.isOriginalByteStringUnit(manifest["bytestringUnit"]))
        assertEquals(false, manifest["installedArtifactsHashed"])
        assertTrue((manifest["interface"] as String).endsWith("/Data/ByteString/Internal/Type.hi"))
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/ByteStringSortAudit.hs", "test/haskell-fixtures/ByteStringSortFixtures.hs",
            "src/test/resources/core/original-bytestring-sort-descriptor.json", "src/main/java/thc/runtime/CoreByteStringSort.java",
            "src/main/java/thc/runtime/ByteStringSort.java", "src/main/java/thc/runtime/ByteStringSortExpression.java"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/pre.json", "$prefix/post.json", "$prefix/oracle.json",
            "$prefix/pre-sortBytes.audit.json", "$prefix/post-sortBytes.audit.json"), "$prefix/")
        val retained = (Json.parse(File(root, "src/test/resources/core/original-bytestring-sort-descriptor.json").readText()) as Map<*, *>)["fps_sort"] as Map<*, *>
        for (stage in listOf("pre", "post")) {
            val call = OriginalStdioChecks.foreignCalls(source(stage)).single()
            val metadata = call[6] as Map<*, *>
            val actual = metadata["foreignCall"] as Map<*, *>
            val target = actual["target"] as Map<*, *>
            assertEquals(manifest["bytestringUnit"], target["unit"])
            assertEquals(retained.filterKeys { it != "target" }, actual.filterKeys { it != "target" })
            assertEquals((retained["target"] as Map<*, *>).filterKeys { it != "unit" }, target.filterKeys { it != "unit" })
            assertTrue(CoreByteStringSort.validate(metadata, (call[2] as List<List<Any?>>).map {
                CoreRepresentations.metadata(it)?.get("rep") }, call[3] as List<*>, metadata["rep"]))
            val audit = json("$stage-sortBytes.audit.json") as Map<*, *>
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any?>(), audit["issues"]); assertEquals(emptyList<Any?>(), audit["missingGlobals"])
            val proof = ArrayCoreEvidence(source(stage), "sortBytes")
            assertEquals(1, proof.bindings.size)
            assertEquals(1, proof.guestLambdas(proof.root["expr"]).size)
        }
        val rows = json("oracle.json") as List<Map<String, Any?>>
        assertTrue(rows.any { it["count"] == 0L })
        assertTrue(rows.any { (it["count"] as Long) >= 256L })
        for (row in rows) {
            val input = HexFormat.of().parseHex(row["input"] as String)
            val offset = (row["offset"] as Long).toInt()
            val count = (row["count"] as Long).toInt()
            val model = input.copyOf()
            input.copyOfRange(offset, offset + count).map { it.toInt() and 255 }.sorted()
                .forEachIndexed { index, byte -> model[offset + index] = byte.toByte() }
            assertArrayEquals(model, HexFormat.of().parseHex(row["bytes"] as String), row["case"] as String)
        }
        return rows
    }

    @Test fun originalSortMatchesNativeAtFirstInstalledEntry() {
        val rows = fixture()
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) inside { language ->
            val p = program(language, backend, CoreModules.reachable(source(stage), "sortBytes", true) + ("instrument" to true))
            val target = p.entryTarget("sortBytes")
            fun exercise(compiled: Boolean) {
                for (kind in 0..(if (nativeAvailable) 3 else 2)) for (row in rows) {
                    val input = HexFormat.of().parseHex(row["input"] as String)
                    val base = when (kind) {
                        0 -> ManagedAddress.fromByteArray(ByteArray(input.size))
                        3 -> Language.currentState().nativeAllocations.malloc(input.size.toLong())
                        else -> ManagedAddress.fromAllocation(ManagedAllocation.mutable(input.size.toLong(), 8, pinned = kind == 2))
                    }
                    try {
                        input.forEachIndexed { index, byte -> base.writeWord8(index.toLong(), byte.toLong()) }
                        val before = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                        if (compiled) valid(target)
                        val result = Calls.target(target, arrayOf(0L, base.plus(row["offset"] as Long), row["count"]))
                        assertEquals(row["count"], result)
                        assertArrayEquals(HexFormat.of().parseHex(row["bytes"] as String), bytes(base, input.size),
                            "$stage/$backend/$kind/${row["case"]}")
                        if (compiled) {
                            assertEquals(before + 1, (p.diagnostics().getValue("compiledEntries") as Number).toLong())
                            valid(target)
                        }
                        released(language)
                    } finally { if (kind == 3) Language.currentState().nativeAllocations.free(base) }
                }
            }
            exercise(false); install(target); exercise(true)
        }
    }

    @Test fun boundsStorageAndLifetimesRejectBeforeMutation() {
        fixture()
        for (backend in listOf("ast", "bytecode")) inside { language ->
            val target = program(language, backend, CoreModules.reachable(source("pre"), "sortBytes", true)).entryTarget("sortBytes")
            val original = byteArrayOf(9, 8, 7, 6)
            val storage = ManagedAddress.fromByteArray(original.copyOf())
            for ((address, count) in listOf(storage to -1L, storage to Long.MIN_VALUE, storage to Long.MAX_VALUE,
                    storage.plus(-1) to 1L, storage.plus(4) to 1L, storage.plus(2) to 3L,
                    ManagedAddress.nullAddress() to 1L, ManagedAddress.unownedNumeric(1) to 0L,
                    ManagedAddress.fromHex("09080706") to 4L)) {
                assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, address, count)) }
                assertArrayEquals(original, bytes(storage, 4))
            }
            assertEquals(0L, Calls.target(target, arrayOf(0L, ManagedAddress.nullAddress(), 0L)))
            assertEquals(0L, Calls.target(target, arrayOf(0L, storage.plus(4), 0L)))
            val cells = ManagedAddress.fromAllocation(ManagedAllocation.mutable(16, 8))
            cells.writeAddressElementIndex(0, storage)
            assertThrows(RuntimeFault::class.java) { ByteStringSort.sort(cells, 8) }
            assertTrue(cells.readAddressElementIndex(0).sameLocation(storage))
            if (nativeAvailable) {
                val native = Language.currentState().nativeAllocations.malloc(4)
                inside { assertThrows(RuntimeFault::class.java) { ByteStringSort.sort(native, 4) } }
                Language.currentState().nativeAllocations.free(native)
                assertThrows(RuntimeFault::class.java) { ByteStringSort.sort(native, 4) }
            }
            released(language)
        }
    }

    @Test fun exactOriginalDescriptorAndVoidStateRemainRequired() {
        fixture()
        for (backend in listOf("ast", "bytecode")) inside { language ->
            val original = OriginalStdioChecks.foreignCalls(source("pre")).single()
            for (variant in 0..10) {
                val candidate = OriginalStdioChecks.rawModule(original, source("pre"))
                val call = OriginalStdioChecks.foreignCalls(candidate).single() as MutableList<Any?>
                val descriptor = (call[6] as Map<*, *>)["foreignCall"] as MutableMap<String, Any?>
                when (variant) {
                    0 -> (descriptor["target"] as MutableMap<String, Any?>)["unit"] = "ghc-internal"
                    1 -> (descriptor["target"] as MutableMap<String, Any?>)["unit"] = "bytestring-0.12.1.0-inplace"
                    2 -> descriptor["safety"] = "safe"
                    3 -> descriptor["convention"] = "capi"
                    4 -> descriptor["arity"] = 2L
                    5 -> (descriptor["argumentReps"] as List<MutableMap<String, Any?>>)[1]["primReps"] = listOf("Int64Rep")
                    6 -> (call[3] as MutableList<Any?>)[0] = true
                    7 -> (call[1] as MutableList<Any?>)[1] = "entry"
                    8 -> ((descriptor["resultRep"] as MutableMap<String, Any?>)["components"] as MutableList<Any?>).clear()
                    9 -> (descriptor["target"] as MutableMap<String, Any?>)["unit"] = "bytestring-0.12.2.0-inplace:forged"
                    10 -> (descriptor["target"] as MutableMap<String, Any?>)["isFunction"] = false
                }
                assertThrows(RuntimeFault::class.java, { program(language, backend, candidate) }, "$backend/variant=$variant")
            }
            val raw = program(language, backend, OriginalStdioChecks.rawModule(original, source("pre")))
            val storage = ManagedAddress.fromByteArray(byteArrayOf(3, 2, 1))
            assertThrows(RuntimeFault::class.java) { Calls.target(raw.entryTarget("entry"), arrayOf(0L, storage, 3L, 7L)) }
            assertArrayEquals(byteArrayOf(3, 2, 1), bytes(storage, 3))
            released(language)
        }
    }
}
