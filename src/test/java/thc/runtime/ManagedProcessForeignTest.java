// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** ABI/provider transaction tests; genuine Core execution is checked separately. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@Timeout(30)
public class ManagedProcessForeignTest {
    @TempDir public Path directory;
    private ManagedAddress nil() { return ManagedAddress.nullAddress(); }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private void inside(Action body) throws Exception { inside(true, body); }
    private void inside(boolean allowed, Action body) throws Exception {
        try (var context = NativeFileProvider.createContext(java.util.Set.of(), thc.ContextProfile.NATIVE, thc.FfiMode.NATIVE, allowed)) {
            context.enter(); try { body.run(); } finally { context.leave(); }
        }
    }
    private ManagedAddress bytes(byte[] value) {
        var address = Language.currentState().getNativeAllocations().malloc((long) value.length + 1);
        for (int i = 0; i < value.length; i++) address.writeWord8(i, value[i]);
        address.writeWord8(value.length, 0); return address;
    }
    private ManagedAddress string(String value) { return bytes(value.getBytes(StandardCharsets.UTF_8)); }
    private ManagedAddress vector(String... values) {
        var address = Language.currentState().getNativeAllocations().malloc(((long) values.length + 1) * 8);
        for (int i = 0; i < values.length; i++) address.writeAddressElementIndex(i, string(values[i]));
        address.writeAddressElementIndex(values.length, nil()); return address;
    }
    private ManagedAddress cell() { return cell(4); }
    private ManagedAddress cell(long width) { return Language.currentState().getNativeAllocations().malloc(width); }
    private String text(ManagedAddress address) {
        var bytes = new byte[(int) address.cStringLength()]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) address.readWord8(i);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private long integer(ManagedAddress address) { return ManagedAddressRead.INT32.readInt(address, 0); }
    private List<ManagedAddress> outputs(boolean initialize) {
        var result = new ArrayList<ManagedAddress>();
        for (int i = 0; i < 3; i++) { var value = cell(); if (initialize) value.writeNativeScalar(0, 4, 991); result.add(value); }
        return result;
    }
    private long create(ManagedAddress command) { return create(command, nil(), nil(), new long[]{-2, -2, -2}, outputs(false), cell(8), nil(), nil()); }
    private long create(ManagedAddress command, long[] streams, List<ManagedAddress> outputs) { return create(command, nil(), nil(), streams, outputs, cell(8), nil(), nil()); }
    private long create(ManagedAddress command, long[] streams, List<ManagedAddress> outputs, ManagedAddress failure) { return create(command, nil(), nil(), streams, outputs, failure, nil(), nil()); }
    private long create(ManagedAddress command, ManagedAddress environment, ManagedAddress cwd, long[] streams, List<ManagedAddress> outputs, ManagedAddress failure, ManagedAddress group, ManagedAddress user) {
        return ManagedProcessForeign.current(null).execute(ProcessOp.CREATE, new Object[]{command, cwd, environment, streams[0], streams[1], streams[2],
            outputs.get(0), outputs.get(1), outputs.get(2), group, user, 0L, failure, thc.runtime.Unit.INSTANCE});
    }
    private long status(ProcessOp operation, long pid) { return status(operation, pid, cell()); }
    private long status(ProcessOp operation, long pid, ManagedAddress output) {
        return ManagedProcessForeign.current(null).execute(operation, operation == ProcessOp.TERMINATE ? new Object[]{pid, thc.runtime.Unit.INSTANCE} : new Object[]{pid, output, thc.runtime.Unit.INSTANCE});
    }
    @Test public void realChildPipeEnvironmentAndCwdReachTheOriginalAbi() throws Exception { inside(() -> {
        var state = Language.currentState(); state.getEnvironment().put(string("PATH=/bin")); state.getEnvironment().put(bytes(new byte[]{82, 65, 87, 61, -1, -2}));
        var snapshot = state.getEnvironment().snapshotForProcess(); byte[] raw = null;
        for (var entry : snapshot.getEntries()) if (Arrays.equals(Arrays.copyOfRange(entry, 0, Math.min(4, entry.length)), new byte[]{82, 65, 87, 61})) { raw = entry; break; }
        assertArrayEquals(new byte[]{82, 65, 87, 61, -1, -2}, raw); assertArrayEquals("/bin".getBytes(StandardCharsets.UTF_8), snapshot.getSearchPath());
        NativeFileProvider.current().changeDirectory(NativeDirectoryOwner.pathBytes(directory)); Files.writeString(directory.resolve("value"), "cwd-data");
        var outputs = outputs(true); var failure = cell(8);
        var pid = create(vector("sh", "-c", "printf '%s:%s:' \"$THC_VALUE\" \"$$\"; /bin/cat value"), vector("THC_VALUE=child", "PATH=/unavailable-child-path"), nil(),
            new long[]{-2, -1, -2}, outputs, failure, nil(), nil());
        assertTrue(pid > 0); assertSame(nil(), failure.readAddressElementIndex(0));
        assertEquals(991L, integer(outputs.get(0))); assertEquals(991L, integer(outputs.get(2)));
        var fd = integer(outputs.get(1)); var received = cell(64); var content = new StringBuilder();
        while (true) {
            var size = state.getStdio().read(fd, received, 64); assertTrue(size >= 0); if (size == 0) break;
            var bytes = new byte[(int) size]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) received.readWord8(i);
            content.append(new String(bytes, StandardCharsets.UTF_8));
        }
        assertEquals("child:" + pid + ":cwd-data", content.toString()); var code = cell();
        assertEquals(0L, status(ProcessOp.WAIT, pid, code)); assertEquals(0L, integer(code)); assertEquals(0L, state.getFiles().close(fd));
    }); }
    private String readAll(long descriptor) {
        var buffer = cell(64); var result = new StringBuilder();
        for (;;) {
            long size = Language.currentState().getStdio().read(descriptor, buffer, 64);
            assertTrue(size >= 0); if (size == 0) return result.toString();
            for (long index = 0; index < size; index++) result.append((char) buffer.readWord8(index));
        }
    }
    private List<byte[]> arguments(String... values) {
        var result = new ArrayList<byte[]>();
        for (var value : values) result.add(value.getBytes(StandardCharsets.UTF_8));
        return result;
    }
    private long inheritedPipe() {
        var stdio = Language.currentState().getStdio(); var pair = cell(8);
        assertEquals(0L, stdio.pipe(pair));
        long reader = integer(pair), writer = ManagedAddressRead.INT32.readInt(pair, 1);
        assertEquals(70L, stdio.fcntl(reader, 0, 70, true)); // Linux F_DUPFD.
        assertEquals(0L, stdio.close(reader));
        assertEquals(1L, stdio.write(writer, ManagedAddress.fromByteArray(new byte[]{42}), 1));
        assertEquals(0L, stdio.close(writer));
        return 70;
    }
    @Test public void guestDescriptorNumbersAndClosePoliciesReachTheRealChild() throws Exception { inside(() -> {
        var state = Language.currentState(); var files = state.getFiles(); var stdio = state.getStdio();
        var oracle = Path.of(System.getProperty("thc.projectRoot"), "build/process-lifecycle/native/process-oracle");
        for (String policy : List.of("inherit", "close-on-exec", "close-fds")) {
            long descriptor = inheritedPipe(); int[] output = {-1};
            try {
                assertEquals(0L, stdio.fcntl(descriptor, 1, 0, false)); // F_GETFD: F_DUPFD clears FD_CLOEXEC.
                if (policy.equals("close-on-exec")) assertEquals(0L, stdio.fcntl(descriptor, 2, 1, true));
                boolean inherit = policy.equals("inherit");
                int pid = files.launchProcess(arguments(oracle.toString(), inherit ? "descriptor" : "closed-descriptor", "70"),
                    List.of(), null, new int[]{-2, -1, -2}, policy.equals("close-fds") ? 1 : 0, null, null, null,
                    (child, returned) -> output[0] = returned[1]);
                assertEquals(inherit ? "42\n" : "closed\n", readAll(output[0]), policy);
                assertEquals(new ProcessResult(0, 0, 0), files.processOperation(ProcessOp.WAIT, pid), policy);
            } finally { if (output[0] >= 0) stdio.close(output[0]); stdio.close(descriptor); }
        }
    }); }
    @Test public void failedLaunchReleasesInheritedDuplicatesWithoutClosingGuestDescriptors() throws Exception { inside(() -> {
        var state = Language.currentState(); var files = state.getFiles(); var stdio = state.getStdio();
        long descriptor = inheritedPipe();
        try {
            var warm = create(vector("/bin/true")); assertTrue(warm > 0); assertEquals(0L, status(ProcessOp.WAIT, warm));
            long before; try (var descriptors = Files.list(Path.of("/proc/self/fd"))) { before = descriptors.count(); }
            for (int attempt = 0; attempt < 8; attempt++) {
                var failure = assertThrows(ProcessSpawnException.class, () -> files.launchProcess(
                    arguments("/definitely-missing-thc-command"), List.of(), null, new int[]{-1, -1, -1}, 0,
                    null, null, null, (child, returned) -> fail("Failed launch published a child")));
                assertEquals(2, failure.getErrno());
            }
            try (var descriptors = Files.list(Path.of("/proc/self/fd"))) { assertEquals(before, descriptors.count()); }
            var value = cell(1); assertEquals(1L, stdio.read(descriptor, value, 1)); assertEquals(42L, value.readWord8(0));
        } finally { assertEquals(0L, stdio.close(descriptor)); }
    }); }
    @Test public void failureAndBadOutputsHaveNoLaunchedChildOrPublishedDescriptors() throws Exception { inside(() -> {
        var state = Language.currentState(); var outputs = outputs(true); var failure = cell(8);
        assertEquals(-1L, create(vector("/definitely-missing-thc-command"), new long[]{-1, -1, -1}, outputs, failure));
        assertEquals("posix_spawnp", text(failure.readAddressElementIndex(0)));
        assertThrows(RuntimeFault.class, () -> failure.readAddressElementIndex(0).writeWord8(0, 0));
        assertThrows(RuntimeFault.class, () -> state.getNativeAllocations().free(failure.readAddressElementIndex(0)));
        boolean unchanged = true; for (var output : outputs) unchanged &= integer(output) == 991L; assertTrue(unchanged);
        var marker = directory.resolve("unexpected"); var command = vector("/bin/sh", "-c", "touch '" + marker + "'");
        assertThrows(RuntimeFault.class, () -> create(command, new long[]{-1, -1, -2}, List.of(outputs.get(0), outputs.get(0), outputs.get(2))));
        assertThrows(RuntimeFault.class, () -> create(command, new long[]{-1, -2, -2}, List.of(nil(), outputs.get(1), outputs.get(2))));
        assertFalse(Files.exists(marker)); assertEquals(0L, state.getFiles().errorKind());
    }); }
    @Test public void pollWaitAndTerminationPreserveOriginalOutputAndErrnoRules() throws Exception { inside(() -> {
        var state = Language.currentState(); var outputs = outputs(false);
        var pid = create(vector("/bin/sh", "-c", "printf R; read line; exit 23"), new long[]{-1, -1, -2}, outputs);
        var ready = cell(1); assertEquals(1L, state.getStdio().read(integer(outputs.get(1)), ready, 1)); assertEquals((long) 'R', ready.readWord8(0));
        var code = cell(); code.writeNativeScalar(0, 4, 991);
        assertEquals(0L, status(ProcessOp.POLL, pid, code)); assertEquals(0L, integer(code));
        assertEquals(1L, status(ProcessOp.TERMINATE, pid)); assertEquals(0L, status(ProcessOp.WAIT, pid, code)); assertEquals(-15L, integer(code));
        assertEquals(1L, status(ProcessOp.POLL, pid, code)); assertEquals(0L, integer(code)); assertEquals(10L, state.getStdio().errno());
        code.writeNativeScalar(0, 4, 991); assertEquals(-1L, status(ProcessOp.WAIT, pid, code)); assertEquals(991L, integer(code)); assertEquals(10L, state.getStdio().errno());
        assertEquals(0L, status(ProcessOp.TERMINATE, pid)); assertEquals(3L, state.getStdio().errno());
        assertEquals(0L, state.getFiles().close(integer(outputs.get(0)))); assertEquals(0L, state.getFiles().close(integer(outputs.get(1))));
    }); }
    @Test public void failedDescriptorPublicationAbortsOnlyTheNewOwnedLaunch() throws Exception { inside(() -> {
        var files = Language.currentState().getFiles(); var pid = new int[]{-1}; var returned = new int[][]{new int[0]};
        class PublicationFailure extends RuntimeException {}
        assertThrows(PublicationFailure.class, () -> {
            var arguments = new ArrayList<byte[]>(); for (var value : List.of("/bin/sh", "-c", "read line")) arguments.add(value.getBytes(StandardCharsets.UTF_8));
            files.launchProcess(arguments, List.of(), null, new int[]{-1, -1, -1}, 0, null, null, null, (child, descriptors) -> {
                pid[0] = child; returned[0] = descriptors.clone(); throw new PublicationFailure();
            });
        });
        assertTrue(pid[0] > 0); assertFalse(ProcessHandle.of((long) pid[0]).map(ProcessHandle::isAlive).orElse(false));
        for (int fd : returned[0]) assertEquals(-1L, files.close(fd), "Unpublished guest descriptor cannot remain installed");
        var next = create(vector("/bin/true")); assertTrue(next > 0); assertEquals(0L, status(ProcessOp.WAIT, next));
    }); }
    @Test public void explicitProcessGrantIsRequiredEvenWithNativeFiles() throws Exception { inside(false, () -> assertThrows(SecurityException.class, () -> create(vector("/bin/true")))); }
    @Test public void nonNullUnsignedCredentialsRejectBeforeLaunchWithoutPublishingOutputs() throws Exception { inside(() -> {
        var state = Language.currentState();
        for (boolean group : new boolean[]{false, true}) for (long bits : new long[]{0L, 0x80000000L, 0xffffffffL}) {
            var credential = cell(); credential.writeNativeScalar(0, 4, bits); var outputs = outputs(true); var failure = cell(8);
            var marker = directory.resolve("credential-" + group + "-" + bits);
            var result = create(vector("/bin/sh", "-c", "touch '" + marker + "'"), nil(), nil(), new long[]{-1, -1, -1}, outputs, failure, group ? credential : nil(), group ? nil() : credential);
            assertEquals(-1L, result); assertEquals(95L, state.getStdio().errno()); assertEquals(ProcessFailureStage.ARGUMENTS.getOperation(), text(failure.readAddressElementIndex(0)));
            boolean unchanged = true; for (var output : outputs) unchanged &= integer(output) == 991L; assertTrue(unchanged); assertFalse(Files.exists(marker));
        }
    }); }
}
