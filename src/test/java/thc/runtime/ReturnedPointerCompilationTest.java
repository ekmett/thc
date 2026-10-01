// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;
import static thc.Main.withContextProfile;

public class ReturnedPointerCompilationTest {
    private static final class AddressResult extends RootNode {
        @Child private PackageScalarAccess access;
        AddressResult(Language language, PackageScalarCall call) { super(language); access = new PackageScalarAccess(call); }
        @Override public Object execute(VirtualFrame frame) { return access.executeAddress(frame.getArguments(), thc.runtime.Unit.INSTANCE); }
    }
    private Map<String, Object> module() {
        var address = Map.of("kind", "address", "primReps", List.of("AddrRep"), "evaluated", true);
        var index = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        var word32 = Map.of("kind", "long", "primReps", List.of("Word32Rep"), "evaluated", true);
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var parameters = List.of(Map.of("id", "p", "lifted", false, "rep", address), Map.of("id", "i", "lifted", false, "rep", index));
        var call = List.of("app", List.of("prim", "indexWord32OffAddr#"),
            List.of(List.of("var", "p", Map.of("rep", address)), List.of("var", "i", Map.of("rep", index))),
            List.of(false, false), false, false, Map.of("rep", word32));
        return Map.of("instrument", true, "constructors", List.of(), "bindings", List.of(Map.of("id", "read", "name", "read",
            "arity", 2, "lifted", true, "rep", closure, "expr", List.of("lam", parameters, call, Map.of("rep", closure, "resultRep", word32)))));
    }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private Context context() {
        return withContextProfile(Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000"),
            ContextProfile.SYNCHRONOUS_TEST).build();
    }
    private byte[] helper() throws IOException {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/thc/cbits/package-pointer.bc"))) { return input.readAllBytes(); }
    }
    private long equal(RootCallTarget target, ManagedAddress left, ManagedAddress right) { return (Long) callScalarTestTarget(target, new Object[] {0L, left, right}); }
    private long read(RootCallTarget target, ManagedAddress pointer) { return Integer.toUnsignedLong((Integer) callScalarTestTarget(target, new Object[] {0L, pointer, 0L})); }
    private record Equality(ManagedAddress left, ManagedAddress right, long expected) {}
    private record Reading(ManagedAddress pointer, long expected) {}

