// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.*;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(180)
@SuppressWarnings("unchecked")
public class STMFullCoreTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final List<String> names = List.of("basic", "rollback", "alternative", "lazyPayload", "nestedAtomic", "unliftedPayload", "newtypeField", "newtypeAlternative", "newtypeCatch");
    private Context context(boolean inlining) { return Context.newBuilder("thc", "llvm").allowNativeAccess(true).allowIO(IOAccess.ALL).allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", Boolean.toString(inlining)).option("engine.SingleTierCompilationThreshold", "10000000").build(); }
    private Map<String, Object> read(String path) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath())); }
    private Map<String, Object> fixture() throws Exception {
        var manifest = read("build/stm/manifest.json"); assertEquals("9.14.1", manifest.get("ghc")); assertEquals(names, manifest.get("entries")); assertEquals(87, ((Number) manifest.get("nativeRows")).intValue());
        for (var kind : List.of("inputHashes", "artifactHashes")) for (var e : ((Map<String, String>) manifest.get(kind)).entrySet()) assertEquals(e.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, e.getKey()).toPath()))), "Stale STM fixture: " + e.getKey());
        for (var stage : List.of("pre", "post")) { var selection = (Map<String, String>) (Map<?, ?>) read("build/stm/" + stage + "/original-selection.json"); assertFalse(selection.isEmpty()); for (var e : selection.entrySet()) { var origin = e.getValue().split("!/", 2); try (var zip = new ZipFile(new File(root, origin[0])); var input = zip.getInputStream(zip.getEntry(origin[1]))) { assertArrayEquals(input.readAllBytes(), Files.readAllBytes(new File(root, e.getKey()).toPath()), "Original installed module was rewritten: " + e.getKey()); } } }
        return manifest;
    }
    private TargetLayout targetLayout;
    private TargetLayout targetLayout() throws Exception { if (targetLayout == null) targetLayout = Objects.requireNonNull(CoreCbdFixtures.visitModules(new File(root, "build/stm/installed/packages.json").getPath(), (module, origin) -> {}).getTargetLayout()); return targetLayout; }
    private Map<String, Object> module(List<String> paths) throws Exception { var modules = new ArrayList<Map<String, Object>>(); for (var path : paths) modules.add(read(path)); var result = new LinkedHashMap<>(CoreModules.merge(modules)); result.put("targetLayout", targetLayout()); return result; }
    private long model(String name, long x) { return switch (name) { case "basic" -> (18 * x + 3) * 31 + x + 3; case "rollback" -> (18 * x + 19) * 31 + 2 * x + 3; case "alternative" -> 32 * x + 7; case "lazyPayload" -> x + 42; case "nestedAtomic" -> x + 31; case "unliftedPayload" -> x + 9; case "newtypeField" -> x + 11; case "newtypeAlternative" -> x + 13; case "newtypeCatch" -> x + 17; default -> throw new IllegalStateException(name); }; }
    private void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, "installed"); }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> result) {
        if (!seen.add(target)) return; var body = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(body);
        if (body instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions()) for (var arg : instruction.getArguments()) if (arg.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var cached = arg.asCachedNode(); if (cached != null) nodes.add(cached); }
        for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class)) if (call.getCurrentCallTarget() instanceof RootCallTarget active && active.getRootNode() instanceof GuestRoot) visit(active, seen, result);
        result.add(target);
    }
    private List<RootCallTarget> activeTargets(RootCallTarget entry) { var result = new ArrayList<RootCallTarget>(); visit(entry, Collections.newSetFromMap(new IdentityHashMap<>()), result); return result; }
    private void released(Language language) { var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getResults().retainedReferences()); assertNull(handoff.getPending()); assertFalse(Language.currentState(null).stm.hasTransaction()); }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) { return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module); }
    private record Row(String name, long input, long expected) {}
    private record Key(String name, long input) {}
    private List<Row> rows() throws Exception { var rows = new ArrayList<Row>(); for (var line : Files.readAllLines(new File(root, "build/stm/oracle.tsv").toPath())) { var fields = line.split("\t", -1); rows.add(new Row(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2]))); } return rows; }
    private void check(Value function, Language language, String name, String label, Row row) { assertEquals(model(name, row.input), row.expected, "Native model " + label + "/" + row.input); assertEquals(row.expected, function.execute(row.input).asLong(), label + "/" + row.input); released(language); }
    @Test public void originalCoreMatchesNativeWithInlining() throws Exception { nativeRows(true, "ast"); }
    @Test public void originalCoreMatchesNativeAcrossResidualCalls() throws Exception { nativeRows(false, "ast"); }
    @Test public void originalBytecodeCoreMatchesNativeWithInlining() throws Exception { nativeRows(true, "bytecode"); }
    @Test public void originalBytecodeCoreMatchesNativeAcrossResidualCalls() throws Exception { nativeRows(false, "bytecode"); }
    private void nativeRows(boolean inlining, String backend) throws Exception {
        var manifest = fixture(); var rows = rows(); assertEquals(87, rows.size()); var keys = new HashSet<Key>(); var originals = new LinkedHashMap<String, List<Row>>();
        for (var row : rows) { keys.add(new Key(row.name, row.input)); if (names.contains(row.name)) originals.computeIfAbsent(row.name, ignored -> new ArrayList<>()).add(row); } assertEquals(87, keys.size()); assertEquals(new HashSet<>(names), originals.keySet());
        for (var stage : ((Map<String, List<String>>) manifest.get("stages")).entrySet()) { var module = module(stage.getValue()); for (var name : names) try (var context = context(inlining)) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var linked = new LinkedHashMap<>(CoreModules.reachable(module, name, true)); linked.put("instrument", true); var program = program(language, linked, backend); var entry = program.entryTarget(name); var host = program.hostEntryTarget(1); var function = context.asValue(new EntryValue(program, name, 1)); var cases = originals.get(name); var inputs = new ArrayList<Long>(); for (var row : cases) inputs.add(row.input); assertEquals(List.of(-31L, -1L, 0L, 1L, 17L, 63L, 4097L), inputs); String label = stage.getKey() + "/" + backend + "/" + name + "/inlining=" + inlining;
            for (var row : cases) check(function, language, name, label, row); var active = activeTargets(host); var installed = new LinkedHashSet<>(active); installed.add(entry); for (var target : installed) if (target != host) compile(target); assertTrue(function.invokeMember("compile").asBoolean());
            // No settling call: every first installed entry is checked immediately.
            for (var row : cases) { long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); check(function, language, name, label, row);
                // runRW is beta-reduced; catch/orElse and opaque newtype consumers add exact roots.
                long entered = switch (name) { case "rollback", "alternative", "nestedAtomic" -> 4L; case "newtypeAlternative", "newtypeCatch" -> 5L; case "newtypeField" -> 3L; default -> 2L; };
                assertEquals(before + entered, ((Number) program.diagnostics().get("compiledEntries")).longValue(), label + " exact original guest-root count on first installed call"); valid(entry, label + " original entry"); assertEquals(active, activeTargets(host), label + " active identities"); for (var target : active) valid(target, label + "/" + row.input + " active target " + target.getRootNode().getName());
            }
            assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
        } finally { context.leave(); } } }
    }
    private Object call(ExecutableProgram program, String name, Object... args) { return Calls.target(program.hostEntryTarget(args.length), new Object[] {program.entryValue(name), args.clone()}); }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    public void autonomousAtomicStackMatchesNative(String backend) throws Exception { autonomousStack("stackAtomic", backend); }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    public void autonomousNestedCatchStackMatchesNative(String backend) throws Exception { autonomousStack("stackCatch", backend); }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    public void autonomousNestedAlternativeStackMatchesNative(String backend) throws Exception { autonomousStack("stackAlternative", backend); }
    private void autonomousStack(String name, String backend) throws Exception {
        var manifest = fixture();
        var cases = rows().stream().filter(row -> row.name.equals(name)).toList();
        assertEquals(List.of(1L, 100L, 4096L), cases.stream().map(Row::input).toList());
        for (var stage : ((Map<String, List<String>>) manifest.get("stages")).entrySet())
                try (var context = Context.newBuilder("thc", "llvm").allowNativeAccess(true).allowIO(IOAccess.ALL)
                        .allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var source = CoreModules.reachable(module(stage.getValue()), name, true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, source, true)
                            : new BytecodeProgram(language, source, true);
                        var state = Language.currentState(); state.getThreads().enterCurrent();
                        try {
                            for (var row : cases) {
                                long before = state.getThreadPollState().get().getAstStack().getSpills();
                                assertEquals(row.expected, call(program, name, row.input), stage.getKey() + "/" + backend + "/" + name + "/" + row.input);
                                var stack = state.getThreadPollState().get().getAstStack();
                                if (row.input >= 100) assertTrue(stack.getSpills() > before, "Nested STM scopes must not defeat boundedness");
                                assertEquals(0, stack.getDepth()); assertFalse(stack.getDriving());
                                released(language);
                            }
                        } finally { state.getThreads().leaveCurrent(); }
                    } finally { context.leave(); }
                }
    }
    @Test public void genuineConcurrentUpdatesAndEitherReadSetWakeMatchNative() throws Exception {
        var manifest = fixture(); var nativeRows = new LinkedHashMap<Key, Long>(); for (var row : rows()) nativeRows.put(new Key(row.name, row.input), row.expected); assertEquals(128L, nativeRows.get(new Key("concurrent", 128))); assertEquals(119L, nativeRows.get(new Key("either", 0))); assertEquals(7L, nativeRows.get(new Key("either", 1)));
        for (var paths : ((Map<String, List<String>>) manifest.get("stages")).values()) for (var backend : List.of("ast", "bytecode")) { var workers = Executors.newFixedThreadPool(2); try (var context = context(true)) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var program = program(language, CoreModules.reachable(module(paths), List.of("newCell", "readCell", "bumpCell", "awaitCell", "awaitEither"), true), backend); var stm = Language.currentState(null).stm; var cell = call(program, "newCell", 0L); var start = new CountDownLatch(1); var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i <= 1; i++) jobs.add(workers.submit(() -> { context.enter(); try { assertTrue(start.await(5, TimeUnit.SECONDS)); for (int j = 0; j < 64; j++) call(program, "bumpCell", cell, 1L); released(language); return null; } finally { context.leave(); } }));
            start.countDown(); for (var job : jobs) job.get(15, TimeUnit.SECONDS); assertEquals(nativeRows.get(new Key("concurrent", 128)), call(program, "readCell", cell));
            for (int side = 0; side <= 1; side++) { var a = call(program, "newCell", 0L); var b = call(program, "newCell", 0L); var waiting = workers.submit(() -> { context.enter(); try { var result = call(program, "awaitEither", a, b); released(language); return result; } finally { context.leave(); } }); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); while (stm.pendingWaiters() != 1 && System.nanoTime() < deadline) Thread.yield(); assertEquals(1, stm.pendingWaiters(), "genuine Core did not register retry"); call(program, "bumpCell", side == 0 ? a : b, 7L); assertEquals(nativeRows.get(new Key("either", side)), waiting.get(5, TimeUnit.SECONDS)); assertEquals(0, stm.pendingWaiters()); }
            released(language);
        } finally { context.leave(); workers.shutdownNow(); } } }
    }
    private void nodes(Object value, List<List<?>> result) { if (value instanceof Map<?, ?> map) for (var child : map.values()) nodes(child, result); else if (value instanceof List<?> list) { result.add(list); for (var child : list) nodes(child, result); } }
    private void collectBinders(Object value, Map<String, CoreRepresentation> binders) { if (value instanceof Map<?, ?> map) { if (map.get("id") instanceof String id && map.get("type") instanceof String) binders.put(id, CoreRepresentations.parse(map.get("rep"))); for (var child : map.values()) collectBinders(child, binders); } else if (value instanceof List<?> list) for (var child : list) collectBinders(child, binders); }
    @Test public void allEightPinnedContractsAndAsyncAdmission() throws Exception {
        var manifest = fixture(); var paths = ((Map<String, List<String>>) manifest.get("stages")).get("pre"); var full = module(paths); var consumer = read(paths.getFirst()); var nodes = new ArrayList<List<?>>(); nodes(consumer, nodes); var calls = new ArrayList<List<?>>();
        for (var node : nodes) if (!node.isEmpty() && Objects.equals(node.getFirst(), "app") && node.size() > 1 && node.get(1) instanceof List<?> head && head.size() > 1 && head.get(1) instanceof String name && STMOp.named(name) != null) calls.add(node);
        var expected = new HashSet<String>(); for (var op : STMOp.values()) expected.add(op.getPrimitive()); var observed = new HashSet<Object>(); for (var call : calls) observed.add(((List<?>) call.get(1)).get(1)); assertEquals(expected, observed); var binders = new HashMap<String, CoreRepresentation>(); collectBinders(consumer, binders); var newtypeActions = new HashSet<String>();
        for (var call : calls) { var op = Objects.requireNonNull(STMOp.named((String) ((List<?>) call.get(1)).get(1))); var arguments = (List<List<Object>>) call.get(2); for (int index = 0; index < arguments.size(); index++) { var arg = arguments.get(index); if (!op.getArguments().get(index).equals("action") || !Objects.equals(arg.getFirst(), "var")) continue; var stored = binders.get(arg.get(1)); if (stored == null || stored.getKind() != CoreKind.OBJECT) continue; var occurrence = CoreRepresentations.expression(arg); assertEquals(List.of("BoxedRep (Just Lifted)"), stored.getPrimReps()); assertEquals(stored.getPrimReps(), occurrence.getPrimReps()); assertEquals(CoreKind.CLOSURE, occurrence.getKind()); assertEquals(CoreKind.CLOSURE, stored.refine(occurrence).getKind()); newtypeActions.add(op.getPrimitive()); } }
        assertEquals(Set.of("atomically#", "catchRetry#", "catchSTM#"), newtypeActions, "The genuine optimized exports must retain the opaque STM/newtype field boundary");
        for (var call : calls) { var op = Objects.requireNonNull(STMOp.named((String) ((List<?>) call.get(1)).get(1))); var args = new ArrayList<CoreRepresentation>(); for (var arg : (List<List<Object>>) call.get(2)) args.add(CoreRepresentations.expression(arg)); var flags = (List<?>) call.get(3); var result = CoreRepresentations.expression(call); op.validate(args, flags, result); assertThrows(RuntimeFault.class, () -> op.validate(args.subList(0, args.size() - 1), flags, result)); var inverse = new ArrayList<Boolean>(); for (var flag : flags) inverse.add(!(Boolean) flag); assertThrows(RuntimeFault.class, () -> op.validate(args, inverse, result)); assertThrows(RuntimeFault.class, () -> op.validate(args, flags, new CoreRepresentation(CoreKind.LONG, false, false, null, null, null, null, null, null))); }
        try (var context = context(true)) { context.initialize("thc"); context.enter(); try { var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var linked = CoreModules.reachable(full, "basic"); assertNotNull(new BytecodeProgram(language, linked, true).entryTarget("basic")); assertNotNull(new Program(language, linked, true).entryTarget("basic")); assertThrows(UnsupportedCore.class, () -> new BytecodeProgram(language, linked, new BytecodeCheckpoint()).entryTarget("basic")); } finally { context.leave(); } }
    }
    private long compiledEntries(Value entry) { return ((Number) ((Map<?, ?>) Json.parse(entry.getMember("diagnostics").asString())).get("compiledEntries")).longValue(); }
    private long call(Value entry, long operation, long token) { return entry.execute(operation, token).asLong(); }
    private CompletableFuture<Long> start(Value entry, Language.State state, String backend, long operation, List<Thread> workers) {
        var result = new CompletableFuture<Long>(); var thread = new Thread(() -> { try { long answer = call(entry, operation, 0); assertFalse(state.stm.hasTransaction(), "Transaction log leaked on its owner carrier"); assertEquals(MaskingState.UNMASKED, state.getMaskingState().get(), "Mask leaked after IO catch"); result.complete(answer); } catch (Throwable failure) { result.completeExceptionally(failure); } }, "stm-" + backend + "-" + operation); thread.setDaemon(true); workers.add(thread); thread.start(); return result;
    }
    private Map<String, Object> request(List<String> paths, String entry, String backend, boolean async) throws Exception { var absolute = new ArrayList<String>(); for (var path : paths) absolute.add(new File(root, path).getPath()); var request = new LinkedHashMap<>((Map<String, Object>) Json.parse(CoreModules.request(absolute, entry, true, false, backend))); request.put("asyncExceptions", async); request.put("strictLink", true); request.put("targetLayout", targetLayout().document()); return request; }
    /** Every call uses the public parser and the same original entry/CAF set. */
    @Test public void publicAsyncRetryAbortsAndSharedThunksRestartLikeGhc() throws Exception {
        var manifest = fixture(); var nativeRows = new LinkedHashMap<String, Long>(); for (var row : rows()) nativeRows.put(row.name, row.expected);
        for (var stage : ((Map<String, List<String>>) manifest.get("stages")).entrySet()) for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) {
            var entry = context.eval("thc", Json.stringify(request(stage.getValue(), "asyncEntry", backend, true))); context.enter(); final Language.State state; final Language language; try { state = Language.currentState(null); language = TruffleLanguage.LanguageReference.create(Language.class).get(null); } finally { context.leave(); }
            assertEquals(0L, call(entry, 5, 0)); assertEquals(0L, call(entry, 6, 0)); assertTrue(entry.invokeMember("compile").asBoolean()); long firstCompiled = compiledEntries(entry); assertEquals(0L, call(entry, 5, 0)); assertTrue(compiledEntries(entry) > firstCompiled, "The first installed call must enter compiled code"); var workers = new ArrayList<Thread>();
            try { for (var name : List.of("retry", "inner")) { long operation = name.equals("retry") ? 0L : 1L; var answer = start(entry, state, backend, operation, workers); var target = workers.getLast(); assertEquals(name.equals("retry") ? 0L : 4L, CompletableFuture.supplyAsync(() -> call(entry, 2, 0)).get(10, TimeUnit.SECONDS)); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!answer.isDone() && System.nanoTime() < deadline) { boolean awaiting = false; if (target.getState() == Thread.State.WAITING) for (var frame : target.getStackTrace()) if (frame.getMethodName().equals("await")) awaiting = true; if (awaiting) break; Thread.sleep(1); }
                assertFalse(answer.isDone(), stage.getKey() + "/" + backend + "/" + name + " must still be running"); assertEquals(Thread.State.WAITING, target.getState()); if (name.equals("retry")) assertEquals(1, state.stm.pendingWaiters()); var sender = start(entry, state, backend, 7, workers); assertEquals(nativeRows.get(name + "-caught"), answer.get(15, TimeUnit.SECONDS)); assertEquals(0L, sender.get(15, TimeUnit.SECONDS), "killThread# returned after acknowledgement"); assertEquals(0, state.stm.pendingWaiters(), "Abandoned retry registrations must be cancelled"); assertEquals(nativeRows.get(name + "-aborted").longValue(), call(entry, 5, 0), "Tentative writes escaped"); assertEquals(nativeRows.get(name + "-prefix").longValue(), call(entry, 6, 0));
                if (name.equals("retry")) { var interruptedAgain = start(entry, state, backend, operation, workers); assertEquals(0L, CompletableFuture.supplyAsync(() -> call(entry, 2, 0)).get(10, TimeUnit.SECONDS)); assertEquals(0L, start(entry, state, backend, 7, workers).get(15, TimeUnit.SECONDS)); assertEquals(-1L, interruptedAgain.get(15, TimeUnit.SECONDS)); assertEquals(0, state.stm.pendingWaiters()); assertEquals(0L, call(entry, 5, 0)); assertEquals(1L, call(entry, 6, 0), "Second interruption replayed enclosing prefix"); }
                assertEquals(name.equals("retry") ? 4L : 9L, call(entry, 4, name.equals("retry") ? 4 : 9)); if (name.equals("inner")) assertEquals(0L, call(entry, 3, 0));
                // The forcing carrier differs from the abandoned owner.
                assertEquals(nativeRows.get(name + "-resumed").longValue(), call(entry, operation, 0)); assertEquals(nativeRows.get(name + "-committed").longValue(), call(entry, 5, 0)); assertEquals(nativeRows.get(name + "-prefix-after").longValue(), call(entry, 6, 0), "Enclosing prefix replayed"); if (name.equals("retry")) assertEquals(4L, call(entry, 2, 0)); context.enter(); try { released(language); } finally { context.leave(); }
            } } finally { for (var worker : workers) worker.join(1000); boolean alive = false; for (var worker : workers) if (worker.isAlive()) alive = true; if (alive) context.close(true); }
        }
    }
    @Test public void publicSynchronousAndAsynchronousTransactionsMatchNative() throws Exception {
        var manifest = fixture(); for (var paths : ((Map<String, List<String>>) manifest.get("stages")).values()) for (var backend : List.of("ast", "bytecode")) for (boolean async : new boolean[] {false, true}) try (var context = context(true)) { var entry = context.eval("thc", Json.stringify(request(paths, "basic", backend, async))); assertEquals(model("basic", 17), entry.execute(17).asLong()); assertTrue(entry.invokeMember("compile").asBoolean()); long before = compiledEntries(entry); assertEquals(model("basic", 63), entry.execute(63).asLong()); assertTrue(compiledEntries(entry) > before); }
    }
}
