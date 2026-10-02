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

import static org.junit.jupiter.api.Assertions.*;

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
}
