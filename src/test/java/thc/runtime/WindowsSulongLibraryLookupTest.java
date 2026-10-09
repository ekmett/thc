// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.FrameSlotKind;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test void ioManagerSelectionIsReadOnlyContextOwnedDescriptorMode() {
        var proof = new CoreRepresentation(CoreKind.ADDRESS, true, true, List.of("AddrRep"));
        try (var context = NativeIO.createContext(Set.of())) {
            var owner = enter(context);
            try {
                var mode = CoreDataLabels.fromCore("rts_IOManagerIsWin32Native", proof);
                assertEquals(0, mode.readWord8Int(0), "the real descriptor frontend is not WinIO HANDLE/IOCP");
                assertSame(mode, CoreDataLabels.fromCore("rts_IOManagerIsWin32Native", proof));
                assertSame(mode, mode.plus(0));
                assertFalse(mode.sameLocation(ManagedAddress.nullAddress()));
                assertThrows(RuntimeFault.class, () -> mode.readWord8Int(1));
                assertThrows(RuntimeFault.class, () -> mode.readWord8Int(-1));
                assertThrows(RuntimeFault.class, () -> mode.writeWord8(0, 1));
                assertThrows(RuntimeFault.class, mode::toNativeBits);
                assertThrows(RuntimeFault.class, () -> ManagedAddressRead.WORD32.readInt(mode, 0));
                try (var other = NativeIO.createContext(Set.of())) {
                    enter(other);
                    try {
                        assertThrows(RuntimeFault.class, () -> mode.readWord8Int(0));
                        assertThrows(RuntimeFault.class, () -> mode.sameLocation(mode));
                    } finally { leave(other); }
                }
                owner.getWindowsNativeIo().close();
                assertThrows(RuntimeFault.class, () -> mode.readWord8Int(0));
            } finally { leave(context); }
        }
        try (var context = context(true)) {
            enter(context);
            try { assertThrows(SecurityException.class, () -> CoreDataLabels.fromCore("rts_IOManagerIsWin32Native", proof)); }
            finally { leave(context); }
        }
    }

    @Test void consoleInstallationHasSeparateLauncherAndStablePtrAuthority() {
        try (var context = NativeIO.createContext(Set.of())) {
            var owner = enter(context);
            try {
                assertThrows(SecurityException.class, () -> owner.getWindowsNativeIo().console().install(-2, ManagedAddress.nullAddress()));
            } finally { leave(context); }
        }
        try (var context = NativeIO.commandLineContext()) {
            var owner = enter(context);
            try {
                var console = owner.getWindowsNativeIo().console();
                assertThrows(RuntimeFault.class, () -> console.install(-4, ManagedAddress.nullAddress()),
                    "A native pointer is not authority to execute a foreign GHC StablePtr");
                var stable = owner.getStablePointers().make(new Object());
                try {
                    var cell = ManagedAddress.fromAllocation(ManagedAllocation.nativeMutable(8, 8));
                    cell.writeAddressElementIndex(0, stable);
                    assertThrows(RuntimeFault.class, () -> cell.requireByteRegion(8, true),
                        "the ordinary native byte-transport guard must keep protecting pointer cells");
                    var missingProgram = assertThrows(RuntimeFault.class, () -> console.install(-4, cell));
                    assertTrue(missingProgram.getMessage().contains("original executable program"),
                        "a valid Haskell pointer cell must reach the separate program-authority check");
                    assertTrue(stable.sameLocation(cell.readAddressElementIndex(0)), "preflight mutated the StablePtr cell");
                    var raw = ManagedAddress.fromAllocation(ManagedAllocation.nativeMutable(8, 8));
                    raw.cbitsSegment();
                    var exposed = assertThrows(RuntimeFault.class, () -> console.install(-4, raw));
                    assertTrue(exposed.getMessage().contains("raw-exposed array"), "typed preflight cannot ignore raw exposure");
                } finally { owner.getStablePointers().free(stable); }
                assertThrows(RuntimeFault.class, () -> console.install(-3, ManagedAddress.nullAddress()));
                assertEquals(-1, console.install(-2, ManagedAddress.nullAddress()));
                assertEquals(-2, console.install(-1, ManagedAddress.nullAddress()));
                console.close();
                assertThrows(RuntimeFault.class, () -> console.install(-2, ManagedAddress.nullAddress()));
            } finally { leave(context); }
        }
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
                    // Actual handwritten/generated scheduler operations, with
                    // both result carriers. These are JVM models, not invented Core.
                    for (boolean bytecode : List.of(false, true)) {
                        var error = ManagedAddress.fromByteArray(new byte[8]);
                        var writeCall = windowsRequestCall(language, true, bytecode);
                        var readCall = windowsRequestCall(language, false, bytecode);
                        assertEquals(0, sdk(selected, "seek", new MemoryLayout[]{JAVA_INT}, fd));
                        bytes.copyFromByteArray(new byte[]{37, 91, 122}, 0, 3);
                        assertEquals(3L, writeCall.call((long) fd, bytes, 3L, error));
                        assertEquals(0, ManagedAddressRead.INT.read(error, 0));
                        assertEquals(0, sdk(selected, "seek", new MemoryLayout[]{JAVA_INT}, fd));
                        bytes.fill(3, 0);
                        assertEquals(2L, readCall.call((long) fd, bytes, 2L, error));
                        assertEquals(0, ManagedAddressRead.INT.read(error, 0));
                        bytes.copyToByteArray(actual, 0, 3);
                        assertArrayEquals(new byte[]{37, 91, 0}, actual);
                        assertEquals(-1L, readCall.call(-1L, ManagedAddress.nullAddress(), 0L, error));
                        assertEquals(9, ManagedAddressRead.INT.read(error, 0), "original length/error pair is retained");
                        assertThrows(RuntimeFault.class, () -> readCall.call((long) fd, ManagedAddress.fromByteArray(new byte[1]), 1L, error),
                            "scheduler lowering cannot promote a heap buffer at a foreign call");
                    }
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

    private static com.oracle.truffle.api.RootCallTarget windowsRequestCall(Language language, boolean writing, boolean bytecode) {
        if (bytecode) return BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
            b.beginRoot();
            var request = b.createLocal("native physical request", FrameSlotKind.Object);
            var length = b.createLocal("native length", FrameSlotKind.Long);
            var error = b.createLocal("native error", FrameSlotKind.Long);
            var stored = b.createLocal("error store state", FrameSlotKind.Object);
            b.beginStaticStoreObject(request);
            b.beginPrepareWindowsIoRequest(writing);
            b.emitLoadArgument(0); b.emitLoadConstant(0L); b.emitLoadArgument(2);
            b.emitLoadArgument(1); b.emitLoadConstant(Unit.INSTANCE);
            b.endPrepareWindowsIoRequest(); b.endStaticStoreObject();
            b.beginAwaitWindowsIoRequest(length, error); b.emitStaticLoadObject(request); b.endAwaitWindowsIoRequest();
            b.beginStaticStoreObject(stored); b.beginWriteNativeScalarOffAddr(8, false);
            b.emitLoadArgument(3); b.emitLoadConstant(0L); b.emitStaticLoadLong(error); b.emitLoadConstant(Unit.INSTANCE);
            b.endWriteNativeScalarOffAddr(); b.endStaticStoreObject();
            b.beginReturn(); b.emitStaticLoadLong(length); b.endReturn();
            b.endRoot();
        }).getNode(0).getCallTarget();
        var layout = new FrameLayout();
        int[] slots = {layout.bind("native length", FrameSlotKind.Long), layout.bind("native error", FrameSlotKind.Long)};
        Expr[] operands = new Expr[5];
        for (int i = 0; i < operands.length; i++) {
            final int index = i;
            operands[i] = new Expr() {
                @Override public Object execute(VirtualFrame frame) {
                    return switch (index) {
                        case 0 -> frame.getArguments()[0]; case 1 -> 0L; case 2 -> frame.getArguments()[2];
                        case 3 -> frame.getArguments()[1]; default -> Unit.INSTANCE;
                    };
                }
            };
        }
        return new RootNode(language, layout.build()) {
            @Child private WindowsIoExpression operation = new WindowsIoExpression(writing ? WindowsIoOp.WRITE : WindowsIoOp.READ, operands, CoreRepresentation.UNKNOWN);
            @Override public Object execute(VirtualFrame frame) {
                operation.executeTuple(frame, slots);
                ((ManagedAddress) frame.getArguments()[3]).writeNativeScalar(0, 8, (long) FrameAccess.read(frame, slots[1]), false);
                return FrameAccess.read(frame, slots[0]);
            }
        }.getCallTarget();
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
    /** Abandonment revokes delivery, not the native read. The duplicated pipe
     * and worker-origin storage loan survive until actual completion. */
    @Test void physicalRequestKeepsNativeLoansAfterGuestAbandonment() throws Throwable {
        var image = Path.of(System.getProperty("thc.projectRoot"), "build/generated/test-cbits/windows-io-fixture.dll");
        try (var context = WindowsDirectoryStreams.createContext(ContextProfile.SYNCHRONOUS_TEST);
             var arena = Arena.ofConfined()) {
            var owner = enter(context);
            try {
                originalOpen(owner);
                var library = SymbolLookup.libraryLookup(image, arena);
                var pair = arena.allocate(8, 4);
                assertEquals(0, sdk(library, "pipe", new MemoryLayout[]{ADDRESS}, pair));
                int input = pair.get(JAVA_INT, 0), output = pair.get(JAVA_INT, 4);
                var bytes = ManagedAddress.fromAllocation(ManagedAllocation.nativeMutable(1, 8));
                var io = owner.getWindowsNativeIo();
                WindowsNativeIo.Request request = null;
                try {
                    request = io.submit(input, false, 1, bytes, false);
                    request.awaitAcquired(null);
                    assertEquals(0, sdk(library, "close", new MemoryLayout[]{JAVA_INT}, input)); input = -1;
                    request.abandon();
                    var value = arena.allocateFrom(JAVA_BYTE, new byte[]{73});
                    assertEquals(1, sdk(library, "write", new MemoryLayout[]{JAVA_INT, ADDRESS, JAVA_INT}, output, value, 1));
                    request.join();
                    assertEquals(73, bytes.readWord8(0));
                    var abandoned = request;
                    assertThrows(RuntimeFault.class, () -> abandoned.await(null));
                    io.finishRequests();
                    assertThrows(RuntimeFault.class, () -> io.submit(output, false, 0, ManagedAddress.nullAddress(), true));
                } finally {
                    if (input >= 0) sdk(library, "close", new MemoryLayout[]{JAVA_INT}, input);
                    sdk(library, "close", new MemoryLayout[]{JAVA_INT}, output);
                    if (request != null) request.join();
                }
            } finally { leave(context); }
        }
    }
    /** The real target claims/acknowledges throwTo while its read is still
     * physically blocked. Supplying data later proves abandonment did not
     * close a shared pipe, lose storage ownership or replay the read. */
    @Test void cancellingGuestWaitKeepsItsPhysicalReadAlive() throws Throwable {
        for (var mask : List.of(MaskingState.UNMASKED, MaskingState.MASKED_INTERRUPTIBLE)) {
            var image = Path.of(System.getProperty("thc.projectRoot"), "build/generated/test-cbits/windows-io-fixture.dll");
            try (var context = WindowsDirectoryStreams.createContext(ContextProfile.SYNCHRONOUS_TEST);
                 var arena = Arena.ofConfined()) {
                var owner = enter(context);
                try {
                    originalOpen(owner);
                    var library = SymbolLookup.libraryLookup(image, arena);
                    var pair = arena.allocate(8, 4);
                    assertEquals(0, sdk(library, "pipe", new MemoryLayout[]{ADDRESS}, pair));
                    int input = pair.get(JAVA_INT, 0), output = pair.get(JAVA_INT, 4);
                    var bytes = ManagedAddress.fromAllocation(ManagedAllocation.nativeMutable(1, 8));
                    var ready = new CountDownLatch(1);
                    var finished = new CountDownLatch(1);
                    var identity = new AtomicLong();
                    var outcome = new AtomicReference<Object>();
                    Object payload = new Object();
                    var threads = owner.getThreads();
                    owner.admitGuestConcurrency();
                    var target = threads.newThread(owner.getEnv(), () -> {
                        threads.enterCurrent(mask, true, true, null);
                        identity.set(threads.currentId()); ready.countDown();
                        try { outcome.set(owner.getWindowsNativeIo().transfer(input, false, 1, bytes, false)); }
                        catch (AsyncBlocked blocked) {
                            outcome.set(blocked.getRequest().getPayload());
                            blocked.getRequest().acknowledge();
                        } catch (Throwable failure) { outcome.set(failure); }
                        finally { threads.leaveCurrent(); finished.countDown(); }
                    }, null, null);
                    boolean started = false, inputClosed = false;
                    try {
                        threads.startThread(target); started = true;
                        assertTrue(ready.await(5, TimeUnit.SECONDS), "guest did not enter");
                        var request = threads.send(identity.get(), payload);
                        assertTrue(finished.await(5, TimeUnit.SECONDS), "guest cancellation waited for the native pipe");
                        assertSame(payload, outcome.get());
                        assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
                        assertEquals(0, sdk(library, "close", new MemoryLayout[]{JAVA_INT}, input));
                        inputClosed = true;
                        var value = arena.allocateFrom(JAVA_BYTE, new byte[]{91});
                        assertEquals(1, sdk(library, "write", new MemoryLayout[]{JAVA_INT, ADDRESS, JAVA_INT}, output, value, 1));
                        owner.getWindowsNativeIo().finishRequests();
                        assertEquals(91, bytes.readWord8(0));
                    } finally {
                        // EOF completes the physical read even on a failed
                        // assertion; join precedes context/native retirement.
                        sdk(library, "close", new MemoryLayout[]{JAVA_INT}, output);
                        if (!inputClosed) sdk(library, "close", new MemoryLayout[]{JAVA_INT}, input);
                        if (started) target.join();
                    }
                } finally { leave(context); }
            }
        }
    }
    /** A saved operation survives its original carrier, but terminal discard
     * revokes delivery. An independent SDK read detects replay of the effect. */
    @Test void suspendedNativeRequestResumesWithoutReplayingItsRead() throws Throwable {
        for (boolean bytecode : List.of(false, true)) for (boolean discard : List.of(false, true)) {
            var image = Path.of(System.getProperty("thc.projectRoot"), "build/generated/test-cbits/windows-io-fixture.dll");
            try (var context = WindowsDirectoryStreams.createContext(ContextProfile.SYNCHRONOUS_TEST);
                 var arena = Arena.ofConfined()) {
                var owner = enter(context);
                try {
                    originalOpen(owner);
                    var library = SymbolLookup.libraryLookup(image, arena);
                    var pair = arena.allocate(8, 4);
                    assertEquals(0, sdk(library, "pipe", new MemoryLayout[]{ADDRESS}, pair));
                    int input = pair.get(JAVA_INT, 0), output = pair.get(JAVA_INT, 4);
                    int alias = sdk(library, "dup", new MemoryLayout[]{JAVA_INT}, input);
                    assertTrue(alias >= 0);
                    var bytes = ManagedAddress.fromAllocation(ManagedAllocation.nativeMutable(1, 8));
                    var io = owner.getWindowsNativeIo();
                    var physical = io.submit(input, false, 1, bytes, false);
                    physical.awaitAcquired(null);
                    var ready = new CountDownLatch(1);
                    var cut = new CountDownLatch(1);
                    var resumed = new CountDownLatch(1);
                    var identity = new AtomicLong();
                    var outcome = new AtomicReference<Object>();
                    var saved = new AtomicReference<SavedGuestContinuation>();
                    var root = bytecode ? nativeRequestRoot() : null;
                    Object payload = new Object();
                    var threads = owner.getThreads();
                    owner.admitGuestConcurrency();
                    var first = threads.newThread(owner.getEnv(), () -> {
                        threads.enterCurrent(MaskingState.UNMASKED, true, true, null);
                        identity.set(threads.currentId()); ready.countDown();
                        try {
                            if (!bytecode) outcome.set(physical.awaitResumable(null, false));
                            else {
                                var continuation = SavedGuestContinuations.savedGuestContinuation(
                                    Calls.target(root.getCallTarget(), new Object[]{0L, physical}));
                                assertNotNull(continuation);
                                saved.set(continuation);
                                var incoming = continuation.asyncRequest();
                                assertNotNull(incoming);
                                outcome.set(incoming.getPayload());
                                incoming.acknowledge();
                            }
                        }
                        catch (AsyncBlocked blocked) {
                            outcome.set(blocked.getRequest().getPayload());
                            blocked.getRequest().acknowledge();
                        } catch (Throwable failure) { outcome.set(failure); }
                        finally { threads.leaveCurrent(); cut.countDown(); }
                    }, null, null);
                    var second = threads.newThread(owner.getEnv(), () -> {
                        threads.enterCurrent(MaskingState.UNMASKED, true, true, null);
                        try { outcome.set(bytecode ? saved.get().continueWith(Unit.INSTANCE) : physical.awaitResumable(null, false)); }
                        catch (Throwable failure) { outcome.set(failure); }
                        finally { threads.leaveCurrent(); resumed.countDown(); }
                    }, null, null);
                    boolean firstStarted = false, secondStarted = false, inputClosed = false, outputClosed = false;
                    try {
                        threads.startThread(first); firstStarted = true;
                        assertTrue(ready.await(5, TimeUnit.SECONDS));
                        var delivery = threads.send(identity.get(), payload);
                        assertTrue(cut.await(5, TimeUnit.SECONDS), "saved cut waited for physical completion");
                        first.join();
                        assertSame(payload, outcome.get());
                        assertEquals(AsyncRequestState.ACKNOWLEDGED, delivery.getState());
                        assertTrue(physical.suspended());
                        assertFalse(physical.abandoned(), "carrier departure discarded the saved operation");
                        if (bytecode) {
                            try (var other = NativeIO.createContext(Set.of())) {
                                enter(other);
                                try { assertThrows(RuntimeFault.class, () -> saved.get().discard()); }
                                finally { leave(other); }
                            }
                            assertFalse(physical.abandoned(), "Another context discarded the activation's request");
                        }
                        if (discard) {
                            if (bytecode) saved.get().discard(); else physical.abandon();
                            assertTrue(physical.abandoned(), "Saved-frame discard did not retire its own request delivery");
                        }
                        assertEquals(0, sdk(library, "close", new MemoryLayout[]{JAVA_INT}, input)); inputClosed = true;
                        var values = arena.allocateFrom(JAVA_BYTE, new byte[]{53, 71});
                        assertEquals(2, sdk(library, "write", new MemoryLayout[]{JAVA_INT, ADDRESS, JAVA_INT}, output, values, 2));
                        assertEquals(0, sdk(library, "close", new MemoryLayout[]{JAVA_INT}, output)); outputClosed = true;
                        physical.join();
                        threads.startThread(second); secondStarted = true;
                        assertTrue(resumed.await(5, TimeUnit.SECONDS), "saved operation did not resume");
                        if (discard) assertInstanceOf(RuntimeFault.class, outcome.get());
                        else if (bytecode) assertEquals(1L, outcome.get());
                        else assertEquals(1, assertInstanceOf(WindowsNativeIo.Result.class, outcome.get()).length());
                        assertEquals(53, bytes.readWord8(0));
                        var remaining = arena.allocate(1);
                        assertEquals(1, sdk(library, "read", new MemoryLayout[]{JAVA_INT, ADDRESS, JAVA_INT}, alias, remaining, 1),
                            "resumption replayed the native read");
                        assertEquals(71, remaining.get(JAVA_BYTE, 0));
                        if (bytecode && !discard) {
                            saved.get().discard();
                            assertEquals(1, physical.await(null).length(), "Completed operation retained a saved-frame owner");
                        }
                    } finally {
                        if (!outputClosed) sdk(library, "close", new MemoryLayout[]{JAVA_INT}, output);
                        if (!inputClosed) sdk(library, "close", new MemoryLayout[]{JAVA_INT}, input);
                        if (firstStarted) first.join();
                        if (secondStarted) second.join();
                        physical.join();
                        sdk(library, "close", new MemoryLayout[]{JAVA_INT}, alias);
                    }
                } finally { leave(context); }
            }
        }
    }
    @Test void astSchedulerCallRetainsItsPhysicalReadAcrossCarrierAndTerminalCut() throws Throwable {
        for (boolean discard : List.of(false, true)) {
            try (var context = WindowsDirectoryStreams.createContext(ContextProfile.SYNCHRONOUS_TEST); var arena = Arena.ofConfined()) {
                var owner = enter(context);
                try {
                    originalOpen(owner);
                    var library = SymbolLookup.libraryLookup(Path.of(System.getProperty("thc.projectRoot"), "build/generated/test-cbits/windows-io-fixture.dll"), arena);
                    var pair = arena.allocate(8, 4);
                    assertEquals(0, sdk(library, "pipe", new MemoryLayout[]{ADDRESS}, pair));
                    int input = pair.get(JAVA_INT, 0), output = pair.get(JAVA_INT, 4);
                    int alias = sdk(library, "dup", new MemoryLayout[]{JAVA_INT}, input);
                    assertTrue(alias >= 0);
                    var bytes = ManagedAddress.fromAllocation(ManagedAllocation.nativeMutable(1, 8));
                    var ready = new CountDownLatch(1);
                    var cut = new CountDownLatch(1);
                    var identity = new AtomicLong();
                    var evaluations = new java.util.concurrent.atomic.AtomicInteger();
                    var outcome = new AtomicReference<Object>();
                    var saved = new AtomicReference<SavedGuestContinuation>();
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var state = new CoreRepresentation(CoreKind.VOID, true, true, List.of());
                    var number = new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"));
                    var proof = new CoreRepresentation(CoreKind.UNKNOWN, true, true, List.of("IntRep", "IntRep"), List.of(state, number, number));
                    var shape = new TupleShape(proof, language);
                    var layout = new FrameLayout();
                    int[] slots = {layout.bind("length", FrameSlotKind.Long), layout.bind("error", FrameSlotKind.Long)};
                    Object[] values = {(long) input, 0L, 1L, bytes, Unit.INSTANCE};
                    Expr[] operands = new Expr[values.length];
                    for (int i = 0; i < values.length; i++) {
                        final int index = i;
                        operands[i] = new Expr() {
                            @Override public Object execute(VirtualFrame frame) {
                                // This rendezvous follows the real root entry
                                // poll; the owner must then prepare before its
                                // first interruptible wait, without replaying operands.
                                if (index == 4) { evaluations.incrementAndGet(); ready.countDown(); }
                                return values[index];
                            }
                        };
                    }
                    var body = new WindowsIoExpression(WindowsIoOp.READ, operands, proof);
                    var root = new FunctionRoot(language, layout.build(), "native AST scheduler call", null,
                        new int[0], new int[0], new int[0], body, new Metrics(false), new CoreRepresentation[0], proof,
                        null, new boolean[0], null, shape, slots, null, true, new int[0][], false, FunctionRootRole.FUNCTION, false);
                    var threads = owner.getThreads();
                    Object payload = new Object();
                    var first = threads.newThread(owner.getEnv(), () -> {
                        threads.enterCurrent(MaskingState.UNMASKED, true, true, null);
                        try {
                            identity.set(threads.currentId());
                            var continuation = assertInstanceOf(AstContinuation.class, root.getCallTarget().call(0L));
                            saved.set(continuation);
                            outcome.set(continuation.asyncRequest().getPayload());
                            continuation.asyncRequest().acknowledge();
                        } catch (Throwable failure) { outcome.set(failure); }
                        finally { threads.leaveCurrent(); cut.countDown(); }
                    }, null, null);
                    var second = threads.newThread(owner.getEnv(), () -> {
                        threads.enterCurrent(MaskingState.UNMASKED, true, true, null);
                        try {
                            var value = saved.get().continueWith(Unit.INSTANCE);
                            var pool = language.getHandoffState().get().getResults();
                            var storage = value == TupleComplete.INSTANCE ? pool.completed() : (HandoffStorage) value;
                            try { outcome.set(new long[]{shape.getLayout().getLong(storage, 0), shape.getLayout().getLong(storage, 1)}); }
                            finally { if (value == TupleComplete.INSTANCE) pool.releaseChecked(storage, shape.getLayout()); }
                        } catch (Throwable failure) { outcome.set(failure); }
                        finally { threads.leaveCurrent(); }
                    }, null, null);
                    boolean firstStarted = false, secondStarted = false, inputClosed = false, outputClosed = false;
                    try {
                        threads.startThread(first); firstStarted = true;
                        assertTrue(ready.await(5, TimeUnit.SECONDS));
                        var delivery = threads.send(identity.get(), payload);
                        assertTrue(cut.await(5, TimeUnit.SECONDS), "AST scheduler cut waited for pipe data");
                        first.join();
                        assertSame(payload, outcome.get());
                        assertEquals(AsyncRequestState.ACKNOWLEDGED, delivery.getState());
                        if (discard) saved.get().discard();
                        assertEquals(0, sdk(library, "close", new MemoryLayout[]{JAVA_INT}, input)); inputClosed = true;
                        var supplied = arena.allocateFrom(JAVA_BYTE, new byte[]{53, 71});
                        assertEquals(2, sdk(library, "write", new MemoryLayout[]{JAVA_INT, ADDRESS, JAVA_INT}, output, supplied, 2));
                        assertEquals(0, sdk(library, "close", new MemoryLayout[]{JAVA_INT}, output)); outputClosed = true;
                        threads.startThread(second); secondStarted = true; second.join();
                        owner.getWindowsNativeIo().finishRequests();
                        if (discard) assertInstanceOf(RuntimeFault.class, outcome.get());
                        else assertArrayEquals(new long[]{1, 0}, assertInstanceOf(long[].class, outcome.get()));
                        assertEquals(1, evaluations.get(), "saved AST operation replayed its operands/preparation");
                        assertEquals(53, bytes.readWord8(0));
                        var remaining = arena.allocate(1);
                        assertEquals(1, sdk(library, "read", new MemoryLayout[]{JAVA_INT, ADDRESS, JAVA_INT}, alias, remaining, 1));
                        assertEquals(71, remaining.get(JAVA_BYTE, 0), "saved AST operation replayed the physical read");
                    } finally {
                        if (!outputClosed) sdk(library, "close", new MemoryLayout[]{JAVA_INT}, output);
                        if (!inputClosed) sdk(library, "close", new MemoryLayout[]{JAVA_INT}, input);
                        if (firstStarted) first.join();
                        if (secondStarted) second.join();
                        owner.getWindowsNativeIo().finishRequests();
                        sdk(library, "close", new MemoryLayout[]{JAVA_INT}, alias);
                    }
                } finally { leave(context); }
            }
        }
    }

    /** Exercise the actual operation and saved frame, without manufacturing
     * a Core module or claiming original Haskell/compiled-call qualification. */
    private static BytecodeRoot nativeRequestRoot() {
        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
        var transactionSlot = new LocalAccessor[1];
        var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
            b.beginRoot();
            var transaction = b.createLocal("saved transaction", FrameSlotKind.Object);
            transactionSlot[0] = LocalAccessor.constantOf(transaction);
            b.beginStaticStoreObject(transaction); b.emitCurrentTransaction(); b.endStaticStoreObject();
            var length = b.createLocal("native length", FrameSlotKind.Long);
            var error = b.createLocal("native error", FrameSlotKind.Long);
            var resumed = b.createLocal("native cut resume", FrameSlotKind.Object);
            b.beginWhile(); b.emitLoadConstant(true); b.beginBlock();
            b.beginTryCatch();
            b.beginBlock();
            b.beginAwaitWindowsIoRequest(length, error);
            b.emitLoadArgument(1); b.endAwaitWindowsIoRequest();
            b.beginReturn(); b.emitStaticLoadLong(length); b.endReturn();
            b.endBlock();
            b.beginBlock();
            b.beginStaticStoreObject(resumed);
            b.beginYield(); b.beginCallSuspensionOnly(); b.emitLoadException(); b.endCallSuspensionOnly(); b.endYield();
            b.endStaticStoreObject();
            b.endBlock(); b.endTryCatch(); b.endBlock(); b.endWhile();
            b.emitLoadNull();
            b.endRoot();
        }).getNode(0);
        root.configureAsync(true);
        root.configureStackTransaction(transactionSlot[0]);
        return root;
    }
    /** Named native producer uses actual SDK files, pipes and loopback sockets.
     * This qualifies the boundary ABI/loans, not guest compiled execution. */
    @Test void selectedNativeNamespaceTransfersAndPinsRealDescriptors() throws Throwable {
        var image = Path.of(System.getProperty("thc.projectRoot"), "build/generated/test-cbits/windows-io-fixture.dll");
        assertTrue(Files.isRegularFile(image), "compileWindowsIoFixture must prepare its own DLL");
        try (var arena = Arena.ofConfined()) {
            var library = SymbolLookup.libraryLookup(image, arena);
            var error = arena.allocate(JAVA_INT);
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
