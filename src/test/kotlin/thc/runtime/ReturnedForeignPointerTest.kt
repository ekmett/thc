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
