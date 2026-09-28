// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.*;
import java.util.*;
import java.util.function.BiFunction;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.withContextProfile;

/** Execute the actual compiled pointer helper through the package C call path.
 * This internal transport control does not fabricate original GHC import proof. */
public class ReturnedForeignPointerTest {
    private static final class Offset extends RootNode {
        @Child private PackageScalarAccess call;
        Offset(Language language, PackageScalarLink link) {
            super(language);
            assertEquals(1, link.getAbi().size());
            call = new PackageScalarAccess(new PackageScalarCall(link, link.getAbi().getFirst()));
        }
        @Override public Object execute(VirtualFrame frame) { return call.executeAddress(frame.getArguments(), thc.runtime.Unit.INSTANCE); }
    }
    private Context context() { return context(true); }
    private Context context(boolean nativeAccess) {
        return withContextProfile(Context.newBuilder("thc").allowNativeAccess(nativeAccess), ContextProfile.SYNCHRONOUS_TEST).build();
    }
    @FunctionalInterface private interface Entered { void run(Language.State owner) throws Exception; }
    private void entered(Context context, Entered body) throws Exception {
        context.initialize("thc"); context.enter();
        try { body.run(Language.currentState()); } finally { context.leave(); }
    }
    private BiFunction<ManagedAddress, Long, ManagedAddress> offset(Language.State owner) throws IOException {
        final byte[] bytes;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/thc/cbits/package-pointer.bc"))) { bytes = input.readAllBytes(); }
        var signature = new PackageScalarSignature("thc_package_pointer_offset", "thc_package_pointer_offset", List.of("AddrRep", "Int64Rep"), "AddrRep");
        var link = new PackageScalarLink("returned-pointer-control", "unused", "returned-pointer-control", "", bytes, List.of(signature));
        owner.getPackageCbits().link(link);
        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
        var target = new Offset(language, link).getCallTarget();
        return (address, count) -> (ManagedAddress) target.call(address, count);
    }

    @Test public void returnedOwnedAliasKeepsBoundsBorrowAndDeallocatorIdentity() throws Exception {
        try (var context = context()) { entered(context, owner -> {
            var offset = offset(owner); var allocation = owner.getNativeAllocations().malloc(32); var alias = offset.apply(allocation, 8L);
            try {
                assertSame(allocation.nativeAllocation(), alias.nativeAllocation()); assertNotNull(alias.returnedAddress().getCarrier());
                assertEquals(24L, alias.availableBytes()); alias.writeNativeScalar(0, 8, 0x1020304050607080L);
                assertEquals(0x1020304050607080L, ManagedAddressRead.WORD64.read(allocation, 1));
                assertThrows(RuntimeFault.class, () -> alias.readWord8(24)); assertThrows(RuntimeFault.class, () -> alias.plus(-9).readWord8(0));
                assertTrue(alias.plus(-8).sameLocation(allocation)); assertEquals(8L, alias.difference(allocation));
                assertThrows(RuntimeFault.class, () -> owner.getNativeAllocations().free(alias));
            } finally { owner.getNativeAllocations().free(allocation); }
            assertThrows(RuntimeFault.class, () -> alias.readWord8(0)); assertThrows(RuntimeFault.class, () -> alias.sameLocation(allocation));
            assertThrows(RuntimeFault.class, () -> allocation.sameLocation(alias)); assertThrows(RuntimeFault.class, () -> alias.sameLocation(alias));
        }); }
    }

