// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import thc.*;
import java.io.File;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ThreadInventoryCoreEvidence.*;

@Timeout(180)
@SuppressWarnings("unchecked")
public class ThreadInventoryNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/thread-inventory");
    private final List<String> entries = List.of("selfInventory", "boundQuery", "snapshotSize", "forkSnapshot", "lazyFork", "forkMasks", "selfKilledStatus", "parkedFork", "callbackObservation");
    private static Object first(List<?> values) { return values.isEmpty() ? null : values.getFirst(); }
    private static List<?> take(Object value, int count) { return value instanceof List<?> list ? list.subList(0, Math.min(list.size(), count)) : null; }
    private static List<?> unique(List<? extends List<?>> nodes, java.util.function.Predicate<List<?>> predicate) {
        List<?> found = null; int count = 0; for (var node : nodes) if (predicate.test(node)) { found = node; count++; } assertEquals(1, count); return found;
    }
    private record ForkCalls(long calls, int roots) {}
    /** Fixed source calls; awaitStatus retries through one local join, not extra guest entries. */
    private ForkCalls forkCalls(Map<String, Object> module, String entry) {
        var proof = new ArrayCoreEvidence(module, entry);
        class Evidence {
            Map<String, Object> binding(String name) { Map<String, Object> found = null; int count = 0; for (var binding : proof.getBindings()) if (name.equals(binding.get("name"))) { found = binding; count++; } assertEquals(1, count); return found; }
            int lambdas(String name) { return proof.guestLambdas(binding(name).get("expr")).size(); }
            int references(String name, String target) { int count = 0; for (var id : proof.globalReferences(binding(name).get("expr"))) if (Objects.equals(id, binding(target).get("id"))) count++; return count; }
        }
        var evidence = new Evidence(); var polling = (List<?>) evidence.binding("awaitStatus").get("expr"); assertEquals("lam", polling.get(0));
        var names = new ArrayList<Object>(); for (var parameter : (List<?>) polling.get(1)) names.add(((Map<?, ?>) parameter).get("name")); assertEquals(List.of("tid", "wanted", "fuel", "s"), names);
        assertEquals(List.of(), proof.globalReferences(polling), "Polling cannot add global calls"); var joins = new ArrayList<Object>(); int lambdas = 0;
        for (var node : proof.nodes(polling)) { if (Objects.equals(first(node), "let")) joins.addAll((List<?>) node.get(2)); if (Objects.equals(first(node), "lam")) lambdas++; }
        assertEquals(1, joins.size()); var join = (Map<?, ?>) joins.getFirst(); assertEquals(3L, join.get("joinValueArity")); assertEquals("wait", join.get("name"));
        assertEquals(2, lambdas, "Only the global entry and non-root join prefix occur in awaitStatus"); assertEquals(1, evidence.references(entry.equals("forkMasks") ? "forkMask" : entry, "awaitStatus"));
        switch (entry) {
            case "forkMasks": assertEquals(1, evidence.lambdas(entry)); assertEquals(2, evidence.lambdas("forkMask")); assertEquals(3, evidence.references(entry, "forkMask")); return new ForkCalls(1L + 3L * (2L + 1L), 4);
            case "parkedFork": assertEquals(2, evidence.lambdas(entry)); return new ForkCalls(2L + 1L, 3);
            case "selfKilledStatus": {
                assertEquals(1, evidence.lambdas(entry)); var fork = unique(proof.nodes(evidence.binding(entry).get("expr")), node -> Objects.equals(first(node), "app") && node.size() > 1 && Objects.equals(take(node.get(1), 2), List.of("prim", "fork#")));
                var action = (List<?>) ((List<?>) fork.get(2)).get(0); assertEquals("var", action.get(0)); var child = Objects.requireNonNull(proof.getAllBindings().get((String) action.get(1)));
                assertEquals(1, proof.guestLambdas(child.get("expr")).size()); return new ForkCalls(1L + 1L + 1L, 3);
            }
            case "lazyFork": {
                assertEquals(2, evidence.lambdas(entry)); assertEquals(1, evidence.lambdas("makeAction")); inlinedStateWrapper(proof); assertEquals(2, evidence.lambdas("actionHead"));
                assertEquals(1, evidence.references(entry, "makeAction")); assertEquals(1, evidence.references("makeAction", "actionHead"));
                var constructor = (List<?>) ((List<?>) evidence.binding("makeAction").get("expr")).get(2); assertEquals("app", constructor.get(0)); assertEquals("con", ((List<?>) constructor.get(1)).get(0)); assertEquals(List.of(true), constructor.get(3), "Action stores exactly one lazy action head");
                var arguments = (List<?>) constructor.get(2); assertEquals(1, arguments.size()); var delayed = (List<?>) arguments.getFirst(); assertEquals("app", delayed.get(0)); assertEquals(List.of("var", evidence.binding("actionHead").get("id")), take(delayed.get(1), 2));
                // Check the exported body, not pre-Tidy's PAP flag.
                assertEquals(4, ((List<?>) delayed.get(2)).size()); assertEquals(List.of(false, false, false, false), delayed.get(3));
                var arities = new ArrayList<Integer>(); for (var lambda : proof.guestLambdas(evidence.binding("actionHead").get("expr"))) arities.add(((List<?>) lambda.get(1)).size()); assertEquals(List.of(4, 1), arities);
                int published = 0; for (var binding : proof.getBindings()) {
                    var expression = (List<?>) binding.get("expr");
                    if (Objects.equals(expression.get(0), "app") && Objects.equals(((List<?>) expression.get(1)).get(0), "con")) {
                        var values = (List<?>) expression.get(2); var only = values.size() == 1 ? values.getFirst() : null; if (Objects.equals(take(only, 3), List.of("lit", "int", "42"))) published++;
                    }
                } assertEquals(1, published);
                var headNodes = proof.nodes(evidence.binding("actionHead").get("expr")); var publications = new ArrayList<List<?>>();
                for (var node : headNodes) if (Objects.equals(first(node), "app") && node.size() > 1 && Objects.equals(take(node.get(1), 2), List.of("prim", "putMVar#"))) publications.add(node);
                assertEquals(2, publications.size()); var readiness = (List<?>) ((List<?>) publications.get(0).get(2)).get(1); assertEquals(List.of(false, true, false), publications.get(0).get(3));
                assertEquals("case", readiness.get(0), "Identity comparison is a second lazy message thunk"); assertEquals(List.of(), proof.globalReferences(readiness)); int comparisons = 0;
                for (var node : proof.nodes(readiness)) if (Objects.equals(take(node, 2), List.of("prim", "reallyUnsafePtrEquality#"))) comparisons++; assertEquals(1, comparisons);
                var result = unique(headNodes, node -> Objects.equals(first(node), "app") && node.size() > 1 && Objects.equals(take(node.get(1), 2), List.of("con", "ghc-internal:GHC.Internal.Types.(#,#)")));
                var discarded = (List<?>) ((List<?>) result.get(2)).get(1); assertEquals("var", discarded.get(0)); var bottom = (List<?>) proof.getAllBindings().get((String) discarded.get(1)).get("expr"); assertEquals(List.of("prim", "raise#"), take(bottom.get(1), 2));
                // Public, makeAction, lazy head, head/child, readiness, awaitStatus; cached CAF adds a root only.
                return new ForkCalls(1L + 1L + 1L + 2L + 1L + 1L, 8);
            }
            default: throw new IllegalStateException("No fork call-count proof for " + entry);
        }
    }
    private List<RootCallTarget> forkTargets(ExecutableProgram program, Map<String, Object> module) throws ReflectiveOperationException {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>()); var result = new ArrayList<RootCallTarget>();
        // Seed genuine function bindings without forcing CAFs.
        for (var value : (List<?>) module.get("bindings")) { var binding = (Map<?, ?>) value; if (Objects.equals(((List<?>) binding.get("expr")).get(0), "lam")) for (var target : targets(program.entryTarget((String) binding.get("id")))) if (seen.add(target)) result.add(target); } return result;
    }
    private static void joinCompletedChildren(GuestThreads registry) throws InterruptedException {
        for (var value : registry.snapshot()) if (value instanceof GuestThreadId id && id.getForked()) { var carrier = id.getCarrier().get(); if (carrier != null) { carrier.join(5000); assertFalse(carrier.isAlive(), "Completion signal must be followed by actual carrier termination"); } }
    }
    private void provenance() throws Exception {
        var manifest = (Map<?, ?>) Json.parse(Files.readString(new File(directory, "manifest.json").toPath())); assertEquals(1L, manifest.get("schema")); assertEquals("9.14.1", manifest.get("ghc")); assertEquals(entries, manifest.get("entries")); assertEquals(List.of("pre", "post"), manifest.get("stages")); assertEquals("unbound forkIO, threaded RTS -N2", manifest.get("nativeThread"));
        var sourcePaths = new LinkedHashSet<>(List.of("examples/ThreadInventory.hs", "test/fixtures/compiler/ThreadInventoryNative.hs", "test/fixtures/compiler/CallbackIdentityNative.hs", "test/fixtures/compiler/callback-identity.c", "thc.cabal", "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/ThreadInventoryFixtures.hs", "bin/audit-core.py", "bin/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json", "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py"));
        for (var file : Objects.requireNonNull(new File(root, "src/compiler/THC").listFiles())) if (file.getName().endsWith(".hs")) sourcePaths.add("src/compiler/THC/" + file.getName());
        for (var file : Objects.requireNonNull(new File(root, "bin").listFiles())) if (file.getName().startsWith("core_") && file.getName().endsWith(".py")) sourcePaths.add("bin/" + file.getName());
        var artifacts = new LinkedHashSet<>(List.of("build/thread-inventory/oracle.txt", "build/thread-inventory/callback-oracle.txt"));
        for (var stage : List.of("pre", "post")) { artifacts.add("build/thread-inventory/" + stage + "/core/ThreadInventory.json"); for (var entry : entries) artifacts.add("build/thread-inventory/" + stage + "/" + entry + "-audit.json"); }
        assertEquals(sourcePaths, ((Map<?, ?>) manifest.get("inputHashes")).keySet()); assertEquals(artifacts, ((Map<?, ?>) manifest.get("artifactHashes")).keySet());
        for (var kind : List.of("inputHashes", "artifactHashes")) for (var item : ((Map<?, ?>) manifest.get(kind)).entrySet()) {
            var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, (String) item.getKey()).toPath()))); assertEquals(item.getValue(), actual, "Stale thread inventory fixture: " + item.getKey());
        }
        assertEquals(List.of(10L, 0L, 111L, 1L, 42L, 210L, 17L, 1L), manifest.get("native")); assertEquals(List.of("10", "0", "111", "1", "42", "210", "17", "1"), Files.readAllLines(new File(directory, "oracle.txt").toPath()));
        for (var stage : List.of("pre", "post")) for (var entry : entries) {
            var audit = (Map<?, ?>) Json.parse(Files.readString(new File(directory, stage + "/" + entry + "-audit.json").toPath())); assertEquals(true, audit.get("accepted")); assertEquals(List.of(), audit.get("issues")); assertEquals(List.of(), audit.get("missingGlobals"));
            var names = new HashSet<Object>(); for (var value : (List<?>) audit.get("primitives")) names.add(((Map<?, ?>) value).get("name"));
            var required = switch (entry) { case "boundQuery", "callbackObservation" -> "isCurrentThreadBound#"; case "selfInventory", "snapshotSize", "forkSnapshot" -> "listThreads#"; default -> "fork#"; }; assertTrue(names.contains(required));
        }
    }
    private Context context() { return Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true).option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build(); }
    private Map<String, Object> module(String stage) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(new File(directory, stage + "/core/ThreadInventory.json").toPath())); }
    private static Map<String, Object> instrument(Map<String, Object> module) { var result = new LinkedHashMap<>(module); result.put("instrument", true); return result; }
    private static long count(ExecutableProgram program) { return ((Number) program.diagnostics().get("compiledEntries")).longValue(); }
    private static boolean allValid(List<RootCallTarget> active) throws ReflectiveOperationException { for (var target : active) if (!valid(target)) return false; return true; }
    @Test void genuineCallbacksStartUnmaskedAndBoundWithoutStealingCallerDelivery() throws Exception {
        provenance(); var expectedNative = new ArrayList<String>(); for (var mask : List.of("Unmasked", "MaskedInterruptible", "MaskedUninterruptible")) { expectedNative.add("(" + mask + ",True,True,Unmasked,True,True,True,Unmasked,True,1,8)"); expectedNative.add("(True,True)"); } expectedNative.add("(True,True,True,True)"); assertEquals(expectedNative, Files.readAllLines(new File(directory, "callback-oracle.txt").toPath()));
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var module = module(stage); var proof = new ArrayCoreEvidence(module, "callbackObservation"); var lambda = (List<?>) proof.getRoot().get("expr"); proof.immediateStateLambda(lambda.get(2)); assertEquals(1, proof.getBindings().size()); assertEquals(2, proof.guestLambdas(lambda).size()); int calls = proof.loweredGuestLambdas(lambda).size(); assertEquals(1, calls, "Only the original entry survives immediate State# lowering");
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var owner = Language.currentState(); var linked = instrument(CoreModules.reachable(module, "callbackObservation"));
                ExecutableProgram program = backend.equals("ast") ? new Program(language, linked, true) : new BytecodeProgram(language, linked, true); var function = context.asValue(new EntryValue(program, "callbackObservation", 1)); var active = targets(program.entryTarget("callbackObservation")); assertEquals(calls, active.size());
                // Existing managed reverse entries; native trampoline is an independent oracle.
                for (int i = 0; i < 3; i++) { var foreign = owner.getThreads().enterForeign(ForeignSafety.SAFE); try { assertEquals(1L, function.execute(0L).asLong()); } finally { owner.getThreads().leaveForeign(foreign); } }
                long beforeInstallation = count(program); var interpretedBefore = interpretedCalls(active); install(active); assertTrue(function.invokeMember("compile").asBoolean()); assertEquals(beforeInstallation, count(program)); assertEquals(interpretedBefore, interpretedCalls(active), "Installation executes no guest calls");
                for (var mask : MaskingState.values()) {
                    var registry = owner.getThreads(); registry.enterCurrent(mask, false, true, null); var caller = registry.currentIdentity();
                    try {
                        var queued = new AtomicReference<AsyncRequest>(); var sender = new Thread(() -> queued.set(registry.send(caller, "suspended caller"))); sender.start(); sender.join(5000); assertFalse(sender.isAlive()); var request = queued.get(); var foreign = registry.enterForeign(ForeignSafety.SAFE);
                        try {
                            long before = count(program); assertEquals(8L, function.execute(7L).asLong(), stage + "/" + backend + "/" + mask + " native callback result"); assertEquals(before + calls, count(program), "First installed callback must enter its exact original root"); assertTrue(allValid(active)); assertEquals(interpretedBefore, interpretedCalls(active));
                            assertSame(caller, registry.currentIdentity()); assertEquals(mask, owner.getMaskingState().get()); assertEquals(AsyncRequestState.PENDING, request.getState(), "Callback never claims its caller's mailbox"); registry.enterCurrent(); var callback = registry.currentIdentity();
                            try {
                                owner.getMaskingState().set(MaskingState.MASKED_UNINTERRUPTIBLE); var nestedForeign = registry.enterForeign(ForeignSafety.SAFE);
                                try { long nestedBefore = count(program); assertEquals(8L, function.execute(7L).asLong()); assertEquals(nestedBefore + calls, count(program)); assertTrue(allValid(active)); assertEquals(interpretedBefore, interpretedCalls(active)); assertSame(callback, registry.currentIdentity()); assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, owner.getMaskingState().get()); }
                                finally { registry.leaveForeign(nestedForeign); }
                            } finally { registry.leaveCurrent(); }
                        } finally { registry.leaveForeign(foreign); }
                        assertSame(caller, registry.currentIdentity()); owner.getMaskingState().set(MaskingState.UNMASKED); var node = program.entryTarget("callbackObservation").getRootNode(); assertSame(request, registry.poll(node)); request.acknowledge(); released(language);
                    } finally { registry.leaveCurrent(); }
                }
            } finally { context.leave(); }
        }
    }
    @Test void nativeSnapshotsAndBoundnessPreserveFirstCompiledEntriesInBothBackends() throws Exception {
        provenance(); record Expected(String entry, long value) {}
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode")) for (var row : List.of(new Expected("selfInventory", 10L), new Expected("boundQuery", 0L), new Expected("snapshotSize", 1L), new Expected("forkSnapshot", 111L), new Expected("lazyFork", 42L), new Expected("forkMasks", 210L), new Expected("selfKilledStatus", 17L))) {
            var entry = row.entry(); long expected = row.value();
            try (var context = context()) {
                context.initialize("thc"); context.enter();
                try {
                    var module = module(stage); var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); assertEquals(Boolean.getBoolean(Handoff.HANDOFF_PROPERTY), language.getHandoffLayouts().getEnabled()); System.out.println("THREAD_INVENTORY_HANDOFF=" + language.getHandoffLayouts().getEnabled());
                    var linked = instrument(CoreModules.reachable(module, entry)); ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked, true); var function = context.asValue(new EntryValue(program, entry, 1));
                    var proof = entries.subList(0, 4).contains(entry) ? new ThreadInventoryCoreEvidence(module, entry) : null; var fixedProof = proof == null ? forkCalls(module, entry) : null; var registry = Language.currentState(null).getThreads(); registry.enterCurrent();
                    try {
                        for (int i = 0; i < 3; i++) { assertEquals(expected, function.execute(0L).asLong(), stage + "/" + backend + "/" + entry); joinCompletedChildren(registry); released(language); }
                        var retained = registry.snapshot(); int population = retained.length + (entry.equals("forkSnapshot") ? 1 : 0); var active = proof != null ? targets(program.entryTarget(entry)) : forkTargets(program, linked);
                        if (proof != null) proof.assertRoots(active); else { var labels = new ArrayList<String>(); for (var target : active) labels.add(target.getRootNode().getName()); assertEquals(fixedProof.roots(), active.size(), entry + " source-derived roots, including child, head thunk and already-forced CAF: " + labels); }
                        long before = count(program); var calls = interpretedCalls(active); install(active); assertTrue(function.invokeMember("compile").asBoolean()); assertEquals(before, count(program), "Installation executes no guest roots"); assertEquals(calls, interpretedCalls(active), "No interpreted settling call");
                        assertEquals(expected + 1L, function.execute(1L).asLong(), stage + "/" + backend + "/" + entry + " first installed observation"); joinCompletedChildren(registry); long delta = count(program) - before; System.out.println("THREAD_INVENTORY_ENTRIES " + stage + "/" + backend + "/" + entry + " population=" + population + " delta=" + delta);
                        assertEquals(fixedProof != null ? fixedProof.calls() : proof.compiledCalls(population), delta, stage + "/" + backend + "/" + entry + " every original guest invocation enters installed code"); assertEquals(calls, interpretedCalls(active), "No guest root silently interpreted"); assertEquals(active, proof != null ? targets(program.entryTarget(entry)) : forkTargets(program, linked));
                        for (var target : active) assertTrue(valid(target), stage + "/" + backend + "/" + entry + " first observation retired " + target.getRootNode().getName()); assertEquals(0L, program.diagnostics().get("unsupportedTraps")); Reference.reachabilityFence(retained);
                    } finally { released(language); registry.leaveCurrent(); }
                } finally { context.leave(); }
            }
        }
    }
    @Test void managedBlockedChildrenAreCancelledByContextCloseAndAstRejectsExternalDelivery() throws Exception {
        provenance();
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode")) {
            var context = context(); var carriers = new ArrayList<Thread>(); final GuestThreads registry;
            try {
                context.initialize("thc"); context.enter();
                try {
                    var module = module(stage); var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var linked = instrument(CoreModules.reachable(module, "parkedFork")); ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked, true);
                    var function = context.asValue(new EntryValue(program, "parkedFork", 1)); assertEquals(1L, function.execute(0L).asLong()); long expectedCalls = forkCalls(module, "parkedFork").calls(); var active = forkTargets(program, linked); assertEquals(3, active.size()); var calls = interpretedCalls(active); install(active); assertTrue(function.invokeMember("compile").asBoolean()); long before = count(program);
                    assertEquals(2L, function.execute(1L).asLong()); assertEquals(expectedCalls, count(program) - before); assertEquals(calls, interpretedCalls(active)); for (var target : active) assertTrue(valid(target)); assertTrue(valid(program.entryTarget("parkedFork"))); registry = Language.currentState(null).getThreads(); registry.enterCurrent();
                    try {
                        var self = registry.currentIdentity(); var children = new ArrayList<GuestThreadId>(); for (var value : registry.snapshot()) if (value instanceof GuestThreadId child && child != self) children.add(child); assertEquals(2, children.size());
                        for (var child : children) { assertEquals(GuestThreadStatus.MVAR, registry.status(child)); var carrier = Objects.requireNonNull(child.getCarrier().get()); carriers.add(carrier); assertTrue(carrier.isAlive()); assertNotSame(Thread.currentThread(), carrier);
                            if (backend.equals("ast")) { assertThrows(UnsupportedCore.class, () -> registry.send(child, "unsupported cancellation")); assertEquals(GuestThreadStatus.MVAR, registry.status(child)); }
                        }
                    } finally { registry.leaveCurrent(); }
                    assertEquals(0L, program.diagnostics().get("unsupportedTraps"));
                } finally { context.leave(); }
            } finally { context.close(true); }
            for (var carrier : carriers) { carrier.join(5000); assertFalse(carrier.isAlive(), stage + "/" + backend + " context close must stop its actual managed children"); } assertThrows(RuntimeFault.class, registry::snapshot);
        }
    }
    private static void retained(List<RootCallTarget> active, RootCallTarget entry, Language language) throws ReflectiveOperationException { assertEquals(active, targets(entry)); for (var target : active) assertTrue(valid(target), "Retained " + target.getRootNode().getName()); released(language); }
    @Test void retiredBoundaryNegativeControlRejectsMissingCompiledEntries() throws Exception {
        provenance();
        for (var stage : List.of("pre", "post")) for (var backendName : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var module = module(stage); var proof = new ThreadInventoryCoreEvidence(module, "selfInventory"); assertEquals(3L, proof.compiledCalls(1), "Public and occurrences at indices zero and one; State# wrapper is inlined");
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); assertEquals(Boolean.getBoolean(Handoff.HANDOFF_PROPERTY), language.getHandoffLayouts().getEnabled()); System.out.println("THREAD_INVENTORY_BOUNDARY_HANDOFF=" + language.getHandoffLayouts().getEnabled());
                var linked = instrument(CoreModules.reachable(module, "selfInventory")); ExecutableProgram program = backendName.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked, true); var entry = program.entryTarget("selfInventory"); var registry = Language.currentState(null).getThreads(); registry.enterCurrent();
                try {
                    for (int i = 0; i < 3; i++) { assertEquals(10L, Calls.target(entry, new Object[]{0L, 0L})); released(language); }
                    var active = targets(entry); proof.assertRoots(active); assertEquals(1, registry.snapshot().length); var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); var jvmci = Class.forName("jdk.vm.ci.runtime.JVMCI").getMethod("getRuntime").invoke(null);
                    var backend = Class.forName("jdk.vm.ci.runtime.JVMCIRuntime").getMethod("getHostJVMCIBackend").invoke(jvmci); var meta = Class.forName("jdk.vm.ci.runtime.JVMCIBackend").getMethod("getMetaAccess").invoke(backend);
                    var boundary = Class.forName("jdk.vm.ci.meta.MetaAccessProvider").getMethod("lookupJavaMethod", java.lang.reflect.Executable.class).invoke(meta, type.getDeclaredMethod("callBoundary", Object[].class)); var reprofile = Class.forName("jdk.vm.ci.meta.ResolvedJavaMethod").getMethod("reprofile"); var hasCode = Class.forName("jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod").getMethod("hasCompiledCode"); install(active);
                    try {
                        reprofile.invoke(boundary); assertEquals(false, hasCode.invoke(boundary)); for (var target : active) rawCompile(target); assertEquals(false, hasCode.invoke(boundary), "Raw installation leaves the boundary retired"); long before = count(program); var calls = interpretedCalls(active);
                        assertEquals(11L, Calls.target(entry, new Object[]{0L, 1L})); long negative = count(program) - before; var interpreted = new ArrayList<Integer>(); var after = interpretedCalls(active); for (int i = 0; i < after.size(); i++) interpreted.add(after.get(i) - calls.get(i));
                        var sourceCalls = Map.of("lambda token", 1, "lambda wanted, threads, i", 2); assertEquals(proof.getLabels(), sourceCalls.keySet()); var observed = new LinkedHashMap<String, Integer>(); for (int i = 0; i < active.size(); i++) observed.put(active.get(i).getRootNode().getName(), interpreted.get(i)); System.out.println("THREAD_INVENTORY_BOUNDARY " + stage + "/" + backendName + " negative=" + negative + " interpreted=" + observed);
                        // Account for actual bypass paths, including previously compiled Java call sites.
                        assertEquals(1, interpreted.get(active.indexOf(entry)), "The outer entry deliberately bypasses code"); int sum = 0;
                        for (int i = 0; i < active.size(); i++) { var target = active.get(i); int value = interpreted.get(i); assertTrue(value >= 0 && value <= sourceCalls.get(target.getRootNode().getName()), "Interpreted calls fit the original root: " + target.getRootNode().getName()); sum += value; }
                        assertEquals(proof.compiledCalls(1), negative + sum, "Every source call is accounted for as compiled or interpreted"); assertTrue(negative < proof.compiledCalls(1), "The deliberate bypass misses required compiled entries"); assertThrows(AssertionError.class, () -> assertEquals(proof.compiledCalls(1), negative)); retained(active, entry, language);
                        reprofile.invoke(boundary); assertEquals(false, hasCode.invoke(boundary)); long beforeInstall = count(program); var callsBeforeInstall = interpretedCalls(active); install(active); assertEquals(true, hasCode.invoke(boundary)); assertEquals(beforeInstall, count(program), "Restoration executes no guest code"); assertEquals(callsBeforeInstall, interpretedCalls(active), "No settling call");
                        assertEquals(12L, Calls.target(entry, new Object[]{0L, 2L})); System.out.println("THREAD_INVENTORY_BOUNDARY " + stage + "/" + backendName + " positive=" + (count(program) - beforeInstall)); assertEquals(proof.compiledCalls(1), count(program) - beforeInstall); assertEquals(callsBeforeInstall, interpretedCalls(active)); retained(active, entry, language);
                    } finally { restoreBoundary(entry); }
                } finally { released(language); registry.leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    @Test void bothBackendsObserveRealConcurrentGuestEntriesButNotOtherContexts() throws Exception {
        provenance();
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var module = module("pre"); var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var linked = CoreModules.reachable(module, List.of("snapshotSize", "selfInventory")); ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                var size = context.asValue(new EntryValue(program, "snapshotSize", 1)); var self = context.asValue(new EntryValue(program, "selfInventory", 1)); assertEquals(1L, size.execute(0L).asLong()); var registry = Language.currentState(null).getThreads(); var ready = new CountDownLatch(1); var release = new CountDownLatch(1); var failure = new AtomicReference<Throwable>();
                var worker = new Thread(() -> { context.enter(); registry.enterCurrent(); try { assertEquals(10L, self.execute(0L).asLong()); ready.countDown(); assertTrue(release.await(20, TimeUnit.SECONDS)); } catch (Throwable error) { failure.set(error); ready.countDown(); } finally { registry.leaveCurrent(); context.leave(); } }); worker.start();
                try {
                    assertTrue(ready.await(20, TimeUnit.SECONDS)); if (failure.get() != null) throw new AssertionError("concurrent guest failed", failure.get()); assertEquals(2L, size.execute(0L).asLong()); assertEquals(10L, self.execute(0L).asLong());
                    try (var other = context()) { other.initialize("thc"); other.enter(); try { var isolated = Language.currentState(null).getThreads(); isolated.enterCurrent(); try { assertEquals(1, isolated.snapshot().length); } finally { isolated.leaveCurrent(); } } finally { other.leave(); } }
                } finally { release.countDown(); worker.join(20000); }
                assertFalse(worker.isAlive()); if (failure.get() != null) throw new AssertionError("concurrent guest failed", failure.get());
            } finally { context.leave(); }
        }
    }
}
