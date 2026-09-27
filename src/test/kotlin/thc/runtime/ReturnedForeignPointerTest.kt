// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Execute the actual compiled pointer helper through the package C call path.
 * This internal transport control does not fabricate original GHC import proof. */
class ReturnedForeignPointerTest {
    private class Offset(language: Language, link: PackageScalarLink) : RootNode(language) {
        @Child private var call = PackageScalarAccess(PackageScalarCall(link, link.abi.single()))
        override fun execute(frame: VirtualFrame): Any = call.executeAddress(frame.arguments, Unit)
    }
    private fun context(native: Boolean = true) = Context.newBuilder("thc").allowNativeAccess(native)
        .withContextProfile(ContextProfile.SYNCHRONOUS_TEST).build()
    private fun <T> entered(context: Context, body: (Language.State) -> T): T {
        context.initialize("thc"); context.enter()
        try { return body(Language.currentState()) } finally { context.leave() }
    }
    private fun offset(owner: Language.State): (ManagedAddress, Long) -> ManagedAddress {
        val bytes = javaClass.getResourceAsStream("/thc/cbits/package-pointer.bc")!!.use { it.readBytes() }
        val signature = PackageScalarSignature("thc_package_pointer_offset", "thc_package_pointer_offset",
            listOf("AddrRep", "Int64Rep"), "AddrRep")
        val link = PackageScalarLink("returned-pointer-control", "unused", "returned-pointer-control", "", bytes, listOf(signature))
        owner.packageCbits.link(link)
        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
        val target = Offset(language, link).callTarget
        return { address, count -> target.call(address, count) as ManagedAddress }
    }

    @Test fun returnedOwnedAliasKeepsBoundsBorrowAndDeallocatorIdentity(): Unit = context().use { context -> entered(context) { owner ->
        val offset = offset(owner)
        val allocation = owner.nativeAllocations.malloc(32)
        val alias = offset(allocation, 8)
        try {
            assertSame(allocation.nativeAllocation(), alias.nativeAllocation())
            assertNotNull(alias.returnedAddress()?.carrier)
            assertEquals(24L, alias.availableBytes())
            alias.writeNativeScalar(0, 8, 0x1020304050607080L)
            assertEquals(0x1020304050607080L, ManagedAddressRead.WORD64.read(allocation, 1))
            assertThrows(RuntimeFault::class.java) { alias.readWord8(24) }
            assertThrows(RuntimeFault::class.java) { alias.plus(-9).readWord8(0) }
            assertTrue(alias.plus(-8).sameLocation(allocation))
            assertEquals(8L, alias.difference(allocation))
            assertThrows(RuntimeFault::class.java) { owner.nativeAllocations.free(alias) }
        } finally { owner.nativeAllocations.free(allocation) }
        assertThrows(RuntimeFault::class.java) { alias.readWord8(0) }
    } }

    @Test fun managedSulongReturnRetainsCarrierForReadsWritesCopiesAndStrings(): Unit = context().use { context -> entered(context) { owner ->
        val offset = offset(owner)
        val bytes = ByteArray(80)
        val original = ManagedAddress.fromByteArray(bytes)
        val alias = offset(original, 4)
        assertNull(alias.nativeAllocation())
        assertNotNull(alias.returnedAddress()?.carrier)
        alias.writeWord8(0, 'a'.code.toLong())
        alias.writeWord8(1, 'b'.code.toLong())
        assertEquals('a'.code.toByte(), bytes[4])
        assertEquals(2L, alias.cStringLength())
        assertEquals("ab", alias.utf8())
        assertTrue(alias.sameLocation(alias.plus(0)))
        assertEquals(3L, alias.plus(3).difference(alias))
        alias.writeNativeScalar(1, 8, 0xfedcba9876543210UL.toLong(), true)
        assertEquals(0xfedcba9876543210UL.toLong(), ManagedAddressRead.WORD64.read(alias, 1, true))
        val source = ByteArray(32) { it.toByte() }
        alias.copyFromByteArray(source, 0, 32)
        val target = ByteArray(32)
        alias.copyToByteArray(target, 0, 32)
        assertArrayEquals(source, target)
        val vector = alias.readVectorBytes(0, 1)
        alias.writeVectorBytes(16, 1, vector)
        assertArrayEquals(source.copyOfRange(0, 16), bytes.copyOfRange(20, 36))
        alias.moveTo(alias.plus(2), 16)
        assertArrayEquals(source.copyOfRange(0, 16), bytes.copyOfRange(6, 22))
        assertThrows(RuntimeFault::class.java) { alias.copyNonOverlappingTo(alias.plus(1), 8) }
        alias.fill(4, 255)
        assertEquals(listOf(-1, -1, -1, -1), bytes.slice(4..7).map { it.toInt() })
        assertThrows(RuntimeFault::class.java) { alias.requireRange(0, -1) }
        assertThrows(RuntimeFault::class.java) { alias.requireRange(Long.MAX_VALUE, 1) }
    } }

