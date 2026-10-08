// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.io.*;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import thc.Language;
import thc.NativeIO;
import thc.PackageScalarLink;
import thc.PackageScalarSignature;
import static org.junit.jupiter.api.Assertions.*;

/** Storage/lifetime extension only: no new foreign-call or descriptor admission. */
@EnabledIf("supportedFileTarget")
class NativeFileBuffersTest {
    private static boolean supportedFileTarget() {
        return NativeIO.supportedPosixHost() || "Mac OS X".equals(System.getProperty("os.name")) &&
            Set.of("amd64", "x86_64", "aarch64", "arm64").contains(System.getProperty("os.arch"));
    }
    @TempDir Path directory;
    private <T> T entered(Context context, Callable<T> body) throws Exception {
        context.initialize("thc"); context.enter(); try { return body.call(); } finally { context.leave(); }
    }
    private ManagedAddress buffer(boolean nativeStorage, int size) {
        if (nativeStorage) {
            var address = Language.currentState(null).getNativeAllocations().malloc(size);
            assertNotSame(ManagedAddress.nullAddress(), address);
            address.withNativeSegmentInt(segment -> { segment.fill((byte) 90); return 0; });
            return address;
        }
        var bytes = new byte[size]; Arrays.fill(bytes, (byte) 90); return ManagedAddress.fromByteArray(bytes);
    }
    private void release(ManagedAddress address) {
        if (address.nativeAllocation() != null) Language.currentState(null).getNativeAllocations().free(address);
    }
    private List<Integer> bytes(ManagedAddress address, int size) {
        var bytes = new ArrayList<Integer>(); for (int i = 0; i < size; i++) bytes.add((int) address.readWord8(i)); return bytes;
    }
    @Test void nativeAndManagedFileBuffersPreserveOffsetsShortReadsEofAndStickyErrno() throws Exception {
        try (var context = NativeIO.createContext(Set.of())) { entered(context, () -> {
            var state = Language.currentState(null); var files = state.getFiles(); var stdio = state.getStdio();
            var path = directory.resolve("bytes"); var name = ManagedAddress.fromByteArray((path + "\0").getBytes(StandardCharsets.UTF_8));
            for (boolean nativeStorage : new boolean[] {false, true}) {
                Files.write(path, new byte[] {1, 2, 3, 4, 5, 6}); long fd = files.open(name, 3, ForeignSafety.UNSAFE); assertTrue(fd >= 3);
                var base = buffer(nativeStorage, 12);
                try {
                    assertEquals(-1L, stdio.close(-1)); long errno = stdio.errno();
                    assertEquals(6L, stdio.read(fd, base.plus(3), 8));
                    assertEquals(List.of(90, 90, 90, 1, 2, 3, 4, 5, 6, 90, 90, 90), bytes(base, 12));
                    assertEquals(errno, stdio.errno()); assertEquals(0L, stdio.read(fd, base.plus(3), 2));
                    assertEquals(0L, stdio.read(fd, base.plus(12), 0)); assertEquals(0L, files.seek(fd, 0, 0, ForeignSafety.UNSAFE));
                    assertEquals(3L, stdio.write(fd, base.plus(4), 3)); assertEquals(errno, stdio.errno());
                    assertArrayEquals(new byte[] {2, 3, 4, 4, 5, 6}, Files.readAllBytes(path));
                    assertEquals(List.of(90, 90, 90, 1, 2, 3, 4, 5, 6, 90, 90, 90), bytes(base, 12));
                } finally { release(base); assertEquals(0L, files.close(fd, ForeignSafety.UNSAFE)); }
            }
            return null;
        }); }
    }
    // External C pointers have no allocation bound: file IO borrows only the
    // requested window while the caller retains the native storage's lifetime.
    @Test void externalReturnedBufferUsesRequestSizedNativeFileWindow() throws Exception {
        try (var context = NativeIO.createContext(Set.of())) { entered(context, () -> {
            var owner = Language.currentState(null); var files = owner.getFiles();
            final byte[] helper;
            try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/thc/cbits/package-pointer.bc"))) { helper = input.readAllBytes(); }
            var symbol = "thc_package_pointer_read_address";
            var signature = new PackageScalarSignature(symbol, symbol, List.of("AddrRep", "Int64Rep"), "AddrRep");
            var link = new PackageScalarLink("external-file-buffer-control", "unused", "external-file-buffer-control", "", helper, List.of(signature));
            owner.getPackageCbits().link(link);
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
            var target = new RootNode(language) {
                @Child private PackageScalarAccess access = new PackageScalarAccess(new PackageScalarCall(link, signature));
                @Override public Object execute(VirtualFrame frame) { return access.executeAddress(frame.getArguments(), Unit.INSTANCE); }
            }.getCallTarget();
            try (var arena = Arena.ofConfined()) {
                var nativeMemory = arena.allocate(8, 8); var initial = new byte[] {90, 90, 1, 2, 3, 4, 90, 90};
                for (int i = 0; i < initial.length; i++) nativeMemory.set(ValueLayout.JAVA_BYTE, i, initial[i]);
                var slot = owner.getNativeAllocations().malloc(8); final ManagedAddress pointer;
                try { slot.writeNativeScalar(0, 8, nativeMemory.address()); pointer = (ManagedAddress) target.call(slot, 0L); }
                finally { owner.getNativeAllocations().free(slot); }
                assertNotNull(pointer.returnedAddress()); assertNull(pointer.returnedAddress().getBacking()); assertNull(pointer.nativeAllocation());
                assertThrows(RuntimeFault.class, pointer::availableBytes); assertTrue(pointer.hasNativeIOStorage());
                var interior = pointer.plus(2);
                interior.withNativeIOWindow(4, false, window -> {
                    assertEquals(nativeMemory.address() + 2, window.address()); assertEquals(4L, window.byteSize());
                    assertArrayEquals(new byte[] {1, 2, 3, 4}, window.toArray(ValueLayout.JAVA_BYTE)); return Unit.INSTANCE;
                });
                var path = directory.resolve("external-buffer"); Files.write(path, new byte[] {7, 8, 9, 10, 11, 12});
                var name = ManagedAddress.fromByteArray((path + "\0").getBytes(StandardCharsets.UTF_8));
                long fd = files.open(name, 3, ForeignSafety.UNSAFE); assertTrue(fd >= 3);
                try {
                    assertEquals(1L, files.seek(fd, 1, 0, ForeignSafety.UNSAFE));
                    assertEquals(4L, files.write(fd, interior, 4, ForeignSafety.UNSAFE));
                    assertArrayEquals(new byte[] {7, 1, 2, 3, 4, 12}, Files.readAllBytes(path));
                    nativeMemory.asSlice(3, 3).fill((byte) 0);
                    assertEquals(1L, files.seek(fd, 1, 0, ForeignSafety.UNSAFE));
                    assertEquals(3L, files.read(fd, pointer.plus(3), 3, ForeignSafety.UNSAFE));
                    assertArrayEquals(new byte[] {90, 90, 1, 1, 2, 3, 90, 90}, nativeMemory.toArray(ValueLayout.JAVA_BYTE));
                    assertNull(pointer.nativeAllocation()); assertThrows(RuntimeFault.class, pointer::availableBytes);
                } finally { assertEquals(0L, files.close(fd, ForeignSafety.UNSAFE)); }
            }
            return null;
        }); }
    }
    @Test void streamWindowsReportOnlyTransferredBytesAndInvalidMemoryPrecedesEffects() throws Exception {
        var reads = new AtomicInteger(); var inputBytes = new byte[2 * 1024 * 1024]; Arrays.fill(inputBytes, (byte) 17);
        var input = new ByteArrayInputStream(inputBytes) {
            @Override public int read(byte[] target, int offset, int length) { reads.incrementAndGet(); return super.read(target, offset, length); }
        };
        var output = new ByteArrayOutputStream();
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).in(input).out(output).build()) { entered(context, () -> {
            var state = Language.currentState(null); var stdio = state.getStdio(); int size = 2 * 1024 * 1024 + 8;
            var base = buffer(true, size); var alias = base.plus(3);
            try {
                for (var address : List.of(alias.plus(size - 3L), ManagedAddress.unownedNumeric(base.toNativeBits()), ManagedAddress.nullAddress())) {
                    assertThrows(RuntimeFault.class, () -> stdio.read(0, address, 1)); assertThrows(RuntimeFault.class, () -> stdio.write(1, address, 1));
                }
                for (long count : new long[] {-1, Long.MAX_VALUE, size}) {
                    assertThrows(RuntimeFault.class, () -> stdio.read(0, alias, count)); assertThrows(RuntimeFault.class, () -> stdio.write(1, alias, count));
                }
                assertEquals(0, reads.get()); assertEquals(0, output.size());
                assertEquals(1024 * 1024L, stdio.read(0, alias, 2 * 1024 * 1024L));
                assertEquals(90L, base.readWord8(2)); assertEquals(17L, alias.readWord8(0));
                assertEquals(17L, alias.readWord8(1024 * 1024L - 1)); assertEquals(90L, alias.readWord8(1024 * 1024L));
                assertEquals(1024 * 1024L, stdio.write(1, alias, 2 * 1024 * 1024L)); assertEquals(1024 * 1024, output.size());
                boolean all17 = true; for (byte value : output.toByteArray()) all17 &= value == (byte) 17; assertTrue(all17);
                try (var other = Context.newBuilder("thc").allowNativeAccess(true).build()) { entered(other, () -> {
                    assertThrows(RuntimeFault.class, () -> Language.currentState(null).getStdio().read(0, alias, 1));
                    assertThrows(RuntimeFault.class, () -> Language.currentState(null).getStdio().write(1, alias, 1)); return null;
                }); }
            } finally { release(base); }
            int before = reads.get(), written = output.size();
            assertThrows(RuntimeFault.class, () -> stdio.read(0, alias, 1)); assertThrows(RuntimeFault.class, () -> stdio.write(1, alias, 1));
            assertEquals(before, reads.get()); assertEquals(written, output.size()); return null;
        }); }
    }
    @Test void borrowedAliasKeepsAllocationAliveUntilBlockedReadAndCopybackComplete() throws Exception {
        var reading = new CountDownLatch(1); var releaseRead = new CountDownLatch(1); var base = new AtomicReference<ManagedAddress>();
        var input = new InputStream() {
            @Override public int read() { throw new IllegalStateException("Unexpected single-byte read"); }
            @Override public int read(byte[] target, int offset, int length) throws IOException {
                reading.countDown();
                try { if (!releaseRead.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Check failed."); }
                catch (InterruptedException failure) { throw propagate(failure); }
                base.get().writeWord8(0, 73); // Same owner, reentrant borrow while free is queued.
                target[offset] = 41; target[offset + 1] = 42; return 2;
            }
        };
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).in(input).build()) {
            var state = entered(context, () -> { var result = Language.currentState(null); base.set(buffer(true, 8)); return result; });
            var alias = entered(context, () -> base.get().plus(3)); var workers = Executors.newFixedThreadPool(2);
            try {
                var read = workers.submit(() -> entered(context, () -> state.getStdio().read(0, alias, 4)));
                assertTrue(reading.await(10, TimeUnit.SECONDS)); var freeing = new CountDownLatch(1);
                var freed = workers.submit(() -> entered(context, () -> { freeing.countDown(); state.getNativeAllocations().free(base.get()); return null; }));
                assertTrue(freeing.await(10, TimeUnit.SECONDS)); assertThrows(TimeoutException.class, () -> freed.get(100, TimeUnit.MILLISECONDS));
                releaseRead.countDown(); assertEquals(2L, read.get(10, TimeUnit.SECONDS)); freed.get(10, TimeUnit.SECONDS);
                entered(context, () -> { assertThrows(RuntimeFault.class, () -> alias.readWord8(0)); return null; });
            } finally { releaseRead.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
    @Test void partialStreamFailuresPreserveBytesAndReleaseNativeBorrows() throws Exception {
        for (boolean nativeStorage : new boolean[] {false, true}) for (boolean read : new boolean[] {false, true}) for (boolean hard : new boolean[] {false, true}) {
            var death = new ThreadDeath();
            var input = new InputStream() {
                private void failure() throws IOException { if (hard) throw death; throw new IOException("expected transfer failure"); }
                @Override public int read() throws IOException { failure(); throw new AssertionError(); }
                @Override public int read(byte[] target, int offset, int length) throws IOException { target[offset] = 41; target[offset + 1] = 42; failure(); throw new AssertionError(); }
            };
            var output = new OutputStream() {
                @Override public void write(int value) throws IOException { if (hard) throw death; throw new IOException("expected transfer failure"); }
                @Override public void write(byte[] source, int offset, int length) throws IOException { if (hard) throw death; throw new IOException("expected transfer failure"); }
            };
            try (var context = Context.newBuilder("thc").allowNativeAccess(true).in(input).out(output).build()) { entered(context, () -> {
                var state = Language.currentState(null); var base = buffer(nativeStorage, 8);
                java.util.function.LongSupplier transfer = () -> read ? state.getStdio().read(0, base.plus(2), 3) : state.getStdio().write(1, base.plus(2), 3);
                if (hard) assertSame(death, assertThrows(ThreadDeath.class, transfer::getAsLong));
                else { assertEquals(-1L, transfer.getAsLong()); assertEquals(StdioHostAbi.load().error(6), state.getStdio().errno()); }
                assertEquals(read ? List.of(90, 90, 41, 42, 90, 90, 90, 90) : Collections.nCopies(8, 90), bytes(base, 8));
                // Free rejects a borrow held by this thread instead of hanging,
                // so this also proves every error path released its borrow.
                release(base); assertEquals(0, state.getNativeAllocations().liveCount()); return null;
            }); }
        }
    }
}