    @Test public void rtsComparisonRejectsFreedReturnedAllocationInEitherOrder() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Linux") && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")));
        var document = new LinkedHashMap<String, Object>(StackInfoTestLayout.document(StackInfoTestLayout.fields()));
        @SuppressWarnings("unchecked") var compiler = new LinkedHashMap<String, Object>((Map<String, Object>) document.get("compiler"));
        compiler.put("abi", "inplace"); document.put("compiler", compiler);
        var layout = TargetLayout.fromDocument(document);
        var proof = CoreRepresentations.parse(Map.of("kind", "address", "primReps", List.of("AddrRep"), "evaluated", true));
        try (var context = context()) { entered(context, owner -> {
            var flags = CoreDataLabels.fromCore("RtsFlags", proof, layout); var allocation = owner.getNativeAllocations().malloc(32);
            final ManagedAddress alias;
            try {
                alias = offset(owner).apply(allocation, 8L);
                assertSame(allocation.nativeAllocation(), alias.nativeAllocation()); assertNotNull(alias.returnedAddress().getCarrier());
                assertFalse(flags.sameLocation(alias)); assertFalse(alias.sameLocation(flags));
            } finally { owner.getNativeAllocations().free(allocation); }
            assertAll("RTS equality must retain the returned allocation lifetime check",
                () -> assertThrows(RuntimeFault.class, () -> flags.sameLocation(alias)),
                () -> assertThrows(RuntimeFault.class, () -> alias.sameLocation(flags)));
        }); }
    }

    @Test public void managedSulongReturnRetainsCarrierForReadsWritesCopiesAndStrings() throws Exception {
        try (var context = context()) { entered(context, owner -> {
            var offset = offset(owner); var bytes = new byte[80]; var original = ManagedAddress.fromByteArray(bytes); var alias = offset.apply(original, 4L);
            assertNull(alias.nativeAllocation()); assertNotNull(alias.returnedAddress().getCarrier());
            alias.writeWord8(0, 'a'); alias.writeWord8(1, 'b'); assertEquals((byte) 'a', bytes[4]);
            assertEquals(2L, alias.cStringLength()); assertEquals("ab", alias.utf8()); assertTrue(alias.sameLocation(alias.plus(0)));
            assertEquals(3L, alias.plus(3).difference(alias)); alias.writeNativeScalar(1, 8, 0xfedcba9876543210L, true);
            assertEquals(0xfedcba9876543210L, ManagedAddressRead.WORD64.read(alias, 1, true));
            var source = new byte[32]; for (int i = 0; i < source.length; i++) source[i] = (byte) i;
            alias.copyFromByteArray(source, 0, 32); var target = new byte[32]; alias.copyToByteArray(target, 0, 32); assertArrayEquals(source, target);
            var vector = alias.readVectorBytes(0, 1); alias.writeVectorBytes(16, 1, vector);
            assertArrayEquals(Arrays.copyOfRange(source, 0, 16), Arrays.copyOfRange(bytes, 20, 36));
            alias.moveTo(alias.plus(2), 16); assertArrayEquals(Arrays.copyOfRange(source, 0, 16), Arrays.copyOfRange(bytes, 6, 22));
            assertThrows(RuntimeFault.class, () -> alias.copyNonOverlappingTo(alias.plus(1), 8));
            alias.fill(4, 255); var filled = new ArrayList<Integer>(); for (int i = 4; i <= 7; i++) filled.add((int) bytes[i]);
            assertEquals(List.of(-1, -1, -1, -1), filled);
            assertThrows(RuntimeFault.class, () -> alias.requireRange(0, -1)); assertThrows(RuntimeFault.class, () -> alias.requireRange(Long.MAX_VALUE, 1));
        }); }
    }

    @Test public void returnedPointersKeepContextAndLibraryAccessGuards() throws Exception {
        try (var first = context()) { entered(first, owner -> {
            var pointer = offset(owner).apply(ManagedAddress.fromByteArray(new byte[] {1, 0}), 0L); assertEquals(1L, pointer.readWord8(0));
            try (var other = context()) { entered(other, ignored -> {
                assertThrows(RuntimeFault.class, () -> pointer.readWord8(0)); assertThrows(RuntimeFault.class, () -> pointer.sameLocation(pointer));
            }); }
            owner.getPackageCbits().close();
            assertThrows(RuntimeFault.class, () -> pointer.readWord8(0)); assertThrows(RuntimeFault.class, () -> pointer.sameLocation(pointer));
        }); }
        try (var denied = context(false)) { entered(denied, owner -> assertThrows(RuntimeFault.class, () -> offset(owner))); }
    }

    @Test public void managedAliasIdentityMatchesOriginalAndRepeatedReturns() throws Exception {
        try (var context = context()) { entered(context, owner -> {
            var offset = offset(owner); var bytes = new byte[40]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
            var original = ManagedAddress.fromByteArray(bytes); var alias = offset.apply(original, 4L); var repeated = offset.apply(original, 4L);
            // Actual C comparisons and checked backing both see distinct JVM transport wrappers.
            var carrier = alias.returnedAddress().transport(); var originalCarrier = owner.getPackageCbits().transport(original.plus(4));
            assertEquals(1, owner.getPackageCbits().memory("equal", carrier, originalCarrier));
            assertEquals(0L, owner.getPackageCbits().memory("difference", carrier, originalCarrier));
            assertEquals(1, owner.getPackageCbits().memory("equal", carrier, repeated.returnedAddress().transport()));
            var unrelated = ManagedAddress.fromByteArray(new byte[40]);
            assertNull(owner.getPackageCbits().managedAliasOffset(carrier, owner.getPackageCbits().transport(unrelated), 0, 40));
            assertEquals(4L, owner.getPackageCbits().managedAliasOffset(carrier, owner.getPackageCbits().transport(original), 0, 40));
            assertAll(
                () -> assertTrue(alias.sameLocation(original.plus(4)), "returned alias equals original view"),
                () -> assertTrue(alias.sameLocation(repeated), "separate calls retain allocation identity"),
                () -> assertEquals(4L, alias.difference(original), "same allocation displacement"),
                () -> assertEquals(0, alias.compareWithinAllocation(original.plus(4))),
                () -> assertEquals(-1, alias.compareWithinAllocation(original.plus(5))),
                () -> assertTrue(alias.overlaps(0, 12, original, 6, 12), "overlap with original view"),
                () -> assertThrows(RuntimeFault.class, () -> alias.copyNonOverlappingTo(original.plus(6), 12)),
                () -> assertEquals(36L, alias.availableBytes(), "actual known remaining extent"),
                () -> assertThrows(RuntimeFault.class, () -> alias.readWord8(36)));
            var expected = bytes.clone(); System.arraycopy(expected, 4, expected, 6, 12);
            alias.moveTo(original.plus(6), 12); assertArrayEquals(expected, bytes);
            alias.copyNonOverlappingTo(original.plus(24), 8); assertArrayEquals(Arrays.copyOfRange(bytes, 4, 12), Arrays.copyOfRange(bytes, 24, 32));
            assertThrows(RuntimeFault.class, alias::toNativeBits);
            var allocation = ManagedAllocation.mutable(40, 8); var owned = ManagedAddress.fromAllocation(allocation); var ownedAlias = offset.apply(owned, 4L);
            assertSame(allocation, ownedAlias.cbitsOwner()); allocation.shrink(12); assertEquals(8L, ownedAlias.availableBytes());
            assertThrows(RuntimeFault.class, () -> ownedAlias.readWord8(8)); assertTrue(ownedAlias.sameLocation(owned.plus(4)));
            var immutable = ManagedAddress.fromStaticBytes(new byte[] {1, 2, 3, 4, 0}); var immutableAlias = offset.apply(immutable, 1L);
            assertEquals(2L, immutableAlias.readWord8(0)); assertThrows(RuntimeFault.class, () -> immutableAlias.writeWord8(0, 9));
        }); }
    }

    @Test public void returnedNativeComparisonDoesNotGrantNumericMemoryAuthority() throws Exception {
        try (var context = context()) { entered(context, owner -> {
            var original = owner.getNativeAllocations().malloc(24);
            try {
                var alias = offset(owner).apply(original, 4L); var numeric = ManagedAddress.unownedNumeric(alias.toNativeBits());
                assertAll(() -> assertTrue(alias.sameLocation(numeric)), () -> assertTrue(numeric.sameLocation(alias)),
                    () -> assertEquals(0L, alias.difference(numeric)), () -> assertEquals(0L, numeric.difference(alias)),
                    () -> assertEquals(-1, alias.compareWithinAllocation(numeric.plus(1))), () -> assertEquals(1, numeric.plus(1).compareWithinAllocation(alias)),
                    () -> assertThrows(RuntimeFault.class, () -> numeric.readWord8(0)), () -> assertThrows(RuntimeFault.class, () -> numeric.writeWord8(0, 1)));
            } finally { owner.getNativeAllocations().free(original); }
        }); }
    }

    @Test public void returnedBuffersUseDescriptorTransfersWithoutArrayExposure() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var context = withContextProfile(Context.newBuilder("thc").allowNativeAccess(true).in(new ByteArrayInputStream(new byte[] {4, 5})).out(output), ContextProfile.SYNCHRONOUS_TEST).build()) {
            entered(context, owner -> {
                var bytes = new byte[8]; Arrays.fill(bytes, (byte) 90); var pointer = offset(owner).apply(ManagedAddress.fromByteArray(bytes), 2L);
                assertEquals(-1L, owner.getStdio().close(-1)); var errno = owner.getStdio().errno();
                assertEquals(2L, owner.getStdio().read(0, pointer, 4)); assertArrayEquals(new byte[] {90, 90, 4, 5, 90, 90, 90, 90}, bytes);
                assertEquals(0L, owner.getStdio().read(0, pointer, 4)); assertEquals(4L, owner.getStdio().write(1, pointer, 4));
                assertArrayEquals(new byte[] {4, 5, 90, 90}, output.toByteArray()); assertEquals(errno, owner.getStdio().errno());
            });
        }
        var failure = new InputStream() {
            @Override public int read() { throw new IllegalStateException("bulk operation required"); }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException { bytes[offset] = 17; throw new IOException("partial read"); }
        };
        try (var context = withContextProfile(Context.newBuilder("thc").allowNativeAccess(true).in(failure), ContextProfile.SYNCHRONOUS_TEST).build()) {
            entered(context, owner -> {
                var bytes = new byte[8]; Arrays.fill(bytes, (byte) 90); var pointer = offset(owner).apply(ManagedAddress.fromByteArray(bytes), 2L);
                assertEquals(-1L, owner.getStdio().read(0, pointer, 4)); assertArrayEquals(new byte[] {90, 90, 17, 90, 90, 90, 90, 90}, bytes);
            });
        }
    }
}
