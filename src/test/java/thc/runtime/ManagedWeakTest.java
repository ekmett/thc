// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import thc.runtime.Unit;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class ManagedWeakTest {
    private Context context() {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("compiler.Inlining", "false")
            .option("engine.TraceCompilation", System.getProperty("thc.weakTrace", "false"))
            .option("engine.TraceTransferToInterpreter", System.getProperty("thc.weakTrace", "false"))
            .option("engine.TraceAssumptions", System.getProperty("thc.weakTrace", "false"))
            .option("engine.SingleTierCompilationThreshold", "10000")
            .option("engine.CompilationFailureAction", "Throw")
            .option("compiler.CompilationTimeout", "30")
            .build();
    }
    @Test
    void registrationsRetainLazyIdentityAndReturnTheRealActionWithoutCallingIt() {
        var registry = new ManagedWeaks();
        var key = new Object();
        var value = new Object();
        int[] calls = {0};
        Object[] weak = {null};
        Supplier<Object> action = () -> {
            ++calls[0];
            assertEquals(0L, registry.dereference(weak[0]).getFlag());
            assertEquals(0L, registry.finalize(weak[0]).getFlag());
            return key;
        };
        weak[0] = registry.make(key, value, action);
        var independent = registry.make(key, key, null);
        registry.make(key, value, action); // Dropping a handle does not erase registration.
        assertEquals(3, registry.retainedCount());
        assertSame(value, registry.dereference(weak[0]).getValue());
        var claimed = registry.finalize(weak[0]);
        assertEquals(1L, claimed.getFlag());
        assertSame(action, claimed.getValue());
        assertEquals(0, calls[0]);
        assertEquals(2, registry.retainedCount());
        action.get();
        assertEquals(1, calls[0]);
        assertEquals(0L, registry.finalize(weak[0]).getFlag());
        assertNull(registry.finalize(weak[0]).getValue());
        assertSame(key, registry.dereference(independent).getValue());
        assertEquals(0L, registry.finalize(independent).getFlag());
        assertEquals(0L, registry.dereference(independent).getFlag());
        assertNull(registry.dereference(independent).getValue());
        registry.close();
        assertEquals(0, registry.retainedCount());
        assertEquals(1, calls[0], "close must not run the discarded handle's action");
        assertThrows(RuntimeFault.class, () -> registry.dereference(weak[0]));
    }
    @Test
    void finalizationIsLinearizableAndFailureDoesNotReviveRegistration() throws Exception {
        var registry = new ManagedWeaks();
        var failure = new IllegalStateException("explicit action failure");
        Runnable action = () -> {
            throw failure;
        };
        var weak = registry.make(new Object(), new Object(), action);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<Future<WeakResult>>();
            for (int i = 0; i < 2; i++)
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(3, TimeUnit.SECONDS))
                        throw new IllegalStateException("Check failed.");
                    return registry.finalize(weak);
                }));
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            start.countDown();
            var results = new ArrayList<WeakResult>();
            for (var future : futures) results.add(future.get(3, TimeUnit.SECONDS));
            var flags = new ArrayList<Long>();
            for (var result : results) flags.add(result.getFlag());
            Collections.sort(flags);
            assertEquals(List.of(0L, 1L), flags);
            WeakResult claimed = null;
            for (var result : results)
                if (result.getFlag() == 1L) {
                    if (claimed != null)
                        throw new IllegalArgumentException("Multiple successful finalizers");
                    claimed = result;
                }
            if (claimed == null)
                throw new NoSuchElementException("Missing successful finalizer");
            assertSame(action, claimed.getValue());
            assertSame(failure, assertThrows(IllegalStateException.class, action::run));
            assertEquals(0L, registry.finalize(weak).getFlag());
            assertEquals(0, registry.retainedCount());
        } finally {
            start.countDown();
            workers.shutdownNow();
            registry.close();
        }
    }
    private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E {
        throw (E) failure;
    }
    @Test
    void explicitCallbacksRunNewestFirstAfterDeathOutsideTheRegistryLock() {
        var registry = new ManagedWeaks();
        var weak = registry.make(new Object(), new Object(), null);
        var observed = new ArrayList<Integer>();
        var worker = Executors.newSingleThreadExecutor();
        try {
            assertEquals(1L, registry.addCallback(weak, () -> observed.add(1)));
            assertEquals(1L, registry.addCallback(weak, () -> {
                try {
                    assertEquals(
                        0L, worker.submit(() -> registry.dereference(weak).getFlag()).get(3, TimeUnit.SECONDS));
                } catch (InterruptedException | ExecutionException | TimeoutException failure) {
                    throw rethrow(failure);
                }
                observed.add(2);
            }));
            assertEquals(0L, registry.finalize(weak).getFlag());
            assertEquals(List.of(2, 1), observed);
            assertEquals(0L, registry.addCallback(weak, () -> observed.add(3)));
            assertEquals(0L, registry.finalize(weak).getFlag());
            registry.close();
            assertEquals(List.of(2, 1), observed);
        } finally {
            worker.shutdownNow();
            registry.close();
        }
    }
    @Test
    void actualContextCloseInvalidatesHandlesWithoutRunningHaskellActions() {
        var first = context();
        first.initialize("thc");
        first.enter();
        var owner = Language.currentState().getWeaks();
        int[] calls = {0};
        Supplier<Integer> action = () -> ++calls[0];
        var handle = owner.make(new Object(), new Object(), action);
        first.leave();
        try (var second = context()) {
            second.initialize("thc");
            second.enter();
            try {
                assertThrows(RuntimeFault.class, () -> Language.currentState().getWeaks().dereference(handle));
                assertThrows(RuntimeFault.class, () -> Language.currentState().getWeaks().finalize(handle));
                assertThrows(RuntimeFault.class, () -> Language.currentState().getWeaks().dereference(new Object()));
            } finally {
                second.leave();
            }
        }
        first.close();
        assertEquals(0, calls[0]);
        assertEquals(0, owner.retainedCount());
        assertThrows(RuntimeFault.class, () -> owner.finalize(handle));
        assertThrows(RuntimeFault.class, () -> owner.make(new Object(), new Object(), null));
    }
    @Test
    void mainThreadCapabilityProjectsOnlyLiveContextOwnedThreadKeys() {
        var first = context();
        first.initialize("thc");
        first.enter();
        MainThreadWeakKey capability;
        MainThreadWeakKey closing;
        try {
            var state = Language.currentState();
            var owner = state.getWeaks();
            var threads = state.getThreads();
            threads.enterCurrent(null, false, true, null);
            var key = threads.currentIdentity();
            var weak = owner.make(key, new Object(), null);
            try {
                capability = owner.mainThreadKey(weak, threads);
                assertEquals(Thread.currentThread().threadId(), capability.liveJavaId(),
                    "Native main-thread projection reads KEY, not value");
                var impostor = new GuestThreadId(
                    key.getLogicalId(), threads, key.getCapability(), Thread.currentThread(), false, false, false);
                assertEquals(key, impostor, "Numeric equality alone must not establish canonical identity");
                assertNull(threads.liveJavaId(impostor));
                assertThrows(RuntimeFault.class, () -> owner.mainThreadKey(owner.make(impostor, new Object(), null), threads));
                closing = owner.mainThreadKey(owner.make(key, new Object(), null), threads);
                var wrongKey = owner.make(new Object(), key, null);
                assertThrows(RuntimeFault.class, () -> owner.mainThreadKey(wrongKey, threads));
            } finally {
                threads.leaveCurrent(GuestThreadStatus.FINISHED);
            }
            assertEquals(key.getJavaId(), capability.liveJavaId(), "A live FOREIGN host carrier is not terminated");
            try (var second = context()) {
                second.initialize("thc");
                second.enter();
                try {
                    var foreign = Language.currentState();
                    foreign.getThreads().enterCurrent(null, false, true, null);
                    try {
                        assertThrows(
                            RuntimeFault.class, () -> foreign.getWeaks().mainThreadKey(weak, foreign.getThreads()));
                        assertThrows(RuntimeFault.class, () -> owner.mainThreadKey(weak, foreign.getThreads()));
                        assertThrows(RuntimeFault.class, () -> foreign.getThreads().liveJavaId(key));
                        var foreignKey = owner.make(foreign.getThreads().currentIdentity(), new Object(), null);
                        assertThrows(RuntimeFault.class, () -> owner.mainThreadKey(foreignKey, threads));
                    } finally {
                        foreign.getThreads().leaveCurrent(GuestThreadStatus.FINISHED);
                    }
                } finally {
                    second.leave();
                }
            }
            assertEquals(0L, owner.finalize(weak).getFlag());
            assertNull(capability.liveJavaId());
            assertThrows(RuntimeFault.class, () -> owner.mainThreadKey(weak, threads));
            assertEquals(key.getJavaId(), closing.liveJavaId());
            threads.close();
            assertNull(closing.liveJavaId(), "Thread service close invalidates even a still-live weak registration");
        } finally {
            first.leave();
            first.close();
        }
        assertNull(closing.liveJavaId());
        assertNull(capability.liveJavaId());
    }
    private record ThreadOutcome(boolean forked, GuestThreadStatus outcome) {}
    @Test
    void mainThreadCapabilityDoesNotResurrectTerminalIdentitiesOrDeadOriginalCarriers() throws Exception {
        for (var test : List.of(new ThreadOutcome(true, GuestThreadStatus.FINISHED),
                 new ThreadOutcome(true, GuestThreadStatus.DIED),
                 new ThreadOutcome(true, GuestThreadStatus.RUNTIME_FAILURE),
                 new ThreadOutcome(false, GuestThreadStatus.FINISHED))) {
            boolean forked = test.forked();
            var outcome = test.outcome();
            var threads = new GuestThreads(
                ThreadLocal.withInitial(() -> MaskingState.UNMASKED), CpuAffinity.discover(false), ignored -> {});
            var owner = new ManagedWeaks();
            var capability = new AtomicReference<MainThreadWeakKey>();
            var weak = new AtomicReference<Object>();
            var failure = new AtomicReference<Throwable>();
            var ready = new CountDownLatch(1);
            var leave = new CountDownLatch(1);
            var left = new CountDownLatch(1);
            var stop = new CountDownLatch(1);
            var worker = new Thread(() -> {
                boolean entered = false;
                try {
                    threads.enterCurrent(null, forked, true, null);
                    entered = true;
                    weak.set(owner.make(threads.currentIdentity(), new Object(), null));
                    capability.set(owner.mainThreadKey(weak.get(), threads));
                    ready.countDown();
                    if (!leave.await(3, TimeUnit.SECONDS))
                        throw new IllegalStateException("Check failed.");
                    threads.leaveCurrent(outcome);
                    entered = false;
                    left.countDown();
                    if (!stop.await(3, TimeUnit.SECONDS))
                        throw new IllegalStateException("Check failed.");
                } catch (Throwable error) {
                    failure.set(error);
                    ready.countDown();
                    left.countDown();
                } finally {
                    if (entered)
                        threads.leaveCurrent(outcome);
                }
            });
            worker.setDaemon(true);
            worker.start();
            try {
                assertTrue(ready.await(3, TimeUnit.SECONDS));
                if (failure.get() != null)
                    throw new AssertionError("thread registration failed", failure.get());
                assertEquals(worker.threadId(), capability.get().liveJavaId());
                leave.countDown();
                assertTrue(left.await(3, TimeUnit.SECONDS));
                if (failure.get() != null)
                    throw new AssertionError("thread completion failed", failure.get());
                assertTrue(worker.isAlive(), "Separate terminal guest status from actual Java carrier death");
                if (forked)
                    assertNull(capability.get().liveJavaId());
                else
                    assertEquals(worker.threadId(), capability.get().liveJavaId());
                stop.countDown();
                worker.join(3000);
                assertFalse(worker.isAlive());
                if (failure.get() != null)
                    throw new AssertionError("worker failed", failure.get());
                assertNull(capability.get().liveJavaId());
                assertEquals(1L, owner.dereference(weak.get()).getFlag(), "Liveness query must not finalize the weak");
                threads.close();
                assertNull(capability.get().liveJavaId());
            } finally {
                leave.countDown();
                stop.countDown();
                worker.join(3000);
                threads.close();
                owner.close();
            }
        }
    }
    private CoreRepresentation proof(CoreKind kind, List<String> reps) {
        return new CoreRepresentation(kind, false, true, reps, null, null, null, null, null);
    }
    private final CoreRepresentation state = proof(CoreKind.VOID, List.of()),
                                     weak = proof(CoreKind.OBJECT, List.of("BoxedRep (Just Unlifted)")),
                                     lifted = proof(CoreKind.DATA, List.of("BoxedRep (Just Lifted)")),
                                     action = proof(CoreKind.CLOSURE, List.of("BoxedRep (Just Lifted)")),
                                     flag = proof(CoreKind.LONG, List.of("IntRep")),
                                     address = proof(CoreKind.ADDRESS, List.of("AddrRep"));
    private final List<WeakOp> originalOps =
        List.of(WeakOp.MAKE, WeakOp.MAKE_PLAIN, WeakOp.DEREFERENCE, WeakOp.FINALIZE);
    private CoreRepresentation tuple(CoreRepresentation... fields) {
        var reps = new ArrayList<String>();
        for (var field : fields) reps.addAll(Objects.requireNonNull(field.getPrimReps()));
        return new CoreRepresentation(
            CoreKind.UNKNOWN, false, true, reps, Arrays.asList(fields), null, null, null, null);
    }
    private CoreRepresentation withReps(CoreRepresentation original, List<String> reps) {
        return original.copy(original.getKind(), original.getEvaluated(), original.getPresent(), reps,
            original.getComponents(), original.getVector(), original.getAlternatives(), original.getTagSlot(),
            original.getAlternativeSlots());
    }
    private List<CoreRepresentation> inputs(WeakOp op, CoreRepresentation value) {
        return switch (op) {
            case MAKE -> List.of(weak, value, action, state);
            case MAKE_PLAIN -> List.of(weak, value, state);
            case ADD_C_FINALIZER -> List.of(address, address, flag, address, weak, state);
            default -> List.of(weak, state);
        };
    }
    private CoreRepresentation output(WeakOp op, CoreRepresentation value) {
        return switch (op) {
            case MAKE, MAKE_PLAIN -> tuple(state, weak);
            case ADD_C_FINALIZER -> tuple(state, flag);
            case DEREFERENCE -> tuple(state, flag, value);
            case FINALIZE -> tuple(state, flag, action);
        };
    }
    private List<Boolean> flags(List<CoreRepresentation> args) {
        var result = new ArrayList<Boolean>();
        for (var arg : args) result.add(List.of("BoxedRep (Just Lifted)").equals(arg.getPrimReps()));
        return result;
    }
    @Test
    void exactWeakContractsRejectForgedStateRepresentationOrBinding() {
        assertEquals(5, WeakOp.values().length);
        for (var op : WeakOp.values())
            for (var value : List.of(lifted, action, weak)) {
                var args = inputs(op, value);
                var result = output(op, value);
                op.validate(args, flags(args), result);
                assertThrows(
                    RuntimeFault.class, () -> op.validate(args.subList(0, args.size() - 1), flags(args), result));
                for (int index = 0; index < args.size(); index++) {
                    var bad = new ArrayList<>(args);
                    bad.set(index, withReps(flag, List.of("WordRep")));
                    assertThrows(RuntimeFault.class, () -> op.validate(bad, flags(bad), result));
                    var wrongFlags = new ArrayList<>(flags(args));
                    wrongFlags.set(index, !wrongFlags.get(index));
                    assertThrows(RuntimeFault.class, () -> op.validate(args, wrongFlags, result));
                    var stored = new ArrayList<>(args);
                    stored.set(index, withReps(flag, List.of("WordRep")));
                    assertThrows(RuntimeFault.class, () -> op.validateBindings(args, stored));
                }
                assertThrows(RuntimeFault.class, () -> op.validate(args, flags(args), withReps(result, List.of())));
                assertThrows(RuntimeFault.class,
                    ()
                        -> op.validate(args, flags(args),
                            tuple(Objects.requireNonNull(result.getComponents())
                                    .subList(1, result.getComponents().size())
                                    .toArray(CoreRepresentation[] ::new))));
            }
        WeakOp.MAKE.validateAction(new CoreFunctionSignature(List.of(state), tuple(state, lifted)));
        assertThrows(RuntimeFault.class,
            () -> WeakOp.MAKE.validateAction(new CoreFunctionSignature(List.of(flag), tuple(state, lifted))));
        assertThrows(RuntimeFault.class,
            () -> WeakOp.MAKE.validateAction(new CoreFunctionSignature(List.of(state), tuple(state, weak))));
        assertThrows(
            RuntimeFault.class, () -> WeakOp.MAKE.validateAction(new CoreFunctionSignature(List.of(state), state)));
    }
    private final File root = new File(System.getProperty("thc.projectRoot")),
                       directory = new File(root, "build/weak-explicit");
    private Map<String, Object> json(File file) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(file.toPath()));
    }
    private String digest(File file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));
    }
    private void valid(RootCallTarget target) throws Exception {
        assertEquals(
            true, target.getClass().getMethod("isValidLastTier").invoke(target), target.getRootNode().getName());
    }
    private List<RootCallTarget> activeTargets(RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>());
        var found = new ArrayList<RootCallTarget>();
        visit(entry, seen, found);
        return found;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> found) {
        if (!seen.add(target))
            return;
        var body = target.getRootNode();
        var nodes = new ArrayList<Node>();
        nodes.add(body);
        if (body instanceof BytecodeRoot bytecode)
            for (var instruction : bytecode.getBytecodeNode().getInstructions())
                for (var argument : instruction.getArguments())
                    if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                        var node = argument.asCachedNode();
                        if (node != null)
                            nodes.add(node);
                    }
        for (var node : nodes)
            for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
                if (call.getCurrentCallTarget() instanceof RootCallTarget callee
                    && callee.getRootNode() instanceof GuestRoot)
                    visit(callee, seen, found);
        found.add(target);
    }
    private List<List<Object>> applications(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.getFirst()))
                result.add((List<Object>) list);
            for (var child : list) result.addAll(applications(child));}else if(value instanceof Map<?,?> map)
            for (var child : map.values()) result.addAll(applications(child));
        return result;
    }
    private boolean primitive(List<?> expression, String name) {
        return expression.size() > 1 && expression.get(1) instanceof List<?> head && head.size() >= 2
            && head.subList(0, 2).equals(List.of("prim", name));
    }
    private List<Object> firstPrimitive(Object module, String name) {
        for (var app : applications(module))
            if (primitive(app, name))
                return app;
        throw new NoSuchElementException("Missing primitive " + name);
    }
    private Map<String, Object> merge(List<String> paths) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (var path : paths) modules.add(json(new File(root, path)));
        return CoreModules.merge(modules);
    }
    private ExecutableProgram load(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    @Test
    void genuineCoreCannotBypassEitherLoaderWithForgedWeakContracts() throws Exception {
        var manifest = json(new File(directory, "manifest.json"));
        for (var stageEntry : ((Map<String, List<String>>) manifest.get("stages")).entrySet()) {
            var stage = stageEntry.getKey();
            var original = CoreModules.reachable(merge(stageEntry.getValue()), "weakComposite", true);
            for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        for (var op : originalOps)
                            for (int mutation = 0; mutation <= 2; mutation++) {
                                var module = (Map<String, Object>) Json.parse(Json.stringify(original));
                                var app = firstPrimitive(module, op.getPrimitive());
                                var args = (List<Object>) app.get(2);
                                switch (mutation) {
                                    case 0 -> {
                                        var flags = (List<Object>) app.get(3);
                                        flags.set(0, !(Boolean) flags.get(0));
                                    }
                                    case 1 ->
                                        ((Map<String, Object>) CoreRepresentations.metadata(
                                             (List<Object>) args.getLast()))
                                            .put("rep", Map.of("kind", "long", "primReps", List.of("IntRep")));
                                    case 2 -> {
                                        var result = (Map<String, Object>) Objects
                                                         .requireNonNull(CoreRepresentations.metadata(app))
                                                         .get("rep");
                                        ((List<Object>) result.get("components")).remove(0);
                                    }
                                }
                                assertThrows(RuntimeFault.class,
                                    ()
                                        -> load(language, module, backend),
                                    stage + "/" + backend + "/" + op.getPrimitive() + "/mutation=" + mutation);
                            }
                    } finally {
                        context.leave();
                    }
                }
        }
    }
    private record Row(long input, long expected) {}
    private List<Row> rows() throws Exception {
        var rows = new ArrayList<Row>();
        for (var line : Files.readAllLines(new File(directory, "oracle.tsv").toPath())) {
            var fields = line.split("\t", -1);
            rows.add(new Row(Long.parseLong(fields[0]), Long.parseLong(fields[1])));
        }
        return rows;
    }
    @Test
    void delayedBoxedCasesPreserveWeakOperandProofs() throws Exception {
        var manifest = json(new File(directory, "manifest.json"));
        var rows = rows();
        var integer = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        var zero = List.of("lit", "int", "0", Map.of("rep", integer));
        for (var stageEntry : ((Map<String, List<String>>) manifest.get("stages")).entrySet()) {
            var stage = stageEntry.getKey();
            var original = CoreModules.reachable(merge(stageEntry.getValue()), "weakComposite", true);
            for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        for (boolean contradictory : new boolean[] {false, true}) {
                            var module = (Map<String, Object>) Json.parse(Json.stringify(original));
                            var app = firstPrimitive(module, "mkWeak#");
                            var args = (List<Object>) app.get(2);
                            var value = (List<Object>) args.get(1);
                            var proof =
                                (Map<String, Object>) Objects.requireNonNull(CoreRepresentations.metadata(value))
                                    .get("rep");
                            // The original ForeignPtr finalizer uses this lifted CASE shape: delaying it must retain
                            // its value proof, not claim WHNF.
                            var delayed = new LinkedHashMap<>(proof);
                            delayed.put("evaluated", false);
                            args.set(1,
                                List.of("case", zero, "delayedWeakCase",
                                    List.of(Arrays.asList("default", null, List.of(), contradictory ? zero : value)),
                                    Map.of("rep", delayed, "binder",
                                        Map.of("id", "delayedWeakCase", "lifted", false, "rep", integer))));
                            if (contradictory)
                                assertThrows(RuntimeFault.class,
                                    ()
                                        -> load(language, module, backend),
                                    stage + "/" + backend + " contradictory delayed result");
                            else {
                                var function = context.asValue(
                                    new EntryValue(load(language, module, backend), "weakComposite", 1));
                                for (var row : rows)
                                    assertEquals(row.expected(), function.execute(row.input()).asLong(),
                                        stage + "/" + backend + "/" + row.input());
                                assertEquals(0, Language.currentState().getWeaks().retainedCount());
                                assertEquals(0, language.getHandoffState().get().getArguments().retainedReferences());
                                assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
                            }
                        }
                    } finally {
                        context.leave();
                    }
                }
        }
    }
    @Test
    void genuineNativeCompositeAgreesOnTheFirstInstalledAstAndBytecodeCalls() throws Exception {
        var manifest = json(new File(directory, "manifest.json"));
        assertEquals(1L, manifest.get("schema"));
        assertEquals("9.14.1", manifest.get("ghc"));
        assertEquals("weakComposite", manifest.get("entry"));
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var item : ((Map<String, String>) manifest.get(kind)).entrySet())
                assertEquals(item.getValue(), digest(new File(root, item.getKey())),
                    "Stale explicit weak fixture: " + item.getKey());
        var rows = rows();
        assertEquals(8, rows.size());
        assertEquals((long) rows.size(), manifest.get("nativeRows"));
        for (var row : rows) assertEquals(row.input() + 58L, row.expected());
        for (var stageEntry : ((Map<String, List<String>>) manifest.get("stages")).entrySet()) {
            var stage = stageEntry.getKey();
            var audit = json(new File(directory, stage + "/audit.json"));
            assertEquals(true, audit.get("accepted"));
            assertEquals(List.of(), audit.get("issues"));
            assertEquals(List.of(), audit.get("missingGlobals"));
            var primitives = new HashSet<Object>();
            for (var p : (List<Map<String, Object>>) audit.get("primitives")) primitives.add(p.get("name"));
            var names = new ArrayList<String>();
            for (var op : originalOps) names.add(op.getPrimitive());
            assertTrue(primitives.containsAll(names));
            assertFalse(primitives.contains("addCFinalizerToWeak#"));
            var merged = merge(stageEntry.getValue());
            var proof = new ArrayCoreEvidence(merged, "weakComposite");
            var lambda = (List<?>) proof.getRoot().get("expr");
            var exported = proof.guestLambdas(lambda);
            var types = new ArrayList<List<Object>>();
            for (var expr : exported) {
                var parameters = new ArrayList<Object>();
                for (var formal : (List<Map<?, ?>>) expr.get(1)) parameters.add(formal.get("type"));
                types.add(parameters);
            }
            assertEquals(List.of(List.of("Int#"), List.of("State# RealWorld"), List.of("State# RealWorld")), types);
            assertSame(exported.get(1), proof.immediateStateLambda(lambda.get(2)),
                "Only the exact void State# application executes in-frame");
            List<Object> registration = null;
            for (var expr : proof.nodes(lambda))
                if (!expr.isEmpty() && "app".equals(expr.getFirst()) && primitive(expr, "mkWeak#")
                    && ((List<?>) expr.get(2)).get(2) instanceof List<?> finalizer && !finalizer.isEmpty()
                    && "lam".equals(finalizer.getFirst())) {
                    if (registration != null)
                        throw new IllegalArgumentException("Multiple weak registrations");
                    registration = expr;
                }
            if (registration == null)
                throw new NoSuchElementException("Missing weak registration");
            assertSame(
                exported.get(2), ((List<?>) registration.get(2)).get(2), "Retain the real finalizer action root");
            var lowered = proof.loweredGuestLambdas(lambda);
            assertEquals(List.of(exported.get(0), exported.get(2)), lowered);
            long expectedEntries = lowered.size();
            assertEquals(2L, expectedEntries, "Public input and returned finalizer execute once; runRW is in-frame");
            for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var module = new LinkedHashMap<>(CoreModules.reachable(merged, "weakComposite", true));
                        module.put("instrument", true);
                        var program = load(language, module, backend);
                        var host = program.hostEntryTarget(1);
                        var original = program.entryTarget("weakComposite");
                        assertTrue(original.getRootNode() instanceof GuestRoot);
                        var function = context.asValue(new EntryValue(program, "weakComposite", 1));
                        for (var row : rows) assertEquals(row.expected(), function.execute(row.input()).asLong());
                        var active = activeTargets(host);
                        boolean linked = false;
                        for (var target : active)
                            linked |= target.getRootNode() instanceof GuestRoot guest && guest.isSelf(original);
                        assertTrue(linked, stage + "/" + backend + " actual weakComposite host linkage");
                        // Include the original identity and every observed split/worker target, including BytecodeDSL's
                        // cached direct calls. Do not settle after install.
                        var installed = new ArrayList<>(new LinkedHashSet<>(active));
                        if (!installed.contains(original))
                            installed.add(original);
                        for (var target : installed) {
                            target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                            valid(target);
                        }
                        for (var row : rows.reversed()) {
                            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            assertEquals(row.expected(), function.execute(row.input()).asLong(),
                                stage + "/" + backend + " first installed " + row.input());
                            long delta = ((Number) program.diagnostics().get("compiledEntries")).longValue() - before;
                            var states = new ArrayList<String>();
                            for (var target : installed)
                                states.add(target.getRootNode().getName()
                                    + ":valid=" + target.getClass().getMethod("isValidLastTier").invoke(target));
                            System.out.println("weak-explicit " + stage + "/" + backend + " input=" + row.input()
                                + " compiledGuestEntries=" + delta + " targets=" + states);
                            // EntryRoot does not increment compiledEntries; this is guest entry evidence.
                            assertEquals(expectedEntries, delta,
                                stage + "/" + backend + " compiled input/returned-action guest entries");
                            assertSame(original, program.entryTarget("weakComposite"));
                            assertEquals(active, activeTargets(host), stage + "/" + backend + " target graph changed");
                            for (var target : installed) valid(target);
                            assertEquals(0, Language.currentState().getWeaks().retainedCount());
                            assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                            assertEquals(0, language.getHandoffState().get().getArguments().retainedReferences());
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                            assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
                            assertNull(language.getHandoffState().get().getPending());
                            assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                        }
                        assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                    } finally {
                        context.leave();
                    }
                }
        }
    }
}
