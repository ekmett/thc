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

/** Retained original ghc-internal declaration executed by explicitly synthetic callers. */
class OriginalMemcpyTest {
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val address = mapOf("kind" to "address", "primReps" to listOf("AddrRep"), "evaluated" to true)
    private val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val descriptor get() = Json.parse(javaClass.getResource("/core/original-memcpy-descriptor.json")!!.readText())
        as Map<String, Any?>

    private fun module(declaration: Map<String, Any?> = descriptor,
        stored: Map<Int, Map<String, Any?>> = emptyMap(),
        shadowForeignWithJoin: Boolean = false,
        canonical: Map<String, Any?> = descriptor,
        changeCall: (List<Any?>) -> List<Any?> = { it }): Map<String, Any?> {
        val tuple = canonical.getValue("resultRep") as Map<String, Any?>
        val fields = tuple.getValue("components") as List<Map<String, Any?>>
        val reps = (canonical.getValue("argumentReps") as List<Map<String, Any?>>).map { it + ("evaluated" to true) }
        val formals = reps.mapIndexed { index, rep ->
            mapOf("id" to "arg$index", "lifted" to false, "rep" to (stored[index] ?: rep))
        }
        val call = changeCall(listOf("app", listOf("var", "original-memcpy-id", mapOf("rep" to closure)),
            reps.mapIndexed { index, rep -> listOf("var", "arg$index", mapOf("rep" to rep)) },
            listOf(false, false, false, false), false, false,
            mapOf("rep" to tuple, "foreignCall" to declaration)))
        val callRegion = if (!shadowForeignWithJoin) call else {
            val joinFormals = reps.mapIndexed { index, rep ->
                mapOf("id" to "join$index", "lifted" to false, "rep" to rep)
            }
            val pair = listOf("app", listOf("con", "tuple2", 2, mapOf("rep" to closure)),
                listOf(listOf("var", "join3", mapOf("rep" to reps[3])),
                    listOf("var", "join0", mapOf("rep" to reps[0]))),
                listOf(false, false), false, false, mapOf("rep" to (tuple + ("evaluated" to true))))
            val join = mapOf("id" to "original-memcpy-id", "name" to "shadowedForeign", "lifted" to true,
                "rep" to closure, "expr" to listOf("lam", joinFormals, pair, mapOf("rep" to closure, "resultRep" to tuple)),
                "joinValueArity" to 4L, "joinResultRep" to tuple, "info" to mapOf("joinArity" to 4L))
            // The four-argument call is tail-positioned within its own tuple-returning join region.
            CoreJoins.validate(listOf(join), call, false)
            listOf("let", false, listOf(join), call, mapOf("rep" to tuple))
        }
        val body = listOf("case", callRegion, "result-tuple", listOf(
            listOf("data", "tuple2", listOf("result-state", "result-address"),
                listOf("var", "result-address", mapOf("rep" to address)),
                mapOf("binders" to fields.mapIndexed { index, rep ->
                    mapOf("id" to if (index == 0) "result-state" else "result-address", "lifted" to false, "rep" to rep)
                }))),
            mapOf("rep" to address, "binder" to mapOf("id" to "result-tuple", "lifted" to false,
                "rep" to (tuple + ("evaluated" to true)))))
        val binding = mapOf("id" to "copy", "name" to "copy", "arity" to 4, "lifted" to true,
            "rep" to closure, "expr" to listOf("lam", formals, body,
                mapOf("rep" to closure, "resultRep" to address)))
        return mapOf("schema" to 1, "module" to "SyntheticOriginalMemcpy", "unit" to "test", "ghc" to "9.14.1",
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
    private fun managed() = ManagedAddress.fromAllocation(ManagedAllocation.mutable(16, 8))
    private fun contents(base: ManagedAddress) = (0L until 16L).map(base::readWord8)
    private fun linuxNative() = System.getProperty("os.name") == "Linux" &&
        System.getProperty("os.arch") in setOf("amd64", "x86_64")
    private fun copy(target: RootCallTarget, destination: ManagedAddress, source: ManagedAddress, count: Long): ManagedAddress =
        Calls.target(target, arrayOf(0L, destination, source, count, Unit)) as ManagedAddress

    @Test fun originalArrayMemcpyRetainsBackingIdentityAndByteArraySafety() {
        // Unchanged FCallId exported in Alex Output, module SHA-256
        // 5ca87bc502b96c468c4b45647776d77693510b4bb2febd1af4fbaeb765091664.
        val original = Json.parse(javaClass.getResource("/core/original-array-memcpy-descriptor.json")!!.readText())
            as Map<String, Any?>
        for (backend in listOf("ast", "bytecode")) inside { language ->
            val guest = program(language, backend, module(original, canonical = original))
            val target = guest.entryTarget("copy")
            val bytes = ByteArray(16) { (it + 16).toByte() }
            val sources = listOf<Any>(bytes, ManagedAllocation.immutable(bytes.copyOf(), 8),
                ManagedAllocation.mutable(16, 8).also { allocation ->
                    bytes.forEachIndexed { i, value -> allocation.writeByte(i.toLong(), value.toLong()) }
                })
            val destinations = listOf<Any>(ByteArray(16), ManagedAllocation.mutable(16, 8))
            val empty = ByteArray(0)
            assertTrue((Calls.target(target, arrayOf(0L, empty, empty, 0L, Unit)) as ManagedAddress)
                .sameLocation(ManagedAddress.fromByteArray(empty)))
            fun run(source: Any, destination: Any) {
                val result = Calls.target(target, arrayOf(0L, destination, source, 8L, Unit)) as ManagedAddress
                val view = ManagedAddress.fromGuestByteArray(destination)
                assertTrue(result.sameLocation(view))
                assertEquals((16L until 24L).toList(), (0L until 8L).map(result::readWord8))
                result.writeWord8(15, 99)
                assertEquals(99L, view.readWord8(15))
            }
            for (source in sources) for (destination in destinations) run(source, destination)
            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
            valid(target)
            for (source in sources) for (destination in destinations) {
                val before = (guest.diagnostics().getValue("compiledEntries") as Number).toLong()
                run(source, destination)
                assertEquals(before + 1, (guest.diagnostics().getValue("compiledEntries") as Number).toLong())
                valid(target)
            }
            val destination = destinations.last()
            val before = contents(ManagedAddress.fromGuestByteArray(destination))
            for ((source, count) in listOf(sources.first() to -1L, sources.first() to 17L,
                destination to 1L, ManagedAllocation.mutable(16, 8).also { it.shrink(3) } to 4L)) {
                assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, destination, source, count, Unit)) }
                assertEquals(before, contents(ManagedAddress.fromGuestByteArray(destination)))
            }
            assertThrows(RuntimeFault::class.java) {
                Calls.target(target, arrayOf(0L, sources[1], sources.first(), 8L, Unit))
            }
            val pointers = ManagedAllocation.mutable(16, 8)
            val payload = managed()
            ManagedAddress.fromAllocation(pointers).writeAddressElementIndex(0, payload)
            Calls.target(target, arrayOf(0L, destination, pointers, 8L, Unit))
            assertSame(payload, ManagedAddress.fromGuestByteArray(destination).readAddressElementIndex(0))
            assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, destination, pointers, 7L, Unit)) }
            assertSame(payload, ManagedAddress.fromGuestByteArray(destination).readAddressElementIndex(0))
        }
    }

    @Test fun originalDescriptorCopiesManagedAndNativeRegionsOnFirstCompiledCalls() {
        for (backend in listOf("ast", "bytecode")) inside { language ->
            val guest = program(language, backend)
            val target = guest.entryTarget("copy")
            val sources = mutableListOf(managed(), ManagedAddress.fromByteArray(ByteArray(16)))
            val destinations = mutableListOf(managed(), ManagedAddress.fromByteArray(ByteArray(16)))
            if (linuxNative()) {
                sources += Language.currentState().nativeAllocations.malloc(16)
                destinations += Language.currentState().nativeAllocations.malloc(16)
            }
            fun run(source: ManagedAddress, destination: ManagedAddress) {
                (0L until 16L).forEach { source.writeWord8(it, it + 16); destination.writeWord8(it, 0) }
                val interior = destination.plus(2)
                assertSame(interior, copy(target, interior, source.plus(3), 5))
                assertEquals(listOf(0, 0, 19, 20, 21, 22, 23, 0, 0, 0, 0, 0, 0, 0, 0, 0).map(Int::toLong), contents(destination))
                assertEquals((16L until 32L).toList(), contents(source))
            }
            for (source in sources) for (destination in destinations) run(source, destination)
            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
            valid(target)
            for (source in sources) for (destination in destinations) {
                val before = (guest.diagnostics().getValue("compiledEntries") as Number).toLong()
                run(source, destination)
                assertEquals(before + 1, (guest.diagnostics().getValue("compiledEntries") as Number).toLong())
                valid(target)
            }
        }
    }

    @Test fun overlapRangesAndNativeOwnershipRejectBeforeWriting() {
        for (backend in listOf("ast", "bytecode")) inside { language ->
            val target = program(language, backend).entryTarget("copy")
            val array = ByteArray(16)
            val arrayBase = ManagedAddress.fromByteArray(array)
            val bases = mutableListOf(managed(), arrayBase)
            if (linuxNative()) bases += Language.currentState().nativeAllocations.malloc(16)
            for (base in bases) {
                (0L until 16L).forEach { base.writeWord8(it, it) }
                val adjacent = base.plus(8)
                assertSame(adjacent, copy(target, adjacent, base, 8))
                val end = base.plus(16)
                assertSame(end, copy(target, end, end, 0))
                val before = contents(base)
                fun rejects(destination: ManagedAddress, source: ManagedAddress, count: Long) {
                    assertThrows(RuntimeFault::class.java) { copy(target, destination, source, count) }
                    assertEquals(before, contents(base))
                }
                rejects(base.plus(4), base, 8)
                rejects(base, base.plus(4), 8)
                rejects(base, base, 1)
                for (count in listOf(-1L, 17L, Long.MAX_VALUE)) rejects(base, managed(), count)
                rejects(base, managed().plus(15), 2)
                rejects(base.plus(15), managed(), 2)
                rejects(base, ManagedAddress.unownedNumeric(1), 1)
                rejects(ManagedAddress.unownedNumeric(1), base, 1)
                rejects(ManagedAddress.fromHex("0000000000000000"), base, 8)
                assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, base, managed(), 1L, 0L)) }
                assertEquals(before, contents(base))
            }
            val beforeAlias = contents(arrayBase)
            assertThrows(RuntimeFault::class.java) { copy(target, ManagedAddress.fromByteArray(array).plus(4), arrayBase, 8) }
            assertEquals(beforeAlias, contents(arrayBase))
            if (linuxNative()) {
                val native = bases.last()
                val destination = managed()
                val before = contents(destination)
                val nativeBefore = contents(native)
                context().use { other ->
                    other.initialize("thc"); other.enter()
                    try {
                        // Use a root owned by the entered context; only the allocation is foreign.
                        val otherLanguage = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val otherTarget = program(otherLanguage, backend).entryTarget("copy")
                        assertThrows(RuntimeFault::class.java) { copy(otherTarget, destination, native, 1) }
                        assertThrows(RuntimeFault::class.java) { copy(otherTarget, native, destination, 1) }
                    } finally { other.leave() }
                }
                assertEquals(before, contents(destination))
                assertEquals(nativeBefore, contents(native))
                val alias = native.plus(4)
                Language.currentState().nativeAllocations.free(native)
                assertThrows(RuntimeFault::class.java) { copy(target, destination, alias, 0) }
                assertThrows(RuntimeFault::class.java) { copy(target, alias, destination, 0) }
                assertEquals(before, contents(destination))
            }
        }
    }

    @Test fun wholePointerCellsCopyAsReferencesAndPartialCellsReject() {
        for (backend in listOf("ast", "bytecode")) inside { language ->
            val target = program(language, backend).entryTarget("copy")
            val base = ManagedAddress.fromAllocation(ManagedAllocation.mutable(40, 8))
            val payload = managed()
            base.writeAddressElementIndex(0, payload)
            base.writeAddressElementIndex(1, base.plus(32))
            val destination = base.plus(16)
            assertSame(destination, copy(target, destination, base, 16))
            assertSame(payload, base.readAddressElementIndex(2))
            assertTrue(base.readAddressElementIndex(3).sameLocation(base.plus(32)))
            assertThrows(RuntimeFault::class.java) { copy(target, destination, base.plus(1), 8) }
            assertSame(payload, base.readAddressElementIndex(2))
            assertTrue(base.readAddressElementIndex(3).sameLocation(base.plus(32)))
        }
    }

    @Test fun malformedDescriptorsAndForgedProofsRejectInBothBackends() = inside { language ->
        for (backend in listOf("ast", "bytecode")) {
            fun reject(change: (MutableMap<String, Any?>) -> Unit) {
                val malformed = descriptor.toMutableMap().also(change)
                assertThrows(RuntimeFault::class.java) { program(language, backend, module(malformed)) }
            }
            reject { it["safety"] = "safe" }
            reject { it["convention"] = "capi" }
            reject { it["arity"] = 3L }
            reject { it["suppliedArity"] = 3L }
            reject { it["schema"] = 1.0 }
            reject { it["argumentReps"] = listOf(address) }
            reject { it["resultRep"] = address }
            for (unit in listOf(null, "foreign"))
                reject { it["target"] = (it.getValue("target") as Map<String, Any?>) + ("unit" to unit) }
            reject { it["target"] = (it.getValue("target") as Map<String, Any?>) + ("isFunction" to false) }
            reject { it["target"] = (it.getValue("target") as Map<String, Any?>) + ("kind" to "dynamic") }
            for (index in 0..3)
                assertThrows(RuntimeFault::class.java) { program(language, backend, module(stored = mapOf(index to long))) }
            // The same local join runs normally when it does not claim foreign-call authority.
            val ordinaryJoin = module(shadowForeignWithJoin = true, changeCall = { it.toMutableList().also { call ->
                call[6] = (call[6] as Map<String, Any?>) - "foreignCall"
            } })
            val ordinaryTarget = program(language, backend, ordinaryJoin).entryTarget("copy")
            val destination = managed()
            assertSame(destination, copy(ordinaryTarget, destination, managed(), 1))
            val shadowed = assertThrows(RuntimeFault::class.java) {
                program(language, backend, module(shadowForeignWithJoin = true))
            }
            assertTrue(shadowed.message.orEmpty().contains("unresolved original foreign variable required"), shadowed.message)
            for (head in listOf(listOf("var", "arg0", mapOf("rep" to closure)), listOf("prim", "memcpy", mapOf("rep" to closure))))
                assertThrows(RuntimeFault::class.java) {
                    program(language, backend, module(changeCall = { it.toMutableList().also { call -> call[1] = head } }))
                }
            assertThrows(RuntimeFault::class.java) {
                program(language, backend, module(changeCall = { it.toMutableList().also { call -> call[3] = listOf(true, false, false, false) } }))
            }
            assertThrows(RuntimeFault::class.java) {
                program(language, backend, module(changeCall = { it.toMutableList().also { call ->
                    call[2] = (call[2] as List<Any?>).toMutableList().also { arguments ->
                        arguments[0] = listOf("lit", "int", "0", mapOf("rep" to address))
                    }
                } }))
            }
        }
    }
}
