// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.Language;
import thc.ForeignExceptionFixtureSupport;
import thc.PackageScalarLink;
import thc.PackageScalarSignature;
import static org.junit.jupiter.api.Assertions.*;

/** Actual C execution controls, separate from the original-package Hashable oracle. */
public class PackageNativeForeignTest {
    @TempDir Path directory;
    private PackageScalarLink library() throws Exception { return library(false, "unsafe"); }
    private PackageScalarLink library(boolean pointerVariants, String safety) throws Exception {
        var source = directory.resolve("native.c"); var bitcode = directory.resolve("native.bc");
        Files.writeString(source, """
            #include <stdint.h>
            #include <stddef.h>
            #include <errno.h>
            #include <stdlib.h>
            unsigned char *allocate_bytes(void) {
              unsigned char *p = malloc(16);
              if (p) { p[0] = 42; p[15] = 73; }
              return p;
            }
            void release_bytes(void *p) { free(p); }
            void *return_pointer(void *p) { return p; }
            int change_errno(int value) { errno = value; return -1; }
            int observe_errno(void) { return errno; }
            int pointer_errno(unsigned char *p, int value) { p[0] = 197; errno = value; return -1; }
            uint64_t sum_bytes(const unsigned char *p, uint64_t n) {
              uint64_t result = 0;
              for (uint64_t i = 0; i < n; ++i) result += p[i];
              return result;
            }
            uint64_t sum_bytes_address(const unsigned char *p, uint64_t n) {
              return sum_bytes(p, n);
            }
            void update(unsigned char *state, const unsigned char *p, uint64_t n) {
              for (uint64_t i = 0; i < n; ++i) state[0] += p[i];
            }
            uint32_t alias(unsigned char *a, unsigned char *b) {
              a[1] = 197;
              return b[0];
            }
            float identity_float(float value) { return value; }
            double identity_double(double value) { return value; }
            uint32_t complement32(uint32_t value) { return ~value; }
            uint8_t complement8(uint8_t value) { return (uint8_t) ~value; }
            static uint64_t scalar_state;
            void advance(uint64_t value) { scalar_state += value; }
            uint64_t current(void) { return scalar_state; }
            const uint64_t fixed_state = 29;
            uint64_t read_fixed(void) { return *(volatile const uint64_t *)&fixed_state; }
            static unsigned char choices[] = {17, 41, 59};
            static unsigned char *selected = choices;
            void select_pointer(uint64_t index) { selected = choices + index; }
            uint64_t read_selected(void) { return *selected; }
            uint32_t same_selected(uint64_t index) { return selected == choices + index; }
            static _Thread_local uint64_t local_state;
            void advance_tls(uint64_t value) { local_state += value; }
            uint64_t current_tls(void) { return local_state; }
            uint64_t state_bits(void) { return ((uintptr_t)&scalar_state) ^ UINT64_C(0x5a5a123456787654); }
            unsigned char *null_pointer(void) { return NULL; }
            uint32_t equal(const unsigned char *a, const unsigned char *b) { return a == b; }
            uint32_t next_equal(const unsigned char *a, const unsigned char *b) { return a + 1 == b; }
            int64_t distance(const unsigned char *a, const unsigned char *b) { return b - a; }
            uint32_t read_before(const unsigned char *p) { return p[-1]; }
            uint64_t native_bits(const unsigned char *p) {
              /* XOR forces numerical projection, unlike a lazy ptrtoint. */
              return ((uintptr_t) p) ^ UINT64_C(0x5a5a123456787654);
            }
            uint32_t mixed_alias(unsigned char *a, const unsigned char *b) {
              if (a != b) return 0;
              a[0] = 91;
              return b[0];
            }
            #ifdef NATIVE_ORACLE
            #include <stdio.h>
            int main(void) {
              advance(3); printf("%llu ", (unsigned long long)current());
              advance(7); printf("%llu ", (unsigned long long)current());
              printf("%llu ", (unsigned long long)read_fixed());
              select_pointer(1); printf("%llu ", (unsigned long long)read_selected());
              if (!same_selected(1)) return 2;
              advance_tls(5); printf("%llu ", (unsigned long long)current_tls());
              if (!state_bits()) return 3;
              advance(1); printf("%llu\\n", (unsigned long long)current());
              return 0;
            }
            #endif
            """.stripTrailing());
        var command = new ArrayList<String>(); command.add(System.getenv("THC_CLANG") == null ? "clang" : System.getenv("THC_CLANG"));
        if (System.getProperty("os.name").equals("Linux")) command.add("--target=" + (System.getProperty("os.arch").equals("amd64") ? "x86_64" : System.getProperty("os.arch")) + "-unknown-linux-gnu");
        command.addAll(List.of("-O1", "-emit-llvm", "-c", source.toString(), "-o", bitcode.toString()));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start(); var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output); var bytes = Files.readAllBytes(bitcode); var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var abi = List.of(new PackageScalarSignature("sum_bytes", "sum_bytes", List.of("ByteArray#", "Word64Rep"), "Word64Rep", "capi"),
            new PackageScalarSignature("update", "update", List.of("MutableByteArray#", "ByteArray#", "Word64Rep"), "void", "capi"),
            new PackageScalarSignature("alias", "alias", List.of("AddrRep", "AddrRep"), "Word32Rep"),
            new PackageScalarSignature("identity_float", "identity_float", List.of("FloatRep"), "FloatRep"),
            new PackageScalarSignature("identity_double", "identity_double", List.of("DoubleRep"), "DoubleRep"),
            new PackageScalarSignature("complement32", "complement32", List.of("Word32Rep"), "Word32Rep"),
            new PackageScalarSignature("complement8", "complement8", List.of("Word8Rep"), "Word8Rep"),
            new PackageScalarSignature("advance", "advance", List.of("Word64Rep"), "void"),
            new PackageScalarSignature("current", "current", List.of(), "Word64Rep"),
            new PackageScalarSignature("read_fixed", "read_fixed", List.of(), "Word64Rep"),
            new PackageScalarSignature("select_pointer", "select_pointer", List.of("Word64Rep"), "void"),
            new PackageScalarSignature("read_selected", "read_selected", List.of(), "Word64Rep"),
            new PackageScalarSignature("same_selected", "same_selected", List.of("Word64Rep"), "Word32Rep"),
            new PackageScalarSignature("advance_tls", "advance_tls", List.of("Word64Rep"), "void"),
            new PackageScalarSignature("current_tls", "current_tls", List.of(), "Word64Rep"),
            new PackageScalarSignature("state_bits", "state_bits", List.of(), "Word64Rep"),
            new PackageScalarSignature("null_pointer", "null_pointer", List.of(), "AddrRep"),
            new PackageScalarSignature("equal", "equal", List.of("AddrRep", "AddrRep"), "Word32Rep"),
            new PackageScalarSignature("next_equal", "next_equal", List.of("AddrRep", "AddrRep"), "Word32Rep"),
            new PackageScalarSignature("distance", "distance", List.of("AddrRep", "AddrRep"), "Int64Rep"),
            new PackageScalarSignature("read_before", "read_before", List.of("AddrRep"), "Word32Rep"),
            new PackageScalarSignature("native_bits", "native_bits", List.of("AddrRep"), "Word64Rep"),
            new PackageScalarSignature("mixed_alias", "mixed_alias", List.of("MutableByteArray#", "ByteArray#"), "Word32Rep"),
            new PackageScalarSignature("change_errno", "change_errno", List.of("Int32Rep"), "Int32Rep"),
            new PackageScalarSignature("observe_errno", "observe_errno", List.of(), "Int32Rep"),
            new PackageScalarSignature("pointer_errno", "pointer_errno", List.of("MutableByteArray#", "Int32Rep"), "Int32Rep"),
            new PackageScalarSignature("allocate_bytes", "allocate_bytes", List.of(), "AddrRep"),
            new PackageScalarSignature("release_bytes", "release_bytes", List.of("AddrRep"), "void"),
            new PackageScalarSignature("return_pointer", "return_pointer", List.of("AddrRep"), "AddrRep"));
        var selected = pointerVariants ? List.of(abi.getFirst(), new PackageScalarSignature(abi.getFirst().symbol(), "sum_bytes_address", List.of("AddrRep", "Word64Rep"), abi.getFirst().result(), abi.getFirst().convention(), abi.getFirst().safety())) : abi;
        var signatures = new ArrayList<PackageScalarSignature>(); for (var signature : selected) signatures.add(new PackageScalarSignature(signature.symbol(), signature.entry(), signature.arguments(), signature.result(), signature.convention(), safety));
        return new PackageScalarLink("native-ffi-control", "test-host", sha, sha, bytes, signatures);
    }
    private static final class Entry extends RootNode {
        private final PackageScalarCall operation; private final boolean forceIntegerResult;
        @Child private PackageScalarAccess access;
        long compiledEntries;
        Entry(Language language, PackageScalarCall operation) { this(language, operation, false); }
        Entry(Language language, PackageScalarCall operation, boolean forceIntegerResult) { super(language); this.operation = operation; this.forceIntegerResult = forceIntegerResult; access = new PackageScalarAccess(operation); }
        @Override public Object execute(VirtualFrame frame) {
            if (com.oracle.truffle.api.CompilerDirectives.inCompiledCode()) compiledEntries++;
            var arguments = frame.getArguments();
            if (operation.getResult().equals("void") && !forceIntegerResult) { access.executeVoid(arguments, thc.runtime.Unit.INSTANCE); return thc.runtime.Unit.INSTANCE; }
            if (operation.getResult().equals("AddrRep")) return access.executeAddress(arguments, thc.runtime.Unit.INSTANCE);
            if (operation.getResult().equals("FloatRep")) return access.executeFloat(arguments, thc.runtime.Unit.INSTANCE);
            if (operation.getResult().equals("DoubleRep")) return access.executeDouble(arguments, thc.runtime.Unit.INSTANCE);
            if (NarrowInteger.fromRep(operation.getResult()) != null) return access.executeInt(arguments, thc.runtime.Unit.INSTANCE);
            return access.executeLong(arguments, thc.runtime.Unit.INSTANCE);
        }
    }
    private PackageScalarSignature signature(PackageScalarLink link, String symbol) {
        PackageScalarSignature result = null;
        for (var value : link.getAbi()) if (value.symbol().equals(symbol)) { if (result != null) throw new IllegalArgumentException("Multiple signatures"); result = value; }
        if (result == null) throw new java.util.NoSuchElementException(symbol); return result;
    }
    @Test public void pointerFreeVoidAndPointerResultsKeepEffectsAndFirstInstalledCalls() throws Exception {
        var link = library();
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                Language.currentState().getPackageCbits().link(link);
                var advance = new Entry(language, new PackageScalarCall(link, signature(link, "advance")));
                var current = new Entry(language, new PackageScalarCall(link, signature(link, "current"))).getCallTarget();
                var absent = new Entry(language, new PackageScalarCall(link, signature(link, "null_pointer")));
                assertSame(Unit.INSTANCE, advance.getCallTarget().call(3L)); assertEquals(3L, current.call());
                assertThrows(RuntimeFault.class, () -> advance.getCallTarget().call(1)); assertEquals(3L, current.call());
                assertSame(ManagedAddress.nullAddress(), absent.getCallTarget().call());
                for (var entry : List.of(advance, absent)) {
                    var target = entry.getCallTarget();
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), entry.operation.getSignature().symbol() + " before");
                    long before = entry.compiledEntries;
                    if (entry == advance) {
                        assertSame(Unit.INSTANCE, target.call(7L));
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), "advance immediately after call");
                        assertEquals(10L, current.call());
                    }
                    else assertSame(ManagedAddress.nullAddress(), target.call());
                    assertEquals(before + 1, entry.compiledEntries);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), entry.operation.getSignature().symbol() + " after");
                }
            } finally { context.leave(); }
        }
    }
    private Context globalContext() {
        return Context.newBuilder("thc").allowNativeAccess(true).allowCreateThread(true).allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").build();
    }
    @ParameterizedTest @ValueSource(strings = {"free", "realloc", "finalizer"})
    public void returnedNativeMallocUsesNativeDeallocationWithoutClaimingOwnership(String operation) throws Exception {
        var link = library();
        try (var context = globalContext()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState();
                state.getPackageCbits().link(link);
                var allocate = globalEntry(link, "allocate_bytes").getCallTarget();
                var release = globalEntry(link, "release_bytes").getCallTarget();
                var address = (ManagedAddress) allocate.call();
                assertNotSame(ManagedAddress.nullAddress(), address);
                assertNotNull(address.returnedAddress()); assertNull(address.nativeAllocation());
                assertNull(address.returnedAddress().getBacking());
                assertEquals(42L, address.readWord8(0));
                assertEquals(73L, address.readWord8(15));
                assertEquals(0, state.getNativeAllocations().liveCount());
                boolean consumed = false;
                try {
                    switch (operation) {
                        case "free" -> { state.getNativeAllocations().free(address); consumed = true; }
                        case "realloc" -> {
                            var resized = state.getNativeAllocations().realloc(address, 32);
                            assertNotSame(ManagedAddress.nullAddress(), resized);
                            consumed = true;
                            try {
                                assertNull(resized.nativeAllocation()); assertNotNull(resized.returnedAddress());
                                assertEquals(42L, resized.readWord8(0));
                                assertEquals(73L, resized.readWord8(15));
                                resized.writeWord8Int(31, 91);
                                assertEquals(91L, resized.readWord8(31));
                            } finally { release.call(resized); }
                        }
                        case "finalizer" -> {
                            var key = new Object(); var weak = state.getWeaks().make(key, key, null, null);
                            var provider = state.cbits();
                            assertEquals(1L, state.getWeaks().addCFinalizer(provider.finalizerLabel("free"), address, 0L, ManagedAddress.nullAddress(), weak, provider));
                            state.getWeaks().finalize(weak);
                            consumed = true;
                            assertEquals(0L, state.getWeaks().finalize(weak).getFlag());
                            java.lang.ref.Reference.reachabilityFence(key);
                        }
                        default -> throw new AssertionError(operation);
                    }
                    assertEquals(0, state.getNativeAllocations().liveCount(), "external allocations are never falsely adopted");
                } finally { if (!consumed) release.call(address); }
            } finally { context.leave(); }
        }
    }
    @Test public void returnedAliasesKeepKnownAllocationOwnershipAndRejectForeignContexts() throws Exception {
        var link = library();
        try (var context = globalContext()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); var allocations = state.getNativeAllocations();
                state.getPackageCbits().link(link);
                var identity = globalEntry(link, "return_pointer").getCallTarget();
                var original = allocations.malloc(16);
                var returned = (ManagedAddress) identity.call(original);
                assertSame(original.nativeAllocation(), returned.returnedAddress().getBacking().nativeAllocation());
                assertThrows(RuntimeFault.class, () -> allocations.free(returned.plus(1)));
                assertThrows(RuntimeFault.class, () -> allocations.free(returned, ManagedNativeAllocations.Allocator.WINDOWS_LOCAL));
                allocations.requireFreeTarget(returned);
                allocations.free(returned);
                assertThrows(RuntimeFault.class, () -> original.readWord8(0));
                assertThrows(RuntimeFault.class, () -> allocations.free(returned));
                assertEquals(0, allocations.liveCount());
                var external = (ManagedAddress) globalEntry(link, "allocate_bytes").getCallTarget().call();
                try {
                    try (var other = globalContext()) {
                        other.initialize("thc"); other.enter();
                        try { assertThrows(RuntimeFault.class, () -> Language.currentState().getNativeAllocations().free(external)); }
                        finally { other.leave(); }
                    }
                    assertEquals(42L, external.readWord8(0), "rejected foreign free has no native effect");
                } finally { allocations.free(external); }
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    public void genericCallsShareOriginalErrnoWithoutLosingZeroOrThreadIsolation(String backend) throws Exception {
        var link = library(false, "safe");
        try (var context = globalContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                Language.currentState().getPackageCbits().link(link);
                var module = OriginalStdioFixtures.module(List.of("errno"));
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
                var errno = program.entryTarget("errno");
                var change = globalEntry(link, "change_errno").getCallTarget();
                var observe = globalEntry(link, "observe_errno").getCallTarget();
                var pointer = globalEntry(link, "pointer_errno").getCallTarget();
                var stdio = Language.currentState().getStdio();
                assertEquals(-1, change.call(73));
                assertEquals(73, ScalarTestCalls.callScalarTestTarget(errno, new Object[]{0L, Unit.INSTANCE}));
                stdio.setErrno(41);
                assertEquals(41, observe.call(), "managed errno is seeded into every generic call");
                var bytes = ManagedAllocation.mutable(1, 8, true);
                assertEquals(-1, pointer.call(bytes, 19));
                assertEquals(197L, bytes.readByte(0));
                assertEquals(19, ScalarTestCalls.callScalarTestTarget(errno, new Object[]{0L, Unit.INSTANCE}));
                var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
                try {
                    worker.submit(() -> {
                        context.enter();
                        try {
                            assertEquals(0, observe.call());
                            assertEquals(-1, change.call(31));
                            assertEquals(31, ScalarTestCalls.callScalarTestTarget(errno, new Object[]{0L, Unit.INSTANCE}));
                        } finally { context.leave(); }
                    }).get(10, java.util.concurrent.TimeUnit.SECONDS);
                } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)); }
                assertEquals(19, observe.call());
                assertEquals(-1, change.call(0));
                assertEquals(0, ScalarTestCalls.callScalarTestTarget(errno, new Object[]{0L, Unit.INSTANCE}));
            } finally { context.leave(); }
        }
    }
    private Entry globalEntry(PackageScalarLink link, String name) {
        return new Entry(TruffleLanguage.LanguageReference.create(Language.class).get(null),
            new PackageScalarCall(link, signature(link, name)));
    }
    @Test @SuppressWarnings("unchecked")
    public void rawCarrierFixturePreservesItsNativeLink() throws Exception {
        var link = library();
        var source = OriginalStdioFixtures.module(List.of("errno"), call -> {
            var descriptor = (Map<String, Object>) ((Map<?, ?>) call.get(6)).get("foreignCall");
            var target = (Map<String, Object>) descriptor.get("target");
            target.put("symbol", "observe_errno"); target.put("unit", link.getUnit());
        });
        source.put("packageScalarLinks", List.of(link));
        var raw = OriginalStdioChecks.rawModule(OriginalStdioChecks.foreignCalls(source).getFirst(), source);
        var call = OriginalStdioChecks.foreignCalls(raw).getFirst();
        var metadata = (Map<String, Object>) call.get(6);
        var links = raw.get("packageScalarLinks") instanceof List<?> values
            ? (List<PackageScalarLink>) values : List.<PackageScalarLink>of();
        var selected = CorePackageScalarForeign.validate(metadata, List.of(OriginalStdioFixtures.scalar(null)),
            (List<?>) call.get(3), metadata.get("rep"), links);
        assertNotNull(selected, "raw-carrier extraction must retain genuine native linkage");
        try (var context = globalContext()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); state.getPackageCbits().link(link);
                state.getStdio().captureForeignErrno(73);
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                assertEquals(73, new Entry(language, selected).getCallTarget().call());
            } finally { context.leave(); }
        }
    }
    @Tag("foreign-exceptions-full-core")
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    @SuppressWarnings("unchecked")
    public void rawCarrierFixtureKeepsGenuineExceptionSupportThroughLowering(String backend) throws Exception {
        var link = library();
        var source = OriginalStdioFixtures.module(List.of("errno"), call -> {
            var descriptor = (Map<String, Object>) ((Map<?, ?>) call.get(6)).get("foreignCall");
            var target = (Map<String, Object>) descriptor.get("target");
            target.put("symbol", "observe_errno"); target.put("unit", link.getUnit());
        });
        source.put("packageScalarLinks", List.of(link));
        var call = OriginalStdioChecks.foreignCalls(source).getFirst();
        var linked = ForeignExceptionFixtureSupport.link(source, "errno");
        var raw = OriginalStdioChecks.rawModule(call, linked);
        assertSame(linked.get("selectedForeignExceptionBridge"), raw.get("selectedForeignExceptionBridge"));
        try (var context = globalContext()) {
            context.initialize("thc"); context.enter();
            try {
                Language.currentState().getPackageCbits().link(link);
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, raw) : new BytecodeProgram(language, raw);
                var target = program.entryTarget("entry");
                // Link/bridge transport through both lowerers. The original
                // corpus tests separately require actual compiled entries.
                Language.currentState().getStdio().captureForeignErrno(73);
                assertEquals(73, Calls.target(target, new Object[]{0L, Unit.INSTANCE}));
                var handoff = language.getHandoffState().get();
                assertEquals(0, handoff.getArguments().getDepth());
                assertEquals(0, handoff.getResults().getDepth());
                assertEquals(0, handoff.getResults().retainedReferences());
            } finally { context.leave(); }
        }
    }
    private RootCallTarget compileEntry(Entry entry) throws Exception {
        var target = entry.getCallTarget();
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
        return target;
    }
    @Test public void nativeFloatingIdentitySurvivesFirstCompiledCalls() throws Exception {
        var link = library();
        try (var context = globalContext()) {
            context.initialize("thc"); context.enter();
            try {
                Language.currentState().getPackageCbits().link(link);
                for (boolean single : new boolean[]{true, false}) {
                    var entry = globalEntry(link, single ? "identity_float" : "identity_double");
                    var target = entry.getCallTarget();
                    // Compare representations where the value is exact. A C value
                    // identity does not establish a NaN-payload preservation contract.
                    long[] patterns = single
                        ? new long[]{0, 0x80000000L, 1, 0x007fffffL, 0x00800000L, 0x7f800000L, 0xff800000L, 0x7fc01234L}
                        : new long[]{0, Long.MIN_VALUE, 1, 0x000fffffffffffffL, 0x0010000000000000L,
                            0x7ff0000000000000L, 0xfff0000000000000L, 0x7ff8000000005678L};
                    for (boolean compiled : new boolean[]{false, true}) {
                        if (compiled) compileEntry(entry);
                        for (int index = 0; index < patterns.length; index++) {
                            long bits = patterns[compiled ? patterns.length - 1 - index : index];
                            Object argument;
                            if (single) argument = Float.intBitsToFloat((int) bits);
                            else argument = Double.longBitsToDouble(bits);
                            long before = entry.compiledEntries;
                            var result = target.call(argument);
                            long actual = single
                                ? Integer.toUnsignedLong(Float.floatToRawIntBits(assertInstanceOf(Float.class, result)))
                                : Double.doubleToRawLongBits(assertInstanceOf(Double.class, result));
                            if (single && Float.isNaN((Float) argument)) assertTrue(Float.isNaN((Float) result));
                            else if (!single && Double.isNaN((Double) argument)) assertTrue(Double.isNaN((Double) result));
                            else assertEquals(bits, actual, (single ? "Float/" : "Double/") + Long.toUnsignedString(bits, 16));
                            if (compiled) {
                                assertTrue(entry.compiledEntries > before, "The first call after compilation must enter installed code");
                                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                            }
                        }
                    }
                }
            } finally { context.leave(); }
        }
    }
    private void assertInstalled(Entry entry, RootCallTarget target, long before) throws Exception {
        assertSame(target, entry.getCallTarget());
        assertEquals(before + 1, entry.compiledEntries);
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }
    @Test public void mutableAndReadonlyGlobalReadersRemainInstalledAcrossWrites() throws Exception {
        var link = library();
        // The independent native oracle uses the Linux C linker/runtime. The
        // Sulong controls below run everywhere without requiring a host SDK.
        if (System.getProperty("os.name").equals("Linux")) {
            var executable = directory.resolve("native");
            var compiler = System.getenv().getOrDefault("THC_CLANG", "clang");
            var process = new ProcessBuilder(compiler, "-O1", "-DNATIVE_ORACLE", directory.resolve("native.c").toString(),
                "-o", executable.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), output);
            process = new ProcessBuilder(executable.toString()).redirectErrorStream(true).start();
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), output);
            assertEquals("3 10 29 41 5 11", output.trim());
        }
        try (var context = globalContext()) {
            context.initialize("thc"); context.enter();
            try {
                Language.currentState().getPackageCbits().link(link);
                var advance = globalEntry(link, "advance").getCallTarget();
                var current = globalEntry(link, "current"); var fixed = globalEntry(link, "read_fixed");
                advance.call(3L); assertEquals(3L, current.getCallTarget().call()); assertEquals(29L, fixed.getCallTarget().call());
                var currentTarget = compileEntry(current); var fixedTarget = compileEntry(fixed);
                long currentBefore = current.compiledEntries, fixedBefore = fixed.compiledEntries;
                advance.call(7L);
                assertEquals(true, currentTarget.getClass().getMethod("isValidLastTier").invoke(currentTarget), "write must not invalidate compiled mutable reader");
                assertEquals(10L, currentTarget.call()); assertInstalled(current, currentTarget, currentBefore);
                assertEquals(29L, fixedTarget.call()); assertInstalled(fixed, fixedTarget, fixedBefore);
            } finally { context.leave(); }
        }
    }
    @Test public void globalPointerWritesRetainAliasesAndInstalledReaders() throws Exception {
        var link = library();
        try (var context = globalContext()) {
            context.initialize("thc"); context.enter();
            try {
                Language.currentState().getPackageCbits().link(link);
                var select = globalEntry(link, "select_pointer"); var read = globalEntry(link, "read_selected");
                var same = globalEntry(link, "same_selected").getCallTarget();
                select.getCallTarget().call(0L); assertEquals(17L, read.getCallTarget().call()); assertEquals(1, same.call(0L));
                var selectTarget = compileEntry(select); var readTarget = compileEntry(read);
                long selectBefore = select.compiledEntries, readBefore = read.compiledEntries;
                assertSame(Unit.INSTANCE, selectTarget.call(1L)); assertInstalled(select, selectTarget, selectBefore);
                assertEquals(true, readTarget.getClass().getMethod("isValidLastTier").invoke(readTarget));
                assertEquals(41L, readTarget.call()); assertInstalled(read, readTarget, readBefore);
                assertEquals(1, same.call(1L)); assertEquals(0, same.call(0L));
            } finally { context.leave(); }
        }
    }
    @Test public void globalsRemainContextLocalAndTlsRemainsThreadLocal() throws Exception {
        var link = library();
        for (int index = 0; index < 2; index++) try (var context = globalContext()) {
            context.initialize("thc"); context.enter();
            try {
                Language.currentState().getPackageCbits().link(link);
                var current = globalEntry(link, "current").getCallTarget();
                var local = globalEntry(link, "current_tls").getCallTarget();
                var advanceLocal = globalEntry(link, "advance_tls").getCallTarget();
                assertEquals(0L, current.call()); assertEquals(0L, local.call());
                globalEntry(link, "advance").getCallTarget().call(3L + index);
                advanceLocal.call(5L);
                var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
                try {
                    worker.submit(() -> {
                        context.enter();
                        try { assertEquals(0L, local.call()); advanceLocal.call(11L); assertEquals(11L, local.call()); }
                        finally { context.leave(); }
                    }).get(10, java.util.concurrent.TimeUnit.SECONDS);
                } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)); }
                assertEquals(5L, local.call()); assertEquals(3L + index, current.call());
            } finally { context.leave(); }
        }
    }
    @Test public void nativeGlobalTransitionPreservesValuesAndStableAddress() throws Exception {
        var link = library();
        try (var context = globalContext()) {
            context.initialize("thc"); context.enter();
            try {
                Language.currentState().getPackageCbits().link(link);
                var advance = globalEntry(link, "advance").getCallTarget();
                var current = globalEntry(link, "current").getCallTarget(); var bits = globalEntry(link, "state_bits").getCallTarget();
                advance.call(3L); assertEquals(3L, current.call());
                long address = (Long) bits.call(); assertNotEquals(0L, address ^ 0x5a5a123456787654L);
                assertEquals(3L, current.call(), "native transition preserves fallback contents");
                advance.call(7L); assertEquals(10L, current.call()); assertEquals(address, bits.call());
            } finally { context.leave(); }
        }
    }
    @Test public void pointerFreeNullResultRetainsItsFirstInstalledCall() throws Exception {
        var link = library();
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                Language.currentState().getPackageCbits().link(link);
                var entry = new Entry(language, new PackageScalarCall(link, signature(link, "null_pointer")));
                var target = entry.getCallTarget();
                assertSame(ManagedAddress.nullAddress(), target.call());
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), "before");
                long before = entry.compiledEntries;
                assertSame(ManagedAddress.nullAddress(), target.call());
                assertEquals(before + 1, entry.compiledEntries);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), "after");
            } finally { context.leave(); }
        }
    }
    @Test public void integerCallSitesRetainFirstInstalledCodeAndRejectWrongResultsBeforeEffects() throws Exception {
        var link = library();
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); Language.currentState().getPackageCbits().link(link);
                var target = new Entry(language, new PackageScalarCall(link, signature(link, "sum_bytes"))).getCallTarget();
                assertEquals(385L, target.call(new byte[]{1, 2, 127, -1}, 4L)); target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                assertEquals(260L, target.call(new byte[]{5, -1}, 2L), "first installed call observes new bytes"); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                var invalid = new Entry(language, new PackageScalarCall(link, signature(link, "update")), true).getCallTarget(); var state = ManagedAllocation.mutable(8, 8, true);
                assertThrows(RuntimeFault.class, () -> invalid.call(state, new byte[]{1}, 1L)); assertEquals(0L, state.readByte(0), "wrong result path must reject before C mutates its argument");
            } finally { context.leave(); }
        }
    }
    private Object argument(PackageScalarSignature signature, byte[] bytes) { return signature.arguments().getFirst().equals("AddrRep") ? ManagedAddress.fromByteArray(bytes).plus(1) : bytes; }
    @Test public void sameCNameRetainsEachNativeAdapterAndItsFirstCompiledCall() throws Exception {
        var link = library(true, "unsafe");
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var registry = Language.currentState().getPackageCbits(); registry.link(link);
                for (var signature : link.getAbi()) {
                    assertSame(signature, registry.resolve(link, signature).getSignature(), "same C symbol must not overwrite a sibling adapter");
                    var target = new Entry(language, new PackageScalarCall(link, signature)).getCallTarget(); var bytes = new byte[]{1, 2, 127, -1};
                    long expected = signature.arguments().getFirst().equals("AddrRep") ? 384L : 130L; assertEquals(expected, target.call(argument(signature, bytes), 3L));
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    var next = new byte[]{3, 5, 7, 11}; assertEquals(signature.arguments().getFirst().equals("AddrRep") ? 23L : 15L, target.call(argument(signature, next), 3L), "first installed call preserves the selected carrier");
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                }
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"unsafe", "safe"})
    public void realCReadsAndMutatesAliasedPersistentByteStorage(String safety) throws Exception {
        var link = library(false, safety);
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); Language.currentState().getPackageCbits().link(link);
                var functions = new LinkedHashMap<String, RootCallTarget>(); for (var signature : link.getAbi()) functions.put(signature.symbol(), new Entry(language, new PackageScalarCall(link, signature)).getCallTarget());
                var source = new byte[]{1, 2, 127, -1}; assertEquals(385L, functions.get("sum_bytes").call(source, 4L));
                var state = ManagedAllocation.mutable(8, 8); functions.get("update").call(state, source, 4L); assertEquals(129L, state.readByte(0));
                functions.get("update").call(state, new byte[]{3, 5}, 2L); assertEquals(137L, state.readByte(0), "foreign mutations survive separate calls");
                var address = ManagedAddress.fromAllocation(state); assertEquals(197, functions.get("alias").call(address, address.plus(1)));
                assertEquals(197L, state.readByte(1), "overlapping arguments share the original allocation");
                assertEquals(1, functions.get("equal").call(address, address)); assertEquals(1, functions.get("equal").call(address, ManagedAddress.fromAllocation(state)));
                assertEquals(1, functions.get("next_equal").call(address, address.plus(1))); assertEquals(0, functions.get("equal").call(address, address.plus(1)));
                assertEquals(7L, functions.get("distance").call(address, address.plus(7))); assertEquals(-7L, functions.get("distance").call(address.plus(7), address));
                assertEquals(8L, functions.get("distance").call(address, address.plus(8)), "one-past pointer"); assertEquals(137, functions.get("read_before").call(address.plus(1)));
                assertEquals(91, functions.get("mixed_alias").call(state, state), "const and writable views share identity"); assertEquals(91L, state.readByte(0));
                var rawAlias = ManagedAddress.fromByteArray(state.rawBytesIfPointerFree()); assertEquals(1, functions.get("equal").call(address, rawAlias), "raw and owned views share identity");
                assertEquals(1, functions.get("equal").call(rawAlias, address), "raw-first views use the same owned transport");
                var pinned = PinnedMemory.allocate(8, 64); var pinnedAddress = ManagedAddress.fromAllocation(pinned); long pinnedBits = pinned.nativeSegment().address();
                functions.get("update").call(pinned, source, 4L); assertEquals(129L, pinned.readByte(0)); assertEquals(197, functions.get("alias").call(pinnedAddress, pinnedAddress.plus(1)));
                assertEquals(197L, pinned.readByte(1)); assertEquals(1, functions.get("next_equal").call(pinnedAddress, pinnedAddress.plus(1)));
                assertEquals(pinnedBits + 1, (Long) functions.get("native_bits").call(pinnedAddress.plus(1)) ^ 0x5a5a123456787654L);
                assertEquals(pinnedBits, pinned.nativeSegment().address(), "C uses the original pinned allocation");
                var literal = ManagedAddress.fromHex("6162"); assertEquals(1, functions.get("next_equal").call(literal, literal.plus(1))); assertEquals(97, functions.get("read_before").call(literal.plus(1)));
                // First/only argument is interior; there is no existing
                // native image or base argument to hide an offset error.
                var nativeLiteral = ManagedAddress.fromHex("1020304050"); var nativeImages = Language.currentState().getNativeAddresses(); assertNull(nativeImages.transport(nativeLiteral));
                long projected = (Long) functions.get("native_bits").call(nativeLiteral.plus(3)); var image = nativeImages.transport(nativeLiteral);
                assertNotNull(image, "C numerical use actually materialized an immutable native image"); assertEquals(image.asPointer() + 3, projected ^ 0x5a5a123456787654L);
                assertEquals(image.asPointer() + 1, (Long) functions.get("native_bits").call(nativeLiteral.plus(1)) ^ 0x5a5a123456787654L);
                assertEquals(48, functions.get("read_before").call(nativeLiteral.plus(3))); var separate = ManagedAddress.fromByteArray(new byte[]{91});
                assertEquals(0, functions.get("equal").call(address, separate)); assertEquals(-1, functions.get("complement32").call(0), "Word32 result retains all raw bits");
                assertEquals(0, functions.get("complement32").call(-1)); assertEquals(255, functions.get("complement8").call((byte) 0));
                var frozen = ManagedAllocation.immutable(new byte[]{0}, 8); assertThrows(Exception.class, () -> functions.get("update").call(frozen, source, 4L)); assertArrayEquals(new byte[]{1, 2, 127, -1}, source);
            } finally { context.leave(); }
        }
    }
    @Test public void defaultHeapBuffersRejectNativePointerProjection() throws Exception {
        var link = library();
        try (var context = globalContext()) {
            context.initialize("thc"); context.enter();
            try {
                var owner = Language.currentState();
                assertFalse(owner.getNativeByteArrays());
                owner.getPackageCbits().link(link);
                var target = globalEntry(link, "native_bits").getCallTarget();
                for (var address : List.of(ManagedAddress.fromByteArray(new byte[]{42}),
                        ManagedAddress.fromAllocation(ManagedAllocation.mutable(1, 8)))) {
                    var failure = assertThrows(com.oracle.truffle.api.exception.AbstractTruffleException.class,
                        () -> target.call(address));
                    assertTrue(failure.getMessage().contains("native pointer"), failure.getMessage());
                    assertFalse(address.hasNativeStorage(), "a foreign call cannot promote an existing heap alias");
                }
            } finally { context.leave(); }
        }
    }
    private record Conversion(String rep, long value) {}
    @Test public void integerConversionsRetainUnsignedBitsAndRejectOutOfRangeInputs() {
        assertEquals((byte) -1, PackageScalarAccess.packageCInteger("Word8Rep", 255)); assertEquals((short) -1, PackageScalarAccess.packageCInteger("Word16Rep", 65535));
        assertEquals(-1, PackageScalarAccess.packageCInteger("Word32Rep", -1));
        for (var row : List.of(new Conversion("Word8Rep", -1L), new Conversion("Word8Rep", 256L), new Conversion("Word16Rep", 65536L),
                new Conversion("Word32Rep", 0x1_0000_0000L), new Conversion("Int8Rep", 128L), new Conversion("Int16Rep", 32768L)))
            assertThrows(RuntimeFault.class, () -> NarrowInteger.fromRep(row.rep()).fromHost(row.value()));
    }
}
