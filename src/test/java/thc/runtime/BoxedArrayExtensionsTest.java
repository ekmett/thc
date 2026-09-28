// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.Unit.INSTANCE;

@SuppressWarnings("unchecked")
public class BoxedArrayExtensionsTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final List<ArrayOp> operations = List.of(ArrayOp.SIZE, ArrayOp.SIZE_MUTABLE, ArrayOp.CLONE_MUTABLE,
        ArrayOp.COPY, ArrayOp.COPY_MUTABLE, ArrayOp.UNSAFE_THAW);
    private final List<String> names =
        List.of("boxedExtSizes", "boxedExtClone", "boxedExtCopy", "boxedExtMove", "boxedExtThaw", "boxedExtLazy");
    private Context context() {
        return context(true);
    }
    private Context context(boolean inlining) {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("compiler.Inlining", Boolean.toString(inlining))
            .build();
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private void valid(RootCallTarget target) throws Exception {
        valid(target, "");
    }
    private void valid(RootCallTarget target, String label) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target),
            label + "/" + target.getRootNode().getName());
    }
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        valid(target);
    }
    private void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth());
        assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().retainedReferences());
    }
    private Map<String, Object> scalar(String kind, String... reps) {
        return Map.of("kind", kind, "primReps", List.of(reps), "evaluated", true);
    }
    private final Map<String, Object> array = scalar("object", "BoxedRep (Just Unlifted)"),
                                      integer = scalar("long", "IntRep"), state = scalar("void"),
                                      closure = scalar("closure", "BoxedRep (Just Lifted)");
    private final Map<String, Object> tuple = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components",
        List.of(state, array), "primReps", array.get("primReps"), "evaluated", true);
    private Map<String, Object> synthetic(ArrayOp operation) {
        var proofs = switch (operation) {
            case SIZE, SIZE_MUTABLE -> List.of(array);
            case CLONE_MUTABLE -> List.of(array, integer, integer, state);
            case FREEZE, UNSAFE_THAW -> List.of(array, state);
            default -> List.of(array, integer, array, integer, integer, state);
        };
        var result = operation.getTuple()                                     ? tuple
            : List.of(ArrayOp.SIZE, ArrayOp.SIZE_MUTABLE).contains(operation) ? integer
                                                                              : state;
        var returned = operation.getTuple() ? array : result;
        var params = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < proofs.size(); i++)
            params.add(Map.of("id", "p" + i, "lifted", false, "rep", proofs.get(i)));
        var app = List.of("app", List.of("prim", operation.getPrimitive()),
            params.stream().map(p -> List.of("var", p.get("id"), Map.of("rep", p.get("rep")))).toList(),
            Collections.nCopies(params.size(), false), false, false, Map.of("rep", result));
        var body = !operation.getTuple()
            ? app
            : List.of("case", app, "pair",
                  List.of(List.of("data", "Tuple2", List.of("s", "copy"), List.of("var", "copy", Map.of("rep", array)),
                      Map.of("binders",
                          List.of(Map.of("id", "s", "lifted", false, "rep", state),
                              Map.of("id", "copy", "lifted", false, "rep", array))))),
                  Map.of("rep", array, "binder", Map.of("id", "pair", "lifted", false, "rep", tuple)));
        return Map.of("instrument", true, "constructors",
            List.of(Map.of("id", "Tuple2", "kind", "unboxed-tuple", "arity", 2, "fieldReps",
                List.of(List.of(), array.get("primReps")), "fieldLifted", List.of(false, false), "strictFields",
                List.of(false, false))),
            "bindings",
            List.of(Map.of("id", "operation", "name", "operation", "arity", params.size(), "lifted", true, "rep",
                closure, "expr", List.of("lam", params, body, Map.of("rep", closure, "resultRep", returned)))));
    }
    private record Range(long offset, long count) {}
    private List<Range> invalidRanges(long size) {
        return List.of(new Range(Long.MIN_VALUE, 0), new Range(-1, 0), new Range(size + 1, 0), new Range(0, -1),
            new Range(0, Long.MIN_VALUE), new Range(0, Long.MAX_VALUE), new Range(Long.MAX_VALUE, 1),
            new Range(1L << 32, 0), new Range(1, Long.MAX_VALUE), new Range(size, 1));
    }
    @Test
    public void firstCompiledFreezeAndThawPreserveIdentityLazyPayloadsAndMetadata() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var programs = List.of(program(language, synthetic(ArrayOp.FREEZE), backend),
                        program(language, synthetic(ArrayOp.UNSAFE_THAW), backend));
                    var targets = programs.stream().map(p -> p.entryTarget("operation")).toList();
                    int[] entered = {0};
                    var bottom = new Thunk(new RootNode(null) {
                        @Override
                        public Object execute(VirtualFrame frame) {
                            entered[0]++;
                            throw new IllegalStateException("freeze/thaw entered a payload");
                        }
                    }.getCallTarget(), null);
                    for (boolean compiled : List.of(false, true)) {
                        if (compiled)
                            for (var target : targets) compile(target);
                        for (int size : List.of(0, 1, 5)) {
                            var storage = new Object[size];
                            for (int i = 0; i < size; i++) storage[i] = i % 2 == 0 ? bottom : null;
                            var separate = storage.clone();
                            assertFalse(ManagedArray.isFrozen(storage));
                            for (int index = 0; index < targets.size(); index++) {
                                long before = count(programs.get(index), "compiledEntries");
                                assertSame(
                                    storage, Calls.target(targets.get(index), new Object[] {0L, storage, INSTANCE}));
                                assertEquals(index == 0, ManagedArray.isFrozen(storage));
                                assertFalse(
                                    ManagedArray.isFrozen(separate), "bookkeeping uses array identity, not elements");
                                assertArrayEquals(separate, storage);
                                if (compiled) {
                                    assertEquals(before + 1, count(programs.get(index), "compiledEntries"),
                                        backend + ": first installed call must execute guest code");
                                    valid(targets.get(index));
                                }
                                released(language);
                            }
                        }
                    }
                    assertEquals(0, entered[0]);
                    assertEquals(0, bottom.getState());
                } finally {
                    context.leave();
                }
            }
    }
    @Test
    public void fullWidthRangesOverlapIndependentClonesAndImmutableAliasing() {
        Object[] elements = {new Object(), null, new byte[] {7}, new Object(), new Object()};
        for (int size = 0; size <= 5; size++)
            for (int count = 0; count <= size; count++)
                for (int from = 0; from <= size - count; from++)
                    for (int to = 0; to <= size - count; to++) {
                        var source = Arrays.copyOf(elements, size);
                        var snapshot = Arrays.copyOfRange(source, from, from + count);
                        var expected = source.clone();
                        for (int i = 0; i < snapshot.length; i++) expected[to + i] = snapshot[i];
                        ManagedArray.copy(source, from, source, to, count, true);
                        assertArrayEquals(expected, source);
                        var destination = new Object[size];
                        Arrays.setAll(destination, i -> new Object());
                        ManagedArray.copy(Arrays.copyOf(elements, size), from, destination, to, count, false);
                        for (int i = 0; i < snapshot.length; i++) assertSame(snapshot[i], destination[to + i]);
                    }
        for (boolean mutable : List.of(false, true))
            for (var range : invalidRanges(5))
                for (boolean badSource : List.of(false, true)) {
                    var source = elements.clone();
                    var destination = elements.clone();
                    assertThrows(RuntimeFault.class,
                        ()
                            -> ManagedArray.copy(source, badSource ? range.offset : 0, destination,
                                badSource ? 0 : range.offset, range.count, mutable));
                    assertArrayEquals(elements, source);
                    assertArrayEquals(elements, destination);
                }
        for (long count : List.of(0L, 1L, 5L)) {
            var source = elements.clone();
            assertThrows(RuntimeFault.class, () -> ManagedArray.copy(source, 0, source, 0, count, false));
            assertArrayEquals(elements, source);
        }
    }
    private Object invoke(ArrayOp operation, RootCallTarget target, Object source, long from, Object destination,
        long to, long count, Object token) {
        Object[] values = switch (operation) {
            case SIZE, SIZE_MUTABLE -> new Object[] {source};
            case CLONE_MUTABLE -> new Object[] {source, from, count, token};
            case UNSAFE_THAW -> new Object[] {source, token};
            default -> new Object[] {source, from, destination, to, count, token};
        };
        var args = new Object[values.length + 1];
        args[0] = 0L;
        System.arraycopy(values, 0, args, 1, values.length);
        return Calls.target(target, args);
    }
    private void cases(boolean compiled, ArrayOp operation, RootCallTarget target, ExecutableProgram program,
        Language language, Thunk bottom) throws Exception {
        for (int n : List.of(0, 1, 5))
            for (int count = 0; count <= n; count++)
                for (int from = 0; from <= n - count; from++)
                    for (int to = 0; to <= n - count; to++) {
                        var source = new Object[n];
                        Arrays.setAll(source, i -> i % 2 == 0 ? bottom : new Object());
                        var old = source.clone();
                        var destination = operation == ArrayOp.COPY_MUTABLE ? source : new Object[n];
                        if (destination != source)
                            Arrays.setAll(destination, i -> new Object());
                        var expected = destination.clone();
                        for (int i = 0; i < count; i++) expected[to + i] = old[from + i];
                        long before = count(program, "compiledEntries");
                        var result = invoke(operation, target, source, from, destination, to, count, INSTANCE);
                        switch (operation) {
                            case SIZE, SIZE_MUTABLE -> assertEquals((long) n, result);
                            case UNSAFE_THAW -> {
                                assertSame(source, result);
                                if (n > 0) {
                                    var replacement = new Object();
                                    ManagedArray.write((Object[]) result, 0, replacement);
                                    assertSame(replacement, source[0]);
                                }
                            }
                            case CLONE_MUTABLE -> {
                                var copy = ManagedArray.require(result);
                                assertNotSame(source, copy);
                                assertArrayEquals(Arrays.copyOfRange(old, from, from + count), copy);
                                if (count > 0) {
                                    copy[0] = new Object();
                                    assertSame(old[from], source[from]);
                                }
                            }
                            default -> {
                                assertSame(INSTANCE, result);
                                assertArrayEquals(expected, destination);
                            }
                        }
                        if (compiled) {
                            assertEquals(before + 1, count(program, "compiledEntries"));
                            valid(target);
                        }
                        released(language);
                    }
    }
    @Test
    public void bothBackendsKeepLazyReferencesIdentityAndCompiledMutationVisibility() throws Exception {
        for (var backend : List.of("ast", "bytecode"))
            for (var operation : operations) try (var context = context()) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var program = program(language, synthetic(operation), backend);
                        var target = program.entryTarget("operation");
                        int[] entered = {0};
                        var bottom = new Thunk(new RootNode(null) {
                            @Override
                            public Object execute(VirtualFrame frame) {
                                entered[0]++;
                                throw new RuntimeFault("boxed bottom entered");
                            }
                        }.getCallTarget(), null);
                        cases(false, operation, target, program, language, bottom);
                        compile(target);
                        cases(true, operation, target, program, language, bottom);
                        Object[] source = {bottom, new Object(), bottom, new Object(), null};
                        if (!List.of(ArrayOp.SIZE, ArrayOp.SIZE_MUTABLE).contains(operation)) {
                            var before = source.clone();
                            assertThrows(RuntimeFault.class,
                                () -> invoke(operation, target, source, 0, new Object[5], 0, 0, 9L));
                            assertArrayEquals(before, source);
                        }
                        if (List.of(ArrayOp.CLONE_MUTABLE, ArrayOp.COPY, ArrayOp.COPY_MUTABLE).contains(operation))
                            for (var range : invalidRanges(5)) {
                                var before = source.clone();
                                var destination = source.clone();
                                assertThrows(RuntimeFault.class,
                                    ()
                                        -> invoke(operation, target, source, range.offset, destination, 0, range.count,
                                            INSTANCE));
                                assertArrayEquals(before, source);
                                assertArrayEquals(before, destination);
                                if (operation != ArrayOp.CLONE_MUTABLE) {
                                    assertThrows(RuntimeFault.class,
                                        ()
                                            -> invoke(operation, target, source, 0, destination, range.offset,
                                                range.count, INSTANCE));
                                    assertArrayEquals(before, destination);
                                }
                            }
                        if (operation == ArrayOp.COPY)
                            assertThrows(
                                RuntimeFault.class, () -> invoke(operation, target, source, 0, source, 0, 0, INSTANCE));
                        for (var bad : List.of(new Object(), new byte[] {1}, new String[] {"wrong component"}))
                            assertThrows(
                                RuntimeFault.class, () -> invoke(operation, target, bad, 0, bad, 0, 0, INSTANCE));
                        assertEquals(0, entered[0]);
                        released(language);
                    } finally {
                        context.leave();
                    }
                }
    }
    private Expr operand(List<String> events, String name, Object value) {
        return new Expr() {
            @Override
            public Object execute(VirtualFrame frame) {
                events.add(name);
                return value;
            }
        };
    }
    @Test
    public void stateIsCheckedBeforeCopyAndNoResultIsPublishedOnFailure() throws Exception {
        var builder = FrameDescriptor.newBuilder();
        int slot = builder.addSlot(FrameSlotKind.Object, "result", null);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], builder.build());
        var sentinel = new Object();
        Object[] source = {sentinel}, destination = {new Object()};
        var original = destination.clone();
        var events = new ArrayList<String>();
        for (var operation : List.of(ArrayOp.COPY, ArrayOp.COPY_MUTABLE)) {
            events.clear();
            var expr = ArrayOp.expression(operation, CoreRepresentation.UNKNOWN,
                new Expr[] {operand(events, "source", source), operand(events, "from", Long.MAX_VALUE),
                    operand(events, "destination", destination), operand(events, "to", Long.MAX_VALUE),
                    operand(events, "count", Long.MAX_VALUE), operand(events, "state", 9L)});
            var failure = assertThrows(RuntimeFault.class, () -> expr.execute(frame));
            assertTrue(Objects.toString(failure.getMessage(), "").contains("zero-width scalar carrier"));
            assertEquals(List.of("source", "from", "destination", "to", "count", "state"), events);
            assertArrayEquals(original, destination);
        }
        for (var operation : List.of(ArrayOp.CLONE_MUTABLE, ArrayOp.UNSAFE_THAW)) {
            frame.setObject(slot, sentinel);
            var args = operation == ArrayOp.CLONE_MUTABLE
                ? new Expr[] {operand(events, "array", source), operand(events, "offset", 0L),
                      operand(events, "count", 1L), operand(events, "state", 9L)}
                : new Expr[] {operand(events, "array", source), operand(events, "state", 9L)};
            assertThrows(RuntimeFault.class,
                ()
                    -> ArrayOp.expression(operation, CoreRepresentation.UNKNOWN, args)
                        .executeTuple(frame, new int[] {slot}, 0));
            assertSame(sentinel, frame.getObject(slot));
        }
    }
    private Object mutable(Object value) {
        if (value instanceof List<?> list) {
            var result = new ArrayList<Object>();
            for (var item : list) result.add(mutable(item));
            return result;
        }if(value instanceof Map<?,?> map){
            var result = new LinkedHashMap<Object, Object>();
            for (var e : map.entrySet()) result.put(e.getKey(), mutable(e.getValue()));
            return result;
        }
        return value;
    }
    private List<List<Object>> nodes(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> list) {
            result.add((List<Object>) list);
            for (var item : list) result.addAll(nodes(item));}else if(value instanceof Map<?,?> map)
            for (var item : map.values()) result.addAll(nodes(item));
        return result;
    }
    @Test
    public void rawFlagsWidthsLevityStateAndAggregateProofsFailClosed() {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var operation : operations)
                        for (boolean diagnostic : List.of(false, true))
                            for (int mutation = 0; mutation <= 12; mutation++) {
                                var module = (Map<String, Object>) mutable(synthetic(operation));
                                var app = single(nodes(module)
                                        .stream()
                                        .filter(n -> !n.isEmpty() && "app".equals(n.getFirst()))
                                        .toList());
                                var args = (List<List<Object>>) app.get(2);
                                var flags = (List<Object>) app.get(3);var proof=(Map<String,Object>)((Map<?,?>)app.get(6)).get("rep");
                                var first =
                                    (Map<String, Object>) CoreRepresentations.metadata(args.get(0)).get("rep");
                                switch (mutation) {
                                    case 0 -> flags.set(0, true);
                                    case 1 -> flags.set(0, 0L);
                                    case 2 -> first.put("primReps", List.of("BoxedRep (Just Lifted)"));
                                    case 3 -> first.put("primReps", List.of("BoxedRep Nothing"));
                                    case 4 -> first.put("components", List.of());
                                    case 5 -> first.put("aggregate", null);
                                    case 6 -> first.put("vector", null);
                                    case 7 -> first.put("evaluated", 1L);
                                    case 8 -> {
                                        args.removeLast();
                                        flags.removeLast();
                                    }
                                    case 9 -> {
                                        args.add(args.getFirst());
                                        flags.add(false);
                                    }
                                    case 10 -> proof.put("primReps", List.of("WordRep"));
                                    case 11 -> {
                                        proof.clear();
                                        proof.putAll(Map.of("kind", "unknown", "primReps", List.of(), "aggregate",
                                            "unboxed-tuple", "components", List.of(), "evaluated", true));
                                    }
                                    case 12 -> {
                                        var integral =
                                            args.stream()
                                                .map(a
                                                    -> (Map<String, Object>) CoreRepresentations.metadata(a)
                                                        .get("rep"))
                                                .filter(p -> "long".equals(p.get("kind")))
                                                .findFirst()
                                                .orElse(null);
                                        if (integral != null)
                                            integral.put("primReps", List.of("Int64Rep"));
                                        else
                                            proof.put("components", List.of());
                                    }
                                }
                                assertThrows(RuntimeFault.class,
                                    ()
                                        -> program(
                                            language, changed(module, "diagnosticUnsupported", diagnostic), backend),
                                    backend + "/" + operation + "/" + diagnostic + "/" + mutation);
                            }
                } finally {
                    context.leave();
                }
            }
    }
    private List<RootCallTarget> activeTargets(RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>());
        var targets = new ArrayList<RootCallTarget>();
        visit(entry, seen, targets);
        return targets;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> targets) {
        if (!seen.add(target))
            return;
        var root = target.getRootNode();
        var nodes = new ArrayList<Node>();
        nodes.add(root);
        // Bytecode DSL operation caches are not ordinary @Children fields.
        // Its public instruction API exposes the actual adopted cached nodes.
        if (root instanceof BytecodeRoot bytecode)
            for (var instruction : bytecode.getBytecodeNode().getInstructions())
                for (var argument : instruction.getArguments())
                    if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                        var node = argument.asCachedNode();
                        if (node != null)
                            nodes.add(node);
                    }
        for (var node : nodes)
            for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
                if (call.getCurrentCallTarget() instanceof RootCallTarget active
                    && active.getRootNode() instanceof GuestRoot)
                    visit(active, seen, targets);
        targets.add(target); // Install callees before their callers.
    }

    private Map<String, Object> read(String path) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(root.resolve(path)));
    }
    private Map<String, Object> checked(Map<String, Object> manifest) throws Exception {
        require(Objects.equals(manifest.get("schema"), 1L) && Objects.equals(manifest.get("ghc"), "9.14.1")
            && Objects.equals(manifest.get("wordBits"), 64L));
        require(Objects.equals(manifest.get("entries"), names)
            && Objects.equals(manifest.get("installedArtifactsHashed"), false)
            && Objects.equals(manifest.get("runtimeVerified"), false));
        var oracle = (String) manifest.get("oracle");
        require(oracle.matches("build/boxed-array-extensions/run-[1-9][0-9]*/logs/native-oracle.stdout"));
        var attempt = oracle.substring(0, oracle.length() - "/logs/native-oracle.stdout".length());
        var stages = (Map<String, List<String>>) manifest.get("stages");
        var audits = (Map<String, List<String>>) manifest.get("audits");
        require(stages.keySet().equals(Set.of("pre", "post")) && audits.keySet().equals(stages.keySet()));
        for (var stage : stages.keySet()) {
            require(new HashSet<>(stages.get(stage))
                    .equals(Set.of(attempt + "/" + stage + "-core/BoxedArrayExtensionsAudit.json",
                        attempt + "/" + stage + "-core/THC.InterfaceClosure.json")));
            require(stages.get(stage).size() == 2);
            require(audits.get(stage).equals(
                names.stream().map(n -> attempt + "/" + stage + "-" + n + ".audit.json").toList()));
        }
        var labels = new ArrayList<>(
            List.of("ghc-version", "ghc-info", "pre-export", "post-export", "native-compile", "native-oracle"));
        for (var stage : List.of("pre", "post"))
            for (var name : names) labels.add(stage + "-audit-" + name);
        var requiredArtifacts = new HashSet<String>();
        for (var paths : stages.values()) requiredArtifacts.addAll(paths);
        for (var paths : audits.values()) requiredArtifacts.addAll(paths);
        requiredArtifacts.add(attempt + "/native/boxed-array-extensions-oracle");
        for (var name : labels)
            for (var suffix : List.of("stdout", "stderr", "command.json"))
                requiredArtifacts.add(attempt + "/logs/" + name + "." + suffix);
        var requiredSources = new HashSet<>(List.of("t/fixtures/compiler/BoxedArrayExtensionsAudit.hs",
            "t/fixtures/compiler/BoxedArrayExtensionsNative.hs",
            "t/haskell-fixtures/BoxedArrayExtensionsFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
            "t/haskell-fixtures/Main.hs", "thc.cabal", "bin/export-core.sh", "bin/build-compiler.sh",
            "bin/toolchain.sh", "bin/plugin.py", "bin/audit-core.py", "bin/core-capabilities.json",
            "src/main/resources/thc/scalar-primop-signatures.json"));
        try (var files = Files.list(root.resolve("src/compiler/THC"))) {
            files.filter(p -> p.getFileName().toString().endsWith(".hs"))
                .forEach(p -> requiredSources.add(root.relativize(p).toString()));
        }
        try (var files = Files.list(root.resolve("bin"))) {
            files
                .filter(
                    p -> p.getFileName().toString().startsWith("core_") && p.getFileName().toString().endsWith(".py"))
                .forEach(p -> requiredSources.add(root.relativize(p).toString()));
        }
        for (var key : List.of("inputHashes", "artifactHashes")) {
            var expectedPaths = key.equals("inputHashes") ? requiredSources : requiredArtifacts;
            var hashes = (Map<String, String>) manifest.get(key);
            require(hashes.keySet().equals(expectedPaths));
            for (var e : hashes.entrySet()) {
                require(e.getValue().matches("[0-9a-f]{64}"));
                var file = root.resolve(e.getKey()).toFile();
                require(file.getCanonicalFile().equals(file.getAbsoluteFile()));
                var actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));
                require(e.getValue().equals(actual), "Stale boxed-array extension " + e.getKey());
            }
        }
        var commands = (List<Map<String, Object>>) manifest.get("commands");
        require(commands.size() == labels.size()
            && commands.stream().allMatch(
                c -> Objects.equals(c.get("exit"), 0L) && !Objects.equals(c.get("timedOut"), true)));
        var records = new ArrayList<Map<String, Object>>();
        for (var label : labels) records.add(read(attempt + "/logs/" + label + ".command.json"));
        require(
            new HashSet<>(commands).equals(new HashSet<>(records)) && new HashSet<>(records).size() == commands.size());
        require(Objects.equals(manifest.get("nativeRows"), 1830L));
        return manifest;
    }
    private boolean application(List<Object> node, ArrayOp operation) {
        return !node.isEmpty() && "app".equals(node.getFirst()) && node.size() > 1
            && node.get(1) instanceof List<?> list
            && list.subList(0, Math.min(2, list.size())).equals(List.of("prim", operation.getPrimitive()));
    }
    @Test
    public void originalProofMutationsAndStaleOrIncompleteReceiptsAreRejected() throws Exception {
        var manifest = checked(read("build/boxed-array-extensions/manifest.json"));
        for (int mutation = 0; mutation <= 6; mutation++) {
            var bad = (Map<String, Object>) mutable(manifest);
            switch (mutation) {
                case 0 -> ((Map<String, Object>) bad.get("inputHashes")).remove("thc.cabal");
                case 1 -> ((Map<String, Object>) bad.get("artifactHashes")).remove(bad.get("oracle"));
                case 2 -> ((Map<String, Object>) bad.get("inputHashes")).put("thc.cabal", "0".repeat(64));
                case 3 -> bad.put("schema", 1.0);
                case 4 -> bad.put("nativeRows", 1L);
                case 5 -> bad.put("oracle", "../outside");
                case 6 -> ((List<Object>) bad.get("commands")).removeFirst();
            }
            assertThrows(IllegalArgumentException.class, () -> checked(bad));
        }
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var paths : ((Map<String, List<String>>) manifest.get("stages")).values())
                        for (var operation : operations) {
                            var modules = new ArrayList<Map<String, Object>>();
                            for (var path : paths) modules.add(read(path));
                            var module = CoreModules.merge(modules);
                            var calls = nodes(module).stream().filter(n -> application(n, operation)).toList();
                            assertTrue(!calls.isEmpty(), operation.getPrimitive());
                            for (boolean diagnostic : List.of(false, true)) {
                                var changed = (Map<String, Object>) mutable(module);
                                var app = nodes(changed)
                                              .stream()
                                              .filter(n -> application(n, operation))
                                              .findFirst()
                                              .orElseThrow();
                                ((List<Object>) app.get(3)).set(0, 0L);
                                assertThrows(RuntimeFault.class,
                                    ()
                                        -> program(
                                            language, changed(changed, "diagnosticUnsupported", diagnostic), backend));
                            }
                        }
                } finally {
                    context.leave();
                }
            }
    }
    private long weighted(List<Long> values) {
        long sum = 0;
        for (int i = 0; i < values.size(); i++) sum += (i + 1) * values.get(i);
        return sum;
    }
    private long model(String name, List<Long> args) {
        long seed = args.get(0), size = args.get(1), from = args.get(2), to = args.get(3), count = args.get(4);
        var source = new ArrayList<Long>();
        for (int i = 0; i < (int) size; i++) source.add(seed + i);
        return switch (name) {
            case "boxedExtSizes" -> 2 * size;
            case "boxedExtLazy" -> seed;
            case "boxedExtClone" -> {
                var copy = new ArrayList<>(source.subList((int) from, (int) (from + count)));
                if (!copy.isEmpty())
                    copy.set(0, seed + 77);
                yield weighted(source) * 3 + weighted(copy) * 5;
            }
            case "boxedExtThaw" -> {
                if (count > 0)
                    source.set((int) to, seed + 77);
                yield weighted(source);
            }
            default -> {
                var copy = new ArrayList<>(source.subList((int) from, (int) (from + count)));
                var destination = name.equals("boxedExtMove") ? source : new ArrayList<Long>();
                if (destination != source)
                    for (int i = 0; i < (int) size; i++) destination.add(seed + 100 + i);
                for (int i = 0; i < copy.size(); i++) destination.set((int) to + i, copy.get(i));
                yield weighted(destination);
            }
        };
    }
    @Test
    public void nativePreAndPostWithInlining() throws Exception {
        nativeChecks(true);
    }
    @Test
    public void nativePreAndPostAcrossResidualCalls() throws Exception {
        nativeChecks(false);
    }
    private void check(List<String> row, org.graalvm.polyglot.Value entry, Language language, String stage,
        String backend, String name) {
        assertEquals(Long.parseLong(row.get(6)),
            entry.execute(row.subList(1, 6).stream().map(Long::valueOf).toArray()).asLong(),
            stage + "/" + backend + "/" + name + "/" + row);
        released(language);
    }
    private void nativeChecks(boolean inlining) throws Exception {
        var manifest = checked(read("build/boxed-array-extensions/manifest.json"));
        assertEquals(1L, manifest.get("schema"));
        assertEquals("9.14.1", manifest.get("ghc"));
        assertEquals(64L, manifest.get("wordBits"));
        assertEquals(names, manifest.get("entries"));
        assertEquals(false, manifest.get("installedArtifactsHashed"));
        assertEquals(false, manifest.get("runtimeVerified"));
        var rows = Files.readAllLines(root.resolve((String) manifest.get("oracle")))
                       .stream()
                       .map(l -> Arrays.asList(l.split("\t", -1)))
                       .toList();
        var requests = new ArrayList<List<String>>();
        for (var name : names)
            for (long seed : List.of(Long.MIN_VALUE, -7L, 0L, 23L, Long.MAX_VALUE))
                for (long n : List.of(0L, 1L, 4L))
                    for (long count = 0; count <= n; count++)
                        for (long from = 0; from <= n - count; from++)
                            for (long to = 0; to <= n - count; to++)
                                requests.add(List.of(name, Long.toString(seed), Long.toString(n), Long.toString(from),
                                    Long.toString(to), Long.toString(count)));
        assertEquals(requests, rows.stream().map(r -> r.subList(0, Math.min(6, r.size()))).toList());
        assertEquals((long) rows.size(), manifest.get("nativeRows"));
        for (var row : rows)
            assertEquals(model(row.get(0), row.subList(1, 6).stream().map(Long::valueOf).toList()),
                Long.parseLong(row.get(6)), "native/model " + row);
        var stages = (Map<String, List<String>>) manifest.get("stages");
        assertEquals(Set.of("pre", "post"), stages.keySet());
        for (var stage : stages.entrySet()) {
            var modules = new ArrayList<Map<String, Object>>();
            for (var path : stage.getValue()) modules.add(read(path));
            var merged = CoreModules.merge(modules);
            var boundary = stage.getKey().equals("pre") ? "optimized-Core-before-Tidy"
                                                        : "optimized-Core-after-Tidy-before-CorePrep";
            assertEquals(boundary,
                single(modules.stream().filter(m -> "BoxedArrayExtensionsAudit".equals(m.get("module"))).toList())
                    .get("boundary"));
            var found = new HashSet<String>();
            for (var name : names) found.addAll(new ArrayCoreEvidence(merged, name).getPrimitiveCounts().keySet());
            assertTrue(operations.stream().allMatch(o -> found.contains(o.getPrimitive())),
                stage.getKey() + " missing original primops");
            for (var path : ((Map<String, List<String>>) manifest.get("audits")).get(stage.getKey()))
                assertEquals(true, read(path).get("accepted"));
            for (var name : names)
                for (var backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var program = program(language,
                                changed(CoreModules.reachable(merged, name, true), "instrument", true), backend);
                            var entry = context.asValue(new EntryValue(program, name, 5));
                            var host = program.hostEntryTarget(5);
                            var cases = rows.stream().filter(r -> name.equals(r.get(0))).toList();
                            for (var row : cases) check(row, entry, language, stage.getKey(), backend, name);
                            var targets = activeTargets(host);
                            assertTrue(targets.size() > 1);
                            for (var target : targets)
                                if (target != host)
                                    compile(target);
                            assertTrue(entry.invokeMember("compile").asBoolean());
                            for (var row : cases.reversed()) {
                                long before = count(program, "compiledEntries");
                                check(row, entry, language, stage.getKey(), backend, name);
                                assertTrue(count(program, "compiledEntries") > before);
                                assertEquals(targets, activeTargets(host));
                                for (var target : targets)
                                    valid(target,
                                        stage.getKey() + "/" + backend + "/" + name + "/inline=" + inlining + "/"
                                            + row);
                            }
                            for (var counter : List.of("unsupportedTraps", "blackholes"))
                                assertEquals(0L, count(program, counter));
                        } finally {
                            context.leave();
                        }
                    }
        }
    }
    private static void require(boolean condition) {
        require(condition, "Failed requirement.");
    }
    private static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalArgumentException(message);
    }
    private static <T> T single(List<T> list) {
        if (list.size() != 1)
            throw new IllegalArgumentException("Expected a single element");
        return list.getFirst();
    }
    private static Map<String, Object> changed(Map<String, Object> module, String key, Object value) {
        var result = new LinkedHashMap<>(module);
        result.put(key, value);
        return result;
    }
    private static long count(ExecutableProgram program, String key) {
        return ((Number) program.diagnostics().get(key)).longValue();
    }
}
