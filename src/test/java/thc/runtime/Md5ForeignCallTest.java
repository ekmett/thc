// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kotlin.Unit;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic ABI execution controls; not original public Fingerprint execution. */
class Md5ForeignCallTest {
    private Map<String, Object> scalar(String rep, boolean evaluated) {
        String kind = rep == null ? "void" : rep.equals("AddrRep") ? "address" : rep.equals("BoxedRep (Just Lifted)") ? "closure" : "long";
        return Map.of("kind", kind, "primReps", rep == null ? List.of() : List.of(rep), "evaluated", evaluated);
    }
    private final Map<String, Object> state = scalar(null, true), integer = scalar("IntRep", true), closure = scalar("BoxedRep (Just Lifted)", true);
    private Map<String, Object> tuple(boolean evaluated) {
        return Map.of("kind", "unknown", "primReps", List.of(), "aggregate", "unboxed-tuple", "components", List.of(state), "evaluated", evaluated);
    }
    private Map<String, Object> module(Md5ForeignOp operation, String unit, boolean descriptor, Object foreignId, Map<String, Object> headProof) {
        List<String> reps = switch (operation) {
            case INIT -> Arrays.asList("AddrRep", null); case UPDATE -> Arrays.asList("AddrRep", "AddrRep", "Int32Rep", null);
            case FINAL -> Arrays.asList("AddrRep", "AddrRep", null);
        };
        var formals = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < reps.size(); i++) formals.add(Map.of("id", "p" + i, "lifted", false, "rep", scalar(reps.get(i), true)));
        var metadata = new LinkedHashMap<String, Object>(); metadata.put("rep", tuple(true));
        if (descriptor) metadata.put("foreignCall", Map.of("schema", 1L,
            "target", Map.of("kind", "static", "symbol", operation.getSymbol(), "unit", unit, "isFunction", true),
            "convention", "ccall", "safety", "unsafe", "arity", (long) reps.size(), "suppliedArity", (long) reps.size(),
            "argumentReps", reps.stream().map(rep -> scalar(rep, false)).toList(), "resultRep", tuple(false)));
        var call = List.of("app", Arrays.asList("var", foreignId, Collections.singletonMap("rep", headProof)),
            formals.stream().map(formal -> List.of("var", formal.get("id"), Map.of("rep", formal.get("rep")))).toList(),
            Collections.nCopies(reps.size(), false), false, false, metadata);
        var body = List.of("case", call, "pair", List.of(List.of("data", "T1", List.of("s"),
            List.of("lit", "int", "17", Map.of("rep", integer)), Map.of("binders", List.of(Map.of("id", "s", "lifted", false, "rep", state))))),
            Map.of("rep", integer, "binder", Map.of("id", "pair", "lifted", false, "rep", tuple(true))));
        return Map.of("instrument", true, "constructors", List.of(Map.of("id", "T1", "kind", "unboxed-tuple", "arity", 1, "tag", 1)),
            "bindings", List.of(Map.of("id", "root", "name", "root", "arity", reps.size(), "lifted", true, "rep", closure,
                "expr", List.of("lam", formals, body, Map.of("rep", closure, "resultRep", integer)))));
    }
    private Context context() {
        return Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build();
    }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void released(Language language) {
        var handoff = language.getHandoffState().get();
        assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
        assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
    }
    @Test void compiledClosedCallsUseVisibleStorageAndEraseOnlyTheStateResult() throws Exception {
        for (var backend : List.of("ast", "bytecode")) for (var operation : Md5ForeignOp.values()) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var module = module(operation, "ghc-internal", true, "foreign-id", closure);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module, false, false) : new BytecodeProgram(language, module);
                var entry = program.entryTarget("root");
                class Caller {
                    Object call(ManagedAddress first, ManagedAddress second, long size) { return call(first, second, size, Unit.INSTANCE); }
                    Object call(ManagedAddress first, ManagedAddress second, long size, Object token) {
                        Object length;
                        if (size >= Integer.MIN_VALUE && size <= Integer.MAX_VALUE) length = (int) size; else length = size;
                        Object[] arguments = switch (operation) {
                            case INIT -> new Object[]{0L, first, token}; case UPDATE -> new Object[]{0L, first, second, length, token};
                            case FINAL -> new Object[]{0L, first, second, token};
                        };
                        return NarrowIntegerCarrierTestKt.callScalarTestTarget(entry, arguments);
                    }
                    void positive(boolean compiled) throws Exception {
                        for (int size : new int[]{0,1,15,55,56,63,64,65,129}) {
                            var bytes = new byte[88]; Arrays.fill(bytes, (byte) 0xa5);
                            var input = new byte[size]; for (int i = 0; i < size; i++) input[i] = (byte) (i * 73 + 127);
                            var output = new byte[16]; Arrays.fill(output, (byte) 0xd3);
                            var address = ManagedAddress.Companion.fromByteArray(bytes); var source = ManagedAddress.Companion.fromByteArray(input);
                            var result = ManagedAddress.Companion.fromByteArray(output);
                            if (operation != Md5ForeignOp.INIT) ManagedMd5.INSTANCE.init(address);
                            if (operation == Md5ForeignOp.FINAL) ManagedMd5.INSTANCE.update(address, source, size);
                            var expected = bytes.clone(); var expectedOutput = output.clone();
                            switch (operation) {
                                case INIT -> ManagedMd5.INSTANCE.init(ManagedAddress.Companion.fromByteArray(expected));
                                case UPDATE -> ManagedMd5.INSTANCE.update(ManagedAddress.Companion.fromByteArray(expected), source, size);
                                case FINAL -> ManagedMd5.INSTANCE.finish(ManagedAddress.Companion.fromByteArray(expectedOutput), ManagedAddress.Companion.fromByteArray(expected));
                            }
                            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            assertEquals(17L, call(operation == Md5ForeignOp.FINAL ? result : address, operation == Md5ForeignOp.FINAL ? address : source, size));
                            assertArrayEquals(expected, bytes); assertArrayEquals(expectedOutput, output);
                            if (compiled) { assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue()); valid(entry); }
                            released(language);
                        }
                    }
                }
                var caller = new Caller(); caller.positive(false);
                entry.getClass().getMethod("compile", boolean.class).invoke(entry, true); valid(entry); caller.positive(true);
                var bytes = new byte[88]; Arrays.fill(bytes, (byte) 0xa5); var output = new byte[16]; Arrays.fill(output, (byte) 0xd3);
                var beforeBytes = bytes.clone(); var beforeOutput = output.clone();
                var badState = assertThrows(RuntimeFault.class, () -> caller.call(ManagedAddress.Companion.fromByteArray(operation == Md5ForeignOp.FINAL ? output : bytes),
                    ManagedAddress.Companion.fromByteArray(bytes), Integer.MAX_VALUE, 7L));
                assertTrue(badState.getMessage().contains("zero-width scalar carrier"), badState.getMessage());
                assertArrayEquals(beforeBytes, bytes); assertArrayEquals(beforeOutput, output); released(language);
                if (operation == Md5ForeignOp.UPDATE) for (long length : new long[]{-1,1L << 32,Long.MAX_VALUE}) {
                    assertThrows(RuntimeFault.class, () -> caller.call(ManagedAddress.Companion.fromByteArray(bytes), ManagedAddress.Companion.fromByteArray(output), length));
                    assertArrayEquals(beforeBytes, bytes); assertArrayEquals(beforeOutput, output); released(language);
                }
            } finally { context.leave(); }
        }
    }
    @Test void mainUnitAndMissingDescriptorsNeverSelectTheAdapter() {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                java.util.function.Function<Map<String, Object>, ExecutableProgram> load = module -> backend.equals("ast")
                    ? new Program(language, module, false, false) : new BytecodeProgram(language, module);
                for (var operation : Md5ForeignOp.values()) {
                    assertThrows(RuntimeFault.class, () -> load.apply(module(operation, "main", true, "foreign-id", closure)));
                    assertThrows(UnsupportedCore.class, () -> load.apply(module(operation, "ghc-internal", false, "foreign-id", closure)));
                    // A descriptor cannot bypass ordinary lexical/global Haskell definitions.
                    for (var id : Arrays.asList(null, "", 3L, "p0", "root"))
                        assertThrows(RuntimeFault.class, () -> load.apply(module(operation, "ghc-internal", true, id, closure)));
                    var unevaluated = new LinkedHashMap<>(closure); unevaluated.put("evaluated", false);
                    var aggregate = new LinkedHashMap<>(closure); aggregate.put("components", List.of());
                    for (var proof : Arrays.asList(null, state, unevaluated, aggregate))
                        assertThrows(RuntimeFault.class, () -> load.apply(module(operation, "ghc-internal", true, "foreign-id", proof)));
                }
            } finally { context.leave(); }
        }
    }
}
