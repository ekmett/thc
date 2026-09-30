// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.interop.ExceptionType;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import thc.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Genuine compiler-produced Exception dictionaries; no synthetic SomeException
 * constructors or representation witnesses occur in this integration test. */
@Tag("foreign-exceptions-full-core")
@SuppressWarnings("unchecked")
public class ForeignExceptionTest {
    private Map<String, Object> source(String stage) throws Exception { return ForeignExceptionFixtureSupport.source(stage); }
    private Map<String, Object> linked(Map<String, Object> source, String id) {
        var linked = CoreModules.reachable(source, id, true);
        // Direct test programs need the native-owner linkage normally done by Language.instantiate.
        for (var link : (List<PackageScalarLink>) linked.get("packageScalarLinks"))
            Language.currentState().getPackageCbits().link(link);
        return linked;
    }
    private Context context() { return Context.newBuilder("thc", "js").allowExperimentalOptions(true).allowNativeAccess(true).allowPolyglotAccess(PolyglotAccess.ALL)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build(); }
    private Map<String, Object> with(Map<String, Object> source, String key, Object value) { var result = new LinkedHashMap<>(source); result.put(key, value); return result; }
    private Map<String, Object> without(Map<String, Object> source, String key) { var result = new LinkedHashMap<>(source); result.remove(key); return result; }
    private List<PolyglotException.StackFrame> stack(PolyglotException failure, int limit) {
        var frames = new ArrayList<PolyglotException.StackFrame>(); for (var frame : failure.getPolyglotStackTrace()) { if (frames.size() == limit) break; frames.add(frame); } return frames;
    }
    @Test public void foreignExecutionRequiresAnUnambiguousGenuineLinkedBridge() throws Exception {
        var source = source("post"); String id = "main:ForeignExceptionAudit.caught"; var proofs = (List<Map<String, Object>>) source.get("foreignExceptionBridges"); assertEquals(1, proofs.size()); var proof = proofs.getFirst();
        for (var invalid : List.of(without(source, "foreignExceptionBridges"), with(source, "foreignExceptionBridgeUnit", "missing-runtime-unit"), with(source, "foreignExceptionBridges", List.of(proof, proof)))) {
            var failure = assertThrows(RuntimeFault.class, () -> CoreModules.reachable(invalid, id, true));
            assertTrue(failure.getMessage().contains("Foreign execution requires") || failure.getMessage().contains("ambiguous foreign exception bridge"), failure.getMessage());
        }
        var linked = without(CoreModules.reachable(source, id, true), "selectedForeignExceptionBridge");
        try (Context context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (String backend : List.of("ast", "bytecode")) {
                    var failure = assertThrows(RuntimeFault.class, () -> { ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked); program.entryValue(id); });
                    assertTrue(failure.getMessage().contains("linked genuine THC.Exception"), failure.getMessage());
                }
            } finally { context.leave(); }
        }
    }
    @Test public void genuineCatchCleanupInspectionAndLazyOrdinaryExceptions() throws Exception {
        var cases = new LinkedHashMap<String, Long>(); cases.put("caught", 42L); cases.put("cleanup", 142L); cases.put("metadata", 19L); cases.put("displayIsInert", 17L); cases.put("parseCleanup", 142L); cases.put("lazyOrdinary", 99L); cases.put("ordinary", 0L);
        for (String stage : List.of("pre", "post")) {
            var source = source(stage);
            for (String backend : List.of("ast", "bytecode")) try (Context context = context()) {
                context.initialize("thc"); context.initialize("js"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var entry : cases.entrySet()) {
                        String id = "main:ForeignExceptionAudit." + entry.getKey(); var linked = with(linked(source, id), "instrument", true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked); var function = context.asValue(new EntryValue(program, id, 1));
                        for (int i = 0; i < 2; i++) assertEquals(entry.getValue().longValue(), function.execute(0L).asLong(), stage + "/" + backend + "/" + entry.getKey());
                        assertTrue(function.invokeMember("compile").asBoolean()); assertEquals(entry.getValue().longValue(), function.execute(0L).asLong(), stage + "/" + backend + "/" + entry.getKey() + " installed");
                        assertEquals(0L, program.diagnostics().get("unsupportedTraps"));
                    }
                } finally { context.leave(); }
            }
        }
    }
    @Test public void ordinarySomeExceptionRethrowRetainsExactForeignIdentityAfterCatch() throws Exception {
        for (String stage : List.of("pre", "post")) {
            var source = source(stage);
            for (String backend : List.of("ast", "bytecode")) try (Context context = context()) {
                context.initialize("thc"); context.initialize("js"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var original = context.eval("js", "globalThis.thcFailure = new Error('identity retained')");
                    for (String entry : List.of("main:ForeignExceptionAudit.rethrowNow", "main:ForeignExceptionAudit.rethrowLater")) {
                        var linked = linked(source, entry); ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                        var function = context.asValue(new EntryValue(program, entry, 1)); assertEquals(8L, function.execute(1L).asLong()); assertTrue(function.invokeMember("compile").asBoolean());
                        var failure = assertThrows(PolyglotException.class, () -> function.execute(0L)); assertTrue(failure.isGuestException());
                        assertEquals(original, failure.getGuestObject(), stage + "/" + backend + "/" + entry + " exact identity: " + failure + " / " + stack(failure, 12));
                    }
                } finally { context.leave(); }
            }
        }
    }
    private Map<String, Object> binding(String id, List<?> body) {
        var result = new LinkedHashMap<String, Object>(); result.put("id", id); result.put("name", id); result.put("type", "opaque primitive control"); result.put("lifted", true); result.put("arity", 0); result.put("expr", body); return result;
    }
    @Test public void registeredProjectorDoesNotInspectRawPrimitiveValuesOrBottoms() throws Exception {
        var source = source("post");
        for (String backend : List.of("ast", "bytecode")) try (Context context = context()) {
            context.initialize("thc"); context.initialize("js"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var linked = linked(source, "main:ForeignExceptionAudit.caught");
                ExecutableProgram registered = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                assertEquals(42L, context.asValue(new EntryValue(registered, "main:ForeignExceptionAudit.caught", 1)).execute(0L).asLong()); assertFalse(Language.currentState(null).getForeignExceptionRegistry().snapshot().isEmpty());
                for (boolean bottom : new boolean[] {false, true}) {
                    List<?> payload = bottom ? List.of("var", "payload") : List.of("lit", "int", "17"); var data = new LinkedHashMap<String, Object>();
                    data.put("schema", 1L); data.put("ghc", "9.14.1"); data.put("module", "PrimitiveControl"); data.put("constructors", List.of());
                    data.put("bindings", List.of(binding("payload", payload), binding("entry", List.of("app", List.of("prim", "raise#"), List.of(List.of("var", "payload")), List.of(true)))));
                    ExecutableProgram raw = backend.equals("ast") ? new Program(language, data) : new BytecodeProgram(language, data); var value = raw.entryValue("payload");
                    for (int i = 0; i < 2; i++) {
                        assertThrows(PolyglotException.class, () -> context.asValue(new EntryValue(raw, "entry", 0)).execute());
                        if (bottom) assertEquals(0, ((Thunk) value).getState(), "Previously registered projector must not force raw bottom");
                    }
                    var failure = assertThrows(GuestException.class, () -> Calls.target(raw.hostEntryTarget(), new Object[] {raw.entryValue("entry"), new Object[0]}));
                    assertFalse(failure.getSomeException(), "Memoized primitive failures retain opaque provenance"); assertEquals(0L, raw.diagnostics().get("blackholes"));
                }
            } finally { context.leave(); }
        }
    }
    private Value entry(String name, String backend, Map<String, Object> source, Context context, Language language) {
        String id = "main:ForeignExceptionAudit." + name; var linked = linked(source, id);
        ExecutableProgram program = backend.equals("ast") ? new Program(language, linked, true) : new BytecodeProgram(language, linked, true); return context.asValue(new EntryValue(program, id, 1));
    }
    @Test public void explicitMetadataSupportsReverseEntryAndNewFailureCleanup() throws Exception {
        var source = source("post");
        for (String backend : List.of("ast", "bytecode")) try (Context context = context()) {
            context.initialize("thc"); context.initialize("js"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var callback = entry("ordinary", backend, source, context, language);
                assertEquals(31L, callback.execute(31L).asLong()); assertTrue(callback.invokeMember("compile").asBoolean()); var count = new AtomicInteger();
                var outer = new MetadataProtocolFailure(() -> { count.incrementAndGet(); assertEquals(31L, callback.execute(31L).asLong(), "Metadata reverse entry must retain guest behavior"); return "reentered"; });
                context.getBindings("js").putMember("thcHostFailure", new ForeignThrower(outer)); var inspect = entry("hostMetadata", backend, source, context, language);
                assertEquals(9L, inspect.execute(0L).asLong()); assertEquals(1, count.get(), "Length and indexed reads share one published text snapshot");
                assertTrue(inspect.invokeMember("compile").asBoolean()); assertEquals(9L, inspect.execute(0L).asLong()); assertEquals(2, count.get());
                var metadataFailure = new MetadataProtocolFailure(() -> "secondary metadata failure");
                context.getBindings("js").putMember("thcHostFailure", new ForeignThrower(new MetadataProtocolFailure(() -> { throw metadataFailure; })));
                var cleanup = entry("hostMetadataCleanup", backend, source, context, language); assertEquals(142L, cleanup.execute(0L).asLong(), "A thrown metadata failure is newly catchable after cleanup");
                assertTrue(cleanup.invokeMember("compile").asBoolean()); assertEquals(142L, cleanup.execute(0L).asLong()); var caught = entry("hostCatch", backend, source, context, language);
                for (var kind : List.of(ExceptionType.EXIT, ExceptionType.INTERRUPT)) {
                    context.getBindings("js").putMember("thcHostFailure", new ForeignThrower(new MetadataProtocolFailure(kind, () -> "excluded control")));
                    var failure = assertThrows(PolyglotException.class, () -> caught.execute(0L));
                    if (kind == ExceptionType.EXIT) assertTrue(failure.isExit(), failure + " / " + stack(failure, 8)); else assertTrue(failure.isInterrupted(), failure + " / " + stack(failure, 8));
                }
            } finally { context.leave(); }
        }
    }
}