    @Test fun returnedPointersKeepContextAndLibraryAccessGuards() {
        lateinit var pointer: ManagedAddress
        context().use { first -> entered(first) { owner ->
            pointer = offset(owner)(ManagedAddress.fromByteArray(byteArrayOf(1, 0)), 0)
            assertEquals(1L, pointer.readWord8(0))
            context().use { other -> entered(other) {
                assertThrows(RuntimeFault::class.java) { pointer.readWord8(0) }
            } }
            owner.packageCbits.close()
            assertThrows(RuntimeFault::class.java) { pointer.readWord8(0) }
        } }
        context(false).use { denied -> entered(denied) { owner ->
            assertThrows(RuntimeFault::class.java) { offset(owner) }
        } }
    }

    @Test fun managedAliasIdentityMatchesOriginalAndRepeatedReturns(): Unit = context().use { context -> entered(context) { owner ->
        val offset = offset(owner)
        val bytes = ByteArray(40) { it.toByte() }
        val original = ManagedAddress.fromByteArray(bytes)
        val alias = offset(original, 4)
        val repeated = offset(original, 4)
        // Exercise actual compiled C comparisons as well as the checked backing
        // path below: each transport has a different JVM wrapper.
        val carrier = alias.returnedAddress()!!.transport()
        val originalCarrier = owner.packageCbits.transport(original.plus(4))
        assertEquals(1, owner.packageCbits.memory("equal", carrier, originalCarrier))
        assertEquals(0L, owner.packageCbits.memory("difference", carrier, originalCarrier))
        assertEquals(1, owner.packageCbits.memory("equal", carrier, repeated.returnedAddress()!!.transport()))
        val unrelated = ManagedAddress.fromByteArray(ByteArray(40))
        assertNull(owner.packageCbits.managedAliasOffset(carrier, owner.packageCbits.transport(unrelated), 0, 40))
        assertEquals(4L, owner.packageCbits.managedAliasOffset(carrier, owner.packageCbits.transport(original), 0, 40))
        assertAll(
            { assertTrue(alias.sameLocation(original.plus(4)), "returned alias equals original view") },
            { assertTrue(alias.sameLocation(repeated), "separate calls retain allocation identity") },
            { assertEquals(4L, alias.difference(original), "same allocation displacement") },
            { assertEquals(0, alias.compareWithinAllocation(original.plus(4))) },
            { assertEquals(-1, alias.compareWithinAllocation(original.plus(5))) },
            { assertTrue(alias.overlaps(0, 12, original, 6, 12), "overlap with original view") },
            { assertThrows(RuntimeFault::class.java) { alias.copyNonOverlappingTo(original.plus(6), 12) } },
            { assertEquals(36L, alias.availableBytes(), "actual known remaining extent") },
            { assertThrows(RuntimeFault::class.java) { alias.readWord8(36) } }
        )
        val expected = bytes.copyOf().also { System.arraycopy(it, 4, it, 6, 12) }
        alias.moveTo(original.plus(6), 12)
        assertArrayEquals(expected, bytes)
        alias.copyNonOverlappingTo(original.plus(24), 8)
        assertArrayEquals(bytes.copyOfRange(4, 12), bytes.copyOfRange(24, 32))
        assertThrows(RuntimeFault::class.java) { alias.toNativeBits() }
        val allocation = ManagedAllocation.mutable(40, 8)
        val owned = ManagedAddress.fromAllocation(allocation)
        val ownedAlias = offset(owned, 4)
        assertSame(allocation, ownedAlias.cbitsOwner())
        allocation.shrink(12)
        assertEquals(8L, ownedAlias.availableBytes())
        assertThrows(RuntimeFault::class.java) { ownedAlias.readWord8(8) }
        assertTrue(ownedAlias.sameLocation(owned.plus(4)))
        val immutable = ManagedAddress.fromStaticBytes(byteArrayOf(1, 2, 3, 4, 0))
        val immutableAlias = offset(immutable, 1)
        assertEquals(2L, immutableAlias.readWord8(0))
        assertThrows(RuntimeFault::class.java) { immutableAlias.writeWord8(0, 9) }
    } }

