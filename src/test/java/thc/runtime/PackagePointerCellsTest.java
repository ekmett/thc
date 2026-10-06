// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.RootNode;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.Language;
import thc.PackageScalarLink;
import thc.PackageScalarSignature;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Native C reads and writes actual pointer-bearing pinned storage. */
class PackagePointerCellsTest {
    @TempDir Path directory;
    private static final String SOURCE = """
        #include <stddef.h>
        #include <stdio.h>
        #include <string.h>
        #include <zlib.h>
        typedef struct Cell { unsigned char *p; struct Cell *link; void *foreign; unsigned long n; } Cell;
        static unsigned char external[] = { 81, 82, 0 };
        static unsigned long effects;
        unsigned long count(void) { return effects; }
        int advance(Cell *s, Cell *alias, unsigned char *out) {
            if (s != alias || s->link->link != s) return -99;
            ++effects; *out = *s->p++; ++s->n; s->foreign = external;
            return 17;
        }
        void finalize_cell(Cell *s) { ++effects; ++s->n; ++s->p; }
        void count_only(void *p) { (void)p; ++effects; }
        unsigned char *next_pointer(Cell *s) { return s->p; }
        unsigned char *return_interior(Cell *s) { ++effects; return s->p + 1; }
        int initialize(z_stream *s) { return deflateInit(s, Z_DEFAULT_COMPRESSION); }
        int initialize_version(z_stream *s, const char *version) {
            ++effects; return deflateInit_(s, Z_DEFAULT_COMPRESSION, version, sizeof(z_stream));
        }
        int finish(z_stream *s) { return deflate(s, Z_FINISH); }
        void end_stream(z_stream *s) { ++effects; (void)deflateEnd(s); }
        _Static_assert(sizeof(Cell) == 32, "Cell layout");
        _Static_assert(sizeof(z_stream) == 112 && _Alignof(z_stream) == 8, "z_stream layout");
        _Static_assert(offsetof(z_stream, next_in) == 0 && offsetof(z_stream, avail_in) == 8
            && offsetof(z_stream, total_in) == 16 && offsetof(z_stream, next_out) == 24
            && offsetof(z_stream, avail_out) == 32 && offsetof(z_stream, total_out) == 40
            && offsetof(z_stream, msg) == 48 && offsetof(z_stream, state) == 56
            && offsetof(z_stream, zalloc) == 64 && offsetof(z_stream, zfree) == 72
            && offsetof(z_stream, opaque) == 80 && offsetof(z_stream, data_type) == 88
            && offsetof(z_stream, adler) == 96 && offsetof(z_stream, reserved) == 104,
            "z_stream offsets");
        #ifdef NATIVE_ORACLE
        int main(void) {
            unsigned char input[] = "pointer-cell transport", output[256];
            z_stream s = {0};
            if (initialize_version(&s, "bad") != Z_VERSION_ERROR) return 3;
            if (initialize(&s) != Z_OK) return 1;
            s.next_in = input; s.avail_in = sizeof(input) - 1;
            s.next_out = output; s.avail_out = sizeof(output);
            if (finish(&s) != Z_STREAM_END) return 2;
            for (unsigned long i = 0; i < s.total_out; ++i) printf("%02x", output[i]);
            puts(""); end_stream(&s); return 0;
        }
        #endif
        """;

