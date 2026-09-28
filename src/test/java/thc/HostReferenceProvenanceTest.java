// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.interop.InteropLibrary;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

/** Re-exporting a callable must not replace its own execution policy. */
class HostReferenceProvenanceTest {
    private static final Map<String, Object> INTEGER = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private static final Map<String, Object> CLOSURE = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private static final Map<String, Object> LAZY_CLOSURE = with(CLOSURE, "evaluated", false);
    private static final Map<String, Object> LAZY_TUPLE = map("kind", "unknown", "aggregate", "unboxed-tuple",
        "components", list(LAZY_CLOSURE), "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private static final Map<String, Object> STATE = map("kind", "void", "primReps", list(), "evaluated", true);
    private static Map<String, Object> binder(String id, Map<String, Object> proof) {
        return map("id", id, "name", id, "lifted", proof.get("kind").equals("closure"), "rep", proof);
    }
    private static List<Object> variable(String id, Map<String, Object> proof) { return list("var", id, map("rep", proof)); }
    private static List<Object> trace(String marker, List<Object> body, Map<String, Object> proof) {
        return list("case", list("app", list("prim", "traceEvent#"),
            list(list("lit", "string-bytes", HexFormat.of().formatHex(marker.getBytes(StandardCharsets.UTF_8))), list("void", map("rep", STATE))),
            list(false, false), false, false, map("rep", STATE)), "traced", list(list("default", null, list(), body)),
            map("rep", proof, "binder", binder("traced", STATE)));
    }
    private static Value entry(Context context, String backend, boolean async, Map<String, Object> input,
            Map<String, Object> result, List<Object> body) {
        return context.eval("thc", Json.stringify(map("backend", backend, "asyncExceptions", async,
            "entry", "host:Provenance.entry", "modules", list(module(input, result, body)))));
    }
    private static Map<String, Object> module(Map<String, Object> input, Map<String, Object> result, List<Object> body) {
        var lambda = list("lam", list(binder("input", input)), body, map("rep", CLOSURE, "resultRep", result));
        var binding = map("id", "host:Provenance.entry", "name", "entry", "arity", 1, "lifted", true, "rep", CLOSURE, "expr", lambda);
        return map("schema", 1, "ghc", "9.14.1", "unit", "host", "module", "Provenance", "bindings", list(binding),
            "constructors", list(map("id", "host:Provenance.Single", "name", "Single", "kind", "unboxed-tuple", "arity", 1)));
    }
    private static List<Object> callable(boolean partial) {
        var parameters = partial ? list(binder("prefix", INTEGER), binder("value", INTEGER)) : list(binder("value", INTEGER));
        var lambda = list("lam", parameters, trace("call", variable("value", INTEGER), INTEGER), map("rep", CLOSURE, "resultRep", INTEGER));
        return partial ? list("app", lambda, list(variable("input", INTEGER)), list(false), false, false, map("rep", CLOSURE)) : lambda;
    }
    private static List<Object> delayed() {
        var binding = map("id", "delayed", "name", "delayed", "lifted", true, "rep", LAZY_CLOSURE,
            "expr", trace("force", variable("input", LAZY_CLOSURE), LAZY_CLOSURE));
        var tuple = list("app", list("con", "host:Provenance.Single", 1), list(variable("delayed", LAZY_CLOSURE)),
            list(true), true, true, map("rep", LAZY_TUPLE));
        return list("let", false, list(binding), tuple, map("rep", LAZY_TUPLE));
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void rawEntryRejectsAnotherContextBeforeExecutionOrCompilation(String backend) throws Exception {
        for (boolean hosted : new boolean[]{false, true}) {
            var ownerOutput = new ByteArrayOutputStream();
            var otherOutput = new ByteArrayOutputStream();
            try (var engine = Engine.create()) {
                var builder = Context.newBuilder("thc").engine(engine).err(ownerOutput);
                if (hosted) builder.allowExperimentalOptions(true).allowCreateThread(true).option("thc.ThreadHosting", "loom");
                try (var owner = builder.build(); var other = Context.newBuilder("thc").engine(engine).err(otherOutput).build()) {
                    owner.initialize("thc"); other.initialize("thc");
                    final EntryValue raw;
                    final Value value;
                    owner.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var source = module(INTEGER, INTEGER, trace("owner-entry", variable("input", INTEGER), INTEGER));
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, source, false) : new BytecodeProgram(language, source, false);
                        raw = new EntryValue(program, "host:Provenance.entry", 1);
                        value = owner.asValue(raw);
                    } finally { owner.leave(); }
                    var interop = InteropLibrary.getUncached();
                    other.enter();
                    try {
                        assertTrue(interop.isExecutable(raw));
                        assertTrue(interop.isMemberReadable(raw, "diagnostics"));
                        assertAll(
                            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                                () -> interop.execute(raw, 17L)).getMessage().contains("another context")),
                            () -> assertTrue(assertThrows(IllegalArgumentException.class,
                                () -> interop.invokeMember(raw, "compile")).getMessage().contains("another context")));
                        assertEquals(0, ownerOutput.size());
                        assertEquals(0, otherOutput.size());
                        assertNull(Language.currentState().getThreads().pollState(Thread.currentThread()).getCurrent());
                        // A normal polyglot Value enters its own context even while another is current.
                        assertEquals(23L, value.execute(23L).asLong());
                        assertEquals(0, otherOutput.size());
                    } finally { other.leave(); }
                    assertTrue(ownerOutput.toString(StandardCharsets.UTF_8).contains("owner-entry"));
                    owner.enter();
                    try { assertEquals(29L, interop.execute(raw, 29L)); }
                    finally { owner.leave(); }
                    owner.close();
                    assertThrows(IllegalStateException.class, () -> value.execute(31L));
                    other.enter();
                    try { assertThrows(IllegalArgumentException.class, () -> interop.execute(raw, 31L)); }
                    finally { other.leave(); }
                    assertEquals(0, otherOutput.size());
                }
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void rawRunIoRejectsAnotherContextWithoutConsumingTheLifecycle(String backend) throws Exception {
        var ownerOutput = new ByteArrayOutputStream();
        var otherOutput = new ByteArrayOutputStream();
        try (var engine = Engine.create(); var owner = Context.newBuilder("thc").engine(engine).err(ownerOutput).build();
                var other = Context.newBuilder("thc").engine(engine).err(otherOutput).build()) {
            owner.initialize("thc"); other.initialize("thc");
            final EntryValue raw;
            final Value value;
            owner.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var unit = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
                var result = map("kind", "unknown", "aggregate", "unboxed-tuple", "components", list(STATE, unit),
                    "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
                String unitId = "ghc-internal:GHC.Internal.Tuple.()";
                var body = list("app", list("con", "StateUnit", 2),
                    list(list("void", map("rep", STATE)), list("con", unitId, 0, map("rep", unit))),
                    list(false, true), true, true, map("rep", result));
                var main = module(STATE, result, trace("owner-main", body, result));
                var shutdown = module(STATE, result, trace("owner-shutdown", body, result));
                var shutdownBinding = with((Map<String, Object>) ((List<?>) shutdown.get("bindings")).getFirst(),
                    "id", "host:Provenance.shutdown", "name", "shutdown");
                var source = with(main, "bindings", list(((List<?>) main.get("bindings")).getFirst(), shutdownBinding),
                    "constructors", list(map("id", "StateUnit", "name", "StateUnit", "kind", "unboxed-tuple", "arity", 2),
                        map("id", unitId, "name", "()", "kind", "boxed", "arity", 0,
                            "strictFields", list(), "fieldLifted", list(), "fieldReps", list())));
                ExecutableProgram program = backend.equals("ast") ? new Program(language, source, false) : new BytecodeProgram(language, source, false);
                var proof = CoreRepresentations.parse(result);
                raw = new EntryValue(program, "host:Provenance.entry", 1, null, proof, language,
                    "host:Provenance.shutdown", proof, false, null, null);
                value = owner.asValue(raw);
            } finally { owner.leave(); }
            var interop = InteropLibrary.getUncached();
            other.enter();
            try {
                assertTrue(interop.isMemberInvocable(raw, "runIO"));
                assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> interop.invokeMember(raw, "runIO")).getMessage().contains("another context"));
                assertEquals(0, ownerOutput.size()); assertEquals(0, otherOutput.size());
                assertNull(Language.currentState().getThreads().pollState(Thread.currentThread()).getCurrent());
            } finally { other.leave(); }
            assertTrue(value.invokeMember("runIO").asBoolean());
            var output = ownerOutput.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("owner-main")); assertTrue(output.contains("owner-shutdown"));
            assertThrows(PolyglotException.class, () -> value.invokeMember("runIO"));
            assertEquals(output, ownerOutput.toString(StandardCharsets.UTF_8));
            assertEquals(0, otherOutput.size());
        }
    }

    @Test void nestedPolicyCannotUpgradeItsCallerAndRestoresItsAdmission() throws Exception {
        var threads = new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), ignored -> {});
        threads.enterCurrent(null, false, false, null);
        try {
            var outer = threads.currentIdentity();
            threads.enterCurrent(null, false, true, null);
            try {
                assertSame(outer, threads.currentIdentity());
                assertFalse(threads.pollState(Thread.currentThread()).getCurrent().getExternalAsync());
                threads.setCurrentExternalAsync(true);
                assertFalse(threads.pollState(Thread.currentThread()).getCurrent().getExternalAsync());
                CompletableFuture.runAsync(() -> assertThrows(UnsupportedCore.class,
                    () -> threads.send(outer, "nested nonresumable caller"))).get(5, TimeUnit.SECONDS);
            } finally { threads.leaveCurrent(); }
            assertSame(outer, threads.currentIdentity());
            assertFalse(threads.pollState(Thread.currentThread()).getCurrent().getExternalAsync());
        } finally { threads.leaveCurrent(); }
        assertNull(threads.pollState(Thread.currentThread()).getCurrent());

        threads.enterCurrent(null, false, true, null);
        try {
            var outer = threads.currentIdentity();
            threads.enterCurrent(null, false, false, null);
            try {
                assertFalse(threads.pollState(Thread.currentThread()).getCurrent().getExternalAsync());
                threads.enterCurrent(null, false, true, null);
                try {
                    assertSame(outer, threads.currentIdentity());
                    assertFalse(threads.pollState(Thread.currentThread()).getCurrent().getExternalAsync());
                    threads.setCurrentExternalAsync(true);
                    assertFalse(threads.pollState(Thread.currentThread()).getCurrent().getExternalAsync());
                } finally { threads.leaveCurrent(); }
                assertFalse(threads.pollState(Thread.currentThread()).getCurrent().getExternalAsync());
            } finally { threads.leaveCurrent(); }
            assertTrue(threads.pollState(Thread.currentThread()).getCurrent().getExternalAsync());
        } finally { threads.leaveCurrent(); }
        assertNull(threads.pollState(Thread.currentThread()).getCurrent());
    }
    @Test void queuedDeliveryWaitsForResumablePhaseWithoutLosingSelfDelivery() throws Exception {
        var threads = new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), ignored -> {});
        threads.enterCurrent(null, false, true, null);
        AsyncRequest queued = null;
        try {
            var identity = threads.currentIdentity();
            queued = CompletableFuture.supplyAsync(() -> threads.send(identity, "queued during force")).get(5, TimeUnit.SECONDS);
            boolean previous = threads.setCurrentExternalAsync(false);
            try {
                assertNull(threads.poll(null, true));
                assertFalse(threads.interruptibleForeignPending());
                assertEquals(AsyncRequestState.PENDING, queued.getState());
                var self = threads.send(identity, "synchronous self delivery");
                assertSame(self, threads.poll(null)); self.acknowledge();
                assertEquals(AsyncRequestState.PENDING, queued.getState());
            } finally { threads.setCurrentExternalAsync(previous); }
            assertSame(identity, threads.currentIdentity());
            assertSame(queued, threads.poll(null)); queued.acknowledge();
        } finally {
            if (queued != null && queued.getState() == AsyncRequestState.PENDING) queued.cancel();
            threads.leaveCurrent();
        }
        assertNull(threads.pollState(Thread.currentThread()).getCurrent());
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void mutVarApplicationThunkRetainsItsPolicyAtPublicForce(String backend) {
        for (boolean async : new boolean[]{false, true}) {
            var calls = new AtomicInteger();
            var output = new ByteArrayOutputStream() {
                private final StringBuilder line = new StringBuilder();
                @Override public void write(byte[] bytes, int start, int length) {
                    for (int i = start; i < start + length; i++) write(bytes[i] & 255);
                }
                @Override public void write(int value) {
                    super.write(value); line.append((char) value);
                    if (value == '\n') {
                        String record = line.toString(); line.setLength(0);
                        if (!record.startsWith("[thc trace event] ")) return;
                        calls.incrementAndGet();
                        var threads = Language.currentState().getThreads();
                        assertEquals(async, threads.pollState(Thread.currentThread()).getCurrent().getExternalAsync());
                    }
                }
            };
            try (var context = Context.newBuilder("thc").err(output).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var source = module(INTEGER, CLOSURE, trace("force", callable(false), CLOSURE));
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, source, async) : new BytecodeProgram(language, source, async);
                    var site = new MutVarModifySite(language, new Metrics(false), async);
                    var application = site.application(program.entryValue("host:Provenance.entry"), 7L);
                    var transport = new Program(language, map("bindings", list()), !async);
                    var callable = context.asValue(new HostReference(Language.currentState(), application,
                        CoreRepresentations.parse(LAZY_CLOSURE), transport));
                    assertEquals(19L, callable.execute(19L).asLong());
                    assertEquals(2, calls.get());
                    assertEquals(async, application.getAsynchronousExceptions());
                    assertEquals(async, site.selector(application).getAsynchronousExceptions());
                    assertNull(Language.currentState().getThreads().pollState(Thread.currentThread()).getCurrent());
                } finally { context.leave(); }
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void independentIdentityEntriesKeepCallableAndThunkExecutionPolicies(String backend) {
        String opposite = backend.equals("ast") ? "bytecode" : "ast";
        for (boolean async : new boolean[]{false, true}) for (boolean hosted : new boolean[]{false, true}) {
            var calls = new AtomicInteger(); var forces = new AtomicInteger();
            var output = new ByteArrayOutputStream() {
                private final StringBuilder line = new StringBuilder();
                @Override public void write(byte[] bytes, int start, int length) {
                    for (int i = start; i < start + length; i++) write(bytes[i] & 255);
                }
                @Override public void write(int value) {
                    super.write(value); line.append((char) value);
                    if (value == '\n') {
                        String record = line.toString(); line.setLength(0);
                        if (!record.startsWith("[thc trace event] ")) return;
                        boolean forcing = record.contains("force");
                        boolean expected = forcing ? !async : async;
                        (forcing ? forces : calls).incrementAndGet();
                        var threads = Language.currentState().getThreads();
                        var slot = Objects.requireNonNull(threads.pollState(Thread.currentThread()).getCurrent());
                        assertEquals(hosted, Thread.currentThread().isVirtual());
                        assertEquals(expected, slot.getExternalAsync(), forcing ? "Thunk root policy" : "Callable root policy");
                        try {
                            CompletableFuture.runAsync(() -> {
                                if (expected) {
                                    var request = threads.send(slot.getIdentity(), "provenance probe");
                                    try { assertFalse(request.getForceSelf()); assertEquals(AsyncRequestState.PENDING, request.getState()); }
                                    finally { assertTrue(request.cancel()); }
                                } else assertThrows(UnsupportedCore.class, () -> threads.send(slot.getIdentity(), "nonresumable probe"));
                            }).get(5, TimeUnit.SECONDS);
                        } catch (Exception failure) { throw new AssertionError(failure); }
                    }
                }
            };
            var builder = Context.newBuilder("thc").err(output);
            if (hosted) builder.allowExperimentalOptions(true).allowCreateThread(true).option("thc.ThreadHosting", "loom");
            try (var context = builder.build()) {
                var identity = entry(context, opposite, !async, LAZY_CLOSURE, LAZY_CLOSURE, variable("input", LAZY_CLOSURE));
                var sourceIdentity = entry(context, backend, async, LAZY_TUPLE, LAZY_TUPLE, variable("input", LAZY_TUPLE));
                var lazify = entry(context, opposite, !async, LAZY_CLOSURE, LAZY_TUPLE, delayed());
                for (boolean partial : new boolean[]{false, true}) {
                    var original = entry(context, backend, async, INTEGER, CLOSURE, callable(partial)).execute(7L);
                    assertEquals(19L, original.execute(19L).asLong());
                    var exported = identity.execute(original);
                    assertEquals(original, exported);
                    assertEquals(23L, exported.execute(23L).asLong());
                    int before = forces.get();
                    var lazy = sourceIdentity.execute(lazify.execute(original)).getArrayElement(0);
                    assertEquals(before, forces.get(), "Transport must not force a callable thunk");
                    assertEquals(29L, lazy.execute(29L).asLong());
                    assertEquals(31L, lazy.execute(31L).asLong());
                    assertEquals(before + 1, forces.get(), "A callable thunk is forced only once");
                }
                assertEquals(8, calls.get()); assertEquals(2, forces.get());
                context.enter();
                try { assertNull(Language.currentState().getThreads().pollState(Thread.currentThread()).getCurrent()); }
                finally { context.leave(); }
            }
        }
    }
}
