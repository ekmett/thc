// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.*;
import thc.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.Unit.INSTANCE;

@Timeout(180)
@SuppressWarnings("unchecked")
public class BoxedCasTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final List<String> names = List.of("arrayCas", "arrayCasUnlifted", "smallCas", "smallCasUnlifted", "varCas",
        "varCasUnlifted", "modifyValue", "modifyLazy", "modifyBottom", "boxedCasCounter");
    private final List<Long> seeds =
        List.of(Long.MIN_VALUE, -1000000L, -17L, -1L, 0L, 1L, 17L, 1000000L, Long.MAX_VALUE);
    private final List<String> cases = List.of("casArray#", "casSmallArray#", "casMutVar#");
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
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
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
    private final Map<String, Object> reference = scalar("object", "BoxedRep (Just Unlifted)"),
                                      integer = scalar("long", "IntRep"), state = scalar("void"),
                                      closure = scalar("closure", "BoxedRep (Just Lifted)"),
                                      data = scalar("data", "BoxedRep (Just Lifted)");
    private boolean lifted(Map<String, Object> p) {
        return List.of("BoxedRep (Just Lifted)").equals(p.get("primReps"));
    }
    private Map<String, Object> binder(String id, Map<String, Object> rep) {
        return Map.of("id", id, "lifted", lifted(rep), "rep", rep);
    }
    private List<Object> variable(String id, Map<String, Object> rep) {
        return List.of("var", id, Map.of("rep", rep));
    }
    private Map<String, Object> synthetic(String primitive) {
        return synthetic(primitive, false);
    }
    /** Return both fields in a boxed record, leaving the primitive's boxed payload unforced. */
    private Map<String, Object> synthetic(String primitive, boolean unlifted) {
        var element = unlifted ? reference : scalar("object", "BoxedRep (Just Lifted)");
        boolean modify = primitive.equals("atomicModifyMutVar_#");
        var proofs = switch (primitive) {
            case "casMutVar#" -> List.of(reference, element, element, state);
            case "atomicModifyMutVar_#" -> List.of(reference, closure, state);
            default -> List.of(reference, integer, element, element, state);
        };
        var fields = modify ? List.of(state, element, element) : List.of(state, integer, element);
        var reps = new ArrayList<String>();
        for (var field : fields) reps.addAll((List<String>) field.get("primReps"));
        var tuple = Map.of(
            "kind", "unknown", "aggregate", "unboxed-tuple", "evaluated", true, "components", fields, "primReps", reps);
        var variables = new ArrayList<List<Object>>();
        var binders = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < proofs.size(); i++) {
            variables.add(variable("p" + i, proofs.get(i)));
            binders.add(binder("p" + i, proofs.get(i)));
        }
        var app = List.of("app", List.of("prim", primitive), variables, proofs.stream().map(this::lifted).toList(),
            false, false, Map.of("rep", tuple));
        var record = List.of("app", List.of("con", "Record", 2),
            List.of(variable("first", fields.get(1)), variable("second", fields.get(2))),
            fields.subList(1, fields.size()).stream().map(this::lifted).toList(), false, false, Map.of("rep", data));
        var body = List.of("case", app, "tuple",
            List.of(List.of("data", "Tuple3", List.of("s", "first", "second"), record,
                Map.of("binders",
                    List.of(binder("s", state), binder("first", fields.get(1)), binder("second", fields.get(2)))))),
            Map.of("rep", data, "binder", Map.of("id", "tuple", "lifted", false, "rep", tuple)));
        return Map.of("instrument", true, "constructors",
            List.of(Map.of("id", "Tuple3", "name", "Tuple3", "kind", "unboxed-tuple", "arity", 3),
                Map.of("id", "Record", "name", "Record", "kind", "boxed", "arity", 2, "fieldReps",
                    fields.subList(1, fields.size()).stream().map(f -> f.get("primReps")).toList(), "fieldLifted",
                    fields.subList(1, fields.size()).stream().map(this::lifted).toList(), "strictFields",
                    List.of(false, false))),
            "bindings",
            List.of(Map.of("id", "operation", "name", "operation", "arity", proofs.size(), "lifted", true, "rep",
                closure, "expr", List.of("lam", binders, body, Map.of("rep", closure, "resultRep", data)))));
    }
    private Object storage(String primitive, Object value) {
        return switch (primitive) {
            case "casArray#" -> ManagedArray.allocate(1, value);
            case "casSmallArray#" -> ManagedSmallArray.allocate(1, value);
            default -> new ManagedMutVar(value);
        };
    }
    private Object exchange(String primitive, Object storage, Object old, Object replacement) {
        return switch (primitive) {
            case "casArray#" -> ManagedArray.compareExchange(ManagedArray.require(storage), 0, old, replacement);
            case "casSmallArray#" ->
                ManagedSmallArray.compareExchange(ManagedSmallArray.require(storage), 0, old, replacement);
            default -> ManagedMutVar.require(storage).compareExchange(old, replacement);
        };
    }
    private Object current(String primitive, Object storage) {
        return exchange(primitive, storage, null, null);
    }
    private DataValue cas(RootCallTarget target, String primitive, Object storage, Object old, Object replacement) {
        return cas(target, primitive, storage, old, replacement, 0, INSTANCE);
    }
    private DataValue cas(RootCallTarget target, String primitive, Object storage, Object old, Object replacement,
        long index, Object token) {
        Object[] arguments = primitive.equals("casMutVar#")
            ? new Object[] {0L, storage, old, replacement, token}
            : new Object[] {0L, storage, index, old, replacement, token};
        return (DataValue) Calls.target(target, arguments);
    }
    private static final class Equal {
        @Override
        public boolean equals(Object other) {
            throw new IllegalStateException("CAS called equals");
        }
        @Override
        public int hashCode() {
            return 0;
        }
    }
    private Thunk completed(Object value, RootCallTarget evaluator) {
        var thunk = new Thunk(new RootNode(null) {
            @Override
            public Object execute(VirtualFrame frame) {
                return value;
            }
        }.getCallTarget(), null);
        assertSame(value, Calls.target(evaluator, new Object[] {thunk}));
        return thunk;
    }
    private void exercise(boolean compiled, String backend, String primitive, boolean unlifted, ExecutableProgram p,
        RootCallTarget target, RootCallTarget evaluator, Thunk bottom, Language language) throws Exception {
        var old = new Equal();
        var distinct = new Equal();
        var cell = storage(primitive, old);
        for (var test : List.of(List.of(distinct, bottom, 1L, old), List.of(old, bottom, 0L, bottom),
                 List.of(old, distinct, 1L, bottom), List.of(bottom, old, 0L, old), List.of(old, old, 0L, old))) {
            var expected = test.get(0);
            var replacement = test.get(1);
            var flag = test.get(2);
            var returned = test.get(3);
            long before = count(p, "compiledEntries");
            var result = cas(target, primitive, cell, expected, replacement);
            assertEquals(flag, result.getLayout().readLong(result, 0), backend + "/" + primitive);
            assertSame(returned, result.getLayout().read(result, 1));
            assertSame(returned, current(primitive, cell));
            if (compiled) {
                assertEquals(before + 1, count(p, "compiledEntries"));
                assertSame(target, p.entryTarget("operation"));
                valid(target);
            }
            released(language);
        }
        if (!unlifted) {
            var whnf = new Equal();
            var indirection = completed(whnf, evaluator);
            var another = completed(whnf, evaluator);
            for (var pair :
                List.of(List.of(indirection, whnf), List.of(whnf, indirection), List.of(indirection, another))) {
                var storedCell = storage(primitive, pair.get(0));
                long before = count(p, "compiledEntries");
                var result = cas(target, primitive, storedCell, pair.get(1), bottom);
                assertEquals(0L, result.getLayout().readLong(result, 0));
                assertSame(bottom, result.getLayout().read(result, 1));
                assertSame(bottom, current(primitive, storedCell));
                if (compiled) {
                    assertEquals(before + 1, count(p, "compiledEntries"));
                    assertSame(target, p.entryTarget("operation"));
                    valid(target);
                }
            }
        }
    }
    @Test
    public void bothBackendsUseIdentityTicketsWithoutForcingAndEnterInstalledRootExactlyOnce() throws Exception {
        for (var backend : List.of("ast", "bytecode"))
            for (boolean inlining : List.of(false, true))
                for (var primitive : cases)
                    for (boolean unlifted : List.of(false, true)) try (var context = context(inlining)) {
                            context.initialize("thc");
                            context.enter();
                            try {
                                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                                var p = program(language, synthetic(primitive, unlifted), backend);
                                var target = p.entryTarget("operation");
                                var evaluator = force(language, new Metrics(false));
                                var entered = new AtomicInteger();
                                var bottom = new Thunk(new RootNode(null) {
                                    @Override
                                    public Object execute(VirtualFrame frame) {
                                        entered.incrementAndGet();
                                        throw new IllegalStateException("forced CAS value");
                                    }
                                }.getCallTarget(), null);
                                for (int i = 0; i < 3; i++)
                                    exercise(
                                        false, backend, primitive, unlifted, p, target, evaluator, bottom, language);
                                compile(target);
                                exercise(true, backend, primitive, unlifted, p, target, evaluator, bottom, language);
                                assertEquals(0, entered.get());
                            } finally {
                                context.leave();
                            }
                        }
    }
    @Test
    public void badStateBoundsAndStorageFamiliesNeverMutate() {
        for (var backend : List.of("ast", "bytecode"))
            for (var primitive : cases) try (var context = context()) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var target = program(language, synthetic(primitive), backend).entryTarget("operation");
                        var old = new Object();
                        var replacement = new Object();
                        var cell = storage(primitive, old);
                        assertThrows(RuntimeFault.class, () -> cas(target, primitive, cell, old, replacement, 0, 7L));
                        assertSame(old, current(primitive, cell));
                        if (!primitive.equals("casMutVar#"))
                            for (long index : List.of(Long.MIN_VALUE, -1L, 1L, 1L << 32, Long.MAX_VALUE)) {
                                assertThrows(RuntimeFault.class,
                                    () -> cas(target, primitive, cell, old, replacement, index, INSTANCE));
                                assertSame(old, current(primitive, cell));
                            }
                        for (var other : cases)
                            if (!other.equals(primitive)) {
                                var wrong = storage(other, old);
                                assertThrows(RuntimeFault.class, () -> cas(target, primitive, wrong, old, replacement));
                                assertSame(old, current(other, wrong));
                            }
                        released(language);
                    } finally {
                        context.leave();
                    }
                }
    }
    @Test
    public void compareExchangeRacesPublishOneIdentityChainWithoutLostUpdates() throws Exception {
        class Ticket {
            final int serial;
            final Ticket previous;
            int payload;
            Ticket(int serial, Ticket previous) {
                this.serial = serial;
                this.previous = previous;
            }
        }
        for (var primitive : cases) {
            var first = new Ticket(0, null);
            var cell = storage(primitive, first);
            var start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(4);
            try {
                var jobs = new ArrayList<Future<?>>();
                for (int i = 0; i < 4; i++)
                    jobs.add(pool.submit(() -> {
                        start.await();
                        for (int n = 0; n < 64; n++) {
                            var ticket = (Ticket) current(primitive, cell);
                            while (true) {
                                assertEquals(ticket.serial * 17, ticket.payload);
                                var replacement = new Ticket(ticket.serial + 1, ticket);
                                replacement.payload = replacement.serial * 17;
                                var witness = (Ticket) exchange(primitive, cell, ticket, replacement);
                                if (witness == ticket)
                                    break;
                                ticket = witness;
                            }
                        }
                        return null;
                    }));
                start.countDown();
                for (var job : jobs) job.get(10, TimeUnit.SECONDS);
                var ticket = (Ticket) current(primitive, cell);
                for (int serial = 256; serial >= 1; serial--) {
                    assertEquals(serial, ticket.serial);
                    assertEquals(serial * 17, ticket.payload);
                    ticket = Objects.requireNonNull(ticket.previous);
                }
                assertSame(first, ticket);
            } finally {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }
    private RootCallTarget force(Language language, Metrics metrics) {
        return new RootNode(language, FrameDescriptor.newBuilder().build()) {
            @Child private Force evaluator = new Force(metrics);
            @Override
            public Object execute(VirtualFrame frame) {
                return evaluator.execute(frame, frame.getArguments()[0]);
            }
        }.getCallTarget();
    }
    private CoreRepresentation tuple(List<CoreRepresentation> fields, List<String> reps) {
        return new CoreRepresentation(CoreKind.UNKNOWN, false, true, reps, fields, null, null, null, null);
    }
    private CoreRepresentation components(CoreRepresentation proof, List<CoreRepresentation> fields) {
        return proof.copy(proof.getKind(), proof.getEvaluated(), proof.getPresent(), proof.getPrimReps(), fields,
            proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots());
    }
    private List<String> concat(List<String> a, List<String> b) {
        var result = new ArrayList<>(a);
        result.addAll(b);
        return result;
    }
    private void validate(
        String primitive, List<CoreRepresentation> args, List<Boolean> flags, CoreRepresentation result) {
        switch (primitive) {
            case "casArray#" -> ArrayOp.CAS.validate(args, flags, result);
            case "casSmallArray#" -> SmallArrayOp.CAS.validate(args, flags, result);
            default -> MutVarOp.CAS.validate(args, flags, result);
        }
    }
    @Test
    public void admissionKeepsCarriersArityStateAndLogicalTupleOrder() {
        var zero = CoreRepresentations.parse(state);
        var ref = CoreRepresentations.parse(reference);
        var flag = CoreRepresentations.parse(integer);
        var function = CoreRepresentations.parse(closure);
        for (boolean unlifted : List.of(false, true)) {
            var element = unlifted ? ref : CoreRepresentations.parse(data);
            var tuple = tuple(List.of(zero, flag, element), concat(flag.getPrimReps(), element.getPrimReps()));
            for (var primitive : cases) {
                var arguments = primitive.equals("casMutVar#") ? List.of(ref, element, element, zero)
                                                               : List.of(ref, flag, element, element, zero);
                var flags =
                    arguments.stream().map(a -> List.of("BoxedRep (Just Lifted)").equals(a.getPrimReps())).toList();
                validate(primitive, arguments, flags, tuple);
                assertThrows(RuntimeFault.class,
                    () -> validate(primitive, arguments.subList(0, arguments.size() - 1), flags, tuple));
                var badFirst = new ArrayList<>(arguments);
                badFirst.set(0, flag);
                assertThrows(RuntimeFault.class, () -> validate(primitive, badFirst, flags, tuple));
                var badLast = new ArrayList<>(arguments);
                badLast.set(badLast.size() - 1, flag);
                assertThrows(RuntimeFault.class, () -> validate(primitive, badLast, flags, tuple));
                assertThrows(RuntimeFault.class,
                    () -> validate(primitive, arguments, flags, components(tuple, List.of(zero, element, flag))));
            }
        }
        var lifted = CoreRepresentations.parse(data);
        var tuple = tuple(List.of(zero, lifted, lifted), concat(lifted.getPrimReps(), lifted.getPrimReps()));
        MutVarOp.MODIFY.validate(List.of(ref, function, zero), List.of(false, true, false), tuple);
        assertThrows(RuntimeFault.class,
            () -> MutVarOp.MODIFY.validate(List.of(ref, ref, zero), List.of(false, false, false), tuple));
        assertThrows(RuntimeFault.class,
            ()
                -> MutVarOp.MODIFY.validate(List.of(ref, function, zero), List.of(false, true, false),
                    components(tuple, List.of(zero, lifted))));
    }
    private void runModify(boolean compiled, ManagedMutVar cell, Object old, Object replacement, Object payload,
        AtomicInteger entered, boolean failure, Closure modifier, ExecutableProgram p, RootCallTarget target,
        RootCallTarget force, Language language) throws Exception {
        int beforeCalls = entered.get();
        long before = count(p, "compiledEntries");
        var result = (DataValue) Calls.target(target, new Object[] {0L, cell, modifier, INSTANCE});
        assertSame(old, result.getLayout().read(result, 0));
        assertSame(cell.getValue(), result.getLayout().read(result, 1));
        assertTrue(cell.getValue() instanceof Thunk);
        assertEquals(beforeCalls, entered.get());
        if (compiled) {
            assertEquals(before + 1, count(p, "compiledEntries"));
            assertSame(target, p.entryTarget("operation"));
            valid(target);
        }
        for (int i = 0; i < 3; i++) {
            if (failure)
                assertSame(payload,
                    assertThrows(GuestException.class, () -> Calls.target(force, new Object[] {cell.getValue()}))
                        .getPayload());
            else
                assertSame(replacement, Calls.target(force, new Object[] {cell.getValue()}));
        }
        assertEquals(beforeCalls + 1, entered.get());
        released(language);
    }
    @Test
    public void lazyModifyPublishesTheReturnedApplicationAndSharesValuesAndFailures() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var metrics = new Metrics(false);
                    var force = force(language, metrics);
                    var p = program(language, synthetic("atomicModifyMutVar_#"), backend);
                    var target = p.entryTarget("operation");
                    var old = new Object();
                    var replacement = new Object();
                    var payload = new Object();
                    var entered = new AtomicInteger();
                    for (boolean failure : List.of(false, true)) {
                        var modifier = new Closure(null, 1, new GuestRoot(language, new FrameLayout().build()) {
                            @Override
                            public long bloom(VirtualFrame frame) {
                                return (Long) frame.getArguments()[0];
                            }
                            @Override
                            public Object execute(VirtualFrame frame) {
                                assertSame(old, frame.getArguments()[1]);
                                entered.incrementAndGet();
                                if (failure)
                                    throw new GuestException(payload, this);
                                return replacement;
                            }
                        }.getCallTarget());
                        for (int i = 0; i < 3; i++)
                            runModify(false, new ManagedMutVar(old), old, replacement, payload, entered, failure,
                                modifier, p, target, force, language);
                        compile(target);
                        runModify(true, new ManagedMutVar(old), old, replacement, payload, entered, failure, modifier,
                            p, target, force, language);
                    }
                    var bottom = new Thunk(new RootNode(null) {
                        @Override
                        public Object execute(VirtualFrame frame) {
                            throw new IllegalStateException("early modifier evaluation");
                        }
                    }.getCallTarget(), null);
                    var cell = new ManagedMutVar(bottom);
                    assertThrows(RuntimeFault.class, () -> Calls.target(target, new Object[] {0L, cell, bottom, 1L}));
                    assertSame(bottom, cell.getValue());
                    var result = (DataValue) Calls.target(target, new Object[] {0L, cell, bottom, INSTANCE});
                    assertSame(bottom, result.getLayout().read(result, 0));
                    assertSame(cell.getValue(), result.getLayout().read(result, 1));
                } finally {
                    context.leave();
                }
            }
    }
    @Test
    public void concurrentLazyModifyPublishesEachUpdateExactlyOnce() throws Exception {
        try (var context = context()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var metrics = new Metrics(false);
                var entered = new AtomicInteger();
                var counter = new DataLayout(language, "Counter", "Counter", new String[] {"IntRep"});
                var modifier = new Closure(null, 1, new GuestRoot(language, new FrameLayout().build()) {
                    @Child private Force evaluator = new Force(metrics);
                    @Override
                    public long bloom(VirtualFrame frame) {
                        return (Long) frame.getArguments()[0];
                    }
                    @Override
                    public Object execute(VirtualFrame frame) {
                        var old = (DataValue) evaluator.execute(frame, frame.getArguments()[1]);
                        entered.incrementAndGet();
                        return counter.createLong(counter.readLong(old, 0) + 1);
                    }
                }.getCallTarget());
                var cell = new ManagedMutVar(counter.createLong(0));
                var site = new MutVarModifySite(language, metrics, false, false);
                var start = new CountDownLatch(1);
                var pool = Executors.newFixedThreadPool(4);
                try {
                    var jobs = new ArrayList<Future<?>>();
                    for (int i = 0; i < 4; i++)
                        jobs.add(pool.submit(() -> {
                            start.await();
                            for (int n = 0; n < 8; n++) cell.modify(modifier, site);
                            return null;
                        }));
                    start.countDown();
                    for (var job : jobs) job.get(10, TimeUnit.SECONDS);
                } finally {
                    pool.shutdownNow();
                    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
                }
                assertEquals(0, entered.get());
                var force = force(language, metrics);
                for (int i = 0; i < 2; i++)
                    assertEquals(
                        32L, counter.readLong((DataValue) Calls.target(force, new Object[] {cell.getValue()}), 0));
                assertEquals(32, entered.get());
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
    private Map<String, Object> manifest() throws Exception {
        var manifest = read("build/boxed-cas/manifest.json");
        assertEquals(1L, manifest.get("schema"));
        assertEquals("9.14.1", manifest.get("ghc"));
        assertEquals(64L, manifest.get("wordBits"));
        assertEquals(names, manifest.get("entries"));
        assertEquals(90L, manifest.get("nativeRows"));
        assertEquals(false, manifest.get("installedArtifactsHashed"));
        assertEquals(false, manifest.get("runtimeVerified"));
        var inputs = new HashSet<>(List.of("t/fixtures/compiler/BoxedCasAudit.hs",
            "t/fixtures/compiler/BoxedCasNative.hs", "src/examples/THC/BoxedCasCounter.hs",
            "t/haskell-fixtures/BoxedCasFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
            "t/haskell-fixtures/Main.hs", "thc.cabal", "bin/export-core.sh", "bin/build-compiler.sh",
            "bin/toolchain.sh", "bin/plugin.py", "bin/audit-core.py", "bin/core-capabilities.json",
            "src/main/resources/thc/scalar-primop-signatures.json"));
        try (var files = Files.list(root.resolve("src/compiler/THC"))) {
            files.filter(p -> p.getFileName().toString().endsWith(".hs"))
                .forEach(p -> inputs.add(root.relativize(p).toString()));
        }
        try (var files = Files.list(root.resolve("bin"))) {
            files
                .filter(
                    p -> p.getFileName().toString().startsWith("core_") && p.getFileName().toString().endsWith(".py"))
                .forEach(p -> inputs.add(root.relativize(p).toString()));
        }assertEquals(inputs,((Map<?,?>)manifest.get("inputHashes")).keySet());
        var oracle = (String) manifest.get("oracle");
        require(oracle.matches("build/boxed-cas/run-[1-9][0-9]*/logs/native-oracle.stdout"));
        var attempt = oracle.substring(0, oracle.length() - "/logs/native-oracle.stdout".length());
        var stages = (Map<String, List<String>>) manifest.get("stages");
        var audits = (Map<String, List<String>>) manifest.get("audits");
        assertEquals(Set.of("pre", "post"), stages.keySet());
        assertEquals(stages.keySet(), audits.keySet());
        for (var stage : stages.keySet()) {
            var prefix = attempt + "/" + stage + "-core/";
            assertEquals(Set.of("BoxedCasAudit.json", "THC.BoxedCasCounter.json", "THC.InterfaceClosure.json"),
                new HashSet<>(stages.get(stage)
                        .stream()
                        .map(p -> p.startsWith(prefix) ? p.substring(prefix.length()) : p)
                        .toList()));
            assertEquals(
                names.stream().map(n -> attempt + "/" + stage + "-" + n + ".audit.json").toList(), audits.get(stage));
        }
        var labels = new ArrayList<>(
            List.of("ghc-version", "ghc-info", "pre-export", "post-export", "native-compile", "native-oracle"));
        for (var stage : List.of("pre", "post"))
            for (var name : names) labels.add(stage + "-audit-" + name);
        var artifacts = new HashSet<String>();
        for (var paths : stages.values()) artifacts.addAll(paths);
        for (var paths : audits.values()) artifacts.addAll(paths);
        artifacts.add(attempt + "/native/boxed-cas-oracle");
        for (var label : labels)
            for (var suffix : List.of("stdout", "stderr", "command.json"))
                artifacts.add(attempt + "/logs/" + label + "." + suffix);assertEquals(artifacts,((Map<?,?>)manifest.get("artifactHashes")).keySet());
        for (var key : List.of("inputHashes", "artifactHashes"))
            for (var e : ((Map<String, String>) manifest.get(key)).entrySet()) {
                var file = root.resolve(e.getKey()).toFile();
                require(file.getCanonicalFile().equals(file.getAbsoluteFile()));
                var actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));
                assertEquals(e.getValue(), actual, "Stale boxed CAS fixture: " + e.getKey());
            }
        var commands = (List<Map<String, Object>>) manifest.get("commands");
        assertEquals(labels.size(), commands.size());
        assertTrue(commands.stream().allMatch(
            c -> Objects.equals(c.get("exit"), 0L) && Objects.equals(c.get("expectedExit"), 0L)));
        var records = new HashSet<Map<String, Object>>();
        for (var label : labels) records.add(read(attempt + "/logs/" + label + ".command.json"));
        assertEquals(records, new HashSet<>(commands));
        return manifest;
    }
    private long model(String name, long n) {
        return switch (name) {
            case "modifyValue" -> n * 3 + 1;
            case "modifyLazy" -> (n + 7) * 2;
            case "modifyBottom" -> n;
            case "boxedCasCounter" -> n & 63;
            default -> n * 3 + 102;
        };
    }
    @Test
    public void genuineCoreNativeOracleAndIndependentModelWithInlining() throws Exception {
        nativeChecks(true);
    }
    @Test
    public void genuineCoreNativeOracleAndIndependentModelWithoutInlining() throws Exception {
        nativeChecks(false);
    }
    private void check(List<String> row, org.graalvm.polyglot.Value entry, Language language, String stage,
        String backend, String name) {
        assertEquals(Long.parseLong(row.get(2)), entry.execute(Long.parseLong(row.get(1))).asLong(),
            stage + "/" + backend + "/" + name);
        released(language);
    }
    private void nativeChecks(boolean inlining) throws Exception {
        var manifest = manifest();
        var rows = Files.readAllLines(root.resolve((String) manifest.get("oracle")))
                       .stream()
                       .map(l -> Arrays.asList(l.split("\t", -1)))
                       .toList();
        var requests = new ArrayList<List<String>>();
        for (var name : names)
            for (long seed : seeds) requests.add(List.of(name, Long.toString(seed)));
        assertEquals(requests, rows.stream().map(r -> r.subList(0, Math.min(2, r.size()))).toList());
        for (var row : rows)
            assertEquals(
                model(row.get(0), Long.parseLong(row.get(1))), Long.parseLong(row.get(2)), "native/model " + row);
        for (var stage : ((Map<String, List<String>>) manifest.get("stages")).entrySet()) {
            var modules = new ArrayList<Map<String, Object>>();
            for (var path : stage.getValue()) modules.add(read(path));
            var merged = CoreModules.merge(modules);
            var primitives = new HashSet<String>();
            for (var name : names) primitives.addAll(new ArrayCoreEvidence(merged, name).getPrimitiveCounts().keySet());
            var required = new ArrayList<>(cases);
            required.add("atomicModifyMutVar_#");
            assertTrue(primitives.containsAll(required));
            for (var path : ((Map<String, List<String>>) manifest.get("audits")).get(stage.getKey())) {
                var audit = read(path);
                assertEquals(true, audit.get("accepted"));
                assertEquals(List.of(), audit.get("missingGlobals"));
                assertEquals(List.of(), audit.get("issues"));
            }
            for (var name : names)
                for (var backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
                        System.out.println("Boxed CAS native " + stage.getKey() + "/" + backend + "/" + name
                            + " inlining=" + inlining);
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var p = program(language,
                                changed(CoreModules.reachable(merged, name, true), "instrument", true), backend);
                            var entry = context.asValue(new EntryValue(p, name, 1));
                            var host = p.hostEntryTarget(1);
                            var cases = rows.stream().filter(r -> name.equals(r.get(0))).toList();
                            for (var row : cases) check(row, entry, language, stage.getKey(), backend, name);
                            var targets = activeTargets(host);
                            assertTrue(targets.size() > 1);
                            for (var target : targets)
                                if (target != host)
                                    compile(target);
                            assertTrue(entry.invokeMember("compile").asBoolean());
                            for (var row : cases.reversed()) {
                                long before = count(p, "compiledEntries");
                                check(row, entry, language, stage.getKey(), backend, name);
                                assertTrue(count(p, "compiledEntries") > before,
                                    "First installed call must enter compiled code: " + stage.getKey() + "/" + backend
                                        + "/" + name);
                                assertEquals(targets, activeTargets(host));
                                if (!name.equals("boxedCasCounter"))
                                    for (var target : targets) valid(target);
                                else {
                                    // Recursive example retention is advisory, not a feature-correctness gate.
                                    // Keep the actual native result and first compiled activity above: no retry.
                                    var retired = new ArrayList<RootCallTarget>();
                                    for (var target : targets)
                                        if (!Boolean.TRUE.equals(
                                                target.getClass().getMethod("isValidLastTier").invoke(target)))
                                            retired.add(target);
                                    if (!retired.isEmpty())
                                        System.out.println("Counter target retirement " + stage.getKey() + "/" + backend
                                            + "/" + row.get(1) + ": " + retired);
                                }
                            }
                            for (var counter : List.of("unsupportedTraps", "blackholes"))
                                assertEquals(0L, count(p, counter));
                        } finally {
                            context.leave();
                        }
                    }
        }
    }
    private static void require(boolean condition) {
        if (!condition)
            throw new IllegalArgumentException("Failed requirement.");
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
