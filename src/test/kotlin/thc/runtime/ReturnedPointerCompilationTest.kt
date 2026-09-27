// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout

class ReturnedPointerCompilationTest {
    private class AddressResult(language: Language, call: PackageScalarCall) : RootNode(language) {
        @Child private var access = PackageScalarAccess(call)
        override fun execute(frame: VirtualFrame): Any = access.executeAddress(frame.arguments, Unit)
    }

    private fun module(): Map<String, Any?> {
        val address = mapOf("kind" to "address", "primReps" to listOf("AddrRep"), "evaluated" to true)
        val index = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
        val word32 = mapOf("kind" to "long", "primReps" to listOf("Word32Rep"), "evaluated" to true)
        val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val parameters = listOf(mapOf("id" to "p", "lifted" to false, "rep" to address),
            mapOf("id" to "i", "lifted" to false, "rep" to index))
        val call = listOf("app", listOf("prim", "indexWord32OffAddr#"),
            listOf(listOf("var", "p", mapOf("rep" to address)), listOf("var", "i", mapOf("rep" to index))),
            listOf(false, false), false, false, mapOf("rep" to word32))
        return mapOf("instrument" to true, "constructors" to emptyList<Any>(),
            "bindings" to listOf(mapOf("id" to "read", "name" to "read", "arity" to 2,
                "lifted" to true, "rep" to closure,
                "expr" to listOf("lam", parameters, call, mapOf("rep" to closure, "resultRep" to word32)))))
    }

    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))

    @Test fun dynamicReturnedAliasEqualityRetainsItsFirstCompiledEntry() {
        val address = mapOf("kind" to "address", "primReps" to listOf("AddrRep"), "evaluated" to true)
        val result = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
        val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val parameters = listOf("left", "right").map { mapOf("id" to it, "lifted" to false, "rep" to address) }
        val call = listOf("app", listOf("prim", "eqAddr#"),
            listOf("left", "right").map { listOf("var", it, mapOf("rep" to address)) },
            listOf(false, false), false, false, mapOf("rep" to result))
        val module = mapOf("instrument" to true, "constructors" to emptyList<Any>(),
            "bindings" to listOf(mapOf("id" to "equal", "name" to "equal", "arity" to 2,
                "lifted" to true, "rep" to closure,
                "expr" to listOf("lam", parameters, call, mapOf("rep" to closure, "resultRep" to result)))))
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc")
            .allowNativeAccess(true).allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000")
            .withContextProfile(ContextProfile.SYNCHRONOUS_TEST).build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val owner = Language.currentState()
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val symbol = "thc_package_pointer_offset"
                    val signature = PackageScalarSignature(symbol, symbol, listOf("AddrRep", "Int64Rep"), "AddrRep")
                    val bytes = javaClass.getResourceAsStream("/thc/cbits/package-pointer.bc")!!.use { it.readBytes() }
                    val link = PackageScalarLink("compiled-equality-control", "unused", "compiled-equality-control", "", bytes, listOf(signature))
                    owner.packageCbits.link(link)
                    val offset = AddressResult(language, PackageScalarCall(link, signature)).callTarget
                    val original = ManagedAddress.fromByteArray(ByteArray(16))
                    val alias = offset.call(original, 4L) as ManagedAddress
                    val repeated = offset.call(original, 4L) as ManagedAddress
                    assertNotNull(alias.returnedAddress()!!.backing)
                    val inputs = listOf(Triple(original.plus(4), alias, 1L), Triple(alias, original.plus(4), 1L),
                        Triple(alias, repeated, 1L), Triple(alias, alias.plus(1), 0L),
                        Triple(original, original.plus(1), 0L))
                    val program: ExecutableProgram = if (backend == "ast") Program(language, module)
                        else BytecodeProgram(language, module)
                    val target = program.entryTarget("equal")
                    owner.threads.enterCurrent()
                    try {
                        fun equal(left: ManagedAddress, right: ManagedAddress) =
                            Calls.target(target, arrayOf(0L, left, right)) as Long
                        repeat(5) { for ((left, right, expected) in inputs) assertEquals(expected, equal(left, right)) }
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        valid(target)
                        val runtime = Truffle.getRuntime()
                        runtime.javaClass.getMethod("bypassedInstalledCode",
                            Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target)
                        for ((left, right, expected) in inputs) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertEquals(expected, equal(left, right), backend)
                            assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong(), backend)
                            valid(target)
                        }
                    } finally { owner.threads.leaveCurrent() }
                } finally { context.leave() }
            }
    }

    /** The C helper really returns both managed aliases and unknown native
     * pointers. The independent arena, not a fabricated malloc owner, owns the
     * latter. This transport control does not manufacture GHC import proof. */
    @Test fun dynamicManagedAndExternalReadsRetainTheirFirstCompiledEntry() {
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc")
            .allowNativeAccess(true).allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000")
            .withContextProfile(ContextProfile.SYNCHRONOUS_TEST).build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val owner = Language.currentState()
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val signatures = listOf("offset", "read_address").map { operation ->
                        val symbol = "thc_package_pointer_$operation"
                        PackageScalarSignature(symbol, symbol, listOf("AddrRep", "Int64Rep"), "AddrRep")
                    }
                    val bytes = javaClass.getResourceAsStream("/thc/cbits/package-pointer.bc")!!.use { it.readBytes() }
                    val link = PackageScalarLink("compiled-return-control", "unused", "compiled-return-control", "", bytes, signatures)
                    owner.packageCbits.link(link)
                    val calls = signatures.map { AddressResult(language, PackageScalarCall(link, it)).callTarget }
                    val original = ManagedAddress.fromByteArray(ByteArray(16))
                    original.writeNativeScalar(1, 4, 0x89abcdefL)
                    val alias = calls[0].call(original, 4L) as ManagedAddress
                    assertNotNull(alias.returnedAddress()!!.backing)
                    Arena.ofConfined().use { arena ->
                        val native = arena.allocate(16, 8)
                        native.set(ValueLayout.JAVA_INT, 0, 0xfedcba98UL.toInt())
                        val slot = owner.nativeAllocations.malloc(8)
                        val external = try {
                            slot.writeNativeScalar(0, 8, native.address())
                            calls[1].call(slot, 0L) as ManagedAddress
                        } finally { owner.nativeAllocations.free(slot) }
                        assertNull(external.returnedAddress()!!.backing)
                        assertThrows(RuntimeFault::class.java) { external.availableBytes() }
                        val program: ExecutableProgram = if (backend == "ast") Program(language, module())
                            else BytecodeProgram(language, module())
                        val target = program.entryTarget("read")
                        val inputs = listOf(original.plus(4) to 0x89abcdefL, alias to 0x89abcdefL, external to 0xfedcba98L)
                        owner.threads.enterCurrent()
                        try {
                            fun read(pointer: ManagedAddress) = Calls.target(target, arrayOf(0L, pointer, 0L)) as Long
                            repeat(5) { for ((pointer, expected) in inputs) assertEquals(expected, read(pointer)) }
                            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                            valid(target)
                            // Restore only the shared host entry prerequisite,
                            // as EntryValue.compile does; no settling guest call.
                            val runtime = Truffle.getRuntime()
                            runtime.javaClass.getMethod("bypassedInstalledCode",
                                Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target)
                            valid(target)
                            for ((pointer, expected) in inputs) {
                                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                assertEquals(expected, read(pointer), backend)
                                assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong(), backend)
                                valid(target)
                            }
                            assertThrows(RuntimeFault::class.java) { alias.requireRange(12, 1) }
                            assertThrows(RuntimeFault::class.java) { external.requireRange(0, -1) }
                            assertThrows(RuntimeFault::class.java) { external.requireRange(Long.MAX_VALUE, 1) }
                        } finally { owner.threads.leaveCurrent() }
                    }
                } finally { context.leave() }
            }
    }
}
