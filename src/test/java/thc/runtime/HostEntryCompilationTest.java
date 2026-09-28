// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeTier;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import thc.EntryValue;
import thc.Language;
import thc.Json;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

/** Explicit host compilation must follow the direct call's actual split target. */
class HostEntryCompilationTest {
    private Map<String, Object> module() {
        var body = List.of("app", List.of("prim", "+#"), List.of(List.of("var", "input"), List.of("lit", "int", "1")), List.of(false, false));
        var function = List.of("lam", List.of(Map.of("id", "input", "name", "input", "lifted", false)), body);
        return Map.of("schema", 1, "ghc", "9.14.1", "module", "Synthetic.HostEntryCompilation", "instrument", true, "constructors", List.of(),
            "bindings", List.of(Map.of("id", "entry", "name", "entry", "arity", 1, "lifted", true, "expr", function)));
    }
    private void checkHostCompilation(String backend) throws ReflectiveOperationException {
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                ExecutableProgram program = switch (backend) {
                    case "ast" -> new Program(language, module());
                    case "bytecode" -> new BytecodeProgram(language, module());
                    default -> throw new IllegalStateException("Unexpected backend: " + backend);
                };
                var original = program.entryTarget("entry"); var host = program.hostEntryTarget(1);
                assertFalse(host.getRootNode().isCloningAllowed(), "The host dispatch tree must remain stable");
                var function = context.asValue(new EntryValue(program, "entry", 1));
                class Observation {
                    Map<?, ?> state() { return (Map<?, ?>) ((Map<?, ?>) Json.parse(function.getMember("diagnostics").asString())).get("explicitCompilation"); }
                    void check(long input) { assertEquals(input + 1L, function.execute(input).asLong(), backend + " input " + input); }
                    long compiledEntries() { return ((Number) program.diagnostics().get("compiledEntries")).longValue(); }
                    void installed() {
                        long before = compiledEntries();
                        for (int i = 0; i < 2; i++) {
                            var state = Objects.requireNonNull(state());
                            assertEquals(2L, state.get("targetCount")); assertEquals(true, state.get("sameTargets")); assertEquals(true, state.get("validLastTier"));
                        }
                        assertEquals(before, compiledEntries(), "Observation must not execute guest code");
                    }
                }
                var observation = new Observation();
                assertNull(observation.state(), "Reading diagnostics must not create an installation");
                observation.check(0L);
                if (backend.equals("bytecode")) assertEquals(BytecodeTier.CACHED, ((BytecodeRoot) original.getRootNode()).getBytecodeNode().getTier(), "The first guest call must execute in the cached interpreter");
                for (int i = 1; i < 20; i++) observation.check(i);
                var calls = new ArrayList<DirectCallNode>();
                for (var node : NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class)) if (node.getCallTarget() == original) calls.add(node);
                assertEquals(1, calls.size(), "Find the warmed host call to the guest entry");
                var call = calls.getFirst();
                assertTrue(call.isCallTargetCloningAllowed(), backend + " entry must support real Truffle splitting");
                assertTrue(call.cloneCallTarget(), "Force a real split independently of heuristic thresholds");
                var active = (RootCallTarget) call.getCurrentCallTarget();
                assertNotSame(original, active);
                assertSame(original, call.getCallTarget(), "The direct node retains its original target identity");
                assertSame(original, program.entryTarget("entry"), "Splitting must not rewrite the closure target");
                // Train the clone before compilation to isolate target selection from unseen entry profiles.
                for (int i = 0; i < 20; i++) observation.check(i);
                assertTrue(function.invokeMember("compile").asBoolean());
                var optimizingTarget = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                assertEquals(true, optimizingTarget.getMethod("isValidLastTier").invoke(active), "Host compilation must install the active split guest target");
                assertEquals(true, optimizingTarget.getMethod("isValidLastTier").invoke(host), "Host compilation must also install the stable bridge used by the public executable value");
                assertSame(active, call.getCurrentCallTarget()); assertSame(original, program.entryTarget("entry"));
                observation.installed();
                for (long input : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, 3_000_000_001L, -3_000_000_001L, 0L, 7L}) {
                    long before = observation.compiledEntries(); observation.check(input);
                    assertTrue(observation.compiledEntries() > before, backend + " input " + input + " must enter installed guest code"); observation.installed();
                }
                assertEquals(0L, program.diagnostics().get("unsupportedTraps"));
                long before = observation.compiledEntries();
                optimizingTarget.getMethod("invalidate", CharSequence.class).invoke(active, "observation regression");
                assertEquals(true, Objects.requireNonNull(observation.state()).get("sameTargets"));
                assertEquals(false, Objects.requireNonNull(observation.state()).get("validLastTier"), "Observation must not repair invalid code");
                call.replace(DirectCallNode.create(original));
                assertEquals(false, Objects.requireNonNull(observation.state()).get("sameTargets"), "Actual target replacement must be detected by identity");
                assertEquals(before, observation.compiledEntries());
            } finally { context.leave(); }
        }
    }
    @Test void astHostCompilationFollowsTheActiveSplit() throws ReflectiveOperationException { checkHostCompilation("ast"); }
    @Test void bytecodeHostCompilationFollowsTheActiveSplit() throws ReflectiveOperationException { checkHostCompilation("bytecode"); }
}
