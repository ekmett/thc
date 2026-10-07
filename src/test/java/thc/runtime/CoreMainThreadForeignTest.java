// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import thc.runtime.Unit;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;

/** The declaration is GHC.Internal.TopHandler.runMainIO1's original unsafe ccall. */
class CoreMainThreadForeignTest {
    private Map<String, Object> scalar(String kind, String rep, boolean evaluated) {
        return Map.of("kind", kind, "primReps", rep == null ? List.of() : List.of(rep), "evaluated", evaluated);
    }
    private final Map<String, Object> weak = scalar("object", "BoxedRep (Just Unlifted)", true);
    private final Map<String, Object> state = scalar("void", null, true);
    private final Map<String, Object> closure = scalar("closure", "BoxedRep (Just Lifted)", true);
    private Map<String, Object> tuple(boolean evaluated) {
        return Map.of("kind", "unknown", "primReps", List.of(), "evaluated", evaluated,
            "aggregate", "unboxed-tuple", "components", List.of(state));
    }
    private Map<String, Object> descriptor() {
        return Map.of("schema", 1L, "target", Map.of("kind", "static", "symbol", "rts_setMainThread", "unit", "ghc-internal", "isFunction", true),
            "convention", "ccall", "safety", "unsafe", "arity", 2L, "suppliedArity", 2L,
            "argumentReps", List.of(scalar("object", "BoxedRep (Just Unlifted)", false), scalar("void", null, false)), "resultRep", tuple(false));
    }
    private Map<String, Object> proof() { return Map.of("rep", tuple(false), "foreignCall", descriptor()); }
    private List<Object> variable(String id, Map<String, Object> rep) { return List.of("var", id, Map.of("rep", rep)); }
    private List<Object> application() {
        return List.of("app", variable("original-fcall", closure), List.of(variable("w", weak), variable("s", state)),
            List.of(false, false), false, false, proof());
    }
    private Map<String, Object> module() {
        var body = List.of("lam", List.of(Map.of("id", "w", "name", "w", "lifted", false, "rep", weak),
            Map.of("id", "s", "name", "s", "lifted", false, "rep", state)), application(), Map.of("rep", closure, "resultRep", tuple(false)));
        var binding = Map.of("id", "register", "name", "register", "arity", 2, "lifted", true, "rep", closure, "expr", body);
        return BoxedForeignProofFixtures.withProof(Map.of("bindings", List.of(binding), "constructors", List.of(), "instrument", true));
    }
    private Map<String, Object> changed(Map<?, ?> original, String key, Object value) {
        var changed = new LinkedHashMap<String, Object>(); original.forEach((k, v) -> changed.put((String) k, v));
        changed.put(key, value); return changed;
    }
    @Test void missingOrWrongNominalProofRejectsBeforeMainThreadRegistration() {
        for (var backend : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var threads = Language.currentState().getThreads(); var before = threads.mainThreadRegistration();
                var missing = new LinkedHashMap<>(module()); missing.remove("staticForeignImports");
                java.util.function.Consumer<Map<String,Object>> lower = source -> {
                    var program = backend.equals("ast") ? new Program(language, source) : new BytecodeProgram(language, source);
                    program.entryTarget("register");
                };
                assertThrows(RuntimeFault.class, () -> lower.accept(missing)); assertSame(before, threads.mainThreadRegistration());
                for (var nominal : List.of("declaredType", "normalizedType")) {
                    var wrong = module(); var proof = (Map<?,?>) wrong.get("staticForeignImports");
                    var declaration = (Map<String,Object>) ((List<?>) proof.get("imports")).getFirst();
                    var type = changed((Map<?,?>) declaration.get(nominal), "argument", Map.of("kind", "tycon", "name",
                        Map.of("unit", "ghc-internal", "module", "GHC.Internal.Prim", "occurrence", "ThreadId#", "namespace", "type"), "arguments", List.of()));
                    declaration.put(nominal, type);
                    assertThrows(IllegalArgumentException.class, () -> lower.accept(wrong)); assertSame(before, threads.mainThreadRegistration());
                }
            } finally { context.leave(); }
        }
    }
    @Test void exactOriginalWeakAndStateAbiRejectsNearMisses() {
        var good = proof();
        assertTrue(CoreMainThreadForeign.validate(good, List.of(weak, state), List.of(false, false), tuple(false)));
        CoreMainThreadForeign.validateHeads(application());
        var otherTarget = changed((Map<?, ?>) descriptor().get("target"), "symbol", "stg_sig_install");
        var otherCall = changed(descriptor(), "target", otherTarget);
        assertFalse(CoreMainThreadForeign.validate(changed(good, "foreignCall", otherCall), List.of(weak, state), List.of(false, false), tuple(false)));
        for (var change : new Object[][]{{"schema",1.0},{"convention","capi"},{"safety","safe"},
            {"arity",1L},{"suppliedArity",3L},{"extra",true}})
            assertThrows(RuntimeFault.class, () -> CoreMainThreadForeign.validate(changed(good, "foreignCall", changed(descriptor(), (String) change[0], change[1])),
                List.of(weak, state), List.of(false, false), tuple(false)));
        for (var change : new Object[][]{{"unit","base"},{"kind","dynamic"},{"isFunction",false}})
            assertThrows(RuntimeFault.class, () -> {
                var call = changed(descriptor(), "target", changed((Map<?, ?>) descriptor().get("target"), (String) change[0], change[1]));
                CoreMainThreadForeign.validate(changed(good, "foreignCall", call), List.of(weak, state), List.of(false, false), tuple(false));
            });
        assertThrows(RuntimeFault.class, () -> CoreMainThreadForeign.validate(good, List.of(state, weak), List.of(false, false), tuple(false)));
        assertThrows(RuntimeFault.class, () -> CoreMainThreadForeign.validate(good, List.of(weak, state), List.of(true, false), tuple(false)));
        assertThrows(RuntimeFault.class, () -> CoreMainThreadForeign.validate(good, List.of(weak, state), List.of(false, false), scalar("void", null, true)));
        assertThrows(RuntimeFault.class, () -> CoreMainThreadForeign.validateHead(variable("original-fcall", closure), true));
        var forged = new ArrayList<>(application()); forged.set(1, List.of("prim", "rts_setMainThread"));
        assertThrows(RuntimeFault.class, () -> CoreMainThreadForeign.validateHeads(forged));
    }
    @Test void bothLoadersRegisterTheWeakKeyWithoutRetainingItsValueOrAThreadIdSnapshot() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module(), false, false) : new BytecodeProgram(language, module());
                var runtime = Language.currentState(null); var threads = runtime.getThreads();
                threads.enterCurrent(null, false, true, null);
                try {
                    var target = program.entryTarget("register"); var key = threads.currentIdentity(); var wrongValue = new Object();
                    var first = runtime.getWeaks().make(key, wrongValue, null, null);
                    var second = runtime.getWeaks().make(key, new Object(), null, null);
                    var resultShape = new TupleShape(CoreRepresentations.parse(tuple(false)), language);
                    var destination = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
                    class Caller {
                        void call(Object handle) { call(handle, Unit.INSTANCE); }
                        void call(Object handle, Object token) {
                            var result = Calls.target(target, new Object[]{0L, handle, token});
                            // Even a zero-width State tuple owns a completion loan.
                            resultShape.consume(destination, result, new int[0], 0);
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                            assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
                        }
                    }
                    var caller = new Caller(); caller.call(first);
                    var initial = threads.mainThreadRegistration(); assertNotNull(initial);
                    assertEquals(Thread.currentThread().threadId(), initial.liveJavaId());
                    assertThrows(RuntimeFault.class, () -> caller.call(first, 1L));
                    assertSame(initial, threads.mainThreadRegistration(), "invalid State# must not replace registration");
                    assertThrows(RuntimeFault.class, () -> caller.call(new Object()));
                    assertSame(initial, threads.mainThreadRegistration(), "invalid Weak# must not replace registration");
                    for (int i = 0; i < 3; i++) caller.call(first);
                    var warmed = threads.mainThreadRegistration(); assertNotNull(warmed);
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                    target.getClass().getMethod("waitForCompilation").invoke(target);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), backend);
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    caller.call(second);
                    assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue(), backend + " first installed guest entry");
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), backend);
                    assertSame(target, program.entryTarget("register"));
                    var replacement = threads.mainThreadRegistration(); assertNotNull(replacement); assertNotSame(warmed, replacement);
                    assertEquals(0L, runtime.getWeaks().finalize(first).getFlag());
                    assertNull(initial.liveJavaId()); assertEquals(Thread.currentThread().threadId(), replacement.liveJavaId());
                    assertEquals(0L, runtime.getWeaks().finalize(second).getFlag());
                    assertNull(replacement.liveJavaId(), "registration may retain only the now-dead capability");
                    var handoff = language.getHandoffState().get();
                    assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getArguments().retainedReferences());
                    assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getResults().retainedReferences());
                    assertNull(handoff.getPending());
                } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
            } finally { context.leave(); }
        }
    }
    @Test void fabricatedThreadKeysRejectBeforeReplacingTheMainRegistration() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var runtime = Language.currentState(); var threads = runtime.getThreads(); threads.enterCurrent();
                try {
                    var self = threads.currentIdentity();
                    CoreMainThreadForeign.register(null, runtime.getWeaks().make(self, new Object(), null, null));
                    var original = threads.mainThreadRegistration();
                    var impostor = new GuestThreadId(self.getLogicalId(), threads, 0, Thread.currentThread(), false);
                    var weak = runtime.getWeaks().make(impostor, new Object(), null, null);
                    assertThrows(RuntimeFault.class, () -> CoreMainThreadForeign.register(null, weak));
                    assertSame(original, threads.mainThreadRegistration());
                } finally { threads.leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
}
