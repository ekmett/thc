// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
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
    private fun module(wrongArgument: Boolean = false): Map<String, Any?> {
        fun binding(name: String, primitive: String, argument: Map<String, Any>, result: Map<String, Any>): Map<String, Any?> {
            val formal = mapOf("id" to "arg", "lifted" to false, "rep" to argument)
            val call = listOf("app", listOf("prim", primitive), listOf(listOf("var", "arg", mapOf("rep" to argument))),
                listOf(false), false, false, mapOf("rep" to result))
            return mapOf("id" to name, "name" to name, "arity" to 1, "lifted" to true, "rep" to closure,
                "expr" to listOf("lam", listOf(formal), call, mapOf("rep" to closure, "resultRep" to result)))
        }
        return mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "SyntheticAddressConsumers", "instrument" to true,
            "constructors" to emptyList<Any>(), "bindings" to listOf(
                binding("toBits", "addr2Int#", if (wrongArgument) integer else address, integer),
                binding("fromBits", "int2Addr#", integer, address)))
    }
    private fun context(native: Boolean = true, inlining: Boolean = false) = Context.newBuilder("thc")
        .allowNativeAccess(native).allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))

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
                        assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                        valid(target)
                    }
                    return result
                }
                val literal = ManagedAddress.fromHex("616263")
                val header = ManagedAddress.fromAllocation(ManagedAllocation.immutable(byteArrayOf(7, 9, 11, 13), 8))
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
                targets.values.forEach { it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true); valid(it) }
                compiled = true
                exercise() // first calls after installation, with no settling calls
                compiled = false
                assertThrows(RuntimeFault::class.java) { call("toBits", 1L) }
                assertThrows(RuntimeFault::class.java) { call("fromBits", literal) }
                assertThrows(RuntimeFault::class.java) {
                    if (backend == "ast") Program(language, module(true)) else BytecodeProgram(language, module(true))
                }
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
                assertThrows(RuntimeFault::class.java) { StablePointers.current(null).make(Unit).toNativeBits() }
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

    @Test fun immutableImagesKeepTheirBytesAndExclusiveOnePastAliases() {
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
                for (original in listOf(allocation, literal)) {
                    val expected = original.rawBacking()
                    val escaped = listOf(original.rawBacking(), original.cbitsBacking()) +
                        if (original === allocation) listOf(owner.wholeBytesForPrimitive()) else emptyList()
                    // Exposures made before projection cannot later mutate its source.
                    val oldBuffer = cbits.buffer(original)
                    escaped.forEach { it.fill(0) }
                    val bits = original.toNativeBits()
                    original.rawBacking().fill(0)
                    original.cbitsBacking().fill(0)
                    val view = registry.transport(original)!!
                    val native = MemorySegment.ofAddress(InteropLibrary.getUncached().asPointer(view))
                        .reinterpret(expected.size.toLong())
                    assertArrayEquals(expected, native.toArray(ValueLayout.JAVA_BYTE))
                    assertArrayEquals(expected, original.rawBacking())
                    assertTrue(registry.recover(bits).sameLocation(original))
                    assertSame(oldBuffer, cbits.buffer(original.plus(1)))
                    Reference.reachabilityFence(view)
                }
                assertEquals(7L, allocation.readWord8(0))
                assertThrows(RuntimeFault::class.java) { allocation.writeAddressElementIndex(0, literal) }
                val pointerOwner = ManagedAllocation.mutable(8, 8)
                pointerOwner.writeAddressByteOffset(0, literal)
                assertThrows(RuntimeFault::class.java) { owner.copyFrom(pointerOwner, 0, 0, 8) }
                assertThrows(RuntimeFault::class.java) { ManagedAddress.fromAllocation(pointerOwner).toNativeBits() }

                // Include empty images and sizes on both sides of native alignment
                // boundaries. One-past is a valid alias, but never readable memory.
                val originals = (0..32).map { ManagedAddress.fromAllocation(ManagedAllocation.immutable(ByteArray(it), 8)) }
                val bases = originals.map(ManagedAddress::toNativeBits)
                for ((index, original) in originals.withIndex()) {
                    val end = bases[index] + original.cbitsSize()
                    assertTrue(registry.recover(end).sameLocation(original.plus(original.cbitsSize())))
                    assertThrows(RuntimeFault::class.java) { registry.recover(end).readWord8(0) }
                    for (other in originals.indices) if (other != index) {
                        val outside = java.lang.Long.compareUnsigned(end, bases[other]) < 0 ||
                            java.lang.Long.compareUnsigned(bases[index], bases[other] + originals[other].cbitsSize()) > 0
                        assertTrue(outside, "Immutable images must have disjoint inclusive address ranges")
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