    @Test public void dynamicReturnedAliasEqualityRetainsItsFirstCompiledEntry() throws Exception {
        var address = Map.of("kind", "address", "primReps", List.of("AddrRep"), "evaluated", true);
        var result = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var parameters = new ArrayList<Map<String, Object>>(); var arguments = new ArrayList<List<Object>>();
        for (var name : List.of("left", "right")) {
            parameters.add(Map.of("id", name, "lifted", false, "rep", address)); arguments.add(List.of("var", name, Map.of("rep", address)));
        }
        var call = List.of("app", List.of("prim", "eqAddr#"), arguments, List.of(false, false), false, false, Map.of("rep", result));
        Map<String, Object> module = Map.of("instrument", true, "constructors", List.of(), "bindings", List.of(Map.of(
            "id", "equal", "name", "equal", "arity", 2, "lifted", true, "rep", closure,
            "expr", List.of("lam", parameters, call, Map.of("rep", closure, "resultRep", result)))));
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var owner = Language.currentState(); var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var symbol = "thc_package_pointer_offset"; var signature = new PackageScalarSignature(symbol, symbol, List.of("AddrRep", "Int64Rep"), "AddrRep");
                var link = new PackageScalarLink("compiled-equality-control", "unused", "compiled-equality-control", "", helper(), List.of(signature));
                owner.getPackageCbits().link(link);
                var offset = new AddressResult(language, new PackageScalarCall(link, signature)).getCallTarget();
                var original = ManagedAddress.fromByteArray(new byte[16]); var alias = (ManagedAddress) offset.call(original, 4L);
                var repeated = (ManagedAddress) offset.call(original, 4L); assertNotNull(alias.returnedAddress().getBacking());
                var inputs = List.of(new Equality(original.plus(4), alias, 1L), new Equality(alias, original.plus(4), 1L),
                    new Equality(alias, repeated, 1L), new Equality(alias, alias.plus(1), 0L), new Equality(original, original.plus(1), 0L));
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
                var target = program.entryTarget("equal"); owner.getThreads().enterCurrent();
                try {
                    for (int i = 0; i < 5; i++) for (var input : inputs) assertEquals(input.expected, equal(target, input.left, input.right));
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
                    var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
                    for (var input : inputs) {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        assertEquals(input.expected, equal(target, input.left, input.right), backend);
                        assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue(), backend); valid(target);
                    }
                } finally { owner.getThreads().leaveCurrent(); }
            } finally { context.leave(); }
        }
    }

    /** The C helper really returns both managed aliases and unknown native
     * pointers. The independent arena, not a fabricated malloc owner, owns the
     * latter. This transport control does not manufacture GHC import proof. */
    @Test public void dynamicManagedAndExternalReadsRetainTheirFirstCompiledEntry() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var owner = Language.currentState(); var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var signatures = new ArrayList<PackageScalarSignature>();
                for (var operation : List.of("offset", "read_address")) { var symbol = "thc_package_pointer_" + operation; signatures.add(new PackageScalarSignature(symbol, symbol, List.of("AddrRep", "Int64Rep"), "AddrRep")); }
                var link = new PackageScalarLink("compiled-return-control", "unused", "compiled-return-control", "", helper(), signatures);
                owner.getPackageCbits().link(link); var calls = new ArrayList<RootCallTarget>();
                for (var signature : signatures) calls.add(new AddressResult(language, new PackageScalarCall(link, signature)).getCallTarget());
                var original = ManagedAddress.fromByteArray(new byte[16]); original.writeNativeScalar(1, 4, 0x89abcdefL);
                var alias = (ManagedAddress) calls.getFirst().call(original, 4L); assertNotNull(alias.returnedAddress().getBacking());
                try (var arena = Arena.ofConfined()) {
                    var nativeMemory = arena.allocate(16, 8); nativeMemory.set(ValueLayout.JAVA_INT, 0, 0xfedcba98);
                    var slot = owner.getNativeAllocations().malloc(8); final ManagedAddress external;
                    try { slot.writeNativeScalar(0, 8, nativeMemory.address()); external = (ManagedAddress) calls.get(1).call(slot, 0L); }
                    finally { owner.getNativeAllocations().free(slot); }
                    assertNull(external.returnedAddress().getBacking()); assertThrows(RuntimeFault.class, external::availableBytes);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, module()) : new BytecodeProgram(language, module());
                    var target = program.entryTarget("read");
                    var inputs = List.of(new Reading(original.plus(4), 0x89abcdefL), new Reading(alias, 0x89abcdefL), new Reading(external, 0xfedcba98L));
                    owner.getThreads().enterCurrent();
                    try {
                        for (int i = 0; i < 5; i++) for (var input : inputs) assertEquals(input.expected, read(target, input.pointer));
                        target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
                        // Restore only the shared host entry prerequisite, as EntryValue.compile does; no settling guest call.
                        var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target); valid(target);
                        for (var input : inputs) {
                            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            assertEquals(input.expected, read(target, input.pointer), backend);
                            assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue(), backend); valid(target);
                        }
                        assertThrows(RuntimeFault.class, () -> alias.requireRange(12, 1)); assertThrows(RuntimeFault.class, () -> external.requireRange(0, -1));
                        assertThrows(RuntimeFault.class, () -> external.requireRange(Long.MAX_VALUE, 1));
                    } finally { owner.getThreads().leaveCurrent(); }
                }
            } finally { context.leave(); }
        }
    }
}
