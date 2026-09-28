// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.NativeIO;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NativeEventDescriptorsTest {
    private void nativeContext(Consumer<ManagedStdio> action) {
        assumeTrue(NativeIO.supportedHost());
        try (var context = NativeIO.createContext(Set.of())) {
            context.enter(); try { action.accept(Language.currentState(null).getStdio()); } finally { context.leave(); }
        }
    }
    private long integer(ManagedAddress address, int offset, int bytes) {
        long value = 0; for (int i = 0; i < bytes; i++) value |= address.readWord8(offset + i) << (i * 8); return value;
    }
    @Test void actualEventfdCounterReadWriteAndNativeErrors() { nativeContext(stdio -> {
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
    @Test void actualPipeTransfersAndCloseOnExecUseTheSharedRegistry() { nativeContext(stdio -> {
        var descriptors = ManagedAddress.fromByteArray(new byte[8]); assertEquals(0L, stdio.pipe(descriptors));
        long reader = integer(descriptors, 0, 4), writer = integer(descriptors, 4, 4);
        assertEquals(3L, reader); assertEquals(4L, writer);
        var payload = ManagedAddress.fromByteArray(new byte[] {3, 1, 4, 1, 5});
        var output = ManagedAddress.fromByteArray(new byte[5]);
        try {
            for (long fd : new long[] {reader, writer}) assertEquals(0L, stdio.fcntl(fd,
                stdio.flagConstant(OriginalStdioOp.F_SETFD), stdio.flagConstant(OriginalStdioOp.FD_CLOEXEC), true));
            assertEquals(5L, stdio.write(writer, payload, 5)); assertEquals(1L, stdio.ready(reader, 0, 0, 0));
            assertEquals(5L, stdio.read(reader, output, 5));
            var expected = new ArrayList<Long>(); var actual = new ArrayList<Long>();
            for (long i = 0; i <= 4; i++) { expected.add(payload.readWord8(i)); actual.add(output.readWord8(i)); }
            assertEquals(expected, actual); assertEquals(0L, stdio.close(writer));
            assertEquals(0L, stdio.read(reader, output, 5), "Closed writer produces real pipe EOF");
        } finally { stdio.close(writer); stdio.close(reader); }
    }); }
    @Test void malformedOutputAndAbsentAuthorityDoNotAcquireDescriptors() {
        nativeContext(stdio -> {
            assertThrows(RuntimeFault.class, () -> stdio.pipe(ManagedAddress.fromByteArray(new byte[7])));
            assertThrows(RuntimeFault.class, () -> stdio.eventfd(1L << 32, 0));
            long fd = stdio.eventfd(0, 0); assertEquals(3L, fd); assertEquals(0L, stdio.close(fd));
        });
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowIO(IOAccess.ALL).build()) {
            context.initialize("thc"); context.enter();
            try {
                var stdio = Language.currentState(null).getStdio(); assertEquals(-1L, stdio.eventfd(0, 0));
                assertEquals(-1L, stdio.pipe(ManagedAddress.fromByteArray(new byte[8])));
            } finally { context.leave(); }
        }
    }
    @Test void failedPairReservationReleasesTheFirstClaim() { nativeContext(ignored -> {
        var state = Language.currentState(null); var limited = new ManagedFiles(state.getEnv(), state.getThreads(), 4);
        limited.installNative(Objects.requireNonNull(state.getNativeFiles()), Set.of());
        try {
            assertEquals(-1L, limited.pipe(ManagedAddress.fromByteArray(new byte[8])));
            assertEquals(10L, limited.errorKind()); long fd = limited.eventfd(0, 0);
            assertEquals(3L, fd); assertEquals(0L, limited.close(fd, ForeignSafety.UNSAFE));
        } finally { limited.dispose(); }
    }); }
    @Test void eventfdAliasesShareTheRealCounterUntilLastClose() { nativeContext(stdio -> {
        long fd = stdio.eventfd(2, stdio.flagConstant(OriginalStdioOp.O_NONBLOCK)), alias = stdio.duplicate(fd);
        assertEquals(4L, alias); var output = ManagedAddress.fromByteArray(new byte[8]);
        try {
            assertEquals(0L, stdio.close(fd)); assertEquals(0L, stdio.eventfdWrite(alias, 7));
            assertEquals(8L, stdio.read(alias, output, 8)); assertEquals(9L, integer(output, 0, 8));
            long reused = stdio.eventfd(19, stdio.flagConstant(OriginalStdioOp.O_NONBLOCK));
            try {
                assertEquals(fd, reused);
                assertEquals(-1L, stdio.read(alias, output, 8), "Reused logical fd is an independent counter");
                assertEquals(11L, stdio.errno()); assertEquals(8L, stdio.read(reused, output, 8));
                assertEquals(19L, integer(output, 0, 8));
            } finally { stdio.close(reused); }
        } finally { stdio.close(alias); }
        assertEquals(-1L, stdio.eventfdWrite(alias, 1)); assertEquals(9L, stdio.errno());
    }); }
}
