// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;

class CoreThreadIdForeignTest {
    private static Map<String,Object> scalar(String rep, boolean evaluated) {
        return Map.of("kind", rep == null ? "void" : rep.startsWith("BoxedRep") ? "object" : "long",
            "primReps", rep == null ? List.of() : List.of(rep), "evaluated", evaluated);
    }
    private static List<Object> call(String symbol) {
        int count = symbol.equals("rts_getThreadId") ? 1 : 2;
        String result = switch (symbol) { case "eq_thread" -> "Word8Rep"; case "cmp_thread" -> "Int32Rep"; default -> "Word64Rep"; };
        var declared = new ArrayList<Map<String,Object>>();
        for (int i = 0; i < count; i++) declared.add(scalar("BoxedRep (Just Unlifted)", false));
        declared.add(scalar(null, false));
        var tuple = Map.of("kind", "unknown", "primReps", List.of(result), "aggregate", "unboxed-tuple",
            "components", List.of(scalar(null, true), scalar(result, true)), "evaluated", false);
        var descriptor = Map.of("schema", 1L, "target", Map.of("kind", "static", "symbol", symbol,
            "unit", "ghc-internal", "isFunction", true), "convention", "ccall", "safety", "unsafe",
            "arity", (long) count + 1, "suppliedArity", (long) count + 1, "argumentReps", declared, "resultRep", tuple);
        return List.of("app", List.of("var", "foreign", Map.of("rep", OriginalStdioFixtures.closure())),
            List.of(), java.util.Collections.nCopies(count + 1, false), false, false, Map.of("foreignCall", descriptor, "rep", tuple));
    }
    private static ExecutableProgram program(Language language, String backend, String symbol) {
        var raw = OriginalStdioChecks.rawModule(call(symbol), Map.of("sourceFiles", List.of(), "sourceSpans", List.of()));
        var entry = (Map<String,Object>) ((List<?>) raw.get("bindings")).getFirst();
        var lambda = (List<?>) entry.get("expr"); var body = new ArrayList<Object>((List<?>) lambda.get(2));
        var foreign = (List<?>) body.get(1); var tuple = ((Map<?,?>) foreign.get(6)).get("rep");
        var formals = (List<Map<String,Object>>) lambda.get(1); int count = formals.size();
        var components = formals.stream().map(formal -> formal.get("rep")).toList();
        var primReps = new ArrayList<Object>();
        for (var component : components) primReps.addAll((List<?>) ((Map<?,?>) component).get("primReps"));
        var input = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components", components, "primReps", primReps, "evaluated", true);
        var leafBody = List.of("case", List.of("var", "packed", Map.of("rep", input)), "unpacked",
            List.of(List.of("data", "T" + count, formals.stream().map(formal -> formal.get("id")).toList(), foreign, Map.of("binders", formals))),
            Map.of("rep", tuple, "binder", Map.of("id", "unpacked", "name", "unpacked", "lifted", false, "rep", input)));
        var leaf = Map.of("id", "foreignLeaf", "name", "foreignLeaf", "arity", 1, "lifted", true,
            "rep", OriginalStdioFixtures.closure(), "expr", List.of("lam", List.of(Map.of("id", "packed", "name", "packed", "lifted", false, "rep", input)), leafBody,
                Map.of("rep", OriginalStdioFixtures.closure(), "resultRep", tuple)));
        var application = new ArrayList<Object>(foreign);
        application.set(1, List.of("var", "foreignLeaf", Map.of("rep", OriginalStdioFixtures.closure())));
        application.set(2, List.of(List.of("app", List.of("con", "T" + count, count), foreign.get(2), java.util.Collections.nCopies(count, false), false, false, Map.of("rep", input))));
        application.set(3, List.of(false));
        application.set(6, Map.of("rep", tuple)); body.set(1, application);
        var framed = new LinkedHashMap<>(entry); framed.put("expr", List.of("lam", lambda.get(1), body, lambda.get(3)));
        var module = new LinkedHashMap<>(raw); module.put("bindings", List.of(leaf, framed));
        var constructors = new ArrayList<Object>((List<?>) raw.get("constructors"));
        if (count != 2) constructors.add(Map.of("id", "T" + count, "kind", "unboxed-tuple", "arity", count, "tag", 1));
        module.put("constructors", constructors);
        var proved = BoxedForeignProofFixtures.withProof(module);
        return backend.equals("ast") ? new Program(language, proved, false, false) : new BytecodeProgram(language, proved);
    }
    @Test void originalThreadIdentityOrderingSurvivesCompletionAndFirstCompiledEntries() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var threads = Language.currentState().getThreads(); threads.enterCurrent();
                try {
                    var first = threads.currentIdentity(); var retained = new AtomicReference<GuestThreadId>();
                    var carrier = Thread.ofPlatform().start(() -> {
                        threads.enterCurrent(null, true); retained.set(threads.currentIdentity());
                        threads.leaveCurrent(GuestThreadStatus.FINISHED);
                    });
                    carrier.join(5000); assertFalse(carrier.isAlive()); var second = retained.get(); assertNotNull(second);
                    for (var symbol : List.of("rts_getThreadId", "eq_thread", "cmp_thread")) {
                        var program = program(language, backend, symbol); var target = program.entryTarget("foreignLeaf");
                        var callerTarget = program.entryTarget("entry");
                        class Caller {
                            long run(GuestThreadId left, GuestThreadId right) {
                                Object[] args = symbol.equals("rts_getThreadId") ? new Object[]{0L, left, Unit.INSTANCE} :
                                    new Object[]{0L, left, right, Unit.INSTANCE};
                                return ((Number) Calls.target(callerTarget, args)).longValue();
                            }
                        }
                        var caller = new Caller();
                        long same = symbol.equals("rts_getThreadId") ? first.getLogicalId() : symbol.equals("eq_thread") ? 1 : 0;
                        assertNotNull(((GuestRoot) target.getRootNode()).getTypedInput());
                        assertEquals(same, caller.run(first, first));
                        assertTrue(language.getHandoffState().get().getArguments().getAllocations() > 0, "boxed reference crosses a framed input");
                        assertTrue(language.getHandoffState().get().getResults().getAllocations() > 0, "RTS tuple crosses a framed result");
                        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        long different = symbol.equals("rts_getThreadId") ? second.getLogicalId() : symbol.equals("eq_thread") ? 0 : -1;
                        assertEquals(different, caller.run(symbol.equals("rts_getThreadId") ? second : first, second));
                        assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue(), backend + "/" + symbol);
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                        if (symbol.equals("cmp_thread")) assertEquals(1, caller.run(second, first));
                    }
                    var handoff = language.getHandoffState().get();
                    assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getArguments().retainedReferences());
                    assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getResults().retainedReferences());
                } finally { threads.leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    @Test void fabricatedForeignAndMalformedThreadCarriersReject() {
        for (var backend : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var threads = Language.currentState().getThreads(); threads.enterCurrent();
                try {
                    var self = threads.currentIdentity();
                    var impostor = new GuestThreadId(self.getLogicalId(), threads, 0, Thread.currentThread(), false);
                    var target = program(language, backend, "eq_thread").entryTarget("entry");
                    for (var invalid : List.of(new Object(), 1L, impostor))
                        assertThrows(RuntimeFault.class, () -> Calls.target(target, new Object[]{0L, self, invalid, Unit.INSTANCE}));
                    assertThrows(RuntimeFault.class, () -> Calls.target(target, new Object[]{0L, self, self, 1L}));
                    try (var other = Main.executionContext(false)) {
                        other.initialize("thc"); other.enter();
                        try { assertThrows(RuntimeFault.class, () -> Calls.target(program(
                            TruffleLanguage.LanguageReference.create(Language.class).get(null), backend, "rts_getThreadId").entryTarget("entry"),
                            new Object[]{0L, self, Unit.INSTANCE})); }
                        finally { other.leave(); }
                    }
                } finally { threads.leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    @Test void directInputsCannotDispatchByBoxedRepresentationAndNameAlone() {
        for (var backend : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var raw = OriginalStdioChecks.rawModule(call("rts_getThreadId"), Map.of("sourceFiles", List.of(), "sourceSpans", List.of()));
                java.util.function.Consumer<Map<String,Object>> lower = module -> {
                    var program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
                    program.entryTarget("entry");
                };
                assertThrows(RuntimeFault.class, () -> lower.accept(raw));
                var forgedToken = new LinkedHashMap<>(raw); forgedToken.put("boxedForeignDeclarations", List.of(Map.of("calls", List.of())));
                assertThrows(IllegalArgumentException.class, () -> lower.accept(forgedToken));
                for (var nominal : List.of("declaredType", "normalizedType")) {
                    var source = BoxedForeignProofFixtures.withProof(raw);
                    var proof = (Map<?,?>) source.get("staticForeignImports");
                    var declaration = (Map<String,Object>) ((List<?>) proof.get("imports")).getFirst();
                    var type = new LinkedHashMap<>((Map<String,Object>) declaration.get(nominal));
                    type.put("argument", Map.of("kind", "tycon", "name", Map.of("unit", "ghc-internal", "module", "GHC.Internal.Prim",
                        "occurrence", "ByteArray#", "namespace", "type"), "arguments", List.of()));
                    declaration.put(nominal, type);
                    assertThrows(IllegalArgumentException.class, () -> lower.accept(source), nominal);
                }
            } finally { context.leave(); }
        }
    }
}
