// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import thc.Language;
import thc.NativeIO;
import java.util.*;
import java.util.function.Consumer;
import com.oracle.truffle.api.TruffleLanguage;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NativeEventDescriptorsTest {
    @Test @SuppressWarnings("unchecked")
    void unixOwnedPipeAndDupToExecuteThroughBothBackends() {
        for (var backend : List.of("ast", "bytecode")) nativeContext(stdio -> {
            var module = OriginalStdioFixtures.module(List.of("unlink", "dup2"), call -> {
                var descriptor = (Map<String, Object>) ((Map<?, ?>) call.get(6)).get("foreignCall");
                var target = (Map<String, Object>) descriptor.get("target");
                // unlink has exactly pipe's Addr#, State# -> (# State#, Int32# #) ABI.
                if (target.get("symbol").equals("unlink")) target.put("symbol", "pipe");
                target.put("unit", "unix-2.8.8.0-inplace");
            });
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
            ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
            var descriptors = ManagedAddress.fromByteArray(new byte[8]);
            assertEquals(0, callScalarTestTarget(program.entryTarget("unlink"), new Object[]{0L, descriptors, Unit.INSTANCE}));
            int reader = (int) integer(descriptors, 0, 4), writer = (int) integer(descriptors, 4, 4);
            try {
                assertEquals(71, callScalarTestTarget(program.entryTarget("dup2"), new Object[]{0L, writer, 71, Unit.INSTANCE}));
                assertEquals(0L, stdio.close(writer));
                assertEquals(1L, stdio.write(71, ManagedAddress.fromByteArray(new byte[]{42}), 1));
                var output = ManagedAddress.fromByteArray(new byte[1]);
                assertEquals(1L, stdio.read(reader, output, 1));
                assertEquals(42L, output.readWord8(0));
                assertEquals(-1, callScalarTestTarget(program.entryTarget("dup2"), new Object[]{0L, -1, 71, Unit.INSTANCE}));
                assertEquals(9L, stdio.errno());
            } finally { stdio.close(71); stdio.close(writer); stdio.close(reader); }
            var handoff = language.getHandoffState().get();
            assertEquals(0, handoff.getArguments().getDepth());
            assertEquals(0, handoff.getResults().getDepth());
        });
    }
    private void nativeContext(Consumer<ManagedStdio> action) {
        assumeTrue(NativeFileProvider.supportedHost());
        try (var context = NativeIO.createContext(Set.of())) {
            context.enter(); try { action.accept(Language.currentState(null).getStdio()); } finally { context.leave(); }
        }
    }
    private long integer(ManagedAddress address, int offset, int bytes) {
        long value = 0; for (int i = 0; i < bytes; i++) value |= address.readWord8(offset + i) << (i * 8); return value;
    }
    @Test @EnabledOnOs(OS.LINUX) void actualEventfdCounterReadWriteAndNativeErrors() { nativeContext(stdio -> {
        assertThrows(RuntimeFault.class, () -> stdio.eventfd(1L << 32, 0));
        long flags = stdio.flagConstant(OriginalStdioOp.O_NONBLOCK), fd = stdio.eventfd(3, flags);
        assertEquals(3L, fd, "Only the context namespace is exposed");
        var output = ManagedAddress.fromByteArray(new byte[8]);
        try {
            assertEquals(0L, stdio.eventfdWrite(fd, 5)); assertEquals(8L, stdio.read(fd, output, 8));
            assertEquals(8L, integer(output, 0, 8)); assertEquals(-1L, stdio.read(fd, output, 8));
            assertEquals(11L, stdio.errno()); // Linux EAGAIN.
            assertEquals(-1L, stdio.eventfdWrite(fd, -1));
            assertEquals(22L, stdio.errno()); // UINT64_MAX is not an eventfd counter increment.
        } finally { assertEquals(0L, stdio.close(fd)); }
        assertEquals(-1L, stdio.eventfdWrite(fd, 1)); assertEquals(9L, stdio.errno());
        assertEquals(-1L, stdio.eventfd(0, -1)); assertEquals(22L, stdio.errno());
    }); }
    @Test void malformedOutputAndAbsentAuthorityDoNotAcquireDescriptors() {
        nativeContext(stdio -> {
            assertThrows(RuntimeFault.class, () -> stdio.pipe(ManagedAddress.fromByteArray(new byte[7])));
            var descriptors = ManagedAddress.fromByteArray(new byte[8]);
            assertEquals(0L, stdio.pipe(descriptors));
            assertEquals(3L, integer(descriptors, 0, 4), "Malformed output must not claim descriptors");
            assertEquals(4L, integer(descriptors, 4, 4));
            assertEquals(0L, stdio.close(integer(descriptors, 0, 4)));
            assertEquals(0L, stdio.close(integer(descriptors, 4, 4)));
        });
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowIO(IOAccess.ALL).build()) {
            context.initialize("thc"); context.enter();
            try {
                var stdio = Language.currentState(null).getStdio();
                assertEquals(-1L, stdio.pipe(ManagedAddress.fromByteArray(new byte[8])));
            } finally { context.leave(); }
        }
    }
    @Test void failedPairReservationReleasesTheFirstClaim() { nativeContext(ignored -> {
        var state = Language.currentState(null); var limited = new ManagedFiles(state.getEnv(), state.getThreads(), 4);
        limited.installNative(Objects.requireNonNull(state.getNativeFiles()), Set.of());
        try {
            assertEquals(-1L, limited.pipe(ManagedAddress.fromByteArray(new byte[8])));
            assertEquals(10L, limited.errorKind()); long fd = limited.duplicate(1);
            assertEquals(3L, fd); assertEquals(0L, limited.close(fd, ForeignSafety.UNSAFE));
        } finally { limited.dispose(); }
    }); }
    @Test void integerFcntlFormsUseNativeCommandsAndErrors() { nativeContext(stdio -> {
        var descriptors = ManagedAddress.fromByteArray(new byte[8]); assertEquals(0L, stdio.pipe(descriptors));
        long reader = integer(descriptors, 0, 4), writer = integer(descriptors, 4, 4);
        try {
            // F_GETFD=1/F_SETFD=2 on the selected POSIX hosts; libc ignores GET's third argument.
            assertEquals(0L, stdio.fcntl(reader, 2, 0, true));
            stdio.setErrno(55);
            assertEquals(0L, stdio.fcntl(reader, 1, 0, false));
            assertEquals(55L, stdio.errno(), "Successful fcntl preserves guest errno");
            assertEquals(0L, stdio.fcntl(reader, 2, 1, true));
            assertEquals(1L, stdio.fcntl(reader, 1, -123, true));
            assertEquals(1L, stdio.fcntl(reader, 1, 0, false));
            assertEquals(-1L, stdio.fcntl(reader, -7, 0, false));
            assertEquals(22L, stdio.errno(), "Unknown command reaches the kernel's EINVAL");
            assertEquals(-1L, stdio.fcntl(reader, -7, 17, true));
            assertEquals(22L, stdio.errno());
        } finally { stdio.close(reader); stdio.close(writer); }
        assertEquals(-1L, stdio.fcntl(reader, 1, 0, false));
        assertEquals(9L, stdio.errno());
    }); }
    @Test void fcntlDuplicatesOwnLogicalNumbersFlagsAndLifetime() { nativeContext(stdio -> {
        var descriptors = ManagedAddress.fromByteArray(new byte[8]); assertEquals(0L, stdio.pipe(descriptors));
        long reader = integer(descriptors, 0, 4), writer = integer(descriptors, 4, 4);
        try {
            assertEquals(0L, stdio.fcntl(writer, 2, 1, true));
            assertEquals(70L, stdio.fcntl(writer, 0, 70, true), "F_DUPFD lower bound is a guest number");
            assertEquals(71L, stdio.fcntl(writer, NativeDirectoryApi.duplicateCommand(), 70, true), "F_DUPFD_CLOEXEC chooses the next guest number");
            assertEquals(0L, stdio.fcntl(70, 1, 0, false));
            assertEquals(1L, stdio.fcntl(71, 1, 0, false));
            assertEquals(1L, stdio.fcntl(writer, 1, 0, false), "Descriptor flags are independent");
            assertEquals(-1L, stdio.fcntl(writer, 0, -1, true));
            assertEquals(22L, stdio.errno());
            long distant = 1L << 30;
            assertEquals(distant, stdio.fcntl(writer, 0, distant, true), "Guest minimum must not constrain the private host fd");
            assertEquals(0L, stdio.close(distant));
            assertEquals(0L, stdio.close(71)); assertEquals(0L, stdio.close(writer));
            assertEquals(1L, stdio.write(70, ManagedAddress.fromByteArray(new byte[]{42}), 1));
            var output = ManagedAddress.fromByteArray(new byte[1]);
            assertEquals(1L, stdio.read(reader, output, 1)); assertEquals(42L, output.readWord8(0));
            assertEquals(0L, stdio.close(70)); assertEquals(0L, stdio.read(reader, output, 1));
            assertEquals(-1L, stdio.fcntl(70, 1, 0, false)); assertEquals(9L, stdio.errno());
        } finally { stdio.close(70); stdio.close(71); stdio.close(writer); stdio.close(reader); }
    }); }

}