    @Test fun returnedNativeComparisonDoesNotGrantNumericMemoryAuthority(): Unit = context().use { context -> entered(context) { owner ->
        val original = owner.nativeAllocations.malloc(24)
        try {
            val alias = offset(owner)(original, 4)
            val numeric = ManagedAddress.unownedNumeric(alias.toNativeBits())
            assertAll(
                { assertTrue(alias.sameLocation(numeric)) },
                { assertTrue(numeric.sameLocation(alias)) },
                { assertEquals(0L, alias.difference(numeric)) },
                { assertEquals(0L, numeric.difference(alias)) },
                { assertEquals(-1, alias.compareWithinAllocation(numeric.plus(1))) },
                { assertEquals(1, numeric.plus(1).compareWithinAllocation(alias)) },
                { assertThrows(RuntimeFault::class.java) { numeric.readWord8(0) } },
                { assertThrows(RuntimeFault::class.java) { numeric.writeWord8(0, 1) } }
            )
        } finally { owner.nativeAllocations.free(original) }
    } }

    @Test fun returnedBuffersUseDescriptorTransfersWithoutArrayExposure() {
        val output = ByteArrayOutputStream()
        Context.newBuilder("thc").allowNativeAccess(true).`in`(ByteArrayInputStream(byteArrayOf(4, 5)))
            .out(output).withContextProfile(ContextProfile.SYNCHRONOUS_TEST).build().use { context -> entered(context) { owner ->
            val bytes = ByteArray(8) { 90 }
            val pointer = offset(owner)(ManagedAddress.fromByteArray(bytes), 2)
            assertEquals(-1L, owner.stdio.close(-1))
            val errno = owner.stdio.errno()
            assertEquals(2L, owner.stdio.read(0, pointer, 4))
            assertArrayEquals(byteArrayOf(90, 90, 4, 5, 90, 90, 90, 90), bytes)
            assertEquals(0L, owner.stdio.read(0, pointer, 4))
            assertEquals(4L, owner.stdio.write(1, pointer, 4))
            assertArrayEquals(byteArrayOf(4, 5, 90, 90), output.toByteArray())
            assertEquals(errno, owner.stdio.errno())
        } }
        val failure = object : InputStream() {
            override fun read(): Int = error("bulk operation required")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                bytes[offset] = 17
                throw IOException("partial read")
            }
        }
        Context.newBuilder("thc").allowNativeAccess(true).`in`(failure)
            .withContextProfile(ContextProfile.SYNCHRONOUS_TEST).build().use { context -> entered(context) { owner ->
            val bytes = ByteArray(8) { 90 }
            val pointer = offset(owner)(ManagedAddress.fromByteArray(bytes), 2)
            assertEquals(-1L, owner.stdio.read(0, pointer, 4))
            assertArrayEquals(byteArrayOf(90, 90, 17, 90, 90, 90, 90, 90), bytes)
        } }
    }
}
