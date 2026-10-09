// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import thc.NativeIO;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import thc.ContextProfile;
import thc.Language;
import thc.Main;
import thc.PackageScalarLink;
import thc.PackageScalarSignature;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static java.lang.foreign.ValueLayout.*;

/** Native DLL lookup must not depend on guest cwd/file authority. The buffer
 * remains real context-owned Sulong C storage, not a managed replacement. */
@EnabledOnOs(OS.WINDOWS)
class WindowsSulongLibraryLookupTest {
    @TempDir Path scratch;

    private Context context(boolean nativeAccess) {
        return Main.withContextProfile(Context.newBuilder("thc").allowIO(IOAccess.NONE)
            .allowNativeAccess(nativeAccess), ContextProfile.SYNCHRONOUS_TEST).build();
    }
    private Language.State enter(Context context) {
        context.initialize("thc");
        context.enter();
        try {
            var owner = Language.currentState(null);
            owner.getThreads().enterCurrent(null, false, true, null);
            return owner;
        } catch (RuntimeException | Error failure) { context.leave(); throw failure; }
    }
    private void leave(Context context) {
        try { Language.currentState(null).getThreads().leaveCurrent(GuestThreadStatus.FINISHED); }
        finally { context.leave(); }
    }

    private static final class Buffer extends RootNode {
        @Child private PackageScalarAccess access;
        Buffer(Language language, PackageScalarCall call) { super(language); access = new PackageScalarAccess(call); }
        @Override public Object execute(VirtualFrame frame) {
            return access.executeAddress(new Object[0], thc.runtime.Unit.INSTANCE);
        }
    }
    private static final class Open extends RootNode {
        @Child private PackageScalarAccess access;
        Open(Language language, PackageScalarCall call) { super(language); access = new PackageScalarAccess(call); }
        @Override public Object execute(VirtualFrame frame) {
            return access.executeInt(new Object[]{frame.getArguments()[0], frame.getArguments()[1], (short) 0x180}, thc.runtime.Unit.INSTANCE);
        }
    }
    private static ManagedAddress nativeWide(String value) {
        var address = ManagedAddress.fromAllocation(ManagedAllocation.nativeMutable((value.length() + 1L) * 2, 8));
        for (int i = 0; i < value.length(); i++) {
            address.writeWord8(i * 2L, value.charAt(i) & 255);
            address.writeWord8(i * 2L + 1, value.charAt(i) >>> 8);
        }
        return address;
    }
    private PackageScalarCall originalOpen(Language.State owner) throws Exception {
        var directory = Path.of(System.getProperty("thc.projectRoot"), "build/generated/test-cbits");
        var bitcode = Files.readAllBytes(directory.resolve("windows-open.bc"));
        var dll = Files.readAllBytes(directory.resolve("windows-opening-fixture.dll"));
        var signature = new PackageScalarSignature("__hscore_open", "thc_windows_fixture_open",
            List.of("AddrRep", "Int32Rep", "Word16Rep"), "Int32Rep", "ccall", "safe");
        // JVM linkage model, not fabricated executable Core. The named producer
        // checks the actual package header/archive and links its original body.
        var link = new PackageScalarLink("ghc-internal", "x86_64-pc-windows-msvc19.33.0",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dll)),
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bitcode)),
            bitcode, List.of(signature), "llvm-bitcode", Set.of(), dll);
        owner.getPackageCbits().link(link);
        return new PackageScalarCall(link, signature);
    }
    /** A real pinned HsBase open and selected CRT transfer under the fixed
     * factory. This is positive Java SAFE/ABI evidence, not a compiled Core call. */
    @Test void originalWindowsOpenUsesContextCwdAndRealNativeDescriptors() throws Throwable {
        var processCwd = Path.of("").toAbsolutePath();
        String filename = "å-文件-𐐷.bin";
        try (var context = WindowsDirectoryStreams.createContext(ContextProfile.SYNCHRONOUS_TEST); var arena = Arena.ofConfined()) {
            var owner = enter(context);
            try {
                owner.getEnv().setCurrentWorkingDirectory(owner.getEnv().getPublicTruffleFile(scratch.toString()));
                var call = originalOpen(owner);
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var open = new Open(language, call).getCallTarget();
                // Seek/close controls come from the companion SDK fixture,
                // never from extra producer roots in the opening component.
                var selected = SymbolLookup.libraryLookup(Path.of(System.getProperty("thc.projectRoot"),
                    "build/generated/test-cbits/windows-io-fixture.dll"), arena);
                int fd = (int) open.call(nativeWide(filename), 0x8302); // SDK O_RDWR|O_CREAT|O_TRUNC|O_BINARY
                assertTrue(fd >= 0);
                try {
                    assertTrue(Files.exists(scratch.resolve(filename)));
                    assertEquals(processCwd, Path.of("").toAbsolutePath());
                    var bytes = ManagedAddress.fromAllocation(ManagedAllocation.nativeMutable(3, 8));
                    bytes.copyFromByteArray(new byte[]{37, 91, 122}, 0, 3);
                    var io = owner.getWindowsNativeIo();
                    var write = io.transfer(fd, false, 3, bytes, true);
                    assertEquals(3, write.length()); assertEquals(0, write.error()); assertFalse(write.consoleAbort());
                    assertEquals(0, sdk(selected, "seek", new MemoryLayout[]{JAVA_INT}, fd));
                    bytes.fill(3, 0);
                    var read = io.transfer(fd, false, 2, bytes, false);
                    assertEquals(2, read.length()); assertEquals(0, read.error()); assertFalse(read.consoleAbort());
                    var actual = new byte[3]; bytes.copyToByteArray(actual, 0, 3);
                    assertArrayEquals(new byte[]{37, 91, 0}, actual);
                    assertEquals(1, io.transfer(fd, false, 3, bytes, false).length());
                    assertEquals(122, bytes.readWord8(0));
                    assertEquals(0, io.transfer(fd, false, 3, bytes, false).length());
                    assertEquals(0, io.transfer(fd, false, 0, ManagedAddress.nullAddress(), false).length());
                } finally { assertEquals(0, sdk(selected, "close", new MemoryLayout[]{JAVA_INT}, fd)); }
                assertEquals(9, owner.getWindowsNativeIo().transfer(fd, false, 0, ManagedAddress.nullAddress(), false).error());
                assertEquals(10045, owner.getWindowsNativeIo().transfer(-1, true, 0, ManagedAddress.nullAddress(), false).error(),
                    "an opening-only component must not borrow unrelated ambient WinSock imports");
                owner.getStdio().setErrno(71);
                assertEquals(-1, (int) open.call(nativeWide("absent/" + filename), 0x8000));
                assertEquals(2, owner.getStdio().errno(), "actual selected maperrno must replace the guest seed");
                assertNotEquals(0, owner.getWindowsCodePages().error());
                var heap = ManagedAddress.fromByteArray(new byte[4]);
                var failure = assertThrows(RuntimeFault.class, () -> open.call(heap, 0x8302));
                assertTrue(failure.getMessage().contains("addressable caller-owned pathname"));
            } finally { leave(context); }
        }
        try (var context = context(true)) {
            var owner = enter(context);
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var open = new Open(language, originalOpen(owner)).getCallTarget();
                assertThrows(SecurityException.class, () -> open.call(nativeWide(scratch.resolve(filename).toString()), 0x8000));
            } finally { leave(context); }
        }
    }
    private ManagedAddress buffer(Language.State owner) throws Exception {
        return buffer(owner, new byte[0]);
    }
    private ManagedAddress buffer(Language.State owner, byte[] nativeLibrary) throws Exception {
        var symbol = "thc_package_pointer_test_buffer";
        var signature = new PackageScalarSignature(symbol, symbol, List.of(), "AddrRep", "ccall", "unsafe");
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/thc/cbits/package-pointer.bc")) {
            assertNotNull(input);
            bytes = input.readAllBytes();
        }
        var link = new PackageScalarLink("windows-loader-control", "unused", "windows-loader-control", "", bytes,
            List.of(signature), "llvm-bitcode", java.util.Set.of(), nativeLibrary);
        owner.getPackageCbits().link(link);
        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
        return (ManagedAddress) new Buffer(language, new PackageScalarCall(link, signature)).getCallTarget().call();
    }
    private void denied(Language.State owner, Path marker) {
        assertThrows(SecurityException.class, () -> owner.getEnv().getCurrentWorkingDirectory());
        assertThrows(SecurityException.class, () -> owner.getEnv().getPublicTruffleFile(marker.toString()).readAllBytes());
    }

    @Test void nativeDependencyLoadingLeavesGuestCwdAndFileAccessDenied() throws Exception {
        var marker = Files.writeString(scratch.resolve("private.txt"), "host-only");
        try (var context = context(true)) {
            var owner = enter(context);
            try {
                denied(owner, marker);
                var pointer = buffer(owner);
                assertNotNull(pointer.returnedAddress());
                assertNull(pointer.returnedAddress().getBacking());
                assertNull(pointer.nativeAllocation());
                assertThrows(RuntimeFault.class, pointer::availableBytes);
                pointer.fill(32, 90);
                var expected = new byte[32];
                Arrays.fill(expected, (byte) 90);
                var actual = new byte[32];
                pointer.copyToByteArray(actual, 0, 32);
                assertArrayEquals(expected, actual);
                denied(owner, marker);
            } finally { leave(context); }
        }
    }

    @Test void packageDeclaredDllLoadingLeavesGuestFileAccessDenied() throws Exception {
        byte[] nativeLibrary;
        try (var input = getClass().getResourceAsStream("/thc/cbits/windows-malloc.dll")) {
            assertNotNull(input);
            nativeLibrary = input.readAllBytes();
        }
        var marker = Files.writeString(scratch.resolve("companion.txt"), "host-only");
        try (var context = context(true)) {
            var owner = enter(context);
            try {
                denied(owner, marker);
                var pointer = buffer(owner, nativeLibrary);
                pointer.fill(32, 73);
                var actual = new byte[32];
                pointer.copyToByteArray(actual, 0, 32);
                var expected = new byte[32];
                Arrays.fill(expected, (byte) 73);
                assertArrayEquals(expected, actual);
                denied(owner, marker);
            } finally { leave(context); }
        }
    }

    @Test void realStaticBuffersRemainIsolatedAndRejectForeignContextUse() throws Exception {
        try (var first = context(true); var second = context(true)) {
            ManagedAddress left;
            var owner = enter(first);
            try { left = buffer(owner); left.fill(32, 17); }
            finally { leave(first); }
            ManagedAddress right;
            owner = enter(second);
            try {
                right = buffer(owner);
                var bytes = new byte[32];
                right.copyToByteArray(bytes, 0, 32);
                assertArrayEquals(new byte[32], bytes);
                right.fill(32, 34);
                assertThrows(RuntimeFault.class, () -> left.readWord8(0));
            } finally { leave(second); }
            enter(first);
            try {
                assertEquals(17L, left.readWord8(0));
                assertThrows(RuntimeFault.class, () -> right.readWord8(0));
            } finally { leave(first); }
            enter(second);
            try { assertEquals(34L, right.readWord8(0)); }
            finally { leave(second); }
        }
    }

    @Test void nativeLibraryLoadingStillRequiresNativeAuthority() {
        try (var context = context(false)) {
            var owner = enter(context);
            try {
                var failure = assertThrows(RuntimeFault.class, () -> buffer(owner));
                assertTrue(failure.getMessage().contains("requires native access"));
            } finally { leave(context); }
        }
    }
    private static MemorySegment wide(Arena arena, String value) {
        var result = arena.allocate((value.length() + 1L) * 2, 2);
        for (int i = 0; i < value.length(); i++) result.set(JAVA_CHAR, i * 2L, value.charAt(i));
        result.set(JAVA_CHAR, value.length() * 2L, '\0');
        return result;
    }
    private static int sdk(SymbolLookup library, String name, MemoryLayout[] arguments, Object... values) throws Throwable {
        return (int) Linker.nativeLinker().downcallHandle(library.find("fixture_" + name).orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, arguments)).invokeWithArguments(values);
    }
    private static void transfer(MemorySegment owner, MemorySegment loan, boolean writing, int count,
                                 MemorySegment buffer, MemorySegment result, int length, int error) throws Throwable {
        WindowsNativeIo.Api.transfer.invokeExact(owner, loan, writing ? 1 : 0, count, buffer, result);
        assertEquals(length, result.get(JAVA_INT, 0));
        assertEquals(error, result.get(JAVA_INT, 4));
        assertEquals(0, result.get(JAVA_INT, 12));
    }
    /** Named native producer uses actual SDK files, pipes and loopback sockets.
     * This qualifies the boundary ABI/loans, not guest compiled execution. */
    @Test void selectedNativeNamespaceTransfersAndPinsRealDescriptors() throws Throwable {
        var image = Path.of(System.getProperty("thc.projectRoot"), "build/generated/test-cbits/windows-io-fixture.dll");
        assertTrue(Files.isRegularFile(image), "compileWindowsIoFixture must prepare its own DLL");
        try (var arena = Arena.ofConfined()) {
            var library = SymbolLookup.libraryLookup(image, arena);
            var error = arena.allocate(JAVA_INT);
            var wrong = (MemorySegment) WindowsNativeIo.Api.bind.invokeExact(wide(arena, "kernelbase.dll"), error);
            assertNotEquals(0, wrong.address(), "the OS module imports errno, but owns no descriptor operations");
            assertEquals(0, error.get(JAVA_INT, 0));
            try {
                var loan = arena.allocate(16, 8);
                assertEquals(40, (int) WindowsNativeIo.Api.acquire.invokeExact(wrong, 1, 0, loan),
                    "unrelated errno ownership must not borrow an ambient CRT descriptor table");
                assertEquals(10045, (int) WindowsNativeIo.Api.acquire.invokeExact(wrong, 1, 1, loan),
                    "unrelated errno ownership must not borrow ambient WinSock imports");
            } finally { WindowsNativeIo.Api.unbind.invokeExact(wrong); }
            var owner = (MemorySegment) WindowsNativeIo.Api.bind.invokeExact(wide(arena, image.toString()), error);
            assertNotEquals(0, owner.address(), "selected import binding failed with " + error.get(JAVA_INT, 0));
            try {
                var loan = arena.allocate(16, 8);
                var result = arena.allocate(16, 4);
                var bytes = arena.allocateFrom(JAVA_BYTE, new byte[]{37, 91, 122});
                int fd = sdk(library, "open", new MemoryLayout[]{ADDRESS}, wide(arena, scratch.resolve("transport.bin").toString()));
                assertTrue(fd >= 0);
                assertEquals(0, (int) WindowsNativeIo.Api.acquire.invokeExact(owner, fd, 0, loan));
                try { transfer(owner, loan, true, 3, bytes, result, 3, 0); }
                finally { WindowsNativeIo.Api.release.invokeExact(owner, loan); }
                assertEquals(0, sdk(library, "seek", new MemoryLayout[]{JAVA_INT}, fd));
                assertEquals(0, (int) WindowsNativeIo.Api.acquire.invokeExact(owner, fd, 0, loan));
                assertEquals(0, sdk(library, "close", new MemoryLayout[]{JAVA_INT}, fd));
                try {
                    bytes.fill((byte) 0);
                    transfer(owner, loan, false, 2, bytes, result, 2, 0);
                    assertArrayEquals(new byte[]{37, 91, 0}, bytes.toArray(JAVA_BYTE));
                    transfer(owner, loan, false, 1, bytes, result, 1, 0);
                    assertEquals(122, bytes.get(JAVA_BYTE, 0));
                    transfer(owner, loan, false, 1, bytes, result, 0, 0);
                    transfer(owner, loan, false, 0, MemorySegment.NULL, result, 0, 0);
                } finally { WindowsNativeIo.Api.release.invokeExact(owner, loan); }
                assertEquals(0, sdk(library, "validation_begin", new MemoryLayout[0]));
                try {
                    assertEquals(9, (int) WindowsNativeIo.Api.acquire.invokeExact(owner, fd, 0, loan));
                    assertEquals(9, (int) WindowsNativeIo.Api.acquire.invokeExact(owner, -1, 0, loan));
                } finally { assertEquals(1, sdk(library, "validation_end", new MemoryLayout[0])); }

                var pair = arena.allocate(8, 4);
                assertEquals(0, sdk(library, "pipe", new MemoryLayout[]{ADDRESS}, pair));
                int input = pair.get(JAVA_INT, 0), output = pair.get(JAVA_INT, 4);
                try {
                    bytes.copyFrom(MemorySegment.ofArray(new byte[]{37, 91, 122}));
                    assertEquals(0, (int) WindowsNativeIo.Api.acquire.invokeExact(owner, output, 0, loan));
                    try { transfer(owner, loan, true, 3, bytes, result, 3, 0); }
                    finally { WindowsNativeIo.Api.release.invokeExact(owner, loan); }
                    assertEquals(0, (int) WindowsNativeIo.Api.acquire.invokeExact(owner, input, 0, loan));
                    try { bytes.fill((byte) 0); transfer(owner, loan, false, 3, bytes, result, 3, 0); }
                    finally { WindowsNativeIo.Api.release.invokeExact(owner, loan); }
                    assertArrayEquals(new byte[]{37, 91, 122}, bytes.toArray(JAVA_BYTE));
                    assertEquals(0, sdk(library, "close", new MemoryLayout[]{JAVA_INT}, input));
                    input = -1;
                    assertEquals(0, (int) WindowsNativeIo.Api.acquire.invokeExact(owner, output, 0, loan));
                    try {
                        transfer(owner, loan, true, 3, bytes, result, -1, 32);
                        assertEquals(232, result.get(JAVA_INT, 8));
                    } finally { WindowsNativeIo.Api.release.invokeExact(owner, loan); }
                } finally {
                    if (input >= 0) assertEquals(0, sdk(library, "close", new MemoryLayout[]{JAVA_INT}, input));
                    assertEquals(0, sdk(library, "close", new MemoryLayout[]{JAVA_INT}, output));
                }
                assertEquals(0, sdk(library, "socket_pair", new MemoryLayout[]{ADDRESS}, pair));
                input = pair.get(JAVA_INT, 4); output = pair.get(JAVA_INT, 0);
                try {
                    assertEquals(0, (int) WindowsNativeIo.Api.acquire.invokeExact(owner, output, 1, loan));
                    try { transfer(owner, loan, true, 3, bytes, result, 3, 0); }
                    finally { WindowsNativeIo.Api.release.invokeExact(owner, loan); }
                    assertEquals(0, (int) WindowsNativeIo.Api.acquire.invokeExact(owner, input, 1, loan));
                    try { bytes.fill((byte) 0); transfer(owner, loan, false, 3, bytes, result, 3, 0); }
                    finally { WindowsNativeIo.Api.release.invokeExact(owner, loan); }
                    assertArrayEquals(new byte[]{37, 91, 122}, bytes.toArray(JAVA_BYTE));
                    assertEquals(10038, (int) WindowsNativeIo.Api.acquire.invokeExact(owner, -1, 1, loan));
                } finally {
                    assertEquals(0, sdk(library, "socket_close", new MemoryLayout[]{JAVA_INT}, input));
                    assertEquals(0, sdk(library, "socket_close", new MemoryLayout[]{JAVA_INT}, output));
                    assertEquals(0, sdk(library, "socket_cleanup", new MemoryLayout[0]));
                }
            } finally { WindowsNativeIo.Api.unbind.invokeExact(owner); }
        }
    }
    @Test void fixedNativeFactoryDoesNotConfuseEmbeddingStreamsWithProcessEndpoints() throws Throwable {
        try (var context = NativeIO.createContext()) {
            var owner = enter(context);
            try {
                var io = owner.getWindowsNativeIo();
                assertNotNull(io);
                var heap = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8, false, 8));
                var failure = assertThrows(RuntimeFault.class, () -> io.transfer(8, false, 1, heap, false));
                assertTrue(failure.getMessage().contains("addressable caller-owned storage"));
                failure = assertThrows(RuntimeFault.class, () -> io.transfer(8, false, 1, ManagedAddress.nullAddress(), false));
                assertTrue(failure.getMessage().contains("storage for a nonzero length"));
                failure = assertThrows(RuntimeFault.class, () -> io.transfer(8, false, 0, ManagedAddress.nullAddress(), false));
                assertTrue(failure.getMessage().contains("selected ghc-internal native component"));
                originalOpen(owner);
                assertThrows(SecurityException.class, () -> io.transfer(0, false, 0, ManagedAddress.nullAddress(), false));
                assertThrows(SecurityException.class, () -> io.transfer(1, false, 0, ManagedAddress.nullAddress(), true));
                assertThrows(SecurityException.class, () -> io.transfer(2, false, 0, ManagedAddress.nullAddress(), true));
                try (var arena = Arena.ofConfined()) {
                    var selected = SymbolLookup.libraryLookup(Path.of(System.getProperty("thc.projectRoot"),
                        "build/generated/test-cbits/windows-io-fixture.dll"), arena);
                    int alias = sdk(selected, "dup", new MemoryLayout[]{JAVA_INT}, 0);
                    assertTrue(alias >= 3);
                    try { assertThrows(SecurityException.class, () -> io.transfer(alias, false, 0, ManagedAddress.nullAddress(), false)); }
                    finally { assertEquals(0, sdk(selected, "close", new MemoryLayout[]{JAVA_INT}, alias)); }
                }
            } finally { leave(context); }
        }
    }
}