    private String command(List<String> arguments) throws Exception {
        var process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output); return output;
    }
    private record Fixture(PackageScalarLink link, byte[] compressed) {}
    private Fixture fixture() throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux") && System.getProperty("os.arch").equals("amd64"));
        Path source = directory.resolve("cells.c"), artifact = directory.resolve("cells.so"), nativeMain = directory.resolve("native");
        Files.writeString(source, SOURCE);
        String clang = System.getenv().getOrDefault("THC_CLANG", "clang");
        var common = List.of(clang, "--target=x86_64-unknown-linux-gnu", "-O1", source.toString());
        var nativeCommand = new ArrayList<>(common);
        nativeCommand.addAll(List.of("-DNATIVE_ORACLE", "-o", nativeMain.toString(), "-lz")); command(nativeCommand);
        byte[] expected = HexFormat.of().parseHex(command(List.of(nativeMain.toString())).trim());
        var bitcodeCommand = new ArrayList<>(common);
        bitcodeCommand.addAll(List.of("-shared", "-fPIC", "-fembed-bitcode", "-o", artifact.toString(), "-lz")); command(bitcodeCommand);
        byte[] bytes = Files.readAllBytes(artifact);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var abi = List.of(signature("count", "WordRep"), signature("advance", "Int32Rep", "AddrRep", "AddrRep", "AddrRep"),
            signature("next_pointer", "AddrRep", "AddrRep"),
            signature("return_interior", "AddrRep", "AddrRep"), signature("count_only", "void", "AddrRep"),
            signature("finalize_cell", "void", "AddrRep"), signature("initialize", "Int32Rep", "AddrRep"),
            signature("initialize_version", "Int32Rep", "AddrRep", "AddrRep"),
            signature("finish", "Int32Rep", "AddrRep"), signature("end_stream", "void", "AddrRep"));
        return new Fixture(new PackageScalarLink("pointer-cells-test", "x86_64-unknown-linux-gnu", sha, sha, bytes, abi,
            "llvm-embedded-elf", Set.of("finalize_cell", "end_stream", "count_only")), expected);
    }
    private static PackageScalarSignature signature(String name, String result, String... arguments) {
        return new PackageScalarSignature(name, name, List.of(arguments), result);
    }
    private Context context() {
        return Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").build();
    }
    private static final class Entry extends RootNode {
        @Child private PackageScalarAccess access;
        private final String result;
        Entry(PackageScalarLink link, String name) {
            super(TruffleLanguage.LanguageReference.create(Language.class).get(null));
            var signature = link.getAbi().stream().filter(item -> item.getSymbol().equals(name)).findFirst().orElseThrow();
            access = new PackageScalarAccess(new PackageScalarCall(link, signature)); result = signature.getResult();
        }
        @Override public Object execute(VirtualFrame frame) {
            if (result.equals("Int32Rep")) return access.executeInt(frame.getArguments(), Unit.INSTANCE);
            if (result.equals("AddrRep")) return access.executeAddress(frame.getArguments(), Unit.INSTANCE);
            if (result.equals("void")) { access.executeVoid(frame.getArguments(), Unit.INSTANCE); return Unit.INSTANCE; }
            return access.executeLong(frame.getArguments(), Unit.INSTANCE);
        }
    }
    private Object call(Fixture fixture, String name, Object... arguments) {
        return new Entry(fixture.link(), name).getCallTarget().call(arguments);
    }
    private ManagedAllocation cell(ManagedAddress value) {
        var allocation = ManagedAllocation.mutable(32, 8, true);
        allocation.writeAddressByteOffset(0, value);
        allocation.writeAddressByteOffset(8, ManagedAddress.nullAddress());
        allocation.writeAddressByteOffset(16, ManagedAddress.nullAddress());
        return allocation;
    }
    @Test void nativeWritesPreserveCyclesInteriorAliasesAndPersistentForeignPointers() throws Exception {
        var fixture = fixture();
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                Language.currentState().getPackageCbits().link(fixture.link());
                var bytes = ManagedAllocation.mutable(4, 8, true); bytes.writeByte(1, 61); bytes.writeByte(2, 62);
                var input = ManagedAddress.fromGuestByteArray(bytes);
                var first = cell(input.plus(1)); var second = cell(ManagedAddress.nullAddress());
                var a = ManagedAddress.fromGuestByteArray(first); var b = ManagedAddress.fromGuestByteArray(second);
                first.writeAddressByteOffset(8, b); second.writeAddressByteOffset(8, a);
                var output = ManagedAddress.fromGuestByteArray(ManagedAllocation.mutable(1, 8, true));
                assertEquals(17, call(fixture, "advance", a, a.plus(0), output));
                assertEquals(61L, output.readWord8(0));
                assertTrue(first.readAddressByteOffset(0).sameLocation(input.plus(2)));
                assertTrue(((ManagedAddress) call(fixture, "next_pointer", a)).sameLocation(input.plus(2)),
                    "returned transitive pointer preserves its native backing");
                assertSame(b, first.readAddressByteOffset(8), "unchanged cell preserves exact managed address");
                assertSame(a, second.readAddressByteOffset(8));
                assertEquals(81L, first.readAddressByteOffset(16).readWord8(0));
                assertEquals(17, call(fixture, "advance", a, a, output));
                assertEquals(62L, output.readWord8(0));
                assertTrue(first.readAddressByteOffset(0).sameLocation(input.plus(3)));
                assertEquals(2L, first.readByte(24)); assertEquals(2L, call(fixture, "count"));
                assertThrows(RuntimeFault.class, first::exposeSegment, "native projection is not raw exposure");
            } finally { context.leave(); }
        }
    }
    @Test void invalidTransitiveCellRejectsBeforeCAndLeavesAllOwnersUnchanged() throws Exception {
        var fixture = fixture();
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                Language.currentState().getPackageCbits().link(fixture.link());
                var input = ManagedAddress.fromGuestByteArray(ManagedAllocation.mutable(4, 8, true));
                var first = cell(input); var second = cell(ManagedAddress.fromByteArray(new byte[]{1}));
                var a = ManagedAddress.fromGuestByteArray(first); var b = ManagedAddress.fromGuestByteArray(second);
                first.writeAddressByteOffset(8, b); second.writeAddressByteOffset(8, a);
                assertThrows(RuntimeFault.class, () -> call(fixture, "advance", a, a, input));
                assertEquals(0L, call(fixture, "count")); assertSame(input, first.readAddressByteOffset(0));
                assertSame(b, first.readAddressByteOffset(8)); assertEquals(0L, first.readByte(24));
                second.writeAddressByteOffset(0, ManagedAddress.nullAddress());
                assertThrows(RuntimeFault.class, () -> call(fixture, "advance", a, a, ManagedAddress.unownedNumeric(1)));
                assertEquals(0L, first.nativeSegment().get(ValueLayout.JAVA_LONG_UNALIGNED, 0), "bad direct argument cannot partially materialize an earlier graph");
                assertEquals(0L, call(fixture, "count"));
            } finally { context.leave(); }
        }
    }
    @Test void genuineZlibRetainsStateAndUpdatesPointersAcrossCallsThenFinalizesOnce() throws Exception {
        var fixture = fixture();
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); state.getPackageCbits().link(fixture.link());
                var stream = ManagedAllocation.mutable(112, 8, true);
                for (int offset : new int[]{0, 24, 48, 64, 72, 80}) stream.writeAddressByteOffset(offset, ManagedAddress.nullAddress());
                var address = ManagedAddress.fromGuestByteArray(stream);
                assertEquals(0, call(fixture, "initialize", address));
                assertNotSame(ManagedAddress.nullAddress(), stream.readAddressByteOffset(64));
                assertNotSame(ManagedAddress.nullAddress(), stream.readAddressByteOffset(72));
                assertNotSame(ManagedAddress.nullAddress(), stream.readAddressByteOffset(56), "explicit Addr read recovers native-written state without scanning scalar fields");
                byte[] original = "pointer-cell transport".getBytes(StandardCharsets.UTF_8);
                var in = ManagedAllocation.mutable(original.length, 8, true); in.copyBytesIn(original, 0, 0, original.length);
                var out = ManagedAllocation.mutable(256, 8, true);
                var input = ManagedAddress.fromGuestByteArray(in); var output = ManagedAddress.fromGuestByteArray(out);
                stream.writeAddressByteOffset(0, input); stream.writeNativeIntByteOffset(8, 4, original.length, true);
                stream.writeAddressByteOffset(24, output); stream.writeNativeIntByteOffset(32, 4, 256, true);
                assertEquals(1, call(fixture, "finish", address));
                assertArrayEquals(fixture.compressed(), out.copyBytesOut(0, fixture.compressed().length));
                assertTrue(stream.readAddressByteOffset(0).sameLocation(input.plus(original.length)));
                assertTrue(stream.readAddressByteOffset(24).sameLocation(output.plus(fixture.compressed().length)));
                var action = new Object();
                var weak = state.getWeaks().make(new Object(), new Object(), action);
                assertEquals(1L, state.getWeaks().addCFinalizer(state.cbits().finalizerLabel("end_stream"), address, 0, ManagedAddress.nullAddress(), weak, state.cbits()));
                var finalized = state.getWeaks().finalize(weak);
                assertEquals(1L, finalized.getFlag());
                assertSame(action, finalized.getValue(), "flag reports the unforced Haskell action, not the C callback");
                assertEquals(1L, call(fixture, "count"));
                assertSame(ManagedAddress.nullAddress(), stream.readAddressByteOffset(56), "deflateEnd clears native state");
                assertEquals(0L, state.getWeaks().finalize(weak).getFlag());
                assertEquals(1L, call(fixture, "count"), "C finalizer must not replay");
            } finally { context.leave(); }
        }
    }
    @Test void nativeWritebackSurvivesTheOriginalFailureAndReleasesTransitiveMallocBorrow() throws Exception {
        var fixture = fixture();
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); state.getPackageCbits().link(fixture.link());
                var input = state.getNativeAllocations().malloc(4); input.writeWord8(0, 53);
                var first = cell(input); var address = ManagedAddress.fromGuestByteArray(first);
                first.writeAddressByteOffset(8, address);
                var output = ManagedAddress.fromGuestByteArray(ManagedAllocation.mutable(1, 8, true));
                var signature = fixture.link().getAbi().stream().filter(item -> item.getSymbol().equals("advance")).findFirst().orElseThrow();
                var entry = state.getPackageCbits().resolve(fixture.link(), signature);
                var original = new IllegalStateException("original after native write");
                assertSame(original, assertThrows(IllegalStateException.class, () ->
                    PackagePointerCells.invoke(entry, List.of(address, output), projection -> () -> {
                        assertThrows(RuntimeFault.class, () -> state.getNativeAllocations().free(input), "transitive pointer owner is borrowed");
                        try {
                            assertEquals(17, InteropLibrary.getUncached().execute(entry.getReceiver(),
                                new PackageNativePointer(projection.address(address), null),
                                new PackageNativePointer(projection.address(address), null),
                                new PackageNativePointer(projection.address(output), null)));
                        } catch (Exception failure) { throw new AssertionError(failure); }
                        throw original;
                    }, (projection, result) -> result)));
                assertEquals(53L, output.readWord8(0));
                assertTrue(first.readAddressByteOffset(0).sameLocation(input.plus(1)));
                assertEquals(1L, call(fixture, "count"));
                state.getNativeAllocations().free(input);
                assertThrows(RuntimeFault.class, () -> first.readAddressByteOffset(0).readWord8(0));
            } finally { context.leave(); }
        }
    }
    @Test void failedArgumentPreparationDoesNotHoldAllocationMonitorsOrMaterializeCells() throws Exception {
        var fixture = fixture();
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); state.getPackageCbits().link(fixture.link());
                var input = state.getNativeAllocations().malloc(4);
                var storage = cell(input); var address = ManagedAddress.fromGuestByteArray(storage);
                var signature = fixture.link().getAbi().stream().filter(item -> item.getSymbol().equals("advance")).findFirst().orElseThrow();
                var entry = state.getPackageCbits().resolve(fixture.link(), signature);
                var original = new IllegalStateException("argument preparation failed");
                boolean[] held = {false};
                assertSame(original, assertThrows(IllegalStateException.class, () ->
                    PackagePointerCells.invoke(entry, List.of(address), projection -> {
                        held[0] = Thread.holdsLock(storage);
                        assertThrows(RuntimeFault.class, () -> state.getNativeAllocations().free(input));
                        throw original;
                    }, (projection, result) -> result)));
                assertFalse(held[0], "argument views must be prepared before allocation monitors");
                assertEquals(0L, storage.nativeSegment().get(ValueLayout.JAVA_LONG_UNALIGNED, 0));
                assertSame(input, storage.readAddressByteOffset(0));
                assertEquals(0L, call(fixture, "count"));
                state.getNativeAllocations().free(input);
            } finally { context.leave(); }
        }
    }
    @Test void resultRecoveryRunsAfterWritebackBeforeBorrowReleaseAndKeepsOriginalFailure() throws Exception {
        var fixture = fixture();
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); state.getPackageCbits().link(fixture.link());
                var input = state.getNativeAllocations().malloc(4);
                var storage = cell(input); var address = ManagedAddress.fromGuestByteArray(storage);
                var signature = fixture.link().getAbi().stream().filter(item -> item.getSymbol().equals("finalize_cell")).findFirst().orElseThrow();
                var entry = state.getPackageCbits().resolve(fixture.link(), signature);
                var original = new IllegalStateException("result recovery failed");
                assertSame(original, assertThrows(IllegalStateException.class, () ->
                    PackagePointerCells.invoke(entry, List.of(address), projection -> () -> {
                        try { return InteropLibrary.getUncached().execute(entry.getReceiver(),
                            new PackageNativePointer(projection.address(address), null)); }
                        catch (Exception failure) { throw new AssertionError(failure); }
                    }, (projection, result) -> {
                        assertFalse(Thread.holdsLock(storage), "result recovery can acquire native registries");
                        assertTrue(storage.readAddressByteOffset(0).sameLocation(input.plus(1)));
                        assertThrows(RuntimeFault.class, () -> state.getNativeAllocations().free(input), "result recovery retains transitive borrows");
                        throw original;
                    })));
                assertEquals(1L, call(fixture, "count"));
                state.getNativeAllocations().free(input);
            } finally { context.leave(); }
        }
    }
    @Test void firstInstalledFinalizerUsesTheOriginalPointerGraphOnce() throws Exception {
        var fixture = fixture();
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); state.getPackageCbits().link(fixture.link());
                var callback = state.cbits().finalizerLabel("finalize_cell").finalizerFunction();
                var input = ManagedAddress.fromGuestByteArray(ManagedAllocation.mutable(4, 8, true));
                var storage = cell(input); var address = ManagedAddress.fromGuestByteArray(storage);
                class FinalizerEntry extends RootNode {
                    int compiledEntries;
                    FinalizerEntry() { super(TruffleLanguage.LanguageReference.create(Language.class).get(null)); }
                    @Override public Object execute(VirtualFrame frame) {
                        if (CompilerDirectives.inCompiledCode()) compiledEntries++;
                        callback.invoke((ManagedAddress) frame.getArguments()[0]); return Unit.INSTANCE;
                    }
                }
                var root = new FinalizerEntry(); var target = root.getCallTarget();
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                var runtime = Truffle.getRuntime();
                runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
                target.call(address);
                assertEquals(1, root.compiledEntries); assertSame(target, root.getCallTarget());
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                assertEquals(1L, storage.readByte(24)); assertTrue(storage.readAddressByteOffset(0).sameLocation(input.plus(1)));
                assertEquals(1L, call(fixture, "count"));
            } finally { context.leave(); }
        }
    }
    @Test void projectedStructProvenanceRejectsForeignAndClosedContexts() throws Exception {
        var fixture = fixture();
        var storage = cell(ManagedAddress.fromGuestByteArray(ManagedAllocation.mutable(4, 8, true)));
        var address = ManagedAddress.fromGuestByteArray(storage);
        try (var first = context(); var second = context()) {
            first.initialize("thc"); first.enter();
            try {
                Language.currentState().getPackageCbits().link(fixture.link());
                call(fixture, "finalize_cell", address);
                assertEquals(1L, call(fixture, "count"));
            } finally { first.leave(); }
            second.initialize("thc"); second.enter();
            try {
                Language.currentState().getPackageCbits().link(fixture.link());
                assertThrows(RuntimeFault.class, () -> storage.readAddressByteOffset(0));
                assertThrows(RuntimeFault.class, () -> call(fixture, "finalize_cell", address));
                assertEquals(0L, call(fixture, "count"));
            } finally { second.leave(); }
            first.enter();
            try {
                assertNotSame(ManagedAddress.nullAddress(), storage.readAddressByteOffset(0));
                call(fixture, "finalize_cell", address);
                assertEquals(2L, call(fixture, "count"));
            } finally { first.leave(); }
            first.close();
            second.enter();
            try {
                assertThrows(RuntimeFault.class, () -> call(fixture, "finalize_cell", address));
                assertEquals(0L, call(fixture, "count"));
            } finally { second.leave(); }
        }
    }
    @Test void directStaticImagePreparationNeverTakesRegistryUnderAllocationMonitor() throws Exception {
        var fixture = fixture();
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); state.getPackageCbits().link(fixture.link());
                var storage = ManagedAllocation.mutable(112, 8, true);
                for (int offset : new int[]{0, 24, 48, 64, 72, 80}) storage.writeAddressByteOffset(offset, ManagedAddress.nullAddress());
                var address = ManagedAddress.fromGuestByteArray(storage);
                var version = ManagedAddress.fromStaticBytes(new byte[]{'b', 'a', 'd', 0});
                var registry = state.getNativeAddresses();
                var management = java.lang.management.ManagementFactory.getThreadMXBean();
                assertTrue(management.isObjectMonitorUsageSupported());
                long caller = Thread.currentThread().threadId();
                var ready = new java.util.concurrent.CountDownLatch(1);
                var held = new java.util.concurrent.CompletableFuture<Boolean>();
                // A bounded observer holds only the registry, never an allocation. It
                // releases it after inspecting the waiting caller, so RED cannot deadlock.
                var observer = Thread.ofPlatform().daemon(true).start(() -> {
                    synchronized (registry) {
                        ready.countDown();
                        try {
                            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                            while (System.nanoTime() < deadline) {
                                var info = management.getThreadInfo(new long[]{caller}, true, false)[0];
                                var waiting = info.getLockInfo();
                                if (info.getThreadState() == Thread.State.BLOCKED && waiting != null
                                        && waiting.getIdentityHashCode() == System.identityHashCode(registry)) {
                                    boolean ownerHeld = false;
                                    for (var monitor : info.getLockedMonitors())
                                        if (monitor.getIdentityHashCode() == System.identityHashCode(storage)
                                                && monitor.getClassName().equals(ManagedAllocation.class.getName())) ownerHeld = true;
                                    held.complete(ownerHeld); return;
                                }
                                Thread.sleep(1);
                            }
                            held.completeExceptionally(new AssertionError("Caller did not reach native-image registry"));
                        } catch (Throwable failure) { held.completeExceptionally(failure); }
                    }
                });
                assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
                try { assertEquals(-6, call(fixture, "initialize_version", address, version)); }
                finally { observer.join(6000); }
                assertFalse(observer.isAlive());
                assertFalse(held.get(1, java.util.concurrent.TimeUnit.SECONDS), "native-image registry must precede allocation monitors");
                assertEquals(1L, call(fixture, "count"));
            } finally { context.leave(); }
        }
    }
    @Test void concurrentFreeCannotStripInteriorPointerOwnershipOnReturn() throws Exception {
        concurrentFreeCannotStripInteriorPointerOwnership(false, false);
    }

    @Test void overlappingCallsCannotReplayThePreNativePointerSnapshot() throws Exception {
        overlappingPublication(0);
    }
    @Test void pointerReadsWaitForNativePublication() throws Exception {
        overlappingPublication(1);
    }
    @Test void pointerCopiesWaitForNativePublication() throws Exception {
        overlappingPublication(2);
    }
    @Test void pointerResizeWaitsForNativePublication() throws Exception {
        overlappingPublication(3);
    }
    private void overlappingPublication(int observation) throws Exception {
        var fixture = fixture();
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); state.getPackageCbits().link(fixture.link());
                var input = ManagedAddress.fromGuestByteArray(ManagedAllocation.mutable(8, 8, true));
                var storage = cell(input); var address = ManagedAddress.fromGuestByteArray(storage);
                var signature = fixture.link().getAbi().stream()
                    .filter(item -> item.getSymbol().equals("finalize_cell")).findFirst().orElseThrow();
                var entry = state.getPackageCbits().resolve(fixture.link(), signature);
                var registry = state.getNativeAddresses();
                var management = java.lang.management.ManagementFactory.getThreadMXBean();
                assertTrue(management.isObjectMonitorUsageSupported());
                var firstDone = new java.util.concurrent.CompletableFuture<Void>();
                var secondDone = new java.util.concurrent.CompletableFuture<Void>();
                var nativeCalls = new java.util.concurrent.atomic.AtomicInteger();
                var observed = new java.util.concurrent.atomic.AtomicReference<ManagedAddress>();
                java.util.function.Consumer<java.util.concurrent.CompletableFuture<Void>> invoke = done -> {
                    context.enter();
                    try {
                        PackagePointerCells.invoke(entry, List.of(address), projection -> () -> {
                            try {
                                Object result = InteropLibrary.getUncached().execute(entry.getReceiver(),
                                    new PackageNativePointer(projection.address(address), null));
                                nativeCalls.incrementAndGet(); return result;
                            } catch (Exception failure) { throw new AssertionError(failure); }
                        }, (projection, result) -> result);
                        done.complete(null);
                    } catch (Throwable failure) { done.completeExceptionally(failure);
                    } finally { context.leave(); }
                };
                Thread first = null, second = null;
                try {
                    synchronized (registry) {
                        first = Thread.ofPlatform().daemon(true).start(() -> invoke.accept(firstDone));
                        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                        boolean firstAtPublication = false;
                        while (System.nanoTime() < deadline) {
                            var info = management.getThreadInfo(new long[]{first.threadId()}, true, false)[0];
                            var lock = info == null ? null : info.getLockInfo();
                            if (nativeCalls.get() == 1 && info != null && info.getThreadState() == Thread.State.BLOCKED
                                    && lock != null && lock.getIdentityHashCode() == System.identityHashCode(registry)) {
                                for (var monitor : info.getLockedMonitors())
                                    assertNotEquals(System.identityHashCode(storage), monitor.getIdentityHashCode(),
                                        "registry recovery must remain outside the allocation monitor");
                                firstAtPublication = true; break;
                            }
                            if (firstDone.isDone()) firstDone.get(1, java.util.concurrent.TimeUnit.SECONDS);
                            Thread.sleep(1);
                        }
                        assertTrue(firstAtPublication, "first C call must finish before the second starts");
                        second = Thread.ofPlatform().daemon(true).start(() -> {
                            if (observation == 0) { invoke.accept(secondDone); return; }
                            context.enter();
                            try {
                                ManagedAllocation source = storage;
                                if (observation == 2) {
                                    source = ManagedAllocation.mutable(storage.getSize(), 8, true);
                                    source.copyFrom(storage, 0, 0, storage.getSize());
                                } else if (observation == 3) source = storage.resized(storage.getSize() + 8);
                                observed.set(source.readAddressByteOffset(0));
                                secondDone.complete(null);
                            } catch (Throwable failure) { secondDone.completeExceptionally(failure);
                            } finally { context.leave(); }
                        });
                        deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                        boolean secondReachedProjection = false;
                        while (System.nanoTime() < deadline) {
                            var info = management.getThreadInfo(new long[]{second.threadId()}, true, false)[0];
                            if (info != null && (info.getThreadState() == Thread.State.BLOCKED
                                    || info.getThreadState() == Thread.State.WAITING
                                    || info.getThreadState() == Thread.State.TIMED_WAITING)) {
                                for (var frame : info.getStackTrace())
                                    if (frame.getClassName().equals(PackagePointerCells.class.getName())
                                            || frame.getClassName().equals(ManagedAllocation.class.getName()))
                                        secondReachedProjection = true;
                                if (secondReachedProjection) break;
                            }
                            if (secondDone.isDone()) {
                                secondDone.get(1, java.util.concurrent.TimeUnit.SECONDS);
                                secondReachedProjection = true; break;
                            }
                            Thread.sleep(1);
                        }
                        assertTrue(secondReachedProjection, "second call must overlap the pending publication");
                    }
                    firstDone.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    secondDone.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    int expectedCalls = observation == 0 ? 2 : 1;
                    assertEquals(expectedCalls, nativeCalls.get());
                    assertEquals((long) expectedCalls, call(fixture, "count"), "real C effects must execute exactly once");
                    assertTrue(storage.readAddressByteOffset(0).sameLocation(input.plus(expectedCalls)),
                        "second projection must preserve the first native pointer increment");
                    if (observation != 0) assertTrue(observed.get().sameLocation(input.plus(1)),
                        "managed pointer observation must see the completed native update");
                } finally {
                    if (first != null) { first.join(6000); assertFalse(first.isAlive()); }
                    if (second != null) { second.join(6000); assertFalse(second.isAlive()); }
                }
            } finally { context.leave(); }
        }
    }
    @Test void concurrentFreeCannotStripInteriorPointerOwnershipOnFailure() throws Exception {
        concurrentFreeCannotStripInteriorPointerOwnership(true, false);
    }
    @Test void concurrentFreeCannotStripReturnedInteriorPointerOwnership() throws Exception {
        concurrentFreeCannotStripInteriorPointerOwnership(false, true);
    }
    private void concurrentFreeCannotStripInteriorPointerOwnership(boolean throwsFailure, boolean returnsPointer) throws Exception {
        var fixture = fixture();
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); state.getPackageCbits().link(fixture.link());
                var registry = state.getNativeAllocations();
                var input = registry.malloc(4);
                var owner = input.nativeAllocation();
                var storage = cell(input.plus(1)); var address = ManagedAddress.fromGuestByteArray(storage);
                var field = owner.getClass().getDeclaredField("lifetime"); field.setAccessible(true);
                var lifetime = (java.util.concurrent.locks.ReentrantReadWriteLock) field.get(owner);
                var startFree = new java.util.concurrent.CountDownLatch(1);
                var freeingStarted = new java.util.concurrent.CountDownLatch(1);
                var freeingThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
                var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
                try {
                    var freeing = executor.submit(() -> {
                        try { assertTrue(startFree.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                        context.enter();
                        try {
                            freeingThread.set(Thread.currentThread()); freeingStarted.countDown();
                            registry.free(input);
                        } finally { context.leave(); }
                    });
                    String name = returnsPointer ? "return_interior" : "finalize_cell";
                    var signature = fixture.link().getAbi().stream().filter(item -> item.getSymbol().equals(name)).findFirst().orElseThrow();
                    var entry = state.getPackageCbits().resolve(fixture.link(), signature);
                    var returnRoot = new Entry(fixture.link(), name); returnRoot.getCallTarget();
                    var normalize = PackageScalarAccess.class.getDeclaredMethod("normalizeResult", PackageScalarFunction.class,
                        Object.class, List.class, int[].class, Object[].class, PackagePointerCells.class);
                    normalize.setAccessible(true);
                    var returned = new java.util.concurrent.atomic.AtomicReference<ManagedAddress>();
                    var original = new IllegalStateException("failure after native pointer update");
                    Runnable invoke = () -> PackagePointerCells.invoke(entry, List.of(address), projection -> () -> {
                        Object result;
                        try {
                            result = InteropLibrary.getUncached().execute(entry.getReceiver(), new PackageNativePointer(projection.address(address), null));
                            startFree.countDown();
                            assertTrue(freeingStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
                            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                            while (!lifetime.hasQueuedThread(freeingThread.get())) {
                                if (System.nanoTime() >= deadline) throw new AssertionError("Free did not queue behind native call borrow");
                                java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1));
                            }
                            assertFalse(freeing.isDone());
                        } catch (Exception failure) { throw new AssertionError(failure); }
                        if (throwsFailure) throw original;
                        return result;
                    }, (projection, result) -> {
                        if (returnsPointer) {
                            try { returned.set((ManagedAddress) normalize.invoke(returnRoot.access, entry, result,
                                List.of(address), new int[]{0}, new Object[]{new PackageNativePointer(address.cbitsOwner().nativeSegment().address(), null)}, projection)); }
                            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                        }
                        return result;
                    });
                    if (throwsFailure) assertSame(original, assertThrows(IllegalStateException.class, invoke::run));
                    else invoke.run();
                    freeing.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    var updated = storage.readAddressByteOffset(0);
                    // Check ownership before dereferencing: the RED path must not actually
                    // read an unowned pointer after the native allocation has been freed.
                    assertSame(owner, updated.nativeAllocation(), "interior pointer retains its borrowed allocation even while free is pending");
                    assertThrows(RuntimeFault.class, () -> updated.readWord8(0));
                    if (returnsPointer) {
                        assertSame(owner, returned.get().nativeAllocation(), "returned interior pointer retains the transitive borrowed owner");
                        assertThrows(RuntimeFault.class, () -> returned.get().readWord8(0));
                    }
                    assertEquals(0, registry.liveCount());
                    assertEquals(1L, call(fixture, "count"));
                } finally {
                    startFree.countDown(); executor.shutdownNow();
                    assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
                }
            } finally { context.leave(); }
        }
    }
    @Test void finalizerProjectionChecksDirectOffsetsBeforeNativeEffectsAndAllowsOnePast() throws Exception {
        var fixture = fixture();
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); state.getPackageCbits().link(fixture.link());
                var storage = cell(ManagedAddress.fromGuestByteArray(ManagedAllocation.mutable(1, 8, true)));
                var address = ManagedAddress.fromGuestByteArray(storage);
                var callback = state.cbits().finalizerLabel("count_only").finalizerFunction();
                // This genuine C callback never dereferences p, keeping the RED path safe.
                assertThrows(RuntimeFault.class, () -> callback.invoke(address.plus(-1)));
                assertThrows(RuntimeFault.class, () -> callback.invoke(address.plus(storage.getSize() + 1)));
                assertEquals(0L, call(fixture, "count"));
                callback.invoke(address.plus(storage.getSize()));
                callback.invoke(address);
                assertEquals(2L, call(fixture, "count"));
            } finally { context.leave(); }
        }
    }
}
