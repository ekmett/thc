// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleSafepoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.graalvm.polyglot.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import thc.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static thc.Main.loadEntry;
import static thc.runtime.ManagedSignals.finishSignalConsumer;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;

@SuppressWarnings("unchecked")
public class ProcessSignalsTest {
    private Map<String, Object> resource(String path) {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream(path))) { return (Map<String, Object>) Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8)); }
        catch (IOException failure) { return rethrow(failure); }
    }
    private final Map<String, Object> descriptor = resource("/core/original-signal-install-descriptor.json");
    // Original unix System.Posix.Signals core/39.json SHA256
    // f4f562a283aec06f517c9187d13d47b93b876a5f0f807af999bcbd6cab1d44dc;
    // all six calls have this descriptor, differing from TopHandler only in unit.
    private final Map<String, Object> unixDescriptor = resource("/core/original-unix-signal-install-descriptor.json");
    private final List<Map<String, Object>> arguments = arguments();
    private List<Map<String, Object>> arguments() { var result = new ArrayList<Map<String, Object>>(); for (var rep : (List<Map<String, Object>>) descriptor.get("argumentReps")) result.add(with(rep, "evaluated", true)); return result; }
    private final Map<String, Object> result = (Map<String, Object>) descriptor.get("resultRep");
    private final Map<String, Object> metadata = Map.of("rep", result, "foreignCall", descriptor);
    private final Map<String, Object> closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Map<String, Object> boxed = with(closure, "kind", "data");
    private final Map<String, Object> state = arguments.getLast();
    private final String unitId = "ghc-internal:GHC.Internal.Tuple.()";
    private Map<String, Object> with(Map<String, Object> source, String key, Object value) { var result = new LinkedHashMap<>(source); result.put(key, value); return result; }
    private List<Object> variable(String id, Map<String, Object> proof) { return List.of("var", id, Map.of("rep", proof)); }
    private List<Object> application() { return application(descriptor); }
    private List<Object> application(Map<String, Object> original) {
        var operands = new ArrayList<List<Object>>(); for (int i = 0; i < arguments.size(); i++) operands.add(variable("a" + i, arguments.get(i)));
        return List.of("app", variable("original-signal-fcall", closure), operands, Collections.nCopies(4, false), false, false, with(metadata, "foreignCall", original));
    }
    private Map<String, Object> binder(String id, Map<String, Object> proof, boolean lifted) { return Map.of("id", id, "rep", proof, "lifted", lifted); }
    private Map<String, Object> constructor(String id, String name, List<Map<String, Object>> fields) {
        var reps = fields.stream().map(field -> field.get("primReps")).toList();
        return Map.of("id", id, "name", name, "kind", "boxed", "arity", fields.size(), "tag", 1, "fieldReps", reps, "fieldTypes", fields,
            "strictFields", Collections.nCopies(fields.size(), false), "fieldLifted", Collections.nCopies(fields.size(), false));
    }
    private Map<String, Object> module(boolean recordSignal, Map<String, Object> original) {
        var inputs = new ArrayList<Map<String, Object>>(); for (int i = 0; i < arguments.size(); i++) inputs.add(Map.of("id", "a" + i, "lifted", false, "rep", arguments.get(i)));
        var binding = Map.of("id", "install", "name", "install", "arity", 4, "lifted", true, "rep", closure,
            "expr", List.of("lam", inputs, application(original), Map.of("rep", closure, "resultRep", result)));
        var ioResult = Map.of("kind", "unknown", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false, "aggregate", "unboxed-tuple", "components", List.of(state, boxed));
        List<?> returned = List.of("app", List.of("con", "tuple2", 2, Map.of("rep", closure)), List.of(variable("token", state), List.of("con", unitId, 0, Map.of("rep", boxed))),
            List.of(false, true), true, true, Map.of("rep", ioResult));
        List<?> body = returned;
        if (recordSignal) {
            Map<String, Object> address = Map.of("kind", "address", "primReps", List.of("AddrRep"), "evaluated", true);
            Map<String, Object> number = Map.of("kind", "long", "primReps", List.of("Int32Rep"), "evaluated", true);
            var integer = with(number, "primReps", List.of("IntRep"));
            var write = List.of("app", List.of("prim", "writeInt32OffAddr#"), List.of(variable("infoAddress", address),
                List.of("lit", "int", "0", Map.of("rep", integer)), variable("signalNumber", number), variable("token", state)), Collections.nCopies(4, false), false, false, Map.of("rep", state));
            body = List.of("case", write, "written", List.of(Arrays.asList("default", null, List.of(), returned)), Map.of("rep", ioResult, "binder", binder("written", state, false)));
            body = List.of("case", variable("signal", boxed), "signalBox", List.of(List.of("data", "ghc-internal:GHC.Internal.Int.I32#", List.of("signalNumber"), body,
                Map.of("binders", List.of(binder("signalNumber", number, false))))), Map.of("rep", ioResult, "binder", binder("signalBox", boxed, true)));
            body = List.of("case", variable("pointer", boxed), "pointerBox", List.of(List.of("data", "ghc-internal:GHC.Internal.Ptr.Ptr", List.of("infoAddress"), body,
                Map.of("binders", List.of(binder("infoAddress", address, false))))), Map.of("rep", ioResult, "binder", binder("pointerBox", boxed, true)));
        }
        // Synthetic consumer, not replacement evidence for original Conc.Signal:
        // exact boxed Ptr/CInt/State transport.
        var dispatcher = Map.of("id", CoreSignalForeign.dispatcher, "name", "runHandlersPtr", "arity", 3, "lifted", true, "rep", closure,
            "expr", List.of("lam", List.of(Map.of("id", "pointer", "lifted", true, "rep", boxed), Map.of("id", "signal", "lifted", true, "rep", boxed),
                Map.of("id", "token", "lifted", false, "rep", state)), body, Map.of("rep", closure, "resultRep", ioResult)));
        return Map.of("bindings", List.of(binding, dispatcher), "instrument", true, "constructors", List.of(
            constructor("ghc-internal:GHC.Internal.Ptr.Ptr", "Ptr", List.of(arguments.get(2))),
            constructor("ghc-internal:GHC.Internal.Int.I32#", "I32#", List.of(arguments.get(0))), constructor(unitId, "()", List.of()),
            Map.of("id", "tuple2", "name", "(#,#)", "kind", "unboxed-tuple", "arity", 2, "tag", 1)));
    }
    @FunctionalInterface private interface BackendAction { void run(Language language, String backend) throws Exception; }
    private void onBackends(BackendAction body) throws Exception { onBackends(false, body); }
    private void onBackends(boolean loom, BackendAction body) throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowCreateThread(true).allowNativeAccess(true).allowExperimentalOptions(true)
            .option("thc.ThreadHosting", loom ? "loom" : "platform")
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try { body.run(TruffleLanguage.LanguageReference.create(Language.class).get(null), backend); } finally { context.leave(); }
        }
    }
    ExecutableProgram program(Language language, String backend) { return program(language, backend, false, true, descriptor); }
    private ExecutableProgram program(Language language, String backend, boolean recordSignal, boolean async, Map<String, Object> original) {
        return backend.equals("ast") ? new Program(language, module(recordSignal, original), async) : new BytecodeProgram(language, module(recordSignal, original), async);
    }
    @SuppressWarnings("unchecked") private static <T, E extends Throwable> T rethrow(Throwable failure) throws E { throw (E) failure; }

    @Test public void hardContextExitSurvivesBothHostCleanupSteps() {
        var death = new ThreadDeath(); var restoration = new RuntimeFault("native restoration failure"); var bookkeeping = new RuntimeFault("thread bookkeeping failure"); var cleaned = new ArrayList<String>();
        var observed = assertThrows(ThreadDeath.class, () -> finishSignalConsumer(death, () -> { cleaned.add("native"); throw restoration; }, () -> { cleaned.add("thread"); throw bookkeeping; }));
        assertSame(death, observed); assertEquals(List.of("native", "thread"), cleaned); assertEquals(List.of(restoration, bookkeeping), Arrays.asList(death.getSuppressed()));
        var laterDeath = new ThreadDeath(); var failure = new RuntimeFault("reader failure"); var unregistered = new boolean[1];
        assertSame(laterDeath, assertThrows(ThreadDeath.class, () -> finishSignalConsumer(failure, () -> { throw laterDeath; }, () -> { unregistered[0] = true; })));
        assertTrue(unregistered[0]); assertEquals(List.of(failure), Arrays.asList(laterDeath.getSuppressed()));
    }
    @Test public void failedInitialInstallationPreservesHardExitAndClosesStartup() throws Exception {
        for (boolean loom : new boolean[]{false, true}) onBackends(loom, (language, backend) -> {
            var owner = Language.currentState(); var death = new ThreadDeath();
            var cleanup = new RuntimeFault("native installation cleanup failed"); var closes = new AtomicInteger();
            var transport = new ProcessSignalTransport() {
                public Result install(int signal, int action) { throw death; }
                public Event take() { throw new AssertionError("failed installation started a reader"); }
                public void wake() { throw new AssertionError("failed installation retained its transport"); }
                public void resetWake() { }
                public void close() { closes.incrementAndGet(); throw cleanup; }
            };
            var service = new ManagedSignals(owner, language, true, () -> true, () -> transport);
            service.bind(program(language, backend)); service.authorizeLauncher();
            assertSame(death, assertThrows(ThreadDeath.class, () -> service.install(2, -4, ManagedAddress.nullAddress())));
            assertEquals(List.of(cleanup), Arrays.asList(death.getSuppressed()));
            service.close(); assertEquals(1, closes.get());
        });
    }
    @Test public void genuineOriginalDeclarationRejectsWidthHeadStateAndDescriptorNearMisses() {
        // Original TopHandler core/246.json SHA256 6dd8a0de3bfc8664c9ea1cd2cfee6d687438761adf15a192dfde3c8e21a6fbea.
        assertEquals(ProcessSignalOp.INSTALL, CoreSignalForeign.validate(metadata, arguments, Collections.nCopies(4, false), result)); CoreSignalForeign.validateHeads(application());
        var wrongs = new LinkedHashMap<String, Object>(); wrongs.put("schema", 2L); wrongs.put("arity", 3L); wrongs.put("suppliedArity", 5L); wrongs.put("safety", "safe"); wrongs.put("convention", "capi"); wrongs.put("extra", 0L);
        for (var wrong : wrongs.entrySet()) assertThrows(RuntimeFault.class, () -> CoreSignalForeign.validate(with(metadata, "foreignCall", with(descriptor, wrong.getKey(), wrong.getValue())), arguments, Collections.nCopies(4, false), result));
        for (int index = 0; index < arguments.size(); index++) {
            var malformed = new ArrayList<>(arguments); malformed.set(index, with(arguments.get(index), "primReps", List.of("IntRep")));
            assertThrows(RuntimeFault.class, () -> CoreSignalForeign.validate(metadata, malformed, Collections.nCopies(4, false), result));
        }
        assertThrows(RuntimeFault.class, () -> CoreSignalForeign.validate(metadata, arguments, List.of(false, false, true, false), result));
        assertThrows(RuntimeFault.class, () -> CoreSignalForeign.validateHead(variable("original-signal-fcall", closure), true));
        var forged = new ArrayList<>(application()); forged.set(1, List.of("prim", "stg_sig_install")); assertThrows(RuntimeFault.class, () -> CoreSignalForeign.validateHeads(forged));
    }
    @Test public void unixUsesTheExactSameAbiAndRejectsUnrelatedUnits() {
        var target = (Map<String, Object>) descriptor.get("target"); assertEquals(with(descriptor, "target", with(target, "unit", "unix-2.8.8.0-inplace")), unixDescriptor);
        var unixMetadata = with(metadata, "foreignCall", unixDescriptor); assertTrue(CoreSignalForeign.named(unixMetadata));
        assertEquals(ProcessSignalOp.INSTALL, CoreSignalForeign.validate(unixMetadata, arguments, Collections.nCopies(4, false), result));
        for (var unit : Arrays.asList(null, "unix", "unix-2.8.7.0-inplace", "other", 1L)) {
            var wrong = with(metadata, "foreignCall", with(descriptor, "target", with(target, "unit", unit))); assertFalse(CoreSignalForeign.named(wrong));
            assertThrows(RuntimeFault.class, () -> CoreSignalForeign.validate(wrong, arguments, Collections.nCopies(4, false), result));
        }
    }
    @Test public void compiledEmbeddingDenialRemainsAHostFaultOnBothBackends() throws Exception { compiledEmbeddingDenial(descriptor); }
    @Test public void compiledUnixEmbeddingDenialRemainsAHostFaultOnBothBackends() throws Exception { compiledEmbeddingDenial(unixDescriptor); }
    private void reject(RootCallTarget target, Object token) {
        var failure = assertThrows(RuntimeFault.class, () -> callScalarTestTarget(target, new Object[] {0L, 2, -5, ManagedAddress.nullAddress(), token}));
        assertEquals(RuntimeFault.class, failure.getClass()); if (token == thc.runtime.Unit.INSTANCE) assertTrue(failure.getMessage().contains("launcher authority"));
    }
    private void compiledEmbeddingDenial(Map<String, Object> original) throws Exception {
        onBackends((language, backend) -> {
            var program = program(language, backend, false, true, original); var target = program.entryTarget("install"); var state = Language.currentState(); state.getThreads().enterCurrent();
            try {
                reject(target, thc.runtime.Unit.INSTANCE); reject(target, 1L); target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                reject(target, thc.runtime.Unit.INSTANCE); assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                // Bytecode invalidates ordinary host faults; denial remains a RuntimeFault, not a guest error.
                if (backend.equals("bytecode")) assertEquals(false, target.getClass().getMethod("isValidLastTier").invoke(target));
                assertEquals(0, language.getHandoffState().get().getResults().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
                assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getArguments().retainedReferences());
            } finally { state.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); }
        });
    }
    @Test public void adaptiveSignalPublicationInvalidatesBeforeTransportAcquisition() throws Exception {
        onBackends((language, backend) -> {
            var owner = Language.currentState(); var stopped = new RuntimeFault("transport boundary");
            var service = new ManagedSignals(owner, language, true, () -> true, () -> {
                assertFalse(owner.getSingleGuestOriginAssumption().isValid()); throw stopped;
            });
            try {
                service.bind(program(language, backend, false, false, descriptor));
                assertTrue(owner.getSingleGuestOriginAssumption().isValid()); service.authorizeLauncher();
                assertSame(stopped, assertThrows(RuntimeFault.class, () -> service.install(2, -4, ManagedAddress.nullAddress())));
            } finally { service.close(); }
        });
    }
    @Test public void malformedLauncherAsyncPropertyFailsExplicitly() {
        var previous = System.getProperty("thc.asyncExceptions");
        try { System.setProperty("thc.asyncExceptions", "enabled"); try (var context = Context.newBuilder("thc").build()) { assertThrows(IllegalArgumentException.class, () -> loadEntry(context, List.of(), "unused")); } }
        finally { property("thc.asyncExceptions", previous); }
    }
    private void property(String key, String value) { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }
    @Test public void launcherAsyncPropertyAndExplicitArgumentPreserveDefaultOff(@TempDir Path directory) throws Exception {
        Map<String, Object> integer = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        var identity = Map.of("id", "identity", "name", "identity", "arity", 1, "lifted", true, "rep", closure, "expr", List.of("lam",
            List.of(Map.of("id", "x", "lifted", false, "rep", integer)), variable("x", integer), Map.of("rep", closure, "resultRep", integer)));
        var core = CoreCbdTestSupport.writeModel(directory.resolve("SignalOptions.cbd"), Map.of("schema", 1, "ghc", "9.14.1", "module", "SignalOptions", "unit", "main", "boundary", "synthetic", "constructors", List.of(), "bindings", List.of(identity)));
        var previous = System.getProperty("thc.asyncExceptions");
        try {
            for (var setting : Arrays.asList(null, "true", "false")) {
                property("thc.asyncExceptions", setting);
                for (var backend : List.of("ast", "bytecode")) try (var context = Context.newBuilder("thc").build()) {
                    var action = loadEntry(context, List.of(core.toString()), "identity", true, backend);
                    assertEquals("true".equals(setting), ((Map<?, ?>) Json.parse(action.getMember("diagnostics").asString())).get("asyncExceptions"));
                    assertEquals(17L, action.execute(17L).asLong());
                    for (boolean override : new boolean[]{false, true}) {
                        var explicit = loadEntry(context, List.of(core.toString()), "identity", true, backend, false, null, override);
                        assertEquals(override, ((Map<?, ?>) Json.parse(explicit.getMember("diagnostics").asString())).get("asyncExceptions"));
                        assertEquals(17L, explicit.execute(17L).asLong());
                    }
                }
            }
        } finally { property("thc.asyncExceptions", previous); }
    }
    @Test public void contextTransportKeepsOldActionsAndClosesAfterDelivery() throws Exception {
        contextTransport(false);
    }
    @Test public void loomTransportKeepsOldActionsAndClosesAfterDelivery() throws Exception {
        contextTransport(true);
    }
    private void contextTransport(boolean loom) throws Exception {
        assumeTrue(NativeIO.supportedPosixHost() || System.getProperty("os.name").startsWith("Mac"));
        var abi = StdioHostAbi.load();
        onBackends(loom, (language, backend) -> {
            var owner = Language.currentState(); var events = new LinkedBlockingQueue<ProcessSignalTransport.Event>(); var delivered = new CountDownLatch(8); var closed = new AtomicInteger();
            owner.getThreads().setCapabilityCount(1);
            var fake = new ProcessSignalTransport() {
                private final Map<Integer, Integer> old = new HashMap<>(); private boolean received;
                @Override public Result install(int signal, int action) { var previous = old.put(signal, action); return new Result(previous == null ? -1 : previous, 0); }
                @Override public Event take() {
                    assertEquals(loom, Thread.currentThread().isVirtual());
                    if (received) delivered.countDown(); received = false;
                    final Event event; try { event = events.take(); } catch (InterruptedException failure) { return rethrow(failure); }
                    if (event.info().length == 0) return null; received = true; return event;
                }
                @Override public void wake() { events.offer(new Event(0, new byte[0])); }
                @Override public void resetWake() { events.removeIf(event -> event.info().length == 0); }
                @Override public void close() { closed.incrementAndGet(); }
            };
            var service = new ManagedSignals(owner, language, true, () -> true, () -> fake); var program = program(language, backend); service.bind(program);
            java.util.function.LongBinaryOperator install = (signal, action) -> loom ? owner.getThreads().hostEntry(null, () -> {
                owner.getThreads().enterCurrent();
                try { return service.install(signal, action, ManagedAddress.nullAddress()); }
                finally { owner.getThreads().leaveCurrent(); }
            }) : service.install(signal, action, ManagedAddress.nullAddress());
            assertThrows(RuntimeFault.class, () -> service.install(2L, -5L, ManagedAddress.nullAddress())); service.authorizeLauncher();
            for (var bad : List.of(new long[] {64L, -5L}, new long[] {11L, -5L}, new long[] {2L, -3L}, new long[] {2L, 1L}))
                assertThrows(RuntimeFault.class, () -> service.install(bad[0], bad[1], ManagedAddress.nullAddress()));
            for (var name : StdioHostAbi.SIGNAL_NAMES) {
                long signal = abi.signal(name);
                var actual = new ArrayList<Long>(); for (long action : new long[] {-2L, -4L, -5L, -1L}) actual.add(install.applyAsLong(signal, action));
                assertEquals(List.of(-1L, -2L, -4L, -5L), actual);
            }
            try {
                for (var name : StdioHostAbi.SIGNAL_NAMES) { var bytes = new byte[Math.toIntExact(abi.getSiginfoBytes())]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i; events.put(new ProcessSignalTransport.Event((int) abi.signal(name), bytes)); }
                assertTrue(TruffleSafepoint.setBlockedThreadInterruptibleFunction(null, (TruffleSafepoint.InterruptibleFunction<CountDownLatch, Boolean>) latch -> latch.await(5, TimeUnit.SECONDS), delivered), "typed guest dispatcher must return");
                assertEquals(8, owner.getNativeAllocations().liveCount(), "dispatcher images remain context-owned");
            } finally { service.close(); }
            assertEquals(1, closed.get()); assertThrows(RuntimeFault.class, () -> service.install(2L, -5L, ManagedAddress.nullAddress()));
        });
    }
    @Test public void extendedHandlersRequireReleasedVmSignalsBeforeAcquiringTransport() throws Exception {
        onBackends((language, backend) -> {
            var service = new ManagedSignals(Language.currentState(), language, false, NativeSignalTransport::userSignalAvailable, () -> { throw new IllegalStateException("denied request must not acquire a native signal transport"); });
            service.bind(program(language, backend)); service.authorizeLauncher();
            var abi = StdioHostAbi.load();
            for (var name : StdioHostAbi.SIGNAL_NAMES) {
                if (name.equals("SIGINT")) continue;
                long signal = abi.signal(name);
                var failure = assertThrows(RuntimeFault.class, () -> service.install(signal, -4L, ManagedAddress.nullAddress())); assertTrue(failure.getMessage().contains("-Xrs"));
            }
            service.close();
        });
    }
    @Test public void userSignalRequiresVerifiedRelocationBeforeAcquiringTransport() throws Exception {
        onBackends((language, backend) -> {
            var service = new ManagedSignals(Language.currentState(), language, true, () -> false, () -> { throw new IllegalStateException("denied request must not acquire signal transport"); });
            service.bind(program(language, backend)); service.authorizeLauncher();
            long usr2 = StdioHostAbi.load().signal("SIGUSR2");
            var failure = assertThrows(RuntimeFault.class, () -> service.install(usr2, -4L, ManagedAddress.nullAddress())); assertTrue(failure.getMessage().contains("_JAVA_SR_SIGNUM=64"));
            assertThrows(RuntimeFault.class, () -> service.install(64L, -4L, ManagedAddress.nullAddress())); service.close();
        });
    }
    @Test public void preparedCodeRetainsDispatcherReachedThroughSignalInstallation() {
        for (boolean recordSignal : new boolean[] {false, true}) try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var code = Program.prepareCode(language, module(recordSignal, descriptor), List.of("install"));
                var target = new SignalDispatchRoot(language, code.newInstance(language)).getCallTarget();
                var owner = Language.currentState();
                var address = owner.getNativeAllocations().malloc(4L);
                address.writeWord8(0, 0L);
                owner.getThreads().enterCurrent();
                try { target.call(address, 2L); assertEquals(recordSignal ? 2L : 0L, address.readWord8(0)); }
                finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); }
            } finally { context.leave(); }
        }
    }
    @Test public void dispatcherPassesEachActualSignalThroughTheOriginalBoxedCIntShape() throws Exception {
        onBackends((language, backend) -> {
            var owner = Language.currentState(); var program = program(language, backend, true, true, descriptor); var target = new SignalDispatchRoot(language, program).getCallTarget();
            var address = owner.getNativeAllocations().malloc(128L); owner.getThreads().enterCurrent();
            try { for (long signal : new long[] {1L, 2L, 3L, 10L, 12L, 15L, 24L, 25L}) { target.call(address, signal); assertEquals(signal, address.readWord8(0)); } }
            finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); }
        });
    }
    @Test public void dispatcherKeepsPointerValidationBeforeTheSignalCastAndRejectsWithoutWriting() throws Exception {
        onBackends((language, backend) -> {
            var owner = Language.currentState(); var target = new SignalDispatchRoot(language, program(language, backend, true, true, descriptor)).getCallTarget();
            var address = owner.getNativeAllocations().malloc(128L); address.writeWord8(0, 77); owner.getThreads().enterCurrent();
            try {
                var pointerFailure = assertThrows(RuntimeFault.class, () -> target.call("not an address", null)); assertEquals("Expected a managed literal Addr# constructor field", pointerFailure.getMessage());
                var nullFailure = assertThrows(NullPointerException.class, () -> target.call(address, null)); assertEquals("Signal number must not be null", nullFailure.getMessage());
                for (var number : List.of(2, "signal")) assertThrows(ClassCastException.class, () -> target.call(address, number));
                assertEquals(77L, address.readWord8(0), "invalid arguments cannot enter the writing guest dispatcher"); ThreadInventoryCoreEvidence.released(language);
                target.call(address, 12L); assertEquals(12L, address.readWord8(0)); ThreadInventoryCoreEvidence.released(language);
            } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); }
        });
    }
    @Test public void dispatcherPreservesTheFirstInstalledSignalDelivery() throws Exception {
        onBackends((language, backend) -> {
            var owner = Language.currentState(); var program = program(language, backend, true, true, descriptor); var target = new SignalDispatchRoot(language, program).getCallTarget();
            var address = owner.getNativeAllocations().malloc(128L); owner.getThreads().enterCurrent();
            try {
                target.call(address, 2L); assertEquals(2L, address.readWord8(0)); ThreadInventoryCoreEvidence.install(List.of(target));
                var calls = ThreadInventoryCoreEvidence.interpretedCalls(List.of(target)); long compiled = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                target.call(address, 25L); // First call after installation; no settling call.
                assertEquals(25L, address.readWord8(0)); assertTrue(ThreadInventoryCoreEvidence.valid(target), backend + " dispatcher remains installed");
                assertEquals(calls, ThreadInventoryCoreEvidence.interpretedCalls(List.of(target))); assertEquals(compiled + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                ThreadInventoryCoreEvidence.released(language);
            } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); }
        });
    }
    @Test public void nativeEventsCrossTheActualJvmBoundaryInAnIsolatedProcess() throws Exception {
        boolean linux = System.getProperty("os.name").equals("Linux");
        boolean darwin = System.getProperty("os.name").startsWith("Mac");
        assumeTrue(linux && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")) || darwin);
        var classpath = Objects.requireNonNull(System.getProperty("thc.testRuntimeClasspath"), "test runner must expose its child JVM classpath");
        var directory = new File(System.getProperty("thc.projectRoot"), "build/process-signals"); directory.mkdirs();
        for (var mode : linux ? List.of("relocated", "unrelocated", "reduced-signals-disabled") : List.of("posix-safe", "reduced-signals-disabled")) {
            var output = File.createTempFile("jvm-transport-", ".log", directory); var command = new ArrayList<>(List.of(new File(System.getProperty("java.home"), "bin/java").getPath(), "-Xrs", "--enable-native-access=ALL-UNNAMED"));
            if (mode.equals("reduced-signals-disabled")) command.add("-XX:-ReduceSignalUsage"); command.addAll(List.of("-cp", classpath, ProcessSignalJvmProbe.class.getName(), mode));
            var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output); builder.environment().remove("LD_PRELOAD");
            if (mode.equals("relocated")) builder.environment().put("_JAVA_SR_SIGNUM", "64"); else builder.environment().remove("_JAVA_SR_SIGNUM");
            var child = builder.start();
            try {
                assertTrue(child.waitFor(30, TimeUnit.SECONDS), "native signal child timed out: " + output); assertEquals(0, child.exitValue(), Files.readString(output.toPath()));
                assertTrue(Files.readString(output.toPath()).contains(switch (mode) { case "reduced-signals-disabled" -> "Later VM option disables reduced signal usage"; case "unrelocated" -> "Unrelocated JVM retains SIGUSR2"; case "posix-safe" -> "JVM received six selected-header signals; VM-owned XFSZ and unverified USR2 denied"; default -> "JVM received signals 1,2,3,10,12,15,24,25"; }), Files.readString(output.toPath()));
            } finally { if (child.isAlive()) child.destroyForcibly().waitFor(); }
        }
    }
    @Test public void standaloneScriptReservesSignalWithoutOverwritingUserSettings(@TempDir Path directory) throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux")); var java = directory.resolve("bin/java").toFile(); java.getParentFile().mkdirs();
        Files.writeString(java.toPath(), "#!/bin/sh\nprintf 'reserved=%s\\n' \"\u0024{_JAVA_SR_SIGNUM-unset}\"\n"); assertTrue(java.setExecutable(true));
        var script = new File(System.getProperty("thc.projectRoot"), "build/scripts/thc");
        for (var setting : Arrays.asList(null, "64", "12", "")) {
            var builder = new ProcessBuilder("sh", script.getPath()).redirectErrorStream(true); builder.environment().put("JAVA_HOME", directory.toString());
            if (setting == null) builder.environment().remove("_JAVA_SR_SIGNUM"); else builder.environment().put("_JAVA_SR_SIGNUM", setting);
            var child = builder.start();
            try {
                assertTrue(child.waitFor(10, TimeUnit.SECONDS)); var output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (setting == null || setting.equals("64")) { assertEquals(0, child.exitValue(), output); assertEquals("reserved=64\n", output); }
                else { assertNotEquals(0, child.exitValue(), output); assertTrue(output.contains("incompatible setting was preserved"), output); assertFalse(output.contains("reserved="), output); }
            } finally { if (child.isAlive()) child.destroyForcibly().waitFor(); }
        }
    }
    @Test public void nativeChildControlsAndGhcActionOracleHaveMatchingInputs() throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux") && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")),
            "Native oracle fixture requires Linux x86_64");
        var root = new File(System.getProperty("thc.projectRoot"));
        var manifest = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/process-signals/manifest.json").toPath()));
        OriginalStdioChecks.hashes(root, manifest.get("inputHashes"), Set.of("t/fixtures/compiler/ProcessSignalsNative.hs", "src/main/c/native-process-signal-api.c", "src/test/c/native-process-signals-test.c",
            "src/test/resources/core/original-signal-install-descriptor.json", "src/test/resources/core/original-unix-signal-install-descriptor.json"), null);
        OriginalStdioChecks.hashes(root, manifest.get("artifactHashes"), Set.of("build/process-signals/oracle.txt", "build/process-signals/native-controls.txt"), "build/process-signals/");
        assertEquals("[(1,[-1,-2,-4,-5]),(2,[-1,-2,-4,-5]),(3,[-1,-2,-4,-5]),(10,[-1,-2,-4,-5]),(12,[-1,-2,-4,-5]),(15,[-1,-2,-4,-5]),(24,[-1,-2,-4,-5]),(25,[-1,-2,-4,-5])]\n", Files.readString(new File(root, "build/process-signals/oracle.txt").toPath()));
        assertEquals("41 isolated native signal controls passed\n", Files.readString(new File(root, "build/process-signals/native-controls.txt").toPath()));
    }
}
