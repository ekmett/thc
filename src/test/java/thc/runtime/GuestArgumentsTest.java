// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GuestArgumentsTest {
    private static Context context(String... arguments) {
        return Context.newBuilder("thc").allowNativeAccess(true).arguments("thc", arguments).build();
    }

    private static void inside(Runnable body) {
        assumeTrue(System.getProperty("os.name").equals("Linux") &&
            Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")));
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try { body.run(); } finally { context.leave(); }
        }
    }

    private record ArgumentImage(int count, ManagedAddress vector) {}

    private static ArgumentImage get(GuestArguments arguments) {
        var allocations = Language.currentState(null).getNativeAllocations();
        var count = allocations.malloc(4);
        var vector = allocations.malloc(8);
        try {
            arguments.get(count, vector);
            return new ArgumentImage(count.withNativeSegmentInt(
                segment -> segment.get(ValueLayout.JAVA_INT_UNALIGNED, 0)), vector.readAddressElementIndex(0));
        } finally { allocations.free(count); allocations.free(vector); }
    }

    private static String text(ManagedAddress address) {
        var bytes = new byte[(int) address.cStringLength()];
        for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) address.readWord8(index);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static List<String> values(ArgumentImage image) {
        assertSame(ManagedAddress.nullAddress(), image.vector().readAddressElementIndex(image.count()));
        var values = new ArrayList<String>();
        for (int index = 0; index < image.count(); index++) values.add(text(image.vector().readAddressElementIndex(index)));
        return values;
    }

    @Test void nativeImagePreservesProgramNameEmptyUnicodeAndOptions() {
        inside(() -> {
            var arguments = Language.currentState(null).getArguments();
            arguments.initialize("program", new String[]{"", "\u03bb\uD834\uDD1E", "--help", "--"});
            var image = get(arguments);
            assertEquals(List.of("program", "", "\u03bb\uD834\uDD1E", "--help", "--"), values(image));
            image.vector().withNativeSegmentInt(pointers -> {
                assertEquals(image.vector().readAddressElementIndex(0).toNativeBits(),
                    pointers.get(ValueLayout.JAVA_LONG_UNALIGNED, 0));
                return 0;
            });
            arguments.get(ManagedAddress.nullAddress(), ManagedAddress.nullAddress());
            assertThrows(RuntimeFault.class, () -> arguments.initialize("again", new String[0]));
        });
    }

    @Test void setCopiesBeforeRetiringEvenSelfAliasedInputAndAllowsEmptyArgv() {
        inside(() -> {
            var arguments = Language.currentState(null).getArguments();
            arguments.initialize("original", new String[]{"one", "two"});
            var old = get(arguments);
            var oldString = old.vector().readAddressElementIndex(1);
            arguments.set(2, old.vector().plus(8));
            assertEquals(List.of("one", "two"), values(get(arguments)));
            assertThrows(RuntimeFault.class, () -> old.vector().readAddressElementIndex(0));
            assertThrows(RuntimeFault.class, () -> oldString.readWord8(0));
            arguments.set(0, ManagedAddress.nullAddress());
            assertEquals(List.of(), values(get(arguments)));
        });
    }

    @Test void setCopiesManagedInputsAndRejectsInvalidCountsAndUnterminatedStrings() {
        inside(() -> {
            var arguments = Language.currentState(null).getArguments();
            var storage = ManagedAllocation.mutable(16, 8);
            var vector = ManagedAddress.fromAllocation(storage);
            vector.writeAddressElementIndex(0, ManagedAddress.fromHex("6100"));
            arguments.set(1, vector);
            vector.writeAddressElementIndex(0, ManagedAddress.fromHex("6200"));
            assertEquals(List.of("a"), values(get(arguments)));
            assertThrows(RuntimeFault.class, () -> arguments.set(-1, vector));
            assertThrows(RuntimeFault.class, () -> arguments.set((long) Integer.MAX_VALUE + 1, vector));
            assertThrows(RuntimeFault.class, () -> arguments.set(3, vector));
            var unterminated = ManagedAddress.fromAllocation(ManagedAllocation.mutable(2, 8));
            unterminated.writeWord8(0, 97); unterminated.writeWord8(1, 98);
            vector.writeAddressElementIndex(0, unterminated);
            assertThrows(RuntimeFault.class, () -> arguments.set(1, vector));
            assertEquals(List.of("a"), values(get(arguments)));
        });
    }

    @Test void contextIsolationAndDisposalInvalidateNativeArgumentAliases() {
        inside(() -> {
            var outer = Language.currentState(null).getArguments();
            var outerImage = get(outer);
            ManagedAddress escaped;
            try (var inner = context("inner")) {
                inner.initialize("thc"); inner.enter();
                try {
                    assertThrows(RuntimeFault.class, () -> get(outer));
                    assertThrows(RuntimeFault.class, () -> outerImage.vector().readAddressElementIndex(0));
                    var image = get(Language.currentState(null).getArguments());
                    assertEquals(List.of("", "inner"), values(image));
                    escaped = image.vector();
                } finally { inner.leave(); }
            }
            assertThrows(RuntimeFault.class, () -> escaped.readAddressElementIndex(0));
            assertEquals(List.of(""), values(get(outer)));
        });
    }

    @Test void pointerCellsKeepRangeOwnershipAndOpaqueUnknownBitsChecks() {
        inside(() -> {
            var allocations = Language.currentState(null).getNativeAllocations();
            var vector = allocations.malloc(16);
            var payload = allocations.malloc(2);
            payload.writeWord8(0, 65); payload.writeWord8(1, 0);
            vector.writeAddressElementIndex(0, payload);
            assertEquals("A", text(vector.readAddressElementIndex(0)));
            assertThrows(RuntimeFault.class, () -> vector.readAddressElementIndex(Long.MAX_VALUE));
            assertThrows(RuntimeFault.class, () -> vector.writeAddressElementIndex(2, payload));
            vector.writeNativeScalar(1, 8, 17);
            var unknown = vector.readAddressElementIndex(1);
            assertEquals(17L, unknown.toNativeBits());
            assertThrows(RuntimeFault.class, () -> unknown.readWord8(0));
            allocations.free(payload);
            assertThrows(RuntimeFault.class, () -> vector.readAddressElementIndex(0).readWord8(0));
            allocations.free(vector);
            assertThrows(RuntimeFault.class, () -> vector.readAddressElementIndex(0));
        });
    }

    @Test void invalidOutputStorageAndNulCannotMutateTheArguments() {
        inside(() -> {
            var arguments = Language.currentState(null).getArguments();
            var nil = ManagedAddress.nullAddress();
            assertThrows(RuntimeFault.class, () -> arguments.get(ManagedAddress.fromHex("00000000"), nil));
            arguments.initialize("program", new String[]{"bad\u0000argument"});
            assertThrows(RuntimeFault.class, () -> arguments.get(nil, nil));
            arguments.initialize("valid", new String[0]);
            assertEquals(List.of("valid"), values(get(arguments)));
        });
    }

    @Test void launcherSuffixKeepsOpaqueArgumentsAndLegacyNoArgumentCalls() {
        assertEquals("thc", Main.launcherArguments(new String[]{"--run-io", "modules", "entry"}, 3).programName());
        var parsed = Main.launcherArguments(new String[]{"--run-io", "modules", "entry", "--", "program", "", "--", "--help"}, 3);
        assertEquals("program", parsed.programName());
        assertArrayEquals(new String[]{"", "--", "--help"}, parsed.arguments());
        assertThrows(IllegalArgumentException.class, () -> Main.launcherArguments(new String[]{"a", "b", "c", "--"}, 3));
        assertThrows(IllegalArgumentException.class, () -> Main.launcherArguments(new String[]{"a", "b", "c", "bad", "name"}, 3));
    }

    private static Map<String, Object> scalar(String primitive, boolean evaluated) {
        return Map.of("kind", primitive == null ? "void" : primitive.equals("AddrRep") ? "address" : "long",
            "primReps", primitive == null ? List.of() : List.of(primitive), "evaluated", evaluated);
    }

    @Test void argumentForeignAdmissionChecksTheActualCAbiAndStateTuple() {
        // Synthetic admission controls; ordinary end-to-end tests supply genuine
        // GHC FCallIds through the full installed interface exporter.
        Map<String, Object> result = Map.of("kind", "unknown", "primReps", List.of(), "evaluated", true,
            "aggregate", "unboxed-tuple", "components", List.of(scalar(null, true)));
        for (var operation : RtsArgumentsOp.values()) {
            var unevaluated = new LinkedHashMap<>(result);
            unevaluated.put("evaluated", false);
            Map<String, Object> descriptor = Map.of("schema", 1, "target", Map.of("kind", "static",
                "symbol", operation.getSymbol(), "unit", "ghc-internal", "isFunction", true),
                "convention", "ccall", "safety", "unsafe", "arity", 3, "suppliedArity", 3,
                "argumentReps", operation.getArguments().stream().map(rep -> scalar(rep, false)).toList(),
                "resultRep", unevaluated);
            var arguments = operation.getArguments().stream().map(rep -> scalar(rep, true)).toList();
            assertEquals(operation, CoreRtsArgumentsForeign.validate(
                Map.of("foreignCall", descriptor, "rep", result), arguments, List.of(false, false, false), result));
            for (var change : List.of(Map.entry("safety", (Object) "safe"), Map.entry("arity", (Object) 2),
                    Map.entry("resultRep", (Object) scalar(null, true)), Map.entry("argumentReps", (Object)
                        List.of(scalar("IntRep", false), scalar("AddrRep", false), scalar(null, false))))) {
                var declaration = new LinkedHashMap<>(descriptor);
                declaration.put(change.getKey(), change.getValue());
                assertThrows(RuntimeFault.class, () -> CoreRtsArgumentsForeign.validate(
                    Map.of("foreignCall", declaration, "rep", result), arguments, List.of(false, false, false), result));
            }
        }
    }
}
