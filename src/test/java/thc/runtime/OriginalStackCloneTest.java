// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Original exported worker + synthetic consumer/corruption controls, not native decoding. */
@SuppressWarnings("unchecked")
public class OriginalStackCloneTest {
    private Map<String, Object> original() throws Exception {
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/core/original-stack-clone.json"))) {
            return (Map<String, Object>) Json.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    private Map<String, Object> binding(Map<String, Object> module) {
        var bindings = (List<Map<String, Object>>) module.get("bindings");
        if (bindings.size() != 1) throw new IllegalArgumentException("Expected one binding");
        return bindings.getFirst();
    }
    private List<Object> lambda(Map<String, Object> module) { return (List<Object>) binding(module).get("expr"); }
    private List<Object> cloneCall(Map<String, Object> module) { return (List<Object>) ((List<?>) lambda(module).get(2)).get(1); }
    private final Map<String, Object> closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Map<String, Object> state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
    private final Map<String, Object> boxed = Map.of("kind", "data", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private final String consumerId = "consumer-unit:Snapshot.Consumer.capture";
    private static Map<String, Object> plus(Map<String, Object> source, String key, Object value) {
        var result = new LinkedHashMap<>(source); result.put(key, value); return result;
    }
    private static Map<String, Object> minus(Map<String, Object> source, String key) {
        var result = new LinkedHashMap<>(source); result.remove(key); return result;
    }
    /** The worker stays unchanged. The synthetic consumer makes its tuple result host-visible. */
    private Map<String, Object> consumer(Map<String, Object> source) { return consumer(source, false); }
    private Map<String, Object> consumer(Map<String, Object> source, boolean movedBody) {
        var worker = binding(source); var lam = lambda(source);
        var formals = (List<Map<String, Object>>) lam.get(1);
        var tuple = ((Map<String, Object>) lam.get(3)).get("resultRep");
        if (formals.size() != 1) throw new IllegalArgumentException("Expected one formal");
        var call = movedBody ? lam.get(2) : List.of("app", List.of("var", worker.get("id"), Map.of("rep", closure)),
            List.of(List.of("var", formals.getFirst().get("id"), Map.of("rep", state))), List.of(false), false, false, Map.of("rep", tuple));
        var body = List.of("case", call, "result-pair", List.of(List.of("data", "ghc-internal:GHC.Internal.Types.(#,#)",
            List.of("result-state", "result-snapshot"), List.of("var", "result-snapshot", Map.of("rep", boxed)),
            Map.of("binders", List.of(Map.of("id", "result-state", "lifted", false, "rep", state),
                Map.of("id", "result-snapshot", "lifted", true, "rep", boxed))))),
            Map.of("rep", boxed, "binder", Map.of("id", "result-pair", "lifted", false, "rep", tuple)));
        var entry = Map.<String, Object>of("id", consumerId, "name", "capture", "arity", 1, "lifted", true, "rep", closure,
            "expr", List.of("lam", formals, body, Map.of("rep", closure, "resultRep", boxed)));
        var result = new LinkedHashMap<>(source); result.put("instrument", true);
        result.put("bindings", movedBody ? List.of(entry) : List.of(worker, entry));
        result.put("bindingOrigins", Map.of(worker.get("id"), Map.of("unit", "ghc-internal", "module", "GHC.Internal.Stack.CloneStack"),
            consumerId, Map.of("unit", "consumer-unit", "module", "Snapshot.Consumer")));
        return result;
    }
    private Context context() { return context(false); }
    private Context context(boolean inlining) { return Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", Boolean.toString(inlining)).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build(); }
    private ExecutableProgram load(Language language, String backend, Map<String, Object> module) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private List<RootCallTarget> activeTargets(RootCallTarget entry) {
        var targets = new ArrayList<RootCallTarget>();
        Set<RootCallTarget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        visit(entry, targets, seen); return targets;
    }
    private void visit(RootCallTarget target, List<RootCallTarget> targets, Set<RootCallTarget> seen) {
        if (!seen.add(target)) return;
        var root = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(root);
        if (root instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions())
            for (var argument : instruction.getArguments()) if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                var cached = argument.asCachedNode(); if (cached != null) nodes.add(cached);
            }
        for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
            if (call.getCurrentCallTarget() instanceof RootCallTarget next && next.getRootNode() instanceof GuestRoot) visit(next, targets, seen);
        targets.add(target);
    }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void released(Language language) {
        var handoff = language.getHandoffState().get();
        assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
        assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
    }
    @Test public void originalWorkerCapturesTheActualTopOperationBeforeAndAfterCompilation() throws Exception {
        for (var backend : List.of("ast", "bytecode")) for (boolean inlining : new boolean[]{false, true}) for (boolean moved : new boolean[]{false, true}) {
            var retained = new ArrayList<ManagedStackSnapshot>(); var rendered = new ArrayList<List<String>>();
            try (var context = context(inlining)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var program = load(language, backend, consumer(original(), moved)); var entry = program.entryTarget(consumerId);
                    class Runner {
                        ManagedStackSnapshot invoke() { return invoke(thc.runtime.Unit.INSTANCE); }
                        ManagedStackSnapshot invoke(Object token) {
                            var value = (DataValue) Calls.target(entry, new Object[]{0L, token});
                            assertEquals("StackSnapshot", value.getLayout().getName());
                            return (ManagedStackSnapshot) value.getLayout().read(value, 0);
                        }
                        void check(ManagedStackSnapshot snapshot) throws Exception {
                            var top = snapshot.getFrames().getFirst();
                            assertEquals(moved ? consumerId : binding(original()).get("id"), top.getCoreIdentity() == null ? null : top.getCoreIdentity().bindingId());
                            assertEquals(178, top.getLocation() == null ? null : top.getLocation().getStartLine(), backend + "/" + inlining + "/moved=" + moved);
                            assertEquals(21, top.getLocation() == null ? null : top.getLocation().getStartColumn());
                            var path = top.getLocation() == null ? null : top.getLocation().getPath();
                            if (path == null && top.getLocation() != null) path = top.getLocation().getName();
                            assertTrue((path == null ? "" : path).endsWith("/GHC/Internal/Stack/CloneStack.hs"));
                            assertEquals(backend.equals("ast") ? ManagedStackLocationKind.CURRENT_NODE : ManagedStackLocationKind.BYTECODE, top.getLocationKind());
                            assertEquals(moved ? 1 : 2, snapshot.getFrames().size());
                            if (!moved) assertEquals(consumerId, snapshot.getFrames().get(1).getCoreIdentity() == null ? null : snapshot.getFrames().get(1).getCoreIdentity().bindingId());
                            released(language);
                        }
                    }
                    var runner = new Runner(); var first = runner.invoke(); runner.check(first); retained.add(first);
                    assertEquals(0L, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                    for (int i = 0; i < 3; i++) runner.check(runner.invoke());
                    var targets = activeTargets(entry);
                    var coreTargets = targets.stream().filter(target -> ((GuestRoot) target.getRootNode()).getCoreIdentity() != null).toList();
                    var helpers = targets.stream().filter(target -> ((GuestRoot) target.getRootNode()).getCoreIdentity() == null).toList();
                    assertEquals(moved ? 1 : 2, coreTargets.size());
                    var expectedBindings = moved ? Set.of(consumerId) : Set.of(consumerId, binding(original()).get("id"));
                    assertEquals(expectedBindings, coreTargets.stream().map(target -> ((GuestRoot) target.getRootNode()).getCoreIdentity().bindingId()).collect(java.util.stream.Collectors.toSet()));
                    // Prepared internal boundaries are reachable code, not extra live Core frames.
                    for (var target : helpers) {
                        var helper = assertInstanceOf(FunctionRoot.class, target.getRootNode());
                        assertEquals(FunctionRootRole.PASS_THROUGH, helper.getRole());
                        assertTrue(helper.getStackCapture());
                    }
                    for (var target : targets) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target); }
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    var installed = runner.invoke(); runner.check(installed); retained.add(installed);
                    assertEquals(before + (moved ? 1 : 2), ((Number) program.diagnostics().get("compiledEntries")).longValue());
                    for (var target : targets) valid(target);
                    for (var snapshot : retained) rendered.add(snapshot.renderLines());
                    assertThrows(RuntimeFault.class, () -> runner.invoke(7L)); released(language);
                    for (var counter : List.of("unsupportedTraps", "blackholes")) assertEquals(0L, ((Number) program.diagnostics().get(counter)).longValue());
                } finally { context.leave(); }
            }
            for (int i = 0; i < retained.size(); i++) assertEquals(rendered.get(i), retained.get(i).renderLines(), "after context close");
        }
    }
    @Test public void rawContractRejectsMalformedDeclarationsOccurrencesAndTupleProofs() throws Exception {
        var call = cloneCall(original()); var meta = (Map<String, Object>) call.get(6);
        var descriptor = (Map<String, Object>) meta.get("foreignCall"); var target = (Map<String, Object>) descriptor.get("target");
        var result = (Map<String, Object>) meta.get("rep");
        assertTrue(CoreStackForeign.validate(meta, List.of(state), List.of(false)));
        for (var mutation : List.of(List.of("schema", 1.0), List.of("schema", 2L), List.of("arity", 0L), List.of("suppliedArity", 2L),
                List.of("convention", "ccall"), List.of("safety", "unsafe"), List.of("extra", true)))
            assertThrows(RuntimeFault.class, () -> CoreStackForeign.validate(plus(meta, "foreignCall", plus(descriptor, (String) mutation.get(0), mutation.get(1))), List.of(state), List.of(false)));
        for (var mutation : List.of(Arrays.asList("unit", null), List.of("unit", "main"), List.of("kind", "dynamic"), List.of("isFunction", false), List.of("extra", true)))
            assertThrows(RuntimeFault.class, () -> CoreStackForeign.validate(plus(meta, "foreignCall", plus(descriptor, "target", plus(target, (String) mutation.get(0), mutation.get(1)))), List.of(state), List.of(false)));
        var wrongStates = Arrays.asList(null, Map.of(), plus(state, "kind", "unknown"), plus(state, "primReps", List.of("IntRep")),
            plus(state, "evaluated", 1L), plus(plus(plus(state, "kind", "unknown"), "aggregate", "unboxed-tuple"), "components", List.of()),
            boxed, plus(state, "vector", Map.of()));
        for (var bad : wrongStates) {
            assertThrows(RuntimeFault.class, () -> CoreStackForeign.validate(meta, Arrays.asList(bad), List.of(false)));
            assertThrows(RuntimeFault.class, () -> CoreStackForeign.validate(plus(meta, "foreignCall", plus(descriptor, "argumentReps", Arrays.asList(bad))), List.of(state), List.of(false)));
        }
        for (var flags : List.of(List.of(), List.of(true), List.of(0L), List.of(false, false)))
            assertThrows(RuntimeFault.class, () -> CoreStackForeign.validate(meta, List.of(state), flags));
        for (var args : List.of(List.of(), List.of(state, state)))
            assertThrows(RuntimeFault.class, () -> CoreStackForeign.validate(meta, args, List.of(false)));
        var components = (List<Map<String, Object>>) result.get("components");
        var reversed = new ArrayList<>(components); Collections.reverse(reversed);
        for (var bad : List.of(plus(result, "primReps", List.of()), minus(result, "aggregate"), plus(result, "extra", true),
                plus(result, "components", reversed), plus(result, "components", List.of(components.get(0), boxed)),
                plus(result, "components", List.of(components.get(0), plus(components.get(1), "evaluated", false))))) {
            assertThrows(RuntimeFault.class, () -> CoreStackForeign.validate(plus(meta, "rep", bad), List.of(state), List.of(false)));
            assertThrows(RuntimeFault.class, () -> CoreStackForeign.validate(plus(meta, "foreignCall", plus(descriptor, "resultRep", bad)), List.of(state), List.of(false)));
        }
        assertFalse(CoreStackForeign.validate(minus(meta, "foreignCall"), List.of(state), List.of(false)));
        assertFalse(CoreStackForeign.validate(plus(meta, "foreignCall", plus(descriptor, "target", plus(target, "symbol", "stg_decodeStackzh"))), List.of(state), List.of(false)));
    }
    @Test public void bothLoadersRejectShadowedHeadsAndStoredStateRelabeling() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var originalFormal = (List<Map<String, Object>>) lambda(original()).get(1);
                if (originalFormal.size() != 1) throw new IllegalArgumentException("Expected one formal");
                for (var replacement : Arrays.asList(null, "", 7L, binding(original()).get("id"), originalFormal.getFirst().get("id"))) {
                    var source = original(); var head = (List<Object>) cloneCall(source).get(1); head.set(1, replacement);
                    assertThrows(RuntimeFault.class, () -> load(language, backend, consumer(source)));
                }
                for (var bad : List.of(boxed, plus(plus(state, "kind", "unknown"), "primReps", List.of("IntRep")),
                        plus(plus(state, "kind", "unknown"), "primReps", List.of("BoxedRep Nothing")),
                        plus(plus(plus(state, "kind", "unknown"), "aggregate", "unboxed-tuple"), "components", List.of()))) {
                    var source = original(); var formals = (List<Map<String, Object>>) lambda(source).get(1);
                    if (formals.size() != 1) throw new IllegalArgumentException("Expected one formal");
                    formals.getFirst().put("rep", bad);
                    assertThrows(RuntimeFault.class, () -> load(language, backend, consumer(source)));
                }
            } finally { context.leave(); }
        }
    }
}
