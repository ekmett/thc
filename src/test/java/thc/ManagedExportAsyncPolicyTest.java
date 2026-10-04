// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

/** Model export protocol controls, not native registration or ABI evidence. */
class ManagedExportAsyncPolicyTest {
    private final Map<String, Object> dataRep = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> closureRep = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Map<String, Object> stateRep = map("kind", "void", "primReps", list(), "evaluated", true);
    private final Map<String, Object> scalar = map("kind", "tycon", "name", map("unit", "ghc-internal", "module", "GHC.Internal.Int", "occurrence", "Int32", "namespace", "type"), "arguments", list());
    private Value exported(Context context, String backend, boolean async) {
        return exported(context, backend, async, list(scalar));
    }
    private Value exported(Context context, String backend, boolean async, List<Map<String, Object>> arguments) {
        return exported(context, backend, async, arguments, null);
    }
    private Value exported(Context context, String backend, boolean async, List<Map<String, Object>> arguments,
            java.util.function.Consumer<com.oracle.truffle.api.nodes.Node> onEntry) {
        context.initialize("thc"); context.enter();
        try {
            var owner = Language.currentState(null); owner.getRuntimeTrace().control(500, 1); var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
            var body = list("case", list("app", list("prim", "traceEvent#"), list(list("lit", "string-bytes", "706f6c696379"), list("void", map("rep", stateRep))),
                list(false, false), false, false, map("rep", stateRep)), "traced", list(list("default", null, list(), list("var", "x", map("rep", dataRep)))),
                map("rep", dataRep, "binder", map("id", "traced", "lifted", false, "rep", stateRep)));
            var module = map("instrument", true, "bindings", list(map("id", "model:Export.identity", "name", "identity", "type", "Int32 -> Int32", "lifted", true, "arity", 1, "rep", closureRep,
                "expr", list("lam", list(map("id", "x", "name", "x", "type", "Int32", "lifted", true, "coercion", false, "rep", dataRep)), body, map("rep", closureRep, "resultRep", dataRep)))),
                "constructors", list(map("id", "ghc-internal:GHC.Internal.Int.I32#", "name", "I32#", "kind", "boxed", "arity", 1, "fieldReps", list(list("Int32Rep")), "strictFields", list(false), "fieldLifted", list(false))));
            ExecutableProgram program = backend.equals("ast") ? new Program(language, module, async, false) : new BytecodeProgram(language, module, async);
            if (onEntry != null) {
                var original = program; var delegate = original.hostEntryTarget(arguments.size());
                var target = new com.oracle.truffle.api.nodes.RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) { onEntry.accept(this); return Calls.target(delegate, frame.getArguments()); }
                }.getCallTarget();
                program = new ExecutableProgram() {
                    public boolean getAsynchronousExceptions() { return original.getAsynchronousExceptions(); }
                    public boolean getCapturesContinuations() { return original.getCapturesContinuations(); }
                    public RootCallTarget hostEntryTarget(int arity) { return target; }
                    public Object entryValue(String name) { return original.entryValue(name); }
                    public RootCallTarget entryTarget(String name) { return original.entryTarget(name); }
                    public DataLayout constructorLayout(String id) { return original.constructorLayout(id); }
                    public Map<String, Object> diagnostics() { return original.diagnostics(); }
                };
            }
            var signature = new ManagedExportSignature("model", "Export", "identity", "model:Export.identity", arguments, scalar, false, 64, null);
            return context.asValue(new ManagedExportValue(owner.getManagedExports(), owner, language, program, signature));
        } finally { context.leave(); }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> void rethrow(Throwable error) throws E { throw (E) error; }
    private void checkPolicy(boolean imported) throws Exception {
        for (String backend : list("ast", "bytecode")) for (boolean async : list(false, true)) {
            int[] observed = {0}; GuestThreadId[] active = {null};
            var output = new ByteArrayOutputStream() {
                @Override public void write(byte[] bytes, int start, int length) {
                    if (length > 0) {
                        observed[0]++; var threads = Language.currentState(null).getThreads();
                        var slot = Objects.requireNonNull(threads.pollState(Thread.currentThread()).getCurrent());
                        active[0] = slot.getIdentity(); assertTrue(slot.getExternalAsync(), backend + " async=" + async + " imported=" + imported);
                        try {
                            CompletableFuture.runAsync(() -> {
                                var request = threads.send(slot.getIdentity(), "external policy probe");
                                try { assertFalse(request.getForceSelf(), "Another carrier cannot use self delivery"); assertEquals(AsyncRequestState.PENDING, request.getState()); }
                                finally { assertTrue(request.cancel()); }
                                assertEquals(AsyncRequestState.CANCELLED, request.getState());
                            }).get(5, TimeUnit.SECONDS);
                        } catch (Exception error) { ManagedExportAsyncPolicyTest.<RuntimeException>rethrow(error); }
                    }
                    super.write(bytes, start, length);
                }
            };
            try (var exporter = Context.newBuilder("thc").err(output).build()) {
                var value = exported(exporter, backend, async); assertEquals(0, observed[0], "Creating an exported value must not execute it");
                if (!imported) assertEquals(19, value.execute(19).asInt());
                else try (var importer = Context.newBuilder("thc").build()) {
                    importer.initialize("thc"); importer.enter();
                    try {
                        var owner = Language.currentState(null); var threads = owner.getThreads();
                        threads.enterCurrent(null, false, true, null); var caller = threads.currentIdentity();
                        try {
                            var foreign = threads.enterForeign(ForeignSafety.SAFE);
                            try { assertEquals(19, value.execute(19).asInt()); } finally { threads.leaveForeign(foreign); }
                            assertSame(owner, Language.currentState(null)); assertSame(caller, threads.currentIdentity()); assertNotSame(caller, active[0]);
                            assertEquals(GuestThreadStatus.RUNNING, threads.status(caller));
                        } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
                        assertNull(threads.pollState(Thread.currentThread()).getCurrent());
                    } finally { importer.leave(); }
                }
                assertTrue(observed[0] > 0); assertEquals("[thc trace event] policy\n", output.toString(StandardCharsets.UTF_8));
                exporter.enter();
                try {
                    var threads = Language.currentState(null).getThreads();
                    assertNull(threads.pollState(Thread.currentThread()).getCurrent(), "Export return must restore the prior guest slot");
                    assertEquals(imported ? GuestThreadStatus.FINISHED : GuestThreadStatus.FOREIGN, threads.status(Objects.requireNonNull(active[0])));
                    var late = CompletableFuture.supplyAsync(() -> threads.send(Objects.requireNonNull(active[0]), "after return")).get(5, TimeUnit.SECONDS);
                    assertEquals(AsyncRequestState.TARGET_FINISHED, late.getState());
                } finally { exporter.leave(); }
                exporter.close(); assertThrows(RuntimeException.class, () -> value.execute(1));
            }
        }
    }
    @Test void directExportsPreserveTheirExecutableAsyncPolicy() throws Exception { checkPolicy(false); }
    @Test void safeCrossContextImportsPreserveExporterPolicyAndRestoreCaller() throws Exception { checkPolicy(true); }

    @ParameterizedTest @CsvSource({"ast,platform", "ast,loom", "bytecode,platform", "bytecode,loom"})
    @org.junit.jupiter.api.Timeout(60)
    void anotherOriginExportInvalidatesBeforeEffectsWhileOriginalStackIsActive(String backend, String hosting) throws Exception {
        var first = new CountDownLatch(1); var second = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var owner = new AtomicReference<Language.State>();
        try (var context = Context.newBuilder("thc").err(new ByteArrayOutputStream()).allowCreateThread(true).allowExperimentalOptions(true)
                .option("thc.ThreadHosting", hosting).build()) {
            var value = exported(context, backend, false, list(scalar), node -> {
                if (calls.incrementAndGet() == 1) {
                    assertTrue(owner.get().getSingleGuestOriginAssumption().isValid()); first.countDown();
                    var threads = owner.get().getThreads(); var permission = threads.enterForeign(ForeignSafety.SAFE);
                    try {
                        com.oracle.truffle.api.TruffleSafepoint.setBlockedThreadInterruptible(node,
                            gate -> assertTrue(gate.await(10, TimeUnit.SECONDS)), second);
                    } finally { threads.leaveForeign(permission); }
                    assertFalse(owner.get().getSingleGuestOriginAssumption().isValid());
                } else {
                    assertFalse(owner.get().getSingleGuestOriginAssumption().isValid()); second.countDown();
                }
            });
            context.enter(); try { owner.set(Language.currentState()); } finally { context.leave(); }
            var active = CompletableFuture.supplyAsync(() -> value.execute(19).asInt());
            try {
                assertTrue(first.await(10, TimeUnit.SECONDS));
                assertEquals(23, value.execute(23).asInt()); assertEquals(19, active.get(10, TimeUnit.SECONDS));
                assertEquals(2, calls.get());
            } finally { second.countDown(); }
        }
    }

    private Map<String, Object> binder(String id, Map<String, Object> proof, boolean lifted) {
        return map("id", id, "name", id, "lifted", lifted, "rep", proof);
    }
    private List<Object> variable(String id, Map<String, Object> proof) { return list("var", id, map("rep", proof)); }
    private ManagedExportValue selfDeliveryExport(String backend, boolean async, AtomicReference<AsyncRequest> request,
            AtomicReference<GuestThreadId> identity) {
        var owner = Language.currentState(); owner.getRuntimeTrace().control(500, 1);
        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
        var threadRep = map("kind", "object", "primReps", list("BoxedRep (Just Unlifted)"), "evaluated", true);
        var threadTuple = map("kind", "unknown", "aggregate", "unboxed-tuple", "components", list(stateRep, threadRep),
            "primReps", list("BoxedRep (Just Unlifted)"), "evaluated", true);
        var ioResult = map("kind", "unknown", "aggregate", "unboxed-tuple", "components", list(stateRep, dataRep),
            "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
        var result = list("app", list("con", "StateResult", 2), list(variable("after", stateRep), variable("x", dataRep)),
            list(false, true), true, true, map("rep", ioResult));
        var kill = list("app", list("prim", "killThread#"), list(variable("tid", threadRep), variable("x", dataRep), variable("s1", stateRep)),
            list(false, true, false), false, false, map("rep", stateRep));
        var killed = list("case", kill, "after", list(list("default", null, list(), result)),
            map("rep", ioResult, "binder", binder("after", stateRep, false)));
        var current = list("app", list("prim", "myThreadId#"), list(variable("s", stateRep)),
            list(false), false, false, map("rep", threadTuple));
        var action = list("case", current, "current", list(list("data", "StateThread", list("s1", "tid"), killed,
            map("binders", list(binder("s1", stateRep, false), binder("tid", threadRep, false))))),
            map("rep", ioResult, "binder", binder("current", threadTuple, false)));
        var traced = list("app", list("prim", "traceEvent#"), list(list("lit", "string-bytes", "73656c662d6578706f7274"), variable("s", stateRep)),
            list(false, false), false, false, map("rep", stateRep));
        var body = list("case", traced, "traced", list(list("default", null, list(), action)),
            map("rep", ioResult, "binder", binder("traced", stateRep, false)));
        var source = map("bindings", list(map("id", "model:Export.self", "name", "self", "arity", 2, "lifted", true, "rep", closureRep,
                "expr", list("lam", list(binder("x", dataRep, true), binder("s", stateRep, false)), body, map("rep", closureRep, "resultRep", ioResult)))),
            "constructors", list(map("id", "StateThread", "name", "StateThread", "kind", "unboxed-tuple", "arity", 2),
                map("id", "StateResult", "name", "StateResult", "kind", "unboxed-tuple", "arity", 2),
                map("id", "ghc-internal:GHC.Internal.Int.I32#", "name", "I32#", "kind", "boxed", "arity", 1,
                    "fieldReps", list(list("Int32Rep")), "strictFields", list(false), "fieldLifted", list(false))));
        ExecutableProgram program = backend.equals("ast") ? new Program(language, source, async, false) : new BytecodeProgram(language, source, async);
        var original = (Closure) program.entryValue("model:Export.self");
        var root = (GuestRoot) original.target.getRootNode();
        // Observe the real lowered self-kill token before boundary cleanup, without
        // replacing delivery, acknowledging it, or fabricating a control signal.
        var observed = new GuestRoot(language, new FrameLayout().build()) {
            @Override public boolean getAsynchronousExceptions() { return true; }
            @Override public long bloom(VirtualFrame frame) { return 0L; }
            @Override public Object execute(VirtualFrame frame) {
                identity.set(owner.getThreads().currentIdentity());
                assertEquals(MaskingState.UNMASKED, owner.getMaskingState().get());
                assertTrue(owner.getThreads().pollState(Thread.currentThread()).getCurrent().getExternalAsync());
                try {
                    Object answer = Calls.target(original.target, frame.getArguments());
                    var saved = SavedGuestContinuations.savedGuestContinuation(answer instanceof TailYield tail ? tail.getContinuation()
                        : answer instanceof AstTailYield tail ? tail.getContinuation() : answer);
                    if (saved != null) request.set(saved.asyncRequest());
                    return answer;
                } catch (AsyncDelivery delivered) {
                    request.set(delivered.getRequest());
                    throw delivered;
                }
            }
        };
        observed.configureInputProofs(root.getInputProofs()); observed.configureInput(root.getInputLayout());
        observed.configureEntry(root.getEntryStrict(), original.environment != null); observed.configureTypedInput(root.getTypedInput());
        observed.configureTupleResult(root.getTupleResult());
        var target = observed.getCallTarget();
        var entry = new Closure(original.environment, original.supplied, original.arity, target, original.suppliedCount, original.typedSupplied);
        var observingProgram = new ExecutableProgram() {
            @Override public boolean getAsynchronousExceptions() { return program.getAsynchronousExceptions(); }
            @Override public boolean getCapturesContinuations() { return program.getCapturesContinuations(); }
            @Override public RootCallTarget hostEntryTarget(int arity) { return program.hostEntryTarget(arity); }
            @Override public Object entryValue(String name) { return entry; }
            @Override public RootCallTarget entryTarget(String name) { return target; }
            @Override public DataLayout constructorLayout(String id) { return program.constructorLayout(id); }
            @Override public Map<String, Object> diagnostics() { return program.diagnostics(); }
        };
        var signature = new ManagedExportSignature("model", "Export", "self", "model:Export.self", list(scalar), scalar, true, 64,
            CoreRepresentations.parse(ioResult));
        return new ManagedExportValue(owner.getManagedExports(), owner, language, observingProgram, signature);
    }
    private void checkSelfDelivery(String backend, boolean async, boolean callback) throws Exception {
        var request = new AtomicReference<AsyncRequest>();
        var identity = new AtomicReference<GuestThreadId>();
        var output = new ByteArrayOutputStream();
        try (var context = Context.newBuilder("thc").err(output).build()) {
            context.initialize("thc"); context.enter();
            try {
                var owner = Language.currentState(); var threads = owner.getThreads();
                var entry = selfDeliveryExport(backend, async, request, identity);
                var interop = InteropLibrary.getUncached();
                GuestException guest;
                if (callback) {
                    threads.enterCurrent(MaskingState.MASKED_UNINTERRUPTIBLE, false, true, null);
                    var caller = threads.currentIdentity();
                    var pending = threads.send(caller, "suspended caller");
                    try {
                        var permission = threads.enterForeign(ForeignSafety.SAFE);
                        try {
                            var failure = assertThrows(ForeignCallbackAsyncFailure.class, () -> interop.execute(entry, 19));
                            guest = assertInstanceOf(GuestException.class, failure.getCause());
                            assertSame(guest.getPayload(), failure.getPayload());
                            assertSame(caller, threads.currentIdentity());
                            assertNull(threads.poll(null, false), "Callback exit restores foreign permission");
                        } finally { threads.leaveForeign(permission); }
                        assertNotSame(caller, identity.get()); assertTrue(identity.get().getCallback());
                        assertEquals(GuestThreadStatus.DIED, threads.status(identity.get()));
                        assertSame(caller, threads.currentIdentity()); assertEquals(GuestThreadStatus.RUNNING, threads.status(caller));
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, owner.getMaskingState().get());
                        assertEquals(AsyncRequestState.PENDING, pending.getState(), "Callback must not consume its caller's request");
                    } finally { pending.cancel(); threads.leaveCurrent(); }
                } else guest = assertThrows(GuestException.class, () -> interop.execute(entry, 19));
                var delivered = Objects.requireNonNull(request.get());
                assertTrue(delivered.getForceSelf()); assertEquals(AsyncRequestState.ACKNOWLEDGED, delivered.getState());
                assertSame(delivered.getPayload(), guest.getPayload());
                var payload = assertInstanceOf(DataValue.class, guest.getPayload());
                assertEquals("ghc-internal:GHC.Internal.Int.I32#", payload.getLayout().getId());
                assertEquals(19, payload.getLayout().read(payload, 0));
                assertEquals(GuestThreadStatus.DIED, identity.get().getLastOutcome());
                assertNull(threads.pollState(Thread.currentThread()).getCurrent());
                assertEquals("[thc trace event] self-export\n", output.toString(StandardCharsets.UTF_8));
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @CsvSource({"ast,false", "ast,true", "bytecode,false", "bytecode,true"})
    void uncaughtIoSelfDeliveryAcknowledgesTheOriginalPayload(String backend, boolean async) throws Exception {
        checkSelfDelivery(backend, async, false);
    }
    @ParameterizedTest @CsvSource({"ast,false", "ast,true", "bytecode,false", "bytecode,true"})
    void safeCallbackSelfDeliveryAcknowledgesItsPayloadAndRestoresTheCaller(String backend, boolean async) throws Exception {
        checkSelfDelivery(backend, async, true);
    }

    @Test void privateDescriptorSnapshotKeepsArityAndHostValidationBeforeEffects() {
        for (String backend : list("ast", "bytecode")) {
            var output = new ByteArrayOutputStream();
            try (var context = Context.newBuilder("thc").err(output).build()) {
                var supplied = new ArrayList<Map<String, Object>>(list(scalar));
                var value = exported(context, backend, false, supplied);
                supplied.clear();
                assertEquals(0, output.size(), "Descriptor conversion must not execute the export");
                assertThrows(RuntimeException.class, () -> value.execute());
                assertThrows(RuntimeException.class, () -> value.execute(1, 2));
                assertThrows(RuntimeException.class, () -> value.execute("wrong carrier"));
                assertThrows(RuntimeException.class, () -> value.execute(1L << 32));
                assertEquals(0, output.size(), "Arity and host conversion failures must precede guest effects");
                assertEquals(19, value.execute(19).asInt());
                assertEquals("[thc trace event] policy\n", output.toString(StandardCharsets.UTF_8));
            }
        }
    }
}
