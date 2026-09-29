// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** The policy changes creation, never an existing host buffer at a foreign call. */
class NativeByteArrayPolicyTest {
    private static final Map<String, Object> INTEGER = rep("long", "IntRep");
    private static final Map<String, Object> BYTES = rep("object", "BoxedRep (Just Unlifted)");
    private static final Map<String, Object> DATA = rep("object", "BoxedRep (Just Lifted)");
    private static final Map<String, Object> STATE = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
    private static final Map<String, Object> RESULT = Map.of("kind", "unknown", "aggregate", "unboxed-tuple",
        "components", List.of(STATE, BYTES), "primReps", List.of("BoxedRep (Just Unlifted)"), "evaluated", true);
    private static Map<String, Object> rep(String kind, String rep) {
        return Map.of("kind", kind, "primReps", List.of(rep), "evaluated", true);
    }
    private static Map<String, Object> arg(String id, Map<String, Object> rep) {
        return Map.of("id", id, "name", id, "rep", rep, "lifted", rep == DATA);
    }
    private static List<Object> variable(String id, Map<String, Object> rep) {
        return List.of("var", id, Map.of("rep", rep));
    }
    private static List<Object> call(String name, List<?> args, Map<String, Object> result) {
        return List.of("app", List.of("prim", name), args, name.equals("compactAdd#") ? List.of(false, true, false) : Collections.nCopies(args.size(), false),
            true, true, Map.of("rep", result));
    }
    private static Map<String, Object> binding(String name, List<?> args, Object body, Map<String, Object> result) {
        return Map.of("id", name, "name", name, "lifted", true, "expr",
            List.of("lam", args, body, Map.of("resultRep", result)));
    }
    private static List<Object> unpack(Object call) {
        return unpack(call, RESULT, BYTES);
    }
    private static List<Object> unpack(Object call, Map<String, Object> tuple, Map<String, Object> value) {
        return List.of("case", call, "whole", List.of(List.of("data", "T2", List.of("s", "array"),
            variable("array", value), Map.of("binders", List.of(arg("s", STATE), arg("array", value))))),
            Map.of("rep", value, "binder", arg("whole", tuple)));
    }
    private static Map<String, Object> module() {
        var bindings = new ArrayList<Object>();
        var state = List.of("void", Map.of("rep", STATE));
        bindings.add(binding("allocate", List.of(arg("size", INTEGER)), unpack(call("newByteArray#",
            List.of(variable("size", INTEGER), state), RESULT)), BYTES));
        bindings.add(binding("resize", List.of(arg("value", BYTES), arg("size", INTEGER)),
            unpack(call("resizeMutableByteArray#", List.of(variable("value", BYTES), variable("size", INTEGER), state), RESULT)), BYTES));
        var compactResult = Map.<String, Object>of("kind", "unknown", "aggregate", "unboxed-tuple", "components",
            List.of(STATE, DATA), "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        bindings.add(binding("compact", List.of(arg("region", BYTES), arg("value", DATA)),
            unpack(call("compactAdd#", List.of(variable("region", BYTES), variable("value", DATA), state), compactResult), compactResult, DATA), DATA));
        for (String failure : CompactOp.getFailures()) bindings.add(Map.of("id", failure, "name", failure,
            "lifted", true, "expr", List.of("lit", "int", "7")));
        for (String query : List.of("isByteArrayPinned#", "isMutableByteArrayPinned#", "isByteArrayWeaklyPinned#", "isMutableByteArrayWeaklyPinned#"))
            bindings.add(binding(query, List.of(arg("value", BYTES)), call(query, List.of(variable("value", BYTES)), INTEGER), INTEGER));
        return Map.of("bindings", bindings, "instrument", true, "constructors",
            List.of(Map.of("id", "T2", "name", "T2", "kind", "unboxed-tuple", "arity", 2)));
    }
    private static Object run(ExecutableProgram program, String entry, Object... arguments) {
        return Calls.target(program.hostEntryTarget(arguments.length), new Object[]{program.entryValue(entry), arguments});
    }
    @Test void guestCreationQueriesResizeAndAliasesRespectContextPolicyOnBothBackends() throws Exception {
        for (String backend : List.of("ast", "bytecode")) for (boolean nativeBacking : new boolean[]{false, true}) {
            var builder = Context.newBuilder("thc").allowExperimentalOptions(true).allowNativeAccess(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw");
            if (nativeBacking) builder.option("thc.ByteArrayStorage", "native");
            try (var context = builder.build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, module()) : new BytecodeProgram(language, module());
                    var target = program.entryTarget("allocate");
                    assertEquals(true, target.getClass().getMethod("compile", boolean.class).invoke(target, true));
                    var runtime = com.oracle.truffle.api.Truffle.getRuntime();
                    runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    // Invoke the installed target itself, not a cold host adapter's independent split target.
                    var original = (ManagedAllocation) Calls.target(target, new Object[]{0L, 16L});
                    // Stock bytecode may deopt its unspecialized cold entry before body accounting.
                    // Do not replay the allocation to turn that into compiled-body evidence.
                    if (backend.equals("ast")) assertEquals(1L, program.diagnostics().get("compiledEntries"), "native=" + nativeBacking);
                    assertFalse(original.isPinned());
                    assertEquals(nativeBacking, original.nativeSegment() != null);
                    var alias = ManagedAddress.fromGuestByteArray(original).plus(3);
                    original.writeByte(3, 91);
                    assertEquals(91, alias.readWord8(0));
                    assertSame(original, ManagedByteArray.freezeGuest(original));
                    for (String query : List.of("isByteArrayPinned#", "isMutableByteArrayPinned#")) assertEquals(0L, run(program, query, original));
                    for (String query : List.of("isByteArrayWeaklyPinned#", "isMutableByteArrayWeaklyPinned#")) assertEquals(nativeBacking ? 1L : 0L, run(program, query, original));
                    if (nativeBacking) {
                        long bits = alias.toNativeBits();
                        assertTrue(NativeAddresses.current(null).recover(bits).sameLocation(alias));
                        assertSame(original, NativeAddresses.current(null).recover(bits).cbitsOwner());
                    } else assertThrows(RuntimeFault.class, alias::toNativeBits);
                    var grown = (ManagedAllocation) run(program, "resize", original, 32L);
                    assertNotSame(original, grown);
                    assertFalse(grown.isPinned());
                    assertEquals(nativeBacking, grown.nativeSegment() != null);
                    assertEquals(91, grown.readByte(3));
                    assertSame(grown, run(program, "resize", grown, 8L));
                    assertEquals(8, grown.getSize());
                    assertThrows(RuntimeFault.class, () -> grown.readByte(8));
                    var pinned = PinnedMemory.allocate(16, 64);
                    assertEquals(1L, run(program, "isByteArrayPinned#", pinned));
                    assertEquals(1L, run(program, "isByteArrayWeaklyPinned#", pinned));
                    assertEquals(0L, pinned.nativeSegment().address() & 63);
                    var region = new ManagedCompact(Language.currentState().compactRegions, 1024);
                    var compact = (ManagedAllocation) run(program, "compact", region, grown);
                    assertNotSame(grown, compact);
                    assertFalse(compact.isPinned());
                    assertEquals(nativeBacking, compact.nativeSegment() != null);
                    assertEquals(91, compact.readByte(3));
                    assertThrows(RuntimeFault.class, () -> compact.writeByte(3, 7));
                    assertThrows(GuestException.class, () -> run(program, "compact", region, pinned));
                    var pinnedGrowth = (ManagedAllocation) run(program, "resize", pinned, 32L);
                    assertFalse(pinnedGrowth.isPinned());
                    assertEquals(nativeBacking, pinnedGrowth.nativeSegment() != null);
                    // Raw host ingress is never silently promoted under either policy.
                    var host = new byte[8];
                    assertSame(host, ManagedByteArray.freezeGuest(host));
                    assertThrows(RuntimeFault.class, ManagedAddress.fromGuestByteArray(host)::toNativeBits);
                    assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                } finally { context.leave(); }
            }
        }
    }
    @ParameterizedTest
    @CsvSource({"true,,true", "true,heap,false", "false,native,false"})
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void launcherDefaultsToNativeButHonorsHeapWithoutChangingEmbeddings(boolean launcher, String setting, boolean nativeBacking) {
        var previous = System.getProperty("thc.byteArrayStorage");
        try {
            if (setting == null) System.clearProperty("thc.byteArrayStorage");
            else System.setProperty("thc.byteArrayStorage", setting);
            try (var context = launcher ? thc.Main.executionContext() : Context.newBuilder("thc").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (String backend : List.of("ast", "bytecode")) {
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, module()) : new BytecodeProgram(language, module());
                        var bytes = (ManagedAllocation) run(program, "allocate", 16L);
                        assertEquals(nativeBacking, bytes.hasNativeStorage(), backend);
                        assertFalse(bytes.isPinned(), backend);
                        bytes.writeByte(3, 91);
                        assertEquals(91, ManagedAddress.fromGuestByteArray(bytes).plus(3).readWord8(0), backend);
                    }
                } finally { context.leave(); }
            }
        } finally {
            if (previous == null) System.clearProperty("thc.byteArrayStorage");
            else System.setProperty("thc.byteArrayStorage", previous);
        }
    }
    @Test void nativeStorageRequiresNativeAuthorityAtContextCreation() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("thc.ByteArrayStorage", "native").build()) {
            var failure = assertThrows(RuntimeException.class, () -> context.initialize("thc"));
            assertTrue(failure.getMessage().contains("requires native access"), failure.getMessage());
        }
    }
    @Test void nativeOwnerOutlivesContextWhileRegistriesAndPointerCellBoundariesStayChecked() {
        ManagedAllocation retained;
        NativeAddresses registry;
        long bits;
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).allowNativeAccess(true).option("thc.ByteArrayStorage", "native").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                retained = (ManagedAllocation) run(new Program(language, module()), "allocate", 16L);
                retained.writeByte(3, 73);
                var address = ManagedAddress.fromGuestByteArray(retained);
                bits = address.toNativeBits();
                registry = NativeAddresses.current(null);
                assertSame(retained, registry.recover(bits + 3).cbitsOwner());
                var cells = (ManagedAllocation) run(new Program(language, module()), "allocate", 16L);
                cells.writeAddressByteOffset(0, address);
                assertThrows(RuntimeFault.class, cells::exposeSegment);
                assertTrue(cells.readAddressByteOffset(0).sameLocation(address));
            } finally { context.leave(); }
        }
        assertEquals(73, retained.readByte(3));
        assertEquals(bits, retained.nativeSegment().address());
        assertThrows(RuntimeFault.class, () -> registry.recover(bits));
    }
}
