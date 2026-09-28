// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import thc.Language;
import thc.NativeIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class NativeDirectoryStreamsTest {
    @TempDir Path root;
    private ManagedAddress address(byte[] bytes) { return ManagedAddress.fromByteArray(Arrays.copyOf(bytes, bytes.length + 1)); }
    private ManagedAddress path(Path path) { return ManagedAddress.fromByteArray(NativeDirectoryOwner.pathBytes(path)); }
    private ManagedAddress cell() { return ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8)); }
    private <T> T entered(Context context, Callable<T> action) throws Exception {
        context.enter(); try { return action.call(); } finally { context.leave(); }
    }
    private List<Integer> bytes(ManagedAddress address) {
        var result = new ArrayList<Integer>(); long length = address.cStringLength();
        for (long i = 0; i < length; i++) result.add((int) address.readWord8(i)); return result;
    }
    private Set<List<Integer>> contents(ManagedAddress stream) {
        var service = NativeFileProvider.current().getDirectoryStreams();
        var output = cell(); var names = new LinkedHashSet<List<Integer>>();
        while (true) {
            Language.currentState(null).getStdio().setErrno(0); long status = service.read(stream, output);
            if (status == -1) {
                assertSame(ManagedAddress.nullAddress(), output.readAddressElementIndex(0));
                assertEquals(0L, Language.currentState(null).getStdio().errno()); return names;
            }
            assertEquals(0L, status); var entry = output.readAddressElementIndex(0);
            names.add(bytes(service.name(entry))); service.freeEntry(entry);
        }
    }
    @Test void rawNamesEofAndEntryLifetimesFollowTheOriginalGlibcProtocol() throws Exception {
        Files.writeString(root.resolve("file"), "unchanged");
        var prefix = (root + "/").getBytes(StandardCharsets.UTF_8); var rawPath = Arrays.copyOf(prefix, prefix.length + 2);
        rawPath[prefix.length] = -1; rawPath[prefix.length + 1] = 110;
        Files.write(NativeDirectoryOwner.bytesPath(rawPath), new byte[] {7});
        var renamed = root.resolveSibling(root.getFileName() + "-renamed");
        try (var context = NativeIO.createContext(Set.of())) { entered(context, () -> {
            var stdio = Language.currentState(null).getStdio();
            var service = NativeFileProvider.current().getDirectoryStreams();
            var stream = stdio.openDirectory(path(root)); assertNotSame(ManagedAddress.nullAddress(), stream);
            var output = cell(); stdio.setErrno(9); assertEquals(0L, service.read(stream, output));
            var entry = output.readAddressElementIndex(0); var name = service.name(entry); var first = bytes(name);
            service.freeEntry(entry); assertEquals(first, bytes(name), "free_dirent is a native no-op on this glibc contract");
            assertEquals(9L, stdio.errno()); Files.move(root, renamed);
            try {
                var names = contents(stream); names.add(first);
                assertEquals(Set.of(List.of(46), List.of(46, 46), List.of(102, 105, 108, 101), List.of(255, 110)), names);
                assertThrows(RuntimeFault.class, () -> bytes(name)); assertThrows(RuntimeFault.class, () -> service.name(entry));
                stdio.setErrno(9); assertEquals(-1L, service.read(stream, output)); assertEquals(9L, stdio.errno(), "EOF preserves the guest errno seed");
                assertEquals(0L, service.closeStream(stream)); assertThrows(RuntimeFault.class, () -> service.closeStream(stream));
            } finally { Files.move(renamed, root); }
            return null;
        }); }
    }
    @Test void fdopendirConsumesOnlyTheSuccessfulGuestFdAndPreservesAliases() throws Exception {
        Files.writeString(root.resolve("file"), "unchanged");
        try (var context = NativeIO.createContext(Set.of())) { entered(context, () -> {
            var stdio = Language.currentState(null).getStdio();
            var service = NativeFileProvider.current().getDirectoryStreams();
            long flags = stdio.flagConstant(OriginalStdioOp.O_RDONLY), fd = stdio.open(path(root), flags, 0);
            assertTrue(fd >= 0); long alias = stdio.duplicate(fd); var stream = stdio.openDirectoryFd(fd);
            assertNotSame(ManagedAddress.nullAddress(), stream); assertEquals(-1L, stdio.close(fd));
            assertEquals(0L, stdio.close(alias), "The surviving guest alias remains independently closeable");
            assertEquals(Set.of(List.of(46), List.of(46, 46), List.of(102, 105, 108, 101)), contents(stream));
            assertEquals(0L, service.closeStream(stream)); long regular = stdio.open(path(root.resolve("file")), flags, 0);
            assertSame(ManagedAddress.nullAddress(), stdio.openDirectoryFd(regular)); assertEquals(20L, stdio.errno());
            assertEquals(0L, stdio.close(regular), "Failed fdopendir leaves its input fd owned by the caller");
            assertSame(ManagedAddress.nullAddress(), stdio.openDirectoryFd(regular)); assertEquals(9L, stdio.errno());
            assertEquals(0, service.liveCount()); return null;
        }); }
    }
    @Test void outputValidationPrecedesReadAndDisposalRetiresAllViews() throws Exception {
        var first = NativeIO.createContext(Set.of()); var second = NativeIO.createContext(Set.of());
        var staleName = new ManagedAddress[1]; var service = new NativeDirectoryStreams[1];
        try {
            var stream = entered(first, () -> {
                service[0] = NativeFileProvider.current().getDirectoryStreams();
                return Language.currentState(null).getStdio().openDirectory(path(root));
            });
            entered(second, () -> {
                assertThrows(RuntimeFault.class, () -> NativeFileProvider.current().getDirectoryStreams().read(stream, cell())); return null;
            });
            entered(first, () -> {
                var reference = Language.currentState(null).getStdio().openDirectory(path(root)); var expected = cell();
                assertEquals(0L, service[0].read(reference, expected)); var expectedName = bytes(service[0].name(expected.readAddressElementIndex(0)));
                service[0].closeStream(reference); var raw = ManagedAllocation.mutable(8, 8); raw.rawBytesIfPointerFree();
                for (var invalid : List.of(ManagedAddress.nullAddress(), ManagedAddress.fromAllocation(raw),
                    ManagedAddress.fromAllocation(ManagedAllocation.mutable(16, 8)).plus(1),
                    ManagedAddress.fromAllocation(ManagedAllocation.immutable(new byte[8], 8))))
                    assertThrows(RuntimeFault.class, () -> service[0].read(stream, invalid));
                var output = cell(); assertEquals(0L, service[0].read(stream, output));
                staleName[0] = service[0].name(output.readAddressElementIndex(0));
                assertEquals(expectedName, bytes(Objects.requireNonNull(staleName[0])), "Rejected storage must not consume the first entry");
                assertThrows(RuntimeFault.class, () -> service[0].read(stream.plus(1), output));
                assertThrows(RuntimeFault.class, () -> service[0].read(address(new byte[0]), output)); return null;
            });
        } finally { first.close(); second.close(); }
        assertEquals(0, service[0].liveCount());
        assertThrows(RuntimeFault.class, () -> bytes(Objects.requireNonNull(staleName[0])));
    }
    @Test void activeNameCannotBeUsedAsTheNextOutputCell() throws Exception {
        Files.writeString(root.resolve("long-directory-entry"), "unchanged");
        try (var context = NativeIO.createContext(Set.of())) { entered(context, () -> {
            var stdio = Language.currentState(null).getStdio();
            var service = NativeFileProvider.current().getDirectoryStreams();
            var stream = stdio.openDirectory(path(root)); var reference = stdio.openDirectory(path(root)); var output = cell(); var comparison = cell();
            for (int i = 0; i < 3; i++) {
                assertEquals(service.read(reference, comparison), service.read(stream, output)); var name = service.name(output.readAddressElementIndex(0));
                assertEquals(bytes(service.name(comparison.readAddressElementIndex(0))), bytes(name));
                if (name.cStringLength() >= 8) {
                    var original = bytes(name); assertThrows(RuntimeFault.class, () -> service.read(stream, name));
                    assertEquals(original, bytes(name), "Rejected alias cannot retire or shrink the current name");
                }
            }
            stdio.setErrno(0); assertEquals(-1L, service.read(stream, output)); assertEquals(0L, stdio.errno());
            assertEquals(0L, service.closeStream(stream)); assertEquals(0L, service.closeStream(reference)); return null;
        }); }
    }
    @Test void nativeOutputCellsRetainPointerIdentityAndRejectReleasedStorageBeforeRead() throws Exception {
        try (var context = NativeIO.createContext(Set.of())) { entered(context, () -> {
            var state = Language.currentState(null); var service = NativeFileProvider.current().getDirectoryStreams();
            var stream = state.getStdio().openDirectory(path(root)); var reference = state.getStdio().openDirectory(path(root));
            var nativeStorage = state.getNativeAllocations().malloc(24); var output = nativeStorage.plus(8); var comparison = cell();
            try {
                nativeStorage.fill(24, 165); assertEquals(0L, service.read(stream, output)); assertEquals(0L, service.read(reference, comparison));
                var entry = output.readAddressElementIndex(0);
                assertEquals(bytes(service.name(comparison.readAddressElementIndex(0))), bytes(service.name(entry))); service.freeEntry(entry);
                for (long offset = 0; offset <= 7; offset++) {
                    assertEquals(165L, nativeStorage.readWord8(offset)); assertEquals(165L, nativeStorage.readWord8(offset + 16));
                }
            } finally { state.getNativeAllocations().free(nativeStorage); }
            assertThrows(RuntimeFault.class, () -> service.read(stream, output)); var next = cell();
            assertEquals(service.read(reference, comparison), service.read(stream, next));
            assertEquals(bytes(service.name(comparison.readAddressElementIndex(0))), bytes(service.name(next.readAddressElementIndex(0))));
            assertEquals(0L, service.closeStream(stream)); assertEquals(0L, service.closeStream(reference)); return null;
        }); }
    }
}
