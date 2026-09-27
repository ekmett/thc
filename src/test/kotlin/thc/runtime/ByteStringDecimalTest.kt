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

class ByteStringDecimalTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/bytestring-decimal"
    private val entries = listOf("decimal", "padded18")
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
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun install(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target)
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        val runtime = Truffle.getRuntime()
        runtime.javaClass.getMethod("bypassedInstalledCode", type).invoke(runtime, target)
        valid(target)
    }
    private fun released(language: Language) {
        val handoff = language.handoffState.get()
        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
    }
    private fun bytes(address: ManagedAddress, count: Int = 48) = ByteArray(count) { address.readWord8(it.toLong()).toByte() }
    private fun fixture(): List<Map<String, Any?>> {
        val manifest = json("manifest.json") as Map<String, Any?>
        assertEquals("9.14.1", manifest["ghc"])
        assertEquals(entries, manifest["entries"])
        assertTrue(isOriginalByteStringUnit(manifest["bytestringUnit"]))
        assertEquals(false, manifest["installedArtifactsHashed"])
        assertTrue((manifest["interface"] as String).endsWith("/Data/ByteString/Internal/Type.hi"))
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/ByteStringDecimalAudit.hs", "test/haskell-fixtures/ByteStringDecimalFixtures.hs",
            "src/test/resources/core/original-bytestring-decimal-descriptors.json", "src/main/java/thc/runtime/CoreByteStringDecimal.java",
            "src/main/java/thc/runtime/ByteStringDecimal.java", "src/main/java/thc/runtime/ByteStringDecimalOp.java",
            "src/main/java/thc/runtime/ByteStringDecimalExpression.java"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/pre.json", "$prefix/post.json", "$prefix/oracle.json") +
            listOf("pre", "post").flatMap { stage -> entries.map { "$prefix/$stage-$it.audit.json" } }, "$prefix/")
        val retained = Json.parse(File(root, "src/test/resources/core/original-bytestring-decimal-descriptors.json").readText()) as Map<*, *>
        for (stage in listOf("pre", "post")) {
            val calls = OriginalStdioChecks.foreignCalls(source(stage))
            assertEquals(2, calls.size)
            for (call in calls) {
                val metadata = call[6] as Map<*, *>
                val actual = metadata["foreignCall"] as Map<*, *>
                val target = actual["target"] as Map<*, *>
                assertEquals(manifest["bytestringUnit"], target["unit"])
                val expected = retained[target["symbol"]] as Map<*, *>
                assertEquals(expected.filterKeys { it != "target" }, actual.filterKeys { it != "target" })
                assertEquals((expected["target"] as Map<*, *>).filterKeys { it != "unit" }, target.filterKeys { it != "unit" })
                val operation = CoreByteStringDecimal.validate(metadata, (call[2] as List<List<Any?>>).map {
                    CoreRepresentations.metadata(it)?.get("rep") }, call[3] as List<*>, metadata["rep"])!!
                val result = actual["resultRep"] as Map<*, *>
                assertEquals(if (operation.addressResult) 2 else 1, (result["components"] as List<*>).size)
            }
            for (entry in entries) {
                val audit = json("$stage-$entry.audit.json") as Map<*, *>
                assertEquals(true, audit["accepted"])
                assertEquals(emptyList<Any?>(), audit["issues"]); assertEquals(emptyList<Any?>(), audit["missingGlobals"])
                val proof = ArrayCoreEvidence(source(stage), entry)
                assertEquals(1, proof.bindings.size)
                assertEquals(1, proof.guestLambdas(proof.root["expr"]).size, "One typed consumer root")
            }
        }
        val rows = json("oracle.json") as List<Map<String, Any?>>
        assertEquals(mapOf("decimal" to 19, "padded18" to 9), rows.groupingBy { it["entry"] }.eachCount())
        assertTrue(rows.any { it["entry"] == "decimal" && it["input"] == Long.MIN_VALUE })
        assertTrue(rows.any { it["entry"] == "decimal" && it["input"] == Long.MAX_VALUE })
        for (row in rows) {
            val value = row["input"] as Long
            val expected = if (row["entry"] == "decimal") value.toString() else value.toString().padStart(18, '0')
            val native = HexFormat.of().parseHex(row["bytes"] as String)
            assertEquals(expected.length.toLong(), row["end"])
            assertEquals(expected, native.copyOfRange(7, 7 + expected.length).toString(Charsets.US_ASCII))
            assertTrue(native.take(7).all { it == 0xa5.toByte() })
            assertTrue(native.drop(7 + expected.length).all { it == 0xa5.toByte() }, "No NUL or writes past end")
        }
        return rows
    }

    @Test fun genuineDecimalWritersMatchNativeOnFirstInstalledCalls() {
        val rows = fixture()
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) inside { language ->
            for (entry in entries) {
                val p = program(language, backend, CoreModules.reachable(source(stage), entry, true) + ("instrument" to true))
                val target = p.entryTarget(entry)
                fun exercise(compiled: Boolean) {
                    for (kind in 0..(if (nativeAvailable) 3 else 2)) for (row in rows.filter { it["entry"] == entry }) {
                        val base = when (kind) {
                            0 -> ManagedAddress.fromByteArray(ByteArray(48))
                            3 -> Language.currentState().nativeAllocations.malloc(48)
                            else -> ManagedAddress.fromAllocation(ManagedAllocation.mutable(48, 8, pinned = kind == 2))
                        }
                        try {
                            repeat(48) { base.writeWord8(it.toLong(), 165) }
                            val address = base.plus(7)
                            val before = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                            if (compiled) valid(target)
                            val result = Calls.target(target, arrayOf(0L, row["input"], address))
                            assertArrayEquals(HexFormat.of().parseHex(row["bytes"] as String), bytes(base), "$stage/$backend/$entry/$kind/${row["input"]}")
                            if (entry == "decimal") {
                                val end = result as ManagedAddress
                                assertTrue(end.sameLocation(address.plus(row["end"] as Long)))
                                assertEquals(row["end"], end.difference(address))
                                end.writeWord8(0, 33)
                                assertEquals(33L, base.readWord8(7 + (row["end"] as Long)), "End pointer retains the caller allocation")
                            } else assertEquals(18L, result)
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
    }

    @Test fun domainBoundsAndOwnershipFailBeforeWrites() {
        fixture()
        for (backend in listOf("ast", "bytecode")) inside { language ->
            for (entry in entries) {
                val p = program(language, backend, CoreModules.reachable(source("pre"), entry, true))
                val target = p.entryTarget(entry)
                val writable = ManagedAddress.fromByteArray(ByteArray(48) { 0xa5.toByte() })
                for (address in listOf(writable.plus(48), writable.plus(-1), ManagedAddress.nullAddress(),
                        ManagedAddress.fromHex("a5".repeat(48)), ManagedAddress.unownedNumeric(1))) {
                    assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, 10L, address)) }
                    assertArrayEquals(ByteArray(48) { 0xa5.toByte() }, bytes(writable))
                }
                val short = ManagedAddress.fromByteArray(ByteArray(if (entry == "decimal") 19 else 17) { 0xa5.toByte() })
                assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, if (entry == "decimal") Long.MIN_VALUE else 0L, short)) }
                assertTrue(bytes(short, if (entry == "decimal") 19 else 17).all { it == 0xa5.toByte() })
                if (entry == "padded18") for (invalid in listOf(Long.MIN_VALUE, -1L, 1_000_000_000_000_000_000L, Long.MAX_VALUE)) {
                    val error = assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, invalid, writable.plus(7))) }
                    assertTrue(error.message!!.contains("0 <= value < 10^18"))
                    assertArrayEquals(ByteArray(48) { 0xa5.toByte() }, bytes(writable))
                }
                released(language)
            }
            val cells = ManagedAddress.fromAllocation(ManagedAllocation.mutable(32, 8))
            val referent = ManagedAddress.fromByteArray(byteArrayOf(1))
            cells.writeAddressElementIndex(0, referent)
            assertThrows(RuntimeFault::class.java) { ByteStringDecimal.signed(10, cells) }
            assertTrue(cells.readAddressElementIndex(0).sameLocation(referent))
            if (nativeAvailable) {
                val allocation = Language.currentState().nativeAllocations.malloc(32)
                val end = ByteStringDecimal.signed(7, allocation)
                inside { assertThrows(RuntimeFault::class.java) { ByteStringDecimal.signed(8, end) } }
                Language.currentState().nativeAllocations.free(allocation)
                assertThrows(RuntimeFault::class.java) { ByteStringDecimal.signed(8, end) }
            }
        }
    }

    @Test fun exactOriginalAbiAndStateRemainRequired() {
        fixture()
        for (backend in listOf("ast", "bytecode")) inside { language ->
            for (original in OriginalStdioChecks.foreignCalls(source("pre"))) {
                for (variant in 0..8) {
                    val candidate = OriginalStdioChecks.rawModule(original, source("pre"))
                    val call = OriginalStdioChecks.foreignCalls(candidate).single() as MutableList<Any?>
                    val descriptor = (call[6] as Map<*, *>)["foreignCall"] as MutableMap<String, Any?>
                    when (variant) {
                        0 -> (descriptor["target"] as MutableMap<String, Any?>)["unit"] = "ghc-internal"
                        1 -> (descriptor["target"] as MutableMap<String, Any?>)["unit"] = "bytestring-0.12.1.0-inplace"
                        2 -> descriptor["safety"] = "safe"
                        3 -> descriptor["convention"] = "capi"
                        4 -> descriptor["arity"] = 2L
                        5 -> (descriptor["argumentReps"] as List<MutableMap<String, Any?>>)[0]["primReps"] = listOf("Word64Rep")
                        6 -> (call[3] as MutableList<Any?>)[0] = true
                        7 -> (call[1] as MutableList<Any?>)[1] = "entry"
                        8 -> ((descriptor["resultRep"] as MutableMap<String, Any?>)["components"] as MutableList<Any?>).removeAt(0)
                    }
                    assertThrows(RuntimeFault::class.java, { program(language, backend, candidate) }, "$backend/variant=$variant")
                }
                val raw = program(language, backend, OriginalStdioChecks.rawModule(original, source("pre")))
                val address = ManagedAddress.fromByteArray(ByteArray(48) { 0xa5.toByte() })
                assertThrows(RuntimeFault::class.java) { Calls.target(raw.entryTarget("entry"), arrayOf(0L, 1L, address, 7L)) }
                assertArrayEquals(ByteArray(48) { 0xa5.toByte() }, bytes(address))
                released(language)
            }
        }
    }
}
