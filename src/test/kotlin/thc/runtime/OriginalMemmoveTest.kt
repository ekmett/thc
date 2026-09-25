// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json
import thc.Language

/** Actual ghc-internal FCallId descriptor from installed Core module
 * sha256 2e57cef6400ed7113800a3e3f607b356b3a5380e5eb50f1e0116abc4338998c0,
 * executed through a small synthetic caller. */
class OriginalMemmoveTest {
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val address = mapOf("kind" to "address", "primReps" to listOf("AddrRep"), "evaluated" to true)
    private val descriptor get() = Json.parse(javaClass.getResource("/core/original-memmove-descriptor.json")!!.readText())
        as Map<String, Any?>

    private fun module(declaration: Map<String, Any?> = descriptor): Map<String, Any?> {
        // Build the caller from the genuine shape even when a negative control
        // mutates only the declaration under test.
        val canonical = descriptor
        val tuple = canonical.getValue("resultRep") as Map<String, Any?>
        val fields = tuple.getValue("components") as List<Map<String, Any?>>
        val formals = (canonical.getValue("argumentReps") as List<Map<String, Any?>>).mapIndexed { index, rep ->
            mapOf("id" to "arg$index", "lifted" to false, "rep" to (rep + ("evaluated" to true)))
        }
        val call = listOf("app", listOf("var", "original-memmove-id", mapOf("rep" to closure)),
            formals.map { listOf("var", it.getValue("id"), mapOf("rep" to it.getValue("rep"))) },
            listOf(false, false, false, false), false, false,
            mapOf("rep" to tuple, "foreignCall" to declaration))
        val body = listOf("case", call, "result-tuple", listOf(
            listOf("data", "tuple2", listOf("result-state", "result-address"),
                listOf("var", "result-address", mapOf("rep" to address)),
                mapOf("binders" to fields.mapIndexed { index, rep ->
                    mapOf("id" to if (index == 0) "result-state" else "result-address", "lifted" to false, "rep" to rep)
                }))),
            mapOf("rep" to address, "binder" to mapOf("id" to "result-tuple", "lifted" to false,
                "rep" to (tuple + ("evaluated" to true)))))
        val binding = mapOf("id" to "move", "name" to "move", "arity" to 4, "lifted" to true,
            "rep" to closure, "expr" to listOf("lam", formals, body,
                mapOf("rep" to closure, "resultRep" to address)))
        return mapOf("schema" to 1, "module" to "SyntheticOriginalMemmove", "unit" to "test", "ghc" to "9.14.1",
            "instrument" to true, "bindings" to listOf(binding), "constructors" to listOf(
                mapOf("id" to "tuple2", "name" to "(#,#)", "kind" to "unboxed-tuple", "arity" to 2, "tag" to 1)))
    }

    private fun context() = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun <T> inside(block: (Language) -> T): T = context().use { context ->
        context.initialize("thc"); context.enter()
        try { block(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun program(language: Language, backend: String, source: Map<String, Any?> = module()): ExecutableProgram =
        if (backend == "ast") Program(language, source) else BytecodeProgram(language, source)

    @Test fun originalDescriptorMovesOverlappingManagedAndNativeRegionsOnFirstCompiledCall() {
        for (backend in listOf("ast", "bytecode")) inside { language ->
            val guest = program(language, backend)
            val target = guest.entryTarget("move")
            fun move(destination: ManagedAddress, source: ManagedAddress, count: Long): ManagedAddress =
                Calls.target(target, arrayOf(0L, destination, source, count, Unit)) as ManagedAddress
            fun contents(base: ManagedAddress) = (0L until 16L).map(base::readWord8)
            fun run(base: ManagedAddress, errors: Boolean = false) {
                (0L until 16L).forEach { base.writeWord8(it, it) }
                val right = base.plus(4)
                assertSame(right, move(right, base, 8))
                assertEquals(listOf(0, 1, 2, 3, 0, 1, 2, 3, 4, 5, 6, 7, 12, 13, 14, 15).map(Int::toLong), contents(base))
                (0L until 16L).forEach { base.writeWord8(it, it) }
                assertSame(base, move(base, right, 8))
                assertEquals(listOf(4, 5, 6, 7, 8, 9, 10, 11, 8, 9, 10, 11, 12, 13, 14, 15).map(Int::toLong), contents(base))
                val end = base.plus(16)
                assertSame(end, move(end, end, 0))
                if (!errors) return
                val before = contents(base)
                for (count in listOf(-1L, 17L, Long.MAX_VALUE)) {
                    assertThrows(RuntimeFault::class.java) { move(base, right, count) }
                    assertEquals(before, contents(base))
                }
                assertThrows(RuntimeFault::class.java) { move(ManagedAddress.fromHex("0000000000000000"), base, 8) }
                assertEquals(before, contents(base))
            }
            val managed = ManagedAddress.fromAllocation(ManagedAllocation.mutable(16, 8))
            run(managed, errors = true)
            repeat(2) { run(ManagedAddress.fromByteArray(ByteArray(16))) }
            val native = if (System.getProperty("os.name") == "Linux" &&
                System.getProperty("os.arch") in setOf("amd64", "x86_64"))
                Language.currentState().nativeAllocations.malloc(16) else null
            native?.let { run(it) }
            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
            valid(target)
            val before = (guest.diagnostics().getValue("compiledEntries") as Number).toLong()
            run(ManagedAddress.fromAllocation(ManagedAllocation.mutable(16, 8)))
            assertEquals(before + 3, (guest.diagnostics().getValue("compiledEntries") as Number).toLong())
            valid(target)
            if (native != null) {
                val beforeNative = (guest.diagnostics().getValue("compiledEntries") as Number).toLong()
                run(native)
                assertEquals(beforeNative + 3, (guest.diagnostics().getValue("compiledEntries") as Number).toLong())
                valid(target)
                val alias = native.plus(4)
                Language.currentState().nativeAllocations.free(native)
                assertThrows(RuntimeFault::class.java) { move(alias, alias, 0) }
            }
        }
    }

    @Test fun pointerCellsRelocateWithoutFabricatingAddressBits() {
        val owner = ManagedAllocation.mutable(40, 8)
        val base = ManagedAddress.fromAllocation(owner)
        val payload = ManagedAddress.fromByteArray(ByteArray(8))
        base.writeAddressElementIndex(0, payload)
        base.writeAddressElementIndex(1, base.plus(32))
        val destination = base.plus(8)
        assertSame(destination, base.moveTo(destination, 16))
        assertSame(payload, base.readAddressElementIndex(1))
        assertTrue(base.readAddressElementIndex(2).sameLocation(base.plus(32)))
        assertThrows(RuntimeFault::class.java) { base.plus(1).moveTo(base.plus(16), 8) }
    }

    @Test fun malformedOriginalDescriptorAndForgedHeadRejectBeforeExecution() = inside { language ->
        assertThrows(RuntimeFault::class.java) {
            CoreMemmoveForeign.validateHead(listOf("var", "forged-defined-head", mapOf("rep" to closure)), true)
        }
        for (backend in listOf("ast", "bytecode")) {
            fun reject(change: (MutableMap<String, Any?>) -> Unit) {
                val malformed = descriptor.toMutableMap().also(change)
                assertThrows(RuntimeFault::class.java) { program(language, backend, module(malformed)) }
            }
            reject { it["safety"] = "safe" }
            reject { it["arity"] = 3L }
            reject { it["argumentReps"] = listOf(address) }
            reject { it["resultRep"] = address }
            reject { it["target"] = (it.getValue("target") as Map<String, Any?>) + ("unit" to "foreign") }
        }
    }
}
