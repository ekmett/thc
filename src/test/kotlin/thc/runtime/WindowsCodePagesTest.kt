// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import thc.ContextProfile
import thc.CoreModules
import thc.Json
import thc.Language
import thc.withContextProfile
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@EnabledOnOs(OS.WINDOWS)
class WindowsCodePagesTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun receipt() = json("build/windows-codepages/manifest.json")
    private fun source(stage: String = "post") = json(receipt()["logs"].toString() + "/$stage.json")
    private fun context(native: Boolean = true) = Context.newBuilder("thc").allowNativeAccess(native)
        .allowIO(IOAccess.NONE).withContextProfile(ContextProfile.SYNCHRONOUS_TEST).build()
    private fun <T> entered(context: Context, action: (Language) -> T): T {
        context.initialize("thc"); context.enter()
        return try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun program(language: Language, backend: String, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun buffer(size: Int = 64, fill: Long = 165, pinned: Boolean = false) =
        ManagedAddress.fromAllocation(ManagedAllocation.mutable(size.toLong(), 8, pinned)).also { it.fill(size.toLong(), fill) }
    private fun bytes(address: ManagedAddress, size: Int = 64) = (0 until size).map { address.readWord8(it.toLong()) }
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private val operations = linkedMapOf("ansiPage" to OriginalStdioOp.ANSI_CODE_PAGE,
        "consolePage" to OriginalStdioOp.CONSOLE_CODE_PAGE, "windowsError" to OriginalStdioOp.LAST_ERROR,
        "pageInfo" to OriginalStdioOp.CODE_PAGE_INFO, "leadByte" to OriginalStdioOp.DBCS_LEAD_BYTE,
        "multiByte" to OriginalStdioOp.MULTI_BYTE_TO_WIDE, "wideChar" to OriginalStdioOp.WIDE_TO_MULTI_BYTE,
        "mapError" to OriginalStdioOp.MAP_ERRNO_VALUE, "mapCurrentError" to OriginalStdioOp.MAP_ERRNO,
        "errorMessage" to OriginalStdioOp.WINDOWS_ERROR_MESSAGE, "localFree" to OriginalStdioOp.LOCAL_FREE)
    private fun original(name: String) = OriginalStdioChecks.foreignCalls(CoreModules.reachable(source(), name)).single()
    private fun validate(call: List<Any?>) = CoreOriginalStdio.validate(call[6],
        (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") },
        call[3] as List<*>, (call[6] as Map<*, *>)["rep"])

    @Test fun genuineDeclarationsMatchNativeEncodingAndErrorsOnEveryFirstCompiledCall() {
        val proof = receipt()
        val logs = proof["logs"].toString().replace('\\', '/')
        assertEquals("9.14.1", proof["ghc"]); assertEquals(true, proof["originalFCallIds"])
        assertEquals(operations.keys.toList(), proof["entries"])
        assertEquals("2a83779c9af86554a3289f2787a38d6aa83d00d136aa9f920361dd693c101e77", proof["archiveSha256"])
        OriginalStdioChecks.hashes(root, proof["inputHashes"], setOf("compiler/test-fixtures/WindowsCodePageAudit.hs",
            "test/haskell-fixtures/WindowsCodePageFixtures.hs", "scripts/core_original_foreign.py"))
        OriginalStdioChecks.hashes(root, proof["artifactHashes"], setOf("$logs/pre.json", "$logs/post.json", "$logs/oracle.json"), "$logs/")
        val upstream = "$logs/source/ghc-9.14.1/libraries/ghc-internal/"
        val sourceHashes = (proof["sourceHashes"] as Map<String, String>).mapKeys { (path, _) ->
            root.canonicalFile.toPath().relativize(File(path).canonicalFile.toPath()).toString().replace('\\', '/')
        }
        OriginalStdioChecks.hashes(root, sourceHashes, setOf(upstream + "src/GHC/Internal/Windows.hs",
            upstream + "src/GHC/Internal/IO/Encoding/CodePage.hs", upstream + "src/GHC/Internal/IO/Encoding/CodePage/API.hs",
            upstream + "cbits/Win32Utils.c"), upstream)
        (proof["commands"] as List<Map<String, Any?>>).forEach { assertEquals(it["expectedExit"], it["exit"]) }
        val oracle = json("$logs/oracle.json")
        assertEquals(263, (oracle["mapping"] as List<*>).size)
        assertEquals(16, (oracle["lead"] as List<*>).size)
        assertEquals(12, (oracle["multi"] as List<*>).size); assertEquals(13, (oracle["wide"] as List<*>).size)
        for (stage in listOf("pre", "post")) {
            for ((name, operation) in operations) {
                assertEquals(operation, validate(original(name)))
                val audit = json("$logs/$stage-$name.audit.json")
                assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any>(), audit["issues"])
                assertEquals(emptyList<Any>(), audit["missingGlobals"])
            }
            for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
                val executable = program(language, backend, source(stage) + ("instrument" to true))
                val targets = operations.keys.associateWith { executable.entryTarget(it) }
                var compiled = false
                fun invoke(name: String, vararg args: Any): Any? {
                    val target = targets.getValue(name)
                    if (compiled) valid(target)
                    val before = (executable.diagnostics().getValue("compiledEntries") as Number).toLong()
                    val result = Calls.target(target, arrayOf(0L, *args))
                    if (compiled) {
                        assertEquals(before + 1, (executable.diagnostics().getValue("compiledEntries") as Number).toLong(), "$stage/$backend/$name first compiled entry")
                        valid(target)
                    }
                    val handoff = language.handoffState.get()
                    assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                    assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
                    return result
                }
                fun exercise() {
                    assertEquals(oracle["ansi"], invoke("ansiPage", 0L))
                    assertEquals(oracle["console"], invoke("consolePage", 0L))
                    if (oracle["console"] == 0L) assertEquals(oracle["consoleError"], invoke("windowsError", 0L))
                    for (row in oracle["info"] as List<Map<String, Any?>>) {
                        val storage = buffer(34); val output = storage.plus(8)
                        assertEquals(row["result"], invoke("pageInfo", row["page"]!!, output))
                        if (row["result"] == 0L) assertEquals(row["error"], invoke("windowsError", 0L))
                        assertEquals((row["bytes"] as List<Long>).take(18), bytes(output, 18))
                        assertEquals(List(8) { 165L }, bytes(storage, 8))
                        assertEquals(List(8) { 165L }, bytes(storage.plus(26), 8))
                    }
                    for (row in oracle["lead"] as List<Map<String, Any?>>) {
                        assertEquals(row["result"], invoke("leadByte", row["page"]!!, row["byte"]!!))
                        if (row["page"] == 999999L) assertEquals(row["error"], invoke("windowsError", 0L))
                    }
                    for (row in oracle["mapping"] as List<List<Long>>) assertEquals(row[1], invoke("mapError", row[0]))
                    val mapped = oracle["mappedCurrent"] as Map<String, Any?>
                    assertEquals(0L, invoke("pageInfo", 999999L, buffer()))
                    assertEquals(mapped["error"], invoke("windowsError", 0L))
                    assertEquals(0L, invoke("mapCurrentError", 0L))
                    assertEquals(mapped["errno"], Language.currentState().stdio.errno())
                    for (row in oracle["multi"] as List<Map<String, Any?>>) {
                        val input = buffer(); val output = if (row["alias"] == true) input else buffer()
                        (row["input"] as List<Long>).forEachIndexed { i, value -> input.writeWord8(i.toLong(), value) }
                        assertEquals(row["result"], invoke("multiByte", row["page"]!!, row["flags"]!!, input, row["count"]!!,
                            if (row["sizing"] == true) ManagedAddress.nullAddress() else output, row["capacity"]!!), row["case"].toString())
                        if (row["result"] == 0L) assertEquals(row["error"], invoke("windowsError", 0L), row["case"].toString())
                        assertEquals(row["bytes"], bytes(output), row["case"].toString())
                    }
                    for (row in oracle["wide"] as List<Map<String, Any?>>) {
                        val input = buffer(pinned = true); val output = buffer(pinned = true)
                        (row["input"] as List<Long>).forEachIndexed { i, value -> input.writeNativeScalar(i.toLong(), 2, value) }
                        val def = row["default"] as List<Long>
                        val defaultChar = if (def.isEmpty()) ManagedAddress.nullAddress()
                            else buffer(4, 0).also { memory -> def.forEachIndexed { i, value -> memory.writeWord8(i.toLong(), value) } }
                        val used = buffer(4, 90)
                        assertEquals(row["result"], invoke("wideChar", row["page"]!!, row["flags"]!!, input, row["count"]!!,
                            if (row["sizing"] == true) ManagedAddress.nullAddress() else output, row["capacity"]!!,
                            defaultChar, if (row["used"] == true) used else ManagedAddress.nullAddress()), row["case"].toString())
                        if (row["result"] == 0L) assertEquals(row["error"], invoke("windowsError", 0L), row["case"].toString())
                        assertEquals(row["bytes"], bytes(output), row["case"].toString())
                        assertEquals(row["usedValue"], used.readWord8(0) or (used.readWord8(1) shl 8) or
                            (used.readWord8(2) shl 16) or (used.readWord8(3) shl 24), row["case"].toString())
                    }
                    for (row in oracle["messages"] as List<Map<String, Any?>>) {
                        val address = invoke("errorMessage", row["error"]!!) as ManagedAddress
                        assertEquals(row["null"], address === ManagedAddress.nullAddress())
                        if (address !== ManagedAddress.nullAddress()) {
                            val expected = row["units"] as List<Long>
                            assertEquals((expected.size + 1L) * 2, address.availableBytes())
                            assertEquals(expected + 0L, (0..expected.size).map { i ->
                                address.readWord8(i * 2L) or (address.readWord8(i * 2L + 1) shl 8) })
                        }
                        assertSame(ManagedAddress.nullAddress(), invoke("localFree", address))
                    }
                    assertEquals(0, Language.currentState().nativeAllocations.liveCount())
                }
                exercise()
                val targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                targets.values.forEach { target ->
                    targetClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    valid(target)
                    val runtime = Truffle.getRuntime()
                    runtime.javaClass.getMethod("bypassedInstalledCode", targetClass).invoke(runtime, target)
                }
                compiled = true
                exercise()
                assertEquals(0L, executable.diagnostics()["unsupportedTraps"])
            } }
        }
    }

    @Test fun bufferValidationAndAllocatorIdentityPreserveOwnershipAndContext() {
        lateinit var service: WindowsCodePages
        lateinit var retained: ManagedAddress
        lateinit var allocations: ManagedNativeAllocations
        context().use { first ->
            entered(first) {
                service = Language.currentState().windowsCodePages
                allocations = Language.currentState().nativeAllocations
                val output = buffer(18)
                assertNotEquals(0L, service.info(932, output))
                for (invalid in listOf(buffer(17), ManagedAddress.nullAddress(), ManagedAddress.fromHex("0000"), ManagedAddress.unownedNumeric(1)))
                    assertThrows(RuntimeFault::class.java) { service.info(932, invalid) }
                assertThrows(RuntimeFault::class.java) { service.multiByte(1252, 0, buffer(1, 65), -1, output, 4) }
                assertThrows(RuntimeFault::class.java) { service.multiByte(1252, 0, buffer(1), 2, output, 4) }
                assertThrows(RuntimeFault::class.java) { service.multiByte(1252, 0, ManagedAddress.nullAddress(), 1, output, 4) }
                assertThrows(RuntimeFault::class.java) { service.multiByte(1252, 0, buffer(1), -2, output, 4) }
                assertThrows(RuntimeFault::class.java) { service.multiByte(1252, 0, buffer(1), 1, output, -1) }
                assertThrows(RuntimeFault::class.java) { service.wideChar(1252, 0, buffer(2), 1, buffer(1), 2,
                    ManagedAddress.nullAddress(), buffer(3)) }
                val message = service.message(2)
                val alias = message.plus(2)
                assertThrows(RuntimeFault::class.java) { allocations.free(message) }
                assertThrows(RuntimeFault::class.java) { allocations.realloc(message, 4) }
                assertThrows(RuntimeFault::class.java) { allocations.requireFreeTarget(message) }
                assertThrows(RuntimeFault::class.java) { service.localFree(alias) }
                message.withNativeBorrow { assertThrows(RuntimeFault::class.java) { service.localFree(message) } }
                assertTrue(message.availableBytes() > 2)
                // A context-owned native message may also be a conversion input.
                assertTrue(service.wideChar(65001, 0, message, -1, ManagedAddress.nullAddress(), 0,
                    ManagedAddress.nullAddress(), ManagedAddress.nullAddress()) > 0)
                service.localFree(message)
                assertThrows(RuntimeFault::class.java) { alias.readWord8(0) }
                assertThrows(RuntimeFault::class.java) { service.localFree(message) }
                retained = service.message(5)
            }
            context().use { second -> entered(second) {
                assertThrows(RuntimeFault::class.java) { service.codePage(false) }
                assertThrows(RuntimeFault::class.java) { retained.readWord8(0) }
                assertThrows(RuntimeFault::class.java) { Language.currentState().windowsCodePages.localFree(retained) }
                assertEquals(0L, Language.currentState().windowsCodePages.error())
            } }
        }
        assertEquals(0, allocations.liveCount())
        context(false).use { denied -> entered(denied) {
            assertThrows(SecurityException::class.java) { Language.currentState().windowsCodePages.codePage(false) }
        } }
    }

    @Test fun localFreeWaitsForAnActiveNativeBorrow() {
        context().use { context -> entered(context) {
            val service = Language.currentState().windowsCodePages
            val address = service.message(2)
            val borrow = address.nativeAllocation()!!.borrow()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val started = CountDownLatch(1)
                val freeing = executor.submit {
                    context.enter()
                    try { started.countDown(); service.localFree(address) } finally { context.leave() }
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertThrows(TimeoutException::class.java) { freeing.get(100, TimeUnit.MILLISECONDS) }
                assertTrue(borrow.segment().byteSize() > 2)
                borrow.close()
                freeing.get(5, TimeUnit.SECONDS)
                assertThrows(RuntimeFault::class.java) { address.readWord8(0) }
                assertEquals(0, Language.currentState().nativeAllocations.liveCount())
            } finally { borrow.close(); executor.shutdownNow() }
        } }
    }

    @Test fun exactForeignProofAndStateRejectBeforeAnOutputEffect() {
        for ((name, operation) in operations) {
            val call = original(name)
            assertEquals(operation, validate(call))
            for ((field, value) in listOf("safety" to "safe", "convention" to "stdcall", "arity" to 42L)) {
                val bad = Json.parse(Json.stringify(call)) as MutableList<Any?>
                ((bad[6] as Map<*, *>)["foreignCall"] as MutableMap<String, Any?>)[field] = value
                assertThrows(RuntimeFault::class.java) { validate(bad) }
            }
            val bad = Json.parse(Json.stringify(call)) as MutableList<Any?>
            (((bad[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as MutableMap<String, Any?>)["unit"] = "main"
            assertThrows(RuntimeFault::class.java) { validate(bad) }
        }
        val call = original("pageInfo")
        for (backend in listOf("ast", "bytecode")) context().use { context -> entered(context) { language ->
            val target = program(language, backend, OriginalStdioChecks.rawModule(call, source())).entryTarget("entry")
            val output = buffer(18)
            assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, 932L, output, 7L)) }
            assertEquals(List(18) { 165L }, bytes(output, 18))
        } }
    }
}
