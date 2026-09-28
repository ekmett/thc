// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class NativeAddressTest {
    @Test public void staticMetadataRetainsPrivateReadOnlyBytesAndExactSize() throws Exception {
        var source = new byte[] {0, -1, 127}; var address = ManagedAddress.fromStaticBytes(source); Arrays.fill(source, (byte) 17);
        assertEquals(3L, address.availableBytes()); var observed = new ArrayList<Long>(); for (long i = 0; i <= 2; i++) observed.add(address.readWord8(i));
        assertEquals(List.of(0L, 255L, 127L), observed); assertThrows(RuntimeFault.class, () -> address.readWord8(3));
        assertThrows(RuntimeFault.class, () -> address.writeWord8(0, 1)); Arrays.fill(address.rawBacking(), (byte) 19); assertEquals(0L, address.readWord8(0));
        final NativeReadOnlyPointer view;
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var registry = NativeAddresses.current(null); long bits = address.toNativeBits(); view = Objects.requireNonNull(registry.transport(address));
                assertEquals(bits + 3, address.plus(3).toNativeBits()); assertTrue(address.plus(3).sameLocation(registry.recover(bits + 3)));
                assertArrayEquals(new byte[] {0, -1, 127}, MemorySegment.ofAddress(bits).reinterpret(3).toArray(ValueLayout.JAVA_BYTE));
                assertEquals(bits, address.toNativeBits());
            } finally { context.leave(); }
        }
        assertFalse(InteropLibrary.getUncached().isPointer(view)); assertEquals(255L, address.readWord8(1), "managed static bytes outlive a retired native view");
    }
    private final Map<String, Object> address = Map.of("kind", "address", "primReps", List.of("AddrRep"), "evaluated", true);
    private final Map<String, Object> integer = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private final Map<String, Object> closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private Map<String, Object> binding(String name, String primitive, Map<String, Object> argument, Map<String, Object> result) {
        var formal = Map.of("id", "arg", "lifted", false, "rep", argument);
        var call = List.of("app", List.of("prim", primitive), List.of(List.of("var", "arg", Map.of("rep", argument))), List.of(false), false, false, Map.of("rep", result));
        return Map.of("id", name, "name", name, "arity", 1, "lifted", true, "rep", closure,
            "expr", List.of("lam", List.of(formal), call, Map.of("rep", closure, "resultRep", result)));
    }
    private Map<String, Object> module() { return Map.of("schema", 1, "ghc", "9.14.1", "module", "SyntheticAddressConsumers", "instrument", true,
        "constructors", List.of(), "bindings", List.of(binding("toBits", "addr2Int#", address, integer), binding("fromBits", "int2Addr#", integer, address))); }
    private Context context() { return context(true, false); }
    private Context context(boolean nativeAccess, boolean inlining) {
        return Context.newBuilder("thc").allowNativeAccess(nativeAccess).allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000").build();
    }
    private void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    /** Failure-only observation; never repairs the stub or executes another guest call. */
    private String entryState(RootCallTarget target) throws Exception {
        var jvmci = Class.forName("jdk.vm.ci.runtime.JVMCI").getMethod("getRuntime").invoke(null);
        var backend = Class.forName("jdk.vm.ci.runtime.JVMCIRuntime").getMethod("getHostJVMCIBackend").invoke(jvmci);
        var metaAccess = Class.forName("jdk.vm.ci.runtime.JVMCIBackend").getMethod("getMetaAccess").invoke(backend);
        var method = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getDeclaredMethod("callBoundary", Object[].class);
        var boundary = Class.forName("jdk.vm.ci.meta.MetaAccessProvider").getMethod("lookupJavaMethod", java.lang.reflect.Executable.class).invoke(metaAccess, method);
        var installed = Class.forName("jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod").getMethod("hasCompiledCode").invoke(boundary);
        return "valid=" + target.getClass().getMethod("isValidLastTier").invoke(target) + " boundary=" + installed;
    }
    @Test public void exactBitsAndImmutableAliasesSurviveTheFirstCompiledEntryInBothBackends() throws Exception {
        for (var backend : List.of("ast", "bytecode")) for (boolean inlining : new boolean[] {false, true}) try (var context = context(true, inlining)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module()) : new BytecodeProgram(language, module());
                var targets = new LinkedHashMap<String, RootCallTarget>(); for (var name : List.of("toBits", "fromBits")) targets.put(name, program.entryTarget(name));
                var literal = ManagedAddress.fromHex("616263"); var header = ManagedAddress.fromHex("07090b0d");
                var pinned = PinnedMemory.allocate(16, 64); pinned.writeByte(7, 93); var pinnedBase = ManagedAddress.fromAllocation(pinned);
                var stablePointers = Language.currentState().getStablePointers(); var referent = new Object(); var stable = stablePointers.make(referent);
                class Exercise {
                    boolean compiled;
                    Long literalBits;
                    Object call(String name, Object argument) throws Exception {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); var target = targets.get(name);
                        var result = Calls.target(target, new Object[] {0L, argument});
                        if (compiled) {
                            var label = backend + "/inlining=" + inlining + "/" + name + "(" + argument + ")";
                            assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue(), () -> {
                                try { return label + " must enter compiled code exactly once; after call " + entryState(target); }
                                catch (Exception failure) { throw new AssertionError(failure); }
                            });
                            valid(target, label + " must remain valid after the call");
                        }
                        return result;
                    }
                    void run() throws Exception {
                        for (long bits : new long[] {Long.MIN_VALUE, -4096L, -1L, 0L, 1L, 4096L, Long.MAX_VALUE}) assertEquals(bits, call("toBits", Objects.requireNonNull(call("fromBits", bits))));
                        for (var original : List.of(literal, header)) {
                            long bits = (Long) call("toBits", original); assertNotEquals(0L, bits);
                            for (long offset = 0; offset <= 4; offset++) {
                                var alias = (ManagedAddress) call("fromBits", bits + offset); assertTrue(alias.sameLocation(original.plus(offset)));
                                assertEquals(bits + offset, call("toBits", alias)); if (offset < 4) assertEquals(original.readWord8(offset), alias.readWord8(0));
                                assertThrows(RuntimeFault.class, () -> alias.writeWord8(0, 0));
                            }
                        }
                        long bits = (Long) call("toBits", literal); if (literalBits != null) assertEquals(literalBits.longValue(), bits); literalBits = bits;
                        for (long offset : new long[] {0L, 7L, 16L}) {
                            long pinnedBits = (Long) call("toBits", pinnedBase.plus(offset)); assertEquals(Objects.requireNonNull(pinned.nativeSegment()).address() + offset, pinnedBits);
                            var alias = (ManagedAddress) call("fromBits", pinnedBits); assertTrue(alias.sameLocation(pinnedBase.plus(offset))); if (offset == 7L) assertEquals(93L, alias.readWord8(0));
                        }
                        var recovered = (ManagedAddress) call("fromBits", Objects.requireNonNull(call("toBits", stable)));
                        assertTrue(stablePointers.equal(stable, recovered)); assertSame(referent, stablePointers.dereference(recovered));
                    }
                }
                var exercise = new Exercise(); for (int i = 0; i < 3; i++) exercise.run();
                var runtime = Truffle.getRuntime(); var targetType = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                for (var entry : targets.entrySet()) {
                    var target = entry.getValue(); target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                    valid(target, backend + "/inlining=" + inlining + "/" + entry.getKey() + " after compilation");
                    // Match EntryValue.compile without executing a settling guest call.
                    runtime.getClass().getMethod("bypassedInstalledCode", targetType).invoke(runtime, target);
                    valid(target, backend + "/inlining=" + inlining + "/" + entry.getKey() + " after boundary restoration");
                }
                exercise.compiled = true; exercise.run(); // first calls after installation, with no settling calls
                exercise.compiled = false; assertThrows(RuntimeFault.class, () -> exercise.call("toBits", 1L)); assertThrows(RuntimeFault.class, () -> exercise.call("fromBits", literal));
                assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                assertEquals(0, language.getHandoffState().get().getArguments().retainedReferences()); assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
                stablePointers.free(stable);
            } finally { context.leave(); }
        }
    }

    @Test public void nativeTransportPointsAtRealBytesAndClosesWithItsOwner() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var registry = NativeAddresses.current(null); var original = ManagedAddress.fromHex("616263");
                var olderBuffer = Language.currentState(null).cbits().buffer(original.plus(1), true);
                long bits = original.toNativeBits(); var view = Objects.requireNonNull(registry.transport(original.plus(1))); var interop = InteropLibrary.getUncached();
                assertTrue(interop.isPointer(view)); assertEquals(bits, interop.asPointer(view)); // C ABI adds the original byte offset
                interop.toNative(olderBuffer); assertTrue(interop.isPointer(olderBuffer)); assertEquals(bits, interop.asPointer(olderBuffer));
                var mutableBuffer = Language.currentState(null).cbits().buffer(ManagedAddress.fromByteArray(new byte[8]), true);
                interop.toNative(mutableBuffer); assertFalse(interop.isPointer(mutableBuffer));
                // Only a currently live owned transport supplies this test address.
                // Never reinterpret an arbitrary input integer in the runtime.
                var nativeMemory = MemorySegment.ofAddress(interop.asPointer(view)).reinterpret(4);
                assertArrayEquals(new byte[] {97, 98, 99, 0}, nativeMemory.toArray(ValueLayout.JAVA_BYTE));
                System.gc(); assertEquals(98L, registry.recover(bits + 1).readWord8(0));
                var md5 = ManagedAddress.fromByteArray(new byte[96]); var digest = ManagedAddress.fromByteArray(new byte[16]);
                ManagedMd5.init(md5); ManagedMd5.update(md5, original, 3); ManagedMd5.finish(digest, md5);
                var hex = new StringBuilder(); for (long i = 0; i < 16; i++) hex.append(String.format(Locale.ROOT, "%02x", digest.readWord8(i)));
                assertEquals("900150983cd24fb0d6963f7d28e17f72", hex.toString());
                registry.close(); assertFalse(interop.isPointer(view)); assertFalse(interop.isPointer(olderBuffer));
                assertThrows(UnsupportedMessageException.class, () -> interop.asPointer(view)); assertThrows(UnsupportedMessageException.class, () -> interop.asPointer(olderBuffer));
                assertThrows(RuntimeFault.class, () -> registry.recover(bits)); assertThrows(RuntimeFault.class, original::toNativeBits); Reference.reachabilityFence(view);
            } finally { context.leave(); }
        }
    }

    @Test public void arbitraryIntegersAndUnsupportedManagedDomainsNeverGrantMemoryAccess() {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var registry = NativeAddresses.current(null);
                for (long bits : new long[] {Long.MIN_VALUE, -1L, 1L, Long.MAX_VALUE}) {
                    var opaque = registry.recover(bits); assertEquals(bits, opaque.toNativeBits()); assertThrows(RuntimeFault.class, () -> opaque.readWord8(0));
                    assertThrows(RuntimeFault.class, () -> opaque.writeWord8(0, 1)); assertThrows(RuntimeFault.class, opaque::cbitsBacking);
                }
                var bytes = new byte[] {1, 2, 3}; var mutable = ManagedAddress.fromByteArray(bytes); assertThrows(RuntimeFault.class, mutable::toNativeBits);
                assertArrayEquals(new byte[] {1, 2, 3}, bytes); assertThrows(RuntimeFault.class, () -> ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8)).toNativeBits());
                var stable = StablePointers.current(null); var handle = stable.make(kotlin.Unit.INSTANCE); long token = handle.toNativeBits();
                assertSame(kotlin.Unit.INSTANCE, stable.dereference(registry.recover(token))); assertThrows(RuntimeFault.class, () -> registry.recover(token).readWord8(0));
                stable.free(handle); assertThrows(RuntimeFault.class, () -> stable.dereference(registry.recover(token)));
            } finally { context.leave(); }
        }
        try (var context = context(false, false)) {
            context.initialize("thc"); context.enter();
            try {
                assertEquals(-1L, NativeAddresses.current(null).recover(-1).toNativeBits()); assertEquals(0L, ManagedAddress.nullAddress().toNativeBits());
                assertThrows(RuntimeFault.class, () -> ManagedAddress.fromHex("41").toNativeBits());
            } finally { context.leave(); }
        }
    }

    @Test public void immutableHeapBuffersStayReadOnlyWithoutPromotionAndStaticLiteralsKeepNativeImages() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var registry = NativeAddresses.current(null); var input = new byte[] {7, 9, 11, 13, 17, 19, 23, 29};
                var owner = ManagedAllocation.immutable(input, 8); var allocation = ManagedAddress.fromAllocation(owner); var literal = ManagedAddress.fromHex("616263");
                Arrays.fill(input, (byte) 0); var cbits = Language.currentState(null).cbits(); var interop = InteropLibrary.getUncached();
                for (var original : List.of(allocation, literal)) {
                    var expected = original.rawBacking(); var escaped = new ArrayList<byte[]>(); escaped.add(original.rawBacking()); escaped.add(original.cbitsBacking());
                    if (original == allocation) escaped.add(owner.wholeBytesForPrimitive());
                    // Writable snapshots cannot mutate the original readonly storage.
                    var oldBuffer = cbits.buffer(original, true);
                    for (var bytes : escaped) Arrays.fill(bytes, (byte) 0); Arrays.fill(original.rawBacking(), (byte) 0); Arrays.fill(original.cbitsBacking(), (byte) 0);
                    assertArrayEquals(expected, original.rawBacking()); assertFalse(interop.isBufferWritable(oldBuffer));
                    assertThrows(UnsupportedMessageException.class, () -> interop.writeBufferByte(oldBuffer, 0, (byte) 0));
                    var observed = new byte[expected.length]; interop.readBuffer(oldBuffer, 0, observed, 0, observed.length); assertArrayEquals(expected, observed);
                    if (original == allocation) {
                        assertThrows(RuntimeFault.class, original::toNativeBits); assertNull(registry.transport(original)); interop.toNative(oldBuffer);
                        assertFalse(interop.isPointer(oldBuffer)); assertThrows(UnsupportedMessageException.class, () -> interop.asPointer(oldBuffer)); assertFalse(original.cbitsSegment().isNative());
                    } else {
                        long bits = original.toNativeBits(); var view = Objects.requireNonNull(registry.transport(original));
                        var nativeMemory = MemorySegment.ofAddress(interop.asPointer(view)).reinterpret(expected.length); assertArrayEquals(expected, nativeMemory.toArray(ValueLayout.JAVA_BYTE));
                        assertTrue(registry.recover(bits).sameLocation(original)); interop.toNative(oldBuffer); assertEquals(bits, interop.asPointer(oldBuffer)); Reference.reachabilityFence(view);
                    }
                    assertSame(oldBuffer, cbits.buffer(original.plus(1), true));
                }
                assertEquals(7L, allocation.readWord8(0)); assertThrows(RuntimeFault.class, () -> allocation.writeAddressElementIndex(0, literal));
                var pointerOwner = ManagedAllocation.mutable(8, 8); pointerOwner.writeAddressByteOffset(0, literal);
                assertThrows(RuntimeFault.class, () -> owner.copyFrom(pointerOwner, 0, 0, 8)); assertThrows(RuntimeFault.class, () -> ManagedAddress.fromAllocation(pointerOwner).toNativeBits());
                // Include empty pinned arrays and both sides of native alignment boundaries.
                // One-past is an alias, but never readable memory.
                var originals = new ArrayList<ManagedAddress>(); for (int i = 0; i <= 32; i++) originals.add(ManagedAddress.fromGuestByteArray(PinnedMemory.allocate(i, 8)));
                var bases = new ArrayList<Long>(); for (var original : originals) bases.add(original.toNativeBits());
                for (int index = 0; index < originals.size(); index++) {
                    var original = originals.get(index); long end = bases.get(index) + original.cbitsSize();
                    assertTrue(registry.recover(end).sameLocation(original.plus(original.cbitsSize()))); assertThrows(RuntimeFault.class, () -> registry.recover(end).readWord8(0));
                    for (int other = 0; other < originals.size(); other++) if (other != index) {
                        boolean outside = Long.compareUnsigned(end, bases.get(other)) < 0 || Long.compareUnsigned(bases.get(index), bases.get(other) + originals.get(other).cbitsSize()) > 0;
                        assertTrue(outside, "Pinned allocations must have disjoint inclusive address ranges");
                    }
                }
                // Immutable hardening must not replace existing mutable aliases.
                var mutable = ManagedAllocation.mutable(8, 8); assertSame(mutable.rawBytesIfPointerFree(), mutable.exposeToNative()); assertSame(mutable.rawBytesIfPointerFree(), mutable.wholeBytesForPrimitive());
            } finally { context.leave(); }
        }
    }

    @Test public void numericalResolutionCannotCrossContextOwnership() {
        try (var first = context(); var second = context()) {
            first.initialize("thc"); second.initialize("thc"); var original = ManagedAddress.fromHex("41"); first.enter(); final long firstBits;
            try { firstBits = original.toNativeBits(); } finally { first.leave(); }
            second.enter(); final long secondBits;
            try {
                var foreign = NativeAddresses.current(null).recover(firstBits); assertEquals(firstBits, foreign.toNativeBits()); assertThrows(RuntimeFault.class, () -> foreign.readWord8(0));
                assertThrows(RuntimeFault.class, foreign::cbitsBacking); secondBits = original.toNativeBits(); assertNotEquals(firstBits, secondBits);
                assertTrue(NativeAddresses.current(null).recover(secondBits).sameLocation(original));
            } finally { second.leave(); }
            first.enter();
            try {
                assertTrue(NativeAddresses.current(null).recover(firstBits).sameLocation(original)); var foreign = NativeAddresses.current(null).recover(secondBits);
                assertEquals(secondBits, foreign.toNativeBits()); assertThrows(RuntimeFault.class, () -> foreign.readWord8(0));
            } finally { first.leave(); }
        }
    }

    @Test public void nativeGhcOracleRetainsAllBitsAndActualPointerAliases() throws Exception {
        var root = new File(System.getProperty("thc.projectRoot")); var prefix = "build/native-addresses";
        var manifest = (Map<String, Object>) Json.parse(Files.readString(new File(root, prefix + "/manifest.json").toPath()));
        assertEquals("9.14.1", manifest.get("ghc"));
        OriginalStdioChecks.hashes(root, manifest.get("inputHashes"), Set.of("compiler/test-fixtures/NativeAddressNative.hs", "test/haskell-fixtures/NativeAddressFixtures.hs"), null);
        OriginalStdioChecks.hashes(root, manifest.get("artifactHashes"), Set.of(prefix + "/oracle.json"), prefix + "/");
        var oracle = (Map<String, Object>) Json.parse(Files.readString(new File(root, prefix + "/oracle.json").toPath()));
        assertEquals(Collections.nCopies(9, true), oracle.get("observations"));
    }
}
