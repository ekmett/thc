// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import kotlin.Unit;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.MainKt;
import static org.junit.jupiter.api.Assertions.*;

/** Structural/carrier controls; original-import evidence is acquired separately from installed Core. */
class CoreBoundThreadForeignTest {
    private Map<String, Object> scalar(String kind, String rep, boolean evaluated) {
        return Map.of("kind", kind, "primReps", rep == null ? List.of() : List.of(rep), "evaluated", evaluated);
    }
    private final Map<String, Object> state = scalar("void", null, true);
    private final Map<String, Object> integer = scalar("long", "IntRep", true);
    private final Map<String, Object> closure = scalar("closure", "BoxedRep (Just Lifted)", true);
    private Map<String, Object> tuple() {
        return Map.of("kind", "unknown", "primReps", List.of("IntRep"), "evaluated", false,
            "aggregate", "unboxed-tuple", "components", List.of(state, integer));
    }
    private Map<String, Object> descriptor() {
        return Map.of("schema", 1L, "target", Map.of("kind", "static", "symbol", "rtsSupportsBoundThreads", "unit", "ghc-internal", "isFunction", true),
            "convention", "ccall", "safety", "unsafe", "arity", 1L, "suppliedArity", 1L,
            "argumentReps", List.of(scalar("void", null, false)), "resultRep", tuple());
    }
    private Map<String, Object> proof() { return Map.of("rep", tuple(), "foreignCall", descriptor()); }
    private List<Object> variable(String id, Map<String, Object> rep) { return List.of("var", id, Map.of("rep", rep)); }
    private List<Object> application() {
        return List.of("app", variable("structural-fcall", closure), List.of(variable("s", state)), List.of(false), false, false, proof());
    }
    private Map<String, Object> module(Map<String, Object> stored, List<Object> expression) {
        var body = List.of("lam", List.of(Map.of("id", "s", "name", "s", "lifted", false, "rep", stored)), expression,
            Map.of("rep", closure, "resultRep", tuple()));
        return Map.of("bindings", List.of(Map.of("id", "query", "name", "query", "arity", 1, "lifted", true, "rep", closure, "expr", body)),
            "constructors", List.of(), "instrument", true);
    }
    private Map<String, Object> changed(Map<?, ?> original, String key, Object value) {
        var changed = new LinkedHashMap<String, Object>(); original.forEach((k, v) -> changed.put((String) k, v));
        changed.put(key, value); return changed;
    }
    @Test void exactStateAndHsBoolCertificateRejectsNearMisses() {
        var validator = CoreBoundThreadForeign.INSTANCE;
        assertTrue(validator.validate(proof(), List.of(state), List.of(false), tuple(), false));
        validator.validateHeads(application());
        for (var symbol : List.of("isCurrentThreadBound", "forkOS", "prefix_rtsSupportsBoundThreads")) {
            var call = changed(descriptor(), "target", changed((Map<?, ?>) descriptor().get("target"), "symbol", symbol));
            assertFalse(validator.validate(changed(proof(), "foreignCall", call), List.of(state), List.of(false), tuple(), false));
        }
        for (var change : new Object[][]{{"schema",true},{"schema",1.0},{"convention","capi"},{"safety","safe"},
            {"arity",0L},{"suppliedArity",2L},{"extra",true}})
            assertThrows(RuntimeFault.class, () -> validator.validate(changed(proof(), "foreignCall", changed(descriptor(), (String) change[0], change[1])),
                List.of(state), List.of(false), tuple(), false));
        for (var change : new Object[][]{{"unit","base"},{"kind","dynamic"},{"isFunction",false},{"extra",1}})
            assertThrows(RuntimeFault.class, () -> {
                var call = changed(descriptor(), "target", changed((Map<?, ?>) descriptor().get("target"), (String) change[0], change[1]));
                validator.validate(changed(proof(), "foreignCall", call), List.of(state), List.of(false), tuple(), false);
            });
        for (var wrong : List.of(changed(tuple(), "components", List.of(integer)),
            changed(tuple(), "components", List.of(state, scalar("long", "Int32Rep", true))),
            changed(tuple(), "primReps", List.of("WordRep")), scalar("data", "BoxedRep (Just Lifted)", true))) {
            assertThrows(RuntimeFault.class, () -> validator.validate(proof(), List.of(state), List.of(false), wrong, false));
            assertThrows(RuntimeFault.class, () -> validator.validate(changed(proof(), "foreignCall", changed(descriptor(), "resultRep", wrong)),
                List.of(state), List.of(false), tuple(), false));
        }
        assertThrows(RuntimeFault.class, () -> validator.validate(proof(), List.of(integer), List.of(false), tuple(), false));
        assertThrows(RuntimeFault.class, () -> validator.validate(proof(), List.of(state), List.of(true), tuple(), false));
        assertThrows(RuntimeFault.class, () -> validator.validate(changed(proof(), "foreignCall", changed(descriptor(), "argumentReps", List.of(state))),
            List.of(state), List.of(false), tuple(), false));
        assertThrows(RuntimeFault.class, () -> validator.validateHead(variable("structural-fcall", closure), true));
        assertThrows(RuntimeFault.class, () -> {
            var application = new ArrayList<>(application()); application.set(1, List.of("prim", "rtsSupportsBoundThreads"));
            validator.validateHeads(application);
        });
    }
    @Test void originalAllocationGetterKeepsItsDistinctPrimAndInt64Abi() {
        var result = changed(changed(tuple(), "primReps", List.of("Int64Rep")), "components", List.of(state, scalar("long", "Int64Rep", true)));
        var call = new LinkedHashMap<>(descriptor());
        call.put("target", Map.of("kind", "static", "symbol", "stg_getThreadAllocationCounterzh", "unit", "ghc-internal", "isFunction", true));
        call.put("convention", "prim"); call.put("safety", "safe"); call.put("resultRep", result);
        var proof = Map.of("rep", result, "foreignCall", call);
        assertTrue(CoreBoundThreadForeign.INSTANCE.validate(proof, List.of(state), List.of(false), result, true));
        assertFalse(CoreBoundThreadForeign.INSTANCE.validate(proof, List.of(state), List.of(false), result, false));
        for (var change : new Object[][]{{"convention","ccall"},{"safety","unsafe"},{"resultRep",tuple()}})
            assertThrows(RuntimeFault.class, () -> CoreBoundThreadForeign.INSTANCE.validate(changed(proof, "foreignCall", changed(call, (String) change[0], change[1])),
                List.of(state), List.of(false), result, true));
    }
    @Test void bothLoadersCheckStoredStateAndRuntimeCarrierBeforeWritingTypedZero() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = MainKt.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                java.util.function.Function<Map<String, Object>, ExecutableProgram> load = value -> backend.equals("ast")
                    ? new Program(language, value, false, false) : new BytecodeProgram(language, value);
                assertThrows(RuntimeFault.class, () -> load.apply(module(integer, application())));
                var forged = new ArrayList<>(application()); forged.set(2, List.of(List.of("lit", "int", "7", Map.of("rep", state))));
                assertThrows(RuntimeFault.class, () -> load.apply(module(state, forged)));
                var program = load.apply(module(state, application())); var target = program.entryTarget("query");
                var builder = FrameDescriptor.newBuilder(); builder.addSlot(FrameSlotKind.Long, null, null);
                var destination = Truffle.getRuntime().createVirtualFrame(new Object[0], builder.build());
                var shape = new TupleShape(CoreRepresentations.INSTANCE.parse(tuple()), language);
                Consumer<Object> call = token -> {
                    var result = Calls.target(target, new Object[]{0L, token});
                    shape.consume(destination, result, new int[]{0}, 0); assertEquals(0L, destination.getLong(0));
                };
                assertThrows(RuntimeFault.class, () -> call.accept(1L));
                for (int i = 0; i < 3; i++) call.accept(Unit.INSTANCE);
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), backend);
                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                call.accept(Unit.INSTANCE);
                assertEquals(before + 1L, ((Number) program.diagnostics().get("compiledEntries")).longValue(), backend);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), backend);
                assertSame(target, program.entryTarget("query"));
                assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                var handoff = language.getHandoffState$org_intelligence_thc().get();
                assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getArguments().retainedReferences());
                assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getResults().retainedReferences());
                assertNull(handoff.getPending());
            } finally { context.leave(); }
        }
    }
}
