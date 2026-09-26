// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.UnsupportedMessageException
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json
import thc.Language
import java.io.File
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.ref.Reference

class NativeAddressTest {
    private val address = mapOf("kind" to "address", "primReps" to listOf("AddrRep"), "evaluated" to true)
    private val integer = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private fun module(): Map<String, Any?> {
        fun binding(name: String, primitive: String, argument: Map<String, Any>, result: Map<String, Any>): Map<String, Any?> {
            val formal = mapOf("id" to "arg", "lifted" to false, "rep" to argument)
            val call = listOf("app", listOf("prim", primitive), listOf(listOf("var", "arg", mapOf("rep" to argument))),
                listOf(false), false, false, mapOf("rep" to result))
            return mapOf("id" to name, "name" to name, "arity" to 1, "lifted" to true, "rep" to closure,
                "expr" to listOf("lam", listOf(formal), call, mapOf("rep" to closure, "resultRep" to result)))
        }
        return mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "SyntheticAddressConsumers", "instrument" to true,
            "constructors" to emptyList<Any>(), "bindings" to listOf(
                binding("toBits", "addr2Int#", address, integer),
                binding("fromBits", "int2Addr#", integer, address)))
    }
    private fun context(native: Boolean = true, inlining: Boolean = false) = Context.newBuilder("thc")
        .allowNativeAccess(native).allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun valid(target: RootCallTarget, label: String) =
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), label)

    /** Failure-only observation; never repairs the stub or executes another guest call. */
    private fun entryState(target: RootCallTarget): String {
        val jvmci = Class.forName("jdk.vm.ci.runtime.JVMCI").getMethod("getRuntime").invoke(null)
        val backend = Class.forName("jdk.vm.ci.runtime.JVMCIRuntime").getMethod("getHostJVMCIBackend").invoke(jvmci)
        val metaAccess = Class.forName("jdk.vm.ci.runtime.JVMCIBackend").getMethod("getMetaAccess").invoke(backend)
        val method = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
            .getDeclaredMethod("callBoundary", Array<Any?>::class.java)
        val boundary = Class.forName("jdk.vm.ci.meta.MetaAccessProvider")
            .getMethod("lookupJavaMethod", java.lang.reflect.Executable::class.java).invoke(metaAccess, method)
        val installed = Class.forName("jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod")
            .getMethod("hasCompiledCode").invoke(boundary)
        return "valid=${target.javaClass.getMethod("isValidLastTier").invoke(target)} boundary=$installed"
    }

    @Test fun exactBitsAndImmutableAliasesSurviveTheFirstCompiledEntryInBothBackends() {
        for (backend in listOf("ast", "bytecode")) for (inlining in listOf(false, true)) context(inlining = inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program: ExecutableProgram = if (backend == "ast") Program(language, module()) else BytecodeProgram(language, module())
                val targets = listOf("toBits", "fromBits").associateWith(program::entryTarget)
                var compiled = false
                fun call(name: String, argument: Any): Any? {
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    val target = targets.getValue(name)
                    val result = Calls.target(target, arrayOf(0L, argument))
                    if (compiled) {
                        val label = "$backend/inlining=$inlining/$name($argument)"
                        assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong()) {
                            "$label must enter compiled code exactly once; after call ${entryState(target)}"
                        }
                        valid(target, "$label must remain valid after the call")
                    }
                    return result
                }
                val literal = ManagedAddress.fromHex("616263")
                val header = ManagedAddress.fromHex("07090b0d")
                var literalBits: Long? = null
                fun exercise() {
                    for (bits in listOf(Long.MIN_VALUE, -4096L, -1L, 0L, 1L, 4096L, Long.MAX_VALUE))
                        assertEquals(bits, call("toBits", call("fromBits", bits)!!))
                    for (original in listOf(literal, header)) {
                        val bits = call("toBits", original) as Long
                        assertNotEquals(0L, bits)
                        for (offset in 0L..4L) {
                            val alias = call("fromBits", bits + offset) as ManagedAddress
                            assertTrue(alias.sameLocation(original.plus(offset)))
                            assertEquals(bits + offset, call("toBits", alias))
                            if (offset < 4) assertEquals(original.readWord8(offset), alias.readWord8(0))
                            assertThrows(RuntimeFault::class.java) { alias.writeWord8(0, 0) }
                        }
                    }
                    val bits = call("toBits", literal) as Long
                    literalBits?.let { assertEquals(it, bits) }; literalBits = bits
                }
                repeat(3) { exercise() }
                val runtime = Truffle.getRuntime()
                val targetType = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                targets.forEach { (name, target) ->
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    valid(target, "$backend/inlining=$inlining/$name after compilation")
                    // Match EntryValue.compile without executing a settling guest call.
                    runtime.javaClass.getMethod("bypassedInstalledCode", targetType).invoke(runtime, target)
                    valid(target, "$backend/inlining=$inlining/$name after boundary restoration")
                }
                compiled = true
                exercise() // first calls after installation, with no settling calls
                compiled = false
                assertThrows(RuntimeFault::class.java) { call("toBits", 1L) }
                assertThrows(RuntimeFault::class.java) { call("fromBits", literal) }
                assertEquals(0, language.handoffState.get().arguments.depth)
                assertEquals(0, language.handoffState.get().results.depth)
                assertEquals(0, language.handoffState.get().arguments.retainedReferences())
                assertEquals(0, language.handoffState.get().results.retainedReferences())
            } finally { context.leave() }
        }
    }

    @Test fun nativeTransportPointsAtRealBytesAndClosesWithItsOwner() {
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val registry = NativeAddresses.current(null)
                val original = ManagedAddress.fromHex("616263")
                val olderBuffer = Language.currentState(null).cbits().buffer(original.plus(1))
                val bits = original.toNativeBits()
                val view = registry.transport(original.plus(1))!!
                val interop = InteropLibrary.getUncached()
                assertTrue(interop.isPointer(view))
                assertEquals(bits, interop.asPointer(view)) // C ABI adds the original byte offset
                interop.toNative(olderBuffer)
                assertTrue(interop.isPointer(olderBuffer))
                assertEquals(bits, interop.asPointer(olderBuffer))
                val mutableBuffer = Language.currentState(null).cbits().buffer(ManagedAddress.fromByteArray(ByteArray(8)))
                interop.toNative(mutableBuffer)
                assertFalse(interop.isPointer(mutableBuffer))
                // Only a currently live owned transport supplies this test address.
                // Never reinterpret an arbitrary input integer in the runtime.
                val native = MemorySegment.ofAddress(interop.asPointer(view)).reinterpret(4)
                assertArrayEquals(byteArrayOf(97, 98, 99, 0), native.toArray(ValueLayout.JAVA_BYTE))
                System.gc()
                assertEquals(98L, registry.recover(bits + 1).readWord8(0))
                val md5 = ManagedAddress.fromByteArray(ByteArray(96))
                val digest = ManagedAddress.fromByteArray(ByteArray(16))
                ManagedMd5.init(md5)
                ManagedMd5.update(md5, original, 3)
                ManagedMd5.finish(digest, md5)
                assertEquals("900150983cd24fb0d6963f7d28e17f72", (0L until 16L).joinToString("") { "%02x".format(digest.readWord8(it)) })
                registry.close()
                assertFalse(interop.isPointer(view))
                assertFalse(interop.isPointer(olderBuffer))
                assertThrows(UnsupportedMessageException::class.java) { interop.asPointer(view) }
                assertThrows(UnsupportedMessageException::class.java) { interop.asPointer(olderBuffer) }
                assertThrows(RuntimeFault::class.java) { registry.recover(bits) }
                assertThrows(RuntimeFault::class.java) { original.toNativeBits() }
                Reference.reachabilityFence(view)
            } finally { context.leave() }
        }
    }

    @Test fun arbitraryIntegersAndUnsupportedManagedDomainsNeverGrantMemoryAccess() {
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val registry = NativeAddresses.current(null)
                for (bits in listOf(Long.MIN_VALUE, -1L, 1L, Long.MAX_VALUE)) {
                    val opaque = registry.recover(bits)
                    assertEquals(bits, opaque.toNativeBits())
                    assertThrows(RuntimeFault::class.java) { opaque.readWord8(0) }
                    assertThrows(RuntimeFault::class.java) { opaque.writeWord8(0, 1) }
                    assertThrows(RuntimeFault::class.java) { opaque.cbitsBacking() }
                }
                val bytes = byteArrayOf(1, 2, 3)
                val mutable = ManagedAddress.fromByteArray(bytes)
                assertThrows(RuntimeFault::class.java) { mutable.toNativeBits() }
                assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
                assertThrows(RuntimeFault::class.java) { ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8)).toNativeBits() }
                val stable = StablePointers.current(null)
                val handle = stable.make(Unit)
                val token = handle.toNativeBits()
                assertSame(Unit, stable.dereference(registry.recover(token)))
                assertThrows(RuntimeFault::class.java) { registry.recover(token).readWord8(0) }
                stable.free(handle)
                assertThrows(RuntimeFault::class.java) { stable.dereference(registry.recover(token)) }
            } finally { context.leave() }
        }
        context(native = false).use { context ->
            context.initialize("thc"); context.enter()
            try {
                assertEquals(-1L, NativeAddresses.current(null).recover(-1).toNativeBits())
                assertEquals(0L, ManagedAddress.nullAddress().toNativeBits())
                assertThrows(RuntimeFault::class.java) { ManagedAddress.fromHex("41").toNativeBits() }
            } finally { context.leave() }
        }
    }

    @Test fun immutableHeapBuffersStayReadOnlyWithoutPromotionAndStaticLiteralsKeepNativeImages() {
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val registry = NativeAddresses.current(null)
                val input = byteArrayOf(7, 9, 11, 13, 17, 19, 23, 29)
                val owner = ManagedAllocation.immutable(input, 8)
                val allocation = ManagedAddress.fromAllocation(owner)
                val literal = ManagedAddress.fromHex("616263")
                input.fill(0)
                val cbits = Language.currentState(null).cbits()
                val interop = InteropLibrary.getUncached()
                for (original in listOf(allocation, literal)) {
                    val expected = original.rawBacking()
                    val escaped = listOf(original.rawBacking(), original.cbitsBacking()) +
                        if (original === allocation) listOf(owner.wholeBytesForPrimitive()) else emptyList()
                    // Writable snapshots cannot mutate the original readonly storage.
                    val oldBuffer = cbits.buffer(original)
                    escaped.forEach { it.fill(0) }
                    original.rawBacking().fill(0)
                    original.cbitsBacking().fill(0)
                    assertArrayEquals(expected, original.rawBacking())
                    assertFalse(interop.isBufferWritable(oldBuffer))
                    assertThrows(UnsupportedMessageException::class.java) { interop.writeBufferByte(oldBuffer, 0, 0) }
                    val observed = ByteArray(expected.size)
                    interop.readBuffer(oldBuffer, 0, observed, 0, observed.size)
                    assertArrayEquals(expected, observed)
                    if (original === allocation) {
                        assertThrows(RuntimeFault::class.java) { original.toNativeBits() }
                        assertNull(registry.transport(original))
                        interop.toNative(oldBuffer)
                        assertFalse(interop.isPointer(oldBuffer))
                        assertThrows(UnsupportedMessageException::class.java) { interop.asPointer(oldBuffer) }
                        assertFalse(original.cbitsSegment().isNative)
                    } else {
                        val bits = original.toNativeBits()
                        val view = registry.transport(original)!!
                        val native = MemorySegment.ofAddress(interop.asPointer(view)).reinterpret(expected.size.toLong())
                        assertArrayEquals(expected, native.toArray(ValueLayout.JAVA_BYTE))
                        assertTrue(registry.recover(bits).sameLocation(original))
                        interop.toNative(oldBuffer)
                        assertEquals(bits, interop.asPointer(oldBuffer))
                        Reference.reachabilityFence(view)
                    }
                    assertSame(oldBuffer, cbits.buffer(original.plus(1)))
                }
                assertEquals(7L, allocation.readWord8(0))
                assertThrows(RuntimeFault::class.java) { allocation.writeAddressElementIndex(0, literal) }
                val pointerOwner = ManagedAllocation.mutable(8, 8)
                pointerOwner.writeAddressByteOffset(0, literal)
                assertThrows(RuntimeFault::class.java) { owner.copyFrom(pointerOwner, 0, 0, 8) }
                assertThrows(RuntimeFault::class.java) { ManagedAddress.fromAllocation(pointerOwner).toNativeBits() }

                // Include empty pinned arrays and sizes on both sides of native alignment
                // boundaries. One-past is a valid alias, but never readable memory.
                val originals = (0..32).map { ManagedAddress.fromGuestByteArray(PinnedMemory.allocate(it.toLong(), 8)) }
                val bases = originals.map(ManagedAddress::toNativeBits)
                for ((index, original) in originals.withIndex()) {
                    val end = bases[index] + original.cbitsSize()
                    assertTrue(registry.recover(end).sameLocation(original.plus(original.cbitsSize())))
                    assertThrows(RuntimeFault::class.java) { registry.recover(end).readWord8(0) }
                    for (other in originals.indices) if (other != index) {
                        val outside = java.lang.Long.compareUnsigned(end, bases[other]) < 0 ||
                            java.lang.Long.compareUnsigned(bases[index], bases[other] + originals[other].cbitsSize()) > 0
                        assertTrue(outside, "Pinned allocations must have disjoint inclusive address ranges")
                    }
                }
                // Immutable hardening must not replace existing mutable aliases.
                val mutable = ManagedAllocation.mutable(8, 8)
                assertSame(mutable.rawBytesIfPointerFree(), mutable.exposeToNative())
                assertSame(mutable.rawBytesIfPointerFree(), mutable.wholeBytesForPrimitive())
            } finally { context.leave() }
        }
    }

    @Test fun numericalResolutionCannotCrossContextOwnership() {
        context().use { first -> context().use { second ->
            first.initialize("thc"); second.initialize("thc")
            val original = ManagedAddress.fromHex("41")
            first.enter()
            val firstBits = try { original.toNativeBits() } finally { first.leave() }
            second.enter()
            val secondBits = try {
                val foreign = NativeAddresses.current(null).recover(firstBits)
                assertEquals(firstBits, foreign.toNativeBits())
                assertThrows(RuntimeFault::class.java) { foreign.readWord8(0) }
                assertThrows(RuntimeFault::class.java) { foreign.cbitsBacking() }
                original.toNativeBits().also {
                    assertNotEquals(firstBits, it)
                    assertTrue(NativeAddresses.current(null).recover(it).sameLocation(original))
                }
            } finally { second.leave() }
            first.enter()
            try {
                assertTrue(NativeAddresses.current(null).recover(firstBits).sameLocation(original))
                val foreign = NativeAddresses.current(null).recover(secondBits)
                assertEquals(secondBits, foreign.toNativeBits())
                assertThrows(RuntimeFault::class.java) { foreign.readWord8(0) }
            } finally { first.leave() }
        } }
    }

    @Test fun nativeGhcOracleRetainsAllBitsAndActualPointerAliases() {
        val root = File(System.getProperty("thc.projectRoot"))
        val prefix = "build/native-addresses"
        val manifest = Json.parse(File(root, "$prefix/manifest.json").readText()) as Map<String, Any?>
        assertEquals("9.14.1", manifest["ghc"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/NativeAddressNative.hs", "test/haskell-fixtures/NativeAddressFixtures.hs"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json"), "$prefix/")
        val oracle = Json.parse(File(root, "$prefix/oracle.json").readText()) as Map<String, Any?>
        assertEquals(List(9) { true }, oracle["observations"])
    }
}
